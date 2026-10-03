package com.liuli.btchat.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.bt.Engine
import com.liuli.btchat.core.Svc
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassMode
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.LiuliTheme
import com.liuli.btchat.ui.glass.glassSurface
import com.liuli.btchat.ui.screens.CallScreen
import com.liuli.btchat.ui.screens.ChatInfoScreen
import com.liuli.btchat.ui.screens.ChatScreen
import com.liuli.btchat.ui.screens.ChatSearchScreen
import com.liuli.btchat.ui.screens.ChatsScreen
import com.liuli.btchat.ui.screens.ContactProfileScreen
import com.liuli.btchat.ui.screens.ContactsScreen
import com.liuli.btchat.ui.screens.DiscoverScreen
import com.liuli.btchat.ui.screens.ForwardPickerScreen
import com.liuli.btchat.ui.screens.GroupCreateScreen
import com.liuli.btchat.ui.screens.MeScreen
import com.liuli.btchat.ui.screens.SettingsScreen
import com.liuli.btchat.ui.screens.StarredScreen
import com.liuli.btchat.ui.screens.VideoPlayerScreen
import kotlinx.coroutines.delay

/**
 * App shell.
 *
 * Navigation is the WeChat shape: **three peer tabs** (会话 / 通讯录 / 设置) that
 * switch in place under a persistent tab bar, plus a stack of second-level
 * screens (a chat, chat info, search, a profile, …) drawn over them.
 *
 * The tab bar is not a way of opening a screen — tapping 通讯录 must land on the
 * contacts tab, not push a page that then offers a back arrow. That also means
 * the system back gesture has to be answered here: without [BackHandler] the
 * activity finishes and the app drops to the launcher from any second-level
 * page.
 */
