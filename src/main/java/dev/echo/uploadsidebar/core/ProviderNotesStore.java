package dev.echo.uploadsidebar.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
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

public final class ProviderNotesStore {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NOTES = 8192;
    private final Path file;
    private Properties notes = new Properties();
    private byte[] original;
    private boolean available = true;
    private String error;

    public ProviderNotesStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        try {
            original = read();
            if (original == null) return;
            Properties loaded = new Properties();
            loaded.load(new StringReader(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(original)).toString()));
            if (!"1".equals(loaded.remove("format")) || loaded.size() > MAX_NOTES)
                throw new IOException("Unsupported or oversized notes file");
            for (String key : loaded.stringPropertyNames()) {
                if (!key.matches("note\\.[0-9a-f]{64}") || !validText(loaded.getProperty(key)))
                    throw new IOException("Malformed note entry");
            }
            notes = loaded;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            available = false;
            error = "备注文件读取失败，已停止写入以保留原文件：" + failure.getClass().getSimpleName();
        }
    }

    public boolean available() { return available; }
    public String error() { return error; }
    public String note(String scope, String identity) {
        return available && validKey(scope, identity) ? notes.getProperty(key(scope, identity), "") : "";
    }

    /** Commit memory only after a successful atomic replacement; never repair a corrupt file implicitly. */
    public boolean save(String scope, String identity, String text) {
        if (!available) return false;
        String value = text == null ? "" : text.strip();
        if (!validKey(scope, identity) || !validText(value)) {
            error = "备注最多 64 个字符，且不能含换行、控制符或格式代码";
            return false;
        }
        Properties next = new Properties();
        next.putAll(notes);
        if (value.isEmpty()) next.remove(key(scope, identity));
        else next.setProperty(key(scope, identity), value);
        Path temporary = null;
        try {
            if (next.size() > MAX_NOTES) throw new IOException("Too many notes");
            if (!Arrays.equals(original, read())) throw new IOException("Notes changed outside the game; reopen the game before editing");
            Properties encoded = new Properties();
            encoded.putAll(next);
            encoded.setProperty("format", "1");
            StringWriter writer = new StringWriter();
            encoded.store(writer, "Private AE2 provider notes. Keys hash world/server scope and physical provider identity.");
            byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Notes file too large");
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "ae2-sidebar-notes-", ".tmp");
            Files.write(temporary, bytes);
            // Do not overwrite an edit detected while writing our temporary file.
            if (!Arrays.equals(original, read())) throw new IOException("Notes changed outside the game");
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            original = bytes;
            notes = next;
            error = null;
            return true;
        } catch (IOException | SecurityException failure) {
            error = "备注未保存，原内容已保留：" + failure.getMessage();
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
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Notes file too large");
            return bytes;
        } catch (NoSuchFileException missing) { return null; }
    }

    private static boolean validKey(String scope, String identity) {
        return scope != null && !scope.isBlank() && scope.length() <= 4096
            && identity != null && !identity.isBlank() && identity.length() <= 512;
    }

    private static boolean validText(String value) {
        if (value == null || value.codePointCount(0, value.length()) > 64) return false;
        return value.codePoints().noneMatch(cp -> Character.isISOControl(cp)
            || Character.getType(cp) == Character.FORMAT || cp == 0xa7
            || (cp >= 0xd800 && cp <= 0xdfff));
    }

    private static String key(String scope, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (scope.length() + ":" + scope + identity).getBytes(StandardCharsets.UTF_8));
            return "note." + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
