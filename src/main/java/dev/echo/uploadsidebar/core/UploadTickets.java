package dev.echo.uploadsidebar.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A single player's one-use target tickets for one menu snapshot.
 * The caller still has to revalidate permissions, network membership and target state after consume.
 */
public final class UploadTickets<T> {
    public static final long DEFAULT_TTL_MILLIS = 10_000;
    public static final int MAX_TARGETS = 512;

    private final long ttlMillis;
    private final int maxTargets;
    private Object snapshotMenu;
    private long snapshotCreatedAt;
    private Map<UUID, T> targetsByToken = new HashMap<>();

    public UploadTickets() {
        this(DEFAULT_TTL_MILLIS, MAX_TARGETS);
    }

    public UploadTickets(long ttlMillis, int maxTargets) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttlMillis must be positive");
        if (maxTargets <= 0 || maxTargets > MAX_TARGETS) {
            throw new IllegalArgumentException("maxTargets must be between 1 and " + MAX_TARGETS);
        }
        this.ttlMillis = ttlMillis;
        this.maxTargets = maxTargets;
    }

    /**
     * Atomically replaces the previous snapshot. Invalid input leaves all previous tickets intact.
     * An empty target list is valid and explicitly invalidates the previous snapshot.
     */
    public synchronized List<Ticket<T>> replaceSnapshot(Object menuIdentity, List<? extends T> targets,
                                                         long nowMillis) {
        Objects.requireNonNull(menuIdentity, "menuIdentity");
        Objects.requireNonNull(targets, "targets");
        checkSize(targets.size());
        List<? extends T> copiedTargets = List.copyOf(targets);
        checkSize(copiedTargets.size());

        Map<UUID, T> replacement = new HashMap<>();
        List<Ticket<T>> tickets = new ArrayList<>(copiedTargets.size());
        for (T target : copiedTargets) {
            UUID token;
            do {
                token = UUID.randomUUID();
            } while (replacement.containsKey(token) || targetsByToken.containsKey(token));
            replacement.put(token, target);
            tickets.add(new Ticket<>(token, target));
        }
        List<Ticket<T>> result = List.copyOf(tickets);
        snapshotMenu = menuIdentity;
        snapshotCreatedAt = nowMillis;
        targetsByToken = replacement;
        return result;
    }

    /**
     * Wrong-menu, null and unknown tokens leave other tickets untouched. For the matching menu,
     * a known token is removed before checking expiry or returning its target, preventing retries.
     * The lifetime is [createdAt, createdAt + ttl); time rollback or arithmetic overflow rejects it.
     */
    public synchronized Optional<T> consume(UUID token, Object actualMenuIdentity, long nowMillis) {
        if (token == null || actualMenuIdentity == null || actualMenuIdentity != snapshotMenu) {
            return Optional.empty();
        }
        T target = targetsByToken.remove(token);
        if (target == null) return Optional.empty();
        long age = nowMillis - snapshotCreatedAt;
        if (nowMillis < snapshotCreatedAt || age < 0 || age >= ttlMillis) return Optional.empty();
        return Optional.of(target);
    }

    private void checkSize(int size) {
        if (size > maxTargets) throw new IllegalArgumentException("Too many upload targets: " + size);
    }

    public record Ticket<T>(UUID token, T target) {}
}
