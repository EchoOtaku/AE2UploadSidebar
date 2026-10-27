package dev.echo.uploadsidebar.core;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UploadTicketsTest {
    @Test void eachTargetReceivesADistinctOneUseTicket() {
        UploadTickets<Object> ledger = new UploadTickets<>();
        Object menu = new Object();
        Object first = new Object();
        Object second = new Object();
        var tickets = ledger.replaceSnapshot(menu, List.of(first, second), 1_000);

        assertEquals(2, tickets.size());
        assertSame(first, tickets.get(0).target());
        assertSame(second, tickets.get(1).target());
        assertNotEquals(tickets.get(0).token(), tickets.get(1).token());
        assertSame(first, ledger.consume(tickets.get(0).token(), menu, 1_001).orElseThrow());
        assertTrue(ledger.consume(tickets.get(0).token(), menu, 1_002).isEmpty());
        assertSame(second, ledger.consume(tickets.get(1).token(), menu, 1_003).orElseThrow());
    }

    @Test void anEqualButDifferentMenuCannotUseOrRemoveTheTicket() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new String("same logical menu");
        Object otherMenu = new String("same logical menu");
        assertEquals(menu, otherMenu);
        assertNotSame(menu, otherMenu);
        var ticket = ledger.replaceSnapshot(menu, List.of("provider"), 0).getFirst();

        assertTrue(ledger.consume(ticket.token(), otherMenu, 1).isEmpty());
        assertEquals("provider", ledger.consume(ticket.token(), menu, 2).orElseThrow());
    }

    @Test void replacingSnapshotInvalidatesTicketsEvenForTheSameMenuObject() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new Object();
        var oldTicket = ledger.replaceSnapshot(menu, List.of("old"), 0).getFirst();
        var replacement = ledger.replaceSnapshot(menu, List.of("new"), 1).getFirst();

        assertTrue(ledger.consume(oldTicket.token(), menu, 2).isEmpty());
        assertEquals("new", ledger.consume(replacement.token(), menu, 2).orElseThrow());
    }

    @Test void oldMenuCannotConsumeNewSnapshotTickets() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object oldMenu = new Object();
        Object newMenu = new Object();
        var oldTicket = ledger.replaceSnapshot(oldMenu, List.of("old"), 0).getFirst();
        var newTicket = ledger.replaceSnapshot(newMenu, List.of("new"), 1).getFirst();

        assertTrue(ledger.consume(oldTicket.token(), oldMenu, 2).isEmpty());
        assertTrue(ledger.consume(newTicket.token(), oldMenu, 2).isEmpty());
        assertEquals("new", ledger.consume(newTicket.token(), newMenu, 2).orElseThrow());
    }

    @Test void defaultTtlAccepts9999MillisecondsButRejects10000() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new Object();
        var tickets = ledger.replaceSnapshot(menu, List.of("before", "boundary"), 1_000);

        assertEquals("before", ledger.consume(tickets.get(0).token(), menu, 10_999).orElseThrow());
        assertTrue(ledger.consume(tickets.get(1).token(), menu, 11_000).isEmpty());
        assertTrue(ledger.consume(tickets.get(1).token(), menu, 10_999).isEmpty(),
            "An expired attempted ticket must not revive when the clock goes back");
    }

    @Test void aWrongMenuCannotRemoveTicketsEvenWhenItsAttemptIsPastExpiry() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new Object();
        var ticket = ledger.replaceSnapshot(menu, List.of("provider"), 0).getFirst();

        assertTrue(ledger.consume(ticket.token(), new Object(), 20_000).isEmpty());
        assertEquals("provider", ledger.consume(ticket.token(), menu, 1).orElseThrow());
    }

    @Test void nullAndUnknownConsumeArgumentsDoNotRemoveValidTickets() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new Object();
        var ticket = ledger.replaceSnapshot(menu, List.of("provider"), 0).getFirst();

        assertTrue(ledger.consume(null, menu, 1).isEmpty());
        assertTrue(ledger.consume(ticket.token(), null, 1).isEmpty());
        assertTrue(ledger.consume(UUID.randomUUID(), menu, 1).isEmpty());
        assertEquals("provider", ledger.consume(ticket.token(), menu, 1).orElseThrow());
    }

    @Test void invalidReplacementLeavesTheOriginalSnapshotUntouched() {
        UploadTickets<String> ledger = new UploadTickets<>(10_000, 2);
        Object menu = new Object();
        var ticket = ledger.replaceSnapshot(menu, List.of("provider"), 0).getFirst();

        assertThrows(NullPointerException.class, () -> ledger.replaceSnapshot(null, List.of("a"), 1));
        assertThrows(NullPointerException.class, () -> ledger.replaceSnapshot(menu, null, 1));
        assertThrows(NullPointerException.class, () -> ledger.replaceSnapshot(menu, Arrays.asList("a", null), 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.replaceSnapshot(menu, List.of("a", "b", "c"), 1));
        assertEquals("provider", ledger.consume(ticket.token(), menu, 2).orElseThrow());
    }

    @Test void emptyReplacementExplicitlyClearsThePreviousSnapshot() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new Object();
        var oldTicket = ledger.replaceSnapshot(menu, List.of("old"), 0).getFirst();

        assertTrue(ledger.replaceSnapshot(menu, List.of(), 1).isEmpty());
        assertTrue(ledger.consume(oldTicket.token(), menu, 2).isEmpty());
    }

    @Test void defaultTargetLimitAllows512ButRejects513WithoutLosingTickets() {
        UploadTickets<Integer> ledger = new UploadTickets<>();
        Object menu = new Object();
        var tickets = ledger.replaceSnapshot(menu, IntStream.range(0, 512).boxed().toList(), 0);

        assertEquals(512, tickets.size());
        assertThrows(IllegalArgumentException.class,
            () -> ledger.replaceSnapshot(menu, IntStream.range(0, 513).boxed().toList(), 1));
        assertEquals(511, ledger.consume(tickets.get(511).token(), menu, 2).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> tickets.clear());
    }

    @Test void customTtlAndClockRollbackCannotExtendATicketLifetime() {
        UploadTickets<String> ledger = new UploadTickets<>(25, 2);
        Object menu = new Object();
        var tickets = ledger.replaceSnapshot(menu, List.of("rollback", "expired"), 1_000);

        assertTrue(ledger.consume(tickets.get(0).token(), menu, 999).isEmpty());
        assertTrue(ledger.consume(tickets.get(0).token(), menu, 1_000).isEmpty());
        assertTrue(ledger.consume(tickets.get(1).token(), menu, 1_025).isEmpty());
    }

    @Test void overflowingAgeNeverMakesAnAncientTicketFresh() {
        UploadTickets<String> ledger = new UploadTickets<>();
        Object menu = new Object();
        var ticket = ledger.replaceSnapshot(menu, List.of("ancient"), Long.MIN_VALUE).getFirst();

        assertTrue(ledger.consume(ticket.token(), menu, Long.MAX_VALUE).isEmpty());
    }

    @Test void rejectsInvalidLifetimeAndTargetLimits() {
        assertThrows(IllegalArgumentException.class, () -> new UploadTickets<>(0, 512));
        assertThrows(IllegalArgumentException.class, () -> new UploadTickets<>(-1, 512));
        assertThrows(IllegalArgumentException.class, () -> new UploadTickets<>(10_000, 0));
        assertThrows(IllegalArgumentException.class, () -> new UploadTickets<>(10_000, 513));
    }

    /** Lets this pure-Java suite run with the installed JDK while other agents compile the mod. */
    public static void main(String[] args) throws Exception {
        int passed = 0;
        var suite = new UploadTicketsTest();
        for (var method : UploadTicketsTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) continue;
            try {
                method.invoke(suite);
                passed++;
            } catch (InvocationTargetException failure) {
                throw new AssertionError(method.getName(), failure.getCause());
            }
        }
        System.out.println("UploadTicketsTest: " + passed + " tests passed");
    }
}
