package com.zevclip.sender

import android.app.Activity
import android.app.Application
import android.os.Bundle

class ZevClipApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivityCount += 1
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        fun isUiVisible(application: Application): Boolean {
            return (application as? ZevClipApplication)?.resumedActivityCount?.let { it > 0 }
                ?: false
        }
    }
}
