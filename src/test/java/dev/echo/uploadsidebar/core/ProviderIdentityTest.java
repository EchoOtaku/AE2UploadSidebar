package dev.echo.uploadsidebar.core;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public final class ProviderIdentityTest {
    private record Candidate(UUID token, String key) { }

    @Test void sameLocationAndTypeRetainNotesAcrossFreshTokens() {
        var first = ProviderIdentity.block("minecraft:overworld", "ae2:pattern_provider", 10, 70, -20);
        var reopened = ProviderIdentity.block("minecraft:overworld", "ae2:pattern_provider", 10, 70, -20);
        check(!first.key().isBlank(), "location identity must not be blank");
        check(first.equals(reopened), "same location and type intentionally inherit their annotation");
        check(first.location().contains("10") && first.location().contains("70") && first.location().contains("-20"),
            "human location identifies the actual upload provider");
        check(first.key().length() <= 512 && first.location().length() <= 256, "identity respects packet bounds");
    }

    @Test void dimensionsKindsTypesCoordinatesAndPartSidesDoNotCollide() {
        String dimension = "minecraft:overworld", type = "ae2:pattern_provider";
        List<ProviderIdentity> variants = List.of(
            ProviderIdentity.block(dimension, type, 1, 2, 3),
            ProviderIdentity.block("minecraft:the_nether", type, 1, 2, 3),
            ProviderIdentity.block(dimension, "extendedae:ex_pattern_provider", 1, 2, 3),
            ProviderIdentity.block(dimension, type, 2, 2, 3),
            ProviderIdentity.block(dimension, type, 1, 3, 3),
            ProviderIdentity.block(dimension, type, 1, 2, 4),
            ProviderIdentity.part(dimension, type, 1, 2, 3, "north"),
            ProviderIdentity.part(dimension, type, 1, 2, 3, "south"));
        check(new HashSet<>(variants.stream().map(ProviderIdentity::key).toList()).size() == variants.size(),
            "every coordinate, kind, type, dimension and part face is significant");
        check(!ProviderIdentity.block(dimension, type, 0, 0, 0).key()
            .equals(ProviderIdentity.block(dimension, type, 0, 0, 256).key()),
            "full coordinates must not reuse AE2's colliding 32-bit terminal sort hash");
    }

    @Test void duplicateStableKeysSuppressEveryAmbiguousRowWithoutMergingTargets() {
        var first = new Candidate(UUID.randomUUID(), "duplicate");
        var second = new Candidate(UUID.randomUUID(), "duplicate");
        var unique = new Candidate(UUID.randomUUID(), "unique");
        List<Candidate> source = List.of(first, unique, second);
        var result = ProviderIdentity.uniqueKeys(source, Candidate::key);
        check(result.equals(List.of(unique)) && result.getFirst() == unique,
            "all ambiguous keys are omitted, not just whichever duplicate was encountered last");
        check(source.equals(List.of(first, unique, second)), "snapshot targets remain untouched");
        check(ProviderIdentity.uniqueKeys(List.of(first, unique), Candidate::key).equals(List.of(first, unique)),
            "unique identities retain source order and their concrete tokens");
    }

    @Test void unsupportedOrUnboundedIdentityInputsAreRejected() {
        expectRejected(() -> ProviderIdentity.block("", "ae2:pattern_provider", 1, 2, 3));
        expectRejected(() -> ProviderIdentity.block("minecraft:overworld", "", 1, 2, 3));
        expectRejected(() -> ProviderIdentity.block("minecraft:overworld|spoof", "ae2:pattern_provider", 1, 2, 3));
        expectRejected(() -> ProviderIdentity.part("minecraft:overworld", "ae2:pattern_provider", 1, 2, 3, ""));
        expectRejected(() -> ProviderIdentity.part("minecraft:overworld", "ae2:pattern_provider", 1, 2, 3, "diagonal"));
        expectRejected(() -> ProviderIdentity.block("minecraft:" + "x".repeat(300), "ae2:pattern_provider", 1, 2, 3));
        expectRejected(() -> new ProviderIdentity("x".repeat(513), "location"));
        expectRejected(() -> new ProviderIdentity("key", "x".repeat(257)));
    }

    private static void expectRejected(Runnable work) {
        try { work.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed or ambiguous location identity should be rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var tests = new ProviderIdentityTest();
        tests.sameLocationAndTypeRetainNotesAcrossFreshTokens();
        tests.dimensionsKindsTypesCoordinatesAndPartSidesDoNotCollide();
        tests.duplicateStableKeysSuppressEveryAmbiguousRowWithoutMergingTargets();
        tests.unsupportedOrUnboundedIdentityInputsAreRejected();
        System.out.println("PROVIDER_IDENTITY_PASS: 4 checks");
    }
}
