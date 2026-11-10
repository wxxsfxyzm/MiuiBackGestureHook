package dev.codex.miuibackgesturehook.hooks.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-runtime reflection lookup cache. Hits are allocation-free: members are indexed by owner
 * class and then by name, so hot hook paths never build composite keys. Resolution results,
 * including missing members, stay cached for the owner's lifetime exactly like the former
 * {@code Pair}-keyed maps.
 */
public final class ReflectionCache {
    /** Reports an argument-count lookup that matched more than one distinct signature. */
    public interface AmbiguityListener {
        void onAmbiguousMethod(Class<?> owner, String methodName, int argCount,
                               Method selected, List<Method> candidates);
    }

    private static final Object MISSING = new Object();
    private static final Object[] NO_SLOTS = new Object[0];

    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> fields =
            new ConcurrentHashMap<>();
    /** Kept apart from {@link #fields}: a declared-only miss may be an inherited hit. */
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> declaredFields =
            new ConcurrentHashMap<>();
    /** Values are slot arrays indexed by argument count; slots hold a Method or MISSING. */
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object[]>> anyMethods =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, ExactEntry[]>>
            exactMethods = new ConcurrentHashMap<>();
    private final AmbiguityListener ambiguityListener;

    public ReflectionCache(AmbiguityListener ambiguityListener) {
        this.ambiguityListener = ambiguityListener;
    }

    /** A field declared on the owner or the nearest superclass declaring that name. */
    public Field field(Class<?> owner, String fieldName) throws NoSuchFieldException {
        ConcurrentHashMap<String, Object> byName = fields.get(owner);
        Object cached = byName == null ? null : byName.get(fieldName);
        if (cached instanceof Field) {
            return (Field) cached;
        }
        if (cached == MISSING) {
            throw new NoSuchFieldException(owner.getName() + "." + fieldName);
        }
        if (byName == null) {
            byName = fields.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>());
        }
        Class<?> current = owner;
        while (current != null) {
            try {
                Field resolved = current.getDeclaredField(fieldName);
                resolved.setAccessible(true);
                Object raced = byName.putIfAbsent(fieldName, resolved);
                return raced instanceof Field ? (Field) raced : resolved;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        byName.putIfAbsent(fieldName, MISSING);
        throw new NoSuchFieldException(owner.getName() + "." + fieldName);
    }

    /** A field declared on exactly this class, like {@link Class#getDeclaredField}. */
    public Field declaredField(Class<?> owner, String fieldName) throws NoSuchFieldException {
        ConcurrentHashMap<String, Object> byName = declaredFields.get(owner);
        Object cached = byName == null ? null : byName.get(fieldName);
        if (cached instanceof Field) {
            return (Field) cached;
        }
        if (cached == MISSING) {
            throw new NoSuchFieldException(owner.getName() + "." + fieldName);
        }
        if (byName == null) {
            byName = declaredFields.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>());
        }
        Field resolved;
        try {
            resolved = owner.getDeclaredField(fieldName);
        } catch (NoSuchFieldException absent) {
            byName.putIfAbsent(fieldName, MISSING);
            throw absent;
        }
        resolved.setAccessible(true);
        Object raced = byName.putIfAbsent(fieldName, resolved);
        return raced instanceof Field ? (Field) raced : resolved;
    }

    Method anyMethod(Class<?> owner, String methodName, int argCount)
            throws NoSuchMethodException {
        ConcurrentHashMap<String, Object[]> byName = anyMethods.get(owner);
        Object[] slots = byName == null ? null : byName.get(methodName);
        Object cached = slots != null && argCount < slots.length ? slots[argCount] : null;
        if (cached instanceof Method) {
            return (Method) cached;
        }
        if (cached == MISSING) {
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        if (byName == null) {
            byName = anyMethods.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>());
        }
        Method resolved = findAnyMethod(owner, methodName, argCount);
        if (resolved != null) {
            resolved.setAccessible(true);
        }
        Object published = publishSlot(byName, methodName, argCount,
                resolved == null ? MISSING : resolved);
        if (published == resolved && resolved != null && ambiguityListener != null) {
            List<Method> candidates = distinctCandidates(owner, methodName, argCount);
            if (candidates.size() > 1) {
                ambiguityListener.onAmbiguousMethod(
                        owner, methodName, argCount, resolved, candidates);
            }
        }
        if (published instanceof Method) {
            return (Method) published;
        }
        throw new NoSuchMethodException(owner.getName() + "." + methodName);
    }

