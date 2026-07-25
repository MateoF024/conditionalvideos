package org.mateof24.conditionalvideos.api;

import java.util.Objects;

/**
 * Describes a custom playback condition registered by another mod.
 *
 * <p>A custom condition has no built-in detector: the owning mod decides when it fires and triggers
 * playback through {@link ConditionalVideosAPI}. The {@code id} becomes the configuration key
 * {@code custom/<id>} (so server admins map videos to it) and the display metadata is purely
 * informational for tooling.</p>
 *
 * @param id          unique, non-blank identifier (used as the {@code custom/<id>} config key)
 * @param displayName human-readable name; falls back to {@code id} when blank
 * @param description optional one-line description; never {@code null} (empty when unset)
 */
public record CustomCondition(String id, String displayName, String description) {
    public CustomCondition {
        Objects.requireNonNull(id, "id");
        id = id.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Custom condition id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        description = description == null ? "" : description;
    }

    public CustomCondition(String id) {
        this(id, id, "");
    }

    /**
     * @return the wire/config key for this condition ({@code custom/<id>}).
     */
    public String conditionKey() {
        return "custom/" + id;
    }
}
