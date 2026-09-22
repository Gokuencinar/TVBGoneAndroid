package com.gokuencinar.iruniversal.ir

import android.content.Context

class AutoIrTransmitter(context: Context) {
    private val native = NativeIrTransmitter(context)
    private val audio = AudioIrTransmitter(context)

    var mode: TransmitterMode = TransmitterMode.AUTO

    fun active(): IrTransmitter = when (mode) {
        TransmitterMode.AUTO -> if (native.isAvailable()) native else audio
        TransmitterMode.NATIVE_IR -> native
        TransmitterMode.AUDIO -> audio
    }

    fun isNativeAvailable(): Boolean = native.isAvailable()
    fun diagnostics(): String = native.diagnostics() + "\n" + audio.diagnostics()
}
