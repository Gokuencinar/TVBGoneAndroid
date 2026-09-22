package com.gokuencinar.iruniversal.learn

import com.gokuencinar.iruniversal.ir.IrCode
import kotlin.math.abs

object IrSignalAnalyzer {
    data class Analysis(
        val protocolHint: String,
        val confidence: Int,
        val carrierHz: Int,
        val segments: Int
    )

    fun analyze(code: IrCode): Analysis {
        val d = code.durationsMicros
        if (d.size < 4) return Analysis("RAW / desconocido", 10, code.effectiveCarrierHz, d.size)

        fun near(value: Int, target: Int, tolerance: Double = 0.30): Boolean =
            abs(value - target) <= target * tolerance

        val hint: Pair<String, Int> = when {
            near(d[0], 9000) && near(d[1], 4500) -> "NEC / NEC Extended" to 90
            near(d[0], 4500) && near(d[1], 4500) -> "Samsung32" to 88
            near(d[0], 8400) && near(d[1], 4200) -> "JVC" to 86
            near(d[0], 2400) && near(d[1], 600) -> "Sony SIRC" to 84
            near(d[0], 3456) && near(d[1], 1728) -> "Kaseikyo / Panasonic" to 82
            near(d[0], 4000) && near(d[1], 4000) -> "RCA" to 80
            near(d[0], 8500) && near(d[1], 4225) -> "Pioneer" to 80
            code.effectiveCarrierHz in 35_000..37_000 -> "RC5 / RC6 probable" to 45
            else -> "RAW / desconocido" to 25
        }
        return Analysis(hint.first, hint.second, code.effectiveCarrierHz, d.size)
    }
}
