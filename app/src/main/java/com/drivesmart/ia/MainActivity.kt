package com.drivesmart.ia

import android.app.Activity
import android.app.AlertDialog
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
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
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
            text = "Calcular rentabilidad"
            isAllCaps = false
            setOnClickListener { calculate() }
        }, params())

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
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(initial)
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
