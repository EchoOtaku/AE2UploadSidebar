package dev.echo.uploadsidebar.core;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public final class ProviderRecommendationsTest {
    private record Provider(UUID token, String name, int free) { }

    @Test void recommendationKeepsConcreteTargetsAndMovesMatchesFirst() {
        Provider other = new Provider(UUID.randomUUID(), "工作盆", 9);
        Provider full = new Provider(UUID.randomUUID(), "分子装配室", 0);
        Provider first = new Provider(UUID.randomUUID(), "分子装配室", 3);
        Provider second = new Provider(UUID.randomUUID(), "分子装配室", 7);
        List<Provider> source = List.of(other, full, first, second);
        List<Provider> result = ProviderRecommendations.sortedEntries(source,
            Provider::name, Provider::free, "分子装配室");
        check(result.equals(List.of(first, second, full, other)), "match and capacity groups are stable");
        check(result.get(0) == first && result.get(1) == second, "same-named providers retain concrete object and token");
        check(source.equals(List.of(other, full, first, second)), "source list is never reordered");
    }

    @Test void unknownAndEmptyMappingNeverRecommendEverything() {
        check(!ProviderRecommendations.matches("分子装配室", ""), "empty key must not match all names");
        check(!ProviderRecommendations.matches("分子装配室", null), "unknown key has no recommendation");
        check(!ProviderRecommendations.matches(null, "分子"), "missing name has no recommendation");
        check(ProviderRecommendations.matches("01 分子装配室", " 分子装配室 "), "mapped keyword supports named machines");
        check(ProviderRecommendations.matches("MIXER-01", "mixer"), "Latin matching is locale independent");
        var providers = List.of(new Provider(UUID.randomUUID(), "Z", 0), new Provider(UUID.randomUUID(), "A", 4));
        check(ProviderRecommendations.sortedEntries(providers, Provider::name, Provider::free, "").equals(providers),
            "unknown mappings preserve the existing display order");
    }

    @Test void manualSelectionIsBoundToMenuAndPatternContents() {
        var selection = new ProviderRecommendations.Selection();
        Object menu = new Object();
        selection.capture(menu, "pattern-a", "");
        check(selection.current().source() == ProviderRecommendations.Source.UNKNOWN, "processing begins unknown");
        selection.setManualKey("灌注");
        check(selection.current().source() == ProviderRecommendations.Source.MANUAL, "manual choice is labelled");
        selection.capture(menu, new String("pattern-a"), "");
        check(selection.current().key().equals("灌注"), "equivalent packet copy retains selection");
        selection.capture(menu, "pattern-b", "");
        check(selection.current().source() == ProviderRecommendations.Source.UNKNOWN, "new recipe cannot reuse old manual context");
        selection.setManualKey("熔炼");
        selection.capture(new Object(), "pattern-b", "");
        check(selection.current().source() == ProviderRecommendations.Source.UNKNOWN, "new menu cannot inherit a mapping");
    }

    @Test void automaticRefreshAndEmptyOutputInvalidateStaleSelection() {
        var selection = new ProviderRecommendations.Selection();
        Object menu = new Object();
        selection.capture(menu, "crafting-pattern", "分子装配室");
        check(selection.current().source() == ProviderRecommendations.Source.AUTOMATIC, "crafting mapping is automatic");
        selection.setManualKey("备用装配室");
        selection.setManualKey("");
        check(selection.current().key().equals("分子装配室"), "clear restores automatic mapping");
        selection.capture(menu, "crafting-pattern", "新装配室");
        check(selection.current().key().equals("新装配室"), "edited shared mapping refreshes without changing the pattern");
        selection.setManualKey("灌注");
        selection.capture(menu, null, "");
        selection.setManualKey("熔炼");
        check(selection.current().source() == ProviderRecommendations.Source.EMPTY, "no encoded output cannot retain manual choice");
        selection.capture(menu, "crafting-pattern", "分子装配室");
        check(selection.current().source() == ProviderRecommendations.Source.AUTOMATIC, "new output does not inherit cleared selection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var tests = new ProviderRecommendationsTest();
        tests.recommendationKeepsConcreteTargetsAndMovesMatchesFirst();
        tests.unknownAndEmptyMappingNeverRecommendEverything();
        tests.manualSelectionIsBoundToMenuAndPatternContents();
        tests.automaticRefreshAndEmptyOutputInvalidateStaleSelection();
        System.out.println("PROVIDER_RECOMMENDATIONS_PASS: 4 checks");
    }
}
