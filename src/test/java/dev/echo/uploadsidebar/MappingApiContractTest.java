package dev.echo.uploadsidebar;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Probes the installed mapping APIs without initializing Minecraft or writing configuration. */
public final class MappingApiContractTest {
    private static final String AE2 = "appliedenergistics2-" + System.getProperty("testAe2Version", "19.2.17") + ".jar";
    private static final String HELPER = "com/extendedae_plus/util/uploadPattern/ExtendedAEPatternUploadUtil";



    @Test void installedJeiTransferBoundaryExposesExactCategoryAndSuccessWithoutReplacingHandlers() throws Exception {
        var util = methods("jei-1.21.1-neoforge-19.27.0.336.jar", "mezz/jei/common/transfer/RecipeTransferUtil");
        assertTrue(util.containsKey("transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferManager;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;ZZ)Ljava/util/Optional;"));
        var helper = methods("ae2jeiintegration-1.2.1.jar", "tamaized/ae2jeiintegration/integration/modules/jei/GenericEntryStackHelper");
        publicStatic(helper, "ofInputs(Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;)Ljava/util/List;");
        publicStatic(helper, "ofOutputs(Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;)Ljava/util/List;");
    }



    @Test void craftingCarriesAuthoritativeRecipeIdentityButProcessingDoesNot() throws Exception {
        String recipeId = "recipeId()Lnet/minecraft/resources/ResourceLocation;";
        for (String type : new String[] {"EncodedCraftingPattern", "EncodedStonecuttingPattern", "EncodedSmithingTablePattern"}) {
            assertTrue(methods(AE2, "appeng/crafting/pattern/" + type).containsKey(recipeId), type);
        }
        assertFalse(methods(AE2, "appeng/crafting/pattern/EncodedProcessingPattern").containsKey(recipeId),
            "Processing cannot safely inherit a recipe type from a global last-JEI key");
    }

    @Test void mappingControlsNeverSendUploadsOrConsumeAnotherModsGlobalJeiContext() throws Exception {
        for (String className : new String[] {"client/SidebarMappings", "client/SidebarMappings$MappingSelectorScreen",
                "client/SidebarRecipeContext", "client/MappingManagerScreen", "client/SidebarJeiPlugin", "mixin/JeiRecipeTransferMixin"}) {
            String resource = "dev/echo/uploadsidebar/" + className + ".class";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String called,
                                                                  String descriptor, boolean isInterface) {
                                assertFalse(owner.equals("net/neoforged/neoforge/network/PacketDistributor"),
                                    "Mapping UI must not distribute network payloads");
                                assertFalse(owner.equals("dev/echo/uploadsidebar/client/SidebarClient") && called.equals("upload"),
                                    "Mapping selection must not auto-upload");
                                if (owner.equals(HELPER)) {
                                    assertFalse(called.startsWith("upload") || called.equals("consumeLastProviderSearchKey")
                                            || called.startsWith("setLast") || called.equals("presetCraftingProviderSearchKey"),
                                        "Mapping UI must not upload or consume/mutate EAEP's global JEI context: " + called);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    private static void publicStatic(Map<String, Integer> methods, String signature) {
        int expected = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        assertEquals(expected, methods.getOrDefault(signature, 0) & expected, signature);
    }

    private static Map<String, Integer> methods(String jarName, String className) throws Exception {
        Path mods = Path.of(System.getProperty("packMods",
            "test-dependencies"));
        Map<String, Integer> result = new HashMap<>();
        try (ZipFile jar = new ZipFile(mods.resolve(jarName).toFile())) {
            var entry = jar.getEntry(className + ".class");
            assertNotNull(entry, className);
            try (InputStream input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        result.put(name + descriptor, access);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return result;
    }
}
