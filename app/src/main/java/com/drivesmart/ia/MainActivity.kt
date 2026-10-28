package com.drivesmart.ia

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {
    private val inputs = mutableMapOf<String, EditText>()
    private lateinit var result: TextView
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

        result = TextView(this).apply {
            text = "Introduce los datos y pulsa Calcular."
            textSize = 16f
            setTextColor(0xFF14213D.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        page.addView(result, params().apply { topMargin = dp(14) })
        page.addView(TextView(this).apply {
            text = "Los valores de comisión, mantención y depreciación comienzan en cero: introduce tus costos reales. Los kilómetros sin pasajero también consumen combustible y generan desgaste. El resultado es una estimación, no una garantía de ganancia."
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
            tolls, maintenancePerKm, depreciationPerKm, others,
            targetPerKm
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
            "Peajes: ${money.format(tolls)}\n" +
            "Mantención/neumáticos: ${money.format(maintenanceCost)}\n" +
            "Depreciación: ${money.format(depreciationCost)}\n" +
            "Otros costos: ${money.format(others)}\n" +
            "Costos considerados: ${money.format(totalCosts)}\n\n" +
            "GANANCIA ESTIMADA: ${money.format(net)}\n" +
            "Ganancia por km total: ${money.format(perKm)}\n" +
            "Objetivo mínimo: ${money.format(targetPerKmValue)} por km\n" +
            "Evaluación del viaje: ${recommendation}\n" +
            "Margen sobre la tarifa: ${decimal.format(margin)}%"
    }

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
