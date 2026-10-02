package com.liuli.btchat.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The page behind everything.
 *
 * A single flat fill. The previous version painted a handful of large soft
 * radial washes, which meant a full-screen shader cost on every frame the
 * background was re-recorded — and on a white interface the washes only ever
 * read as a smudge. WeChat's list background is one flat grey; so is this.
 */
@Composable
fun LiuliBackground(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") animated: Boolean = false
) {
    // The palette is composable now, so read it here rather than inside the
    // modifier chain.
    val bg = LiuliColors.Bg
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .background(bg)
    )
}

/** Kept for callers that want a slightly warmer page (chat detail). */
val ChatPageColor: Color
    @Composable get() = LiuliColors.Bg
