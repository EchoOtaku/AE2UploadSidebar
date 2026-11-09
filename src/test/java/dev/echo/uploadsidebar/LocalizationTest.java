package dev.echo.uploadsidebar;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Verify shipped language resources without bootstrapping a Minecraft client. */
class LocalizationTest {
    private static final String PREFIX = "ae2_upload_sidebar.";
    private static final String DIRECTORY = "assets/ae2_upload_sidebar/lang/";
    private static final List<String> LOCALES = List.of("en_us", "zh_cn", "zh_tw");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([s%])");

    private Map<String, String> language(String locale) throws Exception {
        String resource = DIRECTORY + locale + ".json";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Language file must be present in processed resources: " + resource);
            String processed = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(Files.readString(Path.of("src/main/resources", resource)), processed,
                "Resource processing must preserve UTF-8 translations");
            var values = new LinkedHashMap<String, String>();
            JsonParser.parseString(processed).getAsJsonObject().entrySet().forEach(entry -> {
                assertTrue(entry.getKey().startsWith(PREFIX), entry.getKey());
                assertTrue(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(), entry.getKey());
                assertFalse(entry.getValue().getAsString().isBlank(), entry.getKey());
                values.put(entry.getKey(), entry.getValue().getAsString());
            });
            assertFalse(values.isEmpty());
            return values;
        }
    }

    @Test void allLanguagesShipTheSameKeysAndCompatibleArguments() throws Exception {
        var english = language("en_us");
        for (String locale : LOCALES) {
            var translated = language(locale);
            assertEquals(english.keySet(), translated.keySet(), locale + " must have complete coverage");
            for (String key : english.keySet()) {
                assertEquals(arguments(english.get(key)), arguments(translated.get(key)), locale + ": " + key);
            }
        }
        english.forEach((key, value) -> assertFalse(value.codePoints().anyMatch(
            point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN),
            "English fallback still contains Chinese UI text: " + key));
    }

    @Test void literalTranslationReferencesExistInThePackagedCatalog() throws Exception {
        var available = language("en_us");
        Pattern helperCall = Pattern.compile("SidebarText\\.(?:text|component)\\(\\s*\"([^\"]+)\"\\s*([,)])");
        Pattern fullKey = Pattern.compile("\"(ae2_upload_sidebar\\.[a-z0-9_.]+)\"");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                var calls = helperCall.matcher(source);
                while (calls.find()) assertTrue(available.containsKey(PREFIX + calls.group(1)),
                    file + " references a missing translation: " + calls.group(1));
                var keys = fullKey.matcher(source);
                while (keys.find()) {
                    String key = keys.group(1);
                    if (!key.endsWith(".")) assertTrue(available.containsKey(key), file + " references a missing key: " + key);
                }
            }
        }
    }

    private static List<Integer> arguments(String template) {
        var result = new ArrayList<Integer>();
        var matches = PLACEHOLDER.matcher(template);
        int implicit = 1;
        int previousEnd = 0;
        while (matches.find()) {
            assertFalse(template.substring(previousEnd, matches.start()).contains("%"),
                "Minecraft translatable components support %s / %1$s / %%: " + template);
            if (!matches.group(2).equals("%")) result.add(matches.group(1) == null ? implicit++ : Integer.parseInt(matches.group(1)));
            previousEnd = matches.end();
        }
        assertFalse(template.substring(previousEnd).contains("%"), "Unsupported format placeholder: " + template);
        return result.stream().sorted().toList();
    }
}
