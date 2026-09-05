package dev.codex.miuibackgesturehook.util;

import android.window.TransitionInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local, class-loader-safe reflection access shared by the concrete hookers. */
public final class ReflectionHelper {
    private static final ConcurrentHashMap<MemberKey, Field> FIELDS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MemberKey, Method> ANY_METHODS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MemberKey, Method> EXACT_METHODS =
            new ConcurrentHashMap<>();
    private static final Set<MemberKey> MISSING_MEMBERS =
            ConcurrentHashMap.newKeySet();

    private ReflectionHelper() {}

    public static Object readField(Object target, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        return findCachedField(target.getClass(), fieldName).get(target);
    }

    public static Object readStaticField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        return findCachedField(ownerClass, fieldName).get(null);
    }

    public static Object readFieldOrNull(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return readField(target, fieldName);
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    public static float readFloatFieldOrDefault(
            Object target, String fieldName, float defaultValue) {
        try {
            Object value = readField(target, fieldName);
            return value instanceof Number
                    ? ((Number) value).floatValue() : defaultValue;
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static int readIntFieldOrDefault(
            Object target, String fieldName, int defaultValue) {
        try {
            Object value = readField(target, fieldName);
            return value instanceof Number
                    ? ((Number) value).intValue() : defaultValue;
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static void writeField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        findCachedField(target.getClass(), fieldName).set(target, value);
    }

    public static Field findField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException {
        return findCachedField(ownerClass, fieldName);
    }

    public static Field findCachedField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException {
        MemberKey key = new MemberKey(ownerClass, "field:" + fieldName);
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_MEMBERS.contains(key)) {
            throw new NoSuchFieldException(ownerClass.getName() + "." + fieldName);
        }
        Class<?> current = ownerClass;
        while (current != null) {
            try {
                Field resolved = current.getDeclaredField(fieldName);
                resolved.setAccessible(true);
                Field raced = FIELDS.putIfAbsent(key, resolved);
                return raced == null ? resolved : raced;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        MISSING_MEMBERS.add(key);
        throw new NoSuchFieldException(ownerClass.getName() + "." + fieldName);
    }

    public static Method requireExactDeclaredMethod(
            Class<?> owner, String methodName, String returnTypeName,
            String... parameterTypeNames) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())
                    || !returnTypeName.equals(method.getReturnType().getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != parameterTypeNames.length) {
                continue;
            }
            boolean exact = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!parameterTypeNames[index].equals(parameterTypes[index].getName())) {
                    exact = false;
                    break;
                }
            }
            if (exact) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + methodName
                + "(" + String.join(",", parameterTypeNames) + "):"
                + returnTypeName);
    }

    public static Object invokeMethod(
            Object target, String methodName, Class<?>[] parameterTypes, Object[] args)
            throws Exception {
        Class<?> owner = target.getClass();
        MemberKey key = new MemberKey(owner,
                "exact:" + methodName + Arrays.toString(parameterTypes));
        Method method = EXACT_METHODS.get(key);
        if (method == null) {
            if (MISSING_MEMBERS.contains(key)) {
                throw new NoSuchMethodException(owner.getName() + "." + methodName);
            }
            try {
                Method resolved = owner.getMethod(methodName, parameterTypes);
                resolved.setAccessible(true);
                Method raced = EXACT_METHODS.putIfAbsent(key, resolved);
                method = raced == null ? resolved : raced;
            } catch (NoSuchMethodException exception) {
                MISSING_MEMBERS.add(key);
                throw exception;
            }
        }
        return method.invoke(target, args);
    }

    public static Object invokeAnyMethod(Object target, String methodName, Object... args)
            throws Exception {
        Class<?> owner = target.getClass();
        MemberKey key = new MemberKey(owner,
                "any:" + methodName + "/" + args.length);
        Method method = ANY_METHODS.get(key);
        if (method == null && MISSING_MEMBERS.contains(key)) {
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        if (method == null) {
            Method resolved = findAnyMethod(owner, methodName, args.length);
            if (resolved != null) {
                resolved.setAccessible(true);
                Method raced = ANY_METHODS.putIfAbsent(key, resolved);
                method = raced == null ? resolved : raced;
            }
        }
        if (method == null) {
            MISSING_MEMBERS.add(key);
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        return method.invoke(target, args);
    }

    public static Method findAnyMethod(Class<?> type, String methodName, int argCount) {
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

    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    public static Method findCapabilityMethod(Class<?> actionClass) {
        Constructor<?> matchingConstructor = null;
        for (Constructor<?> constructor : actionClass.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3) {
                if (matchingConstructor != null) {
                    return null;
                }
                matchingConstructor = constructor;
            }
        }
        if (matchingConstructor == null) {
            return null;
        }
        Class<?>[] parameters = matchingConstructor.getParameterTypes();
        return parameters.length == 3
                ? findExactBooleanMethod(parameters[1], "a") : null;
    }

    public static Method findExactBooleanMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            if (method.getReturnType() != Boolean.TYPE
                    || method.getParameterCount() != 0) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object invokeCompatible(Object target, String methodName, Object... args)
            throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())
                        || method.getParameterCount() != args.length) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean compatible = true;
                for (int index = 0; index < parameterTypes.length; index++) {
                    if (args[index] != null
                            && !box(parameterTypes[index]).isInstance(args[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + methodName);
    }

    /** Read framework TransitionInfo through its stable public API when available. */
    public static List<?> readTransitionInfoChanges(Object info) {
        return info instanceof TransitionInfo
                ? ((TransitionInfo) info).getChanges() : null;
    }

    public static Integer readTransitionChangeMode(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getMode() : null;
    }

    public static Integer readTransitionChangeFlags(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getFlags() : null;
    }

    public static Object readTransitionChangeTaskInfo(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getTaskInfo() : null;
    }

    public static Object readTransitionChangeParent(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getParent() : null;
    }

    public static Object readTransitionChangeLastParent(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getLastParent() : null;
    }

    public static Object readTransitionChangeLeash(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getLeash() : null;
    }

    public static Object readTransitionChangeStartAbsBounds(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getStartAbsBounds() : null;
    }

    public static Object readTransitionChangeEndAbsBounds(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getEndAbsBounds() : null;
    }

    public static boolean setTransitionChangeMode(Object change, int mode) {
        if (!(change instanceof TransitionInfo.Change)) {
            return false;
        }
        ((TransitionInfo.Change) change).setMode(mode);
        return true;
    }

    public static String shortObject(Object object) {
        if (object == null) {
            return "null";
        }
        try {
            String value = String.valueOf(object);
            if (value.length() > 180) {
                value = value.substring(0, 180) + "...";
            }
            return object.getClass().getName() + "{" + value + "}";
        } catch (Throwable throwable) {
            return object.getClass().getName() + "{toStringError="
                    + throwable.getClass().getSimpleName() + "}";
        }
    }

    public static String enumName(Object value) {
        return value instanceof Enum<?>
                ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private record MemberKey(Class<?> owner, String signature) {
    }
}
