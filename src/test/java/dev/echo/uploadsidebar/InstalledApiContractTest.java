package dev.echo.uploadsidebar;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the real installed integration boundary without initializing any Minecraft classes. */
public class InstalledApiContractTest {
    private static final String AE2 = "appliedenergistics2-" + System.getProperty("testAe2Version", "19.2.17") + ".jar";
    private static final String WTLIB = "ae2wtlib-19.5.0.jar";
    private static final String ENCODING_SCREEN = "appeng/client/gui/me/items/PatternEncodingTermScreen";
    private static final String ENCODING_MENU = "appeng/menu/me/items/PatternEncodingTermMenu";
    private static final String PROVIDER = "Lappeng/helpers/patternprovider/PatternContainer;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";

    @Test void wirelessEncodingTerminalUsesTheSameScreenAndMenuBase() throws Exception {
        assertEquals(ENCODING_SCREEN, inspect(WTLIB, "de/mari_023/ae2wtlib/wet/WETScreen").parent);
        assertEquals(ENCODING_MENU, inspect(WTLIB, "de/mari_023/ae2wtlib/wet/WETMenu").parent);
        inspect(AE2, ENCODING_SCREEN);
        inspect(AE2, ENCODING_MENU);
    }

    @Test void placedProviderScreensExposeTheExactMenuHostAndShareTheExtendedProviderBase() throws Exception {
        String providerScreen = "appeng/client/gui/implementations/PatternProviderScreen";
        String providerMenu = "appeng/menu/implementations/PatternProviderMenu";
        assertEquals("appeng/client/gui/AEBaseScreen", inspect(AE2, providerScreen).parent);
        assertEquals("appeng/menu/AEBaseMenu", inspect(AE2, providerMenu).parent);
        assertNotEquals(0, method(inspect(AE2, "appeng/menu/AEBaseMenu"), "getTarget", "()Ljava/lang/Object;") & Opcodes.ACC_PUBLIC);
        assertEquals(providerScreen, inspect("ExtendedAE-1.21-2.2.32-neoforge.jar", "com/glodblock/github/extendedae/client/gui/GuiExPatternProvider").parent);
        assertEquals(providerMenu, inspect("ExtendedAE-1.21-2.2.32-neoforge.jar", "com/glodblock/github/extendedae/container/ContainerExPatternProvider").parent);
    }

