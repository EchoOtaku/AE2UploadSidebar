package dev.echo.uploadsidebar.core;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reuses bounded, atomic, external-edit-aware storage; all options default off. */
public final class MappingAutoUploadStore {
    private final ProviderNotesStore storage;
    public MappingAutoUploadStore(Path file) { storage = new ProviderNotesStore(file); }
    public boolean enabled(String scope, String key, String value) {
        return "enabled".equals(storage.note(scope, identity(key, value)));
    }
    public boolean set(String scope, String key, String value, boolean enabled) {
        return storage.save(scope, identity(key, value), enabled ? "enabled" : "");
    }
    public String error() { return storage.error() == null ? "" : "自动上传设置无法保存，请检查配置文件"; }
    private static String identity(String key, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((key.length() + ":" + key + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
