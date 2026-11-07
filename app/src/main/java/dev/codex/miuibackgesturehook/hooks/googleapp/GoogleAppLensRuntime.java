package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

/** Circle to Search Ask Screen; independent of Live Translate and system_server. */
final class GoogleAppLensRuntime {
    private static final String THUMBNAIL = "google_lens_screen_thumbnail_retention";
    private static final String ELIGIBILITY = "google_lens_aim_eligibility_bridge";
    private static final String HINT = "google_lens_aim_hint_bridge";
    private static final String[] HINT_MARKERS = {
            "updateAimCsbHintText should not be called when enableAimCsbHintText is false.",
            "updateAimCsbContextualHintText should not be called when enableAimCsbHintText is false."
    };
    private final GoogleAppRuntime.Host host;
    private volatile SharedPreferences preferences;
    private volatile boolean preferenceFailureLogged;
    private volatile Targets targets;
    private boolean retentionLogged;

    GoogleAppLensRuntime(GoogleAppRuntime.Host host) { this.host = host; }

    boolean needsResolution(Set<String> ids) {
        return targets == null || !ids.contains(THUMBNAIL)
                || !ids.contains(ELIGIBILITY) || !ids.contains(HINT);
    }

    XposedInterface.Hooker replacement(String id) {
        switch (id) {
            case THUMBNAIL: return this::retainThumbnail;
            case ELIGIBILITY: return this::allowOmniEligibility;
            case HINT: return this::enableNativeHint;
            default: return null;
        }
    }

