package com.liuli.btchat.ui.glass

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.liuli.btchat.core.Svc

/**
 * Design tokens.
 *
 * The app has two palettes, light and dark, and they are the *same names*: a
 * screen writes `LiuliColors.TextPrimary` and never learns which theme is
 * active. That works because every property of [LiuliColors] is a composable
 * getter reading [LocalLiuliPalette], so flipping the whole app to dark is one
 * `CompositionLocalProvider` in [LiuliTheme] rather than hundreds of edits.
 *
 * Glass is chrome: the top bar, the tab bar, the chat input bar and modal
 * panels. List rows, bubbles and cards are flat, which is what keeps a long
 * list scrolling at frame rate.
 */
@Immutable
data class LiuliPalette(
    // surfaces
    val Bg: Color,
    val Surface: Color,
    val SurfaceAlt: Color,
    val SurfaceSunken: Color,
    val Separator: Color,
    val Scrim: Color,
    // text
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val TextOnAccent: Color,
    // accent
    val Accent: Color,
    val AccentSoft: Color,
    val AccentDeep: Color,
    // secondary hues (avatars, tags)
    val Violet: Color,
    val Indigo: Color,
    val Magenta: Color,
    val Cyan: Color,
    val Teal: Color,
    val Amber: Color,
    val Lime: Color,
    // bubbles
    val BubbleMineTop: Color,
    val BubbleMineBottom: Color,
    val BubbleMineText: Color,
    val BubbleTheirs: Color,
    val BubbleTheirsText: Color,
    // status
    val Success: Color,
    val Danger: Color,
    val Warn: Color,
    val Link: Color
) {
    /** Gradient for primary buttons and the brand mark. */
    val brandGradient: List<Color> get() = listOf(Accent, AccentDeep)
}

/** WeChat's light page: near-white surfaces, hairline separators, one green. */
val LightPalette = LiuliPalette(
    Bg = Color(0xFFF2F3F5),
    Surface = Color(0xFFFFFFFF),
    SurfaceAlt = Color(0xFFF7F8FA),
    SurfaceSunken = Color(0xFFEDEEF1),
    Separator = Color(0xFFE8EAED),
    Scrim = Color(0x40000000),
    TextPrimary = Color(0xFF14161A),
    TextSecondary = Color(0xFF6B7280),
    TextTertiary = Color(0xFF9CA3AF),
    TextOnAccent = Color(0xFFFFFFFF),
    Accent = Color(0xFF07C160),
    AccentSoft = Color(0xFF3ED27E),
    AccentDeep = Color(0xFF059B4C),
    Violet = Color(0xFF7A5AF8),
    Indigo = Color(0xFF4F63E0),
    Magenta = Color(0xFFE9569B),
    Cyan = Color(0xFF12B5C9),
    Teal = Color(0xFF0EA5A0),
    Amber = Color(0xFFF5A524),
    Lime = Color(0xFF7BC62D),
    BubbleMineTop = Color(0xFF9BEE6D),
    BubbleMineBottom = Color(0xFF8AE05F),
    BubbleMineText = Color(0xFF10240A),
    BubbleTheirs = Color(0xFFFFFFFF),
    BubbleTheirsText = Color(0xFF14161A),
    Success = Color(0xFF07C160),
    Danger = Color(0xFFFA5151),
    Warn = Color(0xFFF5A524),
    Link = Color(0xFF576B95)
)

/**
 * WeChat's dark page: a near-black page, slightly lifted surfaces, and the same
 * green. Bubbles switch to a deep olive for outgoing and a lifted grey for
 * incoming, both carrying light text, because the light palette's lime bubble
 * would glow against a dark page.
 */
