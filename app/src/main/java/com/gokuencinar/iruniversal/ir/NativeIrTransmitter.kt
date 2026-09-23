package com.gokuencinar.iruniversal.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import kotlin.math.abs

class NativeIrTransmitter(context: Context) : IrTransmitter {
    private val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    override val name: String = "IR integrado"

    override fun isAvailable(): Boolean = manager?.hasIrEmitter() == true

    override fun send(code: IrCode) {
        val ir = manager ?: error("Servicio Consumer IR no disponible")
        check(ir.hasIrEmitter()) { "Este dispositivo no tiene emisor IR integrado" }
        require(code.durationsMicros.isNotEmpty()) { "Patrón IR vacío" }
        require(code.durationsMicros.all { it > 0 }) { "El patrón contiene duraciones inválidas" }

        val totalMicros = code.durationsMicros.sumOf { it.toLong() }
        require(totalMicros < 2_000_000L) {
            "Android limita cada transmisión Consumer IR a menos de 2 segundos"
        }

        val carrier = code.effectiveCarrierHz
        val supported = ir.carrierFrequencies
        val transmitCarrier = if (supported != null && supported.isNotEmpty() &&
            supported.none { carrier in it.minFrequency..it.maxFrequency }
        ) {
            val nearest = supported
                .flatMap { listOf(it.minFrequency, it.maxFrequency) }
                .minByOrNull { abs(it - carrier) }
                ?: carrier
            val maxAdjustment = (carrier * 0.05).toInt().coerceAtLeast(1)
            require(abs(nearest - carrier) <= maxAdjustment) {
                "La portadora " + carrier + " Hz está demasiado lejos de los rangos anunciados por el emisor"
            }
            nearest
        } else carrier

        ir.transmit(transmitCarrier, code.durationsMicros.toIntArray())
    }

    override fun diagnostics(): String {
        val ir = manager ?: return "Consumer IR: servicio no disponible"
        if (!ir.hasIrEmitter()) return "Consumer IR: sin emisor integrado"
        val ranges = ir.carrierFrequencies
            ?.joinToString { it.minFrequency.toString() + "-" + it.maxFrequency + " Hz" }
            ?: "rangos no informados"
        return "Consumer IR: disponible · " + ranges
    }
}
