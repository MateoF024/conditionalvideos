package org.mateof24.conditionalvideos;

import java.nio.file.Files;
import java.nio.file.Path;

final class VideoPathResolver {
    private VideoPathResolver() {
    }

    static Path resolve(Path gameDirectory, String configuredPath) {
        Path rawPath = Path.of(configuredPath);
        Path candidate = rawPath.isAbsolute() ? rawPath : gameDirectory.resolve(rawPath);
        return Files.isRegularFile(candidate) ? candidate.normalize() : null;
    }
}