val DarkPalette = LiuliPalette(
    Bg = Color(0xFF111214),
    Surface = Color(0xFF1C1D21),
    SurfaceAlt = Color(0xFF232428),
    SurfaceSunken = Color(0xFF2A2C31),
    Separator = Color(0xFF2A2C31),
    Scrim = Color(0x66000000),
    TextPrimary = Color(0xFFECEDEF),
    TextSecondary = Color(0xFF9BA1AA),
    TextTertiary = Color(0xFF6B7178),
    TextOnAccent = Color(0xFFFFFFFF),
    Accent = Color(0xFF07C160),
    AccentSoft = Color(0xFF3ED27E),
    AccentDeep = Color(0xFF059B4C),
    Violet = Color(0xFF8E74FF),
    Indigo = Color(0xFF6B7BF0),
    Magenta = Color(0xFFF06BA8),
    Cyan = Color(0xFF2FC6D8),
    Teal = Color(0xFF1FBDB6),
    Amber = Color(0xFFFFB94D),
    Lime = Color(0xFF93D455),
    BubbleMineTop = Color(0xFF3E5C33),
    BubbleMineBottom = Color(0xFF35502B),
    BubbleMineText = Color(0xFFECEDEF),
    BubbleTheirs = Color(0xFF25262B),
    BubbleTheirsText = Color(0xFFECEDEF),
    Success = Color(0xFF07C160),
    Danger = Color(0xFFFA5151),
    Warn = Color(0xFFF5A524),
    Link = Color(0xFF8AA4D6)
)

/** The palette every `LiuliColors` read resolves against. */
val LocalLiuliPalette = staticCompositionLocalOf { LightPalette }

/** True while the dark palette is active; used by the glass material. */
val LocalLiuliDark = staticCompositionLocalOf { false }

/**
 * Theme-aware colour tokens.
 *
 * Every member is a composable getter, so the call sites written against the
 * old constant object — `LiuliColors.TextPrimary`, `LiuliColors.Surface` — keep
 * compiling and follow the theme automatically. Non-composable call sites (a
 * top-level `val`, a `DrawScope` lambda) have to be turned into composables or
 * given their colour as a parameter; the handful in this project are annotated
 * accordingly.
 */
object LiuliColors {

    // ------------------------------------------------------------- surfaces
    /** Page background. */
    val Bg: Color @Composable get() = LocalLiuliPalette.current.Bg
    /** Bars, cards, rows. */
    val Surface: Color @Composable get() = LocalLiuliPalette.current.Surface
    val SurfaceAlt: Color @Composable get() = LocalLiuliPalette.current.SurfaceAlt
    val SurfaceSunken: Color @Composable get() = LocalLiuliPalette.current.SurfaceSunken
    val Separator: Color @Composable get() = LocalLiuliPalette.current.Separator
    val Scrim: Color @Composable get() = LocalLiuliPalette.current.Scrim

    // ----------------------------------------------------------------- text
    val TextPrimary: Color @Composable get() = LocalLiuliPalette.current.TextPrimary
    val TextSecondary: Color @Composable get() = LocalLiuliPalette.current.TextSecondary
    val TextTertiary: Color @Composable get() = LocalLiuliPalette.current.TextTertiary
    val TextOnAccent: Color @Composable get() = LocalLiuliPalette.current.TextOnAccent

    // --------------------------------------------------------------- accent
    /** The one loud colour: send button, own bubbles, badges, primary actions. */
    val Accent: Color @Composable get() = LocalLiuliPalette.current.Accent
    val AccentSoft: Color @Composable get() = LocalLiuliPalette.current.AccentSoft
    val AccentDeep: Color @Composable get() = LocalLiuliPalette.current.AccentDeep

    // Secondary hues, used sparingly for avatars, group badges and charts.
    val Violet: Color @Composable get() = LocalLiuliPalette.current.Violet
    val Indigo: Color @Composable get() = LocalLiuliPalette.current.Indigo
    val Magenta: Color @Composable get() = LocalLiuliPalette.current.Magenta
    val Cyan: Color @Composable get() = LocalLiuliPalette.current.Cyan
    val Teal: Color @Composable get() = LocalLiuliPalette.current.Teal
    val Amber: Color @Composable get() = LocalLiuliPalette.current.Amber
    val Lime: Color @Composable get() = LocalLiuliPalette.current.Lime

    // Legacy aliases kept so older screens keep compiling.
    val Ink: Color @Composable get() = LocalLiuliPalette.current.Bg
    val InkSoft: Color @Composable get() = LocalLiuliPalette.current.Surface

