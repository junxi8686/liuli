package com.liuli.btchat.media

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Compose wrappers around the system photo picker.
 *
 * This file deliberately contains **no UI** — it only hands the caller a launcher. The UI picks
 * the result up through a callback, and the returned URI is then fed to
 * [ImagePipeline.process] or [VideoPipeline.process], which copy the bytes into app storage
 * immediately (the picker grant is transient, so nothing may be kept as a raw URI).
 *
 * ```kotlin
 * val pickImage = rememberImagePicker { uri ->
 *     scope.launch { ImagePipeline.process(context, uri)?.let { engine.sendMedia(convId, it) } }
 * }
 * Button(onClick = { pickImage.launchImagePicker() }) { Text("相册") }
 * ```
 *
 * `PickVisualMedia` talks to the system photo picker on Android 13+ and falls back to a
 * documents-provider UI on older devices, so no storage permission is required either way.
 */

/** Launcher for a single image; ignores a cancelled pick. */
@Composable
fun rememberImagePicker(onPicked: (Uri) -> Unit): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }

/** Launcher for a single video; ignores a cancelled pick. */
@Composable
fun rememberVideoPicker(onPicked: (Uri) -> Unit): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }

/** Opens the picker restricted to images. */
fun ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>.launchImagePicker() {
    launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}

/** Opens the picker restricted to videos. */
fun ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>.launchVideoPicker() {
    launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
}
