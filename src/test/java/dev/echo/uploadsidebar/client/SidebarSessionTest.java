package dev.echo.uploadsidebar.client;

import dev.echo.uploadsidebar.core.SidebarSession;
import org.junit.jupiter.api.Test;

/** Pure state/layout checks, also runnable directly with Java before game boot. */
public final class SidebarSessionTest {
    @Test
    void rejectsLateAndWrongMenuResponses() {
        Object original = new Object();
        SidebarSession session = new SidebarSession(original, 8);
        long query = session.begin(SidebarSession.Operation.QUERY, 100);
        check(!session.accept(new Object(), 8, query, SidebarSession.Operation.QUERY), "different menu object");
        check(!session.accept(original, 9, query, SidebarSession.Operation.QUERY), "different menu id");
        check(!session.accept(original, 8, query + 1, SidebarSession.Operation.QUERY), "different request id");
        check(session.accept(original, 8, query, SidebarSession.Operation.QUERY), "matching response");
        check(!session.accept(original, 8, query, SidebarSession.Operation.QUERY), "duplicate response");
        check(session.canUpload(), "fresh snapshot permits upload");
    }

    @Test
    void oneOperationInFlightAndTimeoutInvalidatesTokens() {
        Object menu = new Object();
        SidebarSession session = new SidebarSession(menu, 4);
        check(!session.canUpload(), "no upload before a snapshot");
        long first = session.begin(SidebarSession.Operation.QUERY, 100);
        check(session.begin(SidebarSession.Operation.QUERY, 101) == 0, "one query at a time");
        check(!session.canUpload(), "no upload while querying");
        check(!session.expire(5099), "deadline is five seconds");
        check(session.expire(5100), "timeout");
        check(!session.canUpload(), "timeout invalidates snapshot");
        check(!session.accept(menu, 4, first, SidebarSession.Operation.QUERY), "late reply rejected");
        long next = session.begin(SidebarSession.Operation.QUERY, 5101);
        check(next > first, "request ids never reused");
        check(session.accept(menu, 4, next, SidebarSession.Operation.QUERY), "fresh response");
        long upload = session.begin(SidebarSession.Operation.UPLOAD, 5200);
        check(upload > next && !session.canUpload(), "upload in flight");
        check(session.accept(menu, 4, upload, SidebarSession.Operation.UPLOAD), "matching upload reply");
        check(!session.canUpload(), "upload requires fresh tokens afterward");
        long reopened = new SidebarSession(new Object(), 4).begin(SidebarSession.Operation.QUERY, 1);
        check(reopened > upload, "reopening the same menu id cannot reuse request ids");
    }

    @Test
    void layoutStaysOnScreenAndPreservesAe2ToolbarSpace() {
        for (int width : new int[] {80, 240, 320, 480, 720, 1280}) {
            for (int height : new int[] {60, 180, 300, 540}) {
                for (int guiLeft : new int[] {0, 20, 80, 140, 260}) {
                    SidebarSession.Layout box = SidebarSession.layout(width, height, guiLeft, 30, 240);
                    check(box.x() >= 0 && box.y() >= 0, "non-negative placement");
                    check(box.width() > 0 && box.height() > 0, "visible rectangle");
                    check(box.x() + box.width() <= width, "horizontal containment");
                    check(box.y() + box.height() <= height, "vertical containment");
                    if (!box.collapsed()) {
                        check(box.x() + box.width() <= guiLeft - 26, "AE2 toolbar clearance");
                        check(box.width() >= 104 && box.height() >= 128, "usable expanded panel");
                    }
                }
            }
        }
        check(SidebarSession.layout(480, 300, 110, 6, 288).collapsed(), "small margin collapses safely");
        check(!SidebarSession.layout(720, 450, 262, 20, 360).collapsed(), "normal margin expands");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SidebarSessionTest tests = new SidebarSessionTest();
        tests.rejectsLateAndWrongMenuResponses();
        tests.oneOperationInFlightAndTimeoutInvalidatesTokens();
        tests.layoutStaysOnScreenAndPreservesAe2ToolbarSpace();
        System.out.println("SIDEBAR_STATE_LAYOUT_PASS");
    }
}
