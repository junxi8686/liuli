package com.liuli.btchat.ui

/**
 * The whole navigation graph.
 *
 * Three of these are the peer tabs (会话 / 通讯录 / 设置) and never enter the detail
 * stack; the rest are second-level screens pushed over them.
 */
sealed interface Screen {

    val key: String

    data object Chats : Screen {
        override val key = "chats"
    }

    data object Contacts : Screen {
        override val key = "contacts"
    }

    data object Settings : Screen {
        override val key = "settings"
    }

    data class Chat(val convId: String, val highlight: String? = null) : Screen {
        override val key get() = "chat/$convId"
    }

    data class ChatInfo(val convId: String) : Screen {
        override val key get() = "chatInfo/$convId"
    }

    data class ChatSearch(val convId: String) : Screen {
        override val key get() = "chatSearch/$convId"
    }

    data class Forward(val messageId: String) : Screen {
        override val key get() = "forward/$messageId"
    }

    data class ContactProfile(val deviceId: String) : Screen {
        override val key get() = "contact/$deviceId"
    }

    data object Starred : Screen {
        override val key = "starred"
    }

    data object NewGroup : Screen {
        override val key = "newGroup"
    }

    data object Discover : Screen {
        override val key = "discover"
    }

    data class Video(val path: String, val title: String) : Screen {
        override val key get() = "video/$path"
    }
}
