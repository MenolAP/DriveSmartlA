package com.drivesmart.ia

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import org.json.JSONObject

/**
 * Floating quick-access bubble and best-effort inDrive offer reader.
 * Reads only accessibility text exposed by the foreground app; no screenshots are captured.
 */
class OfferAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_BUBBLE_VISIBILITY_CHANGED = "com.drivesmart.ia.BUBBLE_VISIBILITY_CHANGED"
        const val PREF_BUBBLE_ENABLED = "bubble_enabled"
        private const val PREF_BUBBLE_X = "bubble_x"
        private const val PREF_BUBBLE_Y = "bubble_y"
    }

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var receiverRegistered = false
    private var lastSignature = ""
    private var lastDetectedAt = 0L
    private var recentAccessibilityText = ""
    private var recentTextAt = 0L

    private val visibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncBubbleVisibility()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter(ACTION_BUBBLE_VISIBILITY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(visibilityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(visibilityReceiver, filter)
        }
        receiverRegistered = true
        syncBubbleVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        syncBubbleVisibility()
        val packageName = event.packageName?.toString() ?: return
        if (packageName == packageNameForOwnApp()) return
        val sourcePackage = packageName.lowercase()
        if (!sourcePackage.contains("indriver") && !sourcePackage.contains("indrive")) return

        // A distance and the fare can arrive in separate accessibility events.
        // Read the active window and briefly combine consecutive event text so a later
        // update to the green passenger-distance box is not discarded prematurely.
        val currentText = buildString {
            event.text.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it).append(' ') }
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    appendTreeText(root, this, 0)
                } finally {
                    root.recycle()
                }
            } else {
                event.source?.let { node ->
                    try {
                        appendTreeText(node, this, 0)
                    } finally {
                        node.recycle()
                    }
                }
            }
        }
        if (currentText.isBlank()) return
        val now = System.currentTimeMillis()
        recentAccessibilityText = if (now - recentTextAt <= 3000L) {
            "$recentAccessibilityText $currentText"
        } else {
            currentText
        }.takeLast(12000)
        recentTextAt = now

        val normalized = recentAccessibilityText.replace(Regex("\\s+"), " ").trim()
        val fare = extractFare(normalized) ?: return
        val distances = extractDistances(normalized)
        if (distances.isEmpty()) return

        // inDrive does not always expose the box color through accessibility.
        // Keep the existing blue-first/green-second convention and report when only one
        // distinct distance is exposed instead of pretending the second value was found.
        val kmEmpty = distances.getOrNull(0) ?: 0.0
        val kmPassenger = distances.getOrNull(1) ?: 0.0
        val signature = "$fare|$kmEmpty|$kmPassenger|$sourcePackage"
        if (signature == lastSignature && now - lastDetectedAt < 15000L) return
        lastSignature = signature
        lastDetectedAt = now
        getSharedPreferences("drivesmart_history", MODE_PRIVATE).edit()
            .putString("detected_offer", JSONObject().apply {
                put("fare", fare)
                put("kmEmpty", kmEmpty)
                put("kmPassenger", kmPassenger)
                put("distanceCount", distances.size)
                put("sourcePackage", packageName)
                put("detectedAt", now)
            }.toString())
            .apply()
        bubble?.text = "DS\\n" + 36.toChar() + fare
    }

    private fun appendTreeText(node: android.view.accessibility.AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 9) return
        node.text?.let { out.append(it).append(' ') }
        node.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until minOf(node.childCount, 60)) {
            val child = node.getChild(i) ?: continue
            try {
                appendTreeText(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun extractFare(text: String): Long? {
        val patterns = listOf(
            Regex("(?:CLP|\\\\$)\\\\s*([0-9][0-9., ]{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:tarifa|precio|ganas|recibes)\\\\D{0,12}([0-9][0-9., ]{2,})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val digits = raw.filter { it.isDigit() }
            val value = digits.toLongOrNull() ?: continue
            if (value in 500L..500_000L) return value
        }
        return null
    }

    private fun extractDistances(text: String): List<Double> {
        val normalized = text
            .replace('·', ' ')
            .replace('|', ' ')
            .replace(Regex("\\\\s+"), " ")
        val patterns = listOf(
            Regex("([0-9]+(?:[.,][0-9]+)?)\\\\s*(?:km\\\\b|kil[oó]metros?\\\\b)", RegexOption.IGNORE_CASE),
            Regex("\\\\bkm\\\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
        )
        val found = mutableListOf<Double>()
        for (pattern in patterns) {
            pattern.findAll(normalized).forEach { match ->
                val raw = match.groupValues[1].replace(',', '.')
                val value = raw.toDoubleOrNull()?.takeIf { it in 0.0..300.0 } ?: return@forEach
                if (found.none { kotlin.math.abs(it - value) < 0.0001 }) found.add(value)
            }
        }
        return found.take(2)
    }

    private fun syncBubbleVisibility() {
        val enabled = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
            .getBoolean(PREF_BUBBLE_ENABLED, true)
        if (enabled) showBubble() else hideBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val view = TextView(this).apply {
            text = "DS\\n?"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF147D5A.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(8).toFloat()
            setOnClickListener {
                val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
                val detected = prefs.getString("detected_offer", null)
                startActivity(Intent(this@OfferAccessibilityService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_offer_assistant", true)
                    putExtra("detected_offer", detected)
                })
            }
        }

        val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            dp(58), dp(58),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt(PREF_BUBBLE_X, dp(12)).coerceAtLeast(0)
            y = prefs.getInt(PREF_BUBBLE_Y, dp(220)).coerceAtLeast(0)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = params.x
        var startY = params.y
        var dragged = false
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        // With TOP|END gravity, decreasing screen X means a larger right offset.
                        params.x = (startX - dx.toInt()).coerceAtLeast(0)
                        params.y = (startY + dy.toInt()).coerceAtLeast(0)
                        try { windowManager?.updateViewLayout(touchedView, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        prefs.edit().putInt(PREF_BUBBLE_X, params.x).putInt(PREF_BUBBLE_Y, params.y).apply()
                    } else {
                        touchedView.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            windowManager?.addView(view, params)
            bubble = view
            bubbleParams = params
        } catch (_: Exception) {
            bubble = null
            bubbleParams = null
        }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        bubble = null
        bubbleParams = null
    }

    private fun packageNameForOwnApp() = applicationContext.packageName
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(visibilityReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        hideBubble()
        windowManager = null
        super.onDestroy()
    }
}
