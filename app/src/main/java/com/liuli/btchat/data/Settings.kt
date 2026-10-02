package com.liuli.btchat.data

import android.content.Context
import android.content.SharedPreferences
import com.liuli.btchat.LiuliApp
import com.liuli.btchat.core.GlassModeIds
import com.liuli.btchat.core.Prefs
import com.liuli.btchat.core.SettingsApi
import com.liuli.btchat.core.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The settings store, persisted in the `liuli_prefs` [SharedPreferences] file.
 *
 * Owner: `data-store`. The whole [Prefs] record is kept in memory as the single
 * source of truth ([flow]) and mirrored to disk on every change, so the UI never
 * blocks on I/O and a settings screen always reads what was just written.
 *
 * Threading: [edit] and [ensureIdentity] are read-modify-write cycles and run
 * under one monitor; every other access is a plain state read. Writes go through
 * `apply()`, which is asynchronous and safe to call from the main thread.
 *
 * Degradation: if the preferences file is unavailable (application not created
 * yet, storage failure) or a stored value has the wrong type, the call falls back
 * to in-memory values instead of throwing — a corrupt settings entry can never
 * take the app down or silently reset the local identity.
 */
object Settings : SettingsApi {

    private const val PREFS_NAME = "liuli_prefs"

    /**
     * Fallback nickname.
     *
     * This used to be `"我"`, which was a real bug: every fresh install called
     * itself "我", so the moment two phones connected each one showed the other
     * as *itself* — the contact list filled with "我", and every incoming
     * message looked like it had been sent by you. The advertised name must be
     * something that distinguishes the two devices; "我" is a *display* label
     * for the local user and is applied by the UI, never sent over the wire.
     */
    private const val FALLBACK_NAME = "琉璃用户"

    /** The old default, migrated away on first launch after the fix. */
    private const val LEGACY_NAME = "我"

    /** Avatar hues are stored as a seed in `0..359`, one per degree of the colour wheel. */
    private val avatarSeedRange = 0..359

    private const val KEY_DEVICE_ID = "my_device_id"
    private const val KEY_NAME = "my_name"
    private const val KEY_AVATAR_SEED = "my_avatar_seed"
    private const val KEY_STATUS = "my_status"
    private const val KEY_DISCOVERABLE = "discoverable"
    private const val KEY_AUTO_ACCEPT = "auto_accept_media"
    private const val KEY_GLASS = "glass_intensity"
    private const val KEY_EFFECT = "effect_mode"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_BUBBLE = "bubble_style"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_BLUR = "blur_radius"
    private const val KEY_SOUND = "sound_on"
    private const val KEY_VIBRATE = "vibrate_on"

    private val lock = Any()
    private val state = MutableStateFlow(Prefs())

    @Volatile
    private var loaded = false

    /**
     * Loading is lazy but has to happen before the first read, hence the getter:
     * a collector that obtains the flow from the UI always sees the values that
     * are already on disk.
     */
    override val flow: StateFlow<Prefs>
        get() = state.also { loadOnce() }

    /** True when the values really came from (and go to) disk. */
    internal val persisted: Boolean
        get() = loaded

    override fun current(): Prefs {
        loadOnce()
        return state.value
    }

    override fun edit(block: (Prefs) -> Prefs) = synchronized(lock) {
        loadOnce()
        val before = state.value
        val next = block(before)
        if (next != before) {
            state.value = next
            persist(next)
        }
    }

    /**
     * Creates the local identity on first launch: a fresh device id, a display
     * name and a random avatar hue. An identity that already exists is returned
     * untouched, so the id peers have seen never changes between launches.
     */
    override fun ensureIdentity(): Prefs = synchronized(lock) {
        loadOnce()
        val cur = state.value
        val usable = cur.myName.isNotBlank() && cur.myName != LEGACY_NAME
        if (cur.myDeviceId.isNotBlank() && usable) return@synchronized cur
        val next = cur.copy(
            myDeviceId = cur.myDeviceId.ifBlank { newId() },
            myName = if (usable) cur.myName else generateName(),
            myAvatarSeed = cur.myAvatarSeed.takeIf { it != 0 } ?: avatarSeedRange.random()
        )
        state.value = next
        persist(next)
        next
    }

