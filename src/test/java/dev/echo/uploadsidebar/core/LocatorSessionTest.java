package dev.echo.uploadsidebar.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocatorSessionTest {
    private final Object connection = new Object(), level = new Object();
    private final ProviderLocation first = new ProviderLocation("minecraft:overworld", "ae2:pattern_provider", 1, 70, 0, "");
    private final ProviderLocation second = new ProviderLocation("minecraft:overworld", "ae2:pattern_provider", 2, 70, 0, "");

    @Test void sameNamedTargetsRemainDistinctAndRepeatSelectionCancels() {
        var session = new LocatorSession();
        assertTrue(session.toggle(first, "ME接口", connection, level, 100));
        assertTrue(session.toggle(second, "ME接口", connection, level, 200));
        assertEquals(second, session.current(connection, level, true, 201).orElseThrow().location());
        assertFalse(session.toggle(second, "different private note", connection, level, 300));
        assertTrue(session.current(connection, level, true, 301).isEmpty());
        assertTrue(session.toggle(second, "ME接口", connection, level, 400));
    }

    @Test void expiresAtSixtySecondsAndCanSelectSameTargetAgain() {
        var session = new LocatorSession();
        session.toggle(first, "ME接口", connection, level, 100);
        assertTrue(session.current(connection, level, true, 60_099).isPresent());
        assertTrue(session.current(connection, level, true, 60_100).isEmpty());
        assertTrue(session.toggle(first, "ME接口", connection, level, 60_101));
        assertEquals(120_101, session.current(connection, level, true, 60_101).orElseThrow().expiresAt());
    }

    @Test void disconnectWorldChangeAndOffDiscardMarkerWithoutResurrection() {
        var session = new LocatorSession();
        session.toggle(first, "ME接口", connection, level, 0);
        assertTrue(session.current(new Object(), level, true, 1).isEmpty());
        assertTrue(session.current(connection, level, true, 2).isEmpty());
        session.toggle(first, "ME接口", connection, level, 3);
        assertTrue(session.current(connection, new Object(), true, 4).isEmpty());
        session.toggle(first, "ME接口", connection, level, 5);
        assertTrue(session.current(connection, level, false, 6).isEmpty());
        assertTrue(session.current(connection, level, true, 7).isEmpty());
        assertFalse(session.toggle(first, "ME接口", null, level, 8));
    }
}
