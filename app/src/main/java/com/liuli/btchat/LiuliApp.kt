package com.liuli.btchat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.liuli.btchat.bt.Engine
import com.liuli.btchat.core.Svc
import com.liuli.btchat.data.CallNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LiuliApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Di.install(this)

        // 来电提醒挂在进程作用域上，而不是某个界面的作用域：它必须在应用被切到
        // 后台、甚至界面被销毁时照样工作 —— 那正是它存在的理由。
        CallNotifier.install(
            this,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            Engine.call
        )

        // Foreground tracking, used to keep the app quiet about a message that
        // arrives in the conversation already on screen. A started-activity
        // counter is enough and needs no extra dependency.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                Svc.appVisible = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) Svc.appVisible = false
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        lateinit var instance: LiuliApp
            private set
    }
}
