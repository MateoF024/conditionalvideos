package org.mateof24.conditionalvideos.api;

import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.Viewer;

import java.util.Set;

/**
 * What a single playback changes from the condition's file, which otherwise decides everything and is never
 * written. Used by {@link ConditionalVideosAPI#playOnServer(net.minecraft.server.level.ServerPlayer, String, PlayOptions)}.
 *
 * @param delaySeconds   seconds to wait instead of the file's {@code conditionDelay}; {@code null} keeps the file's
 * @param excludePlayers names left out on top of the file's exclusions, ignoring case
 * @param excludeTeams   teams left out on top of the file's exclusions, exact names
 * @param excludeOps     leaves operators out on top of the file's exclusions
 * @param forced         plays past every exclusion, except the file's {@code excludeOps} for an operator
 * @param ignoreWatched  plays even if a non repeatable condition was already watched
 * @param once           plays only if not watched yet and counts as watched, even if the condition is repeatable
 * @param sync           joins whoever is watching this condition at the point they are; from the start if nobody is
 */
public record PlayOptions(Float delaySeconds, Set<String> excludePlayers, Set<String> excludeTeams, boolean excludeOps,
                          boolean forced, boolean ignoreWatched, boolean once, boolean sync) {
    /** Changes nothing: everything as the file says, like a natural trigger. */
    public static final PlayOptions FILE = new PlayOptions(null, Set.of(), Set.of(), false, false, false, false, false);

    public PlayOptions {
        excludePlayers = excludePlayers == null ? Set.of() : Set.copyOf(excludePlayers);
        excludeTeams = excludeTeams == null ? Set.of() : Set.copyOf(excludeTeams);
    }

    public PlayOptions withForced(boolean value) {
        return new PlayOptions(delaySeconds, excludePlayers, excludeTeams, excludeOps, value, ignoreWatched, once, sync);
    }

    public int delayTicks(ConditionConfig condition) {
        if (delaySeconds == null) {
            return condition.resolvedDelayTicks();
        }
        return delaySeconds > 0f ? Math.round(delaySeconds * 20f) : 0;
    }

    /** Whether having watched it already stops this playback. */
    public boolean stopsIfWatched(ConditionConfig condition) {
        return once || (!ignoreWatched && !condition.repeatableInSameSession());
    }

    /**
     * Why this viewer is left out of this playback; {@code null} when they are not. The file's {@code excludeOps}
     * is absolute: nothing forces an operator past it.
     */
    public String exclusionReason(ConditionConfig condition, Viewer viewer) {
        if (condition.resolvedExcludeOps() && viewer.operator()) {
            return "excludeOps and they are an operator";
        }
        if (forced) {
            return null;
        }
        String fromFile = condition.exclusionReason(viewer);
        if (fromFile != null) {
            return fromFile;
        }
        for (String name : excludePlayers) {
            if (name.equalsIgnoreCase(viewer.name())) {
                return "this playback excludes the player " + viewer.name();
            }
        }
        if (viewer.team() != null && excludeTeams.contains(viewer.team())) {
            return "this playback excludes the team " + viewer.team();
        }
        return excludeOps && viewer.operator() ? "this playback excludes operators" : null;
    }
}
