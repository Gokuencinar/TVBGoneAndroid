package com.gokuencinar.iruniversal.flipper

import com.gokuencinar.iruniversal.ir.IrCode
import com.gokuencinar.iruniversal.ir.IrProtocolEncoder
import java.util.UUID

data class ImportedIrSignal(
    val name: String,
    val code: IrCode,
    val sourceDescription: String
)

object FlipperIrCodec {
    fun parse(text: String): List<ImportedIrSignal> {
        val output = mutableListOf<ImportedIrSignal>()
        parseRecords(text).forEach { record ->
            val name = record["name"] ?: return@forEach
            val type = record["type"]?.lowercase() ?: return@forEach
            val id = "imported:" + UUID.randomUUID()

            if (type == "raw") {
                val frequency = record["frequency"]?.toIntOrNull() ?: return@forEach
                val durations = (record["data"] ?: "")
                    .split(Regex("\\s+"))
                    .mapNotNull { it.toIntOrNull() }
                    .filter { it > 0 }
                if (durations.size >= 2) {
                    output += ImportedIrSignal(
                        name,
                        IrCode(id, frequency, durations),
                        "Flipper RAW"
                    )
                }
            } else if (type == "parsed") {
                val protocol = record["protocol"] ?: return@forEach
                val address = parseHexBytes(record["address"].orEmpty())
                val command = parseHexBytes(record["command"].orEmpty())
                val code = IrProtocolEncoder.encode(protocol, address, command, id)
                if (code != null) {
                    output += ImportedIrSignal(name, code, "Flipper " + protocol)
                }
            }
        }
        return output
    }

    fun exportRaw(name: String, code: IrCode): String =
        exportRawRecords(listOf(name to code))

    fun exportRawRecords(records: List<Pair<String, IrCode>>): String {
        val lines = mutableListOf(
            "Filetype: IR signals file",
            "Version: 1"
        )
        records.forEach { (name, code) ->
            val safe = name.replace("\n", " ").replace("\r", " ")
            lines += "#"
            lines += "name: " + safe
            lines += "type: raw"
            lines += "frequency: " + code.effectiveCarrierHz
            lines += "duty_cycle: 0.330000"
            lines += "data: " + code.durationsMicros.joinToString(" ")
        }
        lines += ""
        return lines.joinToString("\n")
    }

    private fun parseRecords(text: String): List<Map<String, String>> {
        val records = mutableListOf<Map<String, String>>()
        var current = linkedMapOf<String, String>()
        var lastKey: String? = null

        fun finish() {
            if (current["name"] != null && current["type"] != null) {
                records += current.toMap()
            }
            current = linkedMapOf()
            lastKey = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                finish()
                return@forEach
            }

            val colon = line.indexOf(':')
            if (colon >= 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (key == "name" && current["name"] != null) finish()
                current[key] = value
                lastKey = key
            } else if (lastKey == "data" && line.all { it.isDigit() || it.isWhitespace() }) {
                current["data"] = current["data"].orEmpty() + " " + line
            }
        }
        finish()
        return records
    }

    private fun parseHexBytes(value: String): List<Int> =
        value.split(Regex("\\s+"))
            .mapNotNull { token -> token.toIntOrNull(16)?.and(0xFF) }
}

object IrdbCsvCodec {
    fun parse(text: String, idPrefix: String = "irdb"): List<ImportedIrSignal> {
        val lines = text.lineSequence().toList()
        if (lines.size <= 1) return emptyList()

        return lines.drop(1).mapIndexedNotNull { index, line ->
            val fields = csv(line)
            if (fields.size < 5) return@mapIndexedNotNull null
            val device = fields[2].trim().toIntOrNull() ?: return@mapIndexedNotNull null
            val sub = fields[3].trim().toIntOrNull() ?: return@mapIndexedNotNull null
            val function = fields[4].trim().toIntOrNull() ?: return@mapIndexedNotNull null
            if (device !in 0..255 || function !in 0..255) return@mapIndexedNotNull null

            val proto = fields[1].uppercase().replace("-", "")
            val id = idPrefix + ":" + index + ":" + UUID.randomUUID()
            val code = when {
                proto.startsWith("NEC") && sub in 0..255 ->
                    IrProtocolEncoder.encode("NECEXT", listOf(device, sub), listOf(function, function.inv() and 0xFF), id)
                proto.startsWith("NEC") ->
                    IrProtocolEncoder.encode("NEC", listOf(device), listOf(function), id)
                proto.startsWith("RC5") ->
                    IrProtocolEncoder.encode("RC5", listOf(device), listOf(function), id)
                proto.startsWith("RC6") ->
                    IrProtocolEncoder.encode("RC6", listOf(device), listOf(function), id)
                proto.contains("SONY") || proto.contains("SIRC") ->
                    IrProtocolEncoder.encode(
                        if (proto.contains("20")) "SIRC20" else if (proto.contains("15")) "SIRC15" else "SIRC",
                        listOf(device), listOf(function), id
                    )
                proto == "JVC" || proto == "RCA" ->
                    IrProtocolEncoder.encode(proto, listOf(device), listOf(function), id)
                else -> null
            } ?: return@mapIndexedNotNull null

            val name = fields[0].trim().ifBlank { "BUTTON_" + function }
            ImportedIrSignal(name, code, "IRDB " + proto)
        }
    }

    private fun csv(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else quoted = !quoted
                }
                ',' -> if (quoted) current.append(c) else {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }
}
