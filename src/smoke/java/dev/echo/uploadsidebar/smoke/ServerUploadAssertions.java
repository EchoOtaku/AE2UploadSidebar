package dev.echo.uploadsidebar.smoke;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.PatternEncodingTerminalPart;
import dev.echo.uploadsidebar.net.SidebarPackets;
import dev.echo.uploadsidebar.server.ServerUploads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Real-inventory assertions, run only inside the dedicated, newly created smoke world. */
public final class ServerUploadAssertions {
    private static long sequence = 1000;

    private ServerUploadAssertions() {}

    public static void verify(ServerPlayer player, PatternEncodingTerminalPart terminal,
                              PatternProviderBlockEntity first, PatternProviderBlockEntity second) {
        require(player.containerMenu instanceof PatternEncodingTermMenu, "real encoding menu is open");
        PatternEncodingTermMenu menu = (PatternEncodingTermMenu) player.containerMenu;
        InternalInventory source = terminal.getLogic().getEncodedPatternInv();
        InternalInventory firstInventory = first.getLogic().getPatternInv();
        InternalInventory secondInventory = second.getLogic().getPatternInv();
        ItemStack sample = source.getStackInSlot(0).copyWithCount(1);
        require(PatternDetailsHelper.isEncodedPattern(sample), "fixture starts with an encoded pattern");
        require(firstInventory.size() == secondInventory.size() && firstInventory.size() > 1,
                "fixture has two equal-size providers");

        clear(firstInventory);
        clear(secondInventory);
        source.setItemDirect(0, sample.copyWithCount(3));
        firstInventory.setItemDirect(firstInventory.size() - 1, sample.copy());

        // If the implementation returns no rows or aliases tokens, this fails before any upload.
        SidebarPackets.Providers initial = query(player, menu);
        require(initial.entries().size() == 2,
                "query must return exactly two real network targets, got " + initial.entries().size()
                        + "; message=" + initial.message());
        require(!initial.entries().get(0).token().equals(initial.entries().get(1).token()),
                "two provider objects must receive distinct tokens");
        SidebarPackets.Entry firstEntry = firstEntry(initial, firstInventory);

        // A successful upload must move one, not the entire count-three source stack.
        SidebarPackets.Result success = upload(player, menu, firstEntry, sample);
        require(success.success(), "one-pattern upload succeeds: " + success.message());
        require(source.getStackInSlot(0).getCount() == 2, "success consumes exactly one source pattern");
        require(patternCount(firstInventory) == 2, "only the selected provider gains one pattern");
        require(patternCount(secondInventory) == 0, "success does not touch another provider");

        // Replaying a consumed token must not move a second item.
        rejectUnchanged(player, menu, firstEntry, sample, source, firstInventory, secondInventory,
                "replayed token");

        // Refreshing the target ticket must not make the same completed operation id reusable.
        SidebarPackets.Entry freshForReplay = firstEntry(query(player, menu), firstInventory);
        List<ItemStack> replaySource = snapshot(source);
        List<ItemStack> replayFirst = snapshot(firstInventory);
        List<ItemStack> replaySecond = snapshot(secondInventory);
        SidebarPackets.Result operationReplay = ServerUploads.upload(player,
                new SidebarPackets.Upload(menu.containerId, success.operationId(), freshForReplay.token(), sample));
        require(!operationReplay.success(), "same operation with a fresh token must be rejected");
        unchanged(source, replaySource, "operation replay changed source");
        unchanged(firstInventory, replayFirst, "operation replay changed target");
        unchanged(secondInventory, replaySecond, "operation replay changed another provider");

        // Replacing a snapshot invalidates old tokens even while the same menu remains open.
        SidebarPackets.Entry superseded = firstEntry(query(player, menu), firstInventory);
        query(player, menu);
        rejectUnchanged(player, menu, superseded, sample, source, firstInventory, secondInventory,
                "superseded snapshot token");

        // A token belongs to the exact open menu session, not merely an integer container id.
        SidebarPackets.Entry otherMenu = firstEntry(query(player, menu), firstInventory);
        try {
            player.containerMenu = player.inventoryMenu;
            rejectUnchanged(player, menu, otherMenu, sample, source, firstInventory, secondInventory,
                    "closed or replaced menu");
        } finally {
            player.containerMenu = menu;
        }

        // The actual encoded pattern may have changed after the client clicked its old preview.
        SidebarPackets.Entry changedPattern = firstEntry(query(player, menu), firstInventory);
        ItemStack different = PatternDetailsHelper.encodeProcessingPattern(
                List.of(new GenericStack(AEItemKey.of(Items.DIRT), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.GRAVEL), 1)));
        require(!ItemStack.isSameItemSameComponents(sample, different), "changed-pattern fixture differs");
        source.setItemDirect(0, different.copyWithCount(3));
        rejectUnchanged(player, menu, changedPattern, sample, source, firstInventory, secondInventory,
                "source pattern changed");
        source.setItemDirect(0, sample.copyWithCount(3));

