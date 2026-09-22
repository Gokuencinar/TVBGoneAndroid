package com.gokuencinar.iruniversal.ir

import android.content.Context
import android.hardware.ConsumerIrManager

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
        if (supported != null && supported.isNotEmpty()) {
            require(supported.any { carrier in it.minFrequency..it.maxFrequency }) {
                "La portadora " + carrier + " Hz no está dentro de los rangos anunciados por el emisor"
            }
        }

        ir.transmit(carrier, code.durationsMicros.toIntArray())
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
