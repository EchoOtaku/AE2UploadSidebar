package dev.echo.uploadsidebar.core;

import java.util.Optional;

/** Parses only our versioned server identity, never a translated name or human-readable location label. */
public record ProviderLocation(String dimension, String type, int x, int y, int z, String side) {
    public static Optional<ProviderLocation> parse(String key) {
        if (key == null || key.length() > 512) return Optional.empty();
        String[] fields = key.split("\\|", -1);
        if (fields.length < 7 || !fields[0].equals("v1")) return Optional.empty();
        try {
            int x = Integer.parseInt(fields[4]), y = Integer.parseInt(fields[5]), z = Integer.parseInt(fields[6]);
            ProviderIdentity identity;
            String side;
            if (fields.length == 7 && fields[1].equals("block")) {
                side = "";
                identity = ProviderIdentity.block(fields[2], fields[3], x, y, z);
            } else if (fields.length == 8 && fields[1].equals("part")) {
                side = fields[7];
                identity = ProviderIdentity.part(fields[2], fields[3], x, y, z, side);
            } else return Optional.empty();
            if (!identity.key().equals(key) || Math.abs((long) x) >= 30_000_000L
                    || Math.abs((long) z) >= 30_000_000L || y < -2048 || y > 2047) return Optional.empty();
            return Optional.of(new ProviderLocation(fields[2], fields[3], x, y, z, side));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
