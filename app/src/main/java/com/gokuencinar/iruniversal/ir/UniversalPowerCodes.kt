package com.gokuencinar.iruniversal.ir

object UniversalPowerCodes {
    val codes: List<IrCode> = listOfNotNull(
        IrProtocolEncoder.encode("SAMSUNG32", listOf(0x07), listOf(0x02), "universal-samsung-power"),
        rc5("universal-vestel-tdsystems-power", 0x00, 0x0C, 3),
        rc5("universal-telefunken-power", 0x01, 0x0C, 3),
        IrProtocolEncoder.encode("SIRC", listOf(0x01), listOf(0x15), "universal-sony-power"),
        IrProtocolEncoder.encode("NEC", listOf(0x04), listOf(0x08), "universal-nec-04-08"),
        IrProtocolEncoder.encode("RC6", listOf(0x00), listOf(0x0C), "universal-philips-power"),
        IrProtocolEncoder.encode("NEC", listOf(0x19), listOf(0x18), "universal-medion-power"),
        IrProtocolEncoder.encode("NEC", listOf(0x49), listOf(0x1A), "universal-oppo-power"),
        IrProtocolEncoder.encode("NECEXT", listOf(0x00, 0x7F), listOf(0x0A, 0xF5), "universal-denver-power"),
        IrProtocolEncoder.encode("NECEXT", listOf(0x00, 0xBF), listOf(0x0D, 0xF2), "universal-hisense-power"),
        IrProtocolEncoder.encode("NECEXT", listOf(0x00, 0x7F), listOf(0x15, 0xEA), "universal-elitelux-power")
    )

    val vestelTdSystemsTest: IrCode =
        rc5("tdsystems-vestel-rc5-100c", 0x00, 0x0C, 4)
            ?: error("RC5 generation failed")

    private fun rc5(id: String, address: Int, command: Int, repeats: Int): IrCode? {
        val base = IrProtocolEncoder.encode("RC5", listOf(address), listOf(command), id) ?: return null
        val oneFrame = if (base.durationsMicros.size % 3 == 0)
            base.durationsMicros.take(base.durationsMicros.size / 3)
        else base.durationsMicros
        return base.copy(durationsMicros = buildList { repeat(repeats.coerceAtLeast(1)) { addAll(oneFrame) } })
    }
}
