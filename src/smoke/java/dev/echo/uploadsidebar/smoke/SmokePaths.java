package dev.echo.uploadsidebar.smoke;

import java.nio.file.Path;

/** Gradle supplies the isolated workspace path; no machine-specific paths or unsafe defaults. */
final class SmokePaths {
    private SmokePaths() { }

    static Path runDirectory() {
        String configured = System.getProperty("sidebar.smoke.directory");
        if (configured == null || configured.isBlank())
            throw new IllegalStateException("Run smoke tests through Gradle with -Psmoke=true");
        Path path = Path.of(configured);
        if (!path.isAbsolute() || !path.normalize().getFileName().toString().equals("run-test"))
            throw new IllegalStateException("Smoke tests require an absolute run-test directory");
        return path.normalize();
    }
}
