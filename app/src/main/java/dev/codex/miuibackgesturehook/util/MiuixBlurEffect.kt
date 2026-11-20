// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberMiuixBlurBackdrop(): LayerBackdrop {
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun Modifier.miuixBlurEffect(backdrop: LayerBackdrop): Modifier {
    val blendColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)
    return then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = 25f,
            colors = BlurColors(
                blendColors = listOf(BlendColorEntry(color = blendColor)),
            ),
        ),
    )
}

// Adapted with permission from InstallerX Revived's InstallerMiuixProgressiveAppBarSurface.
// Copyright (C) 2026 InstallerX Revived contributors.
/**
 * Persistent progressive band behind a glass top bar. Unlike the bar's own band, it does not
 * fade out when the list cannot scroll, so overscrolled content stays blurred under the bar.
 * Bar buttons should sample the rendered backdrop passed to [content].
 */
@Composable
fun MiuixProgressiveAppBarSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable (renderedBackdrop: LayerBackdrop) -> Unit,
) {
    val renderedBackdrop = rememberLayerBackdrop()
    val topInset = WindowInsets.statusBars.getTop(LocalDensity.current)
    val blurColors = BlurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.3f))),
    )
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val bandHeight = (
                        topInset + TopAppBarDefaults.CollapsedHeight.toPx() + 20.dp.toPx()
                        ) * 1.2f
                    val placeable = measurable.measure(
                        constraints.copy(
                            minHeight = bandHeight.toInt(),
                            maxHeight = bandHeight.toInt(),
                        ),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeRelative(0, 0)
                    }
                }
                .layerBackdrop(renderedBackdrop)
                .progressiveTextureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                    blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                    colors = blurColors,
                ),
        )
        content(renderedBackdrop)
    }
}
