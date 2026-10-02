package com.liuli.btchat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.liuli.btchat.data.EXTRA_CONV_ID
import com.liuli.btchat.ui.LiuliRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A light page runs edge to edge, so the system bars stay transparent
        // and their icons are drawn dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        consumeIntent(intent)
        setContent { LiuliRoot() }
    }

    /**
     * The activity is `singleTask`, so tapping a message notification while the
     * app is already running arrives here rather than in [onCreate]. Without
     * this override the extra below was never read by anything and every
     * notification tap just brought the app to the front, landing on whatever
     * screen it was last on instead of the conversation that was tapped.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(EXTRA_CONV_ID) ?: return
        // Handed to the shell through a process-wide slot: the Compose tree is
        // already built by the time this runs, so it observes and clears it.
        com.liuli.btchat.core.Svc.pendingOpenConvId.value = convId
    }
}
