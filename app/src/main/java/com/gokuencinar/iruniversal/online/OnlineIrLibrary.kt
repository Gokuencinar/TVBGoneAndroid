package com.gokuencinar.iruniversal.online

import com.gokuencinar.iruniversal.flipper.FlipperIrCodec
import com.gokuencinar.iruniversal.flipper.ImportedIrSignal
import com.gokuencinar.iruniversal.flipper.IrdbCsvCodec
import com.gokuencinar.iruniversal.ir.DeviceCategory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class OnlineIrSource(val title: String) {
    FLIPPER_COMMUNITY("Flipper-IRDB"),
    FLIPPER_OFFICIAL("Flipper IRDB oficial"),
    LEGACY_IRDB("IRDB Web")
}

data class OnlineIrRemote(
    val id: String,
    val source: OnlineIrSource,
    val path: String,
    val brand: String,
    val model: String,
    val categoryLabel: String,
    val downloadUrl: String,
    val score: Int
) {
    val displayName: String
        get() = if (model.isBlank() || model.equals(brand, true)) brand else brand + " · " + model
}

data class OnlineLoadedRemote(
    val name: String,
    val sourceDescription: String,
    val signals: List<ImportedIrSignal>
)

class OnlineIrLibrary {
    private val textCache = mutableMapOf<String, String>()