    // -------------------------------------------------------------- bubbles
    /** Own message bubble — green in light, deep olive in dark. */
    val BubbleMineTop: Color @Composable get() = LocalLiuliPalette.current.BubbleMineTop
    val BubbleMineBottom: Color @Composable get() = LocalLiuliPalette.current.BubbleMineBottom
    val BubbleMineText: Color @Composable get() = LocalLiuliPalette.current.BubbleMineText
    /** Incoming bubble: a card that sits above the page in both themes. */
    val BubbleTheirs: Color @Composable get() = LocalLiuliPalette.current.BubbleTheirs
    val BubbleTheirsText: Color @Composable get() = LocalLiuliPalette.current.BubbleTheirsText

    // --------------------------------------------------------------- status
    val Success: Color @Composable get() = LocalLiuliPalette.current.Success
    val Danger: Color @Composable get() = LocalLiuliPalette.current.Danger
    val Warn: Color @Composable get() = LocalLiuliPalette.current.Warn
    val Link: Color @Composable get() = LocalLiuliPalette.current.Link

    /** Gradient for primary buttons and the brand mark. */
    val brandGradient: List<Color> @Composable get() = LocalLiuliPalette.current.brandGradient
}

/** How much material a surface carries. */
enum class GlassLevel { Thin, Regular, Thick, Ultra, Floating, Bar, Sheet }

/**
 * Which rendering effect the chrome uses.
 *
 * The three options are a straight trade of looks against frame time, and the
 * user picks:
 *
 * * [Liquid] — backdrop blur **plus** a runtime-shader lens that bends what is
 *   behind the edge, with chromatic dispersion. The prettiest, the most
 *   expensive (a `RuntimeShader` pass per surface).
 * * [Blur] — backdrop blur only, no refraction. Reads as frosted glass, costs
 *   one render-effect pass per surface.
 * * [None] — no render effect at all: flat translucent panels with a hairline.
 *   The chrome stops recording the content behind it, which is what makes a
 *   long list scroll at frame rate on a slower phone.
 */
enum class GlassMode(val id: Int) {
    Liquid(0),
    Blur(1),
    None(2);

    companion object {
        fun of(id: Int): GlassMode = entries.firstOrNull { it.id == id } ?: Blur
    }
}

/** A resolved description of one piece of glass. */
@Immutable
data class GlassSpec(
    val blur: Dp,
    val refraction: Dp,
    val refractionAmount: Dp,
    val chromatic: Boolean,
    val tintTop: Color,
    val tintBottom: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val highlightAlpha: Float,
    val shadowRadius: Dp,
    val shadowAlpha: Float,
    val shadowOffsetY: Dp,
    val specular: Float
)

/** The body colour of a glass panel: white frosted in light, charcoal in dark. */
private fun materialOf(dark: Boolean): Color = if (dark) Color(0xFF1C1D21) else Color.White

/** The hairline that gives a panel an edge against the page behind it. */
private fun edgeOf(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f)

/**
 * Material for the active theme.
 *
 * Dark glass tints with the dark surface instead of white: a white-tinted bar
 * over a near-black page reads as a light grey slab and fights the palette.
 * Everything else — blur radius, specular, shadow — is shared, so the material
 * does not drift between themes.
 */
