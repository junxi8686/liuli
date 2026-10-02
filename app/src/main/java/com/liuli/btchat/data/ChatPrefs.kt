package com.liuli.btchat.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.liuli.btchat.LiuliApp
import com.liuli.btchat.MainActivity
import com.liuli.btchat.R
import com.liuli.btchat.core.Svc

/**
 * Per-conversation preferences that are too small to deserve a database column:
 * the chat wallpaper and whether this chat is allowed to ring.
 *
 * Keyed by conversation id in one `SharedPreferences` file. A chat that has
 * never been configured simply gets the defaults, so nothing has to be
 * back-filled when a conversation is created.
 */
object ChatPrefs {

    private const val FILE = "liuli_chat_prefs"

    /** 0 = 默认（浅灰底）. Every other id is one of the built-in swatches. */
    const val BACKGROUND_DEFAULT = 0

    private val rev = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Bumped on every write so the chat page can repaint without polling. */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = rev

    private fun bump() {
        rev.value = rev.value + 1
    }

    private fun prefs(): android.content.SharedPreferences? = runCatching {
        LiuliApp.instance.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }.getOrNull()

    // ------------------------------------------------------------ wallpaper

    fun background(convId: String): Int =
        runCatching { prefs()?.getInt("bg_$convId", BACKGROUND_DEFAULT) }
            .getOrDefault(BACKGROUND_DEFAULT) ?: BACKGROUND_DEFAULT

    fun setBackground(convId: String, preset: Int) {
        runCatching { prefs()?.edit()?.putInt("bg_$convId", preset)?.apply() }
        bump()
    }

    /** An image the user picked from the gallery, already copied into app storage. */
    fun backgroundImage(convId: String): String? =
        runCatching { prefs()?.getString("bgimg_$convId", null) }.getOrNull()

    fun setBackgroundImage(convId: String, path: String?) {
        runCatching {
            prefs()?.edit()?.apply {
                if (path.isNullOrBlank()) remove("bgimg_$convId") else putString("bgimg_$convId", path)
            }?.apply()
        }
        bump()
    }

    // ----------------------------------------------------------- walkie-talkie

    /**
     * 对讲机 mode for this chat: hold to talk, and whatever arrives is played
     * out loud on its own. Off by default — a chat that suddenly starts talking
     * at you is a nasty surprise.
     */
    fun walkie(convId: String): Boolean =
        runCatching { prefs()?.getBoolean("ptt_$convId", false) }.getOrDefault(false) ?: false

    fun setWalkie(convId: String, on: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean("ptt_$convId", on)?.apply() }
        bump()
    }

    /** Global switch for playing incoming 对讲 without a tap. On by default. */
    fun autoPlayVoice(): Boolean =
        runCatching { prefs()?.getBoolean(KEY_AUTOPLAY, true) }.getOrDefault(true) ?: true

    fun setAutoPlayVoice(on: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean(KEY_AUTOPLAY, on)?.apply() }
        bump()
    }

    // ----------------------------------------------------------- reminders

    private const val KEY_AUTOPLAY = "autoplay_voice"

    /** False when this chat is muted for the user; see [ChatInfoScreen]. */
    fun notify(convId: String): Boolean =
        runCatching { prefs()?.getBoolean("notify_$convId", true) }.getOrDefault(true) ?: true

    fun setNotify(convId: String, on: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean("notify_$convId", on)?.apply() }
        bump()
    }

    fun clear(convId: String) {
        runCatching {
            prefs()?.edit()?.remove("bg_$convId")?.remove("bgimg_$convId")?.remove("notify_$convId")?.apply()
        }
        bump()
    }
}

/**
 * Rings, buzzes **and posts a notification** for an incoming message.
 *
 * The notification is the part that was missing: the app made a sound in the
 * background but nothing appeared in the shade, so a message that arrived while
 * the user was in another app left no trace unless they happened to hear it.
 *
 * Honours three gates: the app-wide 提示音/震动 switches, this chat's 提醒 switch,
 * and the conversation's own 免打扰 flag.
 */
fun ringForIncoming(convId: String, senderName: String, preview: String) {
    val settings = runCatching { Svc.settings.current() }.getOrNull() ?: return
    val conversation = runCatching { Svc.store.conversation(convId) }.getOrNull()
    if (conversation?.muted == true) return
    if (!ChatPrefs.notify(convId)) return

    // The user is looking straight at this conversation: ringing, buzzing and
    // posting a notification for it is pure noise, and it also means the
    // message is about to be read anyway. Being in *another* chat still rings,
    // which is what every messenger does.
    if (Svc.appVisible && runCatching { Svc.activeConvId.value }.getOrNull() == convId) return

    // A message arriving during a call must not ring: the ringtone is already
    // how the call got here, and a second tone over a live conversation is
    // pure noise. The notification still posts (below), so nothing is lost.
    val ringing = !runCatching { Svc.inCall() }.getOrDefault(false)

    val ctx = runCatching { LiuliApp.instance }.getOrNull() ?: return

    if (ringing && settings.vibrateOn) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    if (ringing && settings.soundOn) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return@runCatching
            val tone = RingtoneManager.getRingtone(ctx, uri) ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = false
            tone.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            if ((ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                tone.play()
            }
        }
    }

    postMessageNotification(ctx, convId, senderName, preview)
}

/** Notification channel for incoming messages. */
private const val CHANNEL_MESSAGES = "liuli_messages"

private fun ensureChannel(ctx: Context): Boolean = runCatching {
    val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return@runCatching false
    if (mgr.getNotificationChannel(CHANNEL_MESSAGES) != null) return@runCatching true
    mgr.createNotificationChannel(
        NotificationChannel(
            CHANNEL_MESSAGES,
            "新消息",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到新消息时提醒"
            // Sound and vibration are both issued by hand in [ringForIncoming]
            // so the app-wide 提示音/震动 switches can gate them. A channel
            // left alone gets the system default notification sound (AOSP:
            // `mSound = Settings.System.DEFAULT_NOTIFICATION_URI`), which meant
            // every message played **two** sounds and the 提示音 switch only
            // silenced one of them.
            setSound(null, null)
            enableVibration(false)
        }
    )
    true
}.getOrDefault(false)

/**
 * One notification per conversation, keyed by its id, so a busy chat collapses
 * into a single line the way every messenger does instead of stacking.
 */
private fun postMessageNotification(ctx: Context, convId: String, senderName: String, preview: String) {
    runCatching {
        // Android 13+ can only post with the runtime permission granted. The
        // app asks for it on first launch; if it was declined we fall back to
        // sound only rather than crashing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@runCatching
        }
        if (!ensureChannel(ctx)) return@runCatching

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONV_ID, convId)
        }
        val tap = PendingIntent.getActivity(
            ctx,
            convId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = when {
            preview.isBlank() -> "发来一条消息"
            preview.length <= 80 -> preview
            else -> preview.take(80) + "…"
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(senderName.ifBlank { "新消息" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(convId.hashCode(), notification)
    }
}

/** The conversation a notification tap should open. */
const val EXTRA_CONV_ID = "conv_id"
