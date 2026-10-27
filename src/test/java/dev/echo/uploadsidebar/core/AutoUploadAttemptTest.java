package dev.echo.uploadsidebar.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AutoUploadAttemptTest {
    private final AutoUploadAttempt attempt = new AutoUploadAttempt();
    private final Object menu = new Object();
    private void ready() {
        attempt.arm(menu, null, 0, 0);
        assertTrue(attempt.observe(menu, "pattern", 1, "mapping", 100));
    }
    private Integer consume(List<Integer> targets) {
        return attempt.consume(menu, "pattern", "mapping", 200, true, targets, n -> n > 0);
    }
    @Test void openingAndRefreshCannotUpload() {
        assertFalse(attempt.observe(menu, "pattern", 1, "mapping", 100));
        assertNull(consume(List.of(1)));
    }
    @Test void uniqueTargetOnlyOnceEvenAfterFailure() {
        ready(); assertEquals(1, consume(List.of(1)));
        assertNull(consume(List.of(1)));
        assertFalse(attempt.observe(menu, "pattern", 1, "mapping", 300));
    }
    @Test void multipleTargetsDoNotBecomeAutomaticAfterRefresh() {
        ready(); assertNull(consume(List.of(1, 2)));
        assertNull(consume(List.of(1)));
    }
    @Test void noTargetsAndFullTargetsRequireManualChoice() {
        ready(); assertNull(consume(List.of(0)));
        assertNull(consume(List.of(1)));
    }
    @Test void disabledOrUnmappedNeverArmsOnExistingOutput() {
        attempt.arm(menu, null, 0, 0);
        assertFalse(attempt.observe(menu, "pattern", 1, "", 100));
        assertFalse(attempt.observe(menu, "pattern", 1, "mapping", 200));
    }
    @Test void unchangedEncodingOrReducedStackDoesNotTrigger() {
        attempt.arm(menu, "pattern", 3, 0);
        assertFalse(attempt.observe(menu, "pattern", 3, "mapping", 100));
        assertFalse(attempt.observe(menu, "pattern", 2, "mapping", 200));
    }
    @Test void newEncodingOfSameRecipeCanTriggerAgain() {
        ready(); consume(List.of(1));
        attempt.arm(menu, null, 0, 300);
        assertTrue(attempt.observe(menu, "pattern", 1, "mapping", 400));
        assertEquals(1, attempt.consume(menu, "pattern", "mapping", 500, true, List.of(1), n -> true));
    }
    @Test void changedMappingOrPatternCannotUpload() {
        ready(); assertNull(attempt.consume(menu, "pattern", "changed", 200, true, List.of(1), n -> true));
        ready(); assertNull(attempt.consume(menu, "different", "mapping", 200, true, List.of(1), n -> true));
    }
    @Test void timeoutMenuChangeAndCancelInvalidate() {
        ready(); assertNull(attempt.consume(menu, "pattern", "mapping", 5000, true, List.of(1), n -> true));
        ready(); assertNull(attempt.consume(new Object(), "pattern", "mapping", 200, true, List.of(1), n -> true));
        ready(); attempt.cancel(); assertNull(consume(List.of(1)));
    }
    @Test void truncatedSnapshotCannotProveUniqueness() {
        ready(); assertNull(attempt.consume(menu, "pattern", "mapping", 200, false, List.of(1), n -> true));
        assertNull(consume(List.of(1)));
    }
}
