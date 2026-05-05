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
    private static final Set<String> seenThisSession = ConcurrentHashMap.newKeySet();
    private static volatile boolean sessionActive = false;
    private static volatile boolean listenerRegistered = false;
    private static volatile boolean suppressingReset = false;
    private static Set<String> pollingPrevious = new HashSet<>();

    private AdvancementVideoHandler() {
    }

    public static void onSessionStarted(Minecraft minecraft) {
        seenThisSession.clear();
        pollingPrevious = new HashSet<>();
        listenerRegistered = false;
        suppressingReset = false;
        sessionActive = true;
        Set<String> existing = snapshotCompletedAdvancements(minecraft);
        seenThisSession.addAll(existing);
        pollingPrevious = new HashSet<>(existing);
        tryRegisterListener(minecraft);
    }

    public static void onSessionEnded() {
        sessionActive = false;
        listenerRegistered = false;
        suppressingReset = false;
        seenThisSession.clear();
        pollingPrevious = new HashSet<>();
    }

    public static void tick(Minecraft minecraft) {
        if (listenerRegistered || !sessionActive) return;

        Set<String> current = snapshotCompletedAdvancements(minecraft);
        for (String id : current) {
            if (!seenThisSession.contains(id)) {
                seenThisSession.add(id);
                ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
                ConditionalVideosConfig.ConditionConfig cfg = config.advancementCompleted().get(id);
                ConditionVideoPlayer.play(minecraft, config, cfg, CONDITION_ID_PREFIX + id, "advancement completed ('" + id + "')", null);
                break;
            }
        }
        seenThisSession.removeIf(id -> !current.contains(id));
        pollingPrevious = current;
    }

    private static void tryRegisterListener(Minecraft minecraft) {
        if (minecraft.getConnection() == null) return;
        try {
            Object clientAdvancements = resolveClientAdvancements(minecraft);
            if (clientAdvancements == null) return;

            Class<?> listenerInterface = findListenerInterface(clientAdvancements.getClass());
            if (listenerInterface == null) {
                ConditionalVideos.LOGGER.debug("[ConditionalVideos] Listener interface not found in {}", clientAdvancements.getClass().getName());
                return;
            }

            Method setListenerMethod = findSetListenerMethod(clientAdvancements.getClass(), listenerInterface);
            if (setListenerMethod == null) {
                ConditionalVideos.LOGGER.debug("[ConditionalVideos] setListener method not found");
                return;
            }

            Object proxy = Proxy.newProxyInstance(
                    clientAdvancements.getClass().getClassLoader(),
                    new Class[]{listenerInterface},
                    (proxyObj, method, args) -> {
                        String mName = method.getName();
                        if ("equals".equals(mName)) return proxyObj == (args != null ? args[0] : null);
                        if ("hashCode".equals(mName)) return System.identityHashCode(proxyObj);
                        if ("toString".equals(mName)) return "CVAdvancementListener";

                        if (args == null || args.length == 0) {
                            suppressingReset = true;
                            seenThisSession.clear();
                            minecraft.execute(() -> {
                                Set<String> newBaseline = snapshotCompletedAdvancements(minecraft);
                                seenThisSession.addAll(newBaseline);
                                suppressingReset = false;
                            });
                        } else if (args.length == 2 && !suppressingReset) {
                            handleProgressUpdate(args[0], args[1], minecraft);
                        }
                        return null;
                    }
            );

            setListenerMethod.invoke(clientAdvancements, proxy);
            listenerRegistered = true;
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Advancement listener registered successfully");
        } catch (Exception e) {
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Listener registration failed, using polling fallback: {}", e.getMessage());
        }
    }

    private static void handleProgressUpdate(Object holder, Object progress, Minecraft minecraft) {
        try {
            Method isDone = progress.getClass().getMethod("isDone");
            Object result = isDone.invoke(progress);
            boolean done = result instanceof Boolean b && b;
            String id = resolveAdvancementId(holder);
            if (id.isBlank()) return;

            if (!done) {
                seenThisSession.remove(id);
                return;
            }

            if (seenThisSession.contains(id)) return;
            seenThisSession.add(id);

            minecraft.execute(() -> {
                if (!sessionActive) return;
                ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
                ConditionalVideosConfig.ConditionConfig cfg = config.advancementCompleted().get(id);
                ConditionVideoPlayer.play(minecraft, config, cfg, CONDITION_ID_PREFIX + id, "advancement completed ('" + id + "')", null);
            });
        } catch (Exception e) {
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Error in advancement progress handler: {}", e.getMessage());
        }
    }

    private static Class<?> findListenerInterface(Class<?> cls) {
        Class<?> clazz = cls;
        while (clazz != null && clazz != Object.class) {
            for (Class<?> inner : clazz.getDeclaredClasses()) {
                if (!inner.isInterface()) continue;
                Method[] methods = inner.getMethods();
                boolean has2Param = false;
                boolean hasVoidOrNullable = false;
                for (Method m : methods) {
                    try {
                        Object.class.getMethod(m.getName(), m.getParameterTypes());
                        continue;
                    } catch (NoSuchMethodException ignored) {
                    }
                    if (m.getParameterCount() == 2) has2Param = true;
                    if (m.getParameterCount() <= 1) hasVoidOrNullable = true;
                }
                if (has2Param && hasVoidOrNullable) return inner;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static Method findSetListenerMethod(Class<?> cls, Class<?> listenerInterface) {
        Class<?> clazz = cls;
        while (clazz != null && clazz != Object.class) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == listenerInterface) {
                    m.setAccessible(true);
                    return m;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    static Set<String> snapshotCompletedAdvancements(Minecraft minecraft) {
        Set<String> completed = new HashSet<>();
        Object advancements = resolveClientAdvancements(minecraft);
        if (advancements == null) return completed;
        Map<?, ?> progressMap = resolveProgressMap(advancements);
        if (progressMap == null) return completed;
        for (Map.Entry<?, ?> entry : progressMap.entrySet()) {
            if (!isCompleted(entry.getValue())) continue;
            String id = resolveAdvancementId(entry.getKey());
            if (!id.isBlank()) completed.add(id);
        }
        return completed;
    }

    private static Object resolveClientAdvancements(Minecraft minecraft) {
        if (minecraft.getConnection() == null) return null;
        try {
            Method m = minecraft.getConnection().getClass().getMethod("getAdvancements");
            return m.invoke(minecraft.getConnection());
        } catch (ReflectiveOperationException e) {
            ConditionalVideos.LOGGER.debug("[ConditionalVideos] Unable to resolve client advancements.", e);
            return null;
        }
    }

    private static Map<?, ?> resolveProgressMap(Object advancements) {
        Class<?> clazz = advancements.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(advancements);
                    if (value instanceof Map<?, ?> map && looksLikeProgressMap(map)) return map;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static boolean looksLikeProgressMap(Map<?, ?> map) {
        if (map.isEmpty()) return false;
        for (Object v : map.values()) {
            if (v == null) continue;
            try {
                v.getClass().getMethod("isDone");
                return true;
            } catch (NoSuchMethodException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isCompleted(Object progress) {
        if (progress == null) return false;
        try {
            Method m = progress.getClass().getMethod("isDone");
            Object r = m.invoke(progress);
            return r instanceof Boolean b && b;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static String resolveAdvancementId(Object advancement) {
        if (advancement == null) return "";
        for (String name : new String[]{"getId", "id"}) {
            try {
                Method m = advancement.getClass().getMethod(name);
                Object id = m.invoke(advancement);
                if (id != null && !id.toString().isBlank()) return id.toString();
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return "";
    }
}