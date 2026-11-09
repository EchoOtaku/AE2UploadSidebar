package dev.echo.uploadsidebar.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Resolve UI text through Minecraft's current language and resource packs. */
public final class SidebarText {
    public static final String PREFIX = "ae2_upload_sidebar.";

    private SidebarText() { }

    public static MutableComponent component(String key, Object... arguments) {
        return Component.translatable(PREFIX + key, arguments);
    }

    public static String text(String key, Object... arguments) {
        return component(key, arguments).getString();
    }

    /** Core stores and server packets carry keys; user-authored text stays literal. */
    public static String resolve(String message) {
        if (message == null) return "";
        return message.startsWith(PREFIX) ? Component.translatable(message).getString() : message;
    }
}
