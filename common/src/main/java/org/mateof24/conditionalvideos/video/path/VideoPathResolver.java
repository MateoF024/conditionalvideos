package org.mateof24.conditionalvideos.video.path;

import java.nio.file.Files;
import java.nio.file.Path;

public final class VideoPathResolver {
    private VideoPathResolver() {
    }

    public static Path resolve(Path gameDirectory, String configuredPath) {
        Path rawPath = Path.of(configuredPath);
        Path candidate = rawPath.isAbsolute() ? rawPath : gameDirectory.resolve(rawPath);
        return Files.isRegularFile(candidate) ? candidate.normalize() : null;
    }
}