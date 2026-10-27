package dev.echo.uploadsidebar.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderSearchTest {
    @Test void fullInitialsAndOriginalNames() {
        for (String query : List.of("zhumojitan", "ZMJT", "zhu mo ji tan", "注魔", "jitan"))
            assertTrue(ProviderSearch.matches("ME接口\n注魔祭坛", query), query);
        assertTrue(ProviderSearch.matches("ME接口", "mejk"));
        assertTrue(ProviderSearch.matches("分子装配室", "fzzps"));
        assertTrue(ProviderSearch.matches("ME Interface", "INTERFACE"));
        assertFalse(ProviderSearch.matches("ME接口\n注魔祭坛", "jkzm"));
        assertFalse(ProviderSearch.matches("注魔祭坛", "fenyezi"));
        assertTrue(ProviderSearch.matches("任意", ""));
    }
    @Test void polyphonicAndUmlaut() {
        assertTrue(ProviderSearch.matches("重型机器", "zhongxing"));
        assertTrue(ProviderSearch.matches("重庆", "chongqing"));
        assertTrue(ProviderSearch.matches("铝锭", "lvding"));
        assertTrue(ProviderSearch.matches("铝锭", "lüding"));
        assertFalse(ProviderSearch.matches("重".repeat(80), "zhong".repeat(79) + "wrong"));
    }
    @Test void favoritesAreStableAndDoNotBypassFilter() {
        var order = ProviderSearch.favoritesFirst(List.of("普通A", "收藏A", "普通B", "收藏B"), n -> n.startsWith("收藏"));
        assertEquals(List.of("收藏A", "收藏B", "普通A", "普通B"), order);
        assertEquals(List.of("普通A", "普通B"), order.stream().filter(n -> ProviderSearch.matches(n, "putong")).toList());
    }
}
