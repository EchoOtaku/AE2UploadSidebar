package dev.echo.uploadsidebar;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;

/** Bytecode boundaries, with no Minecraft initialization, network access, or configuration writes. */
public final class NotePrivacyContractTest {
    private static final String ROOT = "dev/echo/uploadsidebar/";
    private static final String NOTES = ROOT + "client/SidebarNotes";
    private static final String STORE = ROOT + "core/ProviderNotesStore";
    private static final String ICONS_STORE = ROOT + "core/ProviderIconsStore";
    private static final String ICON_CODEC = ROOT + "client/ProviderIconCodec";
    private static final String EDITOR = ROOT + "client/NoteEditorScreen";
    private static final String UPLOAD = ROOT + "net/SidebarPackets$Upload";
    private static final String NOTES_FILE = "ae2-upload-sidebar-notes.properties";
    private static final String ICONS_FILE = "ae2-upload-sidebar-icons.properties";
    private static final Set<String> PRIVATE_LAYERS = Set.of(NOTES, STORE, ICONS_STORE, ICON_CODEC, EDITOR);
    private static final Set<String> PERSISTENCE_LAYERS = Set.of(STORE, ICONS_STORE);
    private static final Set<String> FORBIDDEN_TYPES = Set.of(
        "net/neoforged/neoforge/network/PacketDistributor", ROOT + "server/ServerUploads");
    private static final Set<String> COMPOUND_WRITES = Set.of("put", "putByte", "putShort", "putInt",
        "putLong", "putUUID", "putFloat", "putDouble", "putString", "putByteArray", "putIntArray",
        "putLongArray", "putBoolean", "remove", "merge");
    private static final Set<String> FILE_WRITES = Set.of("write", "writeString", "newOutputStream",
        "newBufferedWriter", "createFile", "createDirectories", "createDirectory", "createTempFile",
        "createTempDirectory", "move", "copy", "delete", "deleteIfExists");

    @Test void noteLayersCannotUploadRenameWorldObjectsOrWriteNbt() throws Exception {
        for (Facts facts : noteClasses()) {
            for (String referenced : facts.types) {
                assertFalse(FORBIDDEN_TYPES.stream().anyMatch(type -> referenced.equals(type) || referenced.startsWith(type + "$")),
                    facts.name + " references forbidden network/server type " + referenced);
            }
            for (Call call : facts.calls) {
                String at = facts.name + "." + call.method + " -> " + call.owner + "." + call.name;
                assertFalse(call.owner.equals(ROOT + "client/SidebarClient") && call.name.equals("upload"), at);
                assertFalse(Set.of("net/minecraft/network/Connection", "net/minecraft/client/multiplayer/ClientPacketListener",
                    "net/minecraft/server/network/ServerGamePacketListenerImpl").contains(call.owner)
                    && Set.of("send", "sendChat", "sendCommand", "sendUnsignedCommand").contains(call.name), at);
                assertFalse((call.owner.startsWith("net/minecraft/world/") || call.owner.startsWith("appeng/"))
                    && call.name.equals("setCustomName"), at);
                assertFalse(call.owner.equals("net/minecraft/nbt/CompoundTag") && COMPOUND_WRITES.contains(call.name), at);
                assertFalse(call.owner.equals("net/minecraft/nbt/ListTag")
                    && Set.of("add", "addTag", "set", "setTag", "remove", "clear").contains(call.name), at);
                assertFalse(call.owner.equals("net/minecraft/nbt/NbtIo")
                    && Set.of("write", "writeCompressed", "writeAnyTag", "writeUnnamedTag").contains(call.name), at);
                assertFalse(call.owner.equals("net/minecraft/world/item/ItemStack")
                    && Set.of("set", "remove", "setCount", "grow", "shrink", "split", "copyAndClear",
                        "consume", "consumeAndReturn", "setDamageValue", "hurtAndBreak", "setPopTime",
                        "applyComponents", "applyComponentsAndValidate", "applyComponentsPatch", "save", "saveOptional")
                        .contains(call.name), at);
                assertFalse((call.owner.equals("net/minecraft/world/inventory/Slot")
                    || call.owner.startsWith("appeng/menu/slot/") || call.owner.equals("appeng/api/inventories/InternalInventory"))
                    && Set.of("set", "setByPlayer", "setChanged", "remove", "safeInsert", "safeTake",
                        "setItemDirect", "insertItem", "extractItem", "setFilterTo", "increase", "decrease", "clearStack")
                        .contains(call.name), at);
                assertFalse((call.owner.startsWith("net/minecraft/world/inventory/")
                    || call.owner.equals("net/minecraft/world/entity/player/Inventory")
                    || call.owner.equals("net/minecraft/world/Container"))
                    && Set.of("setCarried", "setItem", "removeItem", "removeItemNoUpdate", "clearContent").contains(call.name), at);
                assertFalse((call.owner.startsWith("net/minecraft/world/level/")
                    || call.owner.equals("net/minecraft/server/level/ServerLevel"))
                    && Set.of("setBlock", "setBlockAndUpdate", "removeBlock", "destroyBlock", "addFreshEntity",
                        "setChanged", "setBlockEntity", "removeBlockEntity").contains(call.name), at);
                assertFalse(call.owner.startsWith("net/minecraft/world/level/block/entity/")
                    && Set.of("saveAdditional", "saveWithFullMetadata", "saveWithoutMetadata", "saveCustomOnly", "saveToItem").contains(call.name), at);
            }
        }
    }

