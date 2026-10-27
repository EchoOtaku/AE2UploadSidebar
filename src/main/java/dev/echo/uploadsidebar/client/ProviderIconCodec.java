package dev.echo.uploadsidebar.client;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.JsonOps;
import dev.echo.uploadsidebar.core.ProviderIconsStore;
import java.io.IOException;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Full component-aware local display snapshots. No source-stack references or inventory operations. */
public final class ProviderIconCodec {
    private static final int MAX_JSON_DEPTH = 64;

    private ProviderIconCodec() { }

    /** Empty explicitly restores the default. Invalid nonempty selections throw, never silently clear. */
    public static String encode(ItemStack source, HolderLookup.Provider registries) {
        if (source == null) throw new IllegalArgumentException("请选择有效的物品图标");
        if (source.isEmpty()) return "";
        if (registries == null) throw new IllegalArgumentException("当前世界注册表不可用，请重新打开终端");
        try {
            var encoded = ItemStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
                    source.copyWithCount(1))
                .getOrThrow(ignored -> new IllegalArgumentException("此物品的图标组件无法保存"));
            String json = encoded.toString();
            if (!boundedJson(json)) throw new IllegalArgumentException("图标数据过大或过于复杂，最多 16 KiB、64 层");
            // Do not commit data which is already invalid in the current registry, including partial codec errors.
            if (decode(json, registries).isEmpty()) throw new IllegalArgumentException("此物品的图标组件无法恢复");
            return json;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("此物品的图标数据无法保存", failure);
        }
    }

    /** Invalid, oversize, removed-item and changed-component data fall back without logging private data. */
    public static Optional<ItemStack> decode(String json, HolderLookup.Provider registries) {
        if (registries == null || !boundedJson(json)) return Optional.empty();
        try {
            var element = JsonParser.parseString(json);
            if (!element.isJsonObject()) return Optional.empty();
            return ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), element)
                .result().filter(stack -> !stack.isEmpty()).map(stack -> stack.copyWithCount(1));
        } catch (RuntimeException invalidOrRemovedItem) {
            return Optional.empty();
        }
    }

    /** Validate strict JSON iteratively before recursive parsing; Gson's tree parser is otherwise lenient. */
    private static boolean boundedJson(String json) {
        if (json == null || json.isBlank() || json.length() > ProviderIconsStore.MAX_ICON_BYTES) return false;
        try {
            if (StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(json)).remaining()
                    > ProviderIconsStore.MAX_ICON_BYTES) return false;
        } catch (CharacterCodingException malformedUtf16) { return false; }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            int depth = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                switch (reader.peek()) {
                    case BEGIN_OBJECT -> { if (++depth > MAX_JSON_DEPTH) return false; reader.beginObject(); }
                    case BEGIN_ARRAY -> { if (++depth > MAX_JSON_DEPTH) return false; reader.beginArray(); }
                    case END_OBJECT -> { reader.endObject(); depth--; }
                    case END_ARRAY -> { reader.endArray(); depth--; }
                    case NAME -> reader.nextName();
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    default -> { return false; }
                }
            }
            return depth == 0;
        } catch (IOException | IllegalStateException malformedJson) { return false; }
    }
}
