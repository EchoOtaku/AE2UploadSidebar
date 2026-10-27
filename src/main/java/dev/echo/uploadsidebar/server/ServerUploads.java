package dev.echo.uploadsidebar.server;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.AEBasePart;

import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.core.ProviderIdentity;
import dev.echo.uploadsidebar.core.UploadTickets;
import dev.echo.uploadsidebar.net.SidebarPackets;
import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-thread-only uploads bound to a real menu, grid and provider object, never a list index. */
public final class ServerUploads {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();

    private ServerUploads() {}

    public static SidebarPackets.Providers query(ServerPlayer player, SidebarPackets.Query request) {
        return querySnapshot(player, request, false).snapshot();
    }

    public static SidebarPackets.NamedProviders queryNamed(ServerPlayer player, SidebarPackets.NamedQuery request) {
        return querySnapshot(player, new SidebarPackets.Query(request.menuId(), request.requestId()), true);
    }

    private static SidebarPackets.NamedProviders querySnapshot(ServerPlayer player, SidebarPackets.Query request,
                                                               boolean includeIdentities) {
        Session session = session(player, request.menuId());
        if (session == null) return new SidebarPackets.NamedProviders(
                providers(request, List.of(), "终端已关闭、不可用或网络未连接"), List.of());

        try {
            PlayerState state = PLAYERS.compute(player.getUUID(), (id, previous) ->
                    previous != null && previous.menu == session.menu ? previous : new PlayerState(session.menu));
            List<Target> targets = new ArrayList<>();
            Set<PatternContainer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            boolean truncated = false;
            for (PatternContainer provider : ProviderAccess.listAvailableProvidersFromGrid(session.grid)) {
                if (!seen.add(provider) || !validProvider(provider, session.grid)) continue;
                if (targets.size() == SidebarPackets.MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                targets.add(new Target(provider, session.grid));
            }

            List<SidebarPackets.Entry> entries = new ArrayList<>();
            List<SidebarPackets.Identity> identities = new ArrayList<>();
            for (UploadTickets.Ticket<Target> ticket : state.tickets.replaceSnapshot(session.menu, targets, Util.getMillis())) {
                PatternContainer provider = ticket.target().provider;
                var group = provider.getTerminalGroup();
                ItemStack icon = group != null && group.icon() != null ? group.icon().toStack(1) : ItemStack.EMPTY;
                entries.add(new SidebarPackets.Entry(ticket.token(),
                        boundedName(ProviderAccess.getProviderDisplayNameComponent(provider).getString()),
                        ProviderAccess.getAvailableSlots(provider),
                        ProviderAccess.getAccessiblePatternSlotCount(provider), icon));
                if (includeIdentities) {
                    ProviderIdentityResolver.resolve(provider).ifPresent(identity -> identities.add(
                            new SidebarPackets.Identity(ticket.token(), identity.key(), identity.location())));
                }
            }
            return new SidebarPackets.NamedProviders(
                    providers(request, entries, truncated ? "仅显示前 512 个可用供应器" : ""),
                    ProviderIdentity.uniqueKeys(identities, SidebarPackets.Identity::key));
        } catch (RuntimeException failure) {
            forget(player.getUUID());
            LOGGER.warn("Could not create upload sidebar snapshot", failure);
            return new SidebarPackets.NamedProviders(
                    providers(request, List.of(), "读取供应器失败，请重新打开终端"), List.of());
        }
    }

    public static SidebarPackets.Result upload(ServerPlayer player, SidebarPackets.Upload request) {
        Session session = session(player, request.menuId());
        if (session == null) return result(request, false, "终端已关闭、不可用或网络未连接");
        PlayerState state = PLAYERS.get(player.getUUID());
        if (state == null || state.menu != session.menu) return result(request, false, "目标列表已失效，请刷新");
        if (!state.rememberOperation(request.operationId())) return result(request, false, "已处理此上传请求");

        // Consumed before mutation: a rejected/expired/full-target click cannot later move an item on retry.
        Target target = state.tickets.consume(request.token(), session.menu, Util.getMillis()).orElse(null);
        if (target == null) return result(request, false, "目标已失效或此目标已被使用，请刷新");

        try {
            if (target.grid != session.grid || !validProvider(target.provider, session.grid)
                    || !containsIdentity(ProviderAccess.listAvailableProvidersFromGrid(session.grid), target.provider)) {
                return result(request, false, "原供应器已离线、被替换、隐藏或变满，请刷新");
            }
            List<Slot> encodedSlots = session.menu.getSlots(SlotSemantics.ENCODED_PATTERN);
            if (encodedSlots.size() != 1) return result(request, false, "无法确定已编码样板槽位");
            Slot sourceSlot = encodedSlots.getFirst();
            ItemStack source = sourceSlot.getItem();
            if (!sourceSlot.mayPickup(player) || source.isEmpty() || !PatternDetailsHelper.isEncodedPattern(source)) {
                return result(request, false, "请先编码样板，再点击供应器上传");
            }
            if (request.expectedPattern().isEmpty()
                    || !ItemStack.isSameItemSameComponents(source, request.expectedPattern())) {
                return result(request, false, "样板已改变，本次未上传，请重试");
            }

            ItemStack sourceBefore = source.copy();
            ItemStack one = sourceBefore.copyWithCount(1);
            InternalInventory inventory = target.provider.getTerminalPatternInventory();
            int destination = findEmptyAcceptingSlot(target.provider, inventory, one);
            if (destination < 0) return result(request, false, "所选供应器没有可接受此样板的空槽");

            return transferOne(session.menu, sourceSlot, inventory, destination, sourceBefore, one, request);
        } catch (RuntimeException failure) {
            // Never retry another target after a provider error. Its token is already consumed.
            LOGGER.warn("Upload sidebar target rejected an operation", failure);
            return result(request, false, "供应器处理异常，请检查样板槽后重试");
        }
    }

    public static void forget(UUID playerId) {
        PLAYERS.remove(playerId);
    }

    private static SidebarPackets.Result transferOne(PatternEncodingTermMenu menu, Slot sourceSlot,
                                                      InternalInventory inventory, int destination,
                                                      ItemStack before, ItemStack one,
                                                      SidebarPackets.Upload request) {
        ItemStack after = before.copy();
        after.shrink(1);
        // Do not trust a third-party simulation that modified either slot while selecting it.
        if (!ItemStack.matches(before, sourceSlot.getItem()) || !inventory.getStackInSlot(destination).isEmpty()) {
            return result(request, false, "库存状态已改变，本次未上传，请刷新");
        }
        try {
            // Never invoke the legacy uploader, which can redirect to another same-name provider.
            ItemStack remainder = inventory.insertItem(destination, one.copy(), false);
            if (!remainder.isEmpty()) {
                throw new IllegalStateException("Provider did not accept the simulated single-pattern insert");
            }
            if (!ItemStack.matches(inventory.getStackInSlot(destination), one)
                    || !ItemStack.matches(sourceSlot.getItem(), before)) {
                throw new IllegalStateException("An upload callback changed an unexpected inventory state");
            }
            sourceSlot.set(after.copy());
            sourceSlot.setChanged();
            if (observedTransfer(sourceSlot, inventory, destination, before, after, one)
                    != TransferRecovery.Action.COMPLETED) {
                throw new IllegalStateException("Single-pattern transfer did not reach its expected final state");
            }
            menu.broadcastChanges();
            return result(request, true, "已上传 1 张样板");
        } catch (RuntimeException failure) {
            LOGGER.warn("Upload callback failed; checking the exact source and destination slots", failure);
            TransferRecovery.Action action = observedTransfer(sourceSlot, inventory, destination, before, after, one);
            if (action == TransferRecovery.Action.COMPLETE_SOURCE) {
                // The target holds exactly our one item and the source is wholly unchanged.
                // Complete only the missing debit; do not insert again or select another provider.
                try {
                    sourceSlot.set(after.copy());
                    sourceSlot.setChanged();
                } catch (RuntimeException debitFailure) {
                    LOGGER.warn("Source debit callback failed; rechecking whether it committed", debitFailure);
                }
                action = observedTransfer(sourceSlot, inventory, destination, before, after, one);
            }
            if (action == TransferRecovery.Action.COMPLETED) {
                try {
                    menu.broadcastChanges();
                } catch (RuntimeException syncFailure) {
                    LOGGER.warn("Upload completed but its menu notification failed", syncFailure);
                }
                return result(request, true, "已上传 1 张样板（库存已核对）");
            }
            if (action == TransferRecovery.Action.UNCHANGED) {
                return result(request, false, "供应器处理失败，两边库存均未改变");
            }
            // An unrecognized third-party mutation is not safe to overwrite or roll back blindly.
            return result(request, false, "库存状态异常，已停止此请求；请检查源样板与目标槽");
        }
    }

    private static TransferRecovery.Action observedTransfer(Slot sourceSlot, InternalInventory inventory,
                                                            int destination, ItemStack before,
                                                            ItemStack after, ItemStack one) {
        try {
            ItemStack actualSource = sourceSlot.getItem();
            ItemStack actualDestination = inventory.getStackInSlot(destination);
            return TransferRecovery.classify(ItemStack.matches(actualSource, before),
                    ItemStack.matches(actualSource, after), actualDestination.isEmpty(),
                    ItemStack.matches(actualDestination, one));
        } catch (RuntimeException observationFailure) {
            LOGGER.warn("Unable to inspect upload slots after a failed callback", observationFailure);
            return TransferRecovery.Action.UNKNOWN;
        }
    }

    private static Session session(ServerPlayer player, int menuId) {
        if (!player.serverLevel().getServer().isSameThread()
                || player.isRemoved() || !player.isAlive() || player.isSpectator()
                || !(player.containerMenu instanceof PatternEncodingTermMenu menu)
                || menu.containerId != menuId || menu.getPlayer() != player
                || !menu.isValidMenu() || !menu.stillValid(player) || !liveMenuHost(menu.getTarget())) return null;
        IGridNode node = menu.getGridNode();
        if (node == null || !node.isActive() || menu.getLinkStatus() == null || !menu.getLinkStatus().connected()) return null;
        IGrid grid = node.getGrid();
        return grid == null ? null : new Session(menu, grid);
    }

    private static boolean liveMenuHost(Object host) {
        if (host instanceof ItemMenuHost<?> item) return item.isValid();
        if (host instanceof AEBasePart part) {
            return part.getHost() != null && part.getHost().isInWorld()
                    && part.getHost().getPart(part.getSide()) == part && liveBlockEntity(part.getBlockEntity());
        }
        if (host instanceof BlockEntity blockEntity) return liveBlockEntity(blockEntity);
        return false;
    }

    private static boolean liveBlockEntity(BlockEntity blockEntity) {
        return blockEntity != null && !blockEntity.isRemoved() && blockEntity.getLevel() != null
                && blockEntity.getLevel().hasChunkAt(blockEntity.getBlockPos())
                && blockEntity.getLevel().getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
    }

    private static boolean validProvider(PatternContainer provider, IGrid grid) {
        if (provider == null || provider.getGrid() != grid || !provider.isVisibleInTerminal()) return false;
        if (provider instanceof BlockEntity blockEntity && !liveBlockEntity(blockEntity)) return false;
        if (provider instanceof AEBasePart part && !liveMenuHost(part)) return false;
        InternalInventory inventory = provider.getTerminalPatternInventory();
        return inventory != null && inventory.size() > 0
                && ProviderAccess.getAvailableSlots(provider) > 0;
    }

    private static int findEmptyAcceptingSlot(PatternContainer provider, InternalInventory inventory, ItemStack one) {
        if (inventory == null) return -1;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (ProviderAccess.isAccessiblePatternSlot(provider, slot)
                    && inventory.getStackInSlot(slot).isEmpty() && inventory.getSlotLimit(slot) >= 1
                    && inventory.isItemValid(slot, one) && inventory.insertItem(slot, one.copy(), true).isEmpty()) return slot;
        }
        return -1;
    }