private fun specFor(level: GlassLevel, dark: Boolean): GlassSpec {
    val body = materialOf(dark)
    val edge = edgeOf(dark)
    val specular = if (dark) 0.10f else 0.16f
    val shadow = if (dark) 0.45f else 0.08f

    return when (level) {
        GlassLevel.Thin -> GlassSpec(
            blur = 6.dp,
            refraction = 10.dp,
            refractionAmount = 14.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.62f else 0.72f),
            tintBottom = body.copy(alpha = if (dark) 0.52f else 0.62f),
            borderTop = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.90f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.18f else 0.55f,
            shadowRadius = 8.dp,
            shadowAlpha = shadow,
            shadowOffsetY = 2.dp,
            specular = specular
        )

        GlassLevel.Regular -> GlassSpec(
            blur = 14.dp,
            refraction = 14.dp,
            refractionAmount = 20.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.70f else 0.80f),
            tintBottom = body.copy(alpha = if (dark) 0.60f else 0.68f),
            borderTop = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.95f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.22f else 0.65f,
            shadowRadius = 14.dp,
            shadowAlpha = shadow,
            shadowOffsetY = 3.dp,
            specular = specular
        )

        GlassLevel.Thick -> GlassSpec(
            blur = 20.dp,
            refraction = 16.dp,
            refractionAmount = 24.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.80f else 0.88f),
            tintBottom = body.copy(alpha = if (dark) 0.72f else 0.78f),
            borderTop = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 1f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.26f else 0.75f,
            shadowRadius = 20.dp,
            shadowAlpha = shadow,
            shadowOffsetY = 5.dp,
            specular = if (dark) 0.12f else 0.20f
        )

        GlassLevel.Ultra -> GlassSpec(
            blur = 26.dp,
            refraction = 18.dp,
            refractionAmount = 26.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.88f else 0.94f),
            tintBottom = body.copy(alpha = if (dark) 0.82f else 0.86f),
            borderTop = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 1f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.30f else 0.85f,
            shadowRadius = 28.dp,
            shadowAlpha = if (dark) 0.55f else 0.14f,
            shadowOffsetY = 8.dp,
            specular = if (dark) 0.14f else 0.24f
        )

        GlassLevel.Floating -> GlassSpec(
            blur = 18.dp,
            refraction = 16.dp,
            refractionAmount = 22.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.78f else 0.84f),
            tintBottom = body.copy(alpha = if (dark) 0.68f else 0.72f),
            borderTop = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 1f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.28f else 0.80f,
            shadowRadius = 30.dp,
            shadowAlpha = if (dark) 0.60f else 0.20f,
            shadowOffsetY = 10.dp,
            specular = if (dark) 0.16f else 0.26f
        )

        // Chrome: top bars and the tab bar.
        //
        // A 56dp blur is what makes the text under a bar unreadable: the point
        // of frosted chrome is that scrolling content turns into a wash, and
        // anything smaller leaves letters legible, which reads as a
        // transparency bug rather than as material. The tint stays around
        // 72–78% so the *colour* behind still moves through the bar — high
        // white would hide the blur and make the bar flat.
        GlassLevel.Bar -> GlassSpec(
            blur = 56.dp,
            refraction = 14.dp,
            refractionAmount = 18.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.80f else 0.78f),
            tintBottom = body.copy(alpha = if (dark) 0.74f else 0.72f),
            borderTop = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 1f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.12f else 0.30f,
            shadowRadius = 6.dp,
            shadowAlpha = if (dark) 0.35f else 0.04f,
            shadowOffsetY = 1.dp,
            specular = 0.03f
        )

        // Menus, sheets and dialogs.
        //
        // As frosted as the bars. The user's note was blunt: the popups had no
        // visible blur at all, only a flat white panel. Dropping the blur to
        // 20dp to chase a frame-time number was the wrong trade — the measured
        // stutter came from first composition, not from the radius, so the
        // material is back to a strong blur with a moderate tint. Readable
        // content, unmistakably frosted.
        GlassLevel.Sheet -> GlassSpec(
            blur = 48.dp,
            refraction = 12.dp,
            refractionAmount = 16.dp,
            chromatic = false,
            tintTop = body.copy(alpha = if (dark) 0.88f else 0.84f),
            tintBottom = body.copy(alpha = if (dark) 0.85f else 0.80f),
            borderTop = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 1f),
            borderBottom = edge,
            highlightAlpha = if (dark) 0.14f else 0.20f,
            shadowRadius = 34.dp,
            shadowAlpha = if (dark) 0.65f else 0.22f,
            shadowOffsetY = 12.dp,
            specular = 0.03f
        )
    }
}

