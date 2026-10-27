package dev.echo.uploadsidebar.client;

import dev.echo.uploadsidebar.core.RecipeMappings.RecipeTypeMapping;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/** Optional boundary: no EAEP types appear in fields, signatures, or class literals. */
final class EaepMappings {
    private static final String API = "com.extendedae_plus.util.uploadPattern.ExtendedAEPatternUploadUtil";
    private static final String SCREEN = "com.extendedae_plus.client.screen.RecipeTypeMappingScreen";
    private static Class<?> api;
    private static String error = "";

    private static Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            if (api == null) api = Class.forName(API);
            Method method = api.getMethod(name, types);
            Object result = method.invoke(null, args);
            error = "";
            return result;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw failed(failure);
        }
    }

    private static IllegalStateException failed(Throwable failure) {
        error = "EAEP 映射接口不可用，请检查版本；未切换或写入独立映射";
        return new IllegalStateException(error, failure);
    }
    static String error() { return error; }
    static List<RecipeTypeMapping> list() {
        Object result = invoke("getRecipeTypeMappings", new Class<?>[0]);
        if (!(result instanceof List<?> rows)) throw failed(new IllegalStateException("Expected mapping list"));
        var mappings = new ArrayList<RecipeTypeMapping>();
        try {
            for (Object row : rows) {
                String key = (String) row.getClass().getMethod("key").invoke(row);
                String value = (String) row.getClass().getMethod("value").invoke(row);
                if (key == null || value == null) throw new IllegalStateException("Invalid mapping");
                mappings.add(new RecipeTypeMapping(key, value));
            }
            return List.copyOf(mappings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw failed(failure); }
    }
    static String resolveType(ResourceLocation type, String fallback) {
        return (String) invoke("resolveRecipeTypeSearchKey", new Class<?>[]{ResourceLocation.class, String.class}, type, fallback);
    }
    static String resolveProvider(String key) {
        return (String) invoke("resolveProviderSearchKey", new Class<?>[]{String.class}, key);
    }
    static boolean put(String key, String value) {
        return Boolean.TRUE.equals(invoke("addOrUpdateRecipeTypeMapping", new Class<?>[]{String.class, String.class}, key, value));
    }
    static boolean remove(String key) {
        return Boolean.TRUE.equals(invoke("removeRecipeTypeMapping", new Class<?>[]{String.class}, key));
    }
    static Screen manager(Screen parent) {
        try {
            Screen result = (Screen) Class.forName(SCREEN).getConstructor(Screen.class).newInstance(parent);
            error = "";
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw failed(failure); }
    }
}
