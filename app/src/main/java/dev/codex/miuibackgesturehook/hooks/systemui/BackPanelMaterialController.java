package dev.codex.miuibackgesturehook.hooks.systemui;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Draws Android's compositor-backed blur region inside the native AOSP BackPanel render pass. */
final class BackPanelMaterialController {
    interface Logger {
        void log(int priority, String message, Throwable throwable);
    }

    private static final int MATERIAL_RGB_LIGHT = 0x00FFFFFF;
    private static final int MATERIAL_RGB_DARK = 0x00263444;

    private final Logger logger;
    private final Map<View, MaterialState> states = new IdentityHashMap<>();
    private Method getViewRootImpl;
    private boolean apiFailureLogged;

    BackPanelMaterialController(Logger logger) {
        this.logger = logger;
    }

    void reconcile(View panel, boolean enabled, int blurRadiusPx,
            int transparencyPercent, boolean highlightEnabled) {
        if (panel == null) return;
        if (!enabled) {
            MaterialState old;
            synchronized (states) {
                old = states.remove(panel);
            }
            if (old != null) restore(old);
            return;
        }
        MaterialState existing;
        synchronized (states) {
            existing = states.get(panel);
        }
        if (existing != null) {
            existing.configure(blurRadiusPx, transparencyPercent, highlightEnabled);
            panel.invalidate();
            return;
        }
        try {
            MaterialState state = new MaterialState(panel);
            state.configure(blurRadiusPx, transparencyPercent, highlightEnabled);
            state.backgroundPaint.setColorFilter(state.transparentCarrierFilter);
            synchronized (states) {
                states.put(panel, state);
            }
            panel.invalidate();
            apiFailureLogged = false;
            logger.log(Log.INFO,
                    "Enabled AOSP ViewRoot background blur in native BackPanel draw pass", null);
        } catch (Throwable throwable) {
            logFailure("AOSP BackPanel blur initialization failed", throwable);
        }
    }

    /** Called immediately before the original BackPanel.onDraw(). */
    void drawBefore(View panel, Canvas canvas) {
        MaterialState state;
        synchronized (states) {
            state = states.get(panel);
        }
        if (state == null || canvas == null) return;
        try {
            state.ensureDrawable();
            state.draw(canvas);
        } catch (Throwable throwable) {
            synchronized (states) {
                states.remove(panel);
            }
            restore(state);
            logFailure("AOSP BackPanel blur draw failed; restored native carrier", throwable);
        }
    }

    void clear() {
        ArrayList<MaterialState> snapshot;
        synchronized (states) {
            snapshot = new ArrayList<>(states.values());
            states.clear();
        }
        for (MaterialState state : snapshot) restore(state);
    }

    private void restore(MaterialState state) {
        state.backgroundPaint.setColorFilter(state.originalColorFilter);
        if (state.blurDrawable != null) {
            state.blurDrawable.setVisible(false, false);
        }
        state.panel.invalidate();
    }

    private void logFailure(String message, Throwable throwable) {
        if (!apiFailureLogged) {
            apiFailureLogged = true;
            logger.log(Log.WARN, message, throwable);
        }
    }

    private Object viewRoot(View panel) throws Exception {
        if (getViewRootImpl == null) {
            getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRootImpl.setAccessible(true);
        }
        Object root = getViewRootImpl.invoke(panel);
        if (root == null) throw new IllegalStateException("BackPanel is not attached");
        return root;
    }

