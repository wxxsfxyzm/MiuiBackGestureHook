// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keep the spatial blur visible even when a short page has no content scroll offset. */
@Composable
fun SettingsProgressiveAppBarSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bandHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        TopAppBarDefaults.CollapsedHeight + 32.dp
    val colors = BlurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.3f))),
    )
    Box(modifier = modifier) {
        // This child draws beyond the compact bar without changing the bar's measured height.
        Box(
            modifier = Modifier
                .matchParentSize()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .height(bandHeight)
                .progressiveTextureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                    gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                    colors = colors,
                ),
        )
        content()
    }
}
