package com.liuli.btchat.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * The single place that turns a [GlassLevel] into pixels.
 *
 * Every panel, bubble, bar and button in the app is this modifier plus content,
 * which is what keeps the material consistent no matter who wrote the screen.
 */

/**
 * Draws refractive glass behind and around the content it is applied to.
 *
 * @param shape      silhouette; a `CornerBasedShape` is required for the lens
 *                   refraction to resolve its corner radii.
 * @param level      how much material this surface carries.
 * @param tint       flat colour laid over the material (accent chips, bubbles).
 * @param clipToShape forces an explicit clip layer. The backdrop library clips
 *                   the content it draws but extra safety costs one layer, so
 *                   it is opt-in.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(Radii.card),
    level: GlassLevel = GlassLevel.Regular,
    tint: Color = Color.Transparent,
    clipToShape: Boolean = false,
    specularEdge: Boolean = true
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val spec = glassSpec(level)
    val mode = LocalGlassMode.current

    // "全部关闭": no backdrop, no render effect, no graphics layer — an **opaque**
    // panel. A translucent one would let a list show through a menu, which looks
    // like a rendering fault; with effects off the surface has to be solid.
    if (mode == GlassMode.None) {
        val base = LiuliColors.Surface
        val fill = if (tint.alpha > 0f) lerp(base, tint, tint.alpha) else base
        return this
            .background(fill, shape)
            .border(1.dp, spec.borderBottom, shape)
    }

    var m = this
    if (clipToShape) m = m.clip(shape)

    m = m.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // `TileMode.Decal` is not a taste choice. The backdrop library only
            // widens the blur's sampling window when the tile mode is *not*
            // `Clamp`, and without that padding the blur has nothing to sample
            // past the shape's own edge — which is exactly why the material
            // faded out towards the top and bottom of a surface. Decal grants
            // the padding and, unlike Clamp, does not smear edge pixels back in.
            blur(spec.blur.toPx(), TileMode.Decal)
            if (mode == GlassMode.Liquid &&
                spec.refraction > 0.dp &&
                spec.refractionAmount > 0.dp
            ) {
                lens(
                    refractionHeight = spec.refraction.toPx(),
                    refractionAmount = spec.refractionAmount.toPx(),
                    chromaticAberration = spec.chromatic
                )
            }
            vibrancy()
        },
        highlight = {
            if (specularEdge) Highlight(width = 0.8.dp, alpha = spec.highlightAlpha) else null
        },
        shadow = {
            Shadow(
                radius = spec.shadowRadius,
                offset = DpOffset(0.dp, spec.shadowOffsetY),
                color = Color.Black,
                alpha = spec.shadowAlpha
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 6.dp,
                offset = DpOffset(0.dp, 1.dp),
                color = Color.Black,
                alpha = 0.05f
            )
        },
        onDrawSurface = {
            if (spec.tintTop.alpha > 0f || tint.alpha > 0f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                clipPath(Path().apply { addOutline(outline) }) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(spec.tintTop, spec.tintBottom),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                    if (tint.alpha > 0f) drawRect(tint)
                }
            }
        },
        onDrawFront = {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            if (specularEdge && spec.specular > 0f) {
                clipPath(path) {
                    // A thin top highlight: on a white page this is the cue that
                    // reads as "glass" rather than "a white box".
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = spec.specular * 0.85f),
                            0.22f to Color.Transparent
                        )
                    )
                }
            }
            // A hairline outline, darker at the bottom, gives the surface an edge
            // on a near-white background where a white rim would vanish.
            drawPath(path, color = spec.borderBottom, style = Stroke(width = 1.2f))
        }
    )
    return m
}

/** Scale + brightness response used by every tappable surface. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.965f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 900f),
        label = "press"
    )
    return this.scale(scale)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.card),
    level: GlassLevel = GlassLevel.Regular,
    tint: Color = Color.Transparent,
    contentPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier
            .glassSurface(shape = shape, level = level, tint = tint)
            .then(if (contentPadding > 0.dp) Modifier else Modifier),
        content = content
    )
}
