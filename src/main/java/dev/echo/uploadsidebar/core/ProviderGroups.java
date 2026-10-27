package dev.echo.uploadsidebar.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Presentation only: a group header must never be treated as an upload target. */
public final class ProviderGroups {
    private ProviderGroups() { }

    public record Row<T, K>(K key, List<T> members, boolean header, boolean child) {
        public Row { members = List.copyOf(members); }
    }

    public static <T, K> List<Row<T, K>> rows(List<T> entries, boolean enabled,
            Function<T, K> key, Set<K> expanded) {
        var result = new ArrayList<Row<T, K>>();
        if (!enabled) {
            for (T entry : entries) result.add(new Row<>(key.apply(entry), List.of(entry), false, false));
            return List.copyOf(result);
        }
        var groups = new LinkedHashMap<K, List<T>>();
        for (T entry : entries) groups.computeIfAbsent(key.apply(entry), ignored -> new ArrayList<>()).add(entry);
        groups.forEach((groupKey, members) -> {
            if (members.size() == 1) result.add(new Row<>(groupKey, members, false, false));
            else {
                result.add(new Row<>(groupKey, members, true, false));
                if (expanded.contains(groupKey))
                    for (T entry : members) result.add(new Row<>(groupKey, List.of(entry), false, true));
            }
        });
        return List.copyOf(result);
    }
}
