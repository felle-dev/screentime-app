package felle.screentime

import android.app.Application
import com.google.android.material.color.DynamicColors

class Screentime: Application() {
  override fun onCreate() {
    DynamicColors.applyToActivitiesIfAvailable(this)
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
    super.onCreate()
  }
}
