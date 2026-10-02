package com.liuli.btchat.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.liuli.btchat.LiuliApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays one voice message at a time.
 *
 * A voice note is speech, not music: playback uses `USAGE_MEDIA` with `CONTENT_TYPE_SPEECH`, and
 * transient audio focus is taken so a running music app ducks instead of talking over the message.
 * Starting a second clip stops the first, and finishing (or failing) clears [playing] so the
 * bubble animation always has something truthful to follow.
 *
 * Nothing here is tied to a message id — the key is the local file path, which is what both the
 * sender's own recording and the receiver's downloaded payload have in common.
 *
 * ```kotlin
 * val playing by VoicePlayer.playing.collectAsState()
 * val active = playing == att.localPath
 * IconButton(onClick = { VoicePlayer.toggle(att.localPath ?: return@IconButton) }) { … }
 * ```
 */
object VoicePlayer {

    private val lock = Any()

    private val _playing = MutableStateFlow<String?>(null)

    /** Local path of the clip currently playing, or `null` when nothing is playing. */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    private var player: MediaPlayer? = null
    private var current: String? = null
    private var focusRequest: AudioFocusRequest? = null

    /** `true` while a clip is actually playing. */
    val isPlaying: Boolean get() = player != null

    /** Playback head of the current clip, or `0`. */
    val positionMs: Long get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    /** Length of the current clip in milliseconds, or `0` when unknown. */
    val durationMs: Long get() = runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    fun isPlaying(path: String): Boolean = _playing.value == path

    /**
     * Plays [path], or stops it when that clip is already playing — the standard tap-to-toggle of
     * a voice bubble.
     *
     * @param onDone invoked on the main thread when the clip finishes on its own (not when the
     *   user stops it or another clip replaces it).
     */
    fun toggle(path: String, onDone: () -> Unit = {}) {
        // Tapping the clip that is already playing stops it. Cheap, and worth its own lock pass.
        synchronized(lock) {
            if (current == path && player != null) {
                stopLocked()
                return
            }
        }

        // `prepare()` can take tens of milliseconds, so it happens **outside** the lock: holding
        // it here would block `stop()`/`release()` — and every other caller — for that whole time.
        val created = try {
            create(path)
        } catch (e: Exception) {
            null
        } ?: return

        synchronized(lock) {
            // Another tap may have won the race while this one was preparing.
            if (current == path && player != null) {
                runCatching { created.release() }
                return
            }
            stopLocked()

            player = created
            current = path
            _playing.value = path
            requestFocus()

            val started = try {
                created.start()
                true
            } catch (e: Exception) {
                false
            }
            if (!started) {
                stopLocked()
                return
            }

            created.setOnCompletionListener {
                synchronized(lock) {
                    if (player === created) {
                        stopLocked()
                        onDone()
                    }
                }
            }
            created.setOnErrorListener { _, _, _ ->
                synchronized(lock) { if (player === created) stopLocked() }
                true
            }
        }
    }

    /** Stops playback. Idempotent, and safe to call when nothing is playing. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** Drops the player for good; call from the screen's teardown. */
    fun release() = stop()

    // --------------------------------------------------------------- internals

    private fun create(path: String): MediaPlayer {
        require(File(path).isFile) { "voice file missing: $path" }
        val created = MediaPlayer()
        var handedOver = false
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            created.setDataSource(path)
            created.prepare()
            handedOver = true
            return created
        } finally {
            // A player that failed to prepare still holds a native handle, and nothing else will
            // release it once this function throws.
            if (!handedOver) runCatching { created.release() }
        }
    }

    /** Tears the current clip down. Caller must hold [lock]. */
    private fun stopLocked() {
        player?.let { active ->
            runCatching { active.setOnCompletionListener(null) }
            runCatching { active.setOnErrorListener(null) }
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        player = null
        current = null
        _playing.value = null
        abandonFocus()
    }

    private fun requestFocus() {
        runCatching {
            val manager = LiuliApp.instance.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@runCatching
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    // Losing focus (a call, another app) should silence the bubble, not fight it.
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stop()
                    }
                }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        runCatching {
            val manager = LiuliApp.instance.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            focusRequest?.let { manager?.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
    }
}
