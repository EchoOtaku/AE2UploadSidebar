package dev.echo.uploadsidebar.server;

import org.junit.jupiter.api.Test;

import static dev.echo.uploadsidebar.server.TransferRecovery.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferRecoveryTest {
    @Test void beforeInsertionLeavesBothSlotsUntouched() {
        assertEquals(UNCHANGED, TransferRecovery.classify(true, false, true, false));
    }

    @Test void afterDestinationInsertionOnlyTheSourceNeedsCompletion() {
        assertEquals(COMPLETE_SOURCE, TransferRecovery.classify(true, false, false, true));
    }

    @Test void exceptionAfterSourceWriteHasAlreadyCompleted() {
        assertEquals(COMPLETED, TransferRecovery.classify(false, true, false, true));
    }

    @Test void broadcastFailureMustNotMoveAnotherItem() {
        assertEquals(COMPLETED, TransferRecovery.classify(false, true, false, true));
    }

    @Test void unknownSourceMustNotBeOverwritten() {
        assertEquals(UNKNOWN, TransferRecovery.classify(false, false, false, true));
    }

    @Test void unknownDestinationMustNotBeOverwritten() {
        assertEquals(UNKNOWN, TransferRecovery.classify(true, false, false, false));
    }

    @Test void missingDestinationAfterSourceChangeIsNotAssumedSuccessful() {
        assertEquals(UNKNOWN, TransferRecovery.classify(false, true, true, false));
    }

    @Test void contradictorySnapshotsCannotAuthorizeAnotherMutation() {
        assertEquals(UNKNOWN, TransferRecovery.classify(true, true, false, true));
        assertEquals(UNKNOWN, TransferRecovery.classify(true, false, true, true));
    }
}
