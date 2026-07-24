package dev.codex.miuibackgesturehook.hooks.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;

/**
 * Visual-only port of MiuiHome's {@code GestureBackArrowView} water-drop back indicator,
 * rendered inside SystemUI while the hidden native {@code BackPanelController} keeps
 * owning gesture state, commit thresholds, and haptics.
 *
 * <p>The two bitmaps come from the live {@code com.miui.home} package resources so the
 * appearance stays pixel-identical to the stock HyperOS indicator. All entry points must
 * run on the main Looper except {@link #detach()}, which reposts itself.
 */
final class MiuiStyleBackArrowOverlay {

    interface Logger {
        void log(int priority, String message, Throwable throwable);
    }

    /**
     * Fired when the arrow finishes its 100ms fade-in, matching Xiaomi's
     * ready-back haptic timing in GestureBackArrowView.
     */
    interface HapticListener {
        void onArrowReady();
    }

    static final int EDGE_LEFT = 0;
    static final int EDGE_RIGHT = 1;

    private static final String MIUI_HOME_PACKAGE = "com.miui.home";
    private static final String RESOURCE_BACKGROUND = "gesture_back_background";
    private static final String RESOURCE_ARROW = "gesture_back_arrow";
    // Maximum laterFriction(x, 0.8, 2.0, 0.5) value is 0.8 + (2.0 / 3.0) * 0.5.
    private static final float MAX_BACKGROUND_SCALE = 1.14f;
    // WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL (@hide),
    // FIRST_SYSTEM_WINDOW + 24 — the same window type the native BackPanel uses.
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;

    private final Context context;
    private final Logger logger;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArrowView view;
    private WindowManager.LayoutParams layoutParams;
    private int attachedEdge = -1;
    private boolean windowAdded;

    private Bitmap leftBackground;
    private Bitmap rightBackground;
    private Bitmap arrow;
    private int loadedDensityDpi = -1;
    private int loadedUiNightMode = -1;
    private boolean resourceFailureLogged;
    private HapticListener hapticListener;
    private boolean hapticsEnabledForGesture;

    MiuiStyleBackArrowOverlay(Context context, Logger logger) {
        this.context = context;
        this.logger = logger;
    }

    void setHapticListener(HapticListener listener) {
        hapticListener = listener;
    }

    /**
     * Decided per gesture at ACTION_DOWN; off keeps this overlay fully silent.
     */
    void setHapticsForGesture(boolean enabled) {
        hapticsEnabledForGesture = enabled;
    }

    /**
     * Whether the ready-back haptic already played for the current arrow show.
     */
    boolean isArrowFeedbackDone() {
        ArrowView current = view;
        return current == null || current.arrowFeedbackDone;
    }

    /**
     * Blocks a late fade-in completion from vibrating after the gesture ended.
     */
    void markArrowFeedbackDone() {
        ArrowView current = view;
        if (current != null) {
            current.arrowFeedbackDone = true;
        }
    }

    private void dispatchArrowReady() {
        HapticListener listener = hapticListener;
        if (hapticsEnabledForGesture && listener != null) {
            listener.onArrowReady();
        }
    }

