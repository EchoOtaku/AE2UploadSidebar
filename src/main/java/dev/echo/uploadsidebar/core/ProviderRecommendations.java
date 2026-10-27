package dev.echo.uploadsidebar.core;

import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Pure recommendation rules; recommendations never choose an upload target. */
public final class ProviderRecommendations {
    private ProviderRecommendations() { }

    public enum Source { EMPTY, AUTOMATIC, MANUAL, UNKNOWN }
    public record Recommendation(String key, Source source) { }

    public static boolean matches(String name, String key) {
        String normalized = normalize(key);
        return name != null && !normalized.isEmpty()
            && name.toLowerCase(Locale.ROOT).contains(normalized.toLowerCase(Locale.ROOT));
    }

    public static <T> List<T> sortedEntries(List<T> entries, Function<T, String> name,
                                          ToIntFunction<T> freeSlots, String key) {
        if (normalize(key).isEmpty()) return List.copyOf(entries);
        // Stream sorting is stable: equal names remain independent, concrete targets.
        return entries.stream().sorted(Comparator
            .comparingInt((T entry) -> matches(name.apply(entry), key) ? 0 : 1)
            .thenComparingInt(entry -> matches(name.apply(entry), key) && freeSlots.applyAsInt(entry) <= 0 ? 1 : 0))
            .toList();
    }

    public static final class Selection {
        private Object menu;
        private Object patternKey;
        private String automaticKey = "";
        private String manualKey = "";

        /** Menu uses identity; the immutable pattern key uses structural equality. */
        public void capture(Object menu, Object patternKey, String automaticKey) {
            if (this.menu != menu || !Objects.equals(this.patternKey, patternKey)) manualKey = "";
            this.menu = menu;
            this.patternKey = patternKey;
            this.automaticKey = normalize(automaticKey);
        }

        public void setManualKey(String key) {
            manualKey = patternKey == null ? "" : normalize(key);
        }

        public Recommendation current() {
            if (patternKey == null) return new Recommendation("", Source.EMPTY);
            if (!manualKey.isEmpty()) return new Recommendation(manualKey, Source.MANUAL);
            if (!automaticKey.isEmpty()) return new Recommendation(automaticKey, Source.AUTOMATIC);
            return new Recommendation("", Source.UNKNOWN);
        }
    }

    private static String normalize(String text) { return text == null ? "" : text.strip(); }
}