@Composable
fun LiuliRoot() {
    val context = LocalContext.current
    val prefs by Svc.settings.flow.collectAsStateWithLifecycle()

    // Bluetooth + notification permission gate, asked once at the top so no
    // screen has to carry permission plumbing.
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        Svc.settings.ensureIdentity()
        Svc.engine.start()
        if (!asked) {
            asked = true
            val missing = missingPermissions(context)
            if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
        }
    }

    /** 0 = 会话, 1 = 通讯录, 2 = 我的, 3 = 设置. */
    var tab by rememberSaveable { mutableIntStateOf(0) }

    /** Second-level screens, drawn over the tabs. */
    val detail = remember { mutableStateListOf<Screen>() }
    val current = detail.lastOrNull()

    /** Cleared once the one-off render-effect warm-up has done its job. */
    var warmed by remember { mutableStateOf(false) }

    fun pop() {
        if (detail.isNotEmpty()) detail.removeAt(detail.lastIndex)
    }

    fun push(screen: Screen) {
        detail.add(screen)
    }

    // A tapped message notification lands here: open that conversation, then
    // clear the slot so the same tap cannot reopen it on a later recompose.
    // Before this existed the extra was written by the notifier and read by
    // nobody, so every tap just brought the app to whatever screen it was on.
    val pendingConv by Svc.pendingOpenConvId.collectAsState()
    LaunchedEffect(pendingConv) {
        val convId = pendingConv ?: return@LaunchedEffect
        Svc.pendingOpenConvId.value = null
        detail.clear()
        detail.add(Screen.Chat(convId))
    }

    /** Jumping from a search hit back into the chat, with the hit highlighted. */
    fun openMessageFromSearch(convId: String, msgId: String) {
        if (detail.isNotEmpty()) detail.removeAt(detail.lastIndex)
        val idx = detail.indexOfLast { it is Screen.Chat && it.convId == convId }
        if (idx >= 0) {
            detail[idx] = Screen.Chat(convId, highlight = msgId)
        } else {
            detail.add(Screen.Chat(convId, highlight = msgId))
        }
    }

    /**
     * Back navigation — deliberately **not** predictive.
     *
     * A hand-rolled `PredictiveBackHandler` drove the page from the gesture
     * stream, and on the real device it always flickered on release: the frame
     * where the gesture is committed and the frame where the transition starts
     * are not the same frame, so the page showed through for an instant no
     * matter how the offsets were arranged. Tapping the on-screen back arrow
     * never had the problem, because it goes straight to [pop].
     *
     * The user's call was to drop the gesture animation entirely and make every
     * back behave like the button. That is also the simpler contract: one code
     * path, one transition, no gesture state to keep in sync.
     */
    BackHandler(enabled = detail.isNotEmpty()) { pop() }

    BackHandler(enabled = detail.isEmpty() && tab != 0) { tab = 0 }

    LiuliTheme(
        intensity = prefs.glassIntensity,
        mode = GlassMode.of(prefs.effectMode)
    ) {
        // 平板/折叠屏展开时改成微信式双栏：左列表 + 右内容。
        // 判据 = 可用宽度 >= [WideBreakpoint]（720dp），不依赖 material3-window-size-class
        // （离线缓存里没有这个库）。**不强制要求横屏**：竖屏平板只要宽度够也是双栏，
        // 不够就单栏 —— 两种情况都不会出现「叠两层」或错位。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WideBreakpoint

            // 存储坏了要说出来。
            //
            // 数据库打不开时 `Store` 会退化成内存实现让界面继续可用 —— 这是对的，
            // 但退化之后的应用**看起来就是一个全新的空应用**：没有会话、没有联系
            // 人、身份也是新生成的。用户唯一的结论是「我的聊天记录全没了」，而
            // 磁盘上的数据其实还在。所以这条横幅不是装饰，它是唯一能把这个误会
            // 挡下来的东西。
            val warning = Svc.store.persistenceWarning
            if (warning != null) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        // 必须显式压在上层。横幅写在内容**之前**，而 Compose 是后
                        // 组合的盖在上面 —— 不加这一行，它会被下面那个不透明的
                        // 主界面整块盖住。这正是白屏那个 bug 的形状，不想再犯
                        // 一次：任何「先写、但想显示在上面」的元素都要说清楚层级。
                        .zIndex(1f)
                        .fillMaxWidth()
                        .background(LiuliColors.Danger)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        warning,
                        color = LiuliColors.TextOnAccent,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            /**
             * 从左侧列表打开一个会话。
             *
             * 手机：压栈（返回回到列表）。
             * 平板：**替换**右栏内容，不在右栏里叠一串聊天 —— 微信在平板上点另一个
             * 会话就是直接换右边那一页，不会越点越深。
             */
            val openChatFromList: (String) -> Unit = { convId ->
                if (wide) detail.clear()
                push(Screen.Chat(convId))
            }
            Box(Modifier.fillMaxSize()) {

            // Warm-up.
            //
            // The first frosted surface the app ever draws pays for Skia to
            // build the render-effect pipeline, and because sheets are composed
            // lazily that bill landed on the user's first tap — the "first open
            // is much laggier" report. Drawing a tiny discarded one behind the
            // scenes shortly after launch moves that cost off the critical path.
            if (!warmed) {
                Box(
                    Modifier
                        .offset(x = (-80).dp, y = (-80).dp)
                        .size(8.dp)
                        .glassSurface(
                            shape = RoundedCornerShape(4.dp),
                            level = GlassLevel.Sheet,
                            specularEdge = false
                        )
                )
            }
            LaunchedEffect(Unit) {
                delay(700)
                warmed = true
            }

            // ---- tabs: swapped in place, no transition, state kept per tab ----
            //
            // 抽成 lambda：单栏时它铺满整屏，双栏时它是左栏的内容。
            val tabStack: @Composable () -> Unit = {
                when (tab) {
                    0 -> ChatsScreen(
                        onOpenChat = { openChatFromList(it) },
                        onOpenContacts = { tab = 1 },
                        onNewGroup = { push(Screen.NewGroup) },
                        onOpenSettings = { tab = 3 },
                        onOpenMe = { tab = 2 },
                        // 平板：把右侧正在看的会话高亮出来（手机上是 null）
                        selectedConvId = if (wide) (current as? Screen.Chat)?.convId else null
                    )

                    1 -> ContactsScreen(
                        onOpenChat = { openChatFromList(it) },
                        onOpenDiscover = { push(Screen.Discover) },
                        onNewGroup = { push(Screen.NewGroup) },
                        onOpenChats = { tab = 0 },
                        onOpenSettings = { tab = 3 },
                        onOpenContact = { push(Screen.ContactProfile(it)) },
                        onOpenMe = { tab = 2 }
                    )

                    2 -> MeScreen(
                        onOpenStarred = { push(Screen.Starred) },
                        onOpenChats = { tab = 0 },
                        onOpenContacts = { tab = 1 },
                        onOpenSettings = { tab = 3 }
                    )

                    else -> SettingsScreen(
                        onOpenChats = { tab = 0 },
                        onOpenContacts = { tab = 1 },
                        onOpenStarred = { push(Screen.Starred) },
                        onOpenChat = { openChatFromList(it) },
                        onOpenMe = { tab = 2 }
                    )
                }
            }

            // ---- second level -------------------------------------------------
            //
            // 单栏（手机）：页面**从右边缘进来**，返回时再往右出去 —— 同一个轴；被盖住的
            // 那页只左移三分之一，两页读起来像一个栈。平板双栏：这一层只活在右栏里，
            // 转场要克制（淡入 + 一点点位移），不再整屏飞。
            val detailLayer: @Composable () -> Unit = {
            AnimatedContent(
                targetState = detail.size to current,
                transitionSpec = {
                    if (wide) {
                        // 右栏内部：淡入 + 1/16 屏宽的轻微位移，退场只淡出。
                        // 双栏下左栏一直在，右栏再整屏滑会很吵。
                        val enterWide = fadeIn(animationSpec = tween(170)) +
                            slideInHorizontally(animationSpec = tween(170)) { it / 16 }
                        val exitWide = fadeOut(animationSpec = tween(120))
                        enterWide togetherWith exitWide
                    } else {
                        val push = targetState.first >= initialState.first
                        // Slides only — deliberately **no fade**.
                        //
                        // A `fadeOut` on the outgoing page makes it translucent for
                        // the length of the transition, and the layer behind a
                        // second-level page is the tab stack. So tapping 「…」 in a
                        // chat faded the chat out just enough to show the **会话
                        // list** through it mid-animation, which read as the wrong
                        // page flashing past. Two opaque pages sliding past each
                        // other is both correct and cheaper.
                        val enter = slideInHorizontally(
                            animationSpec = tween(300)
                        ) { if (push) it else -it / 3 }
                        val exit = slideOutHorizontally(
                            animationSpec = tween(300)
                        ) { if (push) -it / 3 else it }
                        enter togetherWith exit
                    }
                },
                label = "detail"
            ) { state ->
                val screen = state.second
                // Opaque **only when there is actually a page here**.
                //
                // This box exists so a transitioning detail page can never let
                // the tab stack show through. Painting it unconditionally was a
                // white-screen bug: with an empty detail stack the target is
                // `null`, so this drew a full-screen opaque page-coloured
                // rectangle straight over the tab layer. Everything underneath
                // stayed composed and laid out — accessibility still saw every
                // button and taps fell through to it — which is why it looked
                // like "the app is fine, nothing is just drawn".
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (screen != null) Modifier.background(LiuliColors.Bg) else Modifier)
                ) {
                    // 双栏下右栏没有内容时给空态，**不能**把 tab 层再露一遍。
                    if (wide && screen == null) {
                        EmptyDetailPane()
                    } else {
                    when (screen) {
                    null -> Box(Modifier.fillMaxSize())

                    is Screen.Chat -> ChatScreen(
                        convId = screen.convId,
                        onBack = { pop() },
                        onOpenVideo = { path, title -> push(Screen.Video(path, title)) },
                        onOpenContact = { push(Screen.ContactProfile(it)) },
                        onOpenChatInfo = { push(Screen.ChatInfo(screen.convId)) },
                        onOpenSearch = { push(Screen.ChatSearch(screen.convId)) },
                        onForward = { push(Screen.Forward(it)) },
                        highlightMessageId = screen.highlight
                    )

                    is Screen.ChatInfo -> ChatInfoScreen(
                        convId = screen.convId,
                        onBack = { pop() },
                        onOpenContact = { push(Screen.ContactProfile(it)) },
                        onSearch = { push(Screen.ChatSearch(screen.convId)) },
                        // Leaving a group drops the whole chat stack.
                        onLeft = { detail.clear() }
                    )

                    is Screen.ChatSearch -> ChatSearchScreen(
                        convId = screen.convId,
                        onBack = { pop() },
                        onOpenMessage = { openMessageFromSearch(screen.convId, it) }
                    )

                    is Screen.Forward -> ForwardPickerScreen(
                        messageId = screen.messageId,
                        onBack = { pop() },
                        onDone = { pop() }
                    )

                    is Screen.ContactProfile -> ContactProfileScreen(
                        deviceId = screen.deviceId,
                        onBack = { pop() },
                        onOpenChat = { convId ->
                            // Replace the profile with the chat it opens, so back
                            // returns to where the user came from.
                            if (detail.isNotEmpty()) detail.removeAt(detail.lastIndex)
                            push(Screen.Chat(convId))
                        }
                    )

                    is Screen.Starred -> StarredScreen(
                        onBack = { pop() },
                        onOpenChat = { push(Screen.Chat(it)) }
                    )

                    is Screen.Video -> VideoPlayerScreen(
                        path = screen.path,
                        title = screen.title,
                        onBack = { pop() }
                    )

                    is Screen.Discover -> DiscoverScreen(
                        onBack = { pop() },
                        onOpenChat = { push(Screen.Chat(it)) }
                    )

                    is Screen.NewGroup -> GroupCreateScreen(
                        onBack = { pop() },
                        onCreated = { convId ->
                            detail.clear()
                            push(Screen.Chat(convId))
                        }
                    )

                    // The four tab roots never appear in the detail stack.
                    else -> Box(Modifier.fillMaxSize())
                    }
                    }
                }
            }
            }

            // ---- 布局：单栏（现状）或平板双栏 -----------------------------
            //
            // 单栏：tab 层铺满 + 二级页整屏覆盖（和以前一模一样）。
            // 双栏：左侧固定 [LeftPaneWidth] 放 tab（底部导航留在左栏底部，微信在
            // 平板上也是这个形状），右侧 weight(1f) 放二级页；右侧空态由
            // [EmptyDetailPane] 兜底，**不会把 tab 层露第二遍**。
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .width(LeftPaneWidth)
                            .fillMaxHeight()
                            .background(LiuliColors.Surface)
                    ) {
                        tabStack()
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(LiuliColors.Separator)
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        detailLayer()
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    tabStack()
                }
                detailLayer()
            }

            // ---- call: sits above everything, including the detail stack -----
            //
            // A call is not a page you navigate to; it interrupts whatever is on
            // screen and stays until it ends. Mounting it here also means it
            // survives tab switches and page pushes underneath.
            //
            // `Engine` is imported directly rather than reached through
            // `Svc.engine`: the call surface deliberately lives outside the
            // frozen `ChatEngine` contract, and `ui` already talks to `bt`
            // this way for `SelfTest`.
            val callState by Engine.call.collectAsState()
            if (callState.busy) {
                CallScreen(
                    state = callState,
                    onAccept = { Engine.accept() },
                    onReject = { Engine.reject() },
                    onHangUp = { Engine.hangUp() },
                    onToggleMute = { on -> Engine.setMuted(on) },
                    onToggleCamera = { on -> Engine.setCamera(on) },
                    onSwitchCamera = { Engine.switchCamera() },
                    onDismissEnded = { Engine.resetCall() }
                )
            }
            }   // inner Box
        }       // BoxWithConstraints (wide/narrow split)
    }
}

