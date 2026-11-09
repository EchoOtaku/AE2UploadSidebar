package dev.echo.uploadsidebar.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ServerMessagesTest {
    @Test void everyLegacyServerMessageHasALocalizationKey() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/echo/uploadsidebar/server/ServerUploads.java"));
        var messages = Pattern.compile("\"([^\"\\n]*[\\p{IsHan}][^\"\\n]*)\"").matcher(source);
        int count = 0;
        while (messages.find()) {
            assertTrue(ServerMessages.localizationKey(messages.group(1)).startsWith("ae2_upload_sidebar."), messages.group(1));
            count++;
        }
        assertTrue(count > 0, "Keep the readable v2 messages for older clients");
    }

    @Test void keysAndUnknownMessagesPassThroughUnchanged() {
        assertEquals("ae2_upload_sidebar.server.unavailable",
            ServerMessages.localizationKey("ae2_upload_sidebar.server.unavailable"));
        assertEquals("Future server response", ServerMessages.localizationKey("Future server response"));
        assertEquals("", ServerMessages.localizationKey(""));
    }
}
