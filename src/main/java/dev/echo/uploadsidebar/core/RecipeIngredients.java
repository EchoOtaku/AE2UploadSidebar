package dev.echo.uploadsidebar.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Matches the complete transferred recipe, including amounts and merged duplicate ingredients. */
public final class RecipeIngredients {
    private RecipeIngredients() { }
    public record Amount<K>(K key, long amount) { }

    public static <K> boolean matches(List<List<Amount<K>>> choices, List<Amount<K>> actual) {
        if (choices.isEmpty() || choices.size() > 81 || actual.size() > 81) return false;
        var remaining = new HashMap<K, Long>();
        try {
            for (var stack : actual) {
                if (stack.key() == null || stack.amount() <= 0) return false;
                remaining.merge(stack.key(), stack.amount(), Math::addExact);
            }
        } catch (ArithmeticException overflow) { return false; }
        return !remaining.isEmpty() && match(choices, 0, remaining, new int[] {4096});
    }

    private static <K> boolean match(List<List<Amount<K>>> choices, int index, Map<K, Long> remaining, int[] budget) {
        if (--budget[0] < 0) return false;
        if (index == choices.size()) return remaining.isEmpty();
        var alternatives = choices.get(index);
        if (alternatives.isEmpty() || alternatives.size() > 256) return false;
        for (var candidate : alternatives) {
            long count = remaining.getOrDefault(candidate.key(), 0L);
            if (candidate.key() == null || candidate.amount() <= 0 || candidate.amount() > count) continue;
            if (candidate.amount() == count) remaining.remove(candidate.key());
            else remaining.put(candidate.key(), count - candidate.amount());
            if (match(choices, index + 1, remaining, budget)) return true;
            remaining.put(candidate.key(), count);
            if (budget[0] < 0) return false;
        }
        return false;
    }
}
