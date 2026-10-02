package com.liuli.btchat.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.avatarColors

/**
 * Identity artwork.
 *
 * The glass sits *under* the colour, not over it. A glass surface paints an
 * opaque blurred copy of the wallpaper inside its shape, so a glass layer drawn
 * as a sibling on top would erase the gradient and the initial completely — the
 * avatar would read as an empty bubble. Nesting the coloured core as the glass
 * node's child draws it after the backdrop and keeps both.
 */
@Composable
fun GlassAvatar(
    name: String,
    seed: Int,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    shape: Shape = RoundedCornerShape(size * 0.18f),
    online: Boolean = false,
    glassSheen: Boolean = true,
    showInitial: Boolean = true
) {
    val palette = avatarColors(seed)
    val c1 = Color(palette.first.toInt())
    val c2 = Color(palette.second.toInt())
    val initial = name.trim().firstOrNull()?.toString().orEmpty()
    val fg = if (c1.luminance() > 0.62f) Color(0xFF171A2B) else Color.White
    val coreAlpha = if (glassSheen) 0.92f else 1f

    Box(
        modifier
            .size(size)
            .then(
                if (glassSheen) {
                    Modifier.glassSurface(
                        shape = shape,
                        level = GlassLevel.Thin,
                        tint = Color.White.copy(alpha = 0.04f)
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(c1.copy(alpha = coreAlpha), c2.copy(alpha = coreAlpha)),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showInitial && initial.isNotEmpty()) {
                Text(
                    initial,
                    color = fg,
                    fontSize = (size.value * 0.40f).sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(size * 0.30f)
                    .background(Color(0xFF0B1020), CircleShape)
                    .padding(size * 0.05f)
                    .background(LiuliColors.Success, CircleShape)
            )
        }
    }
}

/**
 * A 2x2 mosaic for groups — the same trick WeChat uses, so a group is
 * recognisable at a glance without anyone setting a picture.
 */
@Composable
fun GroupAvatar(
    seeds: List<Int>,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    shape: Shape = RoundedCornerShape(size * 0.32f)
) {
    val four = (seeds + listOf(17, 93, 141, 268)).take(4)
    Box(
        modifier
            .size(size)
            .glassSurface(
                shape = shape,
                level = GlassLevel.Thin,
                tint = Color.White.copy(alpha = 0.04f)
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0xFF161A2E))
        ) {
            Row(Modifier.weight(1f).fillMaxSize()) {
                MosaicCell(four[0], Modifier.weight(1f).fillMaxHeight())
                MosaicCell(four[1], Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxSize()) {
                MosaicCell(four[2], Modifier.weight(1f).fillMaxHeight())
                MosaicCell(four[3], Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun MosaicCell(seed: Int, modifier: Modifier) {
    val palette = avatarColors(seed)
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(Color(palette.first.toInt()), Color(palette.second.toInt()))
            )
        )
    )
}

/** Overlapping avatars used in group headers and member summaries. */
@Composable
fun AvatarStack(
    seeds: List<Int>,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    max: Int = 5
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-(size.value * 0.34f).dp)) {
        seeds.take(max).forEach { seed ->
            GlassAvatar(
                name = "",
                seed = seed,
                size = size,
                showInitial = false,
                glassSheen = false
            )
        }
    }
}
