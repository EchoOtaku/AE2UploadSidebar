package dev.echo.uploadsidebar.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent mappings. Legacy JSON is imported once, read-only, without loading EAEP classes. */
public final class RecipeMappings {
    public record RecipeTypeMapping(String key, String value) { }
    private static final int MAX_BYTES = 1024 * 1024;
    private final Path file;
    private final Map<String, String> mappings = new TreeMap<>();
    private byte[] original;
    private boolean available = true;
    private String error = "";

    public RecipeMappings(Path file, Path legacy) {
        this.file = file;
        try {
            original = read(file);
            byte[] source = original != null ? original : read(legacy);
            if (source == null) return;
            var root = JsonParser.parseString(StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(source)).toString());
            if (!root.isJsonObject() || root.getAsJsonObject().size() > 8192) throw new IOException("Invalid mapping object");
            for (var entry : root.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IOException("Invalid mapping value");
                String key = normalize(entry.getKey()), value = entry.getValue().getAsString().strip();
                if (!valid(key) || !valid(value) || mappings.putIfAbsent(key, value) != null)
                    throw new IOException("Invalid or duplicate mapping");
            }
            if (original == null && !persist(new TreeMap<>(mappings))) throw new IOException("Import failed");
        } catch (IOException | RuntimeException failure) {
            mappings.clear();
            available = false;
            error = "映射读取或导入失败，原文件已保留，请检查配置后重启游戏";
        }
    }

    public List<RecipeTypeMapping> list() {
        return mappings.entrySet().stream().map(e -> new RecipeTypeMapping(e.getKey(), e.getValue())).toList();
    }
    public String error() { return error; }
    public boolean put(String key, String value) {
        if (!available || !valid(key) || !valid(value)) { error = "映射未保存：名称需为 1～512 字符，不能包含控制符"; return false; }
        var next = new TreeMap<>(mappings);
        next.put(normalize(key), value.strip());
        return persist(next);
    }
    public boolean remove(String key) {
        if (!available) return false;
        var next = new TreeMap<>(mappings);
        next.remove(normalize(key));
        return persist(next);
    }
    public String resolveType(String id, String fallback) {
        String exact = mappings.get(normalize(id));
        if (exact != null) return exact;
        String path = id.substring(id.indexOf(':') + 1);
        return mappings.getOrDefault(normalize(path), fallback);
    }
    public String resolveProvider(String key) {
        return mappings.getOrDefault(normalize(key), key.strip());
    }

    private boolean persist(Map<String, String> next) {
        Path temporary = null;
        try {
            if (next.size() > 8192 || !Arrays.equals(original, read(file))) throw new IOException("Mapping file changed externally");
            byte[] bytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(next).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Mapping file too large");
            Files.createDirectories(file.toAbsolutePath().getParent());
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "sidebar-mappings-", ".tmp");
            Files.write(temporary, bytes);
            if (!Arrays.equals(original, read(file))) throw new IOException("Mapping file changed externally");
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            original = bytes;
            mappings.clear(); mappings.putAll(next); error = "";
            return true;
        } catch (IOException | RuntimeException failure) {
            error = "映射未保存，原文件已保留；若配置被外部修改，请重启游戏";
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
    private static byte[] read(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Mapping file too large");
            return bytes;
        } catch (NoSuchFileException missing) { return null; }
    }
    private static String normalize(String key) { return key.strip().toLowerCase(Locale.ROOT); }
    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.length() <= 512
            && value.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x00a7);
    }
}
