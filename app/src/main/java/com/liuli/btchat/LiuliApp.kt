package com.liuli.btchat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.liuli.btchat.core.Svc

class LiuliApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Di.install(this)

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
