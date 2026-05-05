package org.mateof24.conditionalvideos.condition.advancement;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AdvancementVideoHandler {
    private static final String CONDITION_ID_PREFIX = "advancementCompleted:";
    private static final AdvancementSessionState STATE = new AdvancementSessionState();

    private AdvancementVideoHandler() {
    }

    private static final class AdvancementSessionState {
        private final Set<String> seenThisSession = ConcurrentHashMap.newKeySet();
        private volatile boolean sessionActive = false;
        private volatile boolean listenerRegistered = false;
        private volatile boolean suppressingReset = false;
        private Set<String> pollingPrevious = new HashSet<>();
    }

    public static void onSessionStarted(Minecraft minecraft) {
        STATE.seenThisSession.clear();
        STATE.pollingPrevious = new HashSet<>();
        STATE.listenerRegistered = false;
        STATE.suppressingReset = false;
        STATE.sessionActive = true;
        Set<String> existing = snapshotCompletedAdvancements(minecraft);
        STATE.seenThisSession.addAll(existing);
        STATE.pollingPrevious = new HashSet<>(existing);
        tryRegisterListener(minecraft);
    }

    public static void onSessionEnded() {
        STATE.sessionActive = false;
        STATE.listenerRegistered = false;
        STATE.suppressingReset = false;
        STATE.seenThisSession.clear();
        STATE.pollingPrevious = new HashSet<>();
    }

    /**
     * Polling fallback - always runs regardless of listener state.
     * Shares previousCompleted with the listener to avoid double-triggers.
     */
    public static void tick(Minecraft minecraft) {
        if (!STATE.sessionActive) return;

        Set<String> current = snapshotCompletedAdvancements(minecraft);

        STATE.seenThisSession.removeIf(id -> !current.contains(id));

        if (!STATE.listenerRegistered) {
            for (String id : current) {
                if (!STATE.seenThisSession.contains(id)) {
                    STATE.seenThisSession.add(id);
                    ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
                    ConditionalVideosConfig.ConditionConfig cfg = config.advancementCompleted().get(id);
                    ConditionVideoPlayer.play(minecraft, config, cfg, CONDITION_ID_PREFIX + id, "advancement completed ('" + id + "')", null);
                    break;
                }
            }
        }

        STATE.pollingPrevious = current;
    }

    private static void tryRegisterListener(Minecraft minecraft) {
        if (minecraft.getConnection() == null) return;
        try {
            Object clientAdvancements = getClientAdvancements(minecraft);
            if (clientAdvancements == null) return;

            Class<?> iface = findListenerInterface(clientAdvancements.getClass());
            if (iface == null) {
                ConditionalVideos.LOGGER.debug("[ConditionalVideos] Listener interface not found, polling only");
                return;
            }

            Method setListenerMethod = findSetListenerMethod(clientAdvancements.getClass(), iface);
            if (setListenerMethod == null) {
                ConditionalVideos.LOGGER.debug("[ConditionalVideos] setListener not found, polling only");
                return;
            }

            Object proxy = Proxy.newProxyInstance(
                    clientAdvancements.getClass().getClassLoader(),
                    new Class[]{iface},
                    (proxyObj, method, args) -> {
                        switch (method.getName()) {
                            case "equals" -> { return proxyObj == (args != null ? args[0] : null); }
                            case "hashCode" -> { return System.identityHashCode(proxyObj); }
                            case "toString" -> { return "CVAdvancementListener"; }
                        }
                        int argc = args == null ? 0 : args.length;
                        if (argc == 0) {
                            // onAdvancementsCleared - server reset (e.g. /reload)
                            STATE.suppressingReset = true;
                            STATE.seenThisSession.clear();
                            // The subsequent onUpdateAdvancementProgress calls rebuild the baseline.
                            // Once the execute queue drains (i.e., after the packet is fully processed),
                            // we re-enable normal detection.
                            STATE.seenThisSession.addAll(newBaseline);
                            STATE.suppressingReset = false;
                        } else if (args.length == 2) {
                            handleProgressUpdate(args[0], args[1], minecraft, !STATE.suppressingReset);
                        }
                        // argc == 1: onSelectedTabChanged - irrelevant
                        return null;
                    }
            );

            setListenerMethod.invoke(clientAdvancements, proxy);
            STATE.listenerRegistered = true;
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Advancement listener registered successfully");
        } catch (Exception e) {
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Listener registration failed ({}), polling active", e.getMessage());
        }
    }

    private static void handleProgressUpdate(Object holder, Object progress, Minecraft minecraft, boolean allowPlayback) {
        try {
            Method isDone = progress.getClass().getMethod("isDone");
            Object result = isDone.invoke(progress);
            boolean done = result instanceof Boolean b && b;
            String id = resolveAdvancementId(holder);
            if (id.isBlank()) return;

            if (!done) {
                STATE.seenThisSession.remove(id);
                return;
            }

            if (STATE.seenThisSession.contains(id)) return;
            STATE.seenThisSession.add(id);

            if (!allowPlayback) return;

            minecraft.execute(() -> {
                if (!STATE.sessionActive) return;
                ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
                ConditionalVideosConfig.ConditionConfig cfg = config.advancementCompleted().get(id);
                ConditionVideoPlayer.play(minecraft, config, cfg, CONDITION_ID_PREFIX + id, "advancement completed ('" + id + "')", null);
            });
        } catch (Exception e) {
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Error in advancement progress handler: {}", e.getMessage());
        }
    }

    private static void doTrigger(Minecraft minecraft, String id) {
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionalVideosConfig.ConditionConfig cfg = config.advancementCompleted().get(id);
        ConditionVideoPlayer.play(minecraft, config, cfg, CONDITION_ID_PREFIX + id, "advancement completed ('" + id + "')", null);
    }

    // --- Reflection helpers ---

    private static Object getClientAdvancements(Minecraft minecraft) {
        if (minecraft.getConnection() == null) return null;
        try {
            return minecraft.getConnection().getClass().getMethod("getAdvancements").invoke(minecraft.getConnection());
        } catch (Exception e) {
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] getAdvancements() failed: {}", e.getMessage());
            return null;
        }
    }

    private static Class<?> findListenerInterface(Class<?> cls) {
        for (Class<?> clazz = cls; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Class<?> inner : clazz.getDeclaredClasses()) {
                if (!inner.isInterface()) continue;
                boolean has2Param = false, hasOtherParam = false;
                for (Method m : inner.getMethods()) {
                    try { Object.class.getMethod(m.getName(), m.getParameterTypes()); continue; }
                    catch (NoSuchMethodException ignored) {}
                    if (m.getParameterCount() == 2) has2Param = true;
                    else hasOtherParam = true;
                }
                if (has2Param && hasOtherParam) return inner;
            }
        }
        return null;
    }

    private static Method findSetListenerMethod(Class<?> cls, Class<?> iface) {
        for (Class<?> clazz = cls; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == iface) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    static Set<String> snapshotCompletedAdvancements(Minecraft minecraft) {
        Set<String> result = new HashSet<>();
        Object adv = getClientAdvancements(minecraft);
        if (adv == null) return result;
        Map<?, ?> map = findProgressMap(adv);
        if (map == null) return result;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (checkDone(e.getValue())) {
                String id = resolveId(e.getKey());
                if (!id.isBlank()) result.add(id);
            }
        }
        return result;
    }

    private static Map<?, ?> findProgressMap(Object advancements) {
        for (Class<?> clazz = advancements.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : clazz.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(advancements);
                    if (v instanceof Map<?, ?> m && isProgressMap(m)) return m;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean isProgressMap(Map<?, ?> map) {
        if (map.isEmpty()) return false;
        for (Object v : map.values()) {
            if (v == null) continue;
            try { v.getClass().getMethod("isDone"); return true; }
            catch (NoSuchMethodException e) { return false; }
        }
        return false;
    }

    private static boolean checkDone(Object progress) {
        if (progress == null) return false;
        try {
            return Boolean.TRUE.equals(progress.getClass().getMethod("isDone").invoke(progress));
        } catch (Exception e) { return false; }
    }

    private static String resolveId(Object holder) {
        if (holder == null) return "";
        for (String name : new String[]{"getId", "id"}) {
            try {
                Object result = holder.getClass().getMethod(name).invoke(holder);
                if (result != null) {
                    String s = result.toString();
                    if (!s.isBlank()) return s;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }
}