package com.drivesmart.ia

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_EXPORT_CSV = 4207
    }
    private val inputs = mutableMapOf<String, EditText>()
    private lateinit var result: TextView
    private lateinit var saveButton: Button
    private var latestTrip: JSONObject? = null
    private val preferences by lazy { getSharedPreferences("drivesmart_history", MODE_PRIVATE) }
    private val money = NumberFormat.getCurrencyInstance(Locale("es", "CL")).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }
    private val decimal = NumberFormat.getNumberInstance(Locale("es", "CL")).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(0xFFF4F6F9.toInt())
        }
        val scroll = ScrollView(this).apply { addView(page) }

        page.addView(TextView(this).apply {
            text = "DriveSmartIA"
            textSize = 28f
            setTextColor(0xFF14213D.toInt())
        })
        page.addView(TextView(this).apply {
            text = "Rentabilidad real estimada para viajes inDrive (CLP)"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(18))
        })

        field(page, "Tarifa del viaje (CLP)", "tarifa", "5000")
        field(page, "Kilómetros con pasajero (km)", "km", "8")
        field(page, "Kilómetros sin pasajero (km)", "kmVacio", "0")
        field(page, "Rendimiento del vehículo (km/l)", "rendimiento", "14")
        field(page, "Precio del combustible (CLP/l)", "precio", "1300")

        section(page, "Comisión y costos del vehículo")
        field(page, "Comisión de la plataforma (%)", "comision", "0")
        field(page, "Peajes del viaje (CLP)", "peajes", "0")
        field(page, "Mantención y neumáticos por km (CLP/km)", "mantencionKm", "0")
        field(page, "Depreciación por km (CLP/km)", "depreciacionKm", "0")
        field(page, "Otros costos adicionales (CLP)", "otros", "0")
        section(page, "Objetivo de rentabilidad")
        field(page, "Ganancia mínima deseada por km total (CLP/km)", "objetivoKm", "300")

        page.addView(Button(this).apply {
            text = "Guardar configuración habitual"
            isAllCaps = false
            setOnClickListener { saveVehicleConfiguration() }
        }, params().apply { topMargin = dp(4); bottomMargin = dp(8) })

        page.addView(Button(this).apply {
            text = "Calcular rentabilidad"
            isAllCaps = false
            setOnClickListener { calculate() }
        }, params())

        page.addView(Button(this).apply {
            text = if (preferences.getBoolean(OfferAccessibilityService.PREF_BUBBLE_ENABLED, true))
                "Desactivar botón flotante" else "Activar botón flotante"
            isAllCaps = false
            setOnClickListener {
                val enabled = preferences.getBoolean(OfferAccessibilityService.PREF_BUBBLE_ENABLED, true)
                if (enabled) {
                    preferences.edit().putBoolean(OfferAccessibilityService.PREF_BUBBLE_ENABLED, false).apply()
                    sendBroadcast(Intent(OfferAccessibilityService.ACTION_BUBBLE_VISIBILITY_CHANGED).setPackage(packageName))
                    text = "Activar botón flotante"
                    Toast.makeText(this@MainActivity, "Botón flotante desactivado. La app seguirá disponible.", Toast.LENGTH_SHORT).show()
                } else {
                    preferences.edit().putBoolean(OfferAccessibilityService.PREF_BUBBLE_ENABLED, true).apply()
                    sendBroadcast(Intent(OfferAccessibilityService.ACTION_BUBBLE_VISIBILITY_CHANGED).setPackage(packageName))
                    text = "Desactivar botón flotante"
                    Toast.makeText(this@MainActivity, "Botón flotante activado. Mantén pulsado para moverlo.", Toast.LENGTH_SHORT).show()
                }
            }
        }, params().apply { topMargin = dp(8); bottomMargin = dp(4) })

        page.addView(Button(this).apply {
            text = "Configurar accesibilidad de ofertas"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Accesibilidad de DriveSmartIA")
                    .setMessage("Abre los ajustes para comprobar que el servicio de DriveSmartIA esté activado. La detección solo funciona con el texto que inDrive expone a Android; si no expone el recuadro verde, habrá que revisar el diagnóstico.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Abrir ajustes") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .show()
            }
        }, params().apply { topMargin = dp(4); bottomMargin = dp(8) })

        saveButton = Button(this).apply {
            text = "Guardar viaje en historial"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { saveCurrentTrip() }
        }
        page.addView(saveButton, params().apply { topMargin = dp(8) })

        page.addView(Button(this).apply {
            text = "Ver historial de viajes"
            isAllCaps = false
            setOnClickListener { showHistory() }
        }, params().apply { topMargin = dp(4) })

        page.addView(Button(this).apply {
            text = "Exportar historial a CSV"
            isAllCaps = false
            setOnClickListener { exportHistoryCsv() }
        }, params().apply { topMargin = dp(4) })

        page.addView(Button(this).apply {
            text = "Resumen diario y mensual"
            isAllCaps = false
            setOnClickListener { showPeriodSummary() }
        }, params().apply { topMargin = dp(4) })

        result = TextView(this).apply {
            text = "Introduce los datos y pulsa Calcular."
            textSize = 16f
            setTextColor(0xFF14213D.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        page.addView(result, params().apply { topMargin = dp(14) })
        page.addView(TextView(this).apply {
            text = "Los valores de comisión, mantención y depreciación comienzan en cero: introduce tus costos reales. Los kilómetros sin pasajero también consumen combustible y generan desgaste. El historial se guarda localmente en este teléfono. El resultado es una estimación, no una garantía de ganancia."
            textSize = 12f
            setTextColor(0xFF606875.toInt())
            setPadding(0, dp(14), 0, 0)
        })
        setContentView(scroll)
        handleDetectedOffer(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) { setIntent(intent); handleDetectedOffer(intent) }
    }

    private fun handleDetectedOffer(intent: Intent?) {
        if (intent?.getBooleanExtra("open_offer_assistant", false) != true) return
        val raw = intent.getStringExtra("detected_offer") ?: preferences.getString("detected_offer", null)
        if (raw.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("Asistente de viaje")
                .setMessage("No pude reconocer una oferta. Introduce manualmente tarifa y kilómetros y pulsa Calcular rentabilidad.")
                .setPositiveButton("Entendido", null).show()
            return
        }
        try {
            val offer = JSONObject(raw)
            val fare = offer.optLong("fare", 0L)
            val separated = offer.has("kmEmpty") && offer.has("kmPassenger")
            val kmEmpty = if (separated) offer.optDouble("kmEmpty", 0.0) else 0.0
            val kmPassenger = if (separated) offer.optDouble("kmPassenger", 0.0) else offer.optDouble("km", 0.0)
            val distanceCount = offer.optInt("distanceCount", if (separated) 2 else 1)
            if (fare > 0 && (kmEmpty > 0 || kmPassenger > 0)) {
                inputs["tarifa"]?.setText(fare.toString())
                inputs["km"]?.setText(kmPassenger.toString())
                inputs["kmVacio"]?.setText(kmEmpty.toString())
                calculate()
                val note = if (distanceCount < 2) "Solo se detectó una distancia; comprueba ambos recuadros antes de decidir." else "Oferta detectada: azul = sin pasajero; verde = con pasajero. Revisa la tarifa sugerida."
                Toast.makeText(this, note, Toast.LENGTH_LONG).show()
            } else Toast.makeText(this, "Oferta incompleta; revisa tarifa y ambos recuadros de kilómetros manualmente.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, "No se pudo interpretar la oferta; ingrésala manualmente.", Toast.LENGTH_LONG).show() }
        intent.removeExtra("open_offer_assistant")
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xFF14213D.toInt())
            setPadding(0, dp(10), 0, dp(8))
        })
    }

    private fun field(parent: LinearLayout, label: String, key: String, initial: String) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF252B36.toInt())
        })
        val savedConfig = preferences.getString("vehicle_config_$key", null)
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(savedConfig ?: initial)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0"
            contentDescription = label
        }
        inputs[key] = input
        parent.addView(input, params().apply {
            topMargin = dp(2)
            bottomMargin = dp(10)
        })
    }

    private fun saveVehicleConfiguration() {
        val keys = listOf(
            "rendimiento", "precio", "comision",
            "mantencionKm", "depreciacionKm", "objetivoKm"
        )
        val values = keys.associateWith { key ->
            inputs[key]?.text?.toString()?.trim().orEmpty()
        }

        fun parseValue(raw: String): Double? {
            if (raw.isBlank()) return null
            val normalized = if (raw.contains(',') && !raw.contains('.')) {
                raw.replace(',', '.')
            } else {
                raw.replace(",", "")
            }
            return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        for (key in keys) {
            val value = parseValue(values[key].orEmpty())
            if (value == null || value < 0 ||
                (key == "rendimiento" && value <= 0) ||
                (key == "comision" && value > 100)
            ) {
                Toast.makeText(
                    this,
                    "Revisa la configuración: rendimiento mayor que cero y comisión entre 0 y 100%.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val editor = preferences.edit()
        for (key in keys) {
            editor.putString("vehicle_config_$key", values[key])
        }
        editor.apply()
        Toast.makeText(this, "Configuración habitual guardada en este teléfono.", Toast.LENGTH_SHORT).show()
    }

    private fun calculate() {
        latestTrip = null
        saveButton.isEnabled = false

        fun number(key: String): Double? {
            val raw = inputs[key]?.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) return 0.0
            val normalized = if (raw.contains(',') && !raw.contains('.')) {
                raw.replace(',', '.')
            } else {
                raw.replace(",", "")
            }
            return normalized.toDoubleOrNull()
        }

        val fare = number("tarifa")
        val kmPassenger = number("km")
        val kmEmpty = number("kmVacio")
        val efficiency = number("rendimiento")
        val fuelPrice = number("precio")
        val commissionPercent = number("comision")
        val tolls = number("peajes")
        val maintenancePerKm = number("mantencionKm")
        val depreciationPerKm = number("depreciacionKm")
        val others = number("otros")
        val targetPerKm = number("objetivoKm")
        val values = listOf(
            fare, kmPassenger, kmEmpty, efficiency, fuelPrice, commissionPercent,
            tolls, maintenancePerKm, depreciationPerKm, others, targetPerKm
        )

        if (values.any { it == null || !it.isFinite() }) {
            result.text = "Revisa los datos: introduce números válidos."
            return
        }
        val fareValue = fare ?: return
        val kmPassengerValue = kmPassenger ?: return
        val kmEmptyValue = kmEmpty ?: return
        val efficiencyValue = efficiency ?: return
        val fuelPriceValue = fuelPrice ?: return
        val commissionPercentValue = commissionPercent ?: return
        val tollsValue = tolls ?: return
        val maintenancePerKmValue = maintenancePerKm ?: return
        val depreciationPerKmValue = depreciationPerKm ?: return
        val othersValue = others ?: return
        val targetPerKmValue = targetPerKm ?: return

        if (fareValue < 0 || kmPassengerValue < 0 || kmEmptyValue < 0 || efficiencyValue <= 0 ||
            fuelPriceValue < 0 || commissionPercentValue < 0 || commissionPercentValue > 100 ||
            tollsValue < 0 || maintenancePerKmValue < 0 || depreciationPerKmValue < 0 ||
            othersValue < 0 || targetPerKmValue < 0
        ) {
            result.text = "Revisa los valores: no pueden ser negativos, el rendimiento debe ser mayor que cero y la comisión debe estar entre 0 y 100%."
            return
        }

        val totalKm = kmPassengerValue + kmEmptyValue
        val fuelCost = (totalKm / efficiencyValue) * fuelPriceValue
        val commissionCost = fareValue * commissionPercentValue / 100.0
        val maintenanceCost = totalKm * maintenancePerKmValue
        val depreciationCost = totalKm * depreciationPerKmValue
        val totalCosts = fuelCost + commissionCost + tollsValue + maintenanceCost + depreciationCost + othersValue
        val net = fareValue - totalCosts
        val margin = if (fareValue > 0) net / fareValue * 100.0 else 0.0
        val perKm = if (totalKm > 0) net / totalKm else 0.0
        val baseCostsWithoutCommission = fuelCost + tollsValue + maintenanceCost + depreciationCost + othersValue
        val viableFare = if (commissionPercentValue < 100.0) {
            (baseCostsWithoutCommission + targetPerKmValue * totalKm) / (1.0 - commissionPercentValue / 100.0)
        } else Double.POSITIVE_INFINITY
        val recommendation = when {
            net <= 0 -> "NO CONVIENE: la ganancia estimada no es positiva."
            totalKm <= 0 -> "NO SE PUEDE EVALUAR: introduce kilómetros mayores que cero."
            perKm < targetPerKmValue -> "BAJO TU OBJETIVO: revisa la tarifa, distancia o costos."
            else -> "CUMPLE TU OBJETIVO: alcanza tu mínimo de ganancia por km."
        }

        result.text = "RESULTADO ESTIMADO\n\n" +
            "Tarifa del viaje: ${money.format(fareValue)}\n" +
            "Distancia con pasajero: ${decimal.format(kmPassengerValue)} km\n" +
            "Distancia sin pasajero: ${decimal.format(kmEmptyValue)} km\n" +
            "Distancia total considerada: ${decimal.format(totalKm)} km\n\n" +
            "Combustible: ${money.format(fuelCost)}\n" +
            "Comisión plataforma: ${money.format(commissionCost)}\n" +
            "Peajes: ${money.format(tollsValue)}\n" +
            "Mantención/neumáticos: ${money.format(maintenanceCost)}\n" +
            "Depreciación: ${money.format(depreciationCost)}\n" +
            "Otros costos: ${money.format(othersValue)}\n" +
            "Costos considerados: ${money.format(totalCosts)}\n\n" +
            "GANANCIA ESTIMADA: ${money.format(net)}\n" +
            "Ganancia por km total: ${money.format(perKm)}\n" +
            "Objetivo mínimo: ${money.format(targetPerKmValue)} por km\n" +
            "TARIFA MÍNIMA SUGERIDA: ${if (viableFare.isFinite()) money.format(kotlin.math.ceil(viableFare)) else "No calculable con comisión del 100%"}\n" +
            "Evaluación del viaje: ${recommendation}\n" +
            "Margen sobre la tarifa: ${decimal.format(margin)}%"

        latestTrip = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("fare", fareValue)
            put("kmPassenger", kmPassengerValue)
            put("kmEmpty", kmEmptyValue)
            put("totalKm", totalKm)
            put("fuelCost", fuelCost)
            put("commissionCost", commissionCost)
            put("tolls", tollsValue)
            put("maintenanceCost", maintenanceCost)
            put("depreciationCost", depreciationCost)
            put("others", othersValue)
            put("totalCosts", totalCosts)
            put("net", net)
            put("perKm", perKm)
            put("margin", margin)
            put("targetPerKm", targetPerKmValue)
            put("recommendation", recommendation)
        }
        saveButton.isEnabled = true
    }

    private fun saveCurrentTrip() {
        val trip = latestTrip
        if (trip == null) {
            Toast.makeText(this, "Primero calcula un viaje válido.", Toast.LENGTH_SHORT).show()
            return
        }
        val history = readHistory()
        history.put(trip)
        preferences.edit().putString("trips", history.toString()).apply()
        latestTrip = null
        saveButton.isEnabled = false
        Toast.makeText(this, "Viaje guardado en el historial.", Toast.LENGTH_SHORT).show()
    }

    private fun readHistory(): JSONArray {
        val raw = preferences.getString("trips", "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun showHistory() {
        val history = readHistory()
        if (history.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("Historial de viajes")
                .setMessage("Todavía no has guardado viajes. Calcula uno y pulsa «Guardar viaje en historial».")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        var totalRevenue = 0.0
        var totalCosts = 0.0
        var totalProfit = 0.0
        var totalKm = 0.0
        val entries = StringBuilder()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "CL"))

        for (i in history.length() - 1 downTo 0) {
            val trip = history.optJSONObject(i) ?: continue
            val fare = trip.optDouble("fare")
            val costs = trip.optDouble("totalCosts")
            val profit = trip.optDouble("net")
            val km = trip.optDouble("totalKm")
            totalRevenue += fare
            totalCosts += costs
            totalProfit += profit
            totalKm += km

            val date = dateFormat.format(Date(trip.optLong("timestamp")))
            entries.append(date).append("\n")
                .append("Tarifa: ").append(money.format(fare))
                .append(" | Costos: ").append(money.format(costs)).append("\n")
                .append("Ganancia: ").append(money.format(profit))
                .append(" | Distancia: ").append(decimal.format(km)).append(" km\n")
                .append("Ganancia/km: ").append(money.format(trip.optDouble("perKm"))).append("\n")
                .append(trip.optString("recommendation")).append("\n\n")
        }

        val summary = "VIAJES GUARDADOS: ${history.length()}\n" +
            "Ingresos totales: ${money.format(totalRevenue)}\n" +
            "Costos totales: ${money.format(totalCosts)}\n" +
            "GANANCIA ACUMULADA: ${money.format(totalProfit)}\n" +
            "Kilómetros acumulados: ${decimal.format(totalKm)} km\n\n" +
            "DETALLE (más recientes primero)\n\n" + entries

        val content = TextView(this).apply {
            text = summary
            textSize = 15f
            setTextColor(0xFF14213D.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Historial de viajes")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar historial") { _, _ -> confirmClearHistory() }
            .show()
    }

    private fun showPeriodSummary() {
        val history = readHistory()
        if (history.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("Resumen diario y mensual")
                .setMessage("Guarda algunos viajes para ver tus ingresos, costos y ganancias por período.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        // Cada grupo guarda: cantidad de viajes, ingresos, costos, ganancia y kilómetros.
        val daily = linkedMapOf<String, DoubleArray>()
        val monthly = linkedMapOf<String, DoubleArray>()
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.ROOT)
        val dayLabelFormat = SimpleDateFormat("EEE dd/MM", Locale("es", "CL"))
        val monthLabelFormat = SimpleDateFormat("MMMM yyyy", Locale("es", "CL"))

        for (i in 0 until history.length()) {
            val trip = history.optJSONObject(i) ?: continue
            val timestamp = trip.optLong("timestamp")
            val date = Date(timestamp)
            val values = doubleArrayOf(
                1.0,
                trip.optDouble("fare"),
                trip.optDouble("totalCosts"),
                trip.optDouble("net"),
                trip.optDouble("totalKm")
            )
            val dayKey = dayKeyFormat.format(date)
            val monthKey = monthKeyFormat.format(date)
            val dayTotals = daily.getOrPut(dayKey) { DoubleArray(5) }
            val monthTotals = monthly.getOrPut(monthKey) { DoubleArray(5) }
            for (index in values.indices) {
                dayTotals[index] += values[index]
                monthTotals[index] += values[index]
            }
        }

        val todayKey = dayKeyFormat.format(Date())
        val currentMonthKey = monthKeyFormat.format(Date())
        val today = daily[todayKey] ?: DoubleArray(5)
        val thisMonth = monthly[currentMonthKey] ?: DoubleArray(5)

        fun formatTotals(label: String, values: DoubleArray): String {
            return "$label\n" +
                "Viajes: ${values[0].toInt()}\n" +
                "Ingresos: ${money.format(values[1])}\n" +
                "Costos: ${money.format(values[2])}\n" +
                "Ganancia: ${money.format(values[3])}\n" +
                "Kilómetros: ${decimal.format(values[4])} km\n"
        }

        val detail = StringBuilder()
        detail.append("ÚLTIMOS DÍAS CON VIAJES\n")
        daily.entries.toList().asReversed().take(7).forEach { (key, values) ->
            val parsedDate = try {
                dayKeyFormat.parse(key)
            } catch (_: Exception) {
                null
            }
            val label = parsedDate?.let { dayLabelFormat.format(it) } ?: key
            detail.append("\n").append(formatTotals(label, values))
        }

        detail.append("\nÚLTIMOS MESES CON VIAJES\n")
        monthly.entries.toList().asReversed().take(6).forEach { (key, values) ->
            val parsedDate = try {
                monthKeyFormat.parse(key)
            } catch (_: Exception) {
                null
            }
            val label = parsedDate?.let { monthLabelFormat.format(it) } ?: key
            detail.append("\n").append(formatTotals(label, values))
        }

        val summary = formatTotals("HOY", today) + "\n" +
            formatTotals("ESTE MES", thisMonth) + "\n" + detail
        val content = TextView(this).apply {
            text = summary
            textSize = 15f
            setTextColor(0xFF14213D.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Resumen diario y mensual")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun exportHistoryCsv() {
        val history = readHistory()
        if (history.length() == 0) {
            Toast.makeText(this, "No hay viajes para exportar.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "DriveSmartIA_historial.csv")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_CSV)
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el selector para guardar el CSV.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Usa el selector de documentos de Android para exportar el historial.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_CSV || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val history = readHistory()
        if (history.length() == 0) {
            Toast.makeText(this, "No hay viajes para exportar.", Toast.LENGTH_SHORT).show()
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("es", "CL"))
        val csv = StringBuilder()
        // El punto y coma facilita la apertura en hojas de cálculo con configuración regional española.
        csv.append("Fecha;Tarifa_CLP;Km_con_pasajero;Km_sin_pasajero;Km_totales;Combustible_CLP;Comision_CLP;Peajes_CLP;Mantencion_CLP;Depreciacion_CLP;Otros_costos_CLP;Costos_totales_CLP;Ganancia_neta_CLP;Ganancia_por_km_CLP;Margen_porcentaje;Objetivo_por_km_CLP;Evaluacion\r\n")

        for (i in 0 until history.length()) {
            val trip = history.optJSONObject(i) ?: continue
            val date = dateFormat.format(Date(trip.optLong("timestamp")))
            val values = listOf(
                date,
                trip.optDouble("fare").toString(),
                trip.optDouble("kmPassenger").toString(),
                trip.optDouble("kmEmpty").toString(),
                trip.optDouble("totalKm").toString(),
                trip.optDouble("fuelCost").toString(),
                trip.optDouble("commissionCost").toString(),
                trip.optDouble("tolls").toString(),
                trip.optDouble("maintenanceCost").toString(),
                trip.optDouble("depreciationCost").toString(),
                trip.optDouble("others").toString(),
                trip.optDouble("totalCosts").toString(),
                trip.optDouble("net").toString(),
                trip.optDouble("perKm").toString(),
                trip.optDouble("margin").toString(),
                trip.optDouble("targetPerKm").toString(),
                trip.optString("recommendation")
            )
            csv.append(values.joinToString(";") { csvCell(it) }).append("\r\n")
        }

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                // BOM UTF-8 para mejorar la detección de acentos en Excel.
                output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                output.write(csv.toString().toByteArray(StandardCharsets.UTF_8))
            } ?: throw IllegalStateException("No se pudo abrir el archivo")
            Toast.makeText(this, "Historial exportado correctamente a CSV.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "No se pudo guardar el CSV: ${error.localizedMessage ?: "error de escritura"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(';') || escaped.contains('"') ||
            escaped.contains('\n') || escaped.contains('\r')
        ) "\"$escaped\"" else escaped
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Borrar historial")
            .setMessage("Se eliminarán todos los viajes guardados en este teléfono. Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                preferences.edit().remove("trips").apply()
                Toast.makeText(this, "Historial borrado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
