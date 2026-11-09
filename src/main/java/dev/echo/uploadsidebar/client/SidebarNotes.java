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
            favoriteError = "ae2_upload_sidebar.notes.favorite_unavailable";
            return false;
        }
        boolean saved = favorites().set(scope, identity.key(), !favorite(entry));
        favoriteError = saved ? "" : favorites().error();
        return saved;
    }

    public static String favoriteError() { return SidebarText.resolve(favoriteError); }
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

    public static String unavailableReason() { return SidebarText.resolve(unavailableReasonKey()); }

    static String unavailableReasonKey() {
        if (!SidebarClient.isEnabled()) return "ae2_upload_sidebar.notes.disabled";
        if (!sameContext()) return "ae2_upload_sidebar.notes.wait_refresh";
        if (!metadataSupported) return "ae2_upload_sidebar.notes.update_host";
        if (store != null && !store.available()) return store.error();
        return "ae2_upload_sidebar.notes.no_identity";
    }

    public static String locationLabel(SidebarPackets.Entry entry) {
        var identity = sameContext() ? identities.get(entry.token()) : null;
        return identity == null ? SidebarText.text("notes.unknown_location") : identity.location();
    }

    /** Read-only positioning remains available even if a private notes/icons file cannot be written. */
    public static Optional<ProviderLocation> providerLocation(SidebarPackets.Entry entry) {
        var identity = entry != null && sameContext() && metadataSupported ? identities.get(entry.token()) : null;
        return identity == null ? Optional.empty() : ProviderLocation.parse(identity.key());
    }

    public static boolean save(SidebarPackets.Entry entry, String text) {
        if (!canEdit(entry) || !scope.equals(currentScope())) {
            lastError = unavailableReasonKey();
            return false;
        }
        boolean saved = store().save(scope, identities.get(entry.token()).key(), text);
        lastError = saved ? null : store().error();
        return saved;
    }

    static String errorKey() { return lastError == null ? "ae2_upload_sidebar.notes.unsaved" : lastError; }
    public static String error() { return SidebarText.resolve(errorKey()); }

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
            lastIconError = "ae2_upload_sidebar.notes.select_icon";
            return false;
        }
        if (!validIconContext(entry)) return false;
        try {
            String json = ProviderIconCodec.encode(selected, Minecraft.getInstance().level.registryAccess());
            return persistIcon(entry, json);
        } catch (IllegalArgumentException exception) {
            lastIconError = "ae2_upload_sidebar.notes.invalid_icon";
            return false;
        }
    }

    public static boolean resetIcon(SidebarPackets.Entry entry) {
        return validIconContext(entry) && persistIcon(entry, "");
    }

    private static boolean validIconContext(SidebarPackets.Entry entry) {
        if (canEditIcon(entry) && scope.equals(currentScope())) return true;
        if (!sameContext()) lastIconError = "ae2_upload_sidebar.notes.context_changed";
        else if (!metadataSupported) lastIconError = "ae2_upload_sidebar.notes.icon_update_host";
        else if (iconsStore != null && !iconsStore.available()) lastIconError = iconsStore.error();
        else lastIconError = "ae2_upload_sidebar.notes.icon_no_identity";
        return false;
    }

    private static boolean persistIcon(SidebarPackets.Entry entry, String json) {
        boolean saved = iconsStore().save(scope, identities.get(entry.token()).key(), json);
        lastIconError = saved ? null : iconsStore().error();
        if (saved) decodedIcons.clear();
        return saved;
    }

    public static String iconError() { return SidebarText.resolve(iconErrorKey()); }

    private static String iconErrorKey() {
        if (lastIconError != null) return lastIconError;
        if (!sameContext()) return "ae2_upload_sidebar.notes.icon_wait_refresh";
        if (!metadataSupported) return "ae2_upload_sidebar.notes.icon_update_host";
        if (iconsStore != null && !iconsStore.available()) return iconsStore.error();
        return "ae2_upload_sidebar.notes.icon_no_identity";
    }

    static String currentScope() {
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server != null) return "singleplayer:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var remote = client.getCurrentServer();
        return remote == null || remote.ip.isBlank() ? "" : "multiplayer:" + remote.ip.strip();
    }
}
