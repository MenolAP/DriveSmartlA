package com.drivesmart.ia

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Floating quick-access bubble and best-effort inDrive offer reader.
 * Only reads accessibility text exposed by the foreground app; no screenshots are captured.
 */
class OfferAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var lastSignature = ""
    private var lastDetectedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == packageNameForOwnApp()) return
        val text = buildString {
            event.text.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it).append(' ') }
            event.source?.let { node ->
                try {
                    appendTreeText(node, this, 0)
                } finally {
                    node.recycle()
                }
            }
        }
        if (text.isBlank()) return
        val normalized = text.replace("\\s+".toRegex(), " ").trim()
        val fare = extractFare(normalized) ?: return
        val km = extractKm(normalized) ?: return
        val signature = "$fare|$km|$packageName"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastDetectedAt < 15_000L) return
        lastSignature = signature
        lastDetectedAt = now
        getSharedPreferences("drivesmart_history", MODE_PRIVATE).edit()
            .putString("detected_offer", JSONObject().apply {
                put("fare", fare)
                put("km", km)
                put("sourcePackage", packageName)
                put("detectedAt", now)
            }.toString())
            .apply()
        bubble?.text = "DS\n$$fare"
    }

    private fun appendTreeText(node: android.view.accessibility.AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 5) return
        node.text?.let { out.append(it).append(' ') }
        node.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until minOf(node.childCount, 35)) {
            val child = node.getChild(i) ?: continue
            try { appendTreeText(child, out, depth + 1) } finally { child.recycle() }
        }
    }

    private fun extractFare(text: String): Long? {
        val patterns = listOf(
            Regex("(?:CLP|\\$)\\s*([0-9][0-9., ]{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:tarifa|precio|ganas|recibes)\\D{0,12}([0-9][0-9., ]{2,})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val digits = raw.filter { it.isDigit() }
            val value = digits.toLongOrNull() ?: continue
            if (value in 500L..500_000L) return value
        }
        return null
    }

    private fun extractKm(text: String): Double? {
        val match = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km|kil[oó]metros?)", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.1..300.0 }
    }

    private fun showBubble() {
        if (bubble != null) return
        val view = TextView(this).apply {
            text = "DS\n?"
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
                startActivity(android.content.Intent(this@OfferAccessibilityService, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_offer_assistant", true)
                    putExtra("detected_offer", detected)
                })
            }
        }
        val params = WindowManager.LayoutParams(
            dp(58), dp(58),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(220)
        }
        try {
            windowManager?.addView(view, params)
            bubble = view
        } catch (_: Exception) {
            bubble = null
        }
    }

    private fun packageNameForOwnApp() = applicationContext.packageName
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        bubble?.let { view -> try { windowManager?.removeView(view) } catch (_: Exception) {} }
        bubble = null
        windowManager = null
        super.onDestroy()
    }
}
