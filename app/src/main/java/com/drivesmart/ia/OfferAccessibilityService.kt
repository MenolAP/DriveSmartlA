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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

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
    private var kmPanel: LinearLayout? = null
    private var kmPanelParams: WindowManager.LayoutParams? = null
    private var emptyKmInput: EditText? = null
    private var passengerKmInput: EditText? = null
    private var minimumFareLabel: TextView? = null
    private var panelDragStartX = 0f
    private var panelDragStartY = 0f
    private var panelStartWindowX = 0
    private var panelStartWindowY = 0
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

        val freshNormalized = currentText.replace(Regex("\\s+"), " ").trim()
        val freshDistances = extractDistances(freshNormalized)
        // If this accessibility event already contains a complete offer, do not mix
        // it with distances or fares left over from the previous card.
        val normalized = if (extractFare(freshNormalized) != null && freshDistances.size >= 2) {
            freshNormalized
        } else {
            recentAccessibilityText.replace(Regex("\\s+"), " ").trim()
        }
        val fare = extractFare(normalized) ?: return
        val distances = extractDistances(normalized)
        if (distances.isEmpty()) return

        // inDrive does not always expose the box color through accessibility.
        // Keep the existing blue-first/green-second convention and report when only one
        // distinct distance is exposed instead of pretending the second value was found.
        // Accessibility events may repeat old text for a few seconds. Use the
        // newest distinct distances, not the first ones from a previous offer.
        val currentDistances = distances.takeLast(2)
        val kmEmpty = currentDistances.getOrNull(0) ?: 0.0
        val kmPassenger = currentDistances.getOrNull(1) ?: 0.0
        val signature = "$fare|$kmEmpty|$kmPassenger|$sourcePackage"
        if (signature == lastSignature && now - lastDetectedAt < 15000L) return
        lastSignature = signature
        lastDetectedAt = now
        val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
        val suggestedFare = if (distances.size >= 2) calculateMinimumFare(kmEmpty + kmPassenger) else null
        prefs.edit()
            .putString("detected_offer", JSONObject().apply {
                put("fare", fare)
                put("kmEmpty", kmEmpty)
                put("kmPassenger", kmPassenger)
                put("distanceCount", distances.size)
                if (suggestedFare != null) put("suggestedMinFare", suggestedFare)
                put("sourcePackage", packageName)
                put("detectedAt", now)
            }.toString())
            .apply()
        bubble?.apply {
            text = if (suggestedFare != null) {
                "$" + NumberFormat.getNumberInstance(Locale("es", "CL")).format(suggestedFare) + "\nTARIFA MÍNIMA"
            } else {
                "TARIFA MÍNIMA\nFALTAN DATOS"
            }
            contentDescription = if (suggestedFare != null) {
                "Tarifa mínima sugerida ${suggestedFare} pesos. Toca para ver la oferta."
            } else {
                "No se pudo calcular la tarifa mínima: falta detectar las dos distancias."
            }
        }
    }

    private fun calculateMinimumFare(totalKm: Double): Long? {
        if (!totalKm.isFinite() || totalKm <= 0.0) return null
        val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
        fun config(key: String, fallback: Double): Double {
            val raw = prefs.getString("vehicle_config_$key", null) ?: return fallback
            val normalized = if (raw.contains(',') && !raw.contains('.')) raw.replace(',', '.') else raw.replace(",", "")
            return normalized.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback
        }
        val efficiency = config("rendimiento", 13.0)
        val fuelPrice = config("precio", 1300.0)
        val commissionPercent = config("comision", 10.0)
        val tolls = config("peajes", 0.0)
        val maintenancePerKm = config("mantencionKm", 0.0)
        val depreciationPerKm = config("depreciacionKm", 0.0)
        val others = config("otros", 0.0)
        val targetPerKm = config("objetivoKm", 300.0)
        if (efficiency <= 0.0 || commissionPercent < 0.0 || commissionPercent >= 100.0 ||
            fuelPrice < 0.0 || tolls < 0.0 || maintenancePerKm < 0.0 ||
            depreciationPerKm < 0.0 || others < 0.0 || targetPerKm < 0.0
        ) return null
        val fuelCost = totalKm / efficiency * fuelPrice
        val baseCosts = fuelCost + totalKm * maintenancePerKm + totalKm * depreciationPerKm + tolls + others
        val desiredNet = totalKm * targetPerKm
        val fare = (baseCosts + desiredNet) / (1.0 - commissionPercent / 100.0)
        if (!fare.isFinite() || fare < 0.0 || fare > Long.MAX_VALUE.toDouble()) return null
        return kotlin.math.ceil(fare).toLong()
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
            Regex("(?:CLP|\\$)\\s*([0-9][0-9., ]{2,})", RegexOption.IGNORE_CASE),
            Regex("(?:tarifa|precio|ganas|recibes)\\D{0,12}([0-9][0-9., ]{2,})", RegexOption.IGNORE_CASE)
        )
        val candidates = mutableListOf<Long>()
        for (pattern in patterns) {
            pattern.findAll(text).forEach { match ->
                val raw = match.groupValues.getOrNull(1) ?: return@forEach
                val digits = raw.filter { it.isDigit() }
                val value = digits.toLongOrNull() ?: return@forEach
                if (value in 500L..500_000L && value !in candidates) candidates.add(value)
            }
        }
        // The recent event buffer can contain the previous offer; prefer the latest fare.
        return candidates.lastOrNull()
    }

    private fun extractDistances(text: String): List<Double> {
        val normalized = text
            .replace('·', ' ')
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
        val patterns = listOf(
            Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km\\b|kil[oó]metros?\\b)", RegexOption.IGNORE_CASE),
            Regex("\\bkm\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
        )
        val found = mutableListOf<Double>()
        for (pattern in patterns) {
            pattern.findAll(normalized).forEach { match ->
                val raw = match.groupValues[1].replace(',', '.')
                val value = raw.toDoubleOrNull()?.takeIf { it in 0.0..300.0 } ?: return@forEach
                if (found.none { kotlin.math.abs(it - value) < 0.0001 }) found.add(value)
            }
        }
        return found.takeLast(2)
    }

    private fun syncBubbleVisibility() {
        val enabled = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
            .getBoolean(PREF_BUBBLE_ENABLED, true)
        if (enabled) showBubble() else hideBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val view = TextView(this).apply {
            text = "TARIFA MÍN.\nDetectando…"
            textSize = 13f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF102A43.toInt())
                setStroke(dp(3), 0xFFFFC857.toInt())
            }
            elevation = dp(14).toFloat()
            setOnClickListener { toggleKmPanel() }
        }

        val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            dp(116), dp(76),
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

    private fun toggleKmPanel() {
        // The floating bubble is the single toggle: tap once to open the km panel,
        // tap it again to close the panel without needing the panel's Close button.
        if (kmPanel != null) {
            hideKmPanel()
        } else {
            showKmPanel()
        }
        updateBubblePanelHint()
    }

    private fun updateBubblePanelHint() {
        bubble?.contentDescription = if (kmPanel != null) {
            "Calculadora de kilómetros abierta. Toca otra vez el botón flotante para ocultarla."
        } else {
            "Calculadora de kilómetros cerrada. Toca el botón flotante para abrirla."
        }
    }

    private fun showKmPanel() {
        if (kmPanel != null) return
        val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
        val detected = try {
            JSONObject(prefs.getString("detected_offer", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0xFF102A43.toInt())
                setStroke(dp(2), 0xFFFFC857.toInt())
            }
            elevation = dp(16).toFloat()
        }
        panel.addView(TextView(this).apply {
            text = "CALCULAR TARIFA MÍNIMA"
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = "Ingresa los kilómetros de todo el trayecto"
            textSize = 12f
            setTextColor(0xFFD8E5F0.toInt())
            setPadding(0, dp(2), 0, dp(4))
        })
        fun makeKmInput(label: String, value: Double): EditText {
            return EditText(this).apply {
                hint = label
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(if (value > 0) value.toString() else "")
                textSize = 16f
                setTextColor(0xFF102A43.toInt())
                setHintTextColor(0xFF687786.toInt())
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                }
            }
        }
        val emptyInput = makeKmInput("Km sin pasajero", detected.optDouble("kmEmpty", 0.0))
        val passengerInput = makeKmInput("Km con pasajero", detected.optDouble("kmPassenger", 0.0))
        emptyKmInput = emptyInput
        passengerKmInput = passengerInput
        panel.addView(TextView(this).apply {
            text = "KM SIN PASAJERO"
            textSize = 11f
            setTextColor(0xFFFFC857.toInt())
        })
        panel.addView(emptyInput, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(5) })
        panel.addView(TextView(this).apply {
            text = "KM CON PASAJERO"
            textSize = 11f
            setTextColor(0xFFFFC857.toInt())
        })
        panel.addView(passengerInput, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        val minimum = TextView(this).apply {
            text = "Tarifa mínima: ingresa ambas distancias"
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(6))
        }
        minimumFareLabel = minimum
        panel.addView(minimum, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Cerrar"
            isAllCaps = false
            setOnClickListener { hideKmPanel() }
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(5) })
        actions.addView(Button(this).apply {
            text = "Abrir app"
            isAllCaps = false
            setOnClickListener {
                val current = prefs.getString("detected_offer", null)
                startActivity(Intent(this@OfferAccessibilityService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_offer_assistant", true)
                    putExtra("detected_offer", current)
                })
            }
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        panel.addView(actions)
        val panelWidth = dp(250)
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleX = bubbleParams?.x ?: dp(12)
        val bubbleY = bubbleParams?.y ?: dp(220)
        // Keep the panel to the left of the right-edge bubble so it cannot cover
        // the very button the user needs to tap again to close it.
        val panelRightOffset = (bubbleX + dp(116) + dp(8))
            .coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0))
        val params = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = panelRightOffset
            y = bubbleY
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    panelDragStartX = event.rawX
                    panelDragStartY = event.rawY
                    panelStartWindowX = params.x
                    panelStartWindowY = params.y
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.x = panelStartWindowX + (event.rawX - panelDragStartX).toInt()
                    params.y = (panelStartWindowY + (event.rawY - panelDragStartY).toInt()).coerceAtLeast(dp(8))
                    try { windowManager?.updateViewLayout(panel, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateManualKmFare()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        emptyInput.addTextChangedListener(watcher)
        passengerInput.addTextChangedListener(watcher)
        try {
            windowManager?.addView(panel, params)
            kmPanel = panel
            kmPanelParams = params
            updateBubblePanelHint()
            updateManualKmFare()
        } catch (_: Exception) {
            kmPanel = null
            kmPanelParams = null
        }
    }

    private fun updateManualKmFare() {
        val empty = emptyKmInput?.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val passenger = passengerKmInput?.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val label = minimumFareLabel ?: return
        if (empty == null || passenger == null || empty < 0.0 || passenger < 0.0 ||
            !empty.isFinite() || !passenger.isFinite() || empty + passenger <= 0.0
        ) {
            label.text = "Tarifa mínima: ingresa ambas distancias"
            bubble?.text = "TARIFA MÍNIMA\nFALTAN DATOS"
            return
        }
        val totalKm = empty + passenger
        val fare = calculateMinimumFare(totalKm)
        if (fare == null) {
            label.text = "No se puede calcular. Revisa la configuración del vehículo."
            return
        }
        val formatted = NumberFormat.getNumberInstance(Locale("es", "CL")).format(fare)
        label.text = "TOTAL: " + String.format(Locale("es", "CL"), "%.1f", totalKm) +
            " km\nTARIFA MÍNIMA: $" + formatted
        bubble?.text = "$" + formatted + "\nTARIFA MÍNIMA"
        val prefs = getSharedPreferences("drivesmart_history", MODE_PRIVATE)
        val detected = try {
            JSONObject(prefs.getString("detected_offer", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        detected.put("kmEmpty", empty)
        detected.put("kmPassenger", passenger)
        detected.put("distanceCount", 2)
        detected.put("suggestedMinFare", fare)
        detected.put("manualKmEntry", true)
        detected.put("detectedAt", System.currentTimeMillis())
        prefs.edit().putString("detected_offer", detected.toString()).apply()
    }

    private fun hideKmPanel() {
        val panel = kmPanel ?: return
        try { windowManager?.removeView(panel) } catch (_: Exception) {}
        kmPanel = null
        kmPanelParams = null
        emptyKmInput = null
        passengerKmInput = null
        minimumFareLabel = null
        updateBubblePanelHint()
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
        hideKmPanel()
        hideBubble()
        windowManager = null
        super.onDestroy()
    }
}
