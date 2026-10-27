package dev.echo.uploadsidebar.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

/** Local opaque ItemStack JSON storage; Minecraft registry validation belongs to the client codec. */
public final class ProviderIconsStore {
    public static final int MAX_ICON_BYTES = 16 * 1024;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ICONS = 2048;
    private final Path file;
    private Properties icons = new Properties();
    private byte[] original;
    private boolean available = true;
    private String error;

    public ProviderIconsStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        try {
            original = read();
            if (original == null) return;
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original)).toString();
            Properties loaded = new Properties();
            loaded.load(new StringReader(text));
            if (!"1".equals(loaded.remove("format")) || loaded.size() > MAX_ICONS)
                throw new IOException("Unsupported or oversized icons file");
            for (String key : loaded.stringPropertyNames()) {
                if (!key.matches("icon\\.[0-9a-f]{64}") || !validPayload(loaded.getProperty(key)))
                    throw new IOException("Malformed icon entry");
            }
            icons = loaded;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            available = false;
            error = "图标文件读取失败，已停止写入以保留原文件：" + failure.getClass().getSimpleName();
        }
    }

    public boolean available() { return available; }
    public String error() { return error; }

    public String icon(String scope, String identity) {
        return available && validKey(scope, identity) ? icons.getProperty(key(scope, identity), "") : "";
    }

    /** Empty clears only this location; failed writes leave the old in-memory and on-disk data intact. */
    public boolean save(String scope, String identity, String json) {
        if (!available) return false;
        String value = json == null || json.isBlank() ? "" : json;
        if (!validKey(scope, identity) || !value.isEmpty() && !validPayload(value)) {
            error = "图标数据或位置标识无效；每个图标最多 16 KiB UTF-8 数据";
            return false;
        }
        Properties next = new Properties();
        next.putAll(icons);
        if (value.isEmpty()) next.remove(key(scope, identity));
        else next.setProperty(key(scope, identity), value);
        Path temporary = null;
        try {
            if (next.size() > MAX_ICONS) throw new IOException("Too many icons");
            if (!Arrays.equals(original, read())) throw new IOException("Icons changed outside the game; reopen the game before editing");
            Properties encoded = new Properties();
            encoded.putAll(next);
            encoded.setProperty("format", "1");
            StringWriter writer = new StringWriter();
            encoded.store(writer, "Private AE2 provider icons. Keys hash world/server scope and physical provider identity.");
            byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Icons file too large");
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "ae2-sidebar-icons-", ".tmp");
            Files.write(temporary, bytes);
            if (!Arrays.equals(original, read())) throw new IOException("Icons changed outside the game");
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            original = bytes;
            icons = next;
            error = null;
            return true;
        } catch (IOException | SecurityException failure) {
            error = "图标未保存，原内容已保留：" + failure.getMessage();
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException ignored) { }
            }
        }
    }

    private byte[] read() throws IOException {
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Icons file too large");
            return bytes;
        } catch (NoSuchFileException missing) { return null; }
    }

    private static boolean validKey(String scope, String identity) {
        return scope != null && !scope.isBlank() && scope.length() <= 4096
            && identity != null && !identity.isBlank() && identity.length() <= 512;
    }

    /** Opaque here: removed items or components are handled per-entry by the registry-aware decoder. */
    private static boolean validPayload(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ICON_BYTES) return false;
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= MAX_ICON_BYTES;
        } catch (CharacterCodingException malformedUtf16) { return false; }
    }

    private static String key(String scope, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (scope.length() + ":" + scope + identity).getBytes(StandardCharsets.UTF_8));
            return "icon." + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
