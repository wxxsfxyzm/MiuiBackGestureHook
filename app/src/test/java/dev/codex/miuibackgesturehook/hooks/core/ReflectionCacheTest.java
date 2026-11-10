package dev.codex.miuibackgesturehook.hooks.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ReflectionCacheTest {
    @SuppressWarnings("unused")
    private static class Base {
        private int baseValue = 7;

        public void override() {
        }

        public void baseOnly(String value) {
        }
    }

    @SuppressWarnings("unused")
    private static final class Child extends Base {
        private int childValue = 3;

        @Override
        public void override() {
        }

        private void single(int value) {
        }
    }

    @SuppressWarnings("unused")
    private static final class Overloaded {
        private void pick(int value) {
        }

        private void pick(String value) {
        }
    }

    private static final class Recorder implements ReflectionCache.AmbiguityListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onAmbiguousMethod(Class<?> owner, String methodName, int argCount,
                                      Method selected, List<Method> candidates) {
            events.add(owner.getSimpleName() + "." + methodName + "/" + argCount
                    + ":" + candidates.size());
        }
    }

    @Test
    public void fieldResolvesThroughSuperclassAndIsCached() throws Exception {
        ReflectionCache cache = new ReflectionCache(null);
        Field field = cache.field(Child.class, "baseValue");
        assertEquals(Base.class, field.getDeclaringClass());
        assertEquals(7, field.getInt(new Child()));
        assertSame(field, cache.field(Child.class, "baseValue"));
        assertSame(cache.field(Child.class, "childValue"),
                cache.field(Child.class, "childValue"));
    }

    @Test
    public void missingFieldIsReportedEveryTime() {
        ReflectionCache cache = new ReflectionCache(null);
        assertThrows(NoSuchFieldException.class, () -> cache.field(Child.class, "absent"));
        assertThrows(NoSuchFieldException.class, () -> cache.field(Child.class, "absent"));
    }

    @Test
    public void declaredFieldDoesNotSearchSuperclasses() throws Exception {
        ReflectionCache cache = new ReflectionCache(null);
        assertThrows(NoSuchFieldException.class,
                () -> cache.declaredField(Child.class, "baseValue"));
        assertThrows(NoSuchFieldException.class,
                () -> cache.declaredField(Child.class, "baseValue"));
        Field childValue = cache.declaredField(Child.class, "childValue");
        assertEquals(Child.class, childValue.getDeclaringClass());
        assertSame(childValue, cache.declaredField(Child.class, "childValue"));
    }

    @Test
    public void declaredMissDoesNotPoisonInheritedLookup() throws Exception {
        ReflectionCache cache = new ReflectionCache(null);
        assertThrows(NoSuchFieldException.class,
                () -> cache.declaredField(Child.class, "baseValue"));
        assertEquals(Base.class, cache.field(Child.class, "baseValue").getDeclaringClass());
        assertThrows(NoSuchFieldException.class,
                () -> cache.declaredField(Child.class, "baseValue"));
    }

    @Test
    public void anyMethodMatchesOriginalSelectionAndIsCached() throws Exception {
        ReflectionCache cache = new ReflectionCache(null);
        Method single = cache.anyMethod(Child.class, "single", 1);
        assertEquals(ReflectionCache.findAnyMethod(Child.class, "single", 1), single);
        assertSame(single, cache.anyMethod(Child.class, "single", 1));
        assertEquals(Child.class, cache.anyMethod(Child.class, "override", 0)
                .getDeclaringClass());
        assertEquals(Base.class, cache.anyMethod(Child.class, "baseOnly", 1)
                .getDeclaringClass());
    }

    @Test
    public void anyMethodCachesEachArgumentCountIndependently() {
        ReflectionCache cache = new ReflectionCache(null);
        assertThrows(NoSuchMethodException.class, () -> cache.anyMethod(Child.class, "single", 3));
        assertThrows(NoSuchMethodException.class, () -> cache.anyMethod(Child.class, "single", 0));
        try {
            assertEquals(1, cache.anyMethod(Child.class, "single", 1).getParameterCount());
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
        assertThrows(NoSuchMethodException.class, () -> cache.anyMethod(Child.class, "single", 3));
    }

    @Test
    public void overridesAreNotReportedAsAmbiguous() throws Exception {
        Recorder recorder = new Recorder();
        ReflectionCache cache = new ReflectionCache(recorder);
        cache.anyMethod(Child.class, "override", 0);
        cache.anyMethod(Child.class, "single", 1);
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void overloadsWithSameArgumentCountAreReportedOnce() throws Exception {
        Recorder recorder = new Recorder();
        ReflectionCache cache = new ReflectionCache(recorder);
        Method first = cache.anyMethod(Overloaded.class, "pick", 1);
        Method second = cache.anyMethod(Overloaded.class, "pick", 1);
        assertSame(first, second);
        assertEquals(List.of("Overloaded.pick/1:2"), recorder.events);
    }

    @Test
    public void exactMethodDistinguishesParameterTypes() throws Exception {
        ReflectionCache cache = new ReflectionCache(null);
        Method baseOnly = cache.exactMethod(Child.class, "baseOnly",
                new Class<?>[]{String.class});
        assertSame(baseOnly, cache.exactMethod(Child.class, "baseOnly",
                new Class<?>[]{String.class}));
        assertThrows(NoSuchMethodException.class, () -> cache.exactMethod(
                Child.class, "baseOnly", new Class<?>[]{Integer.class}));
        assertThrows(NoSuchMethodException.class, () -> cache.exactMethod(
                Child.class, "baseOnly", new Class<?>[]{Integer.class}));
        assertSame(baseOnly, cache.exactMethod(Child.class, "baseOnly",
                new Class<?>[]{String.class}));
    }

    @Test
    public void exactMethodUsesPublicLookupOnly() {
        ReflectionCache cache = new ReflectionCache(null);
        assertThrows(NoSuchMethodException.class, () -> cache.exactMethod(
                Child.class, "single", new Class<?>[]{int.class}));
    }

    @Test
    public void findAnyMethodReturnsNullWhenAbsent() {
        assertNull(ReflectionCache.findAnyMethod(Child.class, "absent", 0));
    }
}
