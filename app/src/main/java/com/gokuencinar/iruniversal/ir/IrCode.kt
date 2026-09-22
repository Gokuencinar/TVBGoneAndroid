package com.gokuencinar.iruniversal.ir

data class IrCode(
    val id: String,
    val carrierHz: Int,
    val durationsMicros: List<Int>
) {
    val effectiveCarrierHz: Int get() = if (carrierHz <= 0) 38_000 else carrierHz
    val durationMillis: Long get() = durationsMicros.sumOf { it.toLong() } / 1000L

    val sourceLabel: String
        get() = when {
            id.startsWith("flipper:") -> "Flipper-IRDB"
            id.startsWith("universal-") -> "Universal"
            id.startsWith("code_") || id.contains("EU", true) || id.contains("NA", true) -> "TV-B-Gone"
            else -> "Base IR"
        }

    val brandHint: String
        get() {
            if (id.startsWith("flipper:")) {
                val body = id.removePrefix("flipper:")
                val path = body.substringBefore("#")
                val parts = path.split("/")
                if (parts.size >= 2) return prettify(parts[1])
            }
            if (id.startsWith("universal-")) {
                val ignored = setOf("nec", "rc5", "rc6", "tdsystems", "power")
                val part = id.removePrefix("universal-")
                    .split("-")
                    .firstOrNull { it.lowercase() !in ignored }
                if (part != null) return prettify(part)
            }
            return sourceLabel
        }

    val displayName: String
        get() = when {
            id.startsWith("flipper:") -> {
                val body = id.removePrefix("flipper:")
                val signal = body.substringAfter("#", "Power")
                val filename = body.substringBefore("#").substringAfterLast("/")
                    .removeSuffix(".ir")
                listOf(brandHint, prettify(filename), prettify(signal))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            }
            id.startsWith("universal-") -> prettify(id.removePrefix("universal-").removeSuffix("-power"))
            id.startsWith("code_") -> "TV-B-Gone · $id"
            else -> prettify(id)
        }

    private fun prettify(value: String): String =
        value.replace("_", " ").replace("-", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

enum class DeviceCategory(val title: String, val shortTitle: String) {
    TELEVISION("Televisores", "TV"),
    AIR_CONDITIONER("Aires acondicionados", "Aire"),
    PROJECTOR("Proyectores", "Proyector")
}

enum class TvRegion(val title: String) {
    EUROPE("Europa"),
    NORTH_AMERICA("Norteamérica / Asia")
}

enum class ScanPace(val title: String, val gapMillis: Long) {
    FAST("Rápido", 205L),
    IDENTIFY("Identificar", 800L)
}

enum class TransmitterMode(val title: String) {
    AUTO("Automático"),
    NATIVE_IR("IR integrado"),
    AUDIO("Adaptador de audio")
}
