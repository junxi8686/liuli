package com.liuli.btchat.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.liuli.btchat.data.ChatPrefs
import java.io.File

/**
 * Per-chat wallpaper.
 *
 * WeChat lets every conversation carry its own background, so this does too: a
 * preset swatch, or a photo the user picked. The choice lives in [ChatPrefs]
 * keyed by conversation id, which keeps it out of the message schema.
 */
object ChatBackgrounds {

    /**
     * 0 is the plain page colour; the rest are deliberately pale so that white
     * incoming bubbles and dark text keep their contrast.
     */
    val presets: List<Pair<String, Color>> = listOf(
        "默认" to Color(0xFFF2F3F5),
        "薄荷" to Color(0xFFE6F4EA),
        "晴空" to Color(0xFFE8F0FB),
        "米白" to Color(0xFFFAF6EC),
        "藕粉" to Color(0xFFFBEDF1),
        "雾紫" to Color(0xFFF0EDFA),
        "青瓷" to Color(0xFFE4F3F2),
        "夜色" to Color(0xFF23262E)
    )

    fun colorOf(preset: Int): Color =
        presets.getOrNull(preset)?.second ?: presets.first().second

    fun nameOf(preset: Int): String =
        presets.getOrNull(preset)?.first ?: presets.first().first

    /** True when the swatch is dark enough to need light text. */
    fun isDark(preset: Int): Boolean = preset == presets.lastIndex
}

/** Paints the conversation's wallpaper behind [content]. */
@Composable
fun ChatBackground(
    convId: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // Repaint whenever the user picks a new one; the key is the conversation.
    val revision by ChatPrefs.revision.collectAsStateWithLifecycle()
    val preset = remember(convId, revision) { ChatPrefs.background(convId) }
    val imagePath = remember(convId, revision) { ChatPrefs.backgroundImage(convId) }
    val image = imagePath?.let { File(it) }?.takeIf { it.isFile }

    // The wallpaper has to follow the theme too: a hard-coded light page left
    // the message area glaring white under a dark bar and a dark input field.
    val pageColor = LiuliColors.Bg
    val dark = LocalLiuliDark.current

    Box(modifier.fillMaxSize()) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // A wash keeps bubbles readable over a busy photo, and darkens the
            // photo rather than lightening it when the app is in dark mode.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (dark) Color.Black.copy(alpha = 0.42f)
                        else Color.White.copy(alpha = 0.16f)
                    )
            )
        } else {
            // Two `background` overloads (Color vs Brush); pick one, then apply.
            val paint = when {
                preset == ChatPrefs.BACKGROUND_DEFAULT -> Modifier.background(pageColor)
                dark -> {
                    // Keep a hint of the chosen hue instead of repainting the
                    // page with a pastel that would glare.
                    val base = ChatBackgrounds.colorOf(preset)
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(lerp(pageColor, base, 0.16f), lerp(pageColor, base, 0.10f))
                        )
                    )
                }
                else -> {
                    val base = ChatBackgrounds.colorOf(preset)
                    Modifier.background(
                        Brush.verticalGradient(listOf(base, base.copy(alpha = 0.88f)))
                    )
                }
            }
            Box(Modifier.fillMaxSize().then(paint))
        }
        content()
    }
}
