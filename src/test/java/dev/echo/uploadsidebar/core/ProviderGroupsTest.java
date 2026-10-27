package dev.echo.uploadsidebar.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderGroupsTest {
    private record Key(String name, String note, boolean favorite) { }
    private record Entry(int identity, Key key) { }
    private final Key machine = new Key("分子装配室", "", false);
    private final Entry first = new Entry(1, machine), second = new Entry(2, machine);

    @Test void collapsedHeaderRetainsBothTargetsAndExpansionPreservesIdentity() {
        var collapsed = ProviderGroups.rows(List.of(first, second), true, Entry::key, Set.of());
        assertEquals(1, collapsed.size());
        assertTrue(collapsed.getFirst().header());
        assertEquals(List.of(first, second), collapsed.getFirst().members());
        var expanded = ProviderGroups.rows(List.of(first, second), true, Entry::key, Set.of(machine));
        assertEquals(3, expanded.size());
        assertTrue(expanded.getFirst().header());
        assertFalse(expanded.get(1).header());
        assertTrue(expanded.get(1).child());
        assertSame(first, expanded.get(1).members().getFirst());
        assertSame(second, expanded.get(2).members().getFirst());
    }

    @Test void notesAndFavoritesRemainSeparateAndFirstSeenOrderIsPreserved() {
        var pinned = new Entry(3, new Key(machine.name(), "", true));
        var named = new Entry(4, new Key(machine.name(), "祭坛", false));
        var rows = ProviderGroups.rows(List.of(pinned, first, named, second), true, Entry::key, Set.of());
        assertEquals(3, rows.size());
        assertEquals(List.of(pinned), rows.getFirst().members());
        assertEquals(List.of(first, second), rows.get(1).members());
        assertEquals(List.of(named), rows.get(2).members());
    }

    @Test void disabledAndFilteredSingletonAreConcreteRowsEvenWithExpandedKey() {
        var disabled = ProviderGroups.rows(List.of(first, second), false, Entry::key, Set.of(machine));
        assertEquals(2, disabled.size());
        assertTrue(disabled.stream().noneMatch(row -> row.header() || row.child()));
        var filtered = ProviderGroups.rows(List.of(second), true, Entry::key, Set.of(machine));
        assertEquals(1, filtered.size());
        assertFalse(filtered.getFirst().header());
        assertSame(second, filtered.getFirst().members().getFirst());
        assertTrue(ProviderGroups.rows(List.<Entry>of(), true, Entry::key, Set.of()).isEmpty());
    }
}
