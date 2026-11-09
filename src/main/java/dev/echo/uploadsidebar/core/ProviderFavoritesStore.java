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
        return store.error() == null ? "ae2_upload_sidebar.error.favorites.unsaved" : store.error().replace("error.notes.", "error.favorites.");
    }
}
