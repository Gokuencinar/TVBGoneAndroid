package com.gokuencinar.iruniversal.ir

interface IrTransmitter {
    val name: String
    fun isAvailable(): Boolean
    @Throws(Exception::class)
    fun send(code: IrCode)
    fun diagnostics(): String
}
