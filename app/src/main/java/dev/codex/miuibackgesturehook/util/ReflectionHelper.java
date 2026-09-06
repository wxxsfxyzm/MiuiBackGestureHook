package dev.codex.miuibackgesturehook.util;

import android.window.TransitionInfo;
import android.window.BackNavigationInfo;
import android.view.SurfaceControl;
import android.os.IBinder;
import android.os.Parcelable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local, class-loader-safe reflection access shared by the concrete hookers. */
public final class ReflectionHelper {
    private static final int ANDROID_17_API_LEVEL = 37;
    private static final String[] WINDOW_FLAG_CLASSES = new String[]{
            "com.android.window.flags.Flags",
            "com.android.internal.hidden_from_bootclasspath.com.android.window.flags.Flags",
            "android.window.flags.Flags"
    };
    private static final ConcurrentHashMap<MemberKey, Field> FIELDS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MemberKey, Method> ANY_METHODS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MemberKey, Method> EXACT_METHODS =
            new ConcurrentHashMap<>();
    private static final Set<MemberKey> MISSING_MEMBERS =
            ConcurrentHashMap.newKeySet();

    private ReflectionHelper() {}

    /** Returns the JVM default value required by a reflective proxy invocation. */
    public static Object primitiveDefaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

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

    public static int readIntField(Object target, String fieldName) {
        Object value = readFieldOrNull(target, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : -1;
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

    public static Object invokeStaticMethod(
            Class<?> owner, String methodName, Class<?>[] parameterTypes, Object[] args)
            throws Exception {
        Method method = findDeclaredMethodInHierarchy(owner, null,
                methodName, parameterTypes);
        return method.invoke(null, args);
    }

    public static Object invokeOrNull(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            return invokeAnyMethod(target, methodName, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isBinderServiceAlive(String serviceName) throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Object binderObject = invokeStaticMethod(serviceManagerClass, "getService",
                new Class<?>[]{String.class}, new Object[]{serviceName});
        return binderObject instanceof IBinder
                && ((IBinder) binderObject).isBinderAlive();
    }

    public static Parcelable.Creator<?> readParcelableCreator(Class<?> parcelableClass)
            throws Exception {
        Object creator = readStaticField(parcelableClass, "CREATOR");
        if (!(creator instanceof Parcelable.Creator<?>)) {
            throw new IllegalStateException(parcelableClass.getName()
                    + ".CREATOR is unavailable");
        }
        return (Parcelable.Creator<?>) creator;
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

    public static Method findDeclaredMethodInHierarchy(Class<?> leaf, Class<?> stop,
                                                       String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> current = leaf;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            if (current == stop) {
                break;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(leaf.getName() + "." + name);
    }

    /** Resolves a named method, falling back to one unique non-synthetic signature match. */
    public static Method resolveMethodBySignature(Class<?> leaf, Class<?> stop,
                                                  String name, Class<?> returnType,
                                                  Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return findDeclaredMethodInHierarchy(leaf, stop, name, parameters);
        } catch (NoSuchMethodException ignored) {
        }
        Class<?> current = leaf;
        while (current != null) {
            Method match = null;
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.isSynthetic()
                        || candidate.getReturnType() != returnType
                        || !Arrays.equals(candidate.getParameterTypes(), parameters)) {
                    continue;
                }
                if (match != null) {
                    throw new NoSuchMethodException(name
                            + ": ambiguous signature fallback in " + current.getName()
                            + " (" + match.getName() + " vs " + candidate.getName()
                            + ")");
                }
                match = candidate;
            }
            if (match != null) {
                match.setAccessible(true);
                return match;
            }
            if (current == stop) {
                break;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(leaf.getName() + "." + name);
    }

    public static Object readFirstField(Object target, String... fieldNames)
            throws Exception {
        Throwable failure = null;
        for (String fieldName : fieldNames) {
            try {
                return readField(target, fieldName);
            } catch (Throwable throwable) {
                failure = throwable;
            }
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new NoSuchFieldException(target == null ? "null" : target.getClass().getName()
                + "." + Arrays.toString(fieldNames));
    }

    /** Reads the first field available across version-specific member spellings. */
    public static Object readFirstCrossTaskField(Object target, String... fieldNames)
            throws Exception {
        return readFirstField(target, fieldNames);
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

    /** Reads a no-argument static boolean flag from the known generated flag containers. */
    public static boolean readWindowFlag(String methodName, ClassLoader preferredLoader,
                                         boolean defaultValue) {
        if (methodName == null || preferredLoader == null) {
            return defaultValue;
        }
        for (String className : WINDOW_FLAG_CLASSES) {
            try {
                Class<?> flagsClass = Class.forName(className, false, preferredLoader);
                Method method = flagsClass.getDeclaredMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(null);
                if (result instanceof Boolean) {
                    return ((Boolean) result).booleanValue();
                }
            } catch (Throwable ignored) {
                // Generated flag containers are platform/version dependent. Continue probing.
            }
        }
        return defaultValue;
    }

    public static int readRemoteTargetActivityType(Object target) throws Exception {
        Object windowConfiguration = readField(target, "windowConfiguration");
        Object activityType = invokeAnyMethod(
                windowConfiguration, "getActivityType", new Object[0]);
        return activityType instanceof Number
                ? ((Number) activityType).intValue() : -1;
    }

    public static int readRemoteTargetWindowingMode(Object target) throws Exception {
        Object windowConfiguration = readField(target, "windowConfiguration");
        Object windowingMode = invokeAnyMethod(
                windowConfiguration, "getWindowingMode", new Object[0]);
        return windowingMode instanceof Number
                ? ((Number) windowingMode).intValue() : -1;
    }

    /** Compatibility names used by the transition classifiers. */
    public static int resolveRemoteTargetActivityType(Object target) throws Exception {
        return readRemoteTargetActivityType(target);
    }

    public static int resolveRemoteTargetWindowingMode(Object target) throws Exception {
        return readRemoteTargetWindowingMode(target);
    }

    public static boolean surfacesAreSame(SurfaceControl first, SurfaceControl second)
            throws Exception {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        Object same = invokeAnyMethod(first, "isSameSurface", new Object[]{second});
        if (!(same instanceof Boolean)) {
            throw new IllegalStateException("isSameSurface returned " + shortObject(same));
        }
        return ((Boolean) same).booleanValue();
    }

    public static String readNativeAnimationType(Object windowElement) throws Exception {
        return enumName(invokeAnyMethod(
                windowElement, "getCurrentAnimType", new Object[0]));
    }

    public static int readMotionEventId(Object event) throws Exception {
        Object value = invokeAnyMethod(event, "getId", new Object[0]);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("MotionEvent.getId returned "
                    + shortObject(value));
        }
        return ((Number) value).intValue();
    }

    public static int readMotionEventDisplayId(Object event) throws Exception {
        Object value = invokeAnyMethod(event, "getDisplayId", new Object[0]);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    public static Integer readBackNavigationType(Object navigation) {
        return navigation instanceof BackNavigationInfo
                ? ((BackNavigationInfo) navigation).getType() : null;
    }

    /** Handles Object methods plus a primitive-safe default for a dynamic proxy. */
    public static Object proxyDefaultResult(Object proxy, Method method, Object[] args,
                                             String proxyName) {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals":
                    return Boolean.valueOf(args != null && args.length == 1
                            && proxy == args[0]);
                case "hashCode":
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString":
                    return proxyName + "@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                default:
                    return null;
            }
        }
        return primitiveDefaultValue(method.getReturnType());
    }

    /** Read framework TransitionInfo through its stable public API when available. */
    public static List<?> readTransitionInfoChanges(Object info) {
        return info instanceof TransitionInfo
                ? ((TransitionInfo) info).getChanges() : null;
    }

    public static Integer readTransitionInfoType(Object info) {
        return info instanceof TransitionInfo ? ((TransitionInfo) info).getType() : null;
    }

    public static Integer readTransitionInfoRootCount(Object info) {
        return info instanceof TransitionInfo ? ((TransitionInfo) info).getRootCount() : null;
    }

    public static Object readTransitionInfoRoot(Object info, int index) {
        return info instanceof TransitionInfo
                ? ((TransitionInfo) info).getRoot(index) : null;
    }

    public static Object readTransitionRootLeash(Object root) {
        return root instanceof TransitionInfo.Root
                ? ((TransitionInfo.Root) root).getLeash() : null;
    }

    public static Object readTransitionRootOffset(Object root) {
        return root instanceof TransitionInfo.Root
                ? ((TransitionInfo.Root) root).getOffset() : null;
    }

    public static Integer readTransitionChangeMode(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getMode() : null;
    }

    public static Integer readTransitionChangeFlags(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getFlags() : null;
    }

    public static Boolean hasTransitionChangeFlags(Object change, int flags) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).hasFlags(flags) : null;
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

    public static Object readTransitionChangeActivityComponent(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getActivityComponent() : null;
    }

    public static Integer readTransitionChangeStartDisplayId(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getStartDisplayId() : null;
    }

    public static Integer readTransitionChangeEndDisplayId(Object change) {
        return change instanceof TransitionInfo.Change
                ? ((TransitionInfo.Change) change).getEndDisplayId() : null;
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
