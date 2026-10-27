package dev.echo.uploadsidebar.server;

/** Pure classification of the observed two-slot state after a failed transfer callback. */
final class TransferRecovery {
    enum Action { UNCHANGED, COMPLETE_SOURCE, COMPLETED, UNKNOWN }

    private TransferRecovery() {}

    static Action classify(boolean sourceBefore, boolean sourceAfter,
                           boolean destinationEmpty, boolean destinationOne) {
        if (sourceBefore && sourceAfter || destinationEmpty && destinationOne) return Action.UNKNOWN;
        if (sourceBefore && destinationEmpty) return Action.UNCHANGED;
        if (sourceBefore && destinationOne) return Action.COMPLETE_SOURCE;
        if (sourceAfter && destinationOne) return Action.COMPLETED;
        return Action.UNKNOWN;
    }
}
