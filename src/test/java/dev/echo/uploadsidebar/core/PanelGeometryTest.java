package dev.echo.uploadsidebar.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PanelGeometryTest {
    @Test void defaultPlacementKeepsAe2ToolbarClear() {
        var box = PanelGeometry.layout(720, 450, 262, 20, 360,
            new PanelGeometry.Settings(-1, -1, 152, 5, PanelGeometry.HeightMode.STANDARD, false, false));
        assertFalse(box.collapsed());
        assertTrue(box.x() + box.width() <= 262 - 26);
        assertEquals(5, PanelGeometry.rowCount(box));
    }

    @Test void tinyMarginsCollapseButExplicitExpansionIsRecoverable() {
        var automatic = new PanelGeometry.Settings(-1, -1, 152, 5,
            PanelGeometry.HeightMode.STANDARD, false, false);
        assertTrue(PanelGeometry.layout(480, 300, 110, 6, 288, automatic).collapsed());
        var expanded = new PanelGeometry.Settings(-1, -1, 152, 5,
            PanelGeometry.HeightMode.STANDARD, false, true);
        assertFalse(PanelGeometry.layout(480, 300, 110, 6, 288, expanded).collapsed());
    }

    @Test void savedPlacementAndAllSizesStayInsideViewport() {
        for (int width : new int[] {1, 80, 240, 480, 720}) {
            for (int height : new int[] {1, 60, 180, 300, 540}) {
                for (double position : new double[] {0, .4, 1, 9}) {
                    var settings = new PanelGeometry.Settings(position, position, 900, 99,
                        PanelGeometry.HeightMode.TERMINAL, false, true);
                    var box = PanelGeometry.layout(width, height, 200, 30, 360, settings);
                    assertTrue(box.x() >= 0 && box.y() >= 0);
                    assertTrue(box.width() > 0 && box.height() > 0);
                    assertTrue(box.x() + box.width() <= width);
                    assertTrue(box.y() + box.height() <= height);
                }
            }
        }
    }

    @Test void modeHeightsAndNormalizedDragAreStable() {
        var compact = new PanelGeometry.Settings(.5, .5, 180, 8,
            PanelGeometry.HeightMode.COMPACT, false, true);
        var box = PanelGeometry.layout(800, 600, 300, 20, 360, compact);
        assertEquals(3, PanelGeometry.rowCount(box));
        assertEquals(.5, PanelGeometry.normalized(310, 800, 180));
        assertEquals(0, PanelGeometry.normalized(-50, 800, 180));
        assertEquals(1, PanelGeometry.normalized(900, 800, 180));
        assertEquals(0, PanelGeometry.normalized(0, 1, 1));
    }
}
