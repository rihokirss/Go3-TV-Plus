package ee.local.go3tvplus.tclredirect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class SonyButtonAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        Log.i(TAG, "Sony button filter connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Build.MANUFACTURER.orEmpty().contains("sony", ignoreCase = true)) return false
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_4) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Netflix button intercepted; opening Go3 Air")
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(GO3_PACKAGE, GO3_ACTIVITY)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "Go3ButtonRedirect"
        const val GO3_PACKAGE = "ee.local.go3tvplus.debug"
        const val GO3_ACTIVITY = "ee.local.go3tvplus.MainActivity"
    }
}
