package dev.echo.uploadsidebar.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Versioned, menu-bound messages. No client-only classes are referenced here. */
public final class SidebarPackets {
    public static final int MAX_ENTRIES = 512;
    public static Consumer<Providers> providersReceiver = ignored -> {};
    public static Consumer<Result> resultReceiver = ignored -> {};
    public static Consumer<NamedProviders> namedProvidersReceiver = ignored -> {};

    private SidebarPackets() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("ae2_upload_sidebar", name));
    }

    public record Query(int menuId, long requestId) implements CustomPacketPayload {
        public static final Type<Query> TYPE = SidebarPackets.type("query_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = new StreamCodec<>() {
            public Query decode(RegistryFriendlyByteBuf b) { return new Query(b.readVarInt(), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, Query p) { b.writeVarInt(p.menuId); b.writeLong(p.requestId); }
        };
        public Type<Query> type() { return TYPE; }
    }

    public record Entry(UUID token, String name, int freeSlots, int totalSlots, ItemStack icon) {}

    /** Optional metadata negotiation: old query_v2/providers_v2 remain byte-for-byte unchanged. */
    public record NamedQuery(int menuId, long requestId) implements CustomPacketPayload {
        public static final Type<NamedQuery> TYPE = SidebarPackets.type("query_named_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, NamedQuery> CODEC = new StreamCodec<>() {
            public NamedQuery decode(RegistryFriendlyByteBuf b) { return new NamedQuery(b.readVarInt(), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, NamedQuery p) { b.writeVarInt(p.menuId); b.writeLong(p.requestId); }
        };
        public Type<NamedQuery> type() { return TYPE; }
    }

    /** Annotation identity is display metadata, never an upload credential. */
    public record Identity(UUID token, String key, String location) {
        public Identity {
            if (token == null || key == null || key.isBlank() || key.length() > 512
                    || location == null || location.isBlank() || location.length() > 256) {
                throw new IllegalArgumentException("Invalid provider annotation identity");
            }
        }
    }

    /** The metadata and concrete upload tokens belong to this exact Providers snapshot. */
    public record NamedProviders(Providers snapshot, List<Identity> identities) implements CustomPacketPayload {
        public static final Type<NamedProviders> TYPE = SidebarPackets.type("providers_named_v1");
        public NamedProviders {
            Objects.requireNonNull(snapshot, "snapshot");
            identities = List.copyOf(identities);
            if (identities.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many provider identities");
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, NamedProviders> CODEC = new StreamCodec<>() {
            public NamedProviders decode(RegistryFriendlyByteBuf b) {
                Providers snapshot = Providers.CODEC.decode(b);
                int size = b.readVarInt();
                if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("Invalid identity count");
                List<Identity> identities = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    identities.add(new Identity(b.readUUID(), b.readUtf(512), b.readUtf(256)));
                }
                return new NamedProviders(snapshot, identities);
            }
            public void encode(RegistryFriendlyByteBuf b, NamedProviders p) {
                Providers.CODEC.encode(b, p.snapshot);
                b.writeVarInt(p.identities.size());
                for (Identity identity : p.identities) {
                    b.writeUUID(identity.token); b.writeUtf(identity.key, 512); b.writeUtf(identity.location, 256);
                }
            }
        };
        public Type<NamedProviders> type() { return TYPE; }
    }

    public record Providers(int menuId, long requestId, List<Entry> entries, String message) implements CustomPacketPayload {
        public static final Type<Providers> TYPE = SidebarPackets.type("providers_v2");
        public Providers { entries = List.copyOf(entries); if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many targets"); }
        public static final StreamCodec<RegistryFriendlyByteBuf, Providers> CODEC = new StreamCodec<>() {
            public Providers decode(RegistryFriendlyByteBuf b) {
                int menu = b.readVarInt(); long request = b.readLong(); int size = b.readVarInt();
                if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("Invalid target count");
                List<Entry> result = new ArrayList<>(size);
                for (int i = 0; i < size; i++) result.add(new Entry(b.readUUID(), b.readUtf(256),
                        b.readVarInt(), b.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b)));
                return new Providers(menu, request, result, b.readUtf(256));
            }
            public void encode(RegistryFriendlyByteBuf b, Providers p) {
                b.writeVarInt(p.menuId); b.writeLong(p.requestId); b.writeVarInt(p.entries.size());
                for (Entry row : p.entries) {
                    b.writeUUID(row.token); b.writeUtf(row.name, 256); b.writeVarInt(row.freeSlots); b.writeVarInt(row.totalSlots);
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(b, row.icon);
                }
                b.writeUtf(p.message, 256);
            }
        };
        public Type<Providers> type() { return TYPE; }
    }

    public record Upload(int menuId, long operationId, UUID token, ItemStack expectedPattern) implements CustomPacketPayload {
        public static final Type<Upload> TYPE = SidebarPackets.type("upload_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, Upload> CODEC = new StreamCodec<>() {
            public Upload decode(RegistryFriendlyByteBuf b) {
                return new Upload(b.readVarInt(), b.readLong(), b.readUUID(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
            }
            public void encode(RegistryFriendlyByteBuf b, Upload p) {
                b.writeVarInt(p.menuId); b.writeLong(p.operationId); b.writeUUID(p.token);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(b, p.expectedPattern);
            }
        };
        public Type<Upload> type() { return TYPE; }
    }

    public record Result(int menuId, long operationId, boolean success, String message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = SidebarPackets.type("result_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = new StreamCodec<>() {
            public Result decode(RegistryFriendlyByteBuf b) { return new Result(b.readVarInt(), b.readLong(), b.readBoolean(), b.readUtf(256)); }
            public void encode(RegistryFriendlyByteBuf b, Result p) {
                b.writeVarInt(p.menuId); b.writeLong(p.operationId); b.writeBoolean(p.success); b.writeUtf(p.message, 256);
            }
        };
        public Type<Result> type() { return TYPE; }
    }
}
