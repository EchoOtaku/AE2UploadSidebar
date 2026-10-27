package dev.echo.uploadsidebar.core;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** One explicit encode request, one fresh snapshot, at most one upload attempt. */
public final class AutoUploadAttempt {
    private Object menu;
    private Object before;
    private int beforeCount;
    private Object encoded;
    private String mapping;
    private long expires;

    public void arm(Object menu, Object before, int count, long now) {
        cancel();
        this.menu = menu;
        this.before = before;
        beforeCount = count;
        expires = now + 5000;
    }

    public boolean observe(Object menu, Object pattern, int count, String enabledMapping, long now) {
        if (!valid(menu, now) || encoded != null || pattern == null) return false;
        if (Objects.equals(before, pattern) && count <= beforeCount) return false;
        if (enabledMapping == null || enabledMapping.isEmpty()) { cancel(); return false; }
        encoded = pattern;
        mapping = enabledMapping;
        return true;
    }

    public <T> T consume(Object menu, Object pattern, String enabledMapping, long now,
                         boolean complete, List<T> entries, Predicate<T> matches) {
        if (!valid(menu, now) || encoded == null) return null;
        boolean same = Objects.equals(encoded, pattern) && Objects.equals(mapping, enabledMapping);
        cancel();
        if (!same || !complete) return null;
        List<T> targets = entries.stream().filter(matches).limit(2).toList();
        return targets.size() == 1 ? targets.getFirst() : null;
    }

    private boolean valid(Object current, long now) {
        if (menu == null || current != menu || now >= expires) { cancel(); return false; }
        return true;
    }
    public void cancel() { menu = null; before = null; encoded = null; mapping = null; expires = 0; }
}
