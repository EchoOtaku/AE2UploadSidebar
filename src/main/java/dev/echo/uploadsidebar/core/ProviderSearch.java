package dev.echo.uploadsidebar.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Predicate;

/** Search-only transliteration: never used to authorize uploads or resolve mappings. */
public final class ProviderSearch {
    private ProviderSearch() { }
    private static final Map<Integer, List<String>> READINGS = load();
    private static final Map<String, List<List<String>>> CACHE = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<List<String>>> entry) {
            return size() > 512;
        }
    };

    public static boolean matches(String text, String query) {
        String literal = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (literal.isEmpty()) return true;
        if (text == null) return false;
        if (text.toLowerCase(Locale.ROOT).contains(literal)) return true;
        String key = normalize(literal).replaceAll("[\\s']+", "");
        if (key.isEmpty() || !key.matches("[a-z0-9]+")) return false;
        for (String line : text.split("\\R")) {
            List<List<String>> tokens;
            synchronized (CACHE) { tokens = CACHE.computeIfAbsent(line, ProviderSearch::tokens); }
            if (matchesTokens(tokens, key, false) || matchesTokens(tokens, key, true)) return true;
        }
        return false;
    }

    // Dynamic programming keeps polyphonic matching linear in text length, not all reading combinations.
    private static boolean matchesTokens(List<List<String>> tokens, String key, boolean initials) {
        boolean[] active = new boolean[key.length() + 1];
        for (List<String> readings : tokens) {
            active[0] = true;
            boolean[] next = new boolean[active.length];
            for (int offset = 0; offset < key.length(); offset++) if (active[offset]) {
                for (String reading : readings) {
                    String value = initials ? reading.substring(0, 1) : reading;
                    int count = Math.min(value.length(), key.length() - offset);
                    if (!key.regionMatches(offset, value, 0, count)) continue;
                    if (offset + count == key.length()) return true;
                    next[offset + count] = true;
                }
            }
            active = next;
        }
        return false;
    }

    private static List<List<String>> tokens(String text) {
        return text.toLowerCase(Locale.ROOT).codePoints()
            .mapToObj(cp -> READINGS.getOrDefault(cp, List.of(new String(Character.toChars(cp)))))
            .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("u\u0308", "v").replaceAll("\\p{M}+", "");
    }

    private static Map<Integer, List<String>> load() {
        var result = new HashMap<Integer, List<String>>();
        try (var stream = ProviderSearch.class.getResourceAsStream("/dev/echo/uploadsidebar/pinyin.txt")) {
            if (stream == null) throw new IOException("Missing bundled pinyin dictionary");
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("U+")) continue;
                    String[] parts = line.split("#", 2)[0].split(":", 2);
                    int cp = Integer.parseInt(parts[0].substring(2), 16);
                    result.put(cp, Arrays.stream(parts[1].strip().split(","))
                        .map(ProviderSearch::normalize).distinct().toList());
                }
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
        return Map.copyOf(result);
    }

    /** Stable partition preserves recommendation order within both groups. */
    public static <T> List<T> favoritesFirst(List<T> entries, Predicate<T> favorite) {
        return entries.stream().sorted(Comparator.comparingInt(e -> favorite.test(e) ? 0 : 1)).toList();
    }
}