/**
 * 平板双栏的判据：可用宽度达到 720dp。
 *
 * 720dp 是 Android 上「平板」的经验线（大约 10 英寸设备竖屏的宽度），也覆盖折叠屏展开；
 * 不强制横屏 —— 竖屏平板只要够宽就是双栏，不够就退回单栏，两种都不会错位。
 */
private val WideBreakpoint = 720.dp

/** 左栏宽度：微信在平板上左侧就是一个手机宽的列表。 */
private val LeftPaneWidth = 360.dp

/**
 * 平板右栏的空态：一句引导 + 一个淡图标。
 *
 * 这一层**必须存在**：双栏时右栏如果是空白，用户会以为页面没加载出来；
 * 更糟的做法是把 tab 层在右栏再渲染一遍（那就成了两份列表）。
 */
@Composable
private fun EmptyDetailPane() {
    Column(
        Modifier
            .fillMaxSize()
            .background(LiuliColors.Bg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            LiuliIcons.Chats,
            contentDescription = null,
            tint = LiuliColors.TextTertiary.copy(alpha = 0.55f),
            modifier = Modifier.size(58.dp)
        )
        Text(
            "选择左侧的会话开始聊天",
            color = LiuliColors.TextSecondary,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "左侧是会话、通讯录和设置，这里显示聊天内容",
            color = LiuliColors.TextTertiary,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun missingPermissions(context: Context): List<String> {
    val wanted = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    return wanted.filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