/** Scales a spec by the user's "玻璃强度" slider (0…1, 1 = full). */
fun GlassSpec.scaled(intensity: Float): GlassSpec {
    val k = intensity.coerceIn(0f, 1f)
    if (k >= 0.999f) return this
    // Lowering the slider makes the surface *flatter and more opaque*, which is
    // both a taste setting and the escape hatch on a slow device.
    val mix = 0.45f + 0.55f * k
    return copy(
        blur = blur * mix,
        refraction = refraction * mix,
        refractionAmount = refractionAmount * mix,
        tintTop = tintTop.copy(alpha = (tintTop.alpha + (1f - mix) * 0.2f).coerceAtMost(1f)),
        tintBottom = tintBottom.copy(alpha = (tintBottom.alpha + (1f - mix) * 0.2f).coerceAtMost(1f)),
        shadowAlpha = shadowAlpha * mix,
        specular = specular * mix
    )
}

/** The backdrop every glass surface samples. */
val LocalGlassBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

/** Current material strength, driven by settings. */
val LocalGlassIntensity = staticCompositionLocalOf { 1f }

/** Which effect the chrome renders with, driven by settings. */
val LocalGlassMode = staticCompositionLocalOf { GlassMode.Blur }

/** Resolve a [GlassSpec] honouring the ambient intensity and theme. */
@Composable
fun glassSpec(level: GlassLevel): GlassSpec {
    val intensity = LocalGlassIntensity.current
    val dark = LocalLiuliDark.current
    return specFor(level, dark).scaled(intensity)
}

/** Convenience gradient used for accents, avatars and primary buttons. */
fun accentBrush(start: Color, end: Color): Brush = Brush.linearGradient(listOf(start, end))

private val LiuliTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

@Composable
private fun liuliScheme(p: LiuliPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.Accent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF14361F),
        onPrimaryContainer = p.AccentSoft,
        secondary = p.Link,
        onSecondary = Color.White,
        tertiary = p.Cyan,
        background = p.Bg,
        onBackground = p.TextPrimary,
        surface = p.Surface,
        onSurface = p.TextPrimary,
        surfaceVariant = p.SurfaceAlt,
        onSurfaceVariant = p.TextSecondary,
        outline = p.Separator,
        error = p.Danger,
        onError = Color.White
    )
} else {
    lightColorScheme(
        primary = p.Accent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8F5E4),
        onPrimaryContainer = p.AccentDeep,
        secondary = p.Link,
        onSecondary = Color.White,
        tertiary = p.Cyan,
        background = p.Bg,
        onBackground = p.TextPrimary,
        surface = p.Surface,
        onSurface = p.TextPrimary,
        surfaceVariant = p.SurfaceAlt,
        onSurfaceVariant = p.TextSecondary,
        outline = p.Separator,
        error = p.Danger,
        onError = Color.White
    )
}

/**
 * Installs the palette, the Material scheme and the system-bar appearance.
 *
 * [dark] defaults to the stored `themeMode` (follow the phone / force light /
 * force dark), so the shell does not have to pass it and a switch anywhere in
 * the app repaints everything. The window
 * insets controller is updated in a [SideEffect] because `MainActivity` asks
 * for dark status-bar icons once at startup — without this, turning the theme
 * dark would leave dark icons on a dark bar.
 */
@Composable
fun LiuliTheme(
    intensity: Float = 1f,
    mode: GlassMode = GlassMode.Blur,
    dark: Boolean? = null,
    content: @Composable () -> Unit
) {
    val stored by Svc.settings.flow.collectAsStateWithLifecycle()
    // 0 = follow the phone, 1 = force light, 2 = force dark. Following the
    // system is the default, and `isSystemInDarkTheme()` recomposes on its own
    // when the phone flips, so nothing else has to listen for it.
    val isDark = dark ?: when (stored.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val palette = if (isDark) DarkPalette else LightPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalLiuliPalette provides palette,
        LocalLiuliDark provides isDark,
        LocalGlassIntensity provides intensity,
        LocalGlassMode provides mode
    ) {
        MaterialTheme(
            colorScheme = liuliScheme(palette, isDark),
            typography = LiuliTypography,
            content = content
        )
    }
}

/** Corner radii used across the app so nothing drifts. */
object Radii {
    val bubble = 8.dp
    val bubbleTail = 4.dp
    val card = 12.dp
    val panel = 16.dp
    val sheet = 18.dp
    val bar = 14.dp
    val pill = 999.dp
    val field = 10.dp
    val thumb = 6.dp
}
