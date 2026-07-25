package org.mateof24.conditionalvideos.video.path;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VideoSourceResolver {
    private VideoSourceResolver() {
    }

    public static VideoSource resolve(Path gameDirectory, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return new Unresolved();
        }

        if (looksLikeUrl(configuredPath)) {
            URI uri = parseUri(configuredPath);
            return uri == null ? new Unresolved() : new RemoteUrl(uri);
        }

        Path raw = Path.of(configuredPath);
        Path candidate = raw.isAbsolute() ? raw : gameDirectory.resolve(raw);
        if (Files.isRegularFile(candidate)) {
            return new LocalFile(candidate.normalize());
        }
        return new Unresolved();
    }

    public static boolean looksLikeUrl(String configuredPath) {
        if (configuredPath == null) {
            return false;
        }
        String trimmed = configuredPath.trim().toLowerCase();
        return trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("youtube.com/")
                || trimmed.startsWith("www.youtube.com/")
                || trimmed.startsWith("youtu.be/")
                || trimmed.startsWith("m.youtube.com/");
    }

    public static URI parseUri(String configuredPath) {
        if (configuredPath == null) {
            return null;
        }
        String trimmed = configuredPath.trim();
        String normalized = trimmed;
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            normalized = "https://" + trimmed;
        }
        try {
            return URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public sealed interface VideoSource permits LocalFile, RemoteUrl, Unresolved {
    }

    public record LocalFile(Path path) implements VideoSource {
    }

    public record RemoteUrl(URI uri) implements VideoSource {
    }

    public record Unresolved() implements VideoSource {
    }
}
