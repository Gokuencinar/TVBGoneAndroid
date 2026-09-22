package com.gokuencinar.iruniversal.ir

object IrProtocolEncoder {
    fun encode(
        protocolName: String,
        address: List<Int>,
        command: List<Int>,
        id: String
    ): IrCode? {
        val p = protocolName.uppercase().filter { it.isLetterOrDigit() }
        return when (p) {
            "NEC" -> nec(address, command, id)
            "NECEXT", "NECEXTENDED" -> necExtended(address, command, id)
            "SAMSUNG32" -> samsung32(address, command, id)
            "SIRC", "SONY12" -> sirc(address, command, 12, id)
            "SIRC15", "SONY15" -> sirc(address, command, 15, id)
            "SIRC20", "SONY20" -> sirc(address, command, 20, id)
            "RC5", "RC5X" -> rc5(address, command, id)
            "RC6", "RC6MODE0" -> rc6(address, command, id)
            "JVC" -> jvc(address, command, id)
            "KASEIKYO" -> kaseikyo(address, command, id)
            "RCA" -> rca(address, command, id)
            "PIONEER" -> pioneer(address, command, id)
            else -> null
        }
    }

    private fun pulseDistance(
        headerMark: Int,
        headerSpace: Int,
        value: Long,
        bits: Int,
        bitMark: Int,
        zeroSpace: Int,
        oneSpace: Int,
        trailingMark: Int? = null
    ): List<Int> = buildList {
        add(headerMark); add(headerSpace)
        repeat(bits) { bit ->
            add(bitMark)
            add(if (((value shr bit) and 1L) == 1L) oneSpace else zeroSpace)
        }
        trailingMark?.let(::add)
    }

    private fun littleEndianValue(bytes: List<Int>): Long {
        var value = 0L
        bytes.take(8).forEachIndexed { index, byte ->
            value = value or ((byte and 0xFF).toLong() shl (index * 8))
        }
        return value
    }

    private fun nec(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val a = address.firstOrNull() ?: return null
        val c = command.firstOrNull() ?: return null
        val bytes = listOf(a, a.inv() and 0xFF, c, c.inv() and 0xFF)
        return IrCode(id, 38_222, pulseDistance(
            9000, 4500, littleEndianValue(bytes), 32, 562, 562, 1687, 562
        ))
    }

    private fun necExtended(address: List<Int>, command: List<Int>, id: String): IrCode? {
        if (address.size < 2 || command.size < 2) return null
        val bytes = address.take(2) + command.take(2)
        return IrCode(id, 38_400, pulseDistance(
            9000, 4500, littleEndianValue(bytes), 32, 562, 562, 1687, 562
        ))
    }

    private fun samsung32(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val a = address.firstOrNull() ?: return null
        val c = command.firstOrNull() ?: return null
        val bytes = listOf(a, a, c, c.inv() and 0xFF)
        return IrCode(id, 38_000, pulseDistance(
            4500, 4500, littleEndianValue(bytes), 32, 550, 550, 1650, 550
        ))
    }

    private fun sirc(address: List<Int>, command: List<Int>, bits: Int, id: String): IrCode? {
        val c = command.firstOrNull() ?: return null
        val addressBits = when (bits) { 12 -> 5; 15 -> 8; 20 -> 13; else -> return null }
        val mask = (1L shl addressBits) - 1
        val payload = (c and 0x7F).toLong() or ((littleEndianValue(address) and mask) shl 7)
        val all = mutableListOf<Int>()
        repeat(3) {
            val frame = mutableListOf(2400, 600)
            repeat(bits) { bit ->
                frame += if (((payload shr bit) and 1L) == 1L) 1200 else 600
                frame += 600
            }
            frame.removeLast()
            val used = frame.sum()
            frame += if (used < 45_000) 45_000 - used else 1
            all += frame
        }
        return IrCode(id, 40_000, all)
    }

