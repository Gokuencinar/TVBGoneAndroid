package com.gokuencinar.iruniversal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.gokuencinar.iruniversal.flipper.FlipperIrCodec
import com.gokuencinar.iruniversal.flipper.ImportedIrSignal
import com.gokuencinar.iruniversal.ir.*
import com.gokuencinar.iruniversal.learn.IrLearner
import com.gokuencinar.iruniversal.learn.IrSignalAnalyzer
import com.gokuencinar.iruniversal.online.OnlineIrLibrary
import com.gokuencinar.iruniversal.online.OnlineIrRemote
import com.gokuencinar.iruniversal.storage.AppStore
import com.gokuencinar.iruniversal.storage.CustomRemote
import com.gokuencinar.iruniversal.storage.CustomRemoteButton
import com.gokuencinar.iruniversal.storage.SavedDevice
import com.gokuencinar.iruniversal.update.AppRelease
import com.gokuencinar.iruniversal.update.AppUpdater
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var transmitter: AutoIrTransmitter
    private lateinit var scanner: IrScanner
    private lateinit var store: AppStore
    private lateinit var preferences: SharedPreferences

    private val worker = Executors.newSingleThreadExecutor()
    private val onlineLibrary = OnlineIrLibrary()
    private lateinit var learner: IrLearner

    private var learnedCandidate: IrCode? = null
    private var importedSignals: List<ImportedIrSignal> = emptyList()
    private var screenGeneration = 0L
    private var selectedCategory = DeviceCategory.TELEVISION
    private var selectedRegion = TvRegion.EUROPE
    private var selectedPace = ScanPace.FAST
    private var selectedLearnCarrierIndex = 0
    private var guidedLearning = false
    private var guidedIndex = 0
    private var currentTab = 0
    private val bottomTabViews = mutableListOf<LinearLayout>()

    companion object {
        private const val REQUEST_MIC = 1001
        private const val REQUEST_IMPORT_IR = 1002
        private const val REQUEST_RESTORE_BACKUP = 1003
        private const val PREF_BROWSER_MODE = "irUniversal.localBrowserPresentation"
        private const val PREF_ONLINE_BROWSER_MODE = "irUniversal.onlineBrowserPresentation"
        private const val PREF_OLED_MODE = "irUniversal.oledMode"
        private val IOS_RED = Color.rgb(255, 59, 48)
        private val IOS_GREEN = Color.rgb(52, 199, 89)
        private val IOS_SECONDARY = Color.rgb(142, 142, 147)
        private val IOS_SURFACE = Color.argb(14, 255, 255, 255)
        private val IOS_BORDER = Color.argb(20, 255, 255, 255)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transmitter = AutoIrTransmitter(this)
        scanner = IrScanner { transmitter.active() }
        store = AppStore(this)
        learner = IrLearner(applicationContext)
        preferences = getSharedPreferences("ir_universal_android", MODE_PRIVATE)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(3))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64)
        ))

        addBottomTab("Control", R.drawable.ic_tab_power, 0)
        addBottomTab("Códigos", R.drawable.ic_tab_codes, 1)
        addBottomTab("Mis equipos", R.drawable.ic_tab_star, 2)
        addBottomTab("Aprender", R.drawable.ic_tab_mic, 3)
        addBottomTab("Diagnóstico", R.drawable.ic_tab_diagnostics, 4)

        setContentView(root)
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        currentTab = index.coerceIn(0, 4)
        bottomTabViews.forEachIndexed { itemIndex, item ->
            val selected = itemIndex == currentTab
            val color = if (selected) IOS_RED else IOS_SECONDARY
            (item.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (item.getChildAt(1) as? TextView)?.setTextColor(color)
        }
        when (currentTab) {
            0 -> showControl()
            1 -> showCodes()
            2 -> showSavedDevices()
            3 -> showLearn()
            else -> showDiagnostics()
        }
    }

    private fun addBottomTab(label: String, iconRes: Int, index: Int) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(2), dp(3), dp(2), dp(1))
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(IOS_SECONDARY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(IOS_SECONDARY)
            maxLines = 1
        }
        item.addView(icon, LinearLayout.LayoutParams(dp(25), dp(25)))
        item.addView(text, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        item.setOnClickListener { selectTab(index) }
        bottomTabViews += item
        bottomBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun showControl() {
        val screen = beginScreen()
        val body = installScreenBody("TVBGoneAndroid")

        body.addView(controlHero(), spacedMatch(4))
        body.addView(accessoryStatusCard(), spacedMatch(16))

        val quick = store.loadDevices().filter { it.category == selectedCategory }.take(3)
        if (quick.isNotEmpty()) {
            body.addView(sectionHeader("★  Acceso rápido"))
            quick.forEach { device ->
                val row = card(16).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = categoryGlyph(device.category)
                        textSize = 22f
                        setTextColor(IOS_RED)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(34), dp(42)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(bodyText(device.name, 15f, Color.WHITE, Typeface.BOLD))
                        addView(bodyText(device.code.displayName, 12f, IOS_SECONDARY))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "⏻"
                        textSize = 24f
                        setTextColor(IOS_RED)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(46), dp(46)))
                    setOnClickListener {
                        sendAsync(device.code, screenStatusText("Transmitiendo…"), screen)
                    }
                }
                body.addView(row, spacedMatch(10))
            }
        }

        store.loadWorked().firstOrNull()?.let { record ->
            val recent = card(18).apply {
                val header = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        bodyText("✓  Último código que funcionó", 15f, Color.WHITE, Typeface.BOLD),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(bodyText(
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                            .format(java.util.Date(record.createdAt)),
                        11f,
                        IOS_SECONDARY
                    ))
                }
                addView(header, spacedMatch(8))
                addView(bodyText(record.code.displayName, 14f, Color.WHITE, Typeface.BOLD), spacedMatch(4))
                addView(bodyText(
                    record.code.sourceLabel + " · " + record.code.effectiveCarrierHz + " Hz",
                    12f,
                    IOS_SECONDARY
                ), spacedMatch(8))
                val resend = tintedButton("⏻  PROBAR DE NUEVO", IOS_GREEN)
                addView(resend, matchWrap())
                resend.setOnClickListener { sendAsync(record.code, detailsStatus(this), screen) }
            }
            body.addView(recent, spacedMatch(16))
        }

        val categoryControl = segmentedControl(
            listOf("TV", "Aire", "Proyector"),
            selectedCategory.ordinal
        ) { index ->
            selectedCategory = DeviceCategory.entries[index]
            if (isScreenActive(screen)) showControl()
        }
        body.addView(categoryControl, spacedMatch(12))

        var regionControl: LinearLayout? = null
        if (selectedCategory == DeviceCategory.TELEVISION) {
            regionControl = segmentedControl(
                TvRegion.entries.map { it.title },
                selectedRegion.ordinal
            ) { index ->
                selectedRegion = TvRegion.entries[index]
                if (isScreenActive(screen)) showControl()
            }
            body.addView(regionControl, spacedMatch(16))
        }

        val scanCard = card(20)
        val scanHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText("◉  Barrido universal", 16f, Color.WHITE, Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bodyText(
                IrCodeCatalog.scanCodes(selectedCategory, selectedRegion).size.toString() + " códigos",
                12f,
                IOS_SECONDARY
            ))
        }
        scanCard.addView(scanHeader, spacedMatch(10))

        val paceControl = segmentedControl(
            ScanPace.entries.map { it.title },
            selectedPace.ordinal
        ) { index -> selectedPace = ScanPace.entries[index] }
        scanCard.addView(paceControl, spacedMatch(8))
        val paceHelp = infoText(paceHelp(selectedPace)).apply {
            setPadding(0, 0, 0, dp(8))
        }
        scanCard.addView(paceHelp, matchWrap())
        paceControl.setOnHierarchyChangeListener(null)

        val start = primaryButton(categoryButtonTitle(selectedCategory))
        scanCard.addView(start, matchWrap())
        body.addView(scanCard, spacedMatch(14))

        val activeCard = card(20).apply { visibility = View.GONE }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(IOS_RED)
        }
        val countText = bodyText("0 / 0", 12f, IOS_SECONDARY)
        val etaText = bodyText("", 12f, IOS_SECONDARY)
        val codeLabel = bodyText("Código actual", 12f, IOS_SECONDARY)
        val currentCode = bodyText("—", 14f, Color.WHITE, Typeface.BOLD)
        val carrierText = bodyText("", 12f, IOS_SECONDARY)
        val status = infoText("Listo. " + transmitter.active().name).apply {
            setPadding(0, dp(4), 0, dp(8))
        }
        activeCard.addView(progress, spacedMatch(8))
        val progressMeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(countText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(etaText)
        }
        activeCard.addView(progressMeta, spacedMatch(8))
        activeCard.addView(codeLabel)
        activeCard.addView(currentCode)
        activeCard.addView(carrierText, spacedMatch(8))
        activeCard.addView(status, spacedMatch(8))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = outlineButton("◀|")
        val pause = outlineButton("Ⅱ")
        val next = outlineButton("|▶")
        transport.addView(previous, weighted())
        transport.addView(space(dp(8)))
        transport.addView(pause, weighted())
        transport.addView(space(dp(8)))
        transport.addView(next, weighted())
        activeCard.addView(transport, spacedMatch(10))
        val worked = tintedButton("✓  FUNCIONÓ", IOS_GREEN)
        activeCard.addView(worked, matchWrap())
        body.addView(activeCard, spacedMatch(18))

        fun setScanConfigurationEnabled(enabled: Boolean) {
            setSegmentEnabled(categoryControl, enabled)
            regionControl?.let { setSegmentEnabled(it, enabled) }
            setSegmentEnabled(paceControl, enabled)
        }

        start.setOnClickListener {
            if (scanner.isRunning()) {
                scanner.stop()
                activeCard.visibility = View.GONE
                start.text = categoryButtonTitle(selectedCategory)
                setScanConfigurationEnabled(true)
                return@setOnClickListener
            }

            val codes = IrCodeCatalog.scanCodes(selectedCategory, selectedRegion)
            if (codes.isEmpty()) {
                status.text = "No hay una base offline para esta categoría todavía. Usa Online o importa un mando .ir."
                activeCard.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val active = transmitter.active()
            if (!active.isAvailable()) {
                status.text = "El transmisor seleccionado no está disponible. Revisa Diagnóstico."
                activeCard.visibility = View.VISIBLE
                return@setOnClickListener
            }

            status.text = "Iniciando " + codes.size + " códigos mediante " + active.name
            progress.progress = 0
            etaText.text = ""
            activeCard.visibility = View.VISIBLE
            start.text = "DETENER BARRIDO"
            setScanConfigurationEnabled(false)
            scanner.start(codes, selectedPace) { p ->
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    progress.progress = if (p.total == 0) 0 else (p.index * 1000 / p.total)
                    countText.text = p.index.toString() + " / " + p.total
                    val remainingMillis = (p.total - p.index).coerceAtLeast(0) *
                        (selectedPace.gapMillis + 120L)
                    val remainingSeconds = (remainingMillis / 1000L).toInt()
                    etaText.text = if (p.code == null || remainingSeconds <= 0) "" else if (remainingSeconds < 60) {
                        "~" + remainingSeconds + " s"
                    } else {
                        "~" + (remainingSeconds / 60) + " min " + (remainingSeconds % 60) + " s"
                    }
                    currentCode.text = p.code?.displayName ?: "Barrido terminado"
                    carrierText.text = p.code?.let { it.effectiveCarrierHz.toString() + " Hz" }.orEmpty()
                    pause.text = if (p.paused) "▶" else "Ⅱ"
                    status.text = when {
                        p.code == null -> {
                            activeCard.visibility = View.GONE
                            start.text = categoryButtonTitle(selectedCategory)
                            setScanConfigurationEnabled(true)
                            "Barrido terminado."
                        }
                        p.error != null -> "Código " + p.index + "/" + p.total + " · " + p.error
                        else -> "Código " + p.index + "/" + p.total + " · " +
                            p.code.displayName + " · " + p.code.effectiveCarrierHz + " Hz"
                    }
                }
            }
        }

        pause.setOnClickListener {
            if (!scanner.isRunning()) return@setOnClickListener
            if (scanner.isPaused()) {
                scanner.resume()
                pause.text = "Ⅱ"
                status.text = "Barrido reanudado."
            } else {
                scanner.pause()
                pause.text = "▶"
                status.text = "Barrido pausado."
            }
        }
        previous.setOnClickListener {
            if (!scanner.isRunning()) return@setOnClickListener
            pause.text = "▶"
            status.text = "Modo manual · enviando el código anterior…"
            scanner.step(-1)
        }
        next.setOnClickListener {
            if (!scanner.isRunning()) return@setOnClickListener
            pause.text = "▶"
            status.text = "Modo manual · enviando el código siguiente…"
            scanner.step(1)
        }

        worked.setOnClickListener {
            val candidates = scanner.candidates()
            if (candidates.isEmpty()) {
                toast("Todavía no hay candidatos recientes.")
            } else {
                scanner.pause()
                showCodeChooser("¿Qué código funcionó?", candidates) { code ->
                    store.addWorked(selectedCategory, code)
                    saveDeviceDialog(
                        selectedCategory,
                        code
                    )
                }
            }
        }
    }

    private fun showCodes() {
        val screen = beginScreen()
        val body = installScreenBody("Seleccionar código")

        body.addView(segmentedControl(
            listOf("TV", "Aire", "Proyector"),
            selectedCategory.ordinal
        ) { index ->
            selectedCategory = DeviceCategory.entries[index]
            if (isScreenActive(screen)) showCodes()
        }, spacedMatch(12))

        if (selectedCategory == DeviceCategory.TELEVISION) {
            body.addView(segmentedControl(
                TvRegion.entries.map { it.title },
                selectedRegion.ordinal
            ) { index ->
                selectedRegion = TvRegion.entries[index]
                if (isScreenActive(screen)) showCodes()
            }, spacedMatch(14))
        }

        val onlineCard = card(18).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText("◎", 30f, IOS_RED), LinearLayout.LayoutParams(dp(42), dp(48)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bodyText("Biblioteca IR online", 16f, Color.WHITE, Typeface.BOLD))
                addView(bodyText(
                    "Busca por marca/modelo, prueba códigos y descarga mandos",
                    12f,
                    IOS_SECONDARY
                ))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bodyText("›", 28f, IOS_SECONDARY))
            setOnClickListener { showOnline() }
        }
        body.addView(onlineCard, spacedMatch(14))

        val search = EditText(this).apply {
            hint = "Buscar marca, modelo o código"
            setTextColor(Color.WHITE)
            setHintTextColor(IOS_SECONDARY)
            textSize = 15f
            setSingleLine(true)
            background = roundedDrawable(IOS_SURFACE, 12, IOS_BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(search, spacedMatch(12))

        var sourceIndex = 0
        val sourceLabels = listOf("Todos", "Universal", "TV-B-Gone", "IRDB")
        val sourceControl = segmentedControl(sourceLabels, sourceIndex) { index ->
            sourceIndex = index
        }
        body.addView(sourceControl, spacedMatch(14))

        val browserCard = card(20)
        val browserHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        browserHeader.addView(
            bodyText("ABC  Explorar marcas", 16f, Color.WHITE, Typeface.BOLD),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val countLabel = bodyText("0", 12f, IOS_SECONDARY)
        browserHeader.addView(countLabel)
        browserCard.addView(browserHeader, spacedMatch(10))

        val modeRaw = preferences.getString(PREF_BROWSER_MODE, "list") ?: "list"
        var listMode = modeRaw != "wheel"
        val modeControl = segmentedControl(
            listOf("Lista", "Ruleta"),
            if (listMode) 0 else 1
        ) { index ->
            preferences.edit().putString(PREF_BROWSER_MODE, if (index == 0) "list" else "wheel").apply()
            if (isScreenActive(screen)) showCodes()
        }
        browserCard.addView(modeControl, spacedMatch(8))
        val help = infoText(
            if (listMode) "Toca una marca para ver sus códigos dentro de esta misma lista."
            else "Selecciona una marca con la ruleta."
        ).apply { setPadding(0, 0, 0, dp(8)) }
        browserCard.addView(help)

        val browserContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        browserCard.addView(browserContainer, matchWrap())
        body.addView(browserCard, spacedMatch(14))

        val selectionHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(selectionHost, spacedMatch(18))

        var selectedCode: IrCode? = null
        var selectedBrand = ""
        var selectedLetter = ""

        fun sourceMatches(code: IrCode): Boolean = when (sourceIndex) {
            1 -> code.sourceLabel == "Universal"
            2 -> code.sourceLabel == "TV-B-Gone"
            3 -> code.sourceLabel == "Flipper-IRDB"
            else -> true
        }

        fun allFilteredCodes(): List<IrCode> {
            val q = search.text.toString().trim().lowercase()
            return IrCodeCatalog.codes(selectedCategory, selectedRegion).filter { code ->
                sourceMatches(code) && (
                    q.isBlank() ||
                        code.id.lowercase().contains(q) ||
                        code.displayName.lowercase().contains(q) ||
                        code.brandHint.lowercase().contains(q)
                    )
            }
        }

        fun brandName(code: IrCode): String {
            val hint = code.brandHint.trim()
            if (hint.isNotBlank() && !hint.equals(code.sourceLabel, ignoreCase = true)) return hint
            return code.displayName.removePrefix("TV-B-Gone · ").trim().ifBlank { code.id }
        }

        fun showSelection(code: IrCode?) {
            selectedCode = code
            selectionHost.removeAllViews()
            val value = code ?: return
            val details = card(18).apply {
                addView(bodyText(value.displayName, 17f, Color.WHITE, Typeface.BOLD))
                addView(bodyText(value.id, 12f, IOS_SECONDARY))
                addView(bodyText(
                    value.sourceLabel + " · " + value.effectiveCarrierHz + " Hz · " +
                        value.durationMillis + " ms",
                    13f,
                    IOS_SECONDARY
                ))
            }
            selectionHost.addView(details, spacedMatch(10))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val send = primaryButton("⌁  PROBAR")
            val save = outlineButton("☆  GUARDAR")
            actions.addView(send, weighted())
            actions.addView(space(dp(10)))
            actions.addView(save, weighted())
            selectionHost.addView(actions, matchWrap())
            send.setOnClickListener { sendAsync(value, detailsStatus(details), screen) }
            save.setOnClickListener { saveDeviceDialog(selectedCategory, value) }
        }

        fun brandPowerCodes(brand: String): List<IrCode> =
            IrCodeCatalog.scanCodes(selectedCategory, selectedRegion)
                .filter(::sourceMatches)
                .filter { brandName(it).equals(brand, ignoreCase = true) }

        fun stopBrandScanForNavigation() {
            if (scanner.isRunning()) scanner.stop()
        }

        fun addBrandSweep(brand: String) {
            val powerCodes = brandPowerCodes(brand)
            if (powerCodes.size <= 1) return

            val sweepCard = card(18)
            sweepCard.addView(
                bodyText("◉  Barrido de " + brand, 16f, Color.WHITE, Typeface.BOLD),
                spacedMatch(5)
            )
            sweepCard.addView(
                bodyText(
                    powerCodes.size.toString() +
                        " códigos POWER/OFF de esta marca. El barrido no enviará volumen, entradas ni otros botones.",
                    12f,
                    IOS_SECONDARY
                ),
                spacedMatch(10)
            )

            val paceControl = segmentedControl(
                ScanPace.entries.map { it.title },
                selectedPace.ordinal
            ) { index ->
                if (!scanner.isRunning()) selectedPace = ScanPace.entries[index]
            }
            sweepCard.addView(paceControl, spacedMatch(10))

            val progress = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 1000
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(IOS_RED)
            }
            val count = bodyText(
                "0 / " + powerCodes.size,
                12f,
                IOS_SECONDARY
            )
            val current = bodyText("Listo", 14f, Color.WHITE, Typeface.BOLD)
            val frequency = bodyText("", 12f, IOS_SECONDARY)
            val status = infoText(
                "Puedes dejar que avance automáticamente o pausarlo para ir uno a uno."
            ).apply { setPadding(0, dp(4), 0, dp(8)) }

            sweepCard.addView(progress, spacedMatch(6))
            sweepCard.addView(count, spacedMatch(4))
            sweepCard.addView(current, spacedMatch(2))
            sweepCard.addView(frequency, spacedMatch(6))
            sweepCard.addView(status, spacedMatch(8))

            val start = primaryButton("▶  BARRER ESTA MARCA")
            sweepCard.addView(start, spacedMatch(10))

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val previous = outlineButton("◀|")
            val pause = outlineButton("Ⅱ")
            val next = outlineButton("|▶")
            controls.addView(previous, weighted())
            controls.addView(space(dp(8)))
            controls.addView(pause, weighted())
            controls.addView(space(dp(8)))
            controls.addView(next, weighted())
            sweepCard.addView(controls, spacedMatch(10))

            val worked = tintedButton("✓  FUNCIONÓ", IOS_GREEN)
            sweepCard.addView(worked)

            fun setRunningUi(running: Boolean) {
                start.text = if (running) "■  DETENER BARRIDO" else "▶  BARRER ESTA MARCA"
                pause.text = if (scanner.isPaused()) "▶" else "Ⅱ"
                for (i in 0 until paceControl.childCount) {
                    paceControl.getChildAt(i).isEnabled = !running
                    paceControl.getChildAt(i).alpha = if (running) 0.55f else 1f
                }
            }

            start.setOnClickListener {
                if (scanner.isRunning()) {
                    scanner.stop()
                    setRunningUi(false)
                    status.text = "Barrido de " + brand + " detenido."
                    return@setOnClickListener
                }

                val active = transmitter.active()
                if (!active.isAvailable()) {
                    status.text = "El transmisor seleccionado no está disponible. Revisa Diagnóstico."
                    return@setOnClickListener
                }

                progress.progress = 0
                count.text = "0 / " + powerCodes.size
                current.text = "Iniciando " + brand + "…"
                frequency.text = ""
                status.text =
                    "Probando " + powerCodes.size + " códigos POWER/OFF mediante " + active.name
                setRunningUi(true)

                scanner.start(powerCodes, selectedPace) { p ->
                    runOnUiThread {
                        if (!isScreenActive(screen)) return@runOnUiThread

                        progress.progress =
                            if (p.total == 0) 0 else (p.index * 1000 / p.total)
                        count.text = p.index.toString() + " / " + p.total
                        current.text = p.code?.displayName ?: "Barrido terminado"
                        frequency.text = p.code?.let {
                            it.effectiveCarrierHz.toString() + " Hz"
                        }.orEmpty()
                        pause.text = if (p.paused) "▶" else "Ⅱ"

                        status.text = when {
                            p.code == null -> {
                                setRunningUi(false)
                                "Barrido de " + brand + " terminado."
                            }
                            p.error != null ->
                                "Código " + p.index + "/" + p.total + " · " + p.error
                            p.paused ->
                                "Pausado en " + p.code.displayName + ". Usa anterior/siguiente para ir uno a uno."
                            else ->
                                "Código " + p.index + "/" + p.total + " · " + p.code.displayName
                        }
                    }
                }
            }

            pause.setOnClickListener {
                if (!scanner.isRunning()) return@setOnClickListener
                if (scanner.isPaused()) {
                    scanner.resume()
                    pause.text = "Ⅱ"
                    status.text = "Barrido de " + brand + " reanudado."
                } else {
                    scanner.pause()
                    pause.text = "▶"
                    status.text =
                        "Barrido pausado. Usa anterior/siguiente para recorrer la marca manualmente."
                }
            }

            previous.setOnClickListener {
                if (!scanner.isRunning()) return@setOnClickListener
                pause.text = "▶"
                status.text = "Modo manual · código anterior de " + brand + "…"
                scanner.step(-1)
            }

            next.setOnClickListener {
                if (!scanner.isRunning()) return@setOnClickListener
                pause.text = "▶"
                status.text = "Modo manual · código siguiente de " + brand + "…"
                scanner.step(1)
            }

            worked.setOnClickListener {
                val candidates = scanner.candidates()
                if (candidates.isEmpty()) {
                    toast("Todavía no hay candidatos recientes de " + brand + ".")
                    return@setOnClickListener
                }

                scanner.pause()
                pause.text = "▶"
                showCodeChooser(
                    "¿Qué código de " + brand + " funcionó?",
                    candidates
                ) { code ->
                    store.addWorked(selectedCategory, code)
                    saveDeviceDialog(
                        selectedCategory,
                        code,
                        suggested = brand + " · " + code.displayName
                    )
                }
            }

            browserContainer.addView(sweepCard, spacedMatch(12))
        }

        fun renderBrowser() {
            browserContainer.removeAllViews()
            selectionHost.removeAllViews()
            selectedCode = null

            val codes = allFilteredCodes()
            val brands = codes.map(::brandName)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
            countLabel.text = brands.size.toString()

            if (brands.isEmpty()) {
                browserContainer.addView(emptyState(
                    "Sin códigos",
                    "Prueba con otra búsqueda u otro origen."
                ))
                return
            }

            if (selectedBrand.isNotBlank()) {
                addBrandSweep(selectedBrand)
            }

            if (!listMode) {
                val brandWheel = NumberPicker(this).apply {
                    minValue = 0
                    maxValue = brands.size
                    displayedValues = arrayOf("Todas") + brands.toTypedArray()
                    wrapSelectorWheel = false
                    value = if (selectedBrand.isBlank()) 0 else
                        (brands.indexOfFirst { it.equals(selectedBrand, true) } + 1).coerceAtLeast(0)
                    setOnValueChangedListener { _, _, newValue ->
                        stopBrandScanForNavigation()
                        selectedBrand = if (newValue == 0) "" else brands[newValue - 1]
                        renderBrowser()
                    }
                }
                browserContainer.addView(brandWheel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
                ))
                val options = if (selectedBrand.isBlank()) codes else
                    codes.filter { brandName(it).equals(selectedBrand, true) }
                if (options.isNotEmpty()) {
                    val codeWheel = NumberPicker(this).apply {
                        minValue = 0
                        maxValue = options.lastIndex
                        displayedValues = options.map { it.displayName.take(42) }.toTypedArray()
                        wrapSelectorWheel = false
                        setOnValueChangedListener { _, _, newValue -> showSelection(options[newValue]) }
                    }
                    browserContainer.addView(sectionHeader("Ruleta de códigos"))
                    browserContainer.addView(codeWheel, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
                    ))
                    showSelection(options.first())
                }
                return
            }

            val availableLetters = brands.mapNotNull {
                it.trim().firstOrNull()?.uppercaseChar()?.takeIf { c -> c in 'A'..'Z' }?.toString()
            }.distinct().sorted()
            if (selectedLetter !in availableLetters) selectedLetter = availableLetters.firstOrNull().orEmpty()

            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            val letters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            availableLetters.forEach { letter ->
                val button = TextView(this).apply {
                    text = letter
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (letter == selectedLetter) Color.WHITE else IOS_RED)
                    background = if (letter == selectedLetter)
                        roundedDrawable(IOS_RED, 8) else roundedDrawable(Color.TRANSPARENT, 8)
                    setOnClickListener {
                        stopBrandScanForNavigation()
                        selectedLetter = letter
                        selectedBrand = ""
                        renderBrowser()
                    }
                }
                letters.addView(button, LinearLayout.LayoutParams(dp(36), dp(32)))
            }
            // The whole screen already lives inside a ScrollView. Keeping another
            // ScrollView here makes Android 11 hand the swipe gesture to the parent,
            // so the brand list appears frozen. Let the page own vertical scrolling.
            columns.addView(letters, LinearLayout.LayoutParams(
                dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val rows = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }

            if (selectedBrand.isBlank()) {
                brands.filter {
                    it.trim().firstOrNull()?.uppercaseChar()?.toString() == selectedLetter
                }.forEach { brand ->
                    rows.addView(browserRow(brand, "›") {
                        stopBrandScanForNavigation()
                        selectedBrand = brand
                        renderBrowser()
                    })
                }
            } else {
                rows.addView(browserRow("‹  Marcas", "") {
                    stopBrandScanForNavigation()
                    selectedBrand = ""
                    renderBrowser()
                })
                codes.filter { brandName(it).equals(selectedBrand, true) }.forEach { code ->
                    rows.addView(browserRow(code.displayName, code.effectiveCarrierHz.toString() + " Hz") {
                        showSelection(code)
                    })
                }
            }
            columns.addView(rows, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))
            browserContainer.addView(columns, matchWrap())
        }

        sourceControl.setOnClickListener(null)
        search.addTextChangedListener(simpleTextWatcher {
            stopBrandScanForNavigation()
            selectedBrand = ""
            renderBrowser()
        })
        wireSegmentCallback(sourceControl) { index ->
            stopBrandScanForNavigation()
            sourceIndex = index
            selectedBrand = ""
            renderBrowser()
        }

        renderBrowser()
    }

    private fun showOnline() {
        val screen = beginScreen()
        val body = installScreenBody("IR online") { showCodes() }

        var sourceIndex = 0
        val searchCard = card(20)
        searchCard.addView(bodyText("◎  Biblioteca IR online", 20f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        searchCard.addView(bodyText(
            "Busca mandos publicados por la comunidad, pruébalos y guarda los que funcionen para usarlos después sin Internet.",
            14f,
            IOS_SECONDARY
        ), spacedMatch(12))

        searchCard.addView(segmentedControl(
            listOf("TV", "Aire", "Proyector"),
            selectedCategory.ordinal
        ) { index ->
            selectedCategory = DeviceCategory.entries[index]
            if (isScreenActive(screen)) showOnline()
        }, spacedMatch(10))

        val brand = oledInput("Marca (ej. TD Systems)")
        val model = oledInput("Modelo (opcional)")
        searchCard.addView(brand, spacedMatch(10))
        searchCard.addView(model, spacedMatch(10))

        var reloadBrands: (() -> Unit)? = null
        val sourceControl = segmentedControl(
            listOf("Todas", "Flipper", "Oficial", "IRDB"),
            sourceIndex
        ) { index ->
            sourceIndex = index
            reloadBrands?.invoke()
        }
        searchCard.addView(sourceControl, spacedMatch(8))

        val deep = CheckBox(this).apply {
            text = "Búsqueda profunda"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(IOS_RED)
        }
        searchCard.addView(deep, spacedMatch(8))
        val searchButton = primaryButton("⌕  BUSCAR CÓDIGOS")
        searchCard.addView(searchButton, matchWrap())
        body.addView(searchCard, spacedMatch(14))

        val brandCard = card(18)
        val brandHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText("ABC  Explorar marcas", 16f, Color.WHITE, Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val brandStatus = bodyText("Cargando…", 12f, IOS_SECONDARY)
        brandHeader.addView(brandStatus)
        brandCard.addView(brandHeader, spacedMatch(10))
        val onlineMode = preferences.getString(PREF_ONLINE_BROWSER_MODE, "list") ?: "list"
        var onlineListMode = onlineMode != "wheel"
        brandCard.addView(segmentedControl(
            listOf("Lista", "Ruleta"),
            if (onlineListMode) 0 else 1
        ) { index ->
            preferences.edit().putString(PREF_ONLINE_BROWSER_MODE, if (index == 0) "list" else "wheel").apply()
            if (isScreenActive(screen)) showOnline()
        }, spacedMatch(8))
        brandCard.addView(infoText(
            if (onlineListMode) "Solo aparecen las letras que tienen marcas."
            else "Selecciona una marca usando la ruleta."
        ).apply { setPadding(0, 0, 0, dp(8)) })
        val brandBrowser = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCard.addView(brandBrowser)
        body.addView(brandCard, spacedMatch(14))

        val importCard = card(18)
        importCard.addView(bodyText("↗  Importar por URL", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        val importUrl = oledInput("https://… archivo .ir o CSV IRDB")
        val importStatus = infoText("").apply { setPadding(0, 0, 0, 0) }
        val importButton = outlineButton("IMPORTAR")
        importCard.addView(importUrl, spacedMatch(8))
        importCard.addView(importButton, spacedMatch(8))
        importCard.addView(importStatus)
        body.addView(importCard, spacedMatch(14))

        val resultsCard = card(18)
        val resultsTitle = bodyText("Resultados", 16f, Color.WHITE, Typeface.BOLD)
        val status = infoText("Busca por marca y, si lo conoces, por modelo.").apply {
            setPadding(0, dp(4), 0, dp(8))
        }
        val resultsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsCard.addView(resultsTitle)
        resultsCard.addView(status)
        resultsCard.addView(resultsHost)
        body.addView(resultsCard, spacedMatch(18))

        fun selectedSources(): List<com.gokuencinar.iruniversal.online.OnlineIrSource> = when (sourceIndex) {
            1 -> listOf(com.gokuencinar.iruniversal.online.OnlineIrSource.FLIPPER_COMMUNITY)
            2 -> listOf(com.gokuencinar.iruniversal.online.OnlineIrSource.FLIPPER_OFFICIAL)
            3 -> listOf(com.gokuencinar.iruniversal.online.OnlineIrSource.LEGACY_IRDB)
            else -> com.gokuencinar.iruniversal.online.OnlineIrSource.entries
        }

        fun renderResults(results: List<OnlineIrRemote>) {
            resultsHost.removeAllViews()
            if (results.isEmpty()) {
                resultsHost.addView(emptyState("Sin resultados", "Prueba otra marca, modelo o fuente."))
                return
            }
            results.forEach { remote ->
                resultsHost.addView(browserRow(
                    remote.displayName,
                    remote.source.title + "  ›"
                ) {
                    status.text = "Descargando " + remote.displayName + "…"
                    worker.execute {
                        val loaded = runCatching { onlineLibrary.download(remote) }
                        runOnUiThread {
                            if (!isScreenActive(screen)) return@runOnUiThread
                            loaded.onSuccess { value ->
                                status.text = value.name + " · " + value.signals.size + " señales"
                                showImportedSignals(value.signals, selectedCategory)
                            }.onFailure {
                                status.text = "Error: " + (it.message ?: "desconocido")
                            }
                        }
                    }
                })
            }
        }

        fun renderBrands(values: List<String>) {
            brandBrowser.removeAllViews()
            brandStatus.text = values.size.toString() + " marcas"
            if (values.isEmpty()) {
                brandBrowser.addView(emptyState("Sin marcas", "No se pudo cargar el índice para esta fuente."))
                return
            }
            if (!onlineListMode) {
                val pickerValues = arrayOf("Sin seleccionar") + values.toTypedArray()
                val wheel = NumberPicker(this).apply {
                    minValue = 0
                    maxValue = pickerValues.lastIndex
                    displayedValues = pickerValues
                    wrapSelectorWheel = false
                    setOnValueChangedListener { _, _, newValue ->
                        if (newValue > 0) brand.setText(values[newValue - 1]) else brand.setText("")
                    }
                }
                brandBrowser.addView(wheel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
                ))
                return
            }

            val letters = values.mapNotNull {
                it.trim().firstOrNull()?.uppercaseChar()?.takeIf { c -> c in 'A'..'Z' }?.toString()
            }.distinct().sorted()
            var selectedLetter = letters.firstOrNull().orEmpty()
            val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val letterHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val listHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }

            fun fillList() {
                listHost.removeAllViews()
                values.filter {
                    it.trim().firstOrNull()?.uppercaseChar()?.toString() == selectedLetter
                }.forEach { value ->
                    listHost.addView(browserRow(value, "›") {
                        brand.setText(value)
                    })
                }
                for (i in 0 until letterHost.childCount) {
                    val v = letterHost.getChildAt(i) as TextView
                    val active = v.text.toString() == selectedLetter
                    v.setTextColor(if (active) Color.WHITE else IOS_RED)
                    v.background = if (active) roundedDrawable(IOS_RED, 8)
                    else roundedDrawable(Color.TRANSPARENT, 8)
                }
            }

            letters.forEach { letter ->
                letterHost.addView(TextView(this).apply {
                    text = letter
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        selectedLetter = letter
                        fillList()
                    }
                }, LinearLayout.LayoutParams(dp(36), dp(32)))
            }
            columns.addView(letterHost, LinearLayout.LayoutParams(
                dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            columns.addView(listHost, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))
            brandBrowser.addView(columns)
            fillList()
        }

        reloadBrands = {
            brandStatus.text = "Actualizando marcas…"
            brandBrowser.removeAllViews()
            val categorySnapshot = selectedCategory
            val sourcesSnapshot = selectedSources().toList()
            worker.execute {
                val loaded = runCatching { onlineLibrary.brands(categorySnapshot, sourcesSnapshot) }
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    loaded.onSuccess(::renderBrands).onFailure {
                        brandStatus.text = "No se pudo cargar"
                        brandBrowser.removeAllViews()
                        brandBrowser.addView(emptyState("Error de red", it.message ?: "No se pudo cargar el índice."))
                    }
                }
            }
        }

        searchButton.setOnClickListener {
            val b = brand.text.toString()
            val m = model.text.toString()
            if (b.isBlank() && m.isBlank()) {
                status.text = "Escribe al menos una marca o un modelo."
                return@setOnClickListener
            }
            val categorySnapshot = selectedCategory
            val deepSearch = deep.isChecked
            val sourcesSnapshot = selectedSources().toList()
            status.text = "Consultando bibliotecas IR…"
            searchButton.isEnabled = false
            searchButton.text = "BUSCANDO…"
            worker.execute {
                val found = runCatching {
                    onlineLibrary.search(
                        b, m,
                        categorySnapshot,
                        sources = sourcesSnapshot,
                        deep = deepSearch
                    )
                }
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    searchButton.isEnabled = true
                    searchButton.text = "⌕  BUSCAR CÓDIGOS"
                    found.onSuccess {
                        renderResults(it)
                        status.text = if (it.isEmpty()) "Sin resultados." else "Encontrados " + it.size + " mandos."
                    }.onFailure {
                        status.text = "Error: " + (it.message ?: "desconocido")
                    }
                }
            }
        }

        importButton.setOnClickListener {
            val url = importUrl.text.toString().trim()
            if (url.isBlank()) return@setOnClickListener
            importButton.isEnabled = false
            importStatus.text = "Importando…"
            worker.execute {
                val loaded = runCatching { onlineLibrary.importUrl(url) }
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    importButton.isEnabled = true
                    loaded.onSuccess { value ->
                        importStatus.text = value.name + " · " + value.signals.size + " señales"
                        showImportedSignals(value.signals, selectedCategory)
                    }.onFailure {
                        importStatus.text = "Error: " + (it.message ?: "desconocido")
                    }
                }
            }
        }

        reloadBrands.invoke()
    }

    private fun showLearn() {
        val screen = beginScreen()
        val body = installScreenBody("Aprender IR")

        val intro = card(18).apply {
            addView(bodyText("≋  IR Studio", 17f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
            addView(bodyText(
                "Aprende botones de un mando mediante un receptor IR conectado a una entrada de audio, analiza el protocolo, compáralo con la base y crea tus propios mandos.",
                14f,
                Color.LTGRAY
            ), spacedMatch(6))
            addView(bodyText(
                "El LED emisor no puede recibir. Para aprender hace falta un receptor IR demodulado y una entrada de audio compatible.",
                12f,
                IOS_SECONDARY
            ))
        }
        body.addView(intro, spacedMatch(14))

        body.addView(segmentedControl(
            listOf("TV", "Aire", "Proyector"),
            selectedCategory.ordinal
        ) { index ->
            selectedCategory = DeviceCategory.entries[index]
            if (isScreenActive(screen)) showLearn()
        }, spacedMatch(14))

        val learnedNow = store.loadLearned()
        val studioTools = card(18)
        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val importButton = outlineButton("⇩  Importar .ir")
        val remoteButton = outlineButton("▤  Crear mando").apply {
            isEnabled = learnedNow.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.45f
        }
        toolRow.addView(importButton, weighted())
        toolRow.addView(space(dp(10)))
        toolRow.addView(remoteButton, weighted())
        studioTools.addView(toolRow, spacedMatch(8))
        val guided = CheckBox(this).apply {
            text = "Aprendizaje guiado"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(IOS_RED)
            isChecked = guidedLearning
        }
        studioTools.addView(guided)
        body.addView(studioTools, spacedMatch(14))
        importButton.setOnClickListener { openIrFilePicker() }
        remoteButton.setOnClickListener {
            showRemoteBuilderDialog(selectedCategory)
        }
        guided.setOnCheckedChangeListener { _, enabled ->
            guidedLearning = enabled
            guidedIndex = 0
            if (isScreenActive(screen)) showLearn()
        }

        if (guidedLearning) {
            val buttons = guidedButtonNames(selectedCategory)
            val safeIndex = guidedIndex.coerceIn(0, (buttons.size - 1).coerceAtLeast(0))
            val guidedCard = card(18)
            guidedCard.addView(
                bodyText("≡  Aprendizaje guiado", 16f, Color.WHITE, Typeface.BOLD),
                spacedMatch(8)
            )
            guidedCard.addView(
                bodyText(
                    "Botón " + (safeIndex + 1) + " de " + buttons.size,
                    12f,
                    IOS_SECONDARY
                ),
                spacedMatch(5)
            )
            guidedCard.addView(
                bodyText(buttons.getOrElse(safeIndex) { "Power" }, 19f, Color.WHITE, Typeface.BOLD),
                spacedMatch(8)
            )
            val guidedProgress = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = buttons.size.coerceAtLeast(1)
                progress = safeIndex
                progressTintList = android.content.res.ColorStateList.valueOf(IOS_RED)
            }
            guidedCard.addView(guidedProgress, matchWrap())
            body.addView(guidedCard, spacedMatch(14))
        }

        val inputInfo = learner.inspectInput()
        val inputCard = card(18)
        val inputHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText("●  Entrada de audio", 16f, Color.WHITE, Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bodyText(if (inputInfo.isExternal) "✓" else "✕", 22f,
                if (inputInfo.isExternal) IOS_GREEN else IOS_RED))
        }
        inputCard.addView(inputHeader, spacedMatch(6))
        inputCard.addView(bodyText(inputInfo.description, 14f, Color.LTGRAY), spacedMatch(4))
        inputCard.addView(bodyText(
            if (inputInfo.isExternal) "Entrada externa detectada"
            else "Entrada interna: este dispositivo no puede aprender IR",
            12f,
            if (inputInfo.isExternal) IOS_GREEN else IOS_RED,
            Typeface.BOLD
        ), spacedMatch(8))
        val checkInput = outlineButton("⌁  Comprobar entrada")
        inputCard.addView(checkInput, matchWrap())
        body.addView(inputCard, spacedMatch(14))
        checkInput.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                return@setOnClickListener
            }
            if (isScreenActive(screen)) showLearn()
        }

        val carrierCard = card(18)
        carrierCard.addView(bodyText("Portadora del mando", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        carrierCard.addView(segmentedControl(
            listOf("Auto", "36", "38", "40", "56"),
            selectedLearnCarrierIndex
        ) { selectedLearnCarrierIndex = it }, spacedMatch(8))
        carrierCard.addView(bodyText(
            if (selectedLearnCarrierIndex == 0)
                "Auto analiza la trama capturada, intenta reconocer el protocolo y elige su portadora habitual."
            else
                "Selección manual: " + intArrayOf(0, 36, 38, 40, 56)[selectedLearnCarrierIndex] + " kHz.",
            12f,
            IOS_SECONDARY
        ))
        body.addView(carrierCard, spacedMatch(14))

        val captureCard = card(18)
        captureCard.addView(bodyText("Captura", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        val status = infoText(
            if (inputInfo.isExternal) "Pulsa Capturar y después un botón del mando."
            else "Conecta un receptor IR a una entrada de audio externa."
        ).apply { setPadding(0, 0, 0, dp(8)) }
        captureCard.addView(status)
        val captureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val validate = outlineButton("✓  Validar x3")
        val capture = primaryButton("●  CAPTURAR")
        captureRow.addView(validate, weighted())
        captureRow.addView(space(dp(10)))
        captureRow.addView(capture, weighted())
        captureCard.addView(captureRow)
        body.addView(captureCard, spacedMatch(14))

        val resultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(resultHost, spacedMatch(14))

        fun renderLearnedResult(code: IrCode, message: String) {
            resultHost.removeAllViews()
            learnedCandidate = code
            val analysis = IrSignalAnalyzer.analyze(code)
            val resultCard = card(18)
            resultCard.addView(bodyText("✓  Señal detectada", 16f, IOS_GREEN, Typeface.BOLD), spacedMatch(8))
            resultCard.addView(bodyText(message, 13f, Color.LTGRAY), spacedMatch(6))
            resultCard.addView(bodyText(
                "Portadora  " + code.effectiveCarrierHz / 1000 + " kHz  ·  " +
                    code.durationsMicros.size + " tiempos  ·  " + code.durationMillis + " ms",
                12f,
                IOS_SECONDARY
            ), spacedMatch(6))
            resultCard.addView(bodyText(
                "Protocolo probable: " + analysis.protocolHint + " (" + analysis.confidence + "%)",
                13f,
                IOS_SECONDARY
            ), spacedMatch(10))
            val signalName = oledInput("Nombre del botón").apply {
                val suggested = if (guidedLearning) {
                    guidedButtonNames(selectedCategory)
                        .getOrElse(guidedIndex) { "Power" }
                } else {
                    "Power"
                }
                setText(suggested)
                selectAll()
            }
            resultCard.addView(signalName, spacedMatch(8))
            if (guidedLearning) {
                val quickName = outlineButton("✓  ELEGIR NOMBRE RÁPIDO")
                resultCard.addView(quickName, spacedMatch(10))
                quickName.setOnClickListener {
                    val names = guidedButtonNames(selectedCategory)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Nombre del botón")
                        .setItems(names.toTypedArray()) { _, which ->
                            signalName.setText(names[which])
                            signalName.selectAll()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val test = primaryButton("⌁  PROBAR")
            val save = outlineButton("⇩  GUARDAR")
            actions.addView(test, weighted())
            actions.addView(space(dp(10)))
            actions.addView(save, weighted())
            resultCard.addView(actions)
            resultHost.addView(resultCard)

            test.setOnClickListener { sendAsync(code, status, screen) }
            save.setOnClickListener {
                val name = signalName.text.toString().trim().ifBlank { "Power" }
                val storedCode = code.copy(
                    id = "learned:" + name.replace(":", "_") + ":" +
                        selectedCategory.name + ":" + java.util.UUID.randomUUID()
                )
                val learned = store.loadLearned()
                learned += storedCode
                store.saveLearned(learned)
                learnedCandidate = storedCode
                toast("Guardado: " + name)
                advanceGuidedLearning(selectedCategory)
                if (isScreenActive(screen)) showLearn()
            }
        }

        fun doCapture(validated: Boolean) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                status.text = "Concede permiso de micrófono y vuelve a pulsar Capturar."
                return
            }
            val currentInput = learner.inspectInput()
            if (!currentInput.isExternal) {
                status.text = "No se detecta una entrada externa compatible."
                return
            }

            val selectedHz = intArrayOf(0, 36_000, 38_000, 40_000, 56_000)[selectedLearnCarrierIndex]
            val captures = if (validated) 3 else 1
            status.text = if (validated) "Capturando 3 muestras…" else "Capturando durante ~1,3 s…"
            capture.isEnabled = false
            validate.isEnabled = false
            worker.execute {
                val result = runCatching {
                    val successful = mutableListOf<IrCode>()
                    repeat(captures) {
                        learner.capture(if (selectedHz == 0) 38_000 else selectedHz).code?.let(successful::add)
                    }
                    require(successful.isNotEmpty()) { "No se detectó una trama IR clara." }
                    val base = successful.first()
                    val inferred = if (selectedHz == 0) inferCarrier(IrSignalAnalyzer.analyze(base).protocolHint) else selectedHz
                    base.copy(carrierHz = inferred) to
                        if (validated) "Validación x3 completada: " + successful.size + "/3 capturas válidas."
                        else "Captura reconstruida: " + base.durationsMicros.size + " segmentos."
                }
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    capture.isEnabled = true
                    validate.isEnabled = true
                    result.onSuccess {
                        status.text = it.second
                        renderLearnedResult(it.first, it.second)
                    }.onFailure {
                        status.text = "Error de captura: " + (it.message ?: "desconocido")
                    }
                }
            }
        }
        capture.setOnClickListener { doCapture(false) }
        validate.setOnClickListener { doCapture(true) }

        val learned = store.loadLearned()
        if (learned.isEmpty()) {
            body.addView(emptyState(
                "Aún no hay botones aprendidos",
                "Puedes aprenderlos con un receptor IR o importar un archivo .ir de Flipper."
            ), spacedMatch(18))
        } else {
            body.addView(sectionHeader("Biblioteca aprendida     " + learned.size))
            learned.forEach { code ->
                val row = card(16).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bodyText("⌁", 20f, IOS_RED), LinearLayout.LayoutParams(dp(34), dp(44)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(bodyText(learnedName(code), 15f, Color.WHITE, Typeface.BOLD))
                        addView(bodyText(
                            (code.effectiveCarrierHz / 1000).toString() + " kHz · " +
                                code.durationsMicros.size + " tiempos",
                            12f,
                            IOS_SECONDARY
                        ))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(bodyText("›", 26f, IOS_SECONDARY))
                    setOnClickListener { sendAsync(code, screenStatusText("Transmitiendo…"), screen) }
                }
                body.addView(row, spacedMatch(8))
            }
        }
    }

    private fun showSavedDevices() {
        val screen = beginScreen()
        val body = installScreenBody("Mis equipos")
        val devices = store.loadDevices()
        val remotes = store.loadRemotes()
        val history = store.loadWorked()

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        metrics.addView(metricCard(devices.size, "Equipos", "▣"), weighted())
        metrics.addView(space(dp(8)))
        metrics.addView(metricCard(remotes.size, "Mandos", "▤"), weighted())
        metrics.addView(space(dp(8)))
        metrics.addView(metricCard(history.size, "Funcionaron", "✓"), weighted())
        body.addView(metrics, spacedMatch(20))

        if (remotes.isNotEmpty()) {
            body.addView(sectionHeader("▤  Mis mandos"))
            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            remotes.chunked(2).forEach { rowRemotes ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                rowRemotes.forEachIndexed { index, remote ->
                    val tile = card(18).apply {
                        gravity = Gravity.CENTER
                        addView(bodyText("▤", 28f, IOS_RED).apply {
                            gravity = Gravity.CENTER
                        }, spacedMatch(6))
                        addView(bodyText(remote.name, 15f, Color.WHITE, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                            maxLines = 2
                        }, spacedMatch(4))
                        addView(bodyText(
                            remote.buttons.size.toString() + " botones · " + remote.category.shortTitle,
                            11f,
                            IOS_SECONDARY
                        ).apply { gravity = Gravity.CENTER })
                        setOnClickListener {
                            showCustomRemote(remote, screen)
                        }
                        setOnLongClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Eliminar mando")
                                .setMessage("¿Eliminar " + remote.name + "?")
                                .setPositiveButton("Eliminar") { _, _ ->
                                    store.removeRemote(remote.id)
                                    if (isScreenActive(screen)) showSavedDevices()
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                            true
                        }
                    }
                    row.addView(tile, LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (index == 0 && rowRemotes.size > 1) {
                            marginEnd = dp(6)
                        } else if (index > 0) {
                            marginStart = dp(6)
                        }
                    })
                }
                if (rowRemotes.size == 1) {
                    row.addView(Space(this), LinearLayout.LayoutParams(
                        0,
                        1,
                        1f
                    ).apply { marginStart = dp(6) })
                }
                grid.addView(row, spacedMatch(12))
            }
            body.addView(grid, spacedMatch(10))
        }

        if (devices.isNotEmpty()) {
            body.addView(sectionHeader("⚡  Acceso rápido"))
            devices.forEach { device ->
                val row = card(20).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = categoryGlyph(device.category)
                        textSize = 24f
                        setTextColor(IOS_RED)
                        gravity = Gravity.CENTER
                        background = roundedDrawable(Color.argb(36, 255, 59, 48), 15)
                    }, LinearLayout.LayoutParams(dp(54), dp(54)))

                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, dp(8), 0)
                        addView(bodyText(device.name, 16f, Color.WHITE, Typeface.BOLD))
                        addView(bodyText(device.code.displayName, 12f, IOS_SECONDARY))
                        addView(bodyText(device.category.title, 11f, IOS_SECONDARY))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val power = TextView(this@MainActivity).apply {
                        text = "⏻"
                        textSize = 24f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background = roundedDrawable(IOS_RED, 24)
                    }
                    addView(power, LinearLayout.LayoutParams(dp(46), dp(46)))
                    setOnClickListener {
                        sendAsync(device.code, screenStatusText("Transmitiendo…"), screen)
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Eliminar equipo")
                            .setMessage("¿Eliminar " + device.name + "?")
                            .setPositiveButton("Eliminar") { _, _ ->
                                val updated = store.loadDevices()
                                updated.removeAll { it.id == device.id }
                                store.saveDevices(updated)
                                if (isScreenActive(screen)) showSavedDevices()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                        true
                    }
                }
                body.addView(row, spacedMatch(10))
            }
        }

        if (history.isNotEmpty()) {
            val historyHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(bodyText("↻  Historial de aciertos", 18f, Color.WHITE, Typeface.BOLD),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val clear = TextView(this).apply {
                text = "Borrar"
                textSize = 12f
                setTextColor(IOS_RED)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    store.clearWorked()
                    if (isScreenActive(screen)) showSavedDevices()
                }
            }
            historyHeader.addView(clear)
            body.addView(historyHeader, spacedMatch(8))

            history.take(8).forEach { record ->
                val date = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT
                ).format(java.util.Date(record.createdAt))
                val row = card(16).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bodyText("✓", 22f, IOS_GREEN), LinearLayout.LayoutParams(dp(34), dp(44)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(bodyText(record.code.displayName, 14f, Color.WHITE, Typeface.BOLD))
                        addView(bodyText(
                            record.code.sourceLabel + " · " + record.code.effectiveCarrierHz / 1000 + " kHz",
                            12f,
                            IOS_SECONDARY
                        ))
                        addView(bodyText(date, 11f, IOS_SECONDARY))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val send = outlineButton("⌁")
                    addView(send, LinearLayout.LayoutParams(dp(48), dp(42)))
                    send.setOnClickListener {
                        sendAsync(record.code, screenStatusText("Transmitiendo…"), screen)
                    }
                }
                body.addView(row, spacedMatch(8))
            }
        }

        if (devices.isEmpty() && remotes.isEmpty() && history.isEmpty()) {
            body.addView(emptyState(
                "Tu biblioteca está vacía",
                "Cuando encuentres un código que funcione, aparecerá aquí para que puedas volver a usarlo en segundos."
            ), spacedMatch(40))
        }
    }

    private fun showDiagnostics() {
        val screen = beginScreen()
        val body = installScreenBody("Diagnóstico")
        val input = learner.inspectInput()
        val outputReady = transmitter.active().isAvailable()

        val compatibility = card(18)
        compatibility.addView(bodyText("✓  Compatibilidad del accesorio", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(10))
        compatibility.addView(statusLine(
            "Transmisión",
            transmitter.diagnostics(),
            outputReady
        ), spacedMatch(8))
        compatibility.addView(statusLine(
            "Aprendizaje",
            input.description,
            input.isExternal
        ), spacedMatch(8))
        val conclusion = when {
            outputReady && input.isExternal ->
                "Compatible a nivel de audio para transmitir y aprender. Una captura válida confirmará el receptor IR."
            outputReady ->
                "Compatible para transmitir. No se detecta entrada externa: el aprendizaje IR no está disponible con el accesorio conectado."
            input.isExternal ->
                "Se detecta entrada externa para aprendizaje, pero la salida no parece preparada para transmitir IR."
            else ->
                "Pulsa «Comprobar accesorio». Para transmitir se necesita IR integrado o salida estéreo; para aprender, una entrada externa real."
        }
        compatibility.addView(bodyText(conclusion, 12f, IOS_SECONDARY), spacedMatch(10))
        val check = primaryButton("⌁  COMPROBAR ACCESORIO")
        compatibility.addView(check)
        body.addView(compatibility, spacedMatch(14))
        check.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            } else if (isScreenActive(screen)) {
                showDiagnostics()
            }
        }

        val route = card(18)
        route.addView(bodyText("⌁  " + transmitter.active().name, 15f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        route.addView(bodyText(transmitter.diagnostics(), 13f, IOS_SECONDARY), spacedMatch(10))
        route.addView(divider(), spacedMatch(10))
        route.addView(bodyText("✓  Audio mono: DESACTIVADO", 14f, Color.LTGRAY), spacedMatch(6))
        route.addView(bodyText("≡  Balance: centrado", 14f, Color.LTGRAY), spacedMatch(6))
        route.addView(bodyText("🔊  Volumen multimedia: 100 %", 14f, Color.LTGRAY), spacedMatch(8))
        val sound = outlineButton("AJUSTES DE SONIDO")
        route.addView(sound)
        sound.setOnClickListener { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
        body.addView(route, spacedMatch(14))

        val transmissionCard = card(18)
        transmissionCard.addView(
            bodyText("Modo de transmisión", 16f, Color.WHITE, Typeface.BOLD),
            spacedMatch(8)
        )
        transmissionCard.addView(
            segmentedControl(
                AudioTransmissionMode.entries.map { it.title },
                transmitter.audioTransmissionMode.ordinal
            ) { index ->
                transmitter.audioTransmissionMode = AudioTransmissionMode.entries[index]
                if (isScreenActive(screen)) showDiagnostics()
            },
            spacedMatch(8)
        )
        transmissionCard.addView(
            bodyText(
                transmitter.audioTransmissionMode.detail,
                12f,
                IOS_SECONDARY
            )
        )
        body.addView(transmissionCard, spacedMatch(14))

        val carrierCard = card(18)
        carrierCard.addView(bodyText("Prueba de portadora", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        carrierCard.addView(bodyText(
            "La cámara de otro teléfono puede servir para comprobar que los LED IR emiten, aunque no confirma que la frecuencia sea correcta.",
            12f,
            IOS_SECONDARY
        ), spacedMatch(10))
        val tests = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(36_000, 37_000, 38_000, 39_000, 40_000).forEachIndexed { index, hz ->
            val button = outlineButton((hz / 1000).toString() + " kHz")
            tests.addView(button, weighted())
            if (index < 4) tests.addView(space(dp(6)))
            button.setOnClickListener {
                val status = screenStatusText("Probando " + hz / 1000 + " kHz…")
                sendTestCarrier(hz, status)
            }
        }
        carrierCard.addView(tests)
        body.addView(carrierCard, spacedMatch(14))

        val how = card(18)
        how.addView(bodyText("Cómo funciona", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        how.addView(bodyText(
            "La app convierte cada señal IR en audio estéreo antifase. En este tipo de emisor, los dos canales excitan los LED en sentidos opuestos, por eso la frecuencia de audio es aproximadamente la mitad de la portadora IR deseada.",
            14f,
            IOS_SECONDARY
        ))
        body.addView(how, spacedMatch(14))

        val settingsWhy = card(18)
        settingsWhy.addView(bodyText("Por qué importan los ajustes de audio", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(10))
        settingsWhy.addView(bodyText(
            "🔊  Volumen: controla la amplitud eléctrica. Si baja demasiado, los LED IR reciben menos corriente y cae mucho el alcance.",
            13f,
            Color.LTGRAY
        ), spacedMatch(8))
        settingsWhy.addView(bodyText(
            "≡  Balance: debe estar centrado porque el adaptador usa la diferencia entre L y R.",
            13f,
            Color.LTGRAY
        ), spacedMatch(8))
        settingsWhy.addView(bodyText(
            "◖◗  Audio mono: debe estar desactivado. L y R están en oposición de fase y al mezclarlos pueden cancelarse casi por completo.",
            13f,
            Color.LTGRAY
        ))
        body.addView(settingsWhy, spacedMatch(14))

        val oled = card(18)
        val oledRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText("◐  Pantalla OLED", 16f, Color.WHITE, Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val oledToggle = Switch(this).apply {
            isChecked = oledMode()
            thumbTintList = android.content.res.ColorStateList.valueOf(IOS_RED)
        }
        oledRow.addView(oledToggle)
        oled.addView(oledRow, spacedMatch(8))
        val oledDescription = bodyText(
            if (oledMode()) "Negro puro activado. Reduce los píxeles iluminados y aumenta el contraste en pantallas OLED."
            else "Usando la apariencia estándar oscura de Android.",
            12f,
            IOS_SECONDARY
        )
        oled.addView(oledDescription, spacedMatch(8))
        oled.addView(bodyText("●   ●   ●   Negro real · superficies mínimas · acento rojo", 11f, IOS_SECONDARY))
        body.addView(oled, spacedMatch(14))
        oledToggle.setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean(PREF_OLED_MODE, enabled).apply()
            if (isScreenActive(screen)) selectTab(currentTab)
        }

        val backup = card(18)
        backup.addView(bodyText("Copia de seguridad", 16f, Color.WHITE, Typeface.BOLD), spacedMatch(8))
        backup.addView(bodyText(
            "Guarda en un único archivo tus equipos, señales aprendidas, mandos y códigos que funcionaron.",
            12f,
            IOS_SECONDARY
        ), spacedMatch(10))
        val backupActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val export = outlineButton("↑  EXPORTAR")
        val restore = outlineButton("↓  RESTAURAR")
        backupActions.addView(export, weighted())
        backupActions.addView(space(dp(10)))
        backupActions.addView(restore, weighted())
        backup.addView(backupActions)
        export.setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, store.exportBackup())
            }
            startActivity(Intent.createChooser(share, "Exportar copia de seguridad"))
        }
        restore.setOnClickListener { openBackupPicker() }
        body.addView(backup, spacedMatch(14))

        val updater = AppUpdater(this)
        var pendingRelease: AppRelease? = null
        val updates = card(18)
        updates.addView(
            bodyText("↻  Actualizaciones", 16f, Color.WHITE, Typeface.BOLD),
            spacedMatch(8)
        )
        updates.addView(
            bodyText(
                "Instalada: " + updater.currentVersion + " (" + updater.currentBuild + ")",
                13f,
                IOS_SECONDARY
            ),
            spacedMatch(6)
        )
        val availableVersion = bodyText("", 13f, IOS_GREEN, Typeface.BOLD).apply {
            visibility = View.GONE
        }
        val updateStatus = infoText(
            "Comprueba GitHub Releases para saber si hay una APK más reciente."
        ).apply { setPadding(0, 0, 0, dp(8)) }
        val checkUpdate = outlineButton("↻  BUSCAR ACTUALIZACIÓN")
        val downloadUpdate = primaryButton("↓  DESCARGAR ACTUALIZACIÓN").apply {
            visibility = View.GONE
        }
        updates.addView(availableVersion, spacedMatch(6))
        updates.addView(updateStatus, spacedMatch(8))
        updates.addView(checkUpdate, spacedMatch(8))
        updates.addView(downloadUpdate)
        body.addView(updates, spacedMatch(14))

        checkUpdate.setOnClickListener {
            checkUpdate.isEnabled = false
            checkUpdate.text = "COMPROBANDO…"
            updateStatus.text = "Buscando actualizaciones en GitHub…"
            worker.execute {
                val result = updater.checkForUpdates()
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    checkUpdate.isEnabled = true
                    checkUpdate.text = "↻  BUSCAR ACTUALIZACIÓN"
                    updateStatus.text = result.message
                    pendingRelease = result.release
                    if (result.updateAvailable && result.release != null) {
                        availableVersion.text =
                            "Disponible: " + updater.releaseLabel(result.release)
                        availableVersion.visibility = View.VISIBLE
                        downloadUpdate.visibility = View.VISIBLE
                    } else {
                        availableVersion.visibility = View.GONE
                        downloadUpdate.visibility = View.GONE
                    }
                }
            }
        }

        downloadUpdate.setOnClickListener {
            val release = pendingRelease ?: return@setOnClickListener
            updater.openDownload(release)
        }

        val credits = card(18)
        credits.addView(
            bodyText("★  Créditos", 16f, Color.WHITE, Typeface.BOLD),
            spacedMatch(8)
        )
        credits.addView(
            bodyText("Gokuencinar", 17f, IOS_RED, Typeface.BOLD),
            spacedMatch(4)
        )
        credits.addView(
            bodyText(
                "Creador y desarrollador de TVBGoneAudio y TVBGoneAndroid.",
                13f,
                Color.LTGRAY
            ),
            spacedMatch(8)
        )
        credits.addView(
            bodyText(
                "Versión Android basada funcional y visualmente en TVBGoneAudio. " +
                    "Incluye códigos TV-B-Gone y datos compatibles de Flipper-IRDB.",
                12f,
                IOS_SECONDARY
            ),
            spacedMatch(10)
        )
        val creditActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val project = outlineButton("GITHUB")
        val licenses = outlineButton("LICENCIAS")
        creditActions.addView(project, weighted())
        creditActions.addView(space(dp(10)))
        creditActions.addView(licenses, weighted())
        credits.addView(creditActions)
        project.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Gokuencinar/TVBGoneAndroid")
                )
            )
        }
        licenses.setOnClickListener {
            val notice = resources.openRawResource(R.raw.third_party_notices)
                .bufferedReader()
                .use { it.readText() }
            AlertDialog.Builder(this)
                .setTitle("Licencias de terceros")
                .setMessage(notice)
                .setPositiveButton("Cerrar", null)
                .show()
        }
        body.addView(credits, spacedMatch(20))
    }

    private fun showRemoteBuilderDialog(category: DeviceCategory) {
        val learned = store.loadLearned().filter {
            learnedCategory(it)?.let { value -> value == category } ?: true
        }
        if (learned.isEmpty()) {
            toast("Primero aprende o importa algún botón de esta categoría.")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        val name = oledInput("Nombre").apply {
            setText("Mi mando")
            selectAll()
        }
        container.addView(name, spacedMatch(12))

        val list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_multiple_choice,
                learned.map(::learnedName)
            )
        }
        container.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(300)
        ))

        AlertDialog.Builder(this)
            .setTitle("Nuevo mando")
            .setView(container)
            .setPositiveButton("Crear mando") { _, _ ->
                val buttons = learned.indices
                    .filter { list.isItemChecked(it) }
                    .map { index ->
                        CustomRemoteButton(
                            name = learnedName(learned[index]),
                            code = learned[index]
                        )
                    }
                if (buttons.isEmpty()) {
                    toast("Selecciona al menos un botón.")
                } else {
                    val remote = CustomRemote(
                        name = name.text.toString().trim().ifBlank { "Mi mando" },
                        category = category,
                        buttons = buttons
                    )
                    store.addRemote(remote)
                    toast("Mando creado: " + remote.name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCustomRemote(remote: CustomRemote, screen: Long) {
        val names = remote.buttons.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(remote.name)
            .setItems(names) { _, which ->
                val button = remote.buttons.getOrNull(which) ?: return@setItems
                sendAsync(
                    button.code,
                    screenStatusText("Enviando " + button.name + "…"),
                    screen
                )
            }
            .setNeutralButton("Compartir .ir") { _, _ ->
                val text = FlipperIrCodec.exportRawRecords(
                    remote.buttons.map { it.name to it.code }
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, remote.name + ".ir")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(share, "Compartir mando"))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openBackupPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        startActivityForResult(intent, REQUEST_RESTORE_BACKUP)
    }

    private fun sendTestCarrier(hz: Int, status: TextView) {
        val code = IrCode("test-" + hz, hz, listOf(300_000, 50_000))
        sendAsync(code, status)
    }

    private fun sendAsync(code: IrCode, status: TextView, screen: Long = screenGeneration) {
        val active = transmitter.active()
        status.text = "Enviando " + code.displayName + " mediante " + active.name + "…"
        worker.execute {
            val result = runCatching { active.send(code) }
            runOnUiThread {
                if (!isScreenActive(screen)) return@runOnUiThread
                result.onSuccess {
                    status.text = "Enviado: " + code.displayName + " · " + code.effectiveCarrierHz + " Hz"
                    if (status.parent == null) {
                        toast("Enviado: " + code.displayName)
                    }
                }.onFailure {
                    status.text = "Error: " + (it.message ?: "desconocido")
                    if (status.parent == null) {
                        toast("Error: " + (it.message ?: "desconocido"))
                    }
                }
            }
        }
    }

    private fun showImportedSignals(signals: List<ImportedIrSignal>, category: DeviceCategory) {
        if (signals.isEmpty()) return toast("No hay señales compatibles.")
        val names = signals.map { it.name + " · " + it.sourceDescription }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Señales")
            .setItems(names) { _, which ->
                val signal = signals[which]
                AlertDialog.Builder(this)
                    .setTitle(signal.name)
                    .setMessage(
                        signal.sourceDescription + "\n" +
                            signal.code.effectiveCarrierHz + " Hz · " +
                            signal.code.durationMillis + " ms"
                    )
                    .setPositiveButton("Probar") { _, _ ->
                        val temp = infoText("")
                        sendAsync(signal.code, temp)
                        toast("Enviando " + signal.name)
                    }
                    .setNeutralButton("Guardar") { _, _ ->
                        saveDeviceDialog(category, signal.code, signal.name)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    private fun showCodeChooser(title: String, codes: List<IrCode>, onPick: (IrCode) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(codes.map { it.displayName }.toTypedArray()) { _, which -> onPick(codes[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveDeviceDialog(category: DeviceCategory, code: IrCode, suggested: String = code.displayName) {
        val input = EditText(this).apply {
            hint = defaultDeviceName(category)
            if (suggested != code.displayName) {
                setText(suggested)
                selectAll()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Guardar")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim().ifBlank { defaultDeviceName(category) }
                store.addDevice(SavedDevice(name = name, category = category, code = code))
                toast("Guardado: " + name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openIrFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_IMPORT_IR)
    }

    @Deprecated("Legacy Activity result is used deliberately to keep the project dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode != REQUEST_IMPORT_IR && requestCode != REQUEST_RESTORE_BACKUP) return

        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            toast("No se pudo leer el archivo.")
            return
        }

        when (requestCode) {
            REQUEST_IMPORT_IR -> {
                importedSignals = FlipperIrCodec.parse(text)
                if (importedSignals.isEmpty()) {
                    toast("El archivo no contiene señales Flipper compatibles.")
                } else {
                    val learned = store.loadLearned()
                    importedSignals.forEach { signal ->
                        learned += signal.code.copy(
                            id = "learned:" + signal.name.replace(":", "_") + ":" +
                                selectedCategory.name + ":" + java.util.UUID.randomUUID()
                        )
                    }
                    store.saveLearned(learned)
                    toast("Importadas " + importedSignals.size + " señal(es).")
                    if (currentTab == 3) showLearn()
                }
            }

            REQUEST_RESTORE_BACKUP -> {
                AlertDialog.Builder(this)
                    .setTitle("Restaurar copia")
                    .setMessage(
                        "Se sustituirá la biblioteca actual de equipos, mandos, señales " +
                            "aprendidas e historial. ¿Continuar?"
                    )
                    .setPositiveButton("Restaurar") { _, _ ->
                        runCatching { store.restoreBackup(text) }
                            .onSuccess { summary ->
                                toast(
                                    "Copia restaurada: " +
                                        summary.devices + " equipos · " +
                                        summary.remotes + " mandos · " +
                                        summary.learned + " señales · " +
                                        summary.worked + " aciertos"
                                )
                                selectTab(currentTab)
                            }
                            .onFailure {
                                toast("Copia no válida: " + (it.message ?: "desconocido"))
                            }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && currentTab == 3) {
            showLearn()
        }
    }

    private fun installScreenBody(title: String, onBack: (() -> Unit)? = null): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground())
        }

        val navigation = FrameLayout(this).apply {
            setBackgroundColor(screenBackground())
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        navigation.addView(titleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
            Gravity.CENTER
        ))
        if (onBack != null) {
            val back = TextView(this).apply {
                text = "‹"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(IOS_RED)
                setPadding(dp(6), 0, dp(8), 0)
                isClickable = true
                setOnClickListener { onBack() }
            }
            navigation.addView(back, FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.START or Gravity.CENTER_VERTICAL
            ))
        }
        page.addView(navigation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(28))
            setBackgroundColor(screenBackground())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(screenBackground())
            addView(body, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        contentHost.replace(page)
        return body
    }

    private fun beginScreen(): Long {
        scanner.stop()
        screenGeneration += 1
        return screenGeneration
    }

    private fun isScreenActive(screen: Long): Boolean =
        screenGeneration == screen && !isFinishing && !isDestroyed

    private fun FrameLayout.replace(view: View) {
        if (view.parent === this) return
        removeAllViews()
        addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(IOS_SECONDARY)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun bodyText(
        value: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        includeFontPadding = false
    }

    private fun sectionHeader(value: String) = bodyText(
        value,
        18f,
        Color.WHITE,
        Typeface.BOLD
    ).apply {
        setPadding(dp(2), dp(6), dp(2), dp(10))
    }

    private fun card(radius: Int = 18) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedDrawable(
            if (oledMode()) IOS_SURFACE else Color.rgb(38, 38, 40),
            radius,
            if (oledMode()) IOS_BORDER else Color.rgb(58, 58, 60)
        )
    }

    private fun roundedDrawable(fillColor: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }

    private fun circleDrawable(fillColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }

    private fun primaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        background = roundedDrawable(IOS_RED, 12)
        minHeight = dp(50)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        stateListAnimator = null
    }

    private fun outlineButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        background = roundedDrawable(Color.argb(12, 255, 255, 255), 12, Color.argb(40, 255, 255, 255))
        minHeight = dp(46)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        stateListAnimator = null
    }

    private fun tintedButton(value: String, tint: Int) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        background = roundedDrawable(tint, 12)
        minHeight = dp(48)
        stateListAnimator = null
    }

    private fun oledInput(hintValue: String) = EditText(this).apply {
        hint = hintValue
        setTextColor(Color.WHITE)
        setHintTextColor(IOS_SECONDARY)
        textSize = 15f
        setSingleLine(true)
        background = roundedDrawable(
            if (oledMode()) IOS_SURFACE else Color.rgb(44, 44, 46),
            12,
            if (oledMode()) IOS_BORDER else Color.rgb(70, 70, 72)
        )
        setPadding(dp(12), dp(11), dp(12), dp(11))
    }

    private fun segmentedControl(
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = roundedDrawable(
                if (oledMode()) Color.argb(12, 255, 255, 255) else Color.rgb(44, 44, 46),
                10,
                if (oledMode()) Color.argb(18, 255, 255, 255) else Color.rgb(68, 68, 70)
            )
            tag = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        }
        labels.forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                textSize = if (labels.size >= 4) 11f else 12f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                minHeight = dp(34)
            }
            row.addView(item, LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
            ))
            item.setOnClickListener {
                row.tag = index
                updateSegmentAppearance(row)
                onSelected(index)
            }
        }
        updateSegmentAppearance(row)
        return row
    }

    private fun wireSegmentCallback(row: LinearLayout, callback: (Int) -> Unit) {
        for (i in 0 until row.childCount) {
            row.getChildAt(i).setOnClickListener {
                row.tag = i
                updateSegmentAppearance(row)
                callback(i)
            }
        }
    }

    private fun setSegmentEnabled(row: LinearLayout, enabled: Boolean) {
        row.alpha = if (enabled) 1f else 0.45f
        for (i in 0 until row.childCount) {
            row.getChildAt(i).apply {
                isEnabled = enabled
                isClickable = enabled
            }
        }
    }

    private fun updateSegmentAppearance(row: LinearLayout) {
        val selected = (row.tag as? Int) ?: 0
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i) as? TextView ?: continue
            val active = i == selected
            item.setTextColor(if (active) Color.WHITE else IOS_SECONDARY)
            item.background = if (active) roundedDrawable(IOS_RED, 8)
            else roundedDrawable(Color.TRANSPARENT, 8)
        }
    }

    private fun browserRow(title: String, trailing: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(8), dp(5), dp(6), dp(5))
            addView(bodyText(title, 14f, Color.WHITE), LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(bodyText(trailing, 12f, IOS_SECONDARY))
            isClickable = true
            setOnClickListener { action() }
        }

    private fun emptyState(title: String, message: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
            addView(bodyText("⌾", 38f, IOS_SECONDARY).apply { gravity = Gravity.CENTER })
            addView(bodyText(title, 17f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(6))
            })
            addView(bodyText(message, 13f, IOS_SECONDARY).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
        }

    private fun metricCard(value: Int, labelText: String, glyph: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = roundedDrawable(IOS_SURFACE, 17, IOS_BORDER)
            addView(bodyText(glyph, 18f, IOS_RED).apply { gravity = Gravity.CENTER })
            addView(bodyText(value.toString(), 20f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(2))
            })
            addView(bodyText(labelText, 10f, IOS_SECONDARY).apply { gravity = Gravity.CENTER })
        }

    private fun statusLine(title: String, subtitle: String, ok: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bodyText(if (ok) "✓" else "✕", 20f, if (ok) IOS_GREEN else IOS_RED),
                LinearLayout.LayoutParams(dp(34), dp(40)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bodyText(title, 14f, Color.WHITE, Typeface.BOLD))
                addView(bodyText(subtitle, 11f, IOS_SECONDARY))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.argb(25, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun controlHero(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val icon = TextView(this@MainActivity).apply {
                text = categoryGlyph(selectedCategory)
                textSize = 44f
                gravity = Gravity.CENTER
                setTextColor(IOS_RED)
                background = circleDrawable(Color.argb(40, 255, 59, 48))
            }
            addView(icon, LinearLayout.LayoutParams(dp(104), dp(104)))
            addView(bodyText("TVBGONEANDROID", 11f, IOS_SECONDARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.16f
                setPadding(0, dp(12), 0, dp(7))
            })
            addView(bodyText(selectedCategory.title, 21f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            })
            addView(bodyText(categoryExplanation(selectedCategory), 13f, IOS_SECONDARY).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(8), dp(8), dp(8), 0)
            })
        }

    private fun accessoryStatusCard(): View {
        val active = transmitter.active()
        val ready = active.isAvailable()
        val tint = if (ready) IOS_GREEN else Color.rgb(255, 149, 0)
        return card(20).apply {
            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(bodyText(if (ready) "✓" else "!", 22f, tint),
                    LinearLayout.LayoutParams(dp(38), dp(42)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(bodyText(
                        if (ready) "Accesorio listo" else "Revisa el accesorio",
                        15f,
                        Color.WHITE,
                        Typeface.BOLD
                    ))
                    addView(bodyText(active.name, 12f, IOS_SECONDARY))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(this@MainActivity).apply {
                    background = circleDrawable(tint)
                }, LinearLayout.LayoutParams(dp(9), dp(9)))
            }
            addView(top, spacedMatch(8))
            addView(bodyText(transmitter.diagnostics(), 11f, IOS_SECONDARY), spacedMatch(8))
            addView(bodyText(
                "Recomendado: volumen 100 %, Audio mono desactivado y balance centrado.",
                11f,
                IOS_SECONDARY
            ))
        }
    }

    private fun categoryGlyph(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "▣"
        DeviceCategory.AIR_CONDITIONER -> "❄"
        DeviceCategory.PROJECTOR -> "▰"
    }

    private fun categoryButtonTitle(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "⏻  APAGAR TELEVISORES"
        DeviceCategory.AIR_CONDITIONER -> "⏻  APAGAR AIRES"
        DeviceCategory.PROJECTOR -> "⏻  APAGAR PROYECTORES"
    }

    private fun defaultDeviceName(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "Mi TV"
        DeviceCategory.AIR_CONDITIONER -> "Mi aire"
        DeviceCategory.PROJECTOR -> "Mi proyector"
    }

    private fun categoryExplanation(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION ->
            "Prueba primero los códigos universales y TV-B-Gone que ya sabemos que funcionan, y después la base ampliada."
        DeviceCategory.AIR_CONDITIONER ->
            "Recorre señales POWER/OFF de mandos de aire acondicionado, priorizando capturas RAW."
        DeviceCategory.PROJECTOR ->
            "Recorre señales POWER/OFF de proyectores de distintas marcas y modelos."
    }

    private fun paceHelp(pace: ScanPace): String = when (pace) {
        ScanPace.FAST -> "Recorre los códigos rápidamente."
        ScanPace.IDENTIFY -> "Deja más tiempo entre códigos para poder pulsar «FUNCIONÓ»."
    }

    private fun inferCarrier(protocolHint: String): Int {
        val value = protocolHint.lowercase()
        return when {
            value.contains("rc5") || value.contains("rc6") -> 36_000
            value.contains("sirc") || value.contains("sony") || value.contains("pioneer") -> 40_000
            else -> 38_000
        }
    }

    private fun guidedButtonNames(category: DeviceCategory): List<String> = when (category) {
        DeviceCategory.TELEVISION -> listOf(
            "Power", "Vol +", "Vol -", "Mute", "Channel +", "Channel -",
            "Input", "Menu", "OK", "Arriba", "Abajo", "Izquierda", "Derecha", "Back"
        )
        DeviceCategory.AIR_CONDITIONER -> listOf(
            "Power", "Temp +", "Temp -", "Mode", "Fan", "Swing", "Cool", "Heat", "Auto"
        )
        DeviceCategory.PROJECTOR -> listOf(
            "Power", "Source", "Menu", "OK", "Arriba", "Abajo",
            "Izquierda", "Derecha", "Back", "Mute", "Freeze"
        )
    }

    private fun advanceGuidedLearning(category: DeviceCategory) {
        if (!guidedLearning) return
        val buttons = guidedButtonNames(category)
        if (guidedIndex + 1 < buttons.size) {
            guidedIndex += 1
        } else {
            guidedLearning = false
            guidedIndex = 0
        }
    }

    private fun learnedCategory(code: IrCode): DeviceCategory? {
        if (!code.id.startsWith("learned:")) return null
        val parts = code.id.split(":")
        if (parts.size < 4) return null
        return runCatching { DeviceCategory.valueOf(parts[2]) }.getOrNull()
    }

    private fun learnedName(code: IrCode): String {
        if (!code.id.startsWith("learned:")) return code.displayName
        return code.id.removePrefix("learned:").substringBefore(":").replace("_", " ").ifBlank { "Power" }
    }

    private fun detailsStatus(container: LinearLayout): TextView {
        val existing = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { it.tag == "send-status" }
        if (existing != null) return existing
        return infoText("").apply {
            tag = "send-status"
            setPadding(0, dp(8), 0, 0)
            container.addView(this)
        }
    }

    private fun screenStatusText(initial: String): TextView =
        infoText(initial).apply { tag = "detached-status" }

    private fun oledMode(): Boolean = preferences.getBoolean(PREF_OLED_MODE, true)

    private fun screenBackground(): Int =
        if (oledMode()) Color.BLACK else Color.rgb(18, 18, 18)

    private fun simpleTextWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun spacedMatch(bottomDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottomDp) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun space(width: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        screenGeneration += 1
        scanner.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}
