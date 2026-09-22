package com.gokuencinar.iruniversal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.gokuencinar.iruniversal.storage.SavedDevice
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var contentHost: FrameLayout
    private lateinit var transmitter: AutoIrTransmitter
    private lateinit var scanner: IrScanner
    private lateinit var store: AppStore

    private val worker = Executors.newSingleThreadExecutor()
    private val onlineLibrary = OnlineIrLibrary()
    private val learner = IrLearner()

    private var learnedCandidate: IrCode? = null
    private var importedSignals: List<ImportedIrSignal> = emptyList()

    companion object {
        private const val REQUEST_MIC = 1001
        private const val REQUEST_IMPORT_IR = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transmitter = AutoIrTransmitter(this)
        scanner = IrScanner { transmitter.active() }
        store = AppStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = TextView(this).apply {
            text = "IR Universal · Android"
            textSize = 23f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        tabsScroll.addView(tabs)

        listOf(
            "Control" to { showControl() },
            "Códigos" to { showCodes() },
            "Online" to { showOnline() },
            "Aprender" to { showLearn() },
            "Equipos" to { showSavedDevices() },
            "Diagnóstico" to { showDiagnostics() }
        ).forEach { (title, action) ->
            tabs.addView(tabButton(title, action))
        }

        root.addView(tabsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
        showControl()
    }

    private fun showControl() {
        scanner.stop()
        val body = installScrollableBody()

        body.addView(sectionTitle("Barrido IR"))

        val category = enumSpinner(DeviceCategory.entries.map { it.title })
        val region = enumSpinner(TvRegion.entries.map { it.title })
        val pace = enumSpinner(ScanPace.entries.map { it.title })
        val mode = enumSpinner(TransmitterMode.entries.map { it.title })

        body.addView(label("Categoría"))
        body.addView(category)
        body.addView(label("Región TV"))
        body.addView(region)
        body.addView(label("Modo de transmisión"))
        body.addView(mode)
        body.addView(label("Velocidad"))
        body.addView(pace)

        val status = infoText("Listo. " + transmitter.active().name)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        body.addView(status)
        body.addView(progress, matchWrap())

        val start = actionButton("INICIAR BARRIDO")
        val pause = secondaryButton("PAUSAR / REANUDAR")
        val stop = secondaryButton("DETENER")
        val worked = actionButton("FUNCIONÓ")

        body.addView(start)
        body.addView(pause)
        body.addView(stop)
        body.addView(worked)

        start.setOnClickListener {
            val selectedCategory = DeviceCategory.entries[category.selectedItemPosition]
            val selectedRegion = TvRegion.entries[region.selectedItemPosition]
            val selectedPace = ScanPace.entries[pace.selectedItemPosition]
            transmitter.mode = TransmitterMode.entries[mode.selectedItemPosition]

            val codes = IrCodeCatalog.codes(selectedCategory, selectedRegion)
            if (codes.isEmpty()) {
                status.text = "No hay una base offline para esta categoría todavía. Usa Online o importa un mando .ir."
                return@setOnClickListener
            }

            val active = transmitter.active()
            if (!active.isAvailable()) {
                status.text = "El transmisor seleccionado no está disponible. Revisa Diagnóstico."
                return@setOnClickListener
            }

            status.text = "Iniciando " + codes.size + " códigos mediante " + active.name
            progress.progress = 0
            scanner.start(codes, selectedPace) { p ->
                runOnUiThread {
                    progress.progress = if (p.total == 0) 0 else (p.index * 1000 / p.total)
                    status.text = when {
                        p.code == null -> "Barrido terminado."
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
                status.text = "Barrido reanudado."
            } else {
                scanner.pause()
                status.text = "Barrido pausado."
            }
        }

        stop.setOnClickListener {
            scanner.stop()
            status.text = "Barrido detenido."
        }

        worked.setOnClickListener {
            val candidates = scanner.candidates()
            if (candidates.isEmpty()) {
                toast("Todavía no hay candidatos recientes.")
            } else {
                scanner.pause()
                showCodeChooser("¿Qué código funcionó?", candidates) { code ->
                    saveDeviceDialog(
                        DeviceCategory.entries[category.selectedItemPosition],
                        code
                    )
                }
            }
        }
    }

    private fun showCodes() {
        scanner.stop()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        contentHost.replace(root)

        root.addView(sectionTitle("Códigos offline"))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val category = enumSpinner(DeviceCategory.entries.map { it.title })
        val region = enumSpinner(TvRegion.entries.map { it.title })
        val source = enumSpinner(listOf("Todos", "Universal", "TV-B-Gone"))
        val search = EditText(this).apply {
            hint = "Buscar por marca, nombre o ID"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val selectedInfo = infoText("Toca un código para seleccionarlo.")
        var selected: IrCode? = null
        var current: List<IrCode> = emptyList()

        controls.addView(category)
        controls.addView(region)
        controls.addView(source)
        controls.addView(search)
        controls.addView(selectedInfo)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val send = actionButton("PROBAR")
        val save = secondaryButton("GUARDAR")
        val importFile = secondaryButton("IMPORTAR .IR")
        buttons.addView(send, weighted())
        buttons.addView(save, weighted())
        controls.addView(buttons)
        controls.addView(importFile)

        root.addView(controls, matchWrap())

        val list = ListView(this).apply {
            dividerHeight = 1
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        fun refresh() {
            val cat = DeviceCategory.entries[category.selectedItemPosition]
            val reg = TvRegion.entries[region.selectedItemPosition]
            val q = search.text.toString().trim().lowercase()
            val filter = source.selectedItemPosition

            current = IrCodeCatalog.codes(cat, reg).filter { code ->
                val sourceOk = when (filter) {
                    1 -> code.sourceLabel == "Universal"
                    2 -> code.sourceLabel == "TV-B-Gone"
                    else -> true
                }
                val textOk = q.isBlank() ||
                    code.id.lowercase().contains(q) ||
                    code.displayName.lowercase().contains(q) ||
                    code.brandHint.lowercase().contains(q)
                sourceOk && textOk
            }

            list.adapter = darkArrayAdapter(
                current.map { it.displayName + "  ·  " + it.effectiveCarrierHz + " Hz" }
            )
        }

        category.onItemSelectedListener = simpleSelection { refresh() }
        region.onItemSelectedListener = simpleSelection { refresh() }
        source.onItemSelectedListener = simpleSelection { refresh() }
        search.addTextChangedListener(simpleTextWatcher { refresh() })

        list.setOnItemClickListener { _, _, position, _ ->
            selected = current.getOrNull(position)
            selected?.let {
                selectedInfo.text = it.displayName + "\n" + it.id + "\n" +
                    it.sourceLabel + " · " + it.effectiveCarrierHz + " Hz · " +
                    it.durationMillis + " ms"
            }
        }

        send.setOnClickListener {
            val code = selected ?: return@setOnClickListener toast("Selecciona un código.")
            sendAsync(code, selectedInfo)
        }
        save.setOnClickListener {
            val code = selected ?: return@setOnClickListener toast("Selecciona un código.")
            saveDeviceDialog(DeviceCategory.entries[category.selectedItemPosition], code)
        }
        importFile.setOnClickListener { openIrFilePicker() }

        refresh()
    }

    private fun showOnline() {
        scanner.stop()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        contentHost.replace(root)

        root.addView(sectionTitle("Biblioteca IR online"))

        val category = enumSpinner(DeviceCategory.entries.map { it.title })
        val brand = EditText(this).apply {
            hint = "Marca (ej. Samsung)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val model = EditText(this).apply {
            hint = "Modelo (opcional)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val deep = CheckBox(this).apply {
            text = "Búsqueda profunda"
            setTextColor(Color.LTGRAY)
        }
        val searchButton = actionButton("BUSCAR")
        val status = infoText("Busca por marca y, si lo conoces, por modelo.")
        val list = ListView(this)
        var results: List<OnlineIrRemote> = emptyList()

        root.addView(category)
        root.addView(brand)
        root.addView(model)
        root.addView(deep)
        root.addView(searchButton)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        searchButton.setOnClickListener {
            val b = brand.text.toString()
            val m = model.text.toString()
            if (b.isBlank() && m.isBlank()) {
                status.text = "Escribe al menos una marca o un modelo."
                return@setOnClickListener
            }
            status.text = "Consultando bibliotecas IR…"
            searchButton.isEnabled = false
            worker.execute {
                val found = runCatching {
                    onlineLibrary.search(
                        b, m,
                        DeviceCategory.entries[category.selectedItemPosition],
                        deep = deep.isChecked
                    )
                }
                runOnUiThread {
                    searchButton.isEnabled = true
                    found.onSuccess {
                        results = it
                        list.adapter = darkArrayAdapter(
                            it.map { remote -> remote.displayName + " · " + remote.source.title }
                        )
                        status.text = if (it.isEmpty()) "Sin resultados." else "Encontrados " + it.size + " mandos."
                    }.onFailure {
                        status.text = "Error: " + (it.message ?: "desconocido")
                    }
                }
            }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val remote = results.getOrNull(position) ?: return@setOnItemClickListener
            status.text = "Descargando " + remote.displayName + "…"
            worker.execute {
                val loaded = runCatching { onlineLibrary.download(remote) }
                runOnUiThread {
                    loaded.onSuccess { value ->
                        status.text = value.name + " · " + value.signals.size + " señales"
                        showImportedSignals(value.signals, DeviceCategory.entries[category.selectedItemPosition])
                    }.onFailure {
                        status.text = "Error: " + (it.message ?: "desconocido")
                    }
                }
            }
        }
    }

    private fun showLearn() {
        scanner.stop()
        val body = installScrollableBody()

        body.addView(sectionTitle("Aprender IR por entrada de audio"))
        body.addView(infoText(
            "Necesitas un receptor IR demodulado conectado a una entrada de audio. " +
                "El adaptador de LEDs usado para emitir no puede aprender por sí solo."
        ))

        val carrier = enumSpinner(listOf("38 kHz", "36 kHz", "40 kHz", "56 kHz"))
        val status = infoText("Pulsa Capturar y después un botón del mando.")
        val capture = actionButton("CAPTURAR")
        val test = secondaryButton("PROBAR CAPTURA")
        val save = secondaryButton("GUARDAR CAPTURA")

        body.addView(carrier)
        body.addView(status)
        body.addView(capture)
        body.addView(test)
        body.addView(save)

        capture.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                status.text = "Concede permiso de micrófono y vuelve a pulsar Capturar."
                return@setOnClickListener
            }

            val hz = intArrayOf(38_000, 36_000, 40_000, 56_000)[carrier.selectedItemPosition]
            status.text = "Capturando durante ~1,3 s…"
            capture.isEnabled = false
            worker.execute {
                val result = runCatching { learner.capture(hz) }
                runOnUiThread {
                    capture.isEnabled = true
                    result.onSuccess {
                        learnedCandidate = it.code
                        if (it.code != null) {
                            val analysis = IrSignalAnalyzer.analyze(it.code)
                            status.text = it.message + "\nProtocolo probable: " +
                                analysis.protocolHint + " (" + analysis.confidence + "%) · " +
                                analysis.segments + " segmentos"
                        } else {
                            status.text = it.message
                        }
                    }.onFailure {
                        status.text = "Error de captura: " + (it.message ?: "desconocido")
                    }
                }
            }
        }

        test.setOnClickListener {
            val code = learnedCandidate ?: return@setOnClickListener toast("Primero captura una señal.")
            sendAsync(code, status)
        }

        save.setOnClickListener {
            val code = learnedCandidate ?: return@setOnClickListener toast("Primero captura una señal.")
            val learned = store.loadLearned()
            learned += code
            store.saveLearned(learned)
            saveDeviceDialog(DeviceCategory.TELEVISION, code)
        }
    }

    private fun showSavedDevices() {
        scanner.stop()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(Color.BLACK)
        }
        contentHost.replace(root)

        root.addView(sectionTitle("Mis equipos"))
        val status = infoText("Toca un equipo para enviar POWER. Mantén pulsado para eliminarlo.")
        val list = ListView(this)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        fun refresh() {
            val devices = store.loadDevices()
            list.adapter = darkArrayAdapter(
                devices.map { it.name + " · " + it.category.shortTitle + " · " + it.code.effectiveCarrierHz + " Hz" }
            )
            list.setOnItemClickListener { _, _, position, _ ->
                val device = devices.getOrNull(position) ?: return@setOnItemClickListener
                sendAsync(device.code, status)
            }
            list.setOnItemLongClickListener { _, _, position, _ ->
                val device = devices.getOrNull(position) ?: return@setOnItemLongClickListener true
                AlertDialog.Builder(this)
                    .setTitle("Eliminar")
                    .setMessage("¿Eliminar " + device.name + "?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        val updated = store.loadDevices()
                        updated.removeAll { it.id == device.id }
                        store.saveDevices(updated)
                        refresh()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                true
            }
        }

        refresh()
    }

    private fun showDiagnostics() {
        scanner.stop()
        val body = installScrollableBody()

        body.addView(sectionTitle("Diagnóstico"))
        val diag = infoText(transmitter.diagnostics())
        body.addView(diag)

        val mode = enumSpinner(TransmitterMode.entries.map { it.title })
        mode.setSelection(transmitter.mode.ordinal)
        body.addView(label("Modo de transmisión"))
        body.addView(mode)

        val refresh = secondaryButton("ACTUALIZAR")
        val t36 = secondaryButton("PROBAR 36 kHz")
        val t38 = actionButton("PROBAR 38 kHz")
        val t40 = secondaryButton("PROBAR 40 kHz")

        body.addView(refresh)
        body.addView(t36)
        body.addView(t38)
        body.addView(t40)

        body.addView(infoText(
            "Audio IR: usa volumen multimedia alto, salida estéreo y balance centrado. " +
                "Android puede remuestrear algunas rutas; 96 kHz es preferible para este adaptador."
        ))

        mode.onItemSelectedListener = simpleSelection {
            transmitter.mode = TransmitterMode.entries[mode.selectedItemPosition]
            diag.text = transmitter.diagnostics()
        }
        refresh.setOnClickListener { diag.text = transmitter.diagnostics() }
        t36.setOnClickListener { sendTestCarrier(36_000, diag) }
        t38.setOnClickListener { sendTestCarrier(38_000, diag) }
        t40.setOnClickListener { sendTestCarrier(40_000, diag) }

        val audioSettings = secondaryButton("AJUSTES DE SONIDO")
        body.addView(audioSettings)
        audioSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    private fun sendTestCarrier(hz: Int, status: TextView) {
        val code = IrCode("test-" + hz, hz, listOf(300_000, 50_000))
        sendAsync(code, status)
    }

    private fun sendAsync(code: IrCode, status: TextView) {
        val active = transmitter.active()
        status.text = "Enviando " + code.displayName + " mediante " + active.name + "…"
        worker.execute {
            val result = runCatching { active.send(code) }
            runOnUiThread {
                result.onSuccess {
                    status.text = "Enviado: " + code.displayName + " · " + code.effectiveCarrierHz + " Hz"
                }.onFailure {
                    status.text = "Error: " + (it.message ?: "desconocido")
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
            setText(suggested)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Guardar equipo/código")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim().ifBlank { category.shortTitle }
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
        if (requestCode != REQUEST_IMPORT_IR || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            toast("No se pudo leer el archivo.")
            return
        }

        importedSignals = FlipperIrCodec.parse(text)
        if (importedSignals.isEmpty()) {
            toast("El archivo no contiene señales Flipper compatibles.")
        } else {
            showImportedSignals(importedSignals, DeviceCategory.TELEVISION)
        }
    }

    private fun installScrollableBody(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(28))
            setBackgroundColor(Color.BLACK)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(body, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        contentHost.removeAllViews()
        contentHost.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return body
    }

    private fun FrameLayout.replace(view: View) {
        if (view.parent === this) return
        removeAllViews()
        addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 21f
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.GRAY)
        setPadding(0, dp(10), 0, dp(3))
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.LTGRAY)
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    private fun actionButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(190, 35, 35))
    }

    private fun secondaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(45, 45, 45))
    }

    private fun tabButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(28, 28, 28))
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { action() }
    }

    private fun enumSpinner(values: List<String>) = Spinner(this).apply {
        adapter = darkArrayAdapter(values)
    }

    private fun darkArrayAdapter(values: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE)
                    it.setBackgroundColor(Color.rgb(20, 20, 20))
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE)
                    it.setBackgroundColor(Color.rgb(25, 25, 25))
                }
            }
        }

    private fun simpleSelection(action: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                action()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun simpleTextWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scanner.stop()
        worker.shutdownNow()
        super.onDestroy()
    }
}
