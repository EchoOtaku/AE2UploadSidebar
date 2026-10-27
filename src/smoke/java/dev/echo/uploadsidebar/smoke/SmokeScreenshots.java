package dev.echo.uploadsidebar.smoke;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Captures the actual framebuffer and verifies each asynchronous file write. */
final class SmokeScreenshots {
    private static final Set<String> requested = new LinkedHashSet<>();
    private static final Set<String> saved = new LinkedHashSet<>();
    private static volatile Throwable failure;

    static synchronized void request(Minecraft client, String name) {
        checkFailure();
        if (!requested.add(name)) return;
        Path output = client.gameDirectory.toPath().resolve("screenshots").resolve(name);
        final FileTime previousTimestamp;
        try {
            previousTimestamp = Files.exists(output) ? Files.getLastModifiedTime(output) : null;
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not inspect prior smoke screenshot: " + output, error);
        }
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
            try {
                if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                    throw new AssertionError("Screenshot was not saved: " + name + ": " + message.getString());
                }
                if (previousTimestamp != null && Files.getLastModifiedTime(output).compareTo(previousTimestamp) <= 0) {
                    throw new AssertionError("Screenshot was not updated by this run: " + name + ": " + message.getString());
                }
                synchronized (SmokeScreenshots.class) { saved.add(name); }
                LogUtils.getLogger().info("EMBEDDED_SMOKE: screenshot saved {}", output);
            } catch (Throwable error) { failure = error; }
        });
    }

    static synchronized boolean saved(String name) {
        checkFailure();
        return saved.contains(name);
    }

    static synchronized String report() {
        checkFailure();
        if (!saved.containsAll(requested)) throw new AssertionError("Screenshots still pending");
        return saved.stream().map(name -> "screenshot=screenshots/" + name + "\n")
            .collect(java.util.stream.Collectors.joining());
    }

    private static void checkFailure() {
        if (failure != null) throw new AssertionError("Smoke screenshot failed", failure);
    }
}
