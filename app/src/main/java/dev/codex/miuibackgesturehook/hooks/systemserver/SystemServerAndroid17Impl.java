package dev.codex.miuibackgesturehook.hooks.systemserver;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceControl;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

final class SystemServerAndroid17Impl extends SystemServerPlatformImpl {
    private static final String TRANSITION_STUB =
            "com.android.server.wm.TransitionStub";
    private static final String BACK_WINDOW_ANIMATION_ADAPTOR =
            "com.android.server.wm.BackNavigationController$AnimationHandler"
                    + "$BackWindowAnimationAdaptor";
    private static final int TRANSIT_TO_FRONT = 3;
    private static final int TRANSIT_CHANGE = 6;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int ANIMATION_TYPE_PREDICTIVE_BACK = 256;
    private static final int CHANGE_INFO_BACK_TOP = 0x80;
    private static final int CHANGE_INFO_BACK_BELOW_A17 = 0x110;
    private static final int TRANSITION_FLAG_BACK_TOP = 0x08000000;
    private static final int TRANSITION_FLAG_BACK_GESTURE_ANIMATED = 0x00020000;
    private static final int TRANSITION_FLAG_IS_OCCLUDED = 0x00008000;
    private static final int CROSS_TASK_CLOSING_FLAGS =
            TRANSITION_FLAG_BACK_TOP | TRANSITION_FLAG_BACK_GESTURE_ANIMATED;
    private static final int CROSS_TASK_OPENING_FLAGS =
            TRANSITION_FLAG_BACK_GESTURE_ANIMATED | TRANSITION_FLAG_IS_OCCLUDED;
    private static final int CROSS_TASK_EMBEDDED_OPENING_FLAGS =
            CROSS_TASK_OPENING_FLAGS | 0x200 /* IN_TASK_WITH_EMBEDDED_ACTIVITY */
                    | 0x400 /* FILLS_TASK */;

    static boolean matches(Class<?> transitionClass) {
        return findCalculateTransitionInfo(transitionClass) != null;
    }

    @Override
    String name() {
        return "android17";
    }

    @Override
    boolean nativeLauncherOwnsContextualSearchLongPress() {
        return true;
    }

    @Override
    Method calculateTransitionInfoMethod(Class<?> transitionClass)
            throws NoSuchMethodException {
        Method method = findCalculateTransitionInfo(transitionClass);
        if (method == null) {
            throw new NoSuchMethodException(
                    "Android 17 Transition.calculateTransitionInfo(int, int, ArrayList,"
                            + " Transaction, int, TransitionStub)");
        }
        method.setAccessible(true);
        return method;
    }

    @Override
    Object interceptScheduleAnimationPrepareTransition(
            SystemServerHookRuntime runtime,
            XposedInterface.Chain chain) throws Throwable {
        Object builder = chain.getThisObject();
        Object launchBehind = runtime.readSystemServerPlatformFieldOrNull(
                builder, "mIsLaunchBehind");
        boolean launchBehindKnown = launchBehind instanceof Boolean;
        boolean returnToHome = Boolean.TRUE.equals(launchBehind);

        // Android 17 removed unifyBackNavigationTransition(). Its
        // ScheduleAnimationBuilder now creates TRANSIT_PREDICTIVE_BACK for every
        // non-WindowState target, so preserving the stock call is the native prepared path.
        runtime.logSystemServerPlatform(Log.INFO,
                "Allowing Android 17 ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                        + ", nativePreparedTransition=unconditional"
                        + ", returnToHome=" + (launchBehindKnown
                        ? Boolean.toString(returnToHome) : "unknown")
                        + ", launchBehind=" + launchBehind
                        + ", builder="
                        + runtime.describeSystemServerPlatformObject(builder));
        return chain.proceed();
    }

