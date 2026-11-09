package dev.echo.uploadsidebar.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.echo.uploadsidebar.core.LocatorSession;
import dev.echo.uploadsidebar.core.ProviderLocation;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Local, display-only locator using an already accepted provider snapshot. No packets, inventory or file writes. */
public final class SidebarLocator {
    private static final LocatorSession session = new LocatorSession();

    private SidebarLocator() { }

    static void init() {
        NeoForge.EVENT_BUS.addListener(SidebarLocator::tick);
        NeoForge.EVENT_BUS.addListener(SidebarLocator::renderWorld);
        NeoForge.EVENT_BUS.addListener(SidebarLocator::renderHud);
    }

    public static String toggle(SidebarPackets.Entry entry) {
        return toggleMessage(entry).getString();
    }

    public static Component toggleMessage(SidebarPackets.Entry entry) {
        Minecraft client = Minecraft.getInstance();
        var parsed = SidebarNotes.providerLocation(entry);
        if (parsed.isEmpty() || client.level == null || client.player == null) {
            return SidebarText.component("locator.unavailable");
        }
        ProviderLocation location = parsed.get();
        if (!client.level.dimension().location().toString().equals(location.dimension())) {
            clear();
            Component message = SidebarText.component("locator.other_dimension", location.dimension(), coordinatesComponent(location));
            client.player.displayClientMessage(SidebarText.component("locator.chat", message), false);
            return message;
        }
        if (location.y() < client.level.getMinBuildHeight() || location.y() >= client.level.getMaxBuildHeight()) {
            return SidebarText.component("locator.invalid_height");
        }
        boolean selected = session.toggle(location, SidebarNotes.displayName(entry), client.getConnection(), client.level, Util.getMillis());
        return SidebarText.component(selected ? "locator.selected" : "locator.canceled");
    }

    public static void clear() { session.clear(); }

    public static Optional<LocatorSession.Target> current() {
        Minecraft client = Minecraft.getInstance();
        return session.current(client.getConnection(), client.level, SidebarClient.isEnabled(), Util.getMillis());
    }

    private static void tick(ClientTickEvent.Post event) { current(); }

    private static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var selected = current();
        if (selected.isEmpty()) return;
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        pose.pushPose();
        try {
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(LocatorRenderType.OUTLINE),
                bounds(selected.get().location()).inflate(0.003).move(-camera.x, -camera.y, -camera.z),
                1.0f, 0.78f, 0.12f, 1.0f);
        } finally {
            pose.popPose();
            // Flush only our own render type, which restores its render state afterwards.
            buffers.endBatch(LocatorRenderType.OUTLINE);
        }
    }

    private static void renderHud(RenderGuiEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen != null || client.player == null || client.options.hideGui) return;
        var selected = current();
        if (selected.isEmpty()) return;
        var target = selected.get();
        var location = target.location();
        var pos = new BlockPos(location.x(), location.y(), location.z());
        long distance = Math.round(client.player.position().distanceTo(Vec3.atCenterOf(pos)));
        long seconds = Math.max(1, (target.expiresAt() - Util.getMillis() + 999) / 1000);
        var graphics = event.getGuiGraphics();
        int available = Math.max(0, graphics.guiWidth() - 16);
        String title = client.font.plainSubstrByWidth(SidebarText.text("locator.provider", target.name()), available);
        String details = client.font.plainSubstrByWidth(SidebarText.text("locator.details", coordinates(location), distance, seconds), available);
        String hint = client.level.hasChunkAt(pos) ? SidebarText.text("locator.cancel_hint") : SidebarText.text("locator.unloaded");
        graphics.drawString(client.font, title, 8, 8, 0xffffc733);
        graphics.drawString(client.font, details, 8, 20, 0xffffffff);
        graphics.drawString(client.font, client.font.plainSubstrByWidth(hint, available), 8, 32, 0xffbcbcbc);
    }

    public static AABB bounds(ProviderLocation location) {
        AABB local = switch (location.side()) {
            case "down" -> new AABB(.125, 0, .125, .875, .125, .875);
            case "up" -> new AABB(.125, .875, .125, .875, 1, .875);
            case "north" -> new AABB(.125, .125, 0, .875, .875, .125);
            case "south" -> new AABB(.125, .125, .875, .875, .875, 1);
            case "west" -> new AABB(0, .125, .125, .125, .875, .875);
            case "east" -> new AABB(.875, .125, .125, 1, .875, .875);
            default -> new AABB(0, 0, 0, 1, 1, 1);
        };
        return local.move(location.x(), location.y(), location.z());
    }

    private static String coordinates(ProviderLocation location) {
        return coordinatesComponent(location).getString();
    }

    private static Component coordinatesComponent(ProviderLocation location) {
        var result = Component.literal(location.x() + ", " + location.y() + ", " + location.z());
        if (java.util.Set.of("down", "up", "north", "south", "west", "east").contains(location.side()))
            result.append(" [").append(SidebarText.component("locator.face." + location.side())).append("]");
        return result;
    }

    /** Separate from ExtendedAE's shared highlight queue, so canceling cannot remove another tool's markers. */
    private static final class LocatorRenderType extends RenderType {
        // Vanilla NO_DEPTH_TEST is a no-op in 1.21.1: it does not disable an already enabled depth test.
        private static final DepthTestStateShard THROUGH_WALLS = new DepthTestStateShard("sidebar_through_walls", GL11.GL_ALWAYS) {
            private boolean previouslyEnabled;

            @Override public void setupRenderState() {
                previouslyEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                RenderSystem.disableDepthTest();
            }

            @Override public void clearRenderState() {
                if (previouslyEnabled) RenderSystem.enableDepthTest();
                else RenderSystem.disableDepthTest();
            }
        };

        static final RenderType OUTLINE = create("ae2_sidebar_locator", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 256, false, false, CompositeState.builder()
                .setShaderState(RENDERTYPE_LINES_SHADER).setLineState(new LineStateShard(OptionalDouble.of(3)))
                .setLayeringState(VIEW_OFFSET_Z_LAYERING).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(THROUGH_WALLS).setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));

        private LocatorRenderType() {
            super("unused", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256,
                false, false, () -> { }, () -> { });
        }
    }
}
