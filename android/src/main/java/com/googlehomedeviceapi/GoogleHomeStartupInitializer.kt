package com.googlehomedeviceapi

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.startup.Initializer

class GoogleHomeStartupInitializer : Initializer<Unit> {

  override fun create(context: Context) {
    val app = context.applicationContext as? Application ?: return

    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is ComponentActivity) {
          GoogleHomeDeviceApiModule.onActivityCreate(activity)
        }
      }

      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {
        if (activity is ComponentActivity) {
          GoogleHomeDeviceApiModule.onActivityDestroy(activity)
        }
      }
    })
  }

  override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