    /**
     * Picks a name that tells this phone apart from the other one.
     *
     * The Bluetooth adapter's own name is the best answer when it is readable —
     * it is what the peer sees in their system settings too. Reading it needs
     * `BLUETOOTH_CONNECT` on Android 12+, and this runs before permissions are
     * granted, so the fallback has to be good on its own: a four digit suffix
     * keeps two rooms' worth of fresh installs distinguishable.
     */
    private fun generateName(): String {
        adapterName()?.let { return it.take(24) }
        return "$FALLBACK_NAME${(1000..9999).random()}"
    }

    private fun adapterName(): String? = runCatching {
        val manager = LiuliApp.instance
            .getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter?.name?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------------------------------------------------------------- storage

    private fun loadOnce() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            // A null file (application object not created yet) is not a failure:
            // leave `loaded` false so the next call retries.
            val sp = sharedPrefs() ?: return
            state.value = read(sp)
            loaded = true
        }
    }

    private fun sharedPrefs(): SharedPreferences? = try {
        LiuliApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    private fun read(sp: SharedPreferences): Prefs {
        val defaults = Prefs()
        return Prefs(
            myDeviceId = sp.text(KEY_DEVICE_ID, defaults.myDeviceId),
            myName = sp.text(KEY_NAME, defaults.myName),
            myAvatarSeed = sp.int(KEY_AVATAR_SEED, defaults.myAvatarSeed),
            myStatus = sp.text(KEY_STATUS, defaults.myStatus),
            discoverable = sp.flag(KEY_DISCOVERABLE, defaults.discoverable),
            autoAcceptMedia = sp.flag(KEY_AUTO_ACCEPT, defaults.autoAcceptMedia),
            glassIntensity = sp.real(KEY_GLASS, defaults.glassIntensity),
            // Forced, not read: the three frosted-glass strength presets were
            // removed from the UI, and the only setting left is "off". Keeping
            // the stored value would resurrect the blur for anyone who had
            // picked it before the switch was deleted, so a phone that upgrades
            // would look different from a fresh install for no visible reason.
            effectMode = GlassModeIds.NONE,            themeMode = sp.int(KEY_THEME, defaults.themeMode),
            bubbleStyle = sp.int(KEY_BUBBLE, defaults.bubbleStyle),
            darkMode = sp.flag(KEY_DARK, defaults.darkMode),
            blurRadius = sp.real(KEY_BLUR, defaults.blurRadius),
            soundOn = sp.flag(KEY_SOUND, defaults.soundOn),
            vibrateOn = sp.flag(KEY_VIBRATE, defaults.vibrateOn)
        )
    }

    private fun persist(p: Prefs) {
        val sp = sharedPrefs() ?: return
        runCatching {
            sp.edit()
                .putString(KEY_DEVICE_ID, p.myDeviceId)
                .putString(KEY_NAME, p.myName)
                .putInt(KEY_AVATAR_SEED, p.myAvatarSeed)
                .putString(KEY_STATUS, p.myStatus)
                .putBoolean(KEY_DISCOVERABLE, p.discoverable)
                .putBoolean(KEY_AUTO_ACCEPT, p.autoAcceptMedia)
                .putFloat(KEY_GLASS, p.glassIntensity)
                .putInt(KEY_EFFECT, p.effectMode)
                .putInt(KEY_THEME, p.themeMode)
                .putInt(KEY_BUBBLE, p.bubbleStyle)
                .putBoolean(KEY_DARK, p.darkMode)
                .putFloat(KEY_BLUR, p.blurRadius)
                .putBoolean(KEY_SOUND, p.soundOn)
                .putBoolean(KEY_VIBRATE, p.vibrateOn)
                .apply()
        }
    }

    // Reads tolerate a type mismatch (for example a value written by an older
    // build) field by field: one bad entry must not reset the whole record.

    private fun SharedPreferences.text(key: String, fallback: String): String =
        runCatching { getString(key, fallback) }.getOrNull() ?: fallback

    private fun SharedPreferences.int(key: String, fallback: Int): Int =
        runCatching { getInt(key, fallback) }.getOrDefault(fallback)

    private fun SharedPreferences.flag(key: String, fallback: Boolean): Boolean =
        runCatching { getBoolean(key, fallback) }.getOrDefault(fallback)

    private fun SharedPreferences.real(key: String, fallback: Float): Float =
        runCatching { getFloat(key, fallback) }.getOrDefault(fallback)
}