    @Test void noteAndIconPersistenceTargetExactlyTheirSeparateClientConfigurationFiles() throws Exception {
        Facts notes = read(NOTES);
        assertEquals(Set.of(NOTES_FILE, ICONS_FILE, "ae2-upload-sidebar-favorites.properties"), new HashSet<>(notes.strings.stream()
            .filter(value -> value.endsWith(".properties") || value.endsWith(".json") || value.endsWith(".toml") || value.endsWith(".nbt"))
            .toList()), "Private display data must not acquire another mod's or a world's configuration file");
        assertFactory(notes, "store", STORE, NOTES_FILE);
        assertFactory(notes, "iconsStore", ICONS_STORE, ICONS_FILE);
        assertFactory(notes, "favorites", "dev/echo/uploadsidebar/core/ProviderFavoritesStore", "ae2-upload-sidebar-favorites.properties");
        assertEquals(3, notes.calls.stream().filter(call -> call.owner.equals("java/nio/file/Path")
            && call.name.equals("resolve")).count(), "Only the three fixed configuration targets may be resolved");

        for (String storeName : PERSISTENCE_LAYERS) {
            Facts store = read(storeName);
            assertEquals(List.of("file"), store.declaredFields.stream()
                .filter(field -> field.descriptor.equals("Ljava/nio/file/Path;"))
                .peek(field -> assertNotEquals(0, field.access & Opcodes.ACC_FINAL, "The persistence target must be final"))
                .map(FieldDeclaration::name).toList());
            assertTrue(store.fields.stream().filter(field -> field.opcode == Opcodes.PUTFIELD
                && field.owner.equals(storeName) && field.name.equals("file")).allMatch(field -> field.method.equals("<init>")),
                "Saving private display data must not replace its configured target path");
            assertFalse(store.calls.stream().anyMatch(call -> call.owner.equals("java/nio/file/Path")
                && Set.of("of", "resolve", "resolveSibling").contains(call.name)), "Each store has exactly one configured target");
            assertFalse(store.types.stream().anyMatch(type -> PERSISTENCE_LAYERS.contains(type) && !type.equals(storeName)),
                "Notes and icons stores must not access or delegate to each other's persistence");
        }
        for (Facts facts : noteClasses()) {
            for (Call call : facts.calls) {
                if (call.owner.equals("java/nio/file/Files") && FILE_WRITES.contains(call.name)
                        || Set.of("java/io/FileOutputStream", "java/io/FileWriter", "java/io/RandomAccessFile").contains(call.owner)
                        && call.name.equals("<init>")) {
                    assertTrue(PERSISTENCE_LAYERS.contains(facts.name), "Only the two dedicated stores may perform filesystem writes");
                }
            }
        }
    }

    private static void assertFactory(Facts notes, String factory, String storeType, String filename) {
        assertEquals(List.of(filename), notes.stringUses.stream().filter(use -> use.method.equals(factory))
            .map(StringUse::value).toList(), "Each store factory must use its own exact filename, never the other's");
        assertEquals(List.of("net/neoforged/fml/loading/FMLPaths.get()Ljava/nio/file/Path;",
            "java/nio/file/Path.resolve(Ljava/lang/String;)Ljava/nio/file/Path;", storeType + ".<init>(Ljava/nio/file/Path;)V"),
            notes.calls.stream().filter(call -> call.method.equals(factory))
                .map(call -> call.owner + "." + call.name + call.descriptor).toList(),
            "The fixed filename must resolve directly under this client's CONFIGDIR into its dedicated store");
        assertEquals(1, notes.fields.stream().filter(field -> field.method.equals(factory)
            && field.opcode == Opcodes.GETSTATIC && field.owner.equals("net/neoforged/fml/loading/FMLPaths")
            && field.name.equals("CONFIGDIR")).count(), "Private display data belongs only to the client's config directory");
        assertTrue(notes.fields.stream().filter(field -> field.method.equals(factory)).allMatch(field ->
            field.owner.equals(NOTES) && field.name.equals(factory) && field.descriptor.equals("L" + storeType + ";")
                || field.owner.equals("net/neoforged/fml/loading/FMLPaths") && field.name.equals("CONFIGDIR")),
            "A store factory cannot substitute another filesystem root or another store's target");
    }

