package com.liuli.btchat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liuli.btchat.core.Prefs
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassIconButton
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassSearchField
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii

/**
 * 「我的资料」面板。
 *
 * A modal editor for the local identity: nickname, status line and the colour
 * seed behind the avatar. Tapping the avatar walks the hue wheel, which is the
 * only avatar picker the app needs — every gradient comes from
 * [com.liuli.btchat.core.avatarColors], so a seed is all there is to store.
 *
 * Presentational on purpose: [onApply] hands the edited values back and the
 * caller persists them through `Svc.settings.edit`.
 */
@Composable
fun ProfileSheet(
    visible: Boolean,
    prefs: Prefs,
    onDismiss: () -> Unit,
    onApply: (name: String, status: String, seed: Int) -> Unit,
    modifier: Modifier = Modifier,
    adapterName: String = "",
    conversationCount: Int = 0,
    contactCount: Int = 0
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    var name by rememberSaveable { mutableStateOf(prefs.myName) }
    var status by rememberSaveable { mutableStateOf(prefs.myStatus) }
    var seed by rememberSaveable { mutableStateOf(prefs.myAvatarSeed) }

    // Re-sync every time the sheet opens so a cancelled edit never leaks in.
    LaunchedEffect(visible, prefs.myName, prefs.myStatus, prefs.myAvatarSeed) {
        if (visible) {
            name = prefs.myName
            status = prefs.myStatus
            seed = prefs.myAvatarSeed
        }
    }

    GlassSheet(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "我的资料",
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { seed = (seed + 47) % 360 },
                contentAlignment = Alignment.Center
            ) {
                GlassAvatar(
                    name = name.ifBlank { "我" },
                    seed = seed,
                    size = 76.dp,
                    glassSheen = false
                )
            }
            Text(
                "轻点头像换个颜色 · 色相 $seed",
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            GlassSearchField(
                value = name,
                onValueChange = { name = it.take(24) },
                placeholder = "昵称",
                leading = {
                    Icon(
                        LiuliIcons.Contacts,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            )

            GlassSearchField(
                value = status,
                onValueChange = { status = it.take(40) },
                placeholder = "状态签名",
                leading = {
                    Icon(
                        LiuliIcons.ChatAlt,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            )

            InfoTile(
                label = "设备 ID",
                value = prefs.myDeviceId.ifBlank { "正在生成…" },
                action = {
                    if (prefs.myDeviceId.isNotBlank()) {
                        GlassIconButton(
                            onClick = {
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("琉璃 设备 ID", prefs.myDeviceId)
                                )
                            },
                            size = 32.dp,
                            level = GlassLevel.Thin,
                            bordered = false
                        ) {
                            Icon(
                                LiuliIcons.Copy,
                                contentDescription = "复制设备 ID",
                                tint = LiuliColors.Accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            )

            if (adapterName.isNotBlank()) {
                InfoTile(label = "本机蓝牙名", value = adapterName)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile("会话", "$conversationCount", Modifier.weight(1f))
                StatTile("联系人", "$contactCount", Modifier.weight(1f))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "取消",
                            color = LiuliColors.TextPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                GlassPrimaryButton(
                    onClick = {
                        onApply(name.trim(), status.trim().ifBlank { "在蓝牙上" }, seed)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank()
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "保存",
                            color = LiuliColors.TextOnAccent,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** Label + value tile with an optional trailing action. */
@Composable
private fun InfoTile(
    label: String,
    value: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.card))
            .clickable(enabled = false) { }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                value,
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        action?.invoke()
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, color = LiuliColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(label, color = LiuliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
    }
}
