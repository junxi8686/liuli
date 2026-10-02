package com.liuli.btchat.ui.glass

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One semantic name per icon.
 *
 * Screens never import an icon set directly, so swapping the artwork later is a
 * single-file change instead of a hunt through every screen.
 */
object LiuliIcons {
    val Chat: ImageVector get() = Icons.Rounded.ChatBubble
    val Chats: ImageVector get() = Icons.Rounded.Forum
    val Contacts: ImageVector get() = Icons.Rounded.Person
    /** Address book — distinct from [Me] so the two tabs never look alike. */
    val People: ImageVector get() = Icons.Rounded.People
    /** The "我的" tab. */
    val Me: ImageVector get() = Icons.Rounded.AccountCircle
    val Group: ImageVector get() = Icons.Rounded.Groups
    val Discover: ImageVector get() = Icons.Rounded.BluetoothSearching
    val Settings: ImageVector get() = Icons.Rounded.Settings

    val Search: ImageVector get() = Icons.Rounded.Search
    val Send: ImageVector get() = Icons.AutoMirrored.Rounded.Send
    val Add: ImageVector get() = Icons.Rounded.Add
    val Back: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
    val Forward: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowForward
    val Close: ImageVector get() = Icons.Rounded.Close
    val ChevronRight: ImageVector get() = Icons.Rounded.ChevronRight
    val ExpandMore: ImageVector get() = Icons.Rounded.ExpandMore
    val More: ImageVector get() = Icons.Rounded.MoreHoriz
    val MoreVertical: ImageVector get() = Icons.Rounded.MoreVert

    val Check: ImageVector get() = Icons.Rounded.Check
    val Checked: ImageVector get() = Icons.Rounded.DoneAll
    val Cancel: ImageVector get() = Icons.Rounded.Cancel
    val Warning: ImageVector get() = Icons.Rounded.WarningAmber
    val Error: ImageVector get() = Icons.Rounded.ErrorOutline
    val Info: ImageVector get() = Icons.Rounded.Info
    val Verified: ImageVector get() = Icons.Rounded.Verified

    val Image: ImageVector get() = Icons.Rounded.Image
    val Video: ImageVector get() = Icons.Rounded.Videocam
    val Camera: ImageVector get() = Icons.Rounded.PhotoCamera
    val File: ImageVector get() = Icons.Rounded.InsertDriveFile
    val Folder: ImageVector get() = Icons.Rounded.FolderOpen
    val Attach: ImageVector get() = Icons.Rounded.AttachFile
    val Download: ImageVector get() = Icons.Rounded.Download

    val Play: ImageVector get() = Icons.Rounded.PlayArrow
    val Pause: ImageVector get() = Icons.Rounded.Pause
    val Emoji: ImageVector get() = Icons.Rounded.EmojiEmotions
    val Mic: ImageVector get() = Icons.Rounded.Mic
    /** Switches the chat input back to typing. */
    val Keyboard: ImageVector get() = Icons.Rounded.Keyboard

    // ---- calls ----
    val Call: ImageVector get() = Icons.Rounded.Call
    val CallEnd: ImageVector get() = Icons.Rounded.CallEnd
    val MicOff: ImageVector get() = Icons.Rounded.MicOff
    val VideoOff: ImageVector get() = Icons.Rounded.VideocamOff
    /** Front/back camera flip. */
    val CameraFlip: ImageVector get() = Icons.Rounded.Cameraswitch

    val Bluetooth: ImageVector get() = Icons.Rounded.Bluetooth
    val BluetoothOn: ImageVector get() = Icons.Rounded.BluetoothConnected
    val BluetoothOff: ImageVector get() = Icons.Rounded.BluetoothDisabled
    val Speed: ImageVector get() = Icons.Rounded.Speed
    val Swap: ImageVector get() = Icons.Rounded.SwapHoriz
    val Refresh: ImageVector get() = Icons.Rounded.Refresh
    val Lock: ImageVector get() = Icons.Rounded.Lock
    val QrCode: ImageVector get() = Icons.Rounded.QrCode

    val Delete: ImageVector get() = Icons.Rounded.DeleteOutline
    val Edit: ImageVector get() = Icons.Rounded.Edit
    val Pin: ImageVector get() = Icons.Rounded.PushPin
    val Star: ImageVector get() = Icons.Rounded.Star
    val Mute: ImageVector get() = Icons.Rounded.NotificationsOff
    val Bell: ImageVector get() = Icons.Rounded.Notifications
    val Logout: ImageVector get() = Icons.Rounded.Logout
    val Share: ImageVector get() = Icons.Rounded.Share
    val Copy: ImageVector get() = Icons.Rounded.ContentCopy
    val Link: ImageVector get() = Icons.Rounded.Link
    val Palette: ImageVector get() = Icons.Rounded.Palette
    val ChatAlt: ImageVector get() = Icons.Rounded.Chat
}
