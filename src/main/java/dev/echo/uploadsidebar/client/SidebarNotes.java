package dev.echo.uploadsidebar.client;

import dev.echo.uploadsidebar.core.ProviderNotesStore;
import dev.echo.uploadsidebar.core.ProviderIconsStore;
import dev.echo.uploadsidebar.core.ProviderLocation;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/** Local annotations only. Stable metadata is read-only; remarks are never sent to the server. */
public final class SidebarNotes {
    private static dev.echo.uploadsidebar.core.ProviderFavoritesStore favorites;
    private static String favoriteError = "";

    private static dev.echo.uploadsidebar.core.ProviderFavoritesStore favorites() {
        if (favorites == null) favorites = new dev.echo.uploadsidebar.core.ProviderFavoritesStore(
            FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-favorites.properties"));
        return favorites;
    }

    public static boolean favorite(SidebarPackets.Entry entry) {
        var identity = sameContext() && metadataSupported ? identities.get(entry.token()) : null;
        return identity != null && favorites().favorite(scope, identity.key());
    }

    public static boolean toggleFavorite(SidebarPackets.Entry entry) {
        var identity = sameContext() && metadataSupported ? identities.get(entry.token()) : null;
        if (identity == null || !scope.equals(currentScope())) {
            favoriteError = "请等待供应器刷新；收藏需要可靠的位置标识";
            return false;
        }
        boolean saved = favorites().set(scope, identity.key(), !favorite(entry));
        favoriteError = saved ? "" : favorites().error();
        return saved;
    }

    public static String favoriteError() { return favoriteError; }
    private static ProviderNotesStore store;
    private static ProviderIconsStore iconsStore;
    private static final Map<String, ItemStack> decodedIcons = new HashMap<>();
    private static String lastIconError;
    private static Map<UUID, SidebarPackets.Identity> identities = Map.of();
    private static Object menu;
    private static Object connection;
    private static Object level;
    private static String scope = "";
    private static boolean metadataSupported;
    private static String lastError;

    private SidebarNotes() { }

    private static ProviderNotesStore store() {
        if (store == null) store = new ProviderNotesStore(
            FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-notes.properties"));
        return store;
    }

    private static ProviderIconsStore iconsStore() {
        if (iconsStore == null) iconsStore = new ProviderIconsStore(
            FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-icons.properties"));
        return iconsStore;
    }

    /** Called only after the controller accepted this exact request/menu snapshot. */
    static void accept(Object actualMenu, List<SidebarPackets.Entry> entries,
                       List<SidebarPackets.Identity> metadata, boolean supported) {
        clear();
        Minecraft client = Minecraft.getInstance();
        if (!SidebarClient.isEnabled() || client.player == null || client.player.containerMenu != actualMenu) return;
        menu = actualMenu;
        connection = client.getConnection();
        level = client.level;
        scope = currentScope();
        metadataSupported = supported;
        var tokens = new HashSet<UUID>();
        var repeatedTokens = new HashSet<UUID>();
        for (var entry : entries) if (!tokens.add(entry.token())) repeatedTokens.add(entry.token());
        var keys = new HashSet<String>();
        var repeatedKeys = new HashSet<String>();
        var result = new HashMap<UUID, SidebarPackets.Identity>();
        for (var identity : metadata.stream().limit(512).toList()) {
            if (identity.token() == null || !tokens.contains(identity.token()) || identity.key() == null
                    || identity.key().isBlank() || identity.key().length() > 512
                    || identity.location() == null || identity.location().length() > 256) continue;
            if (!keys.add(identity.key())) repeatedKeys.add(identity.key());
            if (result.put(identity.token(), identity) != null) repeatedTokens.add(identity.token());
        }
        result.values().removeIf(identity -> repeatedKeys.contains(identity.key()) || repeatedTokens.contains(identity.token()));
        identities = Map.copyOf(result);
    }

    /** Real menu/world changes and runtime-off clear bindings, but opening the note editor does not. */
    static void clear() {
        identities = Map.of();
        menu = connection = level = null;
        scope = "";
        metadataSupported = false;
        lastError = null;
        lastIconError = null;
        decodedIcons.clear();
    }

    private static boolean sameContext() {
        Minecraft client = Minecraft.getInstance();
        return SidebarClient.isEnabled() && menu != null && client.player != null
            && client.player.containerMenu == menu && client.getConnection() == connection
            && client.level == level && !scope.isEmpty() && SidebarClient.hasValidAnnotationHost();
    }

    public static String note(SidebarPackets.Entry entry) {
        var identity = sameContext() ? identities.get(entry.token()) : null;
        return identity == null ? "" : store().note(scope, identity.key());
    }

    public static String displayName(SidebarPackets.Entry entry) {
        String note = note(entry);
        return note.isEmpty() ? entry.name() : note;
    }

    public static String searchText(SidebarPackets.Entry entry) {
        return entry.name() + "\n" + note(entry);
    }

    public static boolean canEdit(SidebarPackets.Entry entry) {
        return sameContext() && metadataSupported && identities.containsKey(entry.token()) && store().available();
    }

    public static String unavailableReason() {
        if (!SidebarClient.isEnabled()) return "侧栏已关闭";
        if (!sameContext()) return "请等待供应器刷新后再编辑备注";
        if (!metadataSupported) return "房主需更新至支持备注的补丁；原上传功能仍可使用";
        if (store != null && !store.available()) return store.error();
        return "此供应器暂无唯一、可靠的位置标识，不能保存备注";
    }

    public static String locationLabel(SidebarPackets.Entry entry) {
        var identity = sameContext() ? identities.get(entry.token()) : null;
        return identity == null ? "位置未知" : identity.location();
    }

    /** Read-only positioning remains available even if a private notes/icons file cannot be written. */
    public static Optional<ProviderLocation> providerLocation(SidebarPackets.Entry entry) {
        var identity = entry != null && sameContext() && metadataSupported ? identities.get(entry.token()) : null;
        return identity == null ? Optional.empty() : ProviderLocation.parse(identity.key());
    }

    public static boolean save(SidebarPackets.Entry entry, String text) {
        if (!canEdit(entry) || !scope.equals(currentScope())) {
            lastError = unavailableReason();
            return false;
        }
        boolean saved = store().save(scope, identities.get(entry.token()).key(), text);
        lastError = saved ? null : store().error();
        return saved;
    }

    public static String error() { return lastError == null ? "备注未保存" : lastError; }

    /** Detached display-only copy: never mutate the source or the server snapshot. */
    public static ItemStack icon(SidebarPackets.Entry entry) {
        var identity = sameContext() && metadataSupported ? identities.get(entry.token()) : null;
        if (identity != null) {
            String json = iconsStore().icon(scope, identity.key());
            if (!json.isEmpty()) {
                ItemStack decoded = decodedIcons.computeIfAbsent(json, value ->
                    ProviderIconCodec.decode(value, Minecraft.getInstance().level.registryAccess())
                        .orElse(ItemStack.EMPTY));
                if (!decoded.isEmpty()) return decoded.copyWithCount(1);
            }
        }
        return entry.icon().isEmpty() ? ItemStack.EMPTY : entry.icon().copyWithCount(1);
    }

    public static boolean canEditIcon(SidebarPackets.Entry entry) {
        return sameContext() && metadataSupported && identities.containsKey(entry.token()) && iconsStore().available();
    }

    public static boolean saveIcon(SidebarPackets.Entry entry, ItemStack selected) {
        if (selected == null || selected.isEmpty()) {
            lastIconError = "请选择物品；恢复默认请使用 Shift＋右键图标";
            return false;
        }
        if (!validIconContext(entry)) return false;
        try {
            String json = ProviderIconCodec.encode(selected, Minecraft.getInstance().level.registryAccess());
            return persistIcon(entry, json);
        } catch (IllegalArgumentException exception) {
            lastIconError = "此物品的数据无效或过大，图标未保存";
            return false;
        }
    }

    public static boolean resetIcon(SidebarPackets.Entry entry) {
        return validIconContext(entry) && persistIcon(entry, "");
    }

    private static boolean validIconContext(SidebarPackets.Entry entry) {
        if (canEditIcon(entry) && scope.equals(currentScope())) return true;
        if (!sameContext()) lastIconError = "终端已变化或侧栏已关闭，请刷新后重试";
        else if (!metadataSupported) lastIconError = "房主需使用支持备注的 0.4 或更新补丁";
        else if (iconsStore != null && !iconsStore.available()) lastIconError = iconsStore.error();
        else lastIconError = "此供应器暂无唯一、可靠的位置标识，不能保存图标";
        return false;
    }

    private static boolean persistIcon(SidebarPackets.Entry entry, String json) {
        boolean saved = iconsStore().save(scope, identities.get(entry.token()).key(), json);
        lastIconError = saved ? null : iconsStore().error();
        if (saved) decodedIcons.clear();
        return saved;
    }

    public static String iconError() {
        if (lastIconError != null) return lastIconError;
        if (!sameContext()) return "请等待供应器刷新；侧栏关闭时不能编辑图标";
        if (!metadataSupported) return "房主需使用支持备注的 0.4 或更新补丁";
        if (iconsStore != null && !iconsStore.available()) return iconsStore.error();
        return "此供应器暂无唯一、可靠的位置标识，不能保存图标";
    }

    static String currentScope() {
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server != null) return "singleplayer:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var remote = client.getCurrentServer();
        return remote == null || remote.ip.isBlank() ? "" : "multiplayer:" + remote.ip.strip();
    }
}