    /**
     * Loads MiuiHome resources and (re)attaches the overlay window for the given edge.
     * Returns false when the HyperOS skin cannot be shown; callers must then leave the
     * native AOSP panel visible.
     */
    boolean prepare(int edge) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        if (!loadResources()) {
            return false;
        }
        try {
            ensureWindow(edge);
            return view != null && windowAdded;
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "Failed to attach HyperOS-style back indicator window",
                    throwable);
            return false;
        }
    }

    void onGestureStart(float rawY, int edge) {
        ArrowView current = view;
        if (current == null) {
            return;
        }
        int[] location = new int[2];
        try {
            current.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            location[1] = 0;
        }
        current.onSwipeStart(rawY - location[1], edge == EDGE_LEFT
                ? ArrowView.POSITION_LEFT : ArrowView.POSITION_RIGHT);
    }

    void onGestureProgress(float offsetPx, boolean arrowLit) {
        ArrowView current = view;
        if (current != null) {
            current.onSwipeProgress(offsetPx, arrowLit);
        }
    }

    /**
     * Pass a negative offset to retract from the last drawn scale (cancellation).
     */
    void onGestureEnd(float offsetPx) {
        ArrowView current = view;
        if (current != null) {
            current.onSwipeStop(offsetPx);
        }
    }

    /**
     * Safe from any thread; used by driver detach and hot reload teardown.
     */
    void detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::detach);
            return;
        }
        ArrowView current = view;
        view = null;
        layoutParams = null;
        attachedEdge = -1;
        boolean added = windowAdded;
        windowAdded = false;
        if (current != null) {
            current.cancelAnimations();
            if (added) {
                try {
                    WindowManager windowManager =
                            context.getSystemService(WindowManager.class);
                    windowManager.removeViewImmediate(current);
                } catch (Throwable throwable) {
                    logger.log(Log.WARN,
                            "Failed to remove HyperOS-style back indicator window",
                            throwable);
                }
            }
        }
    }

    private void ensureWindow(int edge) {
        if (view != null && windowAdded && attachedEdge == edge) {
            return;
        }
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        if (view == null || !windowAdded) {
            detachDanglingView(windowManager);
            ArrowView created = new ArrowView(context, this);
            WindowManager.LayoutParams params = buildLayoutParams(edge);
            windowManager.addView(created, params);
            view = created;
            layoutParams = params;
            windowAdded = true;
            attachedEdge = edge;
            logger.log(Log.INFO, "Attached HyperOS-style back indicator window"
                    + ", edge=" + edge
                    + ", width=" + params.width, null);
            return;
        }
        layoutParams.gravity = gravityForEdge(edge);
        windowManager.updateViewLayout(view, layoutParams);
        attachedEdge = edge;
    }

    private void detachDanglingView(WindowManager windowManager) {
        ArrowView dangling = view;
        view = null;
        if (dangling != null && windowAdded) {
            windowAdded = false;
            try {
                windowManager.removeViewImmediate(dangling);
            } catch (Throwable ignored) {
            }
        }
    }

    private WindowManager.LayoutParams buildLayoutParams(int edge) {
        int width = Math.max(1, Math.round(
                leftBackground.getWidth() * MAX_BACKGROUND_SCALE) + 1);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.setTitle("MiuiBackGestureHookIndicator");
        params.gravity = gravityForEdge(edge);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return params;
    }

    @SuppressLint("RtlHardcoded")
    private static int gravityForEdge(int edge) {
        return Gravity.TOP | (edge == EDGE_LEFT ? Gravity.LEFT : Gravity.RIGHT);
    }

    @SuppressLint("DiscouragedApi")
    private boolean loadResources() {
        Configuration configuration = context.getResources().getConfiguration();
        int densityDpi = configuration.densityDpi;
        int uiNightMode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (leftBackground != null && arrow != null
                && densityDpi == loadedDensityDpi && uiNightMode == loadedUiNightMode) {
            return true;
        }
        try {
            Context homeContext = context.createPackageContext(MIUI_HOME_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);
            Resources homeResources = homeContext.getResources();
            int backgroundId = homeResources.getIdentifier(
                    RESOURCE_BACKGROUND, "drawable", MIUI_HOME_PACKAGE);
            int arrowId = homeResources.getIdentifier(
                    RESOURCE_ARROW, "drawable", MIUI_HOME_PACKAGE);
            if (backgroundId == 0 || arrowId == 0) {
                throw new Resources.NotFoundException(
                        "backgroundId=" + backgroundId + ", arrowId=" + arrowId);
            }
            Bitmap loadedBackground =
                    BitmapFactory.decodeResource(homeResources, backgroundId);
            Bitmap loadedArrow = BitmapFactory.decodeResource(homeResources, arrowId);
            if (loadedBackground == null || loadedArrow == null) {
                throw new IllegalStateException("decoded background="
                        + loadedBackground + ", arrow=" + loadedArrow);
            }
            Matrix rotate = new Matrix();
            rotate.postRotate(180.0f);
            leftBackground = loadedBackground;
            rightBackground = Bitmap.createBitmap(loadedBackground, 0, 0,
                    loadedBackground.getWidth(), loadedBackground.getHeight(),
                    rotate, true);
            arrow = loadedArrow;
            loadedDensityDpi = densityDpi;
            loadedUiNightMode = uiNightMode;
            resourceFailureLogged = false;
            logger.log(Log.INFO, "Loaded HyperOS back indicator resources from MiuiHome"
                    + ", background=" + loadedBackground.getWidth()
                    + "x" + loadedBackground.getHeight()
                    + ", arrow=" + loadedArrow.getWidth()
                    + "x" + loadedArrow.getHeight()
                    + ", densityDpi=" + densityDpi, null);
            return true;
        } catch (Throwable throwable) {
            if (!resourceFailureLogged) {
                resourceFailureLogged = true;
                logger.log(Log.WARN, "HyperOS back indicator resources unavailable"
                        + ", policy=keepNativeAospPanel", throwable);
            }
            return false;
        }
    }

    /**
     * Drawing port of {@code com.miui.home.recents.GestureBackArrowView}. The background
     * water-drop scales with {@code laterFriction} damping and the arrow fades in/out with
     * the original 100ms/50ms cubic-ease animations. Haptics stay with the hidden native
     * BackPanelController, so this view never vibrates.
     */
    private static final class ArrowView extends View {
        static final int POSITION_LEFT = 0;
        static final int POSITION_RIGHT = 1;

        private static final Interpolator CUBIC_EASE_OUT_INTERPOLATOR =
                new DecelerateInterpolator(1.5f);
        private static final Interpolator QUAD_EASE_OUT_INTERPOLATOR =
                new DecelerateInterpolator();

        private final MiuiStyleBackArrowOverlay overlay;
        private final Paint backgroundPaint;
        private final Paint arrowPaint;
        private final Rect backgroundDstRect = new Rect();
        private final Rect arrowDstRect = new Rect();

        private ValueAnimator arrowAnimator;
        private ValueAnimator finishAnimator;
        private int position = POSITION_LEFT;
        private float backgroundScale;
        private float startY;
        private float expectBackHeight;
        private boolean arrowLit;
        private boolean arrowShown;
        private boolean iconNeedDraw;
        private int currentArrowAlpha;
        boolean arrowFeedbackDone;

        ArrowView(Context context, MiuiStyleBackArrowOverlay overlay) {
            super(context);
            this.overlay = overlay;
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setFilterBitmap(true);
            backgroundPaint.setDither(true);
            arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            arrowPaint.setFilterBitmap(true);
            arrowPaint.setDither(true);
            arrowPaint.setAlpha(0);
        }

        // Mirrors BackGestureUtils.convertOffset(): linear for the first 80% of
        // offset/360 and cubic-polynomial friction beyond it, returning the 0..~1.13
        // background scale directly (Xiaomi divides its 0..20 result by 20).
        static float convertOffsetToScale(float offset) {
            if (offset < 0.0f) {
                return 0.0f;
            }
            return laterFriction(offset / 360.0f);
        }

        private static float laterFriction(float value) {
            if (value < (float) 0.8) {
                return value;
            }
            return (float) 0.8
                    + afterFrictionValue(value - (float) 0.8)
                    * (float) 0.5;
        }

        private static float afterFrictionValue(float value) {
            int sign = value >= 0.0f ? 1 : -1;
            float normalized = (float) Math.min(Math.abs(value) / (float) 2.0, 1.0d);
            float squared = normalized * normalized;
            return sign * ((squared * normalized / 3) - squared + normalized) * (float) 2.0;
        }

        void onSwipeStart(float localY, int newPosition) {
            cancelFinishAnimation();
            position = newPosition;
            Bitmap background = currentBackground();
            expectBackHeight = background == null ? 0.0f : background.getHeight();
            // Matches Xiaomi's no-explicit-height branch in setStartLocation().
            startY = localY - 20.0f;
            arrowPaint.setAlpha(0);
            currentArrowAlpha = 0;
            arrowShown = false;
            iconNeedDraw = false;
            arrowLit = false;
            backgroundScale = 0.0f;
            invalidate();
        }

        void onSwipeProgress(float offset, boolean lit) {
            arrowLit = lit;
            backgroundScale = convertOffsetToScale(offset);
            invalidate();
        }

        void onSwipeStop(float releaseOffset) {
            arrowLit = false;
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            float startScale = releaseOffset >= 0.0f
                    ? convertOffsetToScale(releaseOffset) : backgroundScale;
            backgroundScale = startScale;
            ValueAnimator animator = ValueAnimator.ofFloat(startScale, 0.0f);
            animator.setDuration(100L);
            animator.setInterpolator(QUAD_EASE_OUT_INTERPOLATOR);
            animator.addUpdateListener(animation -> {
                backgroundScale = (Float) animation.getAnimatedValue();
                long playTime = animation.getCurrentPlayTime();
                if (playTime > 0 && playTime < 50) {
                    iconNeedDraw = false;
                    arrowShown = false;
                }
                invalidate();
            });
            finishAnimator = animator;
            animator.start();
        }

        void cancelAnimations() {
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            cancelFinishAnimation();
        }

        private void cancelFinishAnimation() {
            if (finishAnimator != null) {
                finishAnimator.end();
                finishAnimator = null;
            }
        }

        private Bitmap currentBackground() {
            return position == POSITION_LEFT
                    ? overlay.leftBackground : overlay.rightBackground;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            Bitmap background = currentBackground();
            Bitmap arrowBitmap = overlay.arrow;
            if (background == null || arrowBitmap == null) {
                return;
            }
            float scaledWidth = background.getWidth() * backgroundScale;
            int arrowWidth = arrowBitmap.getWidth();
            int backgroundLeft;
            int backgroundRight;
            int arrowLeft;
            int arrowRight;
            if (position == POSITION_LEFT) {
                backgroundLeft = 0;
                backgroundRight = (int) scaledWidth;
                arrowLeft = (int) ((scaledWidth - arrowWidth) / 2.0f);
                arrowRight = (int) ((scaledWidth + arrowWidth) / 2.0f);
            } else {
                int width = getWidth();
                backgroundLeft = width - (int) scaledWidth;
                backgroundRight = width;
                arrowLeft = width - (int) ((scaledWidth + arrowWidth) / 2.0f);
                arrowRight = width - (int) ((scaledWidth - arrowWidth) / 2.0f);
            }
            backgroundDstRect.set(backgroundLeft,
                    (int) (startY - (expectBackHeight / 2.0f)),
                    backgroundRight,
                    (int) (startY + (expectBackHeight / 2.0f)));
            canvas.drawBitmap(background, null, backgroundDstRect, backgroundPaint);
            if (arrowLit) {
                if (!arrowShown) {
                    iconNeedDraw = true;
                    startArrowAnimating(true, 100);
                    arrowShown = true;
                }
            } else if (arrowShown) {
                startArrowAnimating(false, 50);
                arrowShown = false;
            }
            if (iconNeedDraw && backgroundScale > 0.1d && arrowLit) {
                int arrowHeight = arrowBitmap.getHeight();
                arrowDstRect.set(arrowLeft,
                        (int) (startY - (arrowHeight / 2.0f)),
                        arrowRight,
                        (int) (startY + (arrowHeight / 2.0f)));
                canvas.drawBitmap(arrowBitmap, null, arrowDstRect, arrowPaint);
            }
        }

        private void startArrowAnimating(boolean show, int duration) {
            if (arrowAnimator != null) {
                arrowAnimator.cancel();
            }
            arrowFeedbackDone = false;
            ValueAnimator animator = ValueAnimator.ofInt(
                    currentArrowAlpha, show ? 255 : 0);
            animator.setDuration(duration);
            animator.setInterpolator(CUBIC_EASE_OUT_INTERPOLATOR);
            animator.addUpdateListener(animation -> {
                int alpha = (Integer) animation.getAnimatedValue();
                arrowPaint.setAlpha(alpha);
                invalidate();
                if (alpha == 0 && !show) {
                    iconNeedDraw = false;
                }
                currentArrowAlpha = alpha;
            });
            if (show) {
                // Xiaomi's AnimationSuccessListener: the ready-back haptic fires only
                // when the fade-in completes uncancelled and has not fired yet.
                animator.addListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!cancelled && !arrowFeedbackDone) {
                            arrowFeedbackDone = true;
                            overlay.dispatchArrowReady();
                        }
                    }
                });
            }
            arrowAnimator = animator;
            animator.start();
        }
    }
}
