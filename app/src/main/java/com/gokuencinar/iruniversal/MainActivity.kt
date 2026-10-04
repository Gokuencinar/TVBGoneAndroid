package com.gokuencinar.iruniversal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.gokuencinar.iruniversal.flipper.FlipperIrCodec
import com.gokuencinar.iruniversal.flipper.ImportedIrSignal
import com.gokuencinar.iruniversal.ir.*
import com.gokuencinar.iruniversal.learn.IrLearner
import com.gokuencinar.iruniversal.learn.IrSignalAnalyzer
import com.gokuencinar.iruniversal.online.OnlineIrLibrary
import com.gokuencinar.iruniversal.online.OnlineLoadedRemote
import com.gokuencinar.iruniversal.online.OnlineIrRemote
import com.gokuencinar.iruniversal.online.OnlineIrSource
import com.gokuencinar.iruniversal.storage.AppStore
import com.gokuencinar.iruniversal.storage.CustomRemote
import com.gokuencinar.iruniversal.storage.CustomRemoteButton
import com.gokuencinar.iruniversal.storage.SavedDevice
import com.gokuencinar.iruniversal.update.AppRelease
import com.gokuencinar.iruniversal.update.AppUpdater
import java.util.concurrent.Executors
import java.io.File

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
        set(value) {
            field = value
            if (::preferences.isInitialized) {
                preferences.edit().putString(PREF_CATEGORY, value.name).apply()
            }
        }
    private var selectedLearnCategory = DeviceCategory.TELEVISION
        set(value) {
            field = value
            if (::preferences.isInitialized) {
                preferences.edit().putString(PREF_LEARN_CATEGORY, value.name).apply()
            }
        }
    private var selectedRegion = TvRegion.EUROPE
        set(value) {
            field = value
            if (::preferences.isInitialized) {
                preferences.edit().putString(PREF_REGION, value.name).apply()
            }
        }
    private var selectedPace = ScanPace.FAST
        set(value) {
            field = value
            if (::preferences.isInitialized) {
                preferences.edit().putString(PREF_SCAN_PACE, value.name).apply()
            }
        }
    private var selectedLearnCarrierIndex = 0
    private var guidedLearning = false
    private var guidedIndex = 0
    private var currentTab = 0
        set(value) {
            field = value.coerceIn(0, 5)
            if (::preferences.isInitialized) {
                preferences.edit().putInt(PREF_LAST_TAB, field).apply()
            }
        }
    private val bottomTabViews = mutableListOf<LinearLayout>()
    private var screenBackAction: (() -> Unit)? = null

    companion object {
        private const val REQUEST_MIC = 1001
        private const val REQUEST_IMPORT_IR = 1002
        private const val REQUEST_RESTORE_BACKUP = 1003
        private const val PREF_BROWSER_MODE = "irUniversal.localBrowserPresentation"
        private const val PREF_ONLINE_BROWSER_MODE = "irUniversal.onlineBrowserPresentation"
        private const val PREF_OLED_MODE = "irUniversal.oledMode"
        private const val PREF_CATEGORY = "irUniversal.selectedCategory"
        private const val PREF_LEARN_CATEGORY = "irUniversal.learnCategory"
        private const val PREF_REGION = "irUniversal.selectedRegion"
        private const val PREF_SCAN_PACE = "irUniversal.scanPace"
        private const val PREF_LAST_TAB = "irUniversal.lastTab"
        private const val PREF_REMOTE_ID = "irUniversal.remoteControl.selectedRemote"

        private val CYBER_CYAN = Color.rgb(0, 229, 255)
        private val CYBER_MAGENTA = Color.rgb(255, 43, 214)
        private val CYBER_PURPLE = Color.rgb(139, 92, 246)
        private val CYBER_GREEN = Color.rgb(57, 255, 136)
        private val CYBER_DANGER = Color.rgb(255, 72, 96)
        private val CYBER_WARNING = Color.rgb(255, 176, 32)
        private val CYBER_SURFACE = Color.rgb(15, 19, 27)
        private val CYBER_SURFACE_ALT = Color.rgb(21, 26, 36)
        private val CYBER_BORDER = Color.rgb(39, 62, 74)
        private val CYBER_MUTED = Color.rgb(148, 163, 184)

        // Legacy names are kept so the existing UI can inherit the new palette
        // without duplicating visual logic across every screen.
        private val IOS_RED = CYBER_MAGENTA
        private val IOS_GREEN = CYBER_GREEN
        private val IOS_SECONDARY = CYBER_MUTED
        private val IOS_SURFACE = Color.argb(210, 15, 19, 27)
        private val IOS_BORDER = Color.argb(145, 0, 229, 255)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transmitter = AutoIrTransmitter(this)
        scanner = IrScanner { transmitter.active() }
        store = AppStore(this)
        learner = IrLearner(applicationContext)
        preferences = getSharedPreferences("ir_universal_android", MODE_PRIVATE)

        val restoredCategory = runCatching {
            DeviceCategory.valueOf(
                preferences.getString(PREF_CATEGORY, DeviceCategory.TELEVISION.name)
                    ?: DeviceCategory.TELEVISION.name
            )
        }.getOrDefault(DeviceCategory.TELEVISION)
        selectedCategory = restoredCategory.takeIf {
            it == DeviceCategory.TELEVISION ||
                it == DeviceCategory.AIR_CONDITIONER ||
                it == DeviceCategory.PROJECTOR
        } ?: DeviceCategory.TELEVISION
        selectedLearnCategory = if (preferences.contains(PREF_LEARN_CATEGORY)) {
            runCatching {
                DeviceCategory.valueOf(
                    preferences.getString(PREF_LEARN_CATEGORY, DeviceCategory.TELEVISION.name)
                        ?: DeviceCategory.TELEVISION.name
                )
            }.getOrDefault(DeviceCategory.TELEVISION)
        } else {
            restoredCategory.takeIf {
                it == DeviceCategory.TELEVISION ||
                    it == DeviceCategory.AIR_CONDITIONER ||
                    it == DeviceCategory.PROJECTOR
            } ?: DeviceCategory.TELEVISION
        }
        selectedRegion = runCatching {
            TvRegion.valueOf(
                preferences.getString(PREF_REGION, TvRegion.EUROPE.name)
                    ?: TvRegion.EUROPE.name
            )
        }.getOrDefault(TvRegion.EUROPE)
        selectedPace = runCatching {
            ScanPace.valueOf(
                preferences.getString(PREF_SCAN_PACE, ScanPace.FAST.name)
                    ?: ScanPace.FAST.name
            )
        }.getOrDefault(ScanPace.FAST)
        currentTab = preferences.getInt(PREF_LAST_TAB, 0).coerceIn(0, 5)

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
            setPadding(dp(6), dp(6), dp(6), dp(5))
            background = cyberPanelDrawable(
                fill = Color.rgb(7, 10, 16),
                radiusDp = 0,
                stroke = Color.argb(110, 0, 229, 255)
            )
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64)
        ))

        addBottomTab("Control", R.drawable.ic_tab_power, 0)
        addBottomTab("Mando", R.drawable.ic_tab_remote, 5)
        addBottomTab("Códigos", R.drawable.ic_tab_codes, 1)
        addBottomTab("Equipos", R.drawable.ic_tab_star, 2)
        addBottomTab("Aprender", R.drawable.ic_tab_mic, 3)
        addBottomTab("Ajustes/Info", R.drawable.ic_tab_settings, 4)

        setContentView(root)
        selectTab(currentTab)
    }

    private fun selectTab(index: Int) {
        currentTab = index.coerceIn(0, 5)
        bottomTabViews.forEachIndexed { itemIndex, item ->
            val tabIndex = (item.tag as? Int) ?: itemIndex
            val selected = tabIndex == currentTab
            item.isSelected = selected
            val color = if (selected) CYBER_CYAN else IOS_SECONDARY
            (item.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (item.getChildAt(1) as? TextView)?.setTextColor(color)
            item.background = if (selected) {
                cyberPanelDrawable(
                    fill = Color.argb(34, 0, 229, 255),
                    radiusDp = 12,
                    stroke = Color.argb(105, 0, 229, 255)
                )
            } else {
                roundedDrawable(Color.TRANSPARENT, 12)
            }
        }
        when (currentTab) {
            0 -> showControl()
            1 -> showCodes()
            2 -> showSavedDevices()
            3 -> showLearn()
            4 -> showDiagnostics()
            else -> showRemoteControl()
        }
    }

    private fun addBottomTab(label: String, iconRes: Int, index: Int) {
        val item = LinearLayout(this).apply {
            tag = index
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(dp(2), dp(3), dp(2), dp(1))
            setOnClickListener {
                performClickHaptic()
                selectTab(index)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(IOS_SECONDARY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = if (label.length > 8) 9f else 10f
            gravity = Gravity.CENTER
            setTextColor(IOS_SECONDARY)
            maxLines = 1
        }
        item.addView(icon, LinearLayout.LayoutParams(dp(25), dp(25)))
        item.addView(text, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
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
                        setTextColor(CYBER_CYAN)
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
                        setTextColor(CYBER_MAGENTA)
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
            progressTintList = android.content.res.ColorStateList.valueOf(CYBER_CYAN)
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
        previous.contentDescription = "Código anterior"
        pause.contentDescription = "Pausar o reanudar barrido"
        next.contentDescription = "Código siguiente"
        transport.addView(previous, weighted())
        transport.addView(space(dp(8)))
        transport.addView(pause, weighted())
        transport.addView(space(dp(8)))
        transport.addView(next, weighted())
        activeCard.addView(transport, spacedMatch(10))
        val worked = tintedButton("✓  FUNCIONÓ", IOS_GREEN)
        activeCard.addView(worked, matchWrap())
        body.addView(activeCard, spacedMatch(18))

        fun setTransportEnabled(enabled: Boolean) {
            listOf(previous, pause, next).forEach { control ->
                control.isEnabled = enabled
                control.alpha = if (enabled) 1f else 0.42f
            }
        }
        setTransportEnabled(false)

        fun setScanConfigurationEnabled(enabled: Boolean) {
            setSegmentEnabled(categoryControl, enabled)
            regionControl?.let { setSegmentEnabled(it, enabled) }
            setSegmentEnabled(paceControl, enabled)
        }

        start.setOnClickListener {
            if (scanner.isRunning()) {
                scanner.stop()
                setKeepScreenOn(false)
                performClickHaptic()
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
                status.text = "El transmisor seleccionado no está disponible. Revisa Ajustes / Info."
                activeCard.visibility = View.VISIBLE
                return@setOnClickListener
            }

            status.text = "Iniciando " + codes.size + " códigos mediante " + active.name
            performClickHaptic()
            setKeepScreenOn(true)
            progress.progress = 0
            etaText.text = ""
            activeCard.visibility = View.VISIBLE
            start.text = "DETENER BARRIDO"
            setScanConfigurationEnabled(false)
            setTransportEnabled(true)
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
                            setKeepScreenOn(false)
                            performSuccessHaptic()
                            start.text = categoryButtonTitle(selectedCategory)
                            setScanConfigurationEnabled(true)
                            setTransportEnabled(false)
                            progress.progress = 1000
                            currentCode.text = "BARRIDO COMPLETADO"
                            carrierText.text = "Últimos candidatos disponibles"
                            "Barrido terminado. Pulsa «FUNCIONÓ» si alguno de los últimos códigos respondió."
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
                performSuccessHaptic()
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
            addView(bodyText("◎", 30f, CYBER_CYAN), LinearLayout.LayoutParams(dp(42), dp(48)))
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
            background = cyberPanelDrawable(
                Color.rgb(8, 12, 18),
                12,
                Color.argb(135, 0, 229, 255)
            )
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
            setKeepScreenOn(false)
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
                progressTintList = android.content.res.ColorStateList.valueOf(CYBER_CYAN)
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
                listOf(previous, pause, next).forEach { control ->
                    control.isEnabled = running
                    control.alpha = if (running) 1f else 0.42f
                }
                for (i in 0 until paceControl.childCount) {
                    paceControl.getChildAt(i).isEnabled = !running
                    paceControl.getChildAt(i).alpha = if (running) 0.55f else 1f
                }
            }
            setRunningUi(false)

            start.setOnClickListener {
                if (scanner.isRunning()) {
                    scanner.stop()
                    setKeepScreenOn(false)
                    performClickHaptic()
                    setRunningUi(false)
                    status.text = "Barrido de " + brand + " detenido."
                    return@setOnClickListener
                }

                val active = transmitter.active()
                if (!active.isAvailable()) {
                    status.text = "El transmisor seleccionado no está disponible. Revisa Ajustes / Info."
                    return@setOnClickListener
                }

                progress.progress = 0
                performClickHaptic()
                setKeepScreenOn(true)
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
                                setKeepScreenOn(false)
                                performSuccessHaptic()
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

                performSuccessHaptic()
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
                    setTextColor(if (letter == selectedLetter) Color.WHITE else CYBER_CYAN)
                    background = if (letter == selectedLetter)
                        cyberGradientDrawable(intArrayOf(CYBER_PURPLE, CYBER_MAGENTA), 8)
                    else roundedDrawable(Color.TRANSPARENT, 8)
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

    private fun showOnline(onBack: (() -> Unit) = { showCodes() }) {
        val screen = beginScreen()
        val body = installScreenBody("IR online", onBack)

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
            if (isScreenActive(screen)) showOnline(onBack)
        }, spacedMatch(10))

        val brand = oledInput("Marca (ej. TD Systems)")
        val model = oledInput("Modelo (opcional)")
        searchCard.addView(brand, spacedMatch(10))
        searchCard.addView(model, spacedMatch(10))

        var reloadBrands: (() -> Unit)? = null
        var brandLoadGeneration = 0L
        var onlineSearchGeneration = 0L
        val searchButton = primaryButton("⌕  BUSCAR CÓDIGOS")
        val sourceControl = segmentedControl(
            listOf("Todas", "Flipper", "Oficial", "IRDB"),
            sourceIndex
        ) { index ->
            sourceIndex = index
            onlineSearchGeneration += 1
            searchButton.isEnabled = true
            searchButton.text = "⌕  BUSCAR CÓDIGOS"
            reloadBrands?.invoke()
        }
        searchCard.addView(sourceControl, spacedMatch(8))

        val deep = CheckBox(this).apply {
            text = "Búsqueda profunda"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(CYBER_CYAN)
        }
        searchCard.addView(deep, spacedMatch(8))
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
            if (isScreenActive(screen)) showOnline(onBack)
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
                                showImportedSignals(
                                    value.signals,
                                    selectedCategory,
                                    value.name,
                                    brand = remote.brand,
                                    model = remote.model,
                                    sourceDescription = value.sourceDescription
                                )
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
                    v.setTextColor(if (active) Color.WHITE else CYBER_CYAN)
                    v.background = if (active) cyberGradientDrawable(
                        intArrayOf(CYBER_PURPLE, CYBER_MAGENTA),
                        8
                    )
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
            brandLoadGeneration += 1
            val loadGeneration = brandLoadGeneration
            brandStatus.text = "Actualizando marcas…"
            brandBrowser.removeAllViews()
            val categorySnapshot = selectedCategory
            val sourcesSnapshot = selectedSources().toList()
            worker.execute {
                val loaded = runCatching { onlineLibrary.brands(categorySnapshot, sourcesSnapshot) }
                runOnUiThread {
                    if (!isScreenActive(screen)) return@runOnUiThread
                    if (loadGeneration != brandLoadGeneration) return@runOnUiThread
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
            onlineSearchGeneration += 1
            val searchGeneration = onlineSearchGeneration
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
                    if (searchGeneration != onlineSearchGeneration) return@runOnUiThread
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
                        showImportedSignals(
                            value.signals,
                            selectedCategory,
                            value.name,
                            sourceDescription = value.sourceDescription
                        )
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

        val learnCategory = outlineButton(
            categoryGlyph(selectedLearnCategory) + "  " + selectedLearnCategory.title.uppercase() + "  ›"
        ).apply {
            contentDescription = "Tipo de mando para aprender: " + selectedLearnCategory.title
        }
        body.addView(learnCategory, spacedMatch(14))
        learnCategory.setOnClickListener {
            val categories = remoteCatalogCategories()
            AlertDialog.Builder(this)
                .setTitle("Tipo de mando")
                .setSingleChoiceItems(
                    categories.map { it.title }.toTypedArray(),
                    categories.indexOf(selectedLearnCategory)
                ) { dialog, which ->
                    val category = categories.getOrNull(which) ?: return@setSingleChoiceItems
                    selectedLearnCategory = category
                    guidedIndex = 0
                    dialog.dismiss()
                    if (isScreenActive(screen)) showLearn()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

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
            buttonTintList = android.content.res.ColorStateList.valueOf(CYBER_CYAN)
            isChecked = guidedLearning
        }
        studioTools.addView(guided)
        body.addView(studioTools, spacedMatch(14))
        importButton.setOnClickListener { openIrFilePicker() }
        remoteButton.setOnClickListener {
            showRemoteBuilderDialog(selectedLearnCategory)
        }
        guided.setOnCheckedChangeListener { _, enabled ->
            guidedLearning = enabled
            guidedIndex = 0
            if (isScreenActive(screen)) showLearn()
        }

        if (guidedLearning) {
            val buttons = guidedButtonNames(selectedLearnCategory)
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
                progressTintList = android.content.res.ColorStateList.valueOf(CYBER_CYAN)
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
            if (inputInfo.isExternal) IOS_GREEN else CYBER_DANGER))
        }
        inputCard.addView(inputHeader, spacedMatch(6))
        inputCard.addView(bodyText(inputInfo.description, 14f, Color.LTGRAY), spacedMatch(4))
        inputCard.addView(bodyText(
            if (inputInfo.isExternal) "Entrada externa detectada"
            else "Entrada interna: este dispositivo no puede aprender IR",
            12f,
            if (inputInfo.isExternal) IOS_GREEN else CYBER_DANGER,
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
                    guidedButtonNames(selectedLearnCategory)
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
                    val names = guidedButtonNames(selectedLearnCategory)
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
                        selectedLearnCategory.name + ":" + java.util.UUID.randomUUID()
                )
                val learned = store.loadLearned()
                learned += storedCode
                store.saveLearned(learned)
                learnedCandidate = storedCode
                toast("Guardado: " + name)
                advanceGuidedLearning(selectedLearnCategory)
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

    private fun showRemoteControl() {
        val screen = beginScreen()
        val body = installScreenBody("Mando universal")
        val remotes = store.loadRemotes()

        if (remotes.isEmpty()) {
            val hero = card(22).apply {
                gravity = Gravity.CENTER
                addView(bodyText("▤", 44f, CYBER_CYAN, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                }, spacedMatch(8))
                addView(bodyText("REMOTE DECK // SIN PERFIL", 13f, CYBER_MAGENTA, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    letterSpacing = 0.08f
                }, spacedMatch(8))
                addView(bodyText(
                    "Crea o descarga un mando para usar Power, volumen, canales, entradas, cruceta y el resto de botones desde una sola pantalla.",
                    14f,
                    IOS_SECONDARY
                ).apply {
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                })
            }
            body.addView(hero, spacedMatch(16))

            val addRemote = primaryButton("＋  AÑADIR MANDO")
            val learned = outlineButton("⌁  CREAR DESDE IR APRENDIDO")
            body.addView(addRemote, spacedMatch(10))
            body.addView(learned, spacedMatch(16))
            addRemote.setOnClickListener { showRemoteAddDeviceTypes() }
            learned.setOnClickListener { showLearnedRemoteCategoryChooser() }

            body.addView(emptyState(
                "Consejo",
                "Las señales compatibles de los mandos descargados online pueden guardarse juntas como un perfil. También puedes aprender o importar botones en la pestaña Aprender."
            ))
            return
        }

        val preferredId = preferences.getString(PREF_REMOTE_ID, null)
        val remote = remotes.firstOrNull { it.id == preferredId } ?: remotes.first()
        if (preferredId != remote.id) {
            preferences.edit().putString(PREF_REMOTE_ID, remote.id).apply()
        }

        val header = card(20).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = categoryGlyph(remote.category)
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(CYBER_CYAN)
                background = circleDrawable(Color.argb(34, 0, 229, 255))
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(bodyText(remote.name, 18f, Color.WHITE, Typeface.BOLD))
                val catalogMeta = listOf(remote.brand, remote.model)
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .joinToString(" · ")
                if (catalogMeta.isNotBlank()) {
                    addView(bodyText(catalogMeta, 11f, CYBER_CYAN, Typeface.BOLD))
                }
                addView(bodyText(
                    remote.category.title + " · " + remote.buttons.size + " botones",
                    12f,
                    IOS_SECONDARY
                ))
                addView(bodyText("REMOTE // ACTIVE", 10f, CYBER_GREEN, Typeface.BOLD).apply {
                    letterSpacing = 0.08f
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bodyText("⌄", 26f, CYBER_CYAN, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            isClickable = true
            isFocusable = true
            contentDescription = "Cambiar mando. Seleccionado: " + remote.name
            setOnClickListener {
                performClickHaptic()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Seleccionar mando")
                    .setSingleChoiceItems(
                        remotes.map { it.name + " · " + it.category.shortTitle }.toTypedArray(),
                        remotes.indexOfFirst { it.id == remote.id }
                    ) { dialog, which ->
                        val selected = remotes.getOrNull(which) ?: return@setSingleChoiceItems
                        preferences.edit().putString(PREF_REMOTE_ID, selected.id).apply()
                        dialog.dismiss()
                        if (isScreenActive(screen)) showRemoteControl()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        body.addView(header, spacedMatch(12))

        val quickActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val newRemote = outlineButton("＋  NUEVO")
        val onlineRemote = outlineButton("◎  CATÁLOGO")
        val shareRemote = outlineButton("COMPARTIR").apply { textSize = 12f }
        quickActions.addView(newRemote, weighted())
        quickActions.addView(space(dp(7)))
        quickActions.addView(onlineRemote, weighted())
        quickActions.addView(space(dp(7)))
        quickActions.addView(shareRemote, weighted())
        body.addView(quickActions, spacedMatch(14))
        newRemote.setOnClickListener {
            showRemoteAddDeviceTypes()
        }
        onlineRemote.setOnClickListener { showRemoteAddBrands(remote.category) }
        shareRemote.setOnClickListener { shareCustomRemote(remote) }

        val status = infoText("Listo · " + transmitter.active().name).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cyberPanelDrawable(
                fill = Color.argb(150, 9, 13, 20),
                radiusDp = 12,
                stroke = Color.argb(85, 0, 229, 255)
            )
        }
        body.addView(status, spacedMatch(14))

        val consumed = mutableSetOf<String>()

        fun normalize(value: String): String = value
            .trim()
            .lowercase()
            .replace("á", "a")
            .replace("é", "e")
            .replace("í", "i")
            .replace("ó", "o")
            .replace("ú", "u")
            .replace("ü", "u")
            .replace("ñ", "n")
            .replace("+", " plus ")
            .replace("-", " minus ")
            .replace("_", " ")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun pick(vararg aliases: String): CustomRemoteButton? {
            val wanted = aliases.map(::normalize).toSet()
            val found = remote.buttons.firstOrNull { button ->
                button.id !in consumed && normalize(button.name) in wanted
            }
            if (found != null) consumed += found.id
            return found
        }

        fun pickPrefix(vararg aliases: String): CustomRemoteButton? {
            val wanted = aliases.map(::normalize)
            val found = remote.buttons.firstOrNull { button ->
                if (button.id in consumed) return@firstOrNull false
                val name = normalize(button.name)
                wanted.any { alias -> name == alias || name.startsWith("$alias ") }
            }
            if (found != null) consumed += found.id
            return found
        }

        fun key(label: String, button: CustomRemoteButton?, danger: Boolean = false): Button {
            val view = if (danger) tintedButton(label, CYBER_DANGER) else outlineButton(label)
            view.contentDescription = if (button == null) "$label, sin código asignado" else "$label, ${button.name}"
            view.isEnabled = button != null
            view.alpha = if (button == null) 0.34f else 1f
            view.setOnClickListener {
                val value = button ?: return@setOnClickListener
                sendAsync(value.code, status, screen)
            }
            return view
        }

        fun addKeyRow(vararg values: Pair<String, CustomRemoteButton?>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            values.forEachIndexed { index, value ->
                row.addView(key(value.first, value.second), weighted())
                if (index < values.lastIndex) row.addView(space(dp(8)))
            }
            body.addView(row, spacedMatch(9))
        }

        val power = pickPrefix(
            "Power", "Pwr", "Power toggle", "Toggle power", "Power on off", "Power off", "Power on",
            "Poweroff", "Poweron", "Standby", "Turn off", "Turn on", "Turnoff", "Turnon",
            "On off", "Encendido", "Apagar"
        )
        val powerCard = card(22).apply {
            gravity = Gravity.CENTER
            addView(bodyText("POWER", 11f, IOS_SECONDARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.14f
            }, spacedMatch(8))
            val powerButton = key("PWR", power, danger = true).apply {
                textSize = 18f
                minWidth = dp(76)
                minHeight = dp(76)
                background = if (power != null) {
                    circleDrawable(CYBER_DANGER)
                } else {
                    circleDrawable(Color.argb(60, 255, 72, 96))
                }
            }
            addView(powerButton, LinearLayout.LayoutParams(dp(76), dp(76)))
        }
        body.addView(powerCard, spacedMatch(12))

        when (remote.category) {
            DeviceCategory.TELEVISION -> {
                val input = pick("Input", "Input next", "Source", "Source next", "AV", "Entrada")
                val home = pick("Home", "Inicio", "Smart", "Smart hub")
                val menu = pick("Menu", "Settings", "Ajustes")
                addKeyRow("INPUT" to input, "HOME" to home, "MENU" to menu)

                val up = pick("Up", "Arrow up", "Dpad up", "Cursor up", "Arriba")
                val down = pick("Down", "Arrow down", "Dpad down", "Cursor down", "Abajo")
                val left = pick("Left", "Arrow left", "Dpad left", "Cursor left", "Izquierda")
                val right = pick("Right", "Arrow right", "Dpad right", "Cursor right", "Derecha")
                val ok = pick("OK", "Enter", "Select", "Center", "Centro")
                val nav = card(22)
                nav.addView(bodyText("NAVIGATION", 11f, CYBER_CYAN, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                }, spacedMatch(8))
                nav.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(space(dp(56)), weighted())
                    addView(key("▲", up), weighted())
                    addView(space(dp(56)), weighted())
                }, spacedMatch(8))
                nav.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(key("◀", left), weighted())
                    addView(space(dp(8)))
                    addView(key("OK", ok), weighted())
                    addView(space(dp(8)))
                    addView(key("▶", right), weighted())
                }, spacedMatch(8))
                nav.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(space(dp(56)), weighted())
                    addView(key("▼", down), weighted())
                    addView(space(dp(56)), weighted())
                })
                body.addView(nav, spacedMatch(12))

                val volUp = pick("Vol +", "Vol plus", "Vol up", "Volume plus", "Volume up", "Volumen plus", "Subir volumen")
                val volDown = pick("Vol -", "Vol minus", "Vol down", "Vol dn", "Volume minus", "Volume down", "Volumen minus", "Bajar volumen")
                val chUp = pick("Channel +", "Channel plus", "Channel up", "Channel next", "Ch plus", "Ch up", "Ch next", "Prog plus", "Program plus")
                val chDown = pick("Channel -", "Channel minus", "Channel down", "Channel prev", "Channel previous", "Ch minus", "Ch down", "Ch prev", "Prog minus", "Program minus")
                val mute = pick("Mute", "Silence", "Silencio")
                val rockers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun rocker(title: String, plus: CustomRemoteButton?, minus: CustomRemoteButton?): View =
                    card(18).apply {
                        gravity = Gravity.CENTER
                        addView(bodyText(title, 11f, CYBER_CYAN, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                        }, spacedMatch(7))
                        addView(key("＋", plus), matchWrap())
                        addView(bodyText(title, 10f, IOS_SECONDARY, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, dp(6))
                        })
                        addView(key("−", minus), matchWrap())
                    }
                rockers.addView(rocker("VOL", volUp, volDown), weighted())
                rockers.addView(space(dp(10)))
                rockers.addView(card(18).apply {
                    gravity = Gravity.CENTER
                    addView(bodyText("AUDIO", 11f, CYBER_MAGENTA, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                    }, spacedMatch(10))
                    addView(key("MUTE", mute), matchWrap())
                }, weighted())
                rockers.addView(space(dp(10)))
                rockers.addView(rocker("CH", chUp, chDown), weighted())
                body.addView(rockers, spacedMatch(12))

                val back = pick("Back", "Return", "Exit", "Atras", "Volver")
                val guide = pick("Guide", "EPG", "Guia")
                val info = pick("Info", "Display")
                addKeyRow("BACK" to back, "GUIDE" to guide, "INFO" to info)

                val play = pick("Play", "Play pause", "Playpause")
                val pause = pick("Pause")
                val stop = pick("Stop")
                val previous = pick("Previous", "Prev", "Skip previous", "Back skip")
                val next = pick("Next", "Skip next", "Skip")
                val rewind = pick("Rewind", "Rew", "Reverse")
                val fastForward = pick("Fast forward", "Fastforward", "FF", "Forward")
                if (listOf(play, pause, stop, previous, next, rewind, fastForward).any { it != null }) {
                    body.addView(sectionHeader("Media"))
                    addKeyRow("PREV" to previous, "PLAY" to play, "NEXT" to next)
                    addKeyRow("REW" to rewind, "PAUSE" to pause, "STOP" to stop, "FF" to fastForward)
                }

                val numbers = (0..9).associateWith { value ->
                    pick(value.toString(), "Num $value", "Number $value", "Digit $value", "Key $value")
                }
                if (numbers.values.any { it != null }) {
                    body.addView(sectionHeader("Teclado numérico"))
                    listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { rowValues ->
                        addKeyRow(*rowValues.map { it.toString() to numbers[it] }.toTypedArray())
                    }
                    val zeroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    zeroRow.addView(Space(this@MainActivity), weighted())
                    zeroRow.addView(space(dp(8)))
                    zeroRow.addView(key("0", numbers[0]), weighted())
                    zeroRow.addView(space(dp(8)))
                    zeroRow.addView(Space(this), weighted())
                    body.addView(zeroRow, spacedMatch(10))
                }
            }

            DeviceCategory.SET_TOP_BOX,
            DeviceCategory.MEDIA_BOX -> {
                val source = pick("Input", "Source", "AV", "Entrada")
                val home = pick("Home", "Inicio", "Smart", "Portal")
                val menu = pick("Menu", "Settings", "Ajustes")
                addKeyRow("SOURCE" to source, "HOME" to home, "MENU" to menu)

                val up = pick("Up", "Arrow up", "Dpad up", "Cursor up", "Arriba")
                val down = pick("Down", "Arrow down", "Dpad down", "Cursor down", "Abajo")
                val left = pick("Left", "Arrow left", "Dpad left", "Cursor left", "Izquierda")
                val right = pick("Right", "Arrow right", "Dpad right", "Cursor right", "Derecha")
                val ok = pick("OK", "Enter", "Select", "Center", "Centro")
                addKeyRow("▲" to up)
                addKeyRow("◀" to left, "OK" to ok, "▶" to right)
                addKeyRow("▼" to down)

                val back = pick("Back", "Return", "Exit", "Atras", "Volver")
                val guide = pick("Guide", "EPG", "Guia")
                val info = pick("Info", "Display")
                addKeyRow("BACK" to back, "GUIDE" to guide, "INFO" to info)

                val chUp = pick("Channel +", "Channel plus", "Channel up", "Ch plus", "Ch up", "Ch next")
                val chDown = pick("Channel -", "Channel minus", "Channel down", "Ch minus", "Ch down", "Ch prev")
                val volUp = pick("Vol +", "Vol plus", "Vol up", "Volume plus", "Volume up")
                val volDown = pick("Vol -", "Vol minus", "Vol down", "Volume minus", "Volume down")
                val mute = pick("Mute", "Silence", "Silencio")
                addKeyRow("CH +" to chUp, "CH −" to chDown)
                addKeyRow("VOL +" to volUp, "MUTE" to mute, "VOL −" to volDown)

                val play = pick("Play", "Play pause", "Playpause")
                val pause = pick("Pause")
                val stop = pick("Stop")
                val previous = pick("Previous", "Prev", "Skip previous")
                val next = pick("Next", "Skip next")
                val rewind = pick("Rewind", "Rew", "Reverse")
                val fastForward = pick("Fast forward", "Fastforward", "FF", "Forward")
                if (listOf(play, pause, stop, previous, next, rewind, fastForward).any { it != null }) {
                    body.addView(sectionHeader("Media"))
                    addKeyRow("PREV" to previous, "PLAY" to play, "NEXT" to next)
                    addKeyRow("REW" to rewind, "PAUSE" to pause, "STOP" to stop, "FF" to fastForward)
                }

                val numbers = (0..9).associateWith { value ->
                    pick(value.toString(), "Num $value", "Number $value", "Digit $value", "Key $value")
                }
                if (numbers.values.any { it != null }) {
                    body.addView(sectionHeader("Teclado numérico"))
                    listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { rowValues ->
                        addKeyRow(*rowValues.map { it.toString() to numbers[it] }.toTypedArray())
                    }
                    addKeyRow("0" to numbers[0])
                }
            }

            DeviceCategory.DVD_PLAYER,
            DeviceCategory.BLU_RAY -> {
                val eject = pick("Eject", "Open close", "Open", "Close")
                val menu = pick("Menu", "Disc menu", "Top menu")
                val back = pick("Back", "Return", "Exit")
                addKeyRow("EJECT" to eject, "MENU" to menu, "BACK" to back)

                val up = pick("Up", "Arrow up", "Arriba")
                val down = pick("Down", "Arrow down", "Abajo")
                val left = pick("Left", "Arrow left", "Izquierda")
                val right = pick("Right", "Arrow right", "Derecha")
                val ok = pick("OK", "Enter", "Select")
                addKeyRow("▲" to up)
                addKeyRow("◀" to left, "OK" to ok, "▶" to right)
                addKeyRow("▼" to down)

                val play = pick("Play", "Play pause", "Playpause")
                val pause = pick("Pause")
                val stop = pick("Stop")
                val previous = pick("Previous", "Prev", "Skip previous")
                val next = pick("Next", "Skip next")
                val rewind = pick("Rewind", "Rew", "Reverse")
                val fastForward = pick("Fast forward", "Fastforward", "FF", "Forward")
                body.addView(sectionHeader("Reproducción"))
                addKeyRow("PREV" to previous, "PLAY" to play, "NEXT" to next)
                addKeyRow("REW" to rewind, "PAUSE" to pause, "STOP" to stop, "FF" to fastForward)
            }

            DeviceCategory.AV_RECEIVER,
            DeviceCategory.SOUND_BAR -> {
                val input = pick("Input", "Source", "AV", "Entrada")
                val mode = pick("Mode", "Sound mode", "Surround", "DSP")
                val mute = pick("Mute", "Silence", "Silencio")
                addKeyRow("INPUT" to input, "MODE" to mode, "MUTE" to mute)
                val volUp = pick("Vol +", "Vol plus", "Vol up", "Volume plus", "Volume up")
                val volDown = pick("Vol -", "Vol minus", "Vol down", "Volume minus", "Volume down")
                addKeyRow("VOL +" to volUp, "VOL −" to volDown)
                val up = pick("Up", "Arrow up")
                val down = pick("Down", "Arrow down")
                val left = pick("Left", "Arrow left")
                val right = pick("Right", "Arrow right")
                val ok = pick("OK", "Enter", "Select")
                val menu = pick("Menu", "Settings", "Setup")
                addKeyRow("MENU" to menu, "▲" to up)
                addKeyRow("◀" to left, "OK" to ok, "▶" to right)
                addKeyRow("▼" to down)
            }

            DeviceCategory.FAN -> {
                val speedUp = pick("Speed +", "Speed up", "Fan +", "Fan up", "Fan speed up", "Faster")
                val speedDown = pick("Speed -", "Speed down", "Fan -", "Fan down", "Fan speed down", "Slower")
                val speed = pick("Speed", "Fan speed", "Fan")
                val mode = pick("Mode", "Modo")
                val swing = pick("Swing", "Swing up", "Swing down", "Oscillation", "Oscillate", "Oscilacion")
                val timer = pick("Timer", "Sleep")
                val light = pick("Light", "LED", "Display")
                addKeyRow("SPEED +" to speedUp, "SPEED" to speed, "SPEED −" to speedDown)
                addKeyRow("MODE" to mode, "SWING" to swing)
                addKeyRow("TIMER" to timer, "LIGHT" to light)
            }

            DeviceCategory.CAMERA -> {
                val shutter = pick("Shutter", "Shoot", "Capture", "Photo")
                val zoomIn = pick("Zoom +", "Zoom in", "Tele")
                val zoomOut = pick("Zoom -", "Zoom out", "Wide")
                val menu = pick("Menu")
                val playback = pick("Playback", "Play", "Review")
                addKeyRow("SHUTTER" to shutter)
                addKeyRow("ZOOM +" to zoomIn, "ZOOM −" to zoomOut)
                addKeyRow("MENU" to menu, "PLAYBACK" to playback)
                val up = pick("Up", "Arrow up")
                val down = pick("Down", "Arrow down")
                val left = pick("Left", "Arrow left")
                val right = pick("Right", "Arrow right")
                val ok = pick("OK", "Enter", "Select", "Set")
                addKeyRow("▲" to up)
                addKeyRow("◀" to left, "OK" to ok, "▶" to right)
                addKeyRow("▼" to down)
            }

            DeviceCategory.AIR_CONDITIONER -> {
                val tempUp = pick("Temp +", "Temp plus", "Temperature plus", "Temp up", "Temperature up")
                val tempDown = pick("Temp -", "Temp minus", "Temperature minus", "Temp down", "Temperature down")
                val mode = pick("Mode", "Modo")
                val fan = pick("Fan", "Fan up", "Fan down", "Fan speed", "Ventilador")
                val swing = pick("Swing", "Swing up", "Swing down", "Oscillation", "Oscilacion")
                addKeyRow("TEMP ＋" to tempUp, "TEMP −" to tempDown)
                addKeyRow("MODE" to mode, "FAN" to fan, "SWING" to swing)
                addKeyRow(
                    "COOL" to pick("Cool", "Cool hi", "Cool lo", "Frio"),
                    "HEAT" to pick("Heat", "Heat hi", "Heat lo", "Calor"),
                    "AUTO" to pick("Auto")
                )
            }

            DeviceCategory.PROJECTOR -> {
                val source = pick("Source", "Source next", "Input", "Input next", "Entrada")
                val menu = pick("Menu")
                val back = pick("Back", "Return", "Exit", "Atras")
                addKeyRow("SOURCE" to source, "MENU" to menu, "BACK" to back)
                val up = pick("Up", "Arrow up", "Arriba")
                val down = pick("Down", "Arrow down", "Abajo")
                val left = pick("Left", "Arrow left", "Izquierda")
                val right = pick("Right", "Arrow right", "Derecha")
                val ok = pick("OK", "Enter", "Select")
                addKeyRow("▲" to up)
                addKeyRow("◀" to left, "OK" to ok, "▶" to right)
                addKeyRow("▼" to down)
                addKeyRow(
                    "MUTE" to pick("Mute", "Silencio"),
                    "FREEZE" to pick("Freeze", "Pausa imagen")
                )
            }
        }

        val extras = remote.buttons.filterNot { it.id in consumed }
        if (extras.isNotEmpty()) {
            body.addView(sectionHeader("Más controles     " + extras.size))
            extras.chunked(3).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEachIndexed { index, button ->
                    row.addView(key(button.name.take(18), button), weighted())
                    if (index < chunk.lastIndex) row.addView(space(dp(8)))
                }
                repeat(3 - chunk.size) {
                    row.addView(space(dp(8)))
                    row.addView(Space(this), weighted())
                }
                body.addView(row, spacedMatch(9))
            }
        }

        body.addView(infoText(
            "Si un botón aparece atenuado, este perfil no contiene esa señal. Puedes añadir otro mando desde botones aprendidos o descargar un perfil más completo desde Online."
        ), spacedMatch(18))
    }

    private fun remoteCatalogCategories(): List<DeviceCategory> = listOf(
        DeviceCategory.TELEVISION,
        DeviceCategory.SET_TOP_BOX,
        DeviceCategory.AIR_CONDITIONER,
        DeviceCategory.FAN,
        DeviceCategory.MEDIA_BOX,
        DeviceCategory.DVD_PLAYER,
        DeviceCategory.BLU_RAY,
        DeviceCategory.AV_RECEIVER,
        DeviceCategory.SOUND_BAR,
        DeviceCategory.PROJECTOR,
        DeviceCategory.CAMERA
    )

    private fun showRemoteAddDeviceTypes() {
        beginScreen()
        val body = installScreenBody("Añadir mando", onBack = { showRemoteControl() })
        body.addView(card(18).apply {
            addView(bodyText("1  ELIGE EL DISPOSITIVO", 12f, CYBER_MAGENTA, Typeface.BOLD).apply {
                letterSpacing = 0.08f
            }, spacedMatch(6))
            addView(bodyText(
                "Selecciona qué quieres controlar. Después podrás elegir marca y modelo/perfil, probarlo y guardarlo como en un mando universal dedicado.",
                14f,
                IOS_SECONDARY
            ))
        }, spacedMatch(14))

        val categories = remoteCatalogCategories()
        categories.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, category ->
                val tile = card(17).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Añadir " + category.title
                    addView(bodyText(categoryGlyph(category), 30f, CYBER_CYAN, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                    }, spacedMatch(7))
                    addView(bodyText(category.title, 14f, Color.WHITE, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                    }, spacedMatch(4))
                    addView(bodyText(remoteCategorySubtitle(category), 10f, IOS_SECONDARY).apply {
                        gravity = Gravity.CENTER
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        maxLines = 2
                    })
                    setOnClickListener {
                        performClickHaptic()
                        showRemoteAddBrands(category)
                    }
                }
                row.addView(tile, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (index == 0 && pair.size > 1) marginEnd = dp(6)
                    if (index > 0) marginStart = dp(6)
                })
            }
            if (pair.size == 1) {
                row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
            }
            body.addView(row, spacedMatch(10))
        }

        val learned = outlineButton("⌁  CREAR DESDE BOTONES APRENDIDOS")
        body.addView(learned, spacedMatch(18))
        learned.setOnClickListener { showLearnedRemoteCategoryChooser() }
    }

    private fun showLearnedRemoteCategoryChooser() {
        val categories = remoteCatalogCategories()
        AlertDialog.Builder(this)
            .setTitle("Tipo de mando")
            .setItems(categories.map { it.title }.toTypedArray()) { _, which ->
                val category = categories.getOrNull(which) ?: return@setItems
                showRemoteBuilderDialog(category) { created ->
                    preferences.edit().putString(PREF_REMOTE_ID, created.id).apply()
                    showRemoteControl()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showRemoteAddBrands(category: DeviceCategory) {
        val screen = beginScreen()
        val body = installScreenBody(category.title, onBack = { showRemoteAddDeviceTypes() })
        body.addView(card(18).apply {
            addView(bodyText("2  ELIGE LA MARCA", 12f, CYBER_MAGENTA, Typeface.BOLD).apply {
                letterSpacing = 0.08f
            }, spacedMatch(6))
            addView(bodyText(
                "Las marcas se cargan desde las bibliotecas IR públicas compatibles con " + category.shortTitle + ".",
                13f,
                IOS_SECONDARY
            ))
        }, spacedMatch(12))

        val query = oledInput("Buscar marca")
        body.addView(query, spacedMatch(10))
        val status = infoText("Cargando marcas…")
        body.addView(status, spacedMatch(6))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(host, spacedMatch(18))

        var brands: List<String> = popularRemoteBrands(category)
        fun render() {
            host.removeAllViews()
            val needle = query.text.toString().trim().lowercase()
            val filtered = brands.filter { needle.isBlank() || it.lowercase().contains(needle) }
            status.text = when {
                brands.isEmpty() -> "Cargando marcas…"
                filtered.isEmpty() -> "No hay marcas que coincidan."
                else -> filtered.size.toString() + " marcas"
            }
            filtered.take(220).forEach { brand ->
                host.addView(browserRow(brand, "›") { showRemoteAddProfiles(category, brand) })
            }
            if (filtered.size > 220) {
                host.addView(infoText("Mostrando las primeras 220. Escribe parte del nombre para filtrar."))
            }
        }
        query.addTextChangedListener(simpleTextWatcher(::render))
        render()
        status.text = if (brands.isEmpty()) "Cargando marcas…" else "Marcas populares · cargando catálogo completo…"

        worker.execute {
            val primary = runCatching {
                onlineLibrary.brands(category, listOf(OnlineIrSource.FLIPPER_COMMUNITY))
            }
            runOnUiThread {
                if (!isScreenActive(screen)) return@runOnUiThread
                primary.onSuccess {
                    brands = (brands + it).distinctBy { value -> value.lowercase() }
                    render()
                }.onFailure {
                    status.text = "Sin conexión con la fuente principal · intentando otras fuentes…"
                }
            }

            val extended = runCatching { onlineLibrary.brands(category) }
            runOnUiThread {
                if (!isScreenActive(screen)) return@runOnUiThread
                extended.onSuccess {
                    brands = (brands + it).distinctBy { value -> value.lowercase() }
                        .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    render()
                }.onFailure {
                    if (brands.isEmpty()) {
                        status.text = "No se pudieron cargar las marcas: " + (it.message ?: "error de red")
                        host.removeAllViews()
                        host.addView(outlineButton("REINTENTAR").apply {
                            setOnClickListener { showRemoteAddBrands(category) }
                        })
                    } else {
                        status.text = brands.size.toString() + " marcas · catálogo online no disponible"
                    }
                }
            }
        }
    }

    private fun showRemoteAddProfiles(category: DeviceCategory, brand: String) {
        val screen = beginScreen()
        val body = installScreenBody(brand, onBack = { showRemoteAddBrands(category) })
        body.addView(card(18).apply {
            addView(bodyText("3  ELIGE MODELO / PERFIL", 12f, CYBER_MAGENTA, Typeface.BOLD).apply {
                letterSpacing = 0.08f
            }, spacedMatch(6))
            addView(bodyText(category.title + " · " + brand, 17f, Color.WHITE, Typeface.BOLD))
        }, spacedMatch(12))

        val query = oledInput("Buscar modelo")
        body.addView(query, spacedMatch(10))
        val status = infoText("Buscando perfiles de $brand…")
        body.addView(status, spacedMatch(6))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(host, spacedMatch(18))

        var profiles: List<OnlineIrRemote> = emptyList()
        fun render() {
            host.removeAllViews()
            val needle = query.text.toString().trim().lowercase()
            val filtered = profiles.filter { remote ->
                needle.isBlank() || remote.model.lowercase().contains(needle) ||
                    remote.displayName.lowercase().contains(needle)
            }
            status.text = when {
                profiles.isEmpty() -> "Buscando perfiles de $brand…"
                filtered.isEmpty() -> "No hay modelos que coincidan."
                else -> filtered.size.toString() + " perfiles encontrados"
            }
            filtered.forEach { remote ->
                host.addView(browserRow(
                    remote.model.ifBlank { remote.displayName },
                    remote.source.title + "  ›"
                ) {
                    status.text = "Descargando " + remote.displayName + "…"
                    worker.execute {
                        val loaded = runCatching { onlineLibrary.download(remote) }
                        runOnUiThread {
                            if (!isScreenActive(screen)) return@runOnUiThread
                            loaded.onSuccess { value ->
                                showRemoteProfilePreview(category, brand, remote, value)
                            }.onFailure {
                                status.text = "No se pudo abrir el perfil: " + (it.message ?: "desconocido")
                            }
                        }
                    }
                })
            }
        }
        query.addTextChangedListener(simpleTextWatcher(::render))

        worker.execute {
            val primary = runCatching {
                onlineLibrary.search(
                    brand,
                    "",
                    category,
                    sources = listOf(OnlineIrSource.FLIPPER_COMMUNITY),
                    deep = true
                )
            }
            runOnUiThread {
                if (!isScreenActive(screen)) return@runOnUiThread
                primary.onSuccess {
                    profiles = it
                    render()
                }.onFailure {
                    status.text = "Fuente principal no disponible · buscando alternativas…"
                }
            }

            val extended = runCatching { onlineLibrary.search(brand, "", category, deep = true) }
            runOnUiThread {
                if (!isScreenActive(screen)) return@runOnUiThread
                extended.onSuccess {
                    profiles = (profiles + it).distinctBy { remote -> remote.id }
                    render()
                    if (profiles.isEmpty()) {
                        status.text = "No hay perfiles compatibles para esta marca."
                        host.addView(outlineButton("VOLVER A MARCAS").apply {
                            setOnClickListener { showRemoteAddBrands(category) }
                        })
                    }
                }.onFailure {
                    if (profiles.isEmpty()) {
                        status.text = "Error de catálogo: " + (it.message ?: "desconocido")
                    } else {
                        status.text = profiles.size.toString() + " perfiles · otras fuentes no disponibles"
                    }
                }
            }
        }
    }

    private fun showRemoteProfilePreview(
        category: DeviceCategory,
        brand: String,
        remote: OnlineIrRemote,
        loaded: OnlineLoadedRemote
    ) {
        val screen = beginScreen()
        val body = installScreenBody("Probar mando", onBack = { showRemoteAddProfiles(category, brand) })
        val defaultName = if (remote.model.isBlank() || remote.model.equals(brand, true)) {
            brand
        } else {
            brand + " " + remote.model
        }
        val name = oledInput("Nombre del mando").apply {
            setText(remote.displayName)
            setSelection(text.length)
        }
        val buttons = loaded.signals.map { signal ->
            CustomRemoteButton(name = signal.name, code = signal.code)
        }
        val power = buttons.firstOrNull { button -> isPowerButtonName(button.name) }

        body.addView(card(20).apply {
            gravity = Gravity.CENTER
            addView(bodyText(categoryGlyph(category), 40f, CYBER_CYAN, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            }, spacedMatch(8))
            addView(bodyText(remote.displayName, 19f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, spacedMatch(4))
            addView(bodyText(
                buttons.size.toString() + " señales compatibles · " + remote.source.title,
                12f,
                IOS_SECONDARY
            ).apply { gravity = Gravity.CENTER })
        }, spacedMatch(12))
        body.addView(name, spacedMatch(12))
        val status = infoText(
            if (power != null) "Apunta al dispositivo y prueba el botón de encendido."
            else "Este perfil no contiene un Power reconocible; puedes guardarlo y probar sus botones."
        )
        body.addView(status, spacedMatch(8))
        val test = tintedButton("PWR  PROBAR ENCENDIDO", CYBER_DANGER).apply {
            isEnabled = power != null
            alpha = if (power != null) 1f else 0.4f
        }
        val save = primaryButton("✓  GUARDAR MANDO")
        val other = outlineButton("NO RESPONDE · PROBAR OTRO")
        body.addView(test, spacedMatch(9))
        body.addView(save, spacedMatch(9))
        body.addView(other, spacedMatch(18))
        test.setOnClickListener {
            power?.let { sendAsync(it.code, status, screen) }
        }
        save.setOnClickListener {
            val custom = CustomRemote(
                name = name.text.toString().trim().ifBlank { defaultName },
                category = category,
                buttons = buttons,
                brand = brand,
                model = remote.model,
                sourceDescription = loaded.sourceDescription
            )
            store.addRemote(custom)
            preferences.edit().putString(PREF_REMOTE_ID, custom.id).apply()
            performSuccessHaptic()
            toast("Mando guardado: " + custom.name)
            showRemoteControl()
        }
        other.setOnClickListener { showRemoteAddProfiles(category, brand) }
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
                            preferences.edit().putString(PREF_REMOTE_ID, remote.id).apply()
                            selectTab(5)
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
        val body = installScreenBody("Ajustes / Info")
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
        route.addView(bodyText("◖◗  Recomendado: Audio mono desactivado", 13f, Color.LTGRAY), spacedMatch(6))
        route.addView(bodyText("≡  Recomendado: balance centrado", 13f, Color.LTGRAY), spacedMatch(6))
        route.addView(bodyText("🔊  Recomendado: volumen multimedia al máximo", 13f, Color.LTGRAY), spacedMatch(8))
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
            thumbTintList = android.content.res.ColorStateList.valueOf(CYBER_CYAN)
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
        oled.addView(bodyText("◉  BLACKOUT MODE · negro real · neón cian/magenta", 11f, CYBER_CYAN))
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
        val releaseNotes = bodyText("", 12f, IOS_SECONDARY).apply {
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(6))
        }
        val updateStatus = infoText(
            "Comprueba GitHub Releases para saber si hay una APK más reciente."
        ).apply { setPadding(0, 0, 0, dp(8)) }
        val checkUpdate = outlineButton("↻  BUSCAR ACTUALIZACIÓN")
        val downloadUpdate = primaryButton("↓  DESCARGAR ACTUALIZACIÓN").apply {
            visibility = View.GONE
        }
        updates.addView(availableVersion, spacedMatch(6))
        updates.addView(releaseNotes, spacedMatch(4))
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
                        val notes = result.release.notes
                            .lines()
                            .map { it.trim().removePrefix("-").trim() }
                            .filter { it.isNotBlank() }
                            .take(6)
                            .joinToString("\n• ", prefix = if (result.release.notes.isBlank()) "" else "• ")
                        releaseNotes.text = notes
                        releaseNotes.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
                        downloadUpdate.visibility = View.VISIBLE
                    } else {
                        availableVersion.visibility = View.GONE
                        releaseNotes.visibility = View.GONE
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

    private fun showRemoteBuilderDialog(
        category: DeviceCategory,
        onCreated: ((CustomRemote) -> Unit)? = null
    ) {
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
                    onCreated?.invoke(remote)
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
            .setNeutralButton("Compartir .ir") { _, _ -> shareCustomRemote(remote) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun shareCustomRemote(remote: CustomRemote) {
        val text = FlipperIrCodec.exportRawRecords(
            remote.buttons.map { it.name to it.code }
        )
        val safeBase = remote.name
            .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim()
            .ifBlank { "TVBGoneAndroid-remote" }
            .take(64)
        val shareDir = File(cacheDir, "shared-ir").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val file = File(shareDir, "$safeBase.ir").apply { writeText(text, Charsets.UTF_8) }
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$packageName.irfiles")
            .appendPath(file.name)
            .build()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, remote.name + ".ir")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Compartir mando"))
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
        performClickHaptic()
        status.text = "Enviando " + code.displayName + " mediante " + active.name + "…"
        worker.execute {
            val result = runCatching { active.send(code) }
            runOnUiThread {
                if (!isScreenActive(screen)) return@runOnUiThread
                result.onSuccess {
                    performSuccessHaptic()
                    status.text = "Enviado: " + code.displayName + " · " + code.effectiveCarrierHz + " Hz"
                    if (status.parent == null) {
                        toast("Enviado: " + code.displayName)
                    }
                }.onFailure {
                    performErrorHaptic()
                    status.text = "Error: " + (it.message ?: "desconocido")
                    if (status.parent == null) {
                        toast("Error: " + (it.message ?: "desconocido"))
                    }
                }
            }
        }
    }

    private fun showImportedSignals(
        signals: List<ImportedIrSignal>,
        category: DeviceCategory,
        remoteName: String = "Mando importado",
        brand: String = "",
        model: String = "",
        sourceDescription: String = ""
    ) {
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
            .setNeutralButton("Guardar como mando") { _, _ ->
                val remote = CustomRemote(
                    name = remoteName.trim().ifBlank { "Mando importado" },
                    category = category,
                    buttons = signals.map { signal ->
                        CustomRemoteButton(name = signal.name, code = signal.code)
                    },
                    brand = brand,
                    model = model,
                    sourceDescription = sourceDescription
                )
                store.addRemote(remote)
                preferences.edit().putString(PREF_REMOTE_ID, remote.id).apply()
                performSuccessHaptic()
                toast("Mando guardado: " + remote.name)
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
                                selectedLearnCategory.name + ":" + java.util.UUID.randomUUID()
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
        if (requestCode == REQUEST_MIC) {
            when (currentTab) {
                3 -> showLearn()
                4 -> showDiagnostics()
            }
        }
    }

    private fun installScreenBody(title: String, onBack: (() -> Unit)? = null): LinearLayout {
        screenBackAction = onBack
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground())
        }

        val navigation = FrameLayout(this).apply {
            background = cyberPanelDrawable(
                fill = Color.rgb(7, 10, 16),
                radiusDp = 0,
                stroke = Color.argb(80, 0, 229, 255)
            )
            addView(View(this@MainActivity).apply {
                background = cyberGradientDrawable(
                    intArrayOf(CYBER_MAGENTA, CYBER_PURPLE, CYBER_CYAN),
                    0
                )
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2),
                Gravity.TOP
            ))
        }
        val titleView = TextView(this).apply {
            text = title.uppercase()
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(Color.WHITE)
            letterSpacing = 0.08f
            setPadding(if (onBack != null) dp(56) else dp(16), 0, dp(116), 0)
        }
        navigation.addView(titleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54),
            Gravity.CENTER
        ))
        val active = transmitter.active()
        val ready = active.isAvailable()
        val chip = TextView(this).apply {
            text = if (ready) "IR // READY" else "IR // CHECK"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(if (ready) CYBER_GREEN else CYBER_WARNING)
            background = cyberPanelDrawable(
                fill = Color.argb(24, 0, 229, 255),
                radiusDp = 10,
                stroke = if (ready) Color.argb(150, 57, 255, 136)
                    else Color.argb(160, 255, 176, 32)
            )
        }
        navigation.addView(chip, FrameLayout.LayoutParams(
            dp(96),
            dp(26),
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = dp(12) })
        if (onBack != null) {
            val back = TextView(this).apply {
                text = "‹"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(CYBER_CYAN)
                setPadding(dp(6), 0, dp(8), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Volver"
                setOnClickListener {
                    performClickHaptic()
                    onBack()
                }
            }
            navigation.addView(back, FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.START or Gravity.CENTER_VERTICAL
            ))
        }
        page.addView(navigation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(30))
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
        setKeepScreenOn(false)
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
        16f,
        Color.WHITE,
        Typeface.BOLD
    ).apply {
        letterSpacing = 0.05f
        setPadding(dp(3), dp(8), dp(2), dp(10))
        compoundDrawablePadding = dp(7)
    }

    private fun card(radius: Int = 18) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = cyberPanelDrawable(
            fill = if (oledMode()) CYBER_SURFACE else CYBER_SURFACE_ALT,
            radiusDp = radius,
            stroke = if (oledMode()) IOS_BORDER else CYBER_BORDER
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

    private fun cyberPanelDrawable(fill: Int, radiusDp: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1).coerceAtLeast(1), stroke)
        }

    private fun cyberGradientDrawable(colors: IntArray, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun primaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.rgb(3, 16, 20))
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        background = cyberGradientDrawable(
            intArrayOf(CYBER_CYAN, Color.rgb(96, 239, 255)),
            12
        )
        minHeight = dp(50)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        stateListAnimator = null
        setOnLongClickListener {
            performClickHaptic()
            false
        }
    }

    private fun outlineButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(CYBER_CYAN)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
        background = cyberPanelDrawable(
            Color.argb(210, 11, 16, 24),
            12,
            Color.argb(160, 0, 229, 255)
        )
        minHeight = dp(46)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        stateListAnimator = null
    }

    private fun tintedButton(value: String, tint: Int) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.rgb(3, 16, 20))
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
        background = cyberPanelDrawable(
            if (oledMode()) Color.rgb(8, 12, 18) else CYBER_SURFACE_ALT,
            12,
            Color.argb(135, 0, 229, 255)
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
            background = cyberPanelDrawable(
                if (oledMode()) Color.rgb(8, 12, 18) else CYBER_SURFACE_ALT,
                10,
                Color.argb(110, 0, 229, 255)
            )
            tag = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        }
        labels.forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                textSize = if (labels.size >= 4) 11f else 12f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                minHeight = dp(48)
                contentDescription = label
            }
            row.addView(item, LinearLayout.LayoutParams(
                0,
                dp(48),
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
            item.isSelected = active
            item.setTextColor(if (active) Color.WHITE else IOS_SECONDARY)
            item.background = if (active) cyberGradientDrawable(
                intArrayOf(Color.argb(210, 139, 92, 246), Color.argb(225, 255, 43, 214)),
                8
            )
            else roundedDrawable(Color.TRANSPARENT, 8)
        }
    }

    private fun browserRow(title: String, trailing: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
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
            background = cyberPanelDrawable(
                Color.argb(150, 10, 14, 21),
                18,
                Color.argb(80, 139, 92, 246)
            )
            addView(bodyText("⌾", 38f, CYBER_CYAN).apply { gravity = Gravity.CENTER })
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
            background = cyberPanelDrawable(CYBER_SURFACE, 17, Color.argb(110, 0, 229, 255))
            addView(bodyText(glyph, 18f, CYBER_MAGENTA).apply { gravity = Gravity.CENTER })
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
            addView(bodyText(if (ok) "✓" else "✕", 20f, if (ok) IOS_GREEN else CYBER_DANGER),
                LinearLayout.LayoutParams(dp(34), dp(40)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bodyText(title, 14f, Color.WHITE, Typeface.BOLD))
                addView(bodyText(subtitle, 11f, IOS_SECONDARY))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun divider(): View = View(this).apply {
        background = cyberGradientDrawable(
            intArrayOf(Color.TRANSPARENT, Color.argb(135, 0, 229, 255), Color.TRANSPARENT),
            0
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun controlHero(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val core = FrameLayout(this@MainActivity).apply {
                addView(View(this@MainActivity).apply {
                    background = circleDrawable(Color.argb(42, 255, 43, 214))
                }, FrameLayout.LayoutParams(dp(118), dp(118), Gravity.CENTER))
                addView(View(this@MainActivity).apply {
                    background = cyberPanelDrawable(
                        Color.rgb(8, 12, 18),
                        58,
                        Color.argb(205, 0, 229, 255)
                    )
                }, FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER))
                addView(TextView(this@MainActivity).apply {
                    text = categoryGlyph(selectedCategory)
                    textSize = 42f
                    gravity = Gravity.CENTER
                    setTextColor(CYBER_CYAN)
                }, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
            }
            addView(core, LinearLayout.LayoutParams(dp(122), dp(122)))
            addView(bodyText("TVBGONE // REMOTE CORE", 10f, CYBER_MAGENTA, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.18f
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(bodyText(selectedCategory.title.uppercase(), 22f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.04f
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
        val tint = if (ready) CYBER_GREEN else CYBER_WARNING
        return card(20).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Estado del accesorio. Abrir Ajustes e información"
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
            addView(bodyText("TOCA PARA ABRIR AJUSTES / INFO  ›", 10f, CYBER_CYAN, Typeface.BOLD).apply {
                letterSpacing = 0.08f
                setPadding(0, dp(10), 0, 0)
            })
            setOnClickListener {
                performClickHaptic()
                selectTab(4)
            }
        }
    }

    private fun categoryGlyph(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "▣"
        DeviceCategory.AIR_CONDITIONER -> "❄"
        DeviceCategory.PROJECTOR -> "▰"
        DeviceCategory.SET_TOP_BOX -> "▤"
        DeviceCategory.FAN -> "✺"
        DeviceCategory.MEDIA_BOX -> "◉"
        DeviceCategory.DVD_PLAYER -> "◍"
        DeviceCategory.BLU_RAY -> "◎"
        DeviceCategory.AV_RECEIVER -> "≋"
        DeviceCategory.SOUND_BAR -> "▬"
        DeviceCategory.CAMERA -> "◈"
    }

    private fun categoryButtonTitle(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "⏻  APAGAR TELEVISORES"
        DeviceCategory.AIR_CONDITIONER -> "⏻  APAGAR AIRES"
        DeviceCategory.PROJECTOR -> "⏻  APAGAR PROYECTORES"
        else -> "PWR  PROBAR ENCENDIDO"
    }

    private fun defaultDeviceName(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "Mi TV"
        DeviceCategory.AIR_CONDITIONER -> "Mi aire"
        DeviceCategory.PROJECTOR -> "Mi proyector"
        DeviceCategory.SET_TOP_BOX -> "Mi decodificador"
        DeviceCategory.FAN -> "Mi ventilador"
        DeviceCategory.MEDIA_BOX -> "Mi TV Box"
        DeviceCategory.DVD_PLAYER -> "Mi DVD"
        DeviceCategory.BLU_RAY -> "Mi Blu-ray"
        DeviceCategory.AV_RECEIVER -> "Mi receptor A/V"
        DeviceCategory.SOUND_BAR -> "Mi barra de sonido"
        DeviceCategory.CAMERA -> "Mi cámara"
    }

    private fun categoryExplanation(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION ->
            "Prueba primero los códigos universales y TV-B-Gone que ya sabemos que funcionan, y después la base ampliada."
        DeviceCategory.AIR_CONDITIONER ->
            "Recorre señales POWER/OFF de mandos de aire acondicionado, priorizando capturas RAW."
        DeviceCategory.PROJECTOR ->
            "Recorre señales POWER/OFF de proyectores de distintas marcas y modelos."
        else ->
            "Esta categoría se configura desde Mando usando perfiles por marca y modelo de la biblioteca IR online."
    }

    private fun remoteCategorySubtitle(category: DeviceCategory): String = when (category) {
        DeviceCategory.TELEVISION -> "Smart TV y TV clásicas"
        DeviceCategory.AIR_CONDITIONER -> "Climatización"
        DeviceCategory.PROJECTOR -> "Proyectores IR"
        DeviceCategory.SET_TOP_BOX -> "Cable, TDT y satélite"
        DeviceCategory.FAN -> "Ventiladores y torres"
        DeviceCategory.MEDIA_BOX -> "Streaming y multimedia"
        DeviceCategory.DVD_PLAYER -> "DVD y reproductores"
        DeviceCategory.BLU_RAY -> "Blu-ray"
        DeviceCategory.AV_RECEIVER -> "Home cinema y receptores"
        DeviceCategory.SOUND_BAR -> "Audio y soundbars"
        DeviceCategory.CAMERA -> "Cámaras con IR"
    }

    private fun popularRemoteBrands(category: DeviceCategory): List<String> = when (category) {
        DeviceCategory.TELEVISION -> listOf(
            "Samsung", "LG", "Sony", "Panasonic", "Philips", "Sharp", "TCL", "Hisense",
            "Xiaomi", "Toshiba", "Haier", "JVC", "Grundig", "Thomson", "TD Systems"
        )
        DeviceCategory.AIR_CONDITIONER -> listOf(
            "Daikin", "Mitsubishi", "LG", "Samsung", "Panasonic", "Fujitsu", "Hisense",
            "Haier", "Gree", "Midea", "Toshiba", "Carrier"
        )
        DeviceCategory.PROJECTOR -> listOf(
            "Epson", "BenQ", "Optoma", "ViewSonic", "Sony", "Panasonic", "NEC", "Acer"
        )
        DeviceCategory.SET_TOP_BOX -> listOf(
            "Arris", "Cisco", "Humax", "Pace", "Technicolor", "Sky", "Motorola", "Sagemcom"
        )
        DeviceCategory.FAN -> listOf(
            "Dyson", "Honeywell", "Rowenta", "Hunter", "Lasko", "Dreo", "Xiaomi"
        )
        DeviceCategory.MEDIA_BOX -> listOf(
            "Apple", "Amazon", "Roku", "Nvidia", "Xiaomi", "Google", "Western Digital"
        )
        DeviceCategory.DVD_PLAYER,
        DeviceCategory.BLU_RAY -> listOf(
            "Sony", "Samsung", "LG", "Panasonic", "Philips", "Pioneer", "Toshiba"
        )
        DeviceCategory.AV_RECEIVER -> listOf(
            "Denon", "Yamaha", "Onkyo", "Pioneer", "Marantz", "Sony", "Harman Kardon"
        )
        DeviceCategory.SOUND_BAR -> listOf(
            "Samsung", "LG", "Sony", "Bose", "JBL", "Yamaha", "Polk", "Vizio"
        )
        DeviceCategory.CAMERA -> listOf(
            "Canon", "Nikon", "Sony", "Olympus", "Panasonic", "Pentax"
        )
    }

    private fun normalizedRemoteButtonName(value: String): String = value
        .trim()
        .lowercase()
        .replace("á", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ú", "u")
        .replace("ü", "u")
        .replace("ñ", "n")
        .replace("+", " plus ")
        .replace("-", " minus ")
        .replace("_", " ")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isPowerButtonName(value: String): Boolean {
        val name = normalizedRemoteButtonName(value)
        val compact = name.replace(" ", "")
        return name == "power" || name == "pwr" || name == "standby" ||
            name == "toggle power" || name == "on off" || name == "off on" ||
            name == "on" || name == "off" || name.startsWith("power ") ||
            name.startsWith("pwr ") || name.startsWith("turn on") || name.startsWith("turn off") ||
            compact in setOf("poweroff", "poweron", "turnoff", "turnon", "togglepower")
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
        DeviceCategory.SET_TOP_BOX,
        DeviceCategory.MEDIA_BOX -> listOf(
            "Power", "Home", "Menu", "Back", "OK", "Arriba", "Abajo", "Izquierda", "Derecha",
            "Channel +", "Channel -", "Guide", "Info", "Play", "Pause"
        )
        DeviceCategory.DVD_PLAYER,
        DeviceCategory.BLU_RAY -> listOf(
            "Power", "Eject", "Menu", "Back", "OK", "Arriba", "Abajo", "Izquierda", "Derecha",
            "Play", "Pause", "Stop", "Previous", "Next", "Rewind", "Fast forward"
        )
        DeviceCategory.AV_RECEIVER,
        DeviceCategory.SOUND_BAR -> listOf(
            "Power", "Input", "Vol +", "Vol -", "Mute", "Mode", "Menu", "OK"
        )
        DeviceCategory.FAN -> listOf(
            "Power", "Speed +", "Speed -", "Mode", "Swing", "Timer", "Light"
        )
        DeviceCategory.CAMERA -> listOf(
            "Power", "Shutter", "Zoom +", "Zoom -", "Menu", "Playback", "OK",
            "Arriba", "Abajo", "Izquierda", "Derecha"
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
        if (oledMode()) Color.BLACK else Color.rgb(7, 10, 15)

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun performClickHaptic() {
        if (::contentHost.isInitialized) {
            contentHost.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun performSuccessHaptic() {
        if (::contentHost.isInitialized) {
            contentHost.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun performErrorHaptic() {
        if (::contentHost.isInitialized) {
            contentHost.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

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

    @Deprecated("Activity back handling is kept for Android 11 compatibility without extra dependencies.")
    override fun onBackPressed() {
        val action = screenBackAction
        if (action != null) {
            performClickHaptic()
            action()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        screenGeneration += 1
        setKeepScreenOn(false)
        scanner.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}
