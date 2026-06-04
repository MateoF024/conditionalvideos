package org.mateof24.conditionalvideos.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class CustomConditionRegistry {
    private static final Map<String, CustomCondition> REGISTRY = new ConcurrentHashMap<>();

    private CustomConditionRegistry() {
    }

    static void register(CustomCondition condition) {
        REGISTRY.put(condition.id(), condition);
    }

    static boolean isRegistered(String id) {
        return id != null && REGISTRY.containsKey(id.trim());
    }

    static Collection<CustomCondition> all() {
        return List.copyOf(REGISTRY.values());
    }
}
