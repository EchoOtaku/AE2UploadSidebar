package dev.echo.uploadsidebar.smoke;

import dev.echo.uploadsidebar.client.ProviderIconCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/** Component-aware codec checks use the actual running client's synced registry. */
final class IconCodecAssertions {
    static void verify(Minecraft client) {
        var registries = client.level.registryAccess();
        ItemStack source = new ItemStack(Items.DIAMOND, 7);
        source.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(105));
        ItemStack before = source.copy();
        String encoded = ProviderIconCodec.encode(source, registries);
        ItemStack decoded = ProviderIconCodec.decode(encoded, registries).orElseThrow();
        require(decoded != source && decoded.getCount() == 1
            && decoded.get(DataComponents.CUSTOM_MODEL_DATA).value() == 105,
            "real registry icon round-trip preserves custom components and normalizes count to one");
        require(ItemStack.matches(source, before), "encoding does not modify the original seven-item stack");
        decoded.setCount(4);
        decoded.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(999));
        ItemStack independent = ProviderIconCodec.decode(encoded, registries).orElseThrow();
        require(independent != decoded && independent.getCount() == 1
            && independent.get(DataComponents.CUSTOM_MODEL_DATA).value() == 105
            && ItemStack.matches(source, before), "repeated decode returns independent component-preserving display copies");
        require(ProviderIconCodec.encode(ItemStack.EMPTY, registries).isEmpty(), "empty codec selection represents explicit default reset");
        require(ProviderIconCodec.decode("{\"id\":\"missing_icon:removed\",\"count\":1}", registries).isEmpty(),
            "removed item identifiers safely reject local icon data");
        require(ProviderIconCodec.decode("{broken", registries).isEmpty(), "malformed icon data safely rejects");
        String oversize = "{\"id\":\"minecraft:diamond\",\"count\":1,\"ignored\":\"" + "x".repeat(16385) + "\"}";
        require(ProviderIconCodec.decode(oversize, registries).isEmpty(), "otherwise valid item JSON exceeding 16 KiB safely rejects");
        String nested = "{\"id\":\"minecraft:diamond\",\"count\":1,\"ignored\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}";
        require(ProviderIconCodec.decode(nested, registries).isEmpty(), "otherwise valid item JSON exceeding 64 nesting levels safely rejects");
    }

    static String report() {
        return "private_icon_real_registry_components_roundtrip_and_source_unchanged=PASS\n"
            + "private_icon_decoded_copies_independent=PASS\n"
            + "private_icon_invalid_removed_oversize_and_depth_limits=PASS\n";
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("SMOKE_ICON_CODEC_ASSERT: " + message);
    }
}
