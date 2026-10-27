package dev.echo.uploadsidebar.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Client request gate without Minecraft dependencies; all times use monotonic milliseconds. */
public final class SidebarSession {
    public enum Operation { QUERY, UPLOAD }
    private static final AtomicLong IDS = new AtomicLong();
    private final Object menu;
    private final int menuId;
    private Operation pending;
    private long requestId;
    private long startedAt;
    private boolean snapshotReady;

    public SidebarSession(Object menu, int menuId) {
        this.menu = Objects.requireNonNull(menu);
        this.menuId = menuId;
    }

    public long begin(Operation operation, long now) {
        if (pending != null || operation == Operation.UPLOAD && !snapshotReady) return 0;
        pending = Objects.requireNonNull(operation);
        requestId = IDS.incrementAndGet();
        startedAt = now;
        snapshotReady = false;
        return requestId;
    }

    public boolean accept(Object currentMenu, int replyMenuId, long replyId, Operation operation) {
        if (currentMenu != menu || replyMenuId != menuId || replyId != requestId || pending != operation) {
            return false;
        }
        pending = null;
        requestId = 0;
        snapshotReady = operation == Operation.QUERY;
        return true;
    }

    public boolean expire(long now) {
        if (pending == null || now - startedAt < 5000) return false;
        invalidate();
        return true;
    }

    public void invalidate() {
        pending = null;
        requestId = 0;
        snapshotReady = false;
    }

    public boolean busy() { return pending != null; }
    public boolean uploading() { return pending == Operation.UPLOAD; }
    public boolean canUpload() { return snapshotReady && pending == null; }
    public boolean owns(Object currentMenu) { return menu == currentMenu; }
    public int menuId() { return menuId; }

    public record Layout(int x, int y, int width, int height, boolean collapsed) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    /** Keep the existing AE2 left toolbar and every expanded panel pixel on screen. */
    public static Layout layout(int screenWidth, int screenHeight, int guiLeft, int guiTop, int guiHeight) {
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        int available = Math.min(width - 8, guiLeft - 30);
        if (available < 104 || height < 136) {
            int x = Math.min(4, width - 1);
            int y = Math.min(4, height - 1);
            return new Layout(x, y, Math.max(1, Math.min(144, width - x - 4)),
                Math.max(1, Math.min(20, height - y - 4)), true);
        }
        int panelWidth = Math.min(152, available);
        int x = Math.min(width - panelWidth - 4, guiLeft - 26 - panelWidth);
        int y = Math.max(4, Math.min(guiTop, height - 132));
        int panelHeight = Math.min(Math.max(128, guiHeight), height - y - 4);
        return new Layout(x, y, panelWidth, panelHeight, false);
    }
}
