package dev.echo.uploadsidebar.client;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.core.SidebarSession;
import dev.echo.uploadsidebar.core.AutoUploadAttempt;
import appeng.api.stacks.AEItemKey;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Client-only controller. Never replaces, resizes or re-renders the terminal screen. */
public final class SidebarClient {
    private static final SidebarSwitch runtimeSwitch = new SidebarSwitch(
        FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-enabled.properties"));
    private static boolean initialized;
    private static AEBaseScreen<?> screen;
    private static AEBaseMenu menu;
    private static OpenedProvider.Target openedProvider;
    private static UUID openedProviderToken;
    private static SidebarSession session;
    private static SidebarPanel panel;
    private static List<SidebarPackets.Entry> entries = List.of();
    private static String status = "正在连接服务器…";
    private static boolean supported;
    private static final Set<Integer> panelButtons = new HashSet<>();
    private static boolean auxiliaryScreenOpen;
    private static long nextQueryAt;
    private static final AutoUploadAttempt autoUpload = new AutoUploadAttempt();
    private static boolean autoQueryPending;

    /** Invoked only by the native client encode action, never by opening a terminal. */
    public static void encodeRequested(PatternEncodingTermMenu expectedMenu) {
        if (!synchronizeContext() || menu != expectedMenu || !hasServerSupport() || session.uploading()) return;
        autoUpload.arm(menu, patternFingerprint(), encodedCount(), now());
    }

    private static Object patternFingerprint() {
        ItemStack pattern = encodedPattern();
        return pattern.isEmpty() ? null : AEItemKey.of(pattern);
    }

    private static void cancelAutoUpload() {
        autoUpload.cancel();
        autoQueryPending = false;
    }

    private SidebarClient() { }

    public static void init() {
        if (initialized) return;
        initialized = true;
        SidebarLocator.init();
        // Commands stay registered even when the UI is disabled or cannot be constructed.
        NeoForge.EVENT_BUS.addListener(SidebarCommands::register);
        if (runtimeSwitch.persistenceError() != null)
            LogUtils.getLogger().warn("AE2 sidebar disabled because its runtime switch could not be read: {}",
                runtimeSwitch.persistenceError());
        NeoForge.EVENT_BUS.addListener(SidebarClient::tick);
        NeoForge.EVENT_BUS.addListener(SidebarClient::render);
        NeoForge.EVENT_BUS.addListener(SidebarClient::mousePressed);
        NeoForge.EVENT_BUS.addListener(SidebarClient::mouseReleased);
        NeoForge.EVENT_BUS.addListener(SidebarClient::mouseDragged);
        NeoForge.EVENT_BUS.addListener(SidebarClient::mouseScrolled);
        NeoForge.EVENT_BUS.addListener(SidebarClient::keyPressed);
        NeoForge.EVENT_BUS.addListener(SidebarClient::characterTyped);
    }

    private static boolean synchronizeContext() {
        if (!isEnabled()) return false;
        Minecraft client = Minecraft.getInstance();
        if (isProviderView() && client.player != null && client.player.containerMenu == menu
                && (client.screen == screen || SidebarMappings.isAuxiliaryScreen(client.screen, screen))
                && !hasValidAnnotationHost()) {
            entries = List.of();
            SidebarNotes.clear();
            status = "当前供应器已变化，请重新打开";
            return false;
        }
        // Mapping configuration is client-only. Keep the exact terminal context
        // for returning to it, but stop uploads/queries and invalidate old tickets.
        if (screen != null && client.player != null && client.player.containerMenu == menu
                && SidebarMappings.isAuxiliaryScreen(client.screen, screen)) {
            if (!auxiliaryScreenOpen) {
                cancelAutoUpload();
                session.invalidate();
                entries = List.of();
                panelButtons.clear();
                panel.mouseReleased(0, 0, 0);
                panel.unfocus();
                nextQueryAt = 0;
            }
            auxiliaryScreenOpen = true;
            return false;
        }
        if (!(client.screen instanceof AEBaseScreen<?> current) || !supportsScreen(current)
                || client.player == null || client.player.containerMenu != current.getMenu()) {
            clearContext();
            return false;
        }
        if (screen != current || menu != current.getMenu()) {
            cancelAutoUpload();
            SidebarNotes.clear();
            screen = current;
            menu = current.getMenu();
            openedProvider = null;
            openedProviderToken = null;
            session = new SidebarSession(menu, menu.containerId);
            SidebarMappings.reset();
            panel = new SidebarPanel(current);
            entries = List.of();
            status = "正在连接服务器…";
            supported = false;
            panelButtons.clear();
            nextQueryAt = 0;
        }
        auxiliaryScreenOpen = false;
        if (isProviderView()) {
            refreshOpenedProvider();
            return true;
        }
        SidebarMappings.tick(menu, encodedPattern());
        return true;
    }

    public static boolean isEnabled() { return runtimeSwitch.enabled(); }
    public static String switchPersistenceError() { return runtimeSwitch.persistenceError(); }

    /** Called on the client thread. Discards UI state without invoking potentially broken panel code. */
    public static boolean setEnabled(boolean enabled) {
        if (!enabled) SidebarLocator.clear();
        SidebarRecipeContext.clear();
        Minecraft client = Minecraft.getInstance();
        AEBaseScreen<?> returnTo = screen;
        boolean returnFromAuxiliary = !enabled && returnTo != null && client.player != null
            && client.player.containerMenu == menu && SidebarMappings.isAuxiliaryScreen(client.screen, returnTo);
        boolean saved = runtimeSwitch.setEnabled(enabled);
        clearContext();
        if (returnFromAuxiliary) client.setScreen(returnTo);
        // Re-enabling creates the next session only on normal terminal activity, never inside this command.
        return saved;
    }

    private static void clearContext() {
        cancelAutoUpload();
        if (session != null) session.invalidate();
        screen = null;
        menu = null;
        openedProvider = null;
        openedProviderToken = null;
        session = null;
        panel = null;
        entries = List.of();
        panelButtons.clear();
        auxiliaryScreenOpen = false;
        supported = false;
        nextQueryAt = 0;
        status = "正在连接服务器…";
        SidebarMappings.reset();
        SidebarNotes.clear();
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    private static boolean hasServerSupport() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null
            && NetworkRegistry.hasChannel(connection, SidebarPackets.Query.TYPE.id())
            && NetworkRegistry.hasChannel(connection, SidebarPackets.Upload.TYPE.id());
    }

    private static void tick(ClientTickEvent.Post event) {
        SidebarRecipeContext.tick();
        if (!synchronizeContext()) return;
        if (isProviderView()) return;
        long time = now();
        supported = hasServerSupport();
        if (!supported) {
            cancelAutoUpload();
            session.invalidate();
            entries = List.of();
            status = "服务器未启用样板上传";
            return;
        }
        if (session.expire(time)) {
            cancelAutoUpload();
            entries = List.of();
            status = "请求超时，正在重新读取";
            nextQueryAt = time + 1000;
        }
        if (autoUpload.observe(menu, patternFingerprint(), encodedCount(),
                SidebarMappings.enabledAutoUploadMapping(), time)) {
            // Query only after the new encoded output was received; old tickets cannot authorize this attempt.
            session.invalidate();
            entries = List.of();
            autoQueryPending = true;
            nextQueryAt = 0;
        }
        if (!session.busy() && time >= nextQueryAt) query(time);
    }

    private static void query(long time) {
        long id = session.begin(SidebarSession.Operation.QUERY, time);
        if (id == 0) return;
        status = "正在读取供应器…";
        nextQueryAt = time + 3000;
        try {
            var connection = Minecraft.getInstance().getConnection();
            boolean named = connection != null
                && NetworkRegistry.hasChannel(connection, SidebarPackets.NamedQuery.TYPE.id())
                && NetworkRegistry.hasChannel(connection, SidebarPackets.NamedProviders.TYPE.id());
            if (named) PacketDistributor.sendToServer(new SidebarPackets.NamedQuery(menu.containerId, id));
            else PacketDistributor.sendToServer(new SidebarPackets.Query(menu.containerId, id));
        } catch (RuntimeException error) {
            failRequest("读取失败，请稍后重试", error);
        }
    }

    public static void acceptProviders(SidebarPackets.Providers response) {
        if (acceptSnapshot(response)) {
            SidebarNotes.accept(menu, entries, List.of(), false);
            finishAutoUpload(response);
        }
    }

    public static void acceptNamedProviders(SidebarPackets.NamedProviders response) {
        if (acceptSnapshot(response.snapshot())) {
            SidebarNotes.accept(menu, entries, response.identities(), true);
            finishAutoUpload(response.snapshot());
        }
    }

    private static void finishAutoUpload(SidebarPackets.Providers response) {
        if (!autoQueryPending) return;
        autoQueryPending = false;
        boolean complete = response.entries().size() == entries.size() && entries.size() < 512
            && (response.message() == null || response.message().isBlank() || "OK".equals(response.message()));
        var target = autoUpload.consume(menu, patternFingerprint(), SidebarMappings.enabledAutoUploadMapping(),
            now(), complete, entries, entry -> entry.freeSlots() > 0 && SidebarMappings.matches(entry));
        if (target != null) upload(target);
        else status = "自动上传未执行：请手动选择供应器";
    }

    private static boolean acceptSnapshot(SidebarPackets.Providers response) {
        if (!synchronizeContext() || isProviderView() || !validateDeadline()) return false;
        if (!session.accept(Minecraft.getInstance().player.containerMenu,
                response.menuId(), response.requestId(), SidebarSession.Operation.QUERY)) return false;
        entries = response.entries().stream()
            .filter(entry -> entry.token() != null && entry.name() != null
                && entry.freeSlots() >= 0 && entry.totalSlots() >= entry.freeSlots())
            .limit(512)
            .map(entry -> new SidebarPackets.Entry(entry.token(), entry.name(), entry.freeSlots(),
                entry.totalSlots(), entry.icon().copyWithCount(1)))
            .sorted(Comparator.comparing(SidebarPackets.Entry::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        status = entries.isEmpty() ? message(response.message(), "没有可用的供应器") : "点击供应器上传 1 张";
        nextQueryAt = now() + 3000;
        return true;
    }

    public static void acceptResult(SidebarPackets.Result response) {
        if (!synchronizeContext() || isProviderView() || !validateDeadline()) return;
        if (!session.accept(Minecraft.getInstance().player.containerMenu,
                response.menuId(), response.operationId(), SidebarSession.Operation.UPLOAD)) return;
        entries = List.of();
        status = response.success() ? "已上传 1 张样板" : message(response.message(), "上传失败，请重试");
        nextQueryAt = now() + 750;
    }

    private static boolean validateDeadline() {
        if (!hasServerSupport()) {
            session.invalidate();
            entries = List.of();
            return false;
        }
        if (session.expire(now())) {
            entries = List.of();
            status = "请求已超时，响应已忽略";
            nextQueryAt = now() + 1000;
            return false;
        }
        return true;
    }

    static void upload(SidebarPackets.Entry entry) {
        cancelAutoUpload();
        if (!synchronizeContext() || isProviderView() || !validateDeadline() || !supported || !session.canUpload()) return;
        if (entry.freeSlots() <= 0 || entries.stream().noneMatch(current -> current.token().equals(entry.token()))) return;
        ItemStack expectedPattern = encodedPattern();
        if (expectedPattern.isEmpty()) {
            status = "请先在终端编码样板";
            return;
        }
        long operationId = session.begin(SidebarSession.Operation.UPLOAD, now());
        if (operationId == 0) return;
        status = "正在上传 1 张样板…";
        try {
            PacketDistributor.sendToServer(new SidebarPackets.Upload(
                menu.containerId, operationId, entry.token(), expectedPattern));
        } catch (RuntimeException error) {
            failRequest("上传请求失败，请重试", error);
        }
    }

    private static void failRequest(String text, RuntimeException error) {
        cancelAutoUpload();
        session.invalidate();
        entries = List.of();
        status = text;
        nextQueryAt = now() + 3000;
        LogUtils.getLogger().warn("AE2 sidebar request failed", error);
    }

    /** Read only AE2's unique encoded output slot; never use held items or blank patterns. */
    static ItemStack encodedPattern() {
        if (!(menu instanceof PatternEncodingTermMenu)) return ItemStack.EMPTY;
        List<Slot> slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        if (slots.size() != 1) return ItemStack.EMPTY;
        ItemStack source = slots.getFirst().getItem();
        return !source.isEmpty() && PatternDetailsHelper.isEncodedPattern(source)
            ? source.copyWithCount(1) : ItemStack.EMPTY;
    }

    static int encodedCount() {
        if (!(menu instanceof PatternEncodingTermMenu)) return 0;
        List<Slot> slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        return slots.size() == 1 && !encodedPattern().isEmpty() ? slots.getFirst().getItem().getCount() : 0;
    }

    static List<SidebarPackets.Entry> entries() { return entries; }
    static String status() { return status; }
    static boolean canUpload() { return isEnabled() && !isProviderView() && supported && session != null && session.canUpload() && !encodedPattern().isEmpty(); }

    public static boolean supportsScreen(Screen candidate) {
        return candidate instanceof PatternEncodingTermScreen<?> || candidate instanceof PatternProviderScreen<?>;
    }

    static boolean isProviderView() { return menu instanceof PatternProviderMenu; }

    /** Also used immediately before saving a note/icon in an auxiliary editor. */
    static boolean hasValidAnnotationHost() {
        if (!isProviderView() || openedProvider == null) return true;
        return OpenedProvider.resolve(screen).filter(target -> target.host() == openedProvider.host()
            && target.identity().equals(openedProvider.identity())).isPresent();
    }

    private static void refreshOpenedProvider() {
        var resolved = OpenedProvider.resolve(screen);
        if (resolved.isEmpty()) {
            entries = List.of(); SidebarNotes.clear();
            status = "无法确定当前供应器，请重新打开";
            return;
        }
        boolean first = openedProvider == null;
        if (first) { openedProvider = resolved.get(); openedProviderToken = UUID.randomUUID(); }
        var slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        var entry = new SidebarPackets.Entry(openedProviderToken, openedProvider.name(),
            (int) slots.stream().filter(slot -> slot.getItem().isEmpty()).count(), slots.size(), openedProvider.icon());
        entries = List.of(entry);
        if (first) SidebarNotes.accept(menu, entries, List.of(new SidebarPackets.Identity(openedProviderToken,
            openedProvider.identity().key(), openedProvider.identity().location())), true);
        status = "左 / 右键备注；拖物品换图标";
    }

    private static String message(String message, String fallback) {
        if (message == null || message.isBlank() || "OK".equals(message)) return fallback;
        return switch (message) {
            case "NO_GRID", "GRID_UNAVAILABLE" -> "ME 网络暂不可用";
            case "NO_PATTERN", "INVALID_PATTERN", "NO_ENCODED_PATTERN" -> "请先在终端编码样板";
            case "FULL", "TARGET_FULL", "PROVIDER_FULL" -> "供应器已满，请重新选择";
            case "STALE_TOKEN", "INVALID_TOKEN", "TOKEN_EXPIRED" -> "供应器已变化，正在刷新";
            case "WRONG_MENU", "INVALID_MENU", "MENU_CHANGED" -> "终端已变化，请重新打开";
            case "PATTERN_CHANGED" -> "样板已变化，请重新选择";
            case "NO_PERMISSION", "ACCESS_DENIED" -> "没有访问 ME 网络的权限";
            default -> message;
        };
    }

    private static void render(ScreenEvent.Render.Post event) {
        if (synchronizeContext() && event.getScreen() == screen) {
            panel.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    private static boolean isOurEvent(Screen eventScreen) {
        return synchronizeContext() && eventScreen == screen;
    }

    private static void mousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!isOurEvent(event.getScreen())) return;
        cancelAutoUpload();
        boolean capturedGesture = !panelButtons.isEmpty();
        if (capturedGesture || panel.contains(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            panelButtons.add(event.getButton());
            // Additional buttons belong to the same gesture but must not click
            // another control or replace the left-button title drag's origin.
            if (!capturedGesture) panel.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
        } else {
            panel.unfocus();
        }
    }

    private static void mouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!isOurEvent(event.getScreen())) return;
        // Let a drag that began in the terminal complete its original cleanup; the
        // hasClickedOutside Mixin prevents a release over this panel from dropping it.
        if (panelButtons.remove(event.getButton())) {
            event.setCanceled(true);
            panel.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton());
        }
    }

    private static void mouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!isOurEvent(event.getScreen())) return;
        if (panelButtons.contains(event.getMouseButton())) panel.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton());
        if (!panelButtons.isEmpty() || panel.contains(event.getMouseX(), event.getMouseY())) event.setCanceled(true);
    }

    private static void mouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (isOurEvent(event.getScreen()) && panel.contains(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            panel.scroll(event.getScrollDeltaY());
        }
    }

    private static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!isOurEvent(event.getScreen())) return;
        cancelAutoUpload();
        // Focused search owns typing (including inventory-bound letters) first.
        if (panel.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        boolean closesInventory = client.options.keyInventory.isActiveAndMatches(
            InputConstants.getKey(event.getKeyCode(), event.getScanCode()));
        if (SidebarKeyPolicy.consumeOutsideSearch(!panelButtons.isEmpty() || pointerInPanel(), event.getKeyCode(), closesInventory)) {
            event.setCanceled(true);
        }
    }

    private static boolean pointerInPanel() {
        Minecraft client = Minecraft.getInstance();
        double mouseX = SidebarKeyPolicy.guiCoordinate(client.mouseHandler.xpos(), screen.width,
            client.getWindow().getScreenWidth());
        double mouseY = SidebarKeyPolicy.guiCoordinate(client.mouseHandler.ypos(), screen.height,
            client.getWindow().getScreenHeight());
        return panel.contains(mouseX, mouseY);
    }

    private static void characterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (isOurEvent(event.getScreen())
                && (panel.charTyped(event.getCodePoint(), event.getModifiers()) || !panelButtons.isEmpty() || pointerInPanel())) {
            event.setCanceled(true);
        }
    }

    public static Rect2i exclusionZone(Screen candidate) {
        // JEI can ask during construction, before the screen has real dimensions.
        // Only expose a panel belonging to the already active screen, with no init side effects.
        if (!isEnabled() || panel == null || candidate != screen) return null;
        SidebarSession.Layout box = panel.layout();
        return new Rect2i(box.x(), box.y(), box.width(), box.height());
    }

    /** Read-only JEI probe; never initialize or resurrect a closed panel. */
    public static List<SidebarPanel.IconTarget> iconTargets(Screen candidate) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || panel == null || candidate != screen || client.screen != candidate
                || client.player == null || client.player.containerMenu != menu) return List.of();
        return panel.iconTargets();
    }

    /** A delayed JEI drop must still belong to the same screen, actual menu and visible row. */
    public static boolean acceptIcon(Screen candidate, Object expectedMenu,
                                     SidebarPanel.IconTarget target, ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || panel == null || candidate != screen || client.screen != candidate
                || client.player == null || menu != expectedMenu || client.player.containerMenu != expectedMenu) return false;
        return panel.acceptIcon(target, stack);
    }

    public static boolean contains(Screen candidate, double mouseX, double mouseY) {
        return isEnabled() && panel != null && candidate == screen && panel.contains(mouseX, mouseY);
    }
}