    @Test void existingUploadV2WireShapeContainsNoPrivateRemark() throws Exception {
        Facts payload = read(UPLOAD);
        assertEquals(List.of("menuId:I", "operationId:J", "token:Ljava/util/UUID;",
            "expectedPattern:Lnet/minecraft/world/item/ItemStack;"), payload.recordComponents,
            "Adding a note/alias to the existing upload payload would disclose private client data");
        assertTrue(payload.strings.contains("upload_v2"), "The original upload channel must remain unchanged");
        Facts codec = read(UPLOAD + "$1");
        assertEquals(List.of("menuId", "operationId", "token", "expectedPattern"), codec.fields.stream()
            .filter(field -> field.method.equals("encode") && field.opcode == Opcodes.GETFIELD && field.owner.equals(UPLOAD))
            .map(FieldUse::name).toList(), "Encoding must read only the original four upload fields");
        assertEquals(List.of("writeVarInt", "writeLong", "writeUUID"), codec.calls.stream()
            .filter(call -> call.method.equals("encode") && Set.of("net/minecraft/network/RegistryFriendlyByteBuf",
                "net/minecraft/network/FriendlyByteBuf", "io/netty/buffer/ByteBuf").contains(call.owner))
            .map(Call::name).toList(), "Upload encoding must not append note text or other extra buffer data");
        assertFalse(codec.calls.stream().anyMatch(call -> PRIVATE_LAYERS.contains(call.owner)),
            "The wire encoder must never consult private notes, icon persistence or the display icon codec");
    }

    private List<Facts> noteClasses() throws Exception {
        var remaining = new ArrayDeque<>(PRIVATE_LAYERS);
        var visited = new HashSet<String>();
        var result = new ArrayList<Facts>();
        while (!remaining.isEmpty()) {
            String name = remaining.removeFirst();
            if (!visited.add(name)) continue;
            Facts facts = read(name);
            result.add(facts);
            facts.innerClasses.stream().filter(inner -> inner.startsWith(name + "$"))
                .forEach(remaining::addLast);
        }
        return result;
    }

    private Facts read(String name) throws Exception {
        Facts facts = new Facts(name);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Compiled class is missing: " + name);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visitInnerClass(String inner, String outer, String simpleName, int access) {
                    facts.innerClasses.add(inner);
                }
                @Override public RecordComponentVisitor visitRecordComponent(String field, String descriptor, String signature) {
                    facts.recordComponents.add(field + ":" + descriptor);
                    return null;
                }
                @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                    facts.declaredFields.add(new FieldDeclaration(field, descriptor, access));
                    facts.addDescriptor(descriptor);
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    facts.addDescriptor(descriptor);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitTypeInsn(int opcode, String type) { facts.types.add(type); }
                        @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean isInterface) {
                            facts.types.add(owner);
                            facts.addDescriptor(descriptor);
                            facts.calls.add(new Call(method, owner, called, descriptor));
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            facts.types.add(owner);
                            facts.addDescriptor(descriptor);
                            facts.fields.add(new FieldUse(method, owner, field, descriptor, opcode));
                        }
                        @Override public void visitLdcInsn(Object value) {
                            if (value instanceof String text) {
                                facts.strings.add(text);
                                facts.stringUses.add(new StringUse(method, text));
                            }
                            if (value instanceof Type type) facts.addType(type);
                            if (value instanceof Handle handle) addHandle(handle);
                        }
                        @Override public void visitInvokeDynamicInsn(String called, String descriptor, Handle bootstrap, Object... arguments) {
                            facts.addDescriptor(descriptor);
                            addHandle(bootstrap);
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle) addHandle(handle);
                                if (argument instanceof Type type) facts.addType(type);
                            }
                        }
                        private void addHandle(Handle handle) {
                            facts.types.add(handle.getOwner());
                            facts.addDescriptor(handle.getDesc());
                            facts.calls.add(new Call(method, handle.getOwner(), handle.getName(), handle.getDesc()));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return facts;
    }

    private record Call(String method, String owner, String name, String descriptor) { }
    private record StringUse(String method, String value) { }
    private record FieldUse(String method, String owner, String name, String descriptor, int opcode) { }
    private record FieldDeclaration(String name, String descriptor, int access) { }
    private static final class Facts {
        final String name;
        final List<Call> calls = new ArrayList<>();
        final List<FieldUse> fields = new ArrayList<>();
        final List<FieldDeclaration> declaredFields = new ArrayList<>();
        final List<String> strings = new ArrayList<>();
        final List<StringUse> stringUses = new ArrayList<>();
        final List<String> recordComponents = new ArrayList<>();
        final Set<String> types = new HashSet<>();
        final Set<String> innerClasses = new HashSet<>();
        Facts(String name) { this.name = name; }
        void addDescriptor(String descriptor) { addType(Type.getType(descriptor)); }
        void addType(Type type) {
            if (type.getSort() == Type.METHOD) {
                for (Type argument : type.getArgumentTypes()) addType(argument);
                addType(type.getReturnType());
            } else if (type.getSort() == Type.ARRAY) addType(type.getElementType());
            else if (type.getSort() == Type.OBJECT) types.add(type.getInternalName());
        }
    }
}
