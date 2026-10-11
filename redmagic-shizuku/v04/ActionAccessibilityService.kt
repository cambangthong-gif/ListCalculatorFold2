package com.redtrigger

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ActionAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var current: ActionAccessibilityService? = null
        fun perform(action: String): Boolean {
            val service = current ?: return false
            val type = when (action) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return false
            }
            return try { service.performGlobalAction(type) }
            catch (_: Exception) { false }
        }
    }
    override fun onServiceConnected() { super.onServiceConnected(); current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { current = null; super.onDestroy() }
}
