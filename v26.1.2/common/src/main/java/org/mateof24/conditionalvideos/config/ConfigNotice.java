package org.mateof24.conditionalvideos.config;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A problem found in the user's conditions or during their migration. The English text goes to the log;
// the chat shows it translated through its key, falling back to the same English text.
public record ConfigNotice(String key, String english, Object... args) {
    private static final Pattern POSITION = Pattern.compile("line (\\d+) column (\\d+)");

    public String text() {
        return String.format(Locale.ROOT, english, args);
    }

    static ConfigNotice unreadable(String file, Throwable error) {
        Matcher position = POSITION.matcher(String.valueOf(error.getMessage()));
        if (position.find()) {
            return new ConfigNotice("conditionalvideos.notice.syntax", "%s: invalid JSON at line %s, column %s",
                    file, position.group(1), position.group(2));
        }
        return new ConfigNotice("conditionalvideos.notice.unreadable", "%s could not be read (%s)", file, reason(error));
    }

    static ConfigNotice notObject(String file) {
        return new ConfigNotice("conditionalvideos.notice.not_object", "%s must contain a JSON object { ... }", file);
    }

    static ConfigNotice invalidContent(String file, String detail) {
        return new ConfigNotice("conditionalvideos.notice.invalid_content", "%s has an invalid value (%s); the condition is skipped",
                file, detail);
    }

    static ConfigNotice unknownFile(String file) {
        return new ConfigNotice("conditionalvideos.notice.unknown_file", "%s is not a known condition; check the file name", file);
    }

    static ConfigNotice unknownFolder(String folder) {
        return new ConfigNotice("conditionalvideos.notice.unknown_folder", "%s is not a known condition folder; check its name", folder);
    }

    static ConfigNotice missingNamespace(String file) {
        return new ConfigNotice("conditionalvideos.notice.missing_namespace",
                "%s needs a namespace folder (for example minecraft/) or an \"id\" with the full key", file);
    }

    static ConfigNotice invalidId(String file) {
        return new ConfigNotice("conditionalvideos.notice.invalid_id", "%s: \"id\" must be text", file);
    }

    static ConfigNotice duplicate(String file, String other) {
        return new ConfigNotice("conditionalvideos.notice.duplicate", "%s defines the same condition as %s; it is ignored", file, other);
    }

    static ConfigNotice invalidOption(String file, String field, Object value, int video, String fallback) {
        return new ConfigNotice("conditionalvideos.notice.invalid_option", "%s: invalid %s '%s' in video %s; %s is used",
                file, field, value, video, fallback);
    }

    static ConfigNotice invalidRoomOption(String file, String option, Object value, Object used) {
        return new ConfigNotice("conditionalvideos.notice.invalid_room_option", "%s: invalid %s '%s' in waitingRoom; %s is used", file, option, value, used);
    }

    static ConfigNotice invalidTrigger(String file, Object value) {
        return new ConfigNotice("conditionalvideos.notice.invalid_trigger", "%s: invalid trigger '%s'; enter is used", file, value);
    }

    static ConfigNotice areaWithoutDimension(String file) {
        return new ConfigNotice("conditionalvideos.notice.area_no_dimension", "%s: a worldPosition needs its dimension; the condition is skipped", file);
    }

    static ConfigNotice areaWithoutCorners(String file) {
        return new ConfigNotice("conditionalvideos.notice.area_no_corners", "%s: a worldPosition needs from and to, each with x and z; the condition is skipped", file);
    }

    static ConfigNotice areaWithOneHeight(String file) {
        return new ConfigNotice("conditionalvideos.notice.area_one_height", "%s: y must be in both from and to, or in neither; the condition is skipped", file);
    }

    static ConfigNotice invalidComparator(String file, Object value) {
        return new ConfigNotice("conditionalvideos.notice.invalid_comparator", "%s: invalid comparator '%s'; greaterOrEqual is used",
                file, value);
    }

    static ConfigNotice outOfRange(String file, String field, Object value, int video, String range) {
        return new ConfigNotice("conditionalvideos.notice.out_of_range", "%s: %s %s in video %s is out of range (%s)",
                file, field, value, video, range);
    }

    static ConfigNotice startAfterNext(String file, Object startAt, int video, Object nextAt) {
        return new ConfigNotice("conditionalvideos.notice.start_after_next", "%s: startAt %s in video %s is not before its nextAt %s; the video is skipped",
                file, startAt, video, nextAt);
    }

    static ConfigNotice negativeDelay(String file, Object delay) {
        return new ConfigNotice("conditionalvideos.notice.negative_delay", "%s: conditionDelay %s cannot be negative; it plays without delay", file, delay);
    }

    static ConfigNotice migrationSuspended(String file, Throwable error) {
        Matcher position = POSITION.matcher(String.valueOf(error.getMessage()));
        if (position.find()) {
            return new ConfigNotice("conditionalvideos.notice.migration.syntax",
                    "%s has invalid JSON at line %s, column %s and was not migrated; fix it and restart the game",
                    file, position.group(1), position.group(2));
        }
        return new ConfigNotice("conditionalvideos.notice.migration.unreadable",
                "%s could not be read (%s) and was not migrated; fix it and restart the game", file, reason(error));
    }

    static ConfigNotice migrationNotObject(String file) {
        return new ConfigNotice("conditionalvideos.notice.migration.not_object",
                "%s does not contain a JSON object and was not migrated; fix it and restart the game", file);
    }

    static ConfigNotice migrationFailed(String file, String reason) {
        return new ConfigNotice("conditionalvideos.notice.migration.failed",
                "%s could not be migrated (%s); it was left untouched and will be retried at the next start", file, reason);
    }

    static ConfigNotice legacyIgnored(String file, String folder) {
        return new ConfigNotice("conditionalvideos.notice.migration.ignored",
                "%s was found but %s already exists, so it is ignored. To migrate it again, move or delete the %s folder",
                file, folder, folder);
    }

    static ConfigNotice unrecognized(String file, int count, String target) {
        return new ConfigNotice("conditionalvideos.notice.migration.unrecognized",
                "%s had %s entry(ies) that do not fit any condition; they were kept as they were in %s", file, count, target);
    }

    // Short, single-line reason without the exception class or Gson's troubleshooting link.
    static String reason(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getMessage() != null && cause.getMessage().equals(String.valueOf(cause.getCause()))) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        message = message.replaceFirst("^[\\w.$]+(Exception|Error): ", "");
        int link = message.indexOf("\nSee ");
        if (link >= 0) {
            message = message.substring(0, link);
        }
        return message.replace('\n', ' ').replaceFirst(" at path \\$$", "").trim();
    }
}
