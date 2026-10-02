package com.liuli.btchat.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * Hosts the page background and publishes the backdrop every frosted surface
 * samples.
 *
 * ```
 *   background (recorded)      the flat wash behind everything
 *   content                    lists, chat rows — drawn once, not recorded
 *   overlay                    top bar, tab bar, sheets — frosted
 * ```
 *
 * ## Why the content is not recorded
 *
 * A `LayerBackdrop` draws its subtree **twice**: once to the screen and once
 * into a graphics layer. Recording the scrolling content therefore doubles the
 * drawing work for every frame of a scroll, which is exactly what made the
 * first build stutter. It also only pays off when a bar is meant to be
 * see-through — and in this design the bars are opaque white, the way WeChat's
 * are.
 *
 * Set [refractContent] to `true` for a screen that genuinely wants bars bending
 * the content underneath; keep it off everywhere else.
 *
 * ## Why two backdrops
 *
 * A layer must never sample itself: a glass node inside a recorded subtree
 * would reference the layer while it is being recorded, and Skia's
 * `prepareNodeImpl` then recurses until the render thread overflows its stack.
 * `content` and `overlay` therefore sample different layers, and the background
 * subtree gets an empty one.
 */
@Composable
fun GlassStage(
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    /** `null` follows the user's effect setting; see [GlassMode]. */
    refractContent: Boolean? = null,
    background: @Composable () -> Unit = { LiuliBackground(animated = animated) },
    content: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val mode = LocalGlassMode.current
    // Blur is only visible if there is something behind the bar to blur, so the
    // content is recorded exactly when an effect is switched on.
    val refract = refractContent ?: (mode != GlassMode.None)
    val pageBackdrop = rememberLayerBackdrop()
    val sceneBackdrop = rememberLayerBackdrop()

    CompositionLocalProvider(LocalGlassBackdrop provides pageBackdrop) {
        Box(modifier.fillMaxSize()) {

            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(pageBackdrop)
            ) {
                // Glass inside the wallpaper itself would close the same loop
                // one level down, so that subtree gets no backdrop at all.
                CompositionLocalProvider(LocalGlassBackdrop provides emptyBackdrop()) {
                    background()
                }
            }

            if (refract) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(sceneBackdrop)
                ) { content() }
            } else {
                content()
            }

            CompositionLocalProvider(
                LocalGlassBackdrop provides if (refract) sceneBackdrop else pageBackdrop
            ) {
                overlay()
            }
        }
    }
}
