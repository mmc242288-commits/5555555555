package com.touchpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TouchAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // Only window transitions should change the foreground-target state.
        // Content/overlay events can originate from TouchPilot itself.
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val packageName = e.packageName?.toString() ?: return
        // TouchPilot's own overlay/editor must not flip the target-foreground state.
        if (packageName == this.packageName) return
        // The keyboard opening emits a window event from the IME package. Treating that as
        // "target left the foreground" paused the run until the target changed window again.
        if (isImePackage(packageName)) return
        val target = getSharedPreferences("tp", 0).getString("target", null)
        isTargetForeground = !target.isNullOrBlank() && packageName == target
    }

    private fun isImePackage(pkg: String): Boolean = try {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.enabledInputMethodList.any { it.packageName == pkg }
    } catch (_: Exception) { false }

    override fun onInterrupt() { isTargetForeground = false }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        isTargetForeground = false
        super.onDestroy()
    }

    /**
     * Multiple strokes are submitted in one GestureDescription with identical start times.
     * Android/device/app behavior still needs real-device verification.
     */
    fun dispatchTogether(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val builder = GestureDescription.Builder()
        points.forEach { (x, y) ->
            // A zero-length moveTo-only path is rejected on some Android builds.
            // A same-coordinate line produces a proper tap/hold stroke.
            val path = Path().apply { moveTo(x, y); lineTo(x, y) }
            builder.addStroke(
                GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(40, 60000))
            )
        }
        return dispatchGesture(builder.build(), null, null)
    }

    companion object {
        @Volatile var instance: TouchAccessibilityService? = null
        @Volatile var isTargetForeground: Boolean = false
    }
}