    private static boolean containsIdentity(List<PatternContainer> providers, PatternContainer target) {
        for (PatternContainer provider : providers) if (provider == target) return true;
        return false;
    }

    private static String boundedName(String name) {
        if (name.length() <= 256) return name;
        int end = Character.isHighSurrogate(name.charAt(255)) ? 255 : 256;
        return name.substring(0, end);
    }

    private static SidebarPackets.Providers providers(SidebarPackets.Query request, List<SidebarPackets.Entry> entries, String message) {
        return new SidebarPackets.Providers(request.menuId(), request.requestId(), entries, message);
    }

    private static SidebarPackets.Result result(SidebarPackets.Upload request, boolean success, String message) {
        return new SidebarPackets.Result(request.menuId(), request.operationId(), success, message);
    }

    private record Session(PatternEncodingTermMenu menu, IGrid grid) {}
    private record Target(PatternContainer provider, IGrid grid) {}

    private static final class PlayerState {
        private final PatternEncodingTermMenu menu;
        private final UploadTickets<Target> tickets = new UploadTickets<>();
        private final LinkedHashSet<Long> recentOperations = new LinkedHashSet<>();

        private PlayerState(PatternEncodingTermMenu menu) {
            this.menu = menu;
        }

        private boolean rememberOperation(long operationId) {
            if (!recentOperations.add(operationId)) return false;
            if (recentOperations.size() > 256) recentOperations.removeFirst();
            return true;
        }
    }
}