    private fun rc5(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val addressByte = address.firstOrNull() ?: return null
        val commandByte = command.firstOrNull() ?: return null
        val addr = addressByte and 0x1F
        val cmd = commandByte and 0x7F
        val fieldBit = cmd < 0x40
        val cmd6 = cmd and 0x3F
        val bits = mutableListOf(true, fieldBit, false)
        for (shift in 4 downTo 0) bits += ((addr shr shift) and 1) != 0
        for (shift in 5 downTo 0) bits += ((cmd6 shr shift) and 1) != 0

        val halfLevels = mutableListOf<Boolean>()
        bits.forEach { bit -> halfLevels += !bit; halfLevels += bit }
        if (halfLevels.size <= 1) return null

        val frame = mutableListOf<Int>()
        var current = halfLevels[1]
        var duration = 889
        for (index in 2 until halfLevels.size) {
            if (halfLevels[index] == current) duration += 889
            else {
                frame += duration
                current = halfLevels[index]
                duration = 889
            }
        }
        frame += duration
        val used = frame.sum()
        if (used < 114_000) {
            val gap = 114_000 - used
            if (frame.size % 2 == 0) frame[frame.lastIndex] += gap else frame += gap
        }
        val all = buildList { repeat(3) { addAll(frame) } }
        return IrCode(id, 36_000, all)
    }

    private fun rc6(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val a = address.firstOrNull() ?: return null
        val c = command.firstOrNull() ?: return null
        val payload = ((a and 0xFF) shl 8) or (c and 0xFF)
        val bits = mutableListOf(true, false, false, false, false)
        for (shift in 15 downTo 0) bits += ((payload shr shift) and 1) != 0

        val pattern = mutableListOf<Int>()
        var lastWasMark: Boolean? = null
        fun add(mark: Boolean, duration: Int) {
            if (pattern.isNotEmpty() && lastWasMark == mark) pattern[pattern.lastIndex] += duration
            else { pattern += duration; lastWasMark = mark }
        }
        add(true, 2664); add(false, 888)
        bits.forEachIndexed { index, bit ->
            val half = if (index == 4) 888 else 444
            add(bit, half); add(!bit, half)
        }
        add(false, 2664)
        return IrCode(id, 36_000, pattern)
    }

    private fun jvc(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val a = address.firstOrNull() ?: return null
        val c = command.firstOrNull() ?: return null
        return IrCode(id, 38_000, pulseDistance(
            8400, 4200, littleEndianValue(listOf(a, c)), 16, 525, 525, 1575, 525
        ))
    }

    private fun kaseikyo(address: List<Int>, command: List<Int>, id: String): IrCode? {
        if (address.size < 4 || command.size < 2) return null
        val addressValue = littleEndianValue(address.take(4))
        val commandValue = littleEndianValue(command.take(2))
        val deviceId = ((addressValue shr 24) and 0x03).toInt()
        val vendorId = ((addressValue shr 8) and 0xFFFF).toInt()
        val genre1 = ((addressValue shr 4) and 0x0F).toInt()
        val genre2 = (addressValue and 0x0F).toInt()
        val data0 = vendorId and 0xFF
        val data1 = (vendorId shr 8) and 0xFF
        var vendorParity = data0 xor data1
        vendorParity = (vendorParity and 0x0F) xor (vendorParity shr 4)
        val data2 = (vendorParity and 0x0F) or (genre1 shl 4)
        val data3 = genre2 or (((commandValue and 0x0F).toInt()) shl 4)
        val data4 = (deviceId shl 6) or (((commandValue shr 4) and 0x3F).toInt())
        val data5 = data2 xor data3 xor data4
        val value = littleEndianValue(listOf(data0, data1, data2, data3, data4, data5))
        return IrCode(id, 38_000, pulseDistance(
            3456, 1728, value, 48, 432, 432, 1296, 432
        ))
    }

    private fun rca(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val addr = (address.firstOrNull() ?: return null) and 0x0F
        val cmd = (command.firstOrNull() ?: return null) and 0xFF
        var payload = addr.toLong()
        payload = payload or (cmd.toLong() shl 4)
        payload = payload or (((addr.inv() and 0x0F).toLong()) shl 12)
        payload = payload or (((cmd.inv() and 0xFF).toLong()) shl 16)
        return IrCode(id, 38_000, pulseDistance(
            4000, 4000, payload, 24, 500, 1000, 2000, 500
        ))
    }

    private fun pioneer(address: List<Int>, command: List<Int>, id: String): IrCode? {
        val addr = address.firstOrNull() ?: return null
        val cmd = command.firstOrNull() ?: return null
        val data = listOf(addr, addr.inv() and 0xFF, cmd, cmd.inv() and 0xFF, 0)
        val frame = pulseDistance(
            8500, 4225, littleEndianValue(data), 33, 500, 500, 1500, 500
        ).toMutableList()
        if (frame.size % 2 == 1) frame += 26_000 else frame[frame.lastIndex] += 26_000
        return IrCode(id, 40_000, frame + frame)
    }
}
