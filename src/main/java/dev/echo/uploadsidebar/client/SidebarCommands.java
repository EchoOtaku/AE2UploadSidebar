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
                context.getSource().sendSuccess(() -> Component.literal("[AE2 上传侧栏] 已取消供应器定位。"), false);
                return 1;
            })));
    }

    private static int status(CommandSourceStack source) {
        String state = SidebarClient.isEnabled() ? "开启" : "关闭";
        source.sendSuccess(() -> Component.literal("[AE2 上传侧栏] 当前：" + state
            + "。/ae2sidebar off 关闭，/ae2sidebar on 恢复（只影响自己）。"), false);
        if (SidebarClient.switchPersistenceError() != null)
            source.sendFailure(Component.literal("开关配置读取或保存失败；请以当前状态为准。on/off 可重新尝试保存。"));
        return 1;
    }

    private static int change(CommandSourceStack source, boolean enabled) {
        boolean saved = SidebarClient.setEnabled(enabled);
        String feedback = enabled ? "已开启，打开编码终端或样板供应器即可使用。" : "已关闭侧栏及其交互；原 AE2 界面可照常使用。";
        source.sendSuccess(() -> Component.literal("[AE2 上传侧栏] " + feedback
            + (saved ? " 状态已保存。" : " 本次已生效，但未保存。")), false);
        if (!saved) {
            source.sendFailure(Component.literal("无法写入开关配置，下次启动可能恢复为此前状态。详情见日志。"));
            LogUtils.getLogger().warn("AE2 sidebar runtime switch changed but could not be saved: {}",
                SidebarClient.switchPersistenceError());
        }
        return 1;
    }
}