    /** A public method, own or inherited, like {@link Class#getMethod}. */
    public Method exactMethod(Class<?> owner, String methodName, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        ConcurrentHashMap<String, ExactEntry[]> byName = exactMethods.get(owner);
        ExactEntry cached = byName == null
                ? null : findExact(byName.get(methodName), parameterTypes);
        if (cached != null) {
            if (cached.method == null) {
                throw new NoSuchMethodException(owner.getName() + "." + methodName);
            }
            return cached.method;
        }
        if (byName == null) {
            byName = exactMethods.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>());
        }
        Method resolved;
        try {
            resolved = owner.getMethod(methodName, parameterTypes);
            resolved.setAccessible(true);
        } catch (NoSuchMethodException exception) {
            publishExact(byName, methodName, parameterTypes, null);
            throw exception;
        }
        ExactEntry published = publishExact(byName, methodName, parameterTypes, resolved);
        if (published.method == null) {
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        return published.method;
    }

    /**
     * Returns the first method with the given name and argument count, searching declared
     * methods from the class upward before public inherited members. Selection order is kept
     * identical to the original runtime helper.
     */
    static Method findAnyMethod(Class<?> type, String methodName, int argCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName())
                        && method.getParameterCount() == argCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (methodName.equals(method.getName())
                    && method.getParameterCount() == argCount) {
                return method;
            }
        }
        return null;
    }

    /**
     * Collects the distinct non-bridge signatures {@link #findAnyMethod} could have chosen.
     * Overrides of one signature along the hierarchy count once.
     */
    static List<Method> distinctCandidates(Class<?> type, String methodName, int argCount) {
        List<Method> candidates = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                addCandidate(candidates, method, methodName, argCount);
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            addCandidate(candidates, method, methodName, argCount);
        }
        return candidates;
    }

    private static void addCandidate(List<Method> candidates, Method method,
                                     String methodName, int argCount) {
        if (!methodName.equals(method.getName())
                || method.getParameterCount() != argCount
                || method.isBridge() || method.isSynthetic()) {
            return;
        }
        for (Method existing : candidates) {
            if (Arrays.equals(existing.getParameterTypes(), method.getParameterTypes())) {
                return;
            }
        }
        candidates.add(method);
    }

    private static Object publishSlot(ConcurrentHashMap<String, Object[]> byName,
                                      String methodName, int argCount, Object value) {
        Object[] result = new Object[1];
        byName.compute(methodName, (ignored, slots) -> {
            Object[] current = slots == null ? NO_SLOTS : slots;
            if (argCount < current.length && current[argCount] != null) {
                result[0] = current[argCount];
                return current;
            }
            Object[] updated = Arrays.copyOf(current, Math.max(current.length, argCount + 1));
            updated[argCount] = value;
            result[0] = value;
            return updated;
        });
        return result[0];
    }

    private static ExactEntry findExact(ExactEntry[] entries, Class<?>[] parameterTypes) {
        if (entries == null) {
            return null;
        }
        for (ExactEntry entry : entries) {
            if (Arrays.equals(entry.parameterTypes, parameterTypes)) {
                return entry;
            }
        }
        return null;
    }

    private static ExactEntry publishExact(ConcurrentHashMap<String, ExactEntry[]> byName,
                                           String methodName, Class<?>[] parameterTypes,
                                           Method method) {
        ExactEntry[] result = new ExactEntry[1];
        byName.compute(methodName, (ignored, entries) -> {
            ExactEntry existing = findExact(entries, parameterTypes);
            if (existing != null) {
                result[0] = existing;
                return entries;
            }
            ExactEntry entry = new ExactEntry(parameterTypes.clone(), method);
            ExactEntry[] updated = entries == null
                    ? new ExactEntry[1] : Arrays.copyOf(entries, entries.length + 1);
            updated[updated.length - 1] = entry;
            result[0] = entry;
            return updated;
        });
        return result[0];
    }

    private static final class ExactEntry {
        final Class<?>[] parameterTypes;
        /** Null records a cached miss. */
        final Method method;

        ExactEntry(Class<?>[] parameterTypes, Method method) {
            this.parameterTypes = parameterTypes;
            this.method = method;
        }
    }
}