    void install(ClassLoader loader, Set<String> ids, DexKitBridge bridge) throws Throwable {
        MethodDataList thumbnails = bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().paramCount(1).returnType("boolean").usingEqStrings("vidcip")));
        if (thumbnails.size() != 1) {
            host.log(Log.WARN, "Ask Screen thumbnail matches=" + thumbnails.size());
            return;
        }
        MethodData thumbnail = thumbnails.get(0);
        Method thumbnailMethod = thumbnail.getMethodInstance(loader);
        if (Modifier.isStatic(thumbnailMethod.getModifiers())
                || thumbnailMethod.getParameterTypes()[0].isPrimitive()) return;
        // Resolve the entire bridge before changing either of its two decisions.
        Targets resolved = resolveTargets(loader, bridge, thumbnailMethod);
        if (resolved != null) {
            targets = resolved;
            deoptimizeWithCallers(resolved.eligibilityData, loader);
            deoptimizeWithCallers(resolved.constructorData, loader);
            if (!ids.contains(ELIGIBILITY)) host.install(resolved.eligibility, ELIGIBILITY, this::allowOmniEligibility);
            if (!ids.contains(HINT)) host.install(resolved.constructor, HINT, this::enableNativeHint);
            host.log(Log.INFO, "Resolved Ask Screen bridge, eligibility=" + resolved.eligibility
                    + ", hint=" + resolved.hint + ", model=" + resolved.constructor.getDeclaringClass().getName());
        } else {
            host.log(Log.WARN, "Ask Screen bridge missing or ambiguous; preserving native eligibility");
        }
        deoptimizeWithCallers(thumbnail, loader);
        if (!ids.contains(THUMBNAIL)) host.install(thumbnailMethod, THUMBNAIL, this::retainThumbnail);
    }

    private Targets resolveTargets(ClassLoader loader, DexKitBridge bridge, Method thumbnail) throws Throwable {
        // Google renamed the contextual-hint diagnostic. Accept either exact marker,
        // deduplicating callbacks that contain both, and validate the same owner graph.
        Map<String, MethodData> markers = new LinkedHashMap<>();
        for (String text : HINT_MARKERS) {
            for (MethodData candidate : bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().paramCount(1).usingEqStrings(text)))) {
                markers.put(candidate.getDescriptor(), candidate);
            }
        }
        if (markers.size() != 1) {
            host.log(Log.WARN, "Ask Screen hint marker matches=" + markers.size());
            return null;
        }
        MethodData marker = markers.values().iterator().next();
        int accessors = 0, hints = 0, readers = 0, gates = 0, entries = 0;
        Map<String, Targets> matches = new LinkedHashMap<>();
        // The same callback selects Google's native AIM hint and invokes the model's
        // SearchBoxView accessor. Bind the boolean field to that model, not its index.
        for (MethodData accessor : marker.getInvokes()) {
            if (!"com.google.android.libraries.lens.view.searchbox.SearchBoxView"
                    .equals(accessor.getReturnTypeName()) || accessor.getParamCount() != 0) continue;
            accessors++;
            for (UsingFieldData use : marker.getUsingFields()) {
                FieldData hint = use.getField();
                if (!hint.getDeclaredClassName().equals(accessor.getDeclaredClassName())
                        || !"boolean".equals(hint.getTypeName())) continue;
                hints++;
                for (MethodData reader : hint.getReaders()) {
                    if (!reader.getDeclaredClassName().equals(hint.getDeclaredClassName())
                            || reader.getParamCount() != 0 || !"void".equals(reader.getReturnTypeName())) continue;
                    readers++;
                    for (MethodData gate : reader.getInvokes()) {
                        if (!gate.isMethod() || gate.getParamCount() != 0
                                || !"boolean".equals(gate.getReturnTypeName())
                                || gate.getInvokes().size() != 1) continue;
                        gates++;
                        MethodData entry = gate.getInvokes().get(0);
                        if (!isOmniEntryTest(entry, loader)) continue;
                        entries++;
                        FieldData cached = null, entryOwner = null;
                        for (UsingFieldData gateUse : gate.getUsingFields()) {
                            FieldData f = gateUse.getField();
                            if (!f.getDeclaredClassName().equals(gate.getDeclaredClassName())) continue;
                            if ("boolean".equals(f.getTypeName())) {
                                if (cached != null) { cached = null; break; }
                                cached = f;
                            } else if (f.getTypeName().equals(entry.getDeclaredClassName())) {
                                if (entryOwner != null) { entryOwner = null; break; }
                                entryOwner = f;
                            }
                        }
                        if (cached == null || entryOwner == null || gate.getUsingFields().size() != 2) continue;
                        Class<?> coordinator = gate.getClassInstance(loader);
                        boolean sharedWithThumbnail = false;
                        for (Field f : thumbnail.getDeclaringClass().getDeclaredFields()) {
                            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == coordinator) sharedWithThumbnail = true;
                        }
                        if (!sharedWithThumbnail) continue;
                        FieldData modelOwner = null;
                        for (UsingFieldData readerUse : reader.getUsingFields()) {
                            FieldData f = readerUse.getField();
                            if (f.getDeclaredClassName().equals(hint.getDeclaredClassName())
                                    && f.getTypeName().equals(gate.getDeclaredClassName())) {
                                if (modelOwner != null) { modelOwner = null; break; }
                                modelOwner = f;
                            }
                        }
                        if (modelOwner == null) continue;
                        List<MethodData> constructors = new ArrayList<>();
                        for (MethodData writer : hint.getWriters()) {
                            if (writer.isConstructor() && writer.getDeclaredClassName().equals(hint.getDeclaredClassName()))
                                constructors.add(writer);
                        }
                        if (constructors.size() != 1) continue;
                        Targets t = new Targets(gate, constructors.get(0), hint.getFieldInstance(loader),
                                modelOwner.getFieldInstance(loader), entryOwner.getFieldInstance(loader),
                                entry.getMethodInstance(loader), loader);
                        if (Modifier.isStatic(t.eligibility.getModifiers())
                                || Modifier.isStatic(t.hint.getModifiers())
                                || Modifier.isStatic(t.modelCoordinator.getModifiers())
                                || Modifier.isStatic(t.entryOwner.getModifiers())) continue;
                        matches.put(gate.getDescriptor() + hint.getDescriptor() + modelOwner.getDescriptor(), t);
                    }
                }
            }
        }
        host.log(Log.INFO, "Ask Screen resolver: accessors=" + accessors + ", hints=" + hints
                + ", readers=" + readers + ", gates=" + gates + ", omniTests=" + entries
                + ", targets=" + matches.size());
        return matches.size() == 1 ? matches.values().iterator().next() : null;
    }

    private static boolean isOmniEntryTest(MethodData entry, ClassLoader loader) throws Throwable {
        if (!entry.isMethod() || entry.getParamCount() != 0 || !"boolean".equals(entry.getReturnTypeName())
                || entry.getUsingFields().size() != 1 || entry.getInvokes().size() != 1) return false;
        Field field = entry.getUsingFields().get(0).getField().getFieldInstance(loader);
        if (!field.isEnumConstant()
                || entry.getInvokes().get(0).getReturnTypeInstance(loader) != field.getType()) return false;
        field.setAccessible(true);
        Object value = field.get(null);
        return value instanceof Enum<?> && ((Enum<?>) value).name().equals("OMNI");
    }

    private void deoptimizeWithCallers(MethodData target, ClassLoader loader) throws Throwable {
        Executable executable = target.isConstructor() ? target.getConstructorInstance(loader) : target.getMethodInstance(loader);
        int succeeded = host.deoptimize(executable) ? 1 : 0;
        int attempted = 1;
        for (MethodData caller : target.getCallers()) {
            if (!caller.isMethod() && !caller.isConstructor()) continue;
            attempted++;
            Executable method = caller.isConstructor() ? caller.getConstructorInstance(loader) : caller.getMethodInstance(loader);
            if (host.deoptimize(method)) succeeded++;
        }
        host.log(succeeded == attempted ? Log.INFO : Log.WARN,
                "Ask Screen deoptimized=" + succeeded + "/" + attempted + ", target=" + executable);
    }

    private Object retainThumbnail(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!Boolean.FALSE.equals(result) || !enabled()) return result;
        if (!retentionLogged) {
            retentionLogged = true;
            host.log(Log.INFO, "Retained Google Lens screen thumbnail");
        }
        return Boolean.TRUE;
    }

    private Object allowOmniEligibility(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        Targets t = targets;
        if (t == null || !t.eligibility.equals(chain.getExecutable()) || !Boolean.FALSE.equals(result) || !enabled()) return result;
        try { return t.isOmni(chain.getThisObject()) ? Boolean.TRUE : result; }
        catch (ReflectiveOperationException failure) {
            host.log(Log.WARN, "Ask Screen entrypoint unavailable; preserving eligibility", failure);
            return result;
        }
    }

    private Object enableNativeHint(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        Targets t = targets;
        if (t == null || !t.constructor.equals(chain.getExecutable()) || !enabled()) return result;
        try {
            Object model = chain.getThisObject();
            if (t.isOmni(t.modelCoordinator.get(model)) && !t.hint.getBoolean(model)) t.hint.setBoolean(model, true);
        } catch (ReflectiveOperationException failure) {
            host.log(Log.WARN, "Ask Screen native hint unavailable", failure);
        }
        return result;
    }

    private boolean enabled() {
        try {
            SharedPreferences prefs = preferences;
            if (prefs == null) preferences = prefs = host.preferences(PredictiveBackPreferences.GROUP);
            boolean enabled = prefs.getBoolean(PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                    PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX)
                    && prefs.getBoolean(PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            preferenceFailureLogged = false;
            return enabled;
        } catch (Throwable failure) {
            if (!preferenceFailureLogged) {
                preferenceFailureLogged = true;
                host.log(Log.WARN, "Ask Screen preference unavailable", failure);
            }
            return false;
        }
    }

    private static final class Targets {
        final MethodData eligibilityData, constructorData;
        final Method eligibility, entry;
        final Constructor<?> constructor;
        final Field hint, modelCoordinator, entryOwner;
        Targets(MethodData gate, MethodData init, Field hint, Field modelCoordinator,
                Field entryOwner, Method entry, ClassLoader loader) throws Throwable {
            eligibilityData = gate; constructorData = init;
            eligibility = gate.getMethodInstance(loader); constructor = init.getConstructorInstance(loader);
            this.hint = hint; this.modelCoordinator = modelCoordinator; this.entryOwner = entryOwner; this.entry = entry;
            hint.setAccessible(true); modelCoordinator.setAccessible(true); entryOwner.setAccessible(true); entry.setAccessible(true);
        }
        boolean isOmni(Object coordinator) throws ReflectiveOperationException {
            return coordinator != null && Boolean.TRUE.equals(entry.invoke(entryOwner.get(coordinator)));
        }
    }
}