        // A formerly available target becomes full: never fall back to another array index/provider.
        SidebarPackets.Entry nowFull = firstEntry(query(player, menu), firstInventory);
        for (int slot = 0; slot < firstInventory.size(); slot++) {
            firstInventory.setItemDirect(slot, sample.copy());
        }
        rejectUnchanged(player, menu, nowFull, sample, source, firstInventory, secondInventory,
                "original target became full");
        clear(firstInventory);
        firstInventory.setItemDirect(firstInventory.size() - 1, sample.copy());

        // A replacement at the same position is a different server object, not the token's target.
        SidebarPackets.Entry removed = firstEntry(query(player, menu), firstInventory);
        ServerLevel level = player.serverLevel();
        BlockPos originalPosition = first.getBlockPos();
        level.setBlockAndUpdate(originalPosition, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(originalPosition, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        require(level.getBlockEntity(originalPosition) instanceof PatternProviderBlockEntity,
                "replacement provider was created");
        PatternProviderBlockEntity replacement =
                (PatternProviderBlockEntity) level.getBlockEntity(originalPosition);
        require(replacement != first, "same-position replacement has a new Java identity");
        rejectUnchanged(player, menu, removed, sample, source,
                replacement.getLogic().getPatternInv(), secondInventory, "original target was replaced");

        // Leave a usable fixture for the following real-client screenshot and input checks.
        source.setItemDirect(0, sample.copyWithCount(3));
        player.containerMenu = menu;
        menu.broadcastChanges();
        ServerUploads.forget(player.getUUID());
    }

    private static SidebarPackets.Providers query(ServerPlayer player, PatternEncodingTermMenu menu) {
        return ServerUploads.query(player, new SidebarPackets.Query(menu.containerId, ++sequence));
    }

    private static SidebarPackets.Entry firstEntry(SidebarPackets.Providers providers,
                                                   InternalInventory firstInventory) {
        int occupied = occupiedSlots(firstInventory);
        return providers.entries().stream()
                .filter(entry -> entry.totalSlots() == firstInventory.size()
                        && entry.freeSlots() == firstInventory.size() - occupied)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "SMOKE_SERVER_ASSERT: selected fixture target missing; " + providers.message()));
    }

    private static SidebarPackets.Result upload(ServerPlayer player, PatternEncodingTermMenu menu,
                                                SidebarPackets.Entry entry, ItemStack expected) {
        return ServerUploads.upload(player,
                new SidebarPackets.Upload(menu.containerId, ++sequence, entry.token(), expected.copyWithCount(1)));
    }

    private static void rejectUnchanged(ServerPlayer player, PatternEncodingTermMenu menu,
                                        SidebarPackets.Entry entry, ItemStack expected,
                                        InternalInventory source, InternalInventory first, InternalInventory second,
                                        String reason) {
        List<ItemStack> beforeSource = snapshot(source);
        List<ItemStack> beforeFirst = snapshot(first);
        List<ItemStack> beforeSecond = snapshot(second);
        SidebarPackets.Result result = upload(player, menu, entry, expected);
        require(!result.success(), reason + " must be rejected");
        unchanged(source, beforeSource, reason + " changed source inventory");
        unchanged(first, beforeFirst, reason + " changed selected inventory");
        unchanged(second, beforeSecond, reason + " changed unrelated inventory");
    }

    private static List<ItemStack> snapshot(InternalInventory inventory) {
        return java.util.stream.IntStream.range(0, inventory.size())
                .mapToObj(slot -> inventory.getStackInSlot(slot).copy()).toList();
    }

    private static void unchanged(InternalInventory inventory, List<ItemStack> before, String message) {
        require(inventory.size() == before.size(), message + " (size)");
        for (int slot = 0; slot < before.size(); slot++) {
            require(ItemStack.matches(before.get(slot), inventory.getStackInSlot(slot)), message + " (slot " + slot + ")");
        }
    }

    private static int patternCount(InternalInventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) count += inventory.getStackInSlot(slot).getCount();
        return count;
    }

    private static int occupiedSlots(InternalInventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) count++;
        }
        return count;
    }

    private static void clear(InternalInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) inventory.setItemDirect(slot, ItemStack.EMPTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("SMOKE_SERVER_ASSERT: " + message);
    }
}
