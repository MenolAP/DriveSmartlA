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
            text = "Calculadora de rentabilidad para viajes inDrive (CLP)"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(18))
        })
        field(page, "Tarifa del viaje (CLP)", "tarifa", "5000")
        field(page, "Distancia total (km)", "km", "8")
        field(page, "Rendimiento (km/l)", "rendimiento", "14")
        field(page, "Precio del combustible (CLP/l)", "precio", "1300")
        field(page, "Peajes (CLP)", "peajes", "0")
        field(page, "Otros costos (CLP)", "otros", "0")
        page.addView(Button(this).apply {
            text = "Calcular rentabilidad"
            isAllCaps = false
            setOnClickListener { calculate() }
        }, params())
        result = TextView(this).apply {
            text = "Introduce los datos y pulsa Calcular."
            textSize = 17f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        page.addView(result, params().apply { topMargin = dp(14) })
        page.addView(TextView(this).apply {
            text = "Estimación: incluye combustible, peajes y otros costos ingresados. Añade comisión, mantención y depreciación en Otros costos para una estimación más completa."
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        })
        setContentView(scroll)
    }

    private fun field(parent: LinearLayout, label: String, key: String, initial: String) {
        parent.addView(TextView(this).apply { text = label; textSize = 14f })
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(initial)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0"
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
            val normalized = if (raw.contains(',') && !raw.contains('.')) raw.replace(',', '.') else raw
            return normalized.toDoubleOrNull()
        }
        val fare = number("tarifa")
        val km = number("km")
        val efficiency = number("rendimiento")
        val fuelPrice = number("precio")
        val tolls = number("peajes")
        val others = number("otros")
        if (listOf(fare, km, efficiency, fuelPrice, tolls, others).any { it == null }) {
            result.text = "Revisa los datos: introduce números válidos."
            return
        }
        if (fare!! < 0 || km!! < 0 || efficiency!! <= 0 || fuelPrice!! < 0 || tolls!! < 0 || others!! < 0) {
            result.text = "No uses valores negativos. El rendimiento debe ser mayor que cero."
            return
        }
        val fuel = km / efficiency * fuelPrice
        val costs = fuel + tolls + others
        val net = fare - costs
        val margin = if (fare > 0) net / fare * 100 else 0.0
        result.text = "RESULTADO ESTIMADO\n\n" +
            "Tarifa: ${money.format(fare)}\n" +
            "Combustible: ${money.format(fuel)}\n" +
            "Peajes: ${money.format(tolls)}\n" +
            "Otros costos: ${money.format(others)}\n" +
            "Costos considerados: ${money.format(costs)}\n\n" +
            "Ganancia estimada: ${money.format(net)}\n" +
            "Ganancia por km: ${money.format(if (km > 0) net / km else 0.0)}\n" +
            "Margen: ${String.format(Locale("es", "CL"), "%.1f", margin)}%"
    }

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