    fun brands(
        category: DeviceCategory,
        sources: List<OnlineIrSource> = OnlineIrSource.entries
    ): List<String> {
        val found = mutableListOf<String>()
        var successfulSources = 0
        var lastFailure: Throwable? = null
        sources.forEach { source ->
            if (!sourceSupportsCategory(source, category)) return@forEach
            runCatching {
                when (source) {
                    OnlineIrSource.FLIPPER_COMMUNITY -> found += brandsFromGitHubTree(
                        "https://api.github.com/repos/Lucaslhm/Flipper-IRDB/git/trees/main?recursive=1",
                        source,
                        category
                    )
                    OnlineIrSource.FLIPPER_OFFICIAL -> found += brandsFromGitHubTree(
                        "https://api.github.com/repos/flipperdevices/IRDB/git/trees/dev?recursive=1",
                        source,
                        category
                    )
                    OnlineIrSource.LEGACY_IRDB -> found += brandsFromLegacy(category)
                }
            }.onSuccess {
                successfulSources += 1
            }.onFailure {
                lastFailure = it
            }
        }
        if (sources.isNotEmpty() && successfulSources == 0) {
            throw lastFailure ?: IllegalStateException("No se pudo acceder a ninguna fuente IR.")
        }
        return found
            .map { it.replace("_", " ").trim() }
            .filter { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("desconocida", true) }
            .distinctBy { clean(it) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun search(
        brand: String,
        model: String,
        category: DeviceCategory,
        sources: List<OnlineIrSource> = OnlineIrSource.entries,
        deep: Boolean = false
    ): List<OnlineIrRemote> {
        val b = clean(brand)
        val m = clean(model)
        if (b.isBlank() && m.isBlank()) return emptyList()

        val found = mutableListOf<OnlineIrRemote>()
        var successfulSources = 0
        var lastFailure: Throwable? = null
        sources.forEach { source ->
            if (!sourceSupportsCategory(source, category)) return@forEach
            runCatching {
                when (source) {
                    OnlineIrSource.FLIPPER_COMMUNITY -> found += searchGitHubTree(
                        "https://api.github.com/repos/Lucaslhm/Flipper-IRDB/git/trees/main?recursive=1",
                        "https://raw.githubusercontent.com/Lucaslhm/Flipper-IRDB/main/",
                        source, b, m, category, deep
                    )
                    OnlineIrSource.FLIPPER_OFFICIAL -> found += searchGitHubTree(
                        "https://api.github.com/repos/flipperdevices/IRDB/git/trees/dev?recursive=1",
                        "https://raw.githubusercontent.com/flipperdevices/IRDB/dev/",
                        source, b, m, category, deep
                    )
                    OnlineIrSource.LEGACY_IRDB -> found += searchLegacy(b, m, category, deep)
                }
            }.onSuccess {
                successfulSources += 1
            }.onFailure {
                lastFailure = it
            }
        }
        if (sources.isNotEmpty() && successfulSources == 0) {
            throw lastFailure ?: IllegalStateException("No se pudo acceder a ninguna fuente IR.")
        }

        return found
            .distinctBy { it.id }
            .sortedWith(compareByDescending<OnlineIrRemote> { it.score }.thenBy { it.displayName.lowercase() })
            .take(120)
    }

    private fun sourceSupportsCategory(source: OnlineIrSource, category: DeviceCategory): Boolean =
        !(source == OnlineIrSource.FLIPPER_OFFICIAL && category == DeviceCategory.AIR_CONDITIONER)

    fun download(remote: OnlineIrRemote): OnlineLoadedRemote {
        val text = fetchText(remote.downloadUrl, 2_000_000)
        val signals = if (remote.source == OnlineIrSource.LEGACY_IRDB) {
            IrdbCsvCodec.parse(text, "online-irdb")
        } else {
            FlipperIrCodec.parse(text)
        }
        require(signals.isNotEmpty()) { "El archivo no contiene señales compatibles." }
        return OnlineLoadedRemote(
            remote.displayName,
            remote.source.title + " · " + remote.path,
            signals
        )
    }

    fun importUrl(value: String): OnlineLoadedRemote {
        val url = value.trim()
        require(url.startsWith("https://")) { "Solo se admiten URLs HTTPS." }
        val text = fetchText(normalizedUrl(url), 2_000_000)
        var signals = FlipperIrCodec.parse(text)
        if (signals.isEmpty()) signals = IrdbCsvCodec.parse(text, "url-irdb")
        require(signals.isNotEmpty()) { "No se encontraron señales .ir o IRDB compatibles." }
        val name = URL(normalizedUrl(url)).path.substringAfterLast('/').substringBeforeLast('.')
            .replace("_", " ").ifBlank { "Mando importado" }
        return OnlineLoadedRemote(name, "URL · " + URL(url).host, signals)
    }

    private fun brandsFromGitHubTree(
        treeUrl: String,
        source: OnlineIrSource,
        category: DeviceCategory
    ): List<String> {
        val root = JSONObject(fetchText(treeUrl, 20_000_000))
        if (root.optBoolean("truncated", false)) error("El índice de GitHub llegó truncado.")
        val tree = root.getJSONArray("tree")
        val out = mutableListOf<String>()
        for (i in 0 until tree.length()) {
            val entry = tree.getJSONObject(i)
            if (entry.optString("type") != "blob") continue
            val path = entry.optString("path")
            if (!path.lowercase().endsWith(".ir")) continue
            if (!categoryMatches(path, category, source)) continue
            val brand = guessBrand(path.split("/"), source)
            if (brand.isNotBlank()) out += brand
        }
        return out
    }

    private fun brandsFromLegacy(category: DeviceCategory): List<String> {
        val indexUrl = "https://cdn.jsdelivr.net/gh/probonopd/irdb@master/codes/index"
        return fetchText(indexUrl, 8_000_000).lineSequence().mapNotNull { raw ->
            val path = raw.trim()
            if (!path.lowercase().endsWith(".csv")) return@mapNotNull null
            val parts = path.split("/")
            if (parts.size < 3) return@mapNotNull null
            if (!legacyCategoryMatches(parts[1], category)) return@mapNotNull null
            parts[0].takeIf { it.isNotBlank() }
        }.toList()
    }

    private fun searchGitHubTree(
        treeUrl: String,
        rawPrefix: String,
        source: OnlineIrSource,
        brand: String,
        model: String,
        category: DeviceCategory,
        deep: Boolean
    ): List<OnlineIrRemote> {
        val root = JSONObject(fetchText(treeUrl, 20_000_000))
        if (root.optBoolean("truncated", false)) error("El índice de GitHub llegó truncado.")
        val tree = root.getJSONArray("tree")
        val out = mutableListOf<OnlineIrRemote>()

        for (i in 0 until tree.length()) {
            val entry = tree.getJSONObject(i)
            if (entry.optString("type") != "blob") continue
            val path = entry.optString("path")
            if (!path.lowercase().endsWith(".ir")) continue
            if (!categoryMatches(path, category, source)) continue

            val pieces = path.split("/")
            val filename = pieces.lastOrNull()?.removeSuffix(".ir") ?: "Mando"
            val guessedBrand = guessBrand(pieces, source)
            val score = matchScore(brand, model, guessedBrand, filename, path, deep)
            if (score <= 0) continue

            out += OnlineIrRemote(
                source.name + ":" + path,
                source,
                path,
                guessedBrand.ifBlank { "Desconocida" },
                filename.replace("_", " "),
                category.shortTitle,
                rawPrefix + path.replace(" ", "%20"),
                score
            )
        }
        return out
    }

    private fun searchLegacy(
        brand: String,
        model: String,
        category: DeviceCategory,
        deep: Boolean
    ): List<OnlineIrRemote> {
        val indexUrl = "https://cdn.jsdelivr.net/gh/probonopd/irdb@master/codes/index"
        return fetchText(indexUrl, 8_000_000).lineSequence().mapNotNull { raw ->
            val path = raw.trim()
            if (!path.lowercase().endsWith(".csv")) return@mapNotNull null
            val parts = path.split("/")
            if (parts.size < 3) return@mapNotNull null
            val candidateBrand = parts[0]
            val legacyCategory = parts[1]
            if (!legacyCategoryMatches(legacyCategory, category)) return@mapNotNull null
            val candidateModel = parts.last()
                .substringBeforeLast('.')
                .replace("_", " ")
                .replace("-", " ")
                .ifBlank { parts.getOrNull(2).orEmpty() }

            val score = matchScore(
                brand, model, candidateBrand, candidateModel,
                candidateBrand + " " + candidateModel + " " + path, deep
            )
            if (score <= 0) return@mapNotNull null

            OnlineIrRemote(
                "legacy:" + path,
                OnlineIrSource.LEGACY_IRDB,
                path,
                candidateBrand,
                candidateModel,
                category.shortTitle,
                "https://cdn.jsdelivr.net/gh/probonopd/irdb@master/codes/" + path.replace(" ", "%20"),
                score
            )
        }.toList()
    }

    private fun guessBrand(pieces: List<String>, source: OnlineIrSource): String {
        if (pieces.size < 2) return ""
        if (source == OnlineIrSource.FLIPPER_COMMUNITY) {
            return (if (pieces.size >= 3) pieces[1] else pieces[0]).replace("_", " ")
        }
        val index = pieces.indexOf("categories")
        if (index >= 0 && pieces.size > index + 2) return pieces[index + 2].replace("_", " ")
        return pieces.dropLast(1).lastOrNull()?.replace("_", " ").orEmpty()
    }

    private fun categoryMatches(path: String, category: DeviceCategory, source: OnlineIrSource): Boolean {
        val n = clean(path)
        return when (category) {
            DeviceCategory.TELEVISION -> n.contains("tv") || n.contains("television")
            DeviceCategory.AIR_CONDITIONER ->
                source == OnlineIrSource.FLIPPER_COMMUNITY &&
                    (n.contains(" ac ") || n.contains("air conditioner") || n.contains("acs"))
            DeviceCategory.PROJECTOR -> n.contains("projector")
            DeviceCategory.SET_TOP_BOX ->
                n.contains("cable box") || n.contains("set top") || n.contains("dvb t") ||
                    n.contains("tv tuner") || n.contains("satellite")
            DeviceCategory.FAN -> n.contains("fans") || n.contains(" fan ")
            DeviceCategory.MEDIA_BOX ->
                n.contains("streaming device") || n.contains("multimedia") || n.contains("smart box") ||
                    n.contains("media box") || n.contains("tv box") || n.contains("android tv")
            DeviceCategory.DVD_PLAYER -> n.contains("dvd player") || n.contains(" dvd ")
            DeviceCategory.BLU_RAY -> n.contains("blu ray") || n.contains("bluray")
            DeviceCategory.AV_RECEIVER ->
                n.contains("audio and video receiver") || n.contains("av receiver") ||
                    n.contains("a v receiver") || n.contains("receiver")
            DeviceCategory.SOUND_BAR -> n.contains("soundbar") || n.contains("sound bar")
            DeviceCategory.CAMERA -> n.contains("camera") || n.contains("cctv")
        }
    }

    private fun legacyCategoryMatches(value: String, category: DeviceCategory): Boolean {
        val n = clean(value)
        return when (category) {
            DeviceCategory.TELEVISION -> n.contains("tv") || n.contains("television")
            DeviceCategory.AIR_CONDITIONER -> n.contains("air") || n == "ac" || n.contains(" ac ")
            DeviceCategory.PROJECTOR -> n.contains("projector")
            DeviceCategory.SET_TOP_BOX ->
                n.contains("cable") || n.contains("sat") || n.contains("dvb") || n.contains("tuner") ||
                    n.contains("set top")
            DeviceCategory.FAN -> n.contains("fan")
            DeviceCategory.MEDIA_BOX -> n.contains("stream") || n.contains("media") || n.contains("box")
            DeviceCategory.DVD_PLAYER -> n.contains("dvd")
            DeviceCategory.BLU_RAY -> n.contains("blu") || n.contains("bluray")
            DeviceCategory.AV_RECEIVER -> n.contains("receiver") || n.contains("audio") || n == "av"
            DeviceCategory.SOUND_BAR -> n.contains("sound") || n.contains("bar")
            DeviceCategory.CAMERA -> n.contains("camera") || n.contains("cctv")
        }
    }

    private fun matchScore(
        brand: String,
        model: String,
        candidateBrand: String,
        candidateModel: String,
        full: String,
        deep: Boolean
    ): Int {
        val cb = clean(candidateBrand)
        val cm = clean(candidateModel)
        val all = clean(full)
        var score = 0

        if (brand.isNotBlank()) {
            score += when {
                cb == brand -> 35
                cb.contains(brand) || brand.contains(cb) -> 24
                compact(cb).contains(compact(brand)) -> 18
                deep && tokens(brand).all { all.contains(it) } -> 8
                else -> return 0
            }
        }

        if (model.isNotBlank()) {
            score += when {
                cm == model -> 40
                cm.contains(model) || all.contains(model) -> 28
                compact(all).contains(compact(model)) -> 20
                deep && tokens(model).all { all.contains(it) } -> 10
                else -> return 0
            }
        }
        return score
    }

    private fun clean(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace("_", " ").replace("-", " ").replace(".", " ").replace("/", " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")

    private fun compact(value: String): String = clean(value).filter { it.isLetterOrDigit() }
    private fun tokens(value: String): List<String> = clean(value).split(" ").filter { it.length >= 2 }

    private fun normalizedUrl(value: String): String {
        if (!value.startsWith("https://github.com/")) return value
        val url = URL(value)
        val pieces = url.path.trim('/').split("/")
        return if (pieces.size >= 5 && pieces[2] == "blob") {
            "https://raw.githubusercontent.com/" + pieces[0] + "/" + pieces[1] + "/" +
                pieces[3] + "/" + pieces.drop(4).joinToString("/")
        } else value
    }

    private fun fetchText(url: String, maxBytes: Int): String {
        textCache[url]?.let { return it }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "IR-Universal-Android/0.1")
        connection.instanceFollowRedirects = true

        try {
            val status = connection.responseCode
            require(status in 200..299) { "Servidor HTTP " + status }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    require(total <= maxBytes) { "La respuesta es demasiado grande." }
                    out.write(buffer, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name()).also { textCache[url] = it }
        } finally {
            connection.disconnect()
        }
    }
}