    @Override
    Method openingSurfaceVisibilityMethod(ClassLoader classLoader) throws Exception {
        Class<?> builderClass = Class.forName(
                "com.android.server.wm.BackNavigationController$AnimationHandler"
                        + "$ScheduleAnimationBuilder",
                false, classLoader);
        for (Method method : builderClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("applyPreviewStrategy".equals(method.getName())
                    && parameters.length == 2
                    && parameters[0].getName().endsWith(
                    "$BackWindowAnimationAdaptorWrapper")
                    && parameters[1].isArray()
                    && "com.android.server.wm.ActivityRecord".equals(
                    parameters[1].getComponentType().getName())) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(
                "Android 17 ScheduleAnimationBuilder.applyPreviewStrategy");
    }

    @Override
    boolean shouldForceOpeningTaskFragmentAtAnimationStart() {
        // Exact Android 17 createAdaptor() already updates and shows the TaskFragment for
        // Activity targets. The old module hook widened that behavior to Task targets, which
        // is not part of AOSP's cross-task preview preparation.
        return false;
    }

    boolean isFixedRotationCrossActivityPair(SystemServerHookRuntime runtime,
                                             Object closing, Object opening,
                                             Rect closingBounds, Rect openingBounds)
            throws Exception {
        if (runtime.invokeSystemServerPlatformMethod(closing, "asActivityRecord") != closing
                || runtime.invokeSystemServerPlatformMethod(opening, "asActivityRecord") != opening
                || readIntResult(runtime, closing, "getWindowingMode") != WINDOWING_MODE_FULLSCREEN
                || readIntResult(runtime, opening, "getWindowingMode") != WINDOWING_MODE_FULLSCREEN
                || !Boolean.FALSE.equals(runtime.invokeSystemServerPlatformMethod(
                closing, "hasFixedRotationTransform"))
                || !Boolean.TRUE.equals(runtime.invokeSystemServerPlatformMethod(
                opening, "isFixedRotationTransforming"))) {
            return false;
        }
        Object display = runtime.readSystemServerPlatformFieldOrNull(closing, "mDisplayContent");
        Object state = runtime.readSystemServerPlatformFieldOrNull(
                opening, "mFixedRotationTransformState");
        Object associated = runtime.readSystemServerPlatformFieldOrNull(state, "mAssociatedTokens");
        Object task = runtime.invokeSystemServerPlatformMethod(closing, "getTask");
        return display != null && state != null && task != null
                && display == runtime.readSystemServerPlatformFieldOrNull(opening, "mDisplayContent")
                && readIntResult(runtime, display, "getDisplayId") == 0
                && task == runtime.invokeSystemServerPlatformMethod(opening, "getTask")
                && associated instanceof List<?> && ((List<?>) associated).size() == 1
                && ((List<?>) associated).get(0) == opening
                && closingBounds.left == 0 && closingBounds.top == 0
                && openingBounds.left == 0 && openingBounds.top == 0
                && closingBounds.equals(runtime.invokeSystemServerPlatformMethod(task, "getBounds"))
                && openingBounds.equals(runtime.invokeSystemServerPlatformMethod(
                opening, "getFixedRotationTransformDisplayBounds"))
                && isQuarterTurnGeometry(
                readIntResult(runtime, closing, "getRelativeDisplayRotation"),
                readIntResult(runtime, opening, "getRelativeDisplayRotation"),
                closingBounds.width(), closingBounds.height(),
                openingBounds.width(), openingBounds.height());
    }

    static boolean isQuarterTurnGeometry(int closingRotation, int openingRotation,
                                          int closingWidth, int closingHeight,
                                          int openingWidth, int openingHeight) {
        return closingRotation == 0 && (openingRotation == 1 || openingRotation == 3)
                && closingWidth > 0 && closingHeight > 0 && closingWidth != closingHeight
                && openingWidth == closingHeight && openingHeight == closingWidth;
    }

    private static int readIntResult(SystemServerHookRuntime runtime,
                                     Object target, String method) throws Exception {
        Object value = runtime.invokeSystemServerPlatformMethod(target, method);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    @Override
    void restoreOpeningSurfaceVisibility(SystemServerHookRuntime runtime,
                                         XposedInterface.Chain chain) throws Exception {
        Object builder = chain.getThisObject();
        if (!Boolean.FALSE.equals(runtime.readSystemServerPlatformFieldOrNull(
                builder, "mIsLaunchBehind"))) {
            return;
        }
        Object close = runtime.readSystemServerPlatformFieldOrNull(
                builder, "mCloseTarget");
        Object opensObject = runtime.readSystemServerPlatformFieldOrNull(
                builder, "mOpenTargets");
        if (close == null || opensObject == null || !opensObject.getClass().isArray()
                || Array.getLength(opensObject) != 1) {
            return;
        }
        Object open = Array.get(opensObject, 0);
        Object closeTask = runtime.invokeSystemServerPlatformMethod(
                close, "asTask", new Object[0]);
        Object openTask = open == null ? null
                : runtime.invokeSystemServerPlatformMethod(
                open, "asTask", new Object[0]);
        if (closeTask != close || openTask != open || closeTask == openTask) {
            return;
        }
        Object activitiesObject = chain.getArg(1);
        if (activitiesObject == null || !activitiesObject.getClass().isArray()
                || Array.getLength(activitiesObject) == 0) {
            return;
        }
        Object transitionController = runtime.readSystemServerPlatformFieldOrNull(
                Array.get(activitiesObject, 0), "mTransitionController");
        if (!Boolean.TRUE.equals(runtime.invokeSystemServerPlatformMethod(
                transitionController, "isShellTransitionsEnabled", new Object[0]))) {
            return;
        }

        IdentityHashMap<Object, SurfaceVisibilityNode> nodes = new IdentityHashMap<>();
        Object displayContent = null;
        for (int index = 0; index < Array.getLength(activitiesObject); index++) {
            Object activity = Array.get(activitiesObject, index);
            if (activity == null
                    || runtime.invokeSystemServerPlatformMethod(
                    activity, "getTask", new Object[0]) != openTask
                    || !Boolean.TRUE.equals(runtime.invokeSystemServerPlatformMethod(
                    activity, "isVisibleRequested", new Object[0]))) {
                throw new IllegalStateException(
                        "Android 17 cross-task opening Activity identity changed");
            }
            Object activityDisplay = runtime.readSystemServerPlatformFieldOrNull(
                    activity, "mDisplayContent");
            if (activityDisplay == null
                    || (displayContent != null && displayContent != activityDisplay)) {
                throw new IllegalStateException(
                        "Android 17 cross-task opening display changed");
            }
            displayContent = activityDisplay;
            collectSurfaceNode(runtime, nodes, activity, true, false);
            for (Object parent = runtime.invokeSystemServerPlatformMethod(
                    activity, "getParent", new Object[0]);
                 parent != null && parent != displayContent;
                 parent = runtime.invokeSystemServerPlatformMethod(
                         parent, "getParent", new Object[0])) {
                Object task = runtime.invokeSystemServerPlatformMethod(
                        parent, "asTask", new Object[0]);
                collectSurfaceNode(runtime, nodes, parent, false, task == parent);
            }
        }
        if (!nodes.containsKey(openTask)) {
            throw new IllegalStateException(
                    "Android 17 cross-task opening Task is outside Activity ancestry");
        }

        // AOSP performs this at the end of applyPreviewStrategy(): show the opening
        // Activity and every parent, then schedule the shared sync transaction. Xiaomi
        // 4371 stripped that block and also gutted
        // TransitionController.onVisibleWithoutCollectingTransition(). This Android 17
        // branch no longer has ActivityRecord/Task.mLastSurfaceShowing; visibility state
        // is refreshed by WindowAnimator.updateSurfaceVisibility(), so writing the old
        // Android 14-16 field is both unnecessary and invalid here.
        for (SurfaceVisibilityNode node : new ArrayList<>(nodes.values())) {
            runtime.invokeSystemServerPlatformMethod(
                    node.transaction, "show", node.surface);
        }
        for (int index = 0; index < Array.getLength(activitiesObject); index++) {
            runtime.invokeSystemServerPlatformMethod(
                    Array.get(activitiesObject, index), "scheduleAnimation", new Object[0]);
        }
        runtime.logSystemServerPlatform(Log.INFO,
                "Restored AOSP Android 17 cross-task opening surfaces"
                        + ", closeTask="
                        + runtime.describeSystemServerPlatformObject(closeTask)
                        + ", openTask="
                        + runtime.describeSystemServerPlatformObject(openTask)
                        + ", activities=" + Array.getLength(activitiesObject)
                        + ", surfaces=" + nodes.size());
    }

    private static void collectSurfaceNode(SystemServerHookRuntime runtime,
                                           IdentityHashMap<Object, SurfaceVisibilityNode> nodes,
                                           Object container,
                                           boolean activity,
                                           boolean task) throws Exception {
        if (nodes.containsKey(container)) {
            SurfaceVisibilityNode previous = nodes.get(container);
            previous.activity |= activity;
            previous.task |= task;
            return;
        }
        Object surfaceObject = runtime.readSystemServerPlatformFieldOrNull(
                container, "mSurfaceControl");
        Object transaction = runtime.invokeSystemServerPlatformMethod(
                container, "getSyncTransaction", new Object[0]);
        if (!(surfaceObject instanceof SurfaceControl)
                || !((SurfaceControl) surfaceObject).isValid()
                || !(transaction instanceof SurfaceControl.Transaction)) {
            throw new IllegalStateException(
                    "Android 17 cross-task opening Surface is unavailable, container="
                            + runtime.describeSystemServerPlatformObject(container));
        }
        nodes.put(container, new SurfaceVisibilityNode(
                container, (SurfaceControl) surfaceObject,
                (SurfaceControl.Transaction) transaction, activity, task));
    }

    private static final class SurfaceVisibilityNode {
        final Object container;
        final SurfaceControl surface;
        final SurfaceControl.Transaction transaction;
        boolean activity;
        boolean task;

        SurfaceVisibilityNode(Object container, SurfaceControl surface,
                              SurfaceControl.Transaction transaction,
                              boolean activity, boolean task) {
            this.container = container;
            this.surface = surface;
            this.transaction = transaction;
            this.activity = activity;
            this.task = task;
        }
    }

    @Override
    void inspectCalculatedPredictiveTransition(SystemServerHookRuntime runtime,
                                                XposedInterface.Chain chain,
                                                Object result) throws Exception {
        Object type = chain.getArg(0);
        if (!(type instanceof Number)
                || ((Number) type).intValue() != 13) {
            return;
        }
        Object targetsObject = chain.getArg(2);
        if (!(targetsObject instanceof List<?>)
                || (((List<?>) targetsObject).size() != 2
                && ((List<?>) targetsObject).size() != 3)) {
            return;
        }
        List<?> targets = (List<?>) targetsObject;
        int taskCount = 0;
        for (Object target : targets) {
            Object container = runtime.readSystemServerPlatformFieldOrNull(target, "mContainer");
            if (container != null && runtime.invokeSystemServerPlatformMethod(
                    container, "asTask") == container) {
                taskCount++;
            }
        }
        if (taskCount != 2) {
            return;
        }
        Object changesObject = runtime.readSystemServerPlatformTransitionChanges(result);
        String changesBefore = changesObject instanceof List<?>
                ? describeChanges(runtime, (List<?>) changesObject)
                : runtime.describeSystemServerPlatformObject(changesObject);
        boolean normalized = changesObject instanceof List<?>
                && normalizeCrossTaskPrepareRole(runtime, chain, result, targets,
                (List<?>) changesObject);
        String changesAfter = normalized
                ? describeChanges(runtime, (List<?>) changesObject) : changesBefore;
        runtime.logSystemServerPlatform(Log.INFO,
                "Android 17 cross-task prepared transition"
                        + ", transitionId=" + chain.getArg(4)
                        + ", targets=" + describeTargets(runtime, targets)
                        + ", normalized=" + normalized
                        + ", changesBefore=" + changesBefore
                        + (normalized ? ", changesAfter=" + changesAfter : ""));
    }

    private static boolean normalizeCrossTaskPrepareRole(
            SystemServerHookRuntime runtime,
            XposedInterface.Chain chain,
            Object transitionInfo,
            List<?> targets,
            List<?> changes) throws Exception {
        if (changes.size() != targets.size()) {
            return false;
        }
        Object closingInfo = null;
        Object openingInfo = null;
        Object embeddedInfo = null;
        Object closingTask = null;
        Object openingTask = null;
        for (Object target : targets) {
            Object container = runtime.readSystemServerPlatformFieldOrNull(
                    target, "mContainer");
            if (container == null) {
                return false;
            }
            if (runtime.invokeSystemServerPlatformMethod(container, "asTask") != container) {
                if (embeddedInfo != null || runtime.invokeSystemServerPlatformMethod(
                        container, "asTaskFragment") != container) {
                    return false;
                }
                embeddedInfo = target;
                continue;
            }
            Object animator = runtime.readSystemServerPlatformFieldOrNull(
                    container, "mSurfaceAnimator");
            Object animation = runtime.readSystemServerPlatformFieldOrNull(
                    animator, "mAnimation");
            if (animation == null
                    || !BACK_WINDOW_ANIMATION_ADAPTOR.equals(
                    animation.getClass().getName())
                    || runtime.readSystemServerPlatformFieldOrNull(
                    animation, "mTarget") != container) {
                return false;
            }
            Object isOpen = runtime.readSystemServerPlatformFieldOrNull(
                    animation, "mIsOpen");
            if (Boolean.FALSE.equals(isOpen) && closingInfo == null) {
                closingInfo = target;
                closingTask = container;
            } else if (Boolean.TRUE.equals(isOpen) && openingInfo == null) {
                openingInfo = target;
                openingTask = container;
            } else {
                return false;
            }
        }
        if (closingInfo == null || openingInfo == null
                || closingTask == null || openingTask == null
                || closingTask == openingTask
                || readIntField(runtime, closingInfo, "mFlags", -1)
                != CHANGE_INFO_BACK_TOP
                || readIntField(runtime, openingInfo, "mFlags", -1)
                != CHANGE_INFO_BACK_BELOW_A17
                || !Boolean.TRUE.equals(runtime.readSystemServerPlatformFieldOrNull(
                closingInfo, "mVisible"))
                || !Boolean.FALSE.equals(runtime.readSystemServerPlatformFieldOrNull(
                openingInfo, "mVisible"))
                || !Boolean.TRUE.equals(invokeOrNull(
                runtime, closingTask, "isVisibleRequested"))
                || !Boolean.TRUE.equals(invokeOrNull(
                runtime, openingTask, "isVisibleRequested"))
                || valueOrDefault((Integer) invokeOrNull(
                runtime, closingTask, "getWindowingMode"), -1)
                != WINDOWING_MODE_FULLSCREEN
                || valueOrDefault((Integer) invokeOrNull(
                runtime, openingTask, "getWindowingMode"), -1)
                != WINDOWING_MODE_FULLSCREEN
                || runtime.readSystemServerPlatformFieldOrNull(
                closingTask, "mDisplayContent")
                != runtime.readSystemServerPlatformFieldOrNull(
                openingTask, "mDisplayContent")) {
            return false;
        }
        Object closingBoundsObject = invokeOrNull(runtime, closingTask, "getBounds");
        Object openingBoundsObject = invokeOrNull(runtime, openingTask, "getBounds");
        if (!(closingBoundsObject instanceof Rect)
                || !(openingBoundsObject instanceof Rect)
                || ((Rect) closingBoundsObject).isEmpty()
                || !closingBoundsObject.equals(openingBoundsObject)) {
            return false;
        }

        int closingTaskId = readIntField(runtime, closingTask, "mTaskId", -1);
        int openingTaskId = readIntField(runtime, openingTask, "mTaskId", -1);
        Object closingChange = null;
        Object openingChange = null;
        Object embeddedChange = null;
        for (Object change : changes) {
            Object taskInfo = runtime.readSystemServerPlatformTransitionChangeTaskInfo(change);
            int taskId = readIntField(runtime, taskInfo, "taskId", -1);
            if (taskInfo == null && embeddedInfo != null && embeddedChange == null) {
                embeddedChange = change;
            } else if (taskId == closingTaskId && closingChange == null) {
                closingChange = change;
            } else if (taskId == openingTaskId && openingChange == null) {
                openingChange = change;
            } else {
                return false;
            }
        }
        if (closingTaskId < 0 || openingTaskId < 0
                || closingChange == null || openingChange == null
                || valueOrDefault(
                runtime.readSystemServerPlatformTransitionChangeMode(
                        closingChange), -1) != TRANSIT_TO_FRONT
                || valueOrDefault(
                runtime.readSystemServerPlatformTransitionChangeMode(
                        openingChange), -1) != TRANSIT_TO_FRONT
                || valueOrDefault(
                runtime.readSystemServerPlatformTransitionChangeFlags(
                        closingChange), -1) != CROSS_TASK_CLOSING_FLAGS
                || valueOrDefault(
                runtime.readSystemServerPlatformTransitionChangeFlags(
                        openingChange), -1) != CROSS_TASK_OPENING_FLAGS
                || runtime.readSystemServerPlatformTransitionChangeParent(
                closingChange) != null
                || runtime.readSystemServerPlatformTransitionChangeLastParent(
                closingChange) != null
                || runtime.readSystemServerPlatformTransitionChangeParent(
                openingChange) != null
                || runtime.readSystemServerPlatformTransitionChangeLastParent(
                openingChange) != null) {
            return false;
        }
        if (embeddedInfo != null && !isExactOpeningEmbeddedFragment(runtime, transitionInfo,
                embeddedInfo, embeddedChange, closingTask, openingTask, closingChange,
                openingChange, (Rect) openingBoundsObject)) {
            return false;
        }

        Object closingAnimator = runtime.readSystemServerPlatformFieldOrNull(
                closingTask, "mSurfaceAnimator");
        Object openingAnimator = runtime.readSystemServerPlatformFieldOrNull(
                openingTask, "mSurfaceAnimator");
        Object closingAnimation = runtime.readSystemServerPlatformFieldOrNull(
                closingAnimator, "mAnimation");
        Object openingAnimation = runtime.readSystemServerPlatformFieldOrNull(
                openingAnimator, "mAnimation");
        Object closingLeash = runtime.readSystemServerPlatformFieldOrNull(
                closingAnimator, "mLeash");
        Object openingLeash = runtime.readSystemServerPlatformFieldOrNull(
                openingAnimator, "mLeash");
        int closingLayer = readIntField(runtime, closingTask, "mLastLayer", -1);
        int openingLayer = readIntField(runtime, openingTask, "mLastLayer", -1);
        Object startTransaction = chain.getArg(3);
        if (runtime.readSystemServerPlatformFieldOrNull(
                closingAnimation, "mCapturedLeash") != closingLeash
                || runtime.readSystemServerPlatformFieldOrNull(
                openingAnimation, "mCapturedLeash") != openingLeash
                || readIntField(runtime, closingAnimator,
                "mAnimationType", -1) != ANIMATION_TYPE_PREDICTIVE_BACK
                || readIntField(runtime, openingAnimator,
                "mAnimationType", -1) != ANIMATION_TYPE_PREDICTIVE_BACK
                || runtime.readSystemServerPlatformFieldOrNull(
                closingTask, "mLastRelativeToLayer") != null
                || runtime.readSystemServerPlatformFieldOrNull(
                openingTask, "mLastRelativeToLayer") != null
                || !(closingLeash instanceof SurfaceControl)
                || !(openingLeash instanceof SurfaceControl)
                || !((SurfaceControl) closingLeash).isValid()
                || !((SurfaceControl) openingLeash).isValid()
                || !(startTransaction instanceof SurfaceControl.Transaction)
                || closingLayer <= openingLayer || openingLayer < 0) {
            return false;
        }
        if (!runtime.setSystemServerPlatformTransitionChangeMode(
                closingChange, TRANSIT_CHANGE)) {
            return false;
        }
        SurfaceControl.Transaction transaction =
                (SurfaceControl.Transaction) startTransaction;
        transaction.setLayer((SurfaceControl) openingLeash, openingLayer);
        transaction.setLayer((SurfaceControl) closingLeash, closingLayer);
        if (valueOrDefault(
                runtime.readSystemServerPlatformTransitionChangeMode(
                        closingChange), -1) != TRANSIT_CHANGE
                || valueOrDefault(
                runtime.readSystemServerPlatformTransitionChangeMode(
                        openingChange), -1) != TRANSIT_TO_FRONT) {
            throw new IllegalStateException(
                    "Android 17 cross-task prepare role was not retained");
        }
        runtime.logSystemServerPlatform(Log.INFO,
                "Normalized Android 17 cross-task prepare role"
                        + ", transitionId=" + chain.getArg(4)
                        + ", closingTaskId=" + closingTaskId
                        + ", openingTaskId=" + openingTaskId
                        + ", embeddedOpening=" + (embeddedInfo != null)
                        + ", mode=" + TRANSIT_TO_FRONT + "->" + TRANSIT_CHANGE
                        + ", leashLayers=" + closingLayer + "/" + openingLayer);
        return true;
    }

    private static boolean isExactOpeningEmbeddedFragment(
            SystemServerHookRuntime runtime, Object info, Object embeddedInfo,
            Object embeddedChange, Object closingTask, Object openingTask,
            Object closingChange, Object openingChange, Rect bounds) throws Exception {
        Object fragment = runtime.readSystemServerPlatformFieldOrNull(embeddedInfo, "mContainer");
        Object display = runtime.readSystemServerPlatformFieldOrNull(openingTask, "mDisplayContent");
        if (embeddedChange == null || display == null
                || readIntField(runtime, embeddedInfo, "mFlags", -1) != 0x10
                || !Boolean.FALSE.equals(runtime.readSystemServerPlatformFieldOrNull(
                embeddedInfo, "mVisible"))
                || !Boolean.TRUE.equals(runtime.invokeSystemServerPlatformMethod(fragment, "isEmbedded"))
                || !Boolean.TRUE.equals(runtime.invokeSystemServerPlatformMethod(fragment, "isVisibleRequested"))
                || runtime.invokeSystemServerPlatformMethod(fragment, "getParent") != openingTask
                || runtime.invokeSystemServerPlatformMethod(fragment, "getTask") != openingTask
                || runtime.readSystemServerPlatformFieldOrNull(fragment, "mDisplayContent") != display
                || readIntResult(runtime, fragment, "getWindowingMode") != WINDOWING_MODE_FULLSCREEN
                || readIntResult(runtime, closingTask, "getActivityType") != 1
                || readIntResult(runtime, openingTask, "getActivityType") != 1
                || readIntResult(runtime, display, "getDisplayId") != 0
                || !bounds.equals(runtime.invokeSystemServerPlatformMethod(display, "getBounds"))
                || !bounds.equals(runtime.invokeSystemServerPlatformMethod(fragment, "getBounds"))
                || readIntResult(runtime, embeddedChange, "getMode") != TRANSIT_TO_FRONT
                || readIntResult(runtime, embeddedChange, "getFlags") != CROSS_TASK_EMBEDDED_OPENING_FLAGS
                || runtime.invokeSystemServerPlatformMethod(embeddedChange, "getLastParent") != null
                || readIntResult(runtime, info, "getRootCount") != 1) {
            return false;
        }
        Object fragmentToken = runtime.invokeSystemServerPlatformMethod(fragment, "getFragmentToken");
        Object openingToken = runtime.invokeSystemServerPlatformMethod(openingChange, "getContainer");
        Object animator = runtime.readSystemServerPlatformFieldOrNull(fragment, "mSurfaceAnimator");
        if (fragmentToken == null || openingToken == null || animator == null
                || !fragmentToken.equals(runtime.invokeSystemServerPlatformMethod(
                embeddedChange, "getTaskFragmentToken"))
                || !openingToken.equals(runtime.invokeSystemServerPlatformMethod(embeddedChange, "getParent"))
                || runtime.invokeSystemServerPlatformMethod(animator, "getAnimation") != null) {
            return false;
        }
        Object root = runtime.invokeSystemServerPlatformMethod(info, "getRoot", 0);
        Object rootLeash = runtime.invokeSystemServerPlatformMethod(root, "getLeash");
        Object offset = runtime.invokeSystemServerPlatformMethod(root, "getOffset");
        if (!(rootLeash instanceof SurfaceControl) || !((SurfaceControl) rootLeash).isValid()
                || readIntResult(runtime, root, "getDisplayId") != 0
                || !(offset instanceof Point) || ((Point) offset).x != 0 || ((Point) offset).y != 0) {
            return false;
        }
        // The third Change is a full-task child of the opening Task, not a third runner.
        // Keep its mode, parent, flags and native Shell handling intact.
        Object[] containers = {closingTask, openingTask, fragment};
        Object[] changes = {closingChange, openingChange, embeddedChange};
        IdentityHashMap<Object, Boolean> leashes = new IdentityHashMap<>();
        for (int index = 0; index < containers.length; index++) {
            Object container = containers[index];
            Object change = changes[index];
            Object token = runtime.invokeSystemServerPlatformMethod(
                    runtime.readSystemServerPlatformFieldOrNull(container, "mRemoteToken"),
                    "toWindowContainerToken");
            Object leash = runtime.readSystemServerPlatformTransitionChangeLeash(change);
            if (token == null || !token.equals(runtime.invokeSystemServerPlatformMethod(change, "getContainer"))
                    || !(leash instanceof SurfaceControl) || !((SurfaceControl) leash).isValid()
                    || leash != runtime.invokeSystemServerPlatformMethod(container, "getSurfaceControl")
                    || leash == rootLeash || leashes.put(leash, Boolean.TRUE) != null
                    || !bounds.equals(runtime.readSystemServerPlatformTransitionChangeStartAbsBounds(change))
                    || !bounds.equals(runtime.readSystemServerPlatformTransitionChangeEndAbsBounds(change))
                    || readIntResult(runtime, change, "getStartDisplayId") != 0
                    || readIntResult(runtime, change, "getEndDisplayId") != 0
                    || readIntResult(runtime, change, "getStartRotation") != 0
                    || readIntResult(runtime, change, "getEndRotation") != 0) {
                return false;
            }
        }
        return true;
    }

    private static String describeTargets(SystemServerHookRuntime runtime,
                                          List<?> targets) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < targets.size(); index++) {
            if (index > 0) {
                result.append(", ");
            }
            Object changeInfo = targets.get(index);
            Object container = runtime.readSystemServerPlatformFieldOrNull(
                    changeInfo, "mContainer");
            Object animator = runtime.readSystemServerPlatformFieldOrNull(
                    container, "mSurfaceAnimator");
            result.append("{index=").append(index)
                    .append(", container=")
                    .append(runtime.describeSystemServerPlatformObject(container))
                    .append(", taskId=")
                    .append(readIntField(runtime, container, "mTaskId", -1))
                    .append(", flags=0x")
                    .append(Integer.toHexString(readIntField(
                            runtime, changeInfo, "mFlags", -1)))
                    .append(", visible=")
                    .append(runtime.readSystemServerPlatformFieldOrNull(
                            changeInfo, "mVisible"))
                    .append(", requested=")
                    .append(invokeOrNull(runtime, container, "isVisibleRequested"))
                    .append(", lastLayer=")
                    .append(runtime.readSystemServerPlatformFieldOrNull(
                            container, "mLastLayer"))
                    .append(", relative=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformFieldOrNull(
                                    container, "mLastRelativeToLayer")))
                    .append(", surface=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformFieldOrNull(
                                    container, "mSurfaceControl")))
                    .append(", animation=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformFieldOrNull(
                                    animator, "mAnimation")))
                    .append(", leash=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformFieldOrNull(
                                    animator, "mLeash")))
                    .append('}');
        }
        return result.append(']').toString();
    }

    private static String describeChanges(SystemServerHookRuntime runtime,
                                          List<?> changes) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < changes.size(); index++) {
            if (index > 0) {
                result.append(", ");
            }
            Object change = changes.get(index);
            Object taskInfo = runtime.readSystemServerPlatformTransitionChangeTaskInfo(change);
            result.append("{index=").append(index)
                    .append(", taskId=")
                    .append(readIntField(runtime, taskInfo, "taskId", -1))
                    .append(", mode=")
                    .append(runtime.readSystemServerPlatformTransitionChangeMode(change))
                    .append(", flags=0x")
                    .append(Integer.toHexString(valueOrDefault(
                            runtime.readSystemServerPlatformTransitionChangeFlags(change), -1)))
                    .append(", leash=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformTransitionChangeLeash(change)))
                    .append(", parent=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformTransitionChangeParent(change)))
                    .append(", lastParent=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformTransitionChangeLastParent(change)))
                    .append(", startBounds=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformTransitionChangeStartAbsBounds(change)))
                    .append(", endBounds=")
                    .append(runtime.describeSystemServerPlatformObject(
                            runtime.readSystemServerPlatformTransitionChangeEndAbsBounds(change)))
                    .append('}');
        }
        return result.append(']').toString();
    }

    private static int readIntField(SystemServerHookRuntime runtime,
                                    Object target, String name, int fallback) {
        Object value = runtime.readSystemServerPlatformFieldOrNull(target, name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value.intValue();
    }

    private static Object invokeOrNull(SystemServerHookRuntime runtime,
                                       Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return runtime.invokeSystemServerPlatformMethod(
                    target, name, new Object[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findCalculateTransitionInfo(Class<?> transitionClass) {
        for (Method method : transitionClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("calculateTransitionInfo".equals(method.getName())
                    && parameters.length == 6
                    && parameters[0] == int.class
                    && parameters[1] == int.class
                    && "java.util.ArrayList".equals(parameters[2].getName())
                    && parameters[3] == SurfaceControl.Transaction.class
                    && parameters[4] == int.class
                    && TRANSITION_STUB.equals(parameters[5].getName())) {
                return method;
            }
        }
        return null;
    }
}
