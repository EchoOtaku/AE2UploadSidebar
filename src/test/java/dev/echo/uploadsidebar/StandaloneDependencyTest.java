package dev.echo.uploadsidebar;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneDependencyTest {
    @Test void productionBytecodeAndMetadataHaveNoPlusDependency() throws Exception {
        try (var files = Files.walk(Path.of("build/classes/java/main"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains("com/extendedae_plus"), file.toString());
                if (!file.getFileName().toString().equals("EaepMappings.class"))
                    assertFalse(bytes.contains("com.extendedae_plus"), file.toString());
            }
        }
        assertFalse(Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml")).contains("extendedae_plus"));
    }
}