    private static Method requiredMethod(Class<?> type, String name, Class<?>... params)
            throws ReflectiveOperationException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + '#' + name);
    }

    private static Field requiredField(Class<?> type, String name)
            throws ReflectiveOperationException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + '#' + name);
    }

    private final class MaterialState {
        final View panel;
        final Paint backgroundPaint;
        final ColorFilter originalColorFilter;
        final ColorMatrixColorFilter transparentCarrierFilter;
        final Field backgroundWidth;
        final Field backgroundHeight;
        final Field backgroundAlpha;
        final Field backgroundEdgeCornerRadius;
        final Field backgroundFarCornerRadius;
        final Field scale;
        final Field scalePivotX;
        final Field horizontalTranslation;
        final Field verticalTranslation;
        final Field isLeftPanel;
        final Field animatedPosition;
        final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path highlightPath = new Path();
        final RectF highlightBounds = new RectF();
        final float[] highlightRadii = new float[8];
        Drawable blurDrawable;
        Method setBlurRadius;
        Method setCornerRadius;
        Method setColor;
        float lastEdgeRadius = -1f;
        float lastFarRadius = -1f;
        int blurRadiusPx;
        int transparencyPercent;
        boolean highlightEnabled;
        int appliedBlurRadiusPx = -1;
        int appliedMaterialColor = Integer.MIN_VALUE;

        MaterialState(View panel) throws ReflectiveOperationException {
            this.panel = panel;
            Class<?> type = panel.getClass();
            backgroundPaint = (Paint) requiredField(type, "arrowBackgroundPaint").get(panel);
            originalColorFilter = backgroundPaint.getColorFilter();
            ColorMatrix matrix = new ColorMatrix();
            matrix.setScale(1f, 1f, 1f, 0f);
            transparentCarrierFilter = new ColorMatrixColorFilter(matrix);
            backgroundWidth = requiredField(type, "backgroundWidth");
            backgroundHeight = requiredField(type, "backgroundHeight");
            backgroundAlpha = requiredField(type, "backgroundAlpha");
            backgroundEdgeCornerRadius = requiredField(type, "backgroundEdgeCornerRadius");
            backgroundFarCornerRadius = requiredField(type, "backgroundFarCornerRadius");
            scale = requiredField(type, "scale");
            scalePivotX = requiredField(type, "scalePivotX");
            horizontalTranslation = requiredField(type, "horizontalTranslation");
            verticalTranslation = requiredField(type, "verticalTranslation");
            isLeftPanel = requiredField(type, "isLeftPanel");
            animatedPosition = requiredField(backgroundWidth.get(panel).getClass(), "pos");
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(panel.getResources().getDisplayMetrics().density * 1.25f);
            highlightPaint.setColor(0xFFFFFFFF);
        }

        void configure(int blurRadiusPx, int transparencyPercent, boolean highlightEnabled) {
            this.blurRadiusPx = blurRadiusPx;
            this.transparencyPercent = transparencyPercent;
            this.highlightEnabled = highlightEnabled;
            appliedBlurRadiusPx = -1;
        }

        void ensureDrawable() throws Exception {
            if (blurDrawable == null) {
                Object root = viewRoot(panel);
                Object candidate = requiredMethod(root.getClass(),
                        "createBackgroundBlurDrawable").invoke(root);
                if (!(candidate instanceof Drawable)) {
                    throw new IllegalStateException("ViewRoot returned no blur drawable");
                }
                blurDrawable = (Drawable) candidate;
                setBlurRadius = requiredMethod(candidate.getClass(), "setBlurRadius", int.class);
                setCornerRadius = requiredMethod(candidate.getClass(), "setCornerRadius",
                        float.class, float.class, float.class, float.class);
                setColor = requiredMethod(candidate.getClass(), "setColor", int.class);
                blurDrawable.setVisible(true, false);
            }
            if (appliedBlurRadiusPx != blurRadiusPx) {
                setBlurRadius.invoke(blurDrawable, Integer.valueOf(blurRadiusPx));
                appliedBlurRadiusPx = blurRadiusPx;
            }
            boolean dark = (panel.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int tintAlpha = Math.round((100 - transparencyPercent) * 2.55f);
            int materialColor = (tintAlpha << 24)
                    | (dark ? MATERIAL_RGB_DARK : MATERIAL_RGB_LIGHT);
            if (materialColor != appliedMaterialColor) {
                setColor.invoke(blurDrawable, Integer.valueOf(materialColor));
                appliedMaterialColor = materialColor;
            }
        }

        void draw(Canvas canvas) throws Exception {
            float width = animated(backgroundWidth);
            float height = animated(backgroundHeight);
            float alpha = animated(backgroundAlpha);
            if (width <= 0f || height <= 0f || alpha <= 0.01f) return;
            float edgeRadius = animated(backgroundEdgeCornerRadius);
            float farRadius = animated(backgroundFarCornerRadius);
            if (edgeRadius != lastEdgeRadius || farRadius != lastFarRadius) {
                lastEdgeRadius = edgeRadius;
                lastFarRadius = farRadius;
                setCornerRadius.invoke(blurDrawable,
                        Float.valueOf(edgeRadius), Float.valueOf(farRadius),
                        Float.valueOf(farRadius), Float.valueOf(edgeRadius));
            }
            blurDrawable.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
            int halfHeight = Math.max(1, Math.round(height * 0.5f));
            blurDrawable.setBounds(0, -halfHeight, Math.max(1, Math.round(width)), halfHeight);
            int save = canvas.save();
            try {
                if (!isLeftPanel.getBoolean(panel)) {
                    canvas.scale(-1f, 1f, panel.getWidth() * 0.5f, 0f);
                }
                canvas.translate(animated(horizontalTranslation),
                        panel.getHeight() * 0.5f + animated(verticalTranslation));
                float panelScale = animated(scale);
                canvas.scale(panelScale, panelScale, animated(scalePivotX), 0f);
                blurDrawable.draw(canvas);
                if (highlightEnabled) {
                    drawHighlight(canvas, width, height, edgeRadius, farRadius, alpha);
                }
            } finally {
                canvas.restoreToCount(save);
            }
        }

        void drawHighlight(Canvas canvas, float width, float height, float edgeRadius,
                float farRadius, float alpha) {
            float halfStroke = highlightPaint.getStrokeWidth() * 0.5f;
            highlightBounds.set(halfStroke, -height * 0.5f + halfStroke,
                    width - halfStroke, height * 0.5f - halfStroke);
            highlightRadii[0] = edgeRadius;
            highlightRadii[1] = edgeRadius;
            highlightRadii[2] = farRadius;
            highlightRadii[3] = farRadius;
            highlightRadii[4] = farRadius;
            highlightRadii[5] = farRadius;
            highlightRadii[6] = edgeRadius;
            highlightRadii[7] = edgeRadius;
            highlightPath.rewind();
            highlightPath.addRoundRect(highlightBounds, highlightRadii, Path.Direction.CW);
            highlightPaint.setAlpha(Math.max(0, Math.min(96, Math.round(alpha * 96f))));
            canvas.drawPath(highlightPath, highlightPaint);
        }

        float animated(Field field) throws IllegalAccessException {
            return animatedPosition.getFloat(field.get(panel));
        }
    }
}
