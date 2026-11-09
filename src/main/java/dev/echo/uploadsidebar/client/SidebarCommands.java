package dev.echo.uploadsidebar.client;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Entirely local: no operator permission, server command, or server-global disable state. */
public final class SidebarCommands {
    private SidebarCommands() { }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ae2sidebar")
            .executes(context -> status(context.getSource()))
            .then(Commands.literal("status").executes(context -> status(context.getSource())))
            .then(Commands.literal("off").executes(context -> change(context.getSource(), false)))
            .then(Commands.literal("on").executes(context -> change(context.getSource(), true)))
            .then(Commands.literal("unhighlight").executes(context -> {
                SidebarLocator.clear();
                context.getSource().sendSuccess(() -> SidebarText.component("commands.unhighlight"), false);
                return 1;
            })));
    }

    private static int status(CommandSourceStack source) {
        Component state = SidebarText.component(SidebarClient.isEnabled() ? "commands.on" : "commands.off");
        source.sendSuccess(() -> SidebarText.component("commands.status", state), false);
        if (SidebarClient.switchPersistenceError() != null)
            source.sendFailure(SidebarText.component("commands.config_error"));
        return 1;
    }

    private static int change(CommandSourceStack source, boolean enabled) {
        boolean saved = SidebarClient.setEnabled(enabled);
        Component feedback = SidebarText.component(enabled ? "commands.enabled" : "commands.disabled");
        source.sendSuccess(() -> SidebarText.component("commands.changed", feedback,
            SidebarText.component(saved ? "commands.saved" : "commands.unsaved")), false);
        if (!saved) {
            source.sendFailure(SidebarText.component("commands.save_error"));
            LogUtils.getLogger().warn("AE2 sidebar runtime switch changed but could not be saved: {}",
                SidebarClient.switchPersistenceError());
        }
        return 1;
    }
}
