package dev.echo.uploadsidebar.core;

import java.nio.file.Path;

/** Separate private file, reusing atomic writes and identity validation from annotation storage. */
public final class ProviderFavoritesStore {
    private final ProviderNotesStore store;
    public ProviderFavoritesStore(Path file) { store = new ProviderNotesStore(file); }
    public boolean available() { return store.available(); }
    public boolean favorite(String scope, String identity) { return "1".equals(store.note(scope, identity)); }
    public boolean set(String scope, String identity, boolean favorite) {
        return store.save(scope, identity, favorite ? "1" : "");
    }
    public String error() {
        return store.error() == null ? "收藏未保存" : store.error().replace("备注", "收藏");
    }
}
