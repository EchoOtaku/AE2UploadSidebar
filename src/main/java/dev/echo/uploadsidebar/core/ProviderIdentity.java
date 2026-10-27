package dev.echo.uploadsidebar.core;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** A location annotation key, never authorization to upload or a physical-block lifetime ID. */
public record ProviderIdentity(String key, String location) {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Set<String> SIDES = Set.of("down", "up", "north", "south", "west", "east");

    public ProviderIdentity {
        if (key == null || key.isBlank() || key.length() > 512
                || location == null || location.isBlank() || location.length() > 256) {
            throw new IllegalArgumentException("Invalid provider annotation identity");
        }
    }

    public static ProviderIdentity block(String dimension, String type, int x, int y, int z) {
        return create("block", dimension, type, x, y, z, "");
    }

    public static ProviderIdentity part(String dimension, String type, int x, int y, int z, String side) {
        if (side == null || !SIDES.contains(side)) throw new IllegalArgumentException("Unknown part side");
        return create("part", dimension, type, x, y, z, side);
    }

    /** Reject every ambiguous location, without merging or replacing any upload target. */
    public static <T> List<T> uniqueKeys(List<T> entries, Function<T, String> keyOf) {
        Map<String, Integer> counts = new HashMap<>();
        for (T entry : entries) counts.merge(keyOf.apply(entry), 1, Integer::sum);
        return entries.stream().filter(entry -> counts.get(keyOf.apply(entry)) == 1).toList();
    }

    private static ProviderIdentity create(String kind, String dimension, String type,
                                           int x, int y, int z, String side) {
        if (dimension == null || type == null || !RESOURCE_ID.matcher(dimension).matches()
                || !RESOURCE_ID.matcher(type).matches()) {
            throw new IllegalArgumentException("Provider dimension and type must be registry IDs");
        }
        // Full coordinates avoid both terminal-sort collisions and BlockPos packing bounds.
        // Same-location, same-type replacements intentionally inherit the local annotation.
        String key = "v1|" + kind + "|" + dimension + "|" + type + "|" + x + "|" + y + "|" + z;
        String location = dimension + " (" + x + ", " + y + ", " + z + ")";
        if (!side.isEmpty()) {
            key += "|" + side;
            location += " [" + side + "]";
        }
        return new ProviderIdentity(key, location);
    }
}
