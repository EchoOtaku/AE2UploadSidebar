package dev.echo.uploadsidebar.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.echo.uploadsidebar.core.RecipeIngredients.Amount;

class RecipeIngredientsTest {
    private static Amount<String> item(String key, long count) { return new Amount<>(key, count); }

    @Test void matchesAlternativesMergedInputsAndExactAmounts() {
        var recipe = List.of(List.of(item("oak", 1), item("birch", 1)), List.of(item("oak", 2)));
        assertTrue(RecipeIngredients.matches(recipe, List.of(item("oak", 3))));
        assertTrue(RecipeIngredients.matches(recipe, List.of(item("birch", 1), item("oak", 2))));
        assertFalse(RecipeIngredients.matches(recipe, List.of(item("oak", 2))));
        assertFalse(RecipeIngredients.matches(recipe, List.of(item("oak", 3), item("extra", 1))));
        assertFalse(RecipeIngredients.matches(recipe, List.of(item("birch", 3))));
    }

    @Test void resolvesAmbiguousAlternativesWithoutGreedyMisclassification() {
        var recipe = List.of(List.of(item("a", 1), item("b", 1)), List.of(item("a", 1)));
        assertTrue(RecipeIngredients.matches(recipe, List.of(item("a", 1), item("b", 1))));
        assertFalse(RecipeIngredients.matches(recipe, List.of(item("b", 2))));
    }

    @Test void rejectsInvalidAmountsEmptyRecipesAndOverflow() {
        assertFalse(RecipeIngredients.matches(List.of(), List.of(item("a", 1))));
        assertFalse(RecipeIngredients.matches(List.of(List.of(item("a", 1))), List.of(item("a", 0))));
        assertFalse(RecipeIngredients.matches(List.of(List.of(item("a", 1))), List.of(item("a", Long.MAX_VALUE), item("a", 1))));
        assertFalse(RecipeIngredients.matches(List.of(List.of(item("a", -1))), List.of(item("a", 1))));
    }
}
