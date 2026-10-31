package org.mateof24.conditionalvideos.config;

import java.util.List;

// The placeholders any text of a waiting room may use: %current_players% who is in, %required_players% who it waits
// for and %missing_players% those listed who are not in yet. Only what is written shows.
public final class WaitingRoomText {
    public static final String DEFAULT_COUNTER = "%current_players%/%required_players%\n%missing_players%";
    // What the server sends of %missing_players%: enough to know whether more remain after the three that can show.
    public static final int MISSING_SENT = 4;
    private static final int THREE_FIT_CHARACTERS = 30;

    private WaitingRoomText() {
    }

    // A placeholder with nothing to show must not leave an empty line at the end.
    public static String fill(String text, int current, int required, List<String> missing) {
        if (text == null) {
            return "";
        }
        return text.replace("%current_players%", String.valueOf(current))
                .replace("%required_players%", String.valueOf(required))
                .replace("%missing_players%", missingNames(missing))
                .stripTrailing();
    }

    // Three names when they are short, two when they are long, dots when more remain, and just the name when only
    // one is left.
    public static String missingNames(List<String> missing) {
        if (missing.isEmpty()) {
            return "";
        }
        int shown = Math.min(missing.size(), 2);
        if (missing.size() >= 3 && missing.get(0).length() + missing.get(1).length() + missing.get(2).length() <= THREE_FIT_CHARACTERS) {
            shown = 3;
        }
        String names = String.join(", ", missing.subList(0, shown));
        return missing.size() > shown ? names + "..." : names;
    }
}
