package com.habitcoach.mobile

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.bridge.Arguments
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import expo.modules.ReactActivityDelegateWrapper

class MainActivity : ReactActivity() {

  private var pendingAlarmExtras: AlarmExtras? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    // Set the theme to AppTheme BEFORE onCreate to support
    // coloring the background, status bar, and navigation bar.
    // This is required for expo-splash-screen.
    setTheme(R.style.AppTheme);
    super.onCreate(null)
    handleAlarmIntent(intent, isNewIntent = false)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleAlarmIntent(intent, isNewIntent = true)
  }

  /**
   * A habit alarm (AlarmReceiver) launched this Activity directly via
   * startActivity() — see AlarmReceiver's javadoc. The flags below are what
   * actually put the app on screen over a locked device / on top of
   * whatever else is running, with zero taps: without them, Android would
   * launch the activity into the background task stack instead of
   * bringing it to the foreground.
   */
  private fun handleAlarmIntent(intent: Intent?, isNewIntent: Boolean) {
    val habitId = intent?.getIntExtra(AlarmReceiver.EXTRA_HABIT_ID, -1) ?: -1
    if (habitId < 0) return
    val kind = intent?.getStringExtra(AlarmReceiver.EXTRA_KIND) ?: AlarmReceiver.KIND_DAILY

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
      keyguardManager.requestDismissKeyguard(this, null)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
      )
    }

    if (isNewIntent) {
      // App/JS already running -> push it straight to JS instead of making
      // App.tsx wait for a poll that would violate "no JS polling".
      reactInstanceManagerOrHost()?.let { reactContext ->
        val params = Arguments.createMap()
        params.putInt("habitId", habitId)
        params.putString("kind", kind)
        reactContext.emitDeviceEvent("onHabitAlarm", params)
      }
    } else {
      // Cold start -> stash it for AlarmModule.getInitialAlarm()'s one-time pull.
      pendingAlarmExtras = AlarmExtras(habitId, kind)
    }
  }

  private fun reactInstanceManagerOrHost() = reactDelegate?.reactHost?.currentReactContext

  /** Consumed exactly once by AlarmModule.getInitialAlarm() — mirrors
   * expo-notifications' getLastNotificationResponseAsync() semantics so a
   * cold-start alarm launch is reported to JS exactly one time. */
  fun consumeInitialAlarmExtras(): AlarmExtras? {
    val extras = pendingAlarmExtras
    pendingAlarmExtras = null
    return extras
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "main"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate {
    return ReactActivityDelegateWrapper(
          this,
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
          object : DefaultReactActivityDelegate(
              this,
              mainComponentName,
              fabricEnabled
          ){})
  }

  /**
    * Align the back button behavior with Android S
    * where moving root activities to background instead of finishing activities.
    * @see <a href="https://developer.android.com/reference/android/app/Activity#onBackPressed()">onBackPressed</a>
    */
  override fun invokeDefaultOnBackPressed() {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
          if (!moveTaskToBack(false)) {
              // For non-root activities, use the default implementation to finish them.
              super.invokeDefaultOnBackPressed()
          }
          return
      }

      // Use the default back button implementation on Android S
      // because it's doing more than [Activity.moveTaskToBack] in fact.
      super.invokeDefaultOnBackPressed()
  }
}