    @Test void aeBaseScreenExposesTheActualOutsideClickAndExclusionHooks() throws Exception {
        ApiClass screen = inspect(AE2, "appeng/client/gui/AEBaseScreen");
        int outside = method(screen, "hasClickedOutside", "(DDIII)Z");
        assertNotEquals(0, outside & Opcodes.ACC_PROTECTED);
        assertEquals(0, outside & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC));
        int exclusion = method(screen, "getExclusionZones", "()Ljava/util/List;");
        assertNotEquals(0, exclusion & Opcodes.ACC_PUBLIC);
        assertEquals(0, exclusion & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC));
        method(screen, "getGuiLeft", "()I");
        method(screen, "getGuiTop", "()I");
    }

    @Test void encodedPatternsCanBeSelectedByPublicSlotSemantics() throws Exception {
        method(inspect(AE2, "appeng/menu/AEBaseMenu"), "getSlots", "(Lappeng/menu/SlotSemantic;)Ljava/util/List;");
        assertEquals("Lappeng/menu/SlotSemantic;",
            inspect(AE2, "appeng/menu/SlotSemantics").fields.get("ENCODED_PATTERN"),
            "The upload path must continue reading the encoded-pattern slot, not a crafting preview");
    }

    @Test void encodingMixinTargetRemainsAnInstanceVoidMethod() throws Exception {
        int access = method(inspect(AE2, ENCODING_MENU), "encode", "()V");
        assertEquals(0, access & Opcodes.ACC_STATIC);
    }

    @Test void nativeGridCanEnumerateActivePatternContainers() throws Exception {
        int nodeAccess = method(inspect(AE2, "appeng/menu/me/common/MEStorageMenu"),
            "getGridNode", "()Lappeng/api/networking/IGridNode;");
        assertNotEquals(0, nodeAccess & Opcodes.ACC_PUBLIC);
        assertEquals(0, nodeAccess & Opcodes.ACC_STATIC);
        method(inspect(AE2, "appeng/api/networking/IGrid"), "getMachineClasses", "()Ljava/lang/Iterable;");
        method(inspect(AE2, "appeng/api/networking/IGrid"), "getActiveMachines", "(Ljava/lang/Class;)Ljava/util/Set;");
        method(inspect(AE2, "appeng/helpers/patternprovider/PatternContainer"), "isVisibleInTerminal", "()Z");
        method(inspect(AE2, "appeng/api/implementations/blockentities/PatternContainerGroup"), "name", "()Lnet/minecraft/network/chat/Component;");
    }

    @Test void providerInventoryAllowsCheckingAndInsertingIntoTheExactSlot() throws Exception {
        method(inspect(AE2, "appeng/helpers/patternprovider/PatternContainer"), "getTerminalPatternInventory",
            "()Lappeng/api/inventories/InternalInventory;");
        ApiClass inventory = inspect(AE2, "appeng/api/inventories/InternalInventory");
        method(inventory, "insertItem", "(I" + STACK + "Z)" + STACK);
        method(inventory, "isItemValid", "(I" + STACK + ")Z");
        method(inventory, "getSlotLimit", "(I)I");
    }

    private static int method(ApiClass type, String name, String descriptor) {
        Integer access = type.methods.get(name + descriptor);
        assertNotNull(access, "Installed API is missing " + name + descriptor);
        return access;
    }

    @Test void installedMatrixPatternCoreExposesNativePatternContainerContract() throws Exception {
        var matrix = inspect("ExtendedAE-1.21-2.2.32-neoforge.jar",
            "com/glodblock/github/extendedae/common/tileentities/matrix/TileAssemblerMatrixPattern");
        assertTrue(matrix.interfaces.contains("appeng/helpers/patternprovider/PatternContainer"));
        method(matrix, "getGrid", "()Lappeng/api/networking/IGrid;");
        method(matrix, "getTerminalPatternInventory", "()Lappeng/api/inventories/InternalInventory;");
        method(matrix, "getTerminalGroup", "()Lappeng/api/implementations/blockentities/PatternContainerGroup;");
    }

    private static void publicStatic(ApiClass type, String name, String descriptor) {
        int flags = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        assertEquals(flags, method(type, name, descriptor) & flags, name + " must stay callable as public static");
    }

    private static ApiClass inspect(String jarName, String internalName) throws Exception {
        Path mods = Path.of(System.getProperty("packMods",
            "test-dependencies"));
        ApiClass result = new ApiClass();
        try (ZipFile jar = new ZipFile(mods.resolve(jarName).toFile())) {
            var entry = jar.getEntry(internalName + ".class");
            assertNotNull(entry, jarName + " is missing " + internalName);
            try (InputStream bytes = jar.getInputStream(entry)) {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public void visit(int version, int access, String name, String signature,
                                                String superName, String[] interfaces) {
                        result.parent = superName;
                        result.interfaces = java.util.List.of(interfaces);
                    }
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        result.methods.put(name + descriptor, access);
                        return null;
                    }
                    @Override public FieldVisitor visitField(int access, String name, String descriptor,
                                                             String signature, Object value) {
                        result.fields.put(name, descriptor);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return result;
    }

    private static final class ApiClass {
        private String parent;
        private java.util.List<String> interfaces;
        private final Map<String, Integer> methods = new HashMap<>();
        private final Map<String, String> fields = new HashMap<>();
    }

    /** Allows an isolated API check while the complete mod is being built by another agent. */
    public static void main(String[] args) throws Exception {
        InstalledApiContractTest checks = new InstalledApiContractTest();
        checks.wirelessEncodingTerminalUsesTheSameScreenAndMenuBase();
        checks.aeBaseScreenExposesTheActualOutsideClickAndExclusionHooks();
        checks.encodedPatternsCanBeSelectedByPublicSlotSemantics();
        checks.nativeGridCanEnumerateActivePatternContainers();
        checks.providerInventoryAllowsCheckingAndInsertingIntoTheExactSlot();
        System.out.println("InstalledApiContractTest: 5 checks passed");
    }
}
