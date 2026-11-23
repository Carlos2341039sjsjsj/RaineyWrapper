package com.raineywrapper.converter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Title, icon, system and storage mode for one ROM, then convert + install. */
class ConvertActivity : GamepadActivity() {

    companion object {
        const val EXTRA_ROM = "rom"
        private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
        private val IMAGE_FOLDERS = listOf("images", "covers", "boxart", "box art", "media/images", "media/covers", "media/box2dfront")
    }

    private lateinit var romFile: File
    private lateinit var source: GameSource
    private lateinit var systems: List<GameSystem>
    private var image: File? = null
    private var busy = false
    private var previewJob: Job? = null

    private lateinit var form: LinearLayout
    private lateinit var iconView: ImageView
    private lateinit var iconNote: TextView
    private lateinit var titleInput: EditText
    private lateinit var systemSpinner: Spinner
    private lateinit var emulatorLabel: TextView
    private lateinit var emulatorSpinner: Spinner
    /** Juegos web por enlace (Unity): la direccion de la pagina del juego. */
    private lateinit var urlLabel: TextView
    private lateinit var urlInput: EditText
    private lateinit var storageLabel: TextView
    private lateinit var storageGroup: RadioGroup
    /** Installed emulator apps for the current external system (PS2), one per package. */
    private var installedEmulators: List<ExternalEmulator> = emptyList()
    private lateinit var embedRadio: RadioButton
    private lateinit var linkRadio: RadioButton
    private lateinit var info: TextView
    private lateinit var convertButton: Button
    private lateinit var saveCopyCheck: android.widget.CheckBox
    private var savedCopyName: String? = null
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var doneRow: LinearLayout

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(BrowserActivity.RESULT_PATH) ?: return@registerForActivityResult
        image = path.takeIf { it.isNotEmpty() }?.let(::File)
        refreshPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        romFile = File(intent.getStringExtra(EXTRA_ROM)!!)
        systems = Systems.all(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!busy) finish()
            }
        })

        source = try {
            GameSource.resolve(this, romFile)
        } catch (e: UserError) {
            showFatal(e.message!!)
            return
        } catch (e: Exception) {
            showFatal("Couldn't read ${romFile.name}: ${e.message}")
            return
        }
        image = matchingImage()
        buildForm()
    }

    /**
     * A cover image with the same name as the ROM, next to it or in a media subfolder
     * that frontends commonly use, e.g. "snes/Game.sfc" + "snes/images/Game.jpg".
     */
    private fun matchingImage(): File? {
        val dir = romFile.parentFile ?: return null
        val stems = setOf(
            romFile.nameWithoutExtension.lowercase(),
            File(source.displayName).nameWithoutExtension.lowercase(),
            titleFromFileName(source.displayName).lowercase(),
        )
        val folders = listOf(dir) + IMAGE_FOLDERS.map { File(dir, it) }
        return folders.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().asSequence() }
            .firstOrNull { it.ext in IMAGE_EXTENSIONS && it.nameWithoutExtension.lowercase() in stems }
    }

    private fun buildForm() {
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(form) })

        form.addView(label("Convert game", 22f))
        form.addView(label(romFile.absolutePath, 13f, Palette.SUBTLE).apply { setPadding(0, dp(2), 0, dp(14)) })

        // Icon + title side by side; handhelds are landscape.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val iconCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        iconView = ImageView(this)
        iconCol.addView(iconView, LinearLayout.LayoutParams(dp(112), dp(112)))
        iconNote = label("", 12f, Palette.SUBTLE).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        iconCol.addView(iconNote, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT))
        iconCol.addView(button("Choose image…") {
            pickImage.launch(
                Intent(this, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_PICK_IMAGE, true)
                    .putExtra(BrowserActivity.EXTRA_START_DIR, romFile.parent),
            )
        }, LinearLayout.LayoutParams(dp(150), dp(44)).apply { topMargin = dp(6) })
        top.addView(iconCol)

        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0) }
        fields.addView(label("Title", 14f, Palette.SUBTLE))
        titleInput = EditText(this).apply {
            setText(titleFromFileName(source.displayName))
            setTextColor(Palette.TEXT)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    refreshInfo()
                    if (image == null) refreshPreview(debounce = true)
                }
            })
        }
        fields.addView(titleInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fields.addView(label("System", 14f, Palette.SUBTLE).apply { setPadding(0, dp(10), 0, 0) })
        systemSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ConvertActivity, android.R.layout.simple_spinner_dropdown_item, systems.map { it.name })
            setSelection(systems.indexOf(source.system).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val chosen = systems[position]
                    if (chosen != source.system) {
                        source = runCatching { GameSource.resolve(this@ConvertActivity, romFile, chosen) }.getOrElse { source }
                        applyStorageDefault()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        fields.addView(systemSpinner)
        emulatorLabel = label("Emulator", 14f, Palette.SUBTLE).apply { setPadding(0, dp(10), 0, 0) }
        fields.addView(emulatorLabel)
        emulatorSpinner = Spinner(this).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = refreshInfo()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        fields.addView(emulatorSpinner)
        urlLabel = label("Enlace del juego (https)", 14f, Palette.SUBTLE).apply { setPadding(0, dp(10), 0, 0) }
        fields.addView(urlLabel)
        urlInput = EditText(this).apply {
            hint = "https://usuario.itch.io/juego"
            setHintTextColor(Palette.SUBTLE)
            setTextColor(Palette.TEXT)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        fields.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(fields, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(top)

        form.addView(patchSection())
        storageLabel = label("Storage", 14f, Palette.SUBTLE).apply { setPadding(0, dp(16), 0, 0) }
        form.addView(storageLabel)
        val group = RadioGroup(this)
        storageGroup = group
        embedRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Put the game inside the app (works on its own; uses extra space)"
            setTextColor(Palette.TEXT)
        }
        linkRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Play from where the file is now (small app; keep the file in place)"
            setTextColor(Palette.TEXT)
        }
        group.addView(embedRadio)
        group.addView(linkRadio)
        group.setOnCheckedChangeListener { _, _ -> refreshInfo() }
        form.addView(group)
        applyStorageDefault()
        saveCopyCheck = android.widget.CheckBox(this).apply {
            text = "Guardar tambien una copia del APK en Descargas (respaldo)"
            setTextColor(Palette.TEXT)
        }
        form.addView(saveCopyCheck)

        info = label("", 14f, Palette.SUBTLE).apply { setPadding(0, dp(10), 0, dp(10)) }
        form.addView(info)

        convertButton = button("Convert and install", primary = true) { startConversion() }
        form.addView(convertButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; max = 1000 }
        form.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        statusText = label("", 15f).apply { visibility = View.GONE }
        form.addView(statusText)
        doneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        form.addView(doneRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        refreshPreview()
        refreshInfo()
        // Start on Convert, not the title field, so pressing A converts rather than opening the keyboard.
        defaultAction = convertButton
        form.isFocusableInTouchMode = true
        form.defaultFocusHighlightEnabled = false
        focusHolder = form
        form.requestFocus()
    }

    /** Mods de ROM: si junto a la ROM hay parches (.ips .bps .ups .xdelta) deja aplicar uno y convertir el resultado. */
    private fun patchSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dir = romFile.parentFile ?: return box
        if (romFile.ext == "zip") return box
        val patches = dir.listFiles().orEmpty()
            .filter { it.isFile && it.ext in RomPatcher.EXTENSIONS }
            .sortedBy { it.name.lowercase() }
        if (patches.isEmpty()) return box
        box.addView(
            button("Aplicar un parche de mod...") { choosePatch(patches) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) },
        )
        box.addView(label("Crea una copia parcheada de la ROM; la original no se toca.", 12f, Palette.SUBTLE))
        return box
    }

    private fun choosePatch(patches: List<File>) {
        if (busy) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Elegi el parche")
            .setItems(patches.map { it.name }.toTypedArray()) { _, which -> applyPatch(patches[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyPatch(patch: File) {
        busy = true
        android.widget.Toast.makeText(this, "Aplicando el parche...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { patchedCopy(patch) } }
            busy = false
            result.onSuccess { out ->
                android.widget.Toast.makeText(this@ConvertActivity, "Listo: " + out.name, android.widget.Toast.LENGTH_LONG).show()
                startActivity(Intent(this@ConvertActivity, ConvertActivity::class.java).putExtra(EXTRA_ROM, out.absolutePath))
                finish()
            }.onFailure { e ->
                val msg = if (e is UserError) e.message
                else if (e is OutOfMemoryError) "No hay memoria para parchear una ROM tan grande. Usa hack64.net en el navegador."
                else "No se pudo aplicar el parche: " + e.message
                android.app.AlertDialog.Builder(this@ConvertActivity).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    /** Escribe "<nombre> (parcheado).<ext>" junto a la ROM (o en la carpeta de la app si ahi no se puede escribir). */
    private fun patchedCopy(patch: File): File {
        if (romFile.length() > RomPatcher.MAX_BYTES) {
            throw UserError("La ROM es demasiado grande para parchearla en el celular. Usa hack64.net en el navegador.")
        }
        val rom = romFile.readBytes()
        val bytes = patch.readBytes()
        val name = romFile.nameWithoutExtension + " (parcheado)." + romFile.extension
        var out = File(romFile.parentFile, name)
        try {
            out.outputStream().buffered().use { RomPatcher.apply(rom, bytes, it) }
        } catch (e: java.io.IOException) {
            out = File(getExternalFilesDir(null) ?: cacheDir, name)
            out.outputStream().buffered().use { RomPatcher.apply(rom, bytes, it) }
        }
        return out
    }

    private fun applyStorageDefault() {
        val external = source.system.isExternal
        val folder = source.system.folderGame
        linkRadio.isEnabled = source.canLink && external
        // External emulators read the file in place; PS2 images are too big to bundle anyway.
        // Folder games (RPG Maker) need every file next to the .exe / .html, so they always play in place.
        embedRadio.isEnabled = !external && !folder
        val link = source.canLink
        (if (link) linkRadio else embedRadio).isChecked = true

        installedEmulators = source.system.external.filter { isInstalled(it.packageName) }.distinctBy { it.packageName }
        emulatorLabel.visibility = if (external) View.VISIBLE else View.GONE
        emulatorSpinner.visibility = if (external && installedEmulators.isNotEmpty()) View.VISIBLE else View.GONE
        emulatorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, installedEmulators.map { it.name })
        // Juego web por enlace: se pide la direccion y no tiene sentido elegir donde guardar el juego.
        val web = source.system.webUrl
        urlLabel.visibility = if (web) View.VISIBLE else View.GONE
        urlInput.visibility = if (web) View.VISIBLE else View.GONE
        storageLabel.visibility = if (web) View.GONE else View.VISIBLE
        storageGroup.visibility = if (web) View.GONE else View.VISIBLE
        refreshInfo()
    }

    private fun isInstalled(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** The chosen emulator first, then the rest as fallbacks if it's uninstalled later. */
    private fun emulatorOrder(): List<ExternalEmulator> {
        val chosen = installedEmulators.getOrNull(emulatorSpinner.selectedItemPosition)
        return listOfNotNull(chosen) + source.system.external.filter { it != chosen }
    }

    private fun currentTitle() = titleInput.text.toString().trim().ifEmpty { titleFromFileName(source.displayName) }

    private fun refreshInfo() {
        if (!::info.isInitialized) return
        val appId = appIdFor(currentTitle())
        val lines = mutableListOf<String>()
        lines += "App: $appId"
        val installed = runCatching { packageManager.getPackageInfo(appId, 0) }.isSuccess
        if (installed) lines += "Already installed — it will be updated and its saves kept."
        if (source.system.biosRequired) {
            val bios = findBios(source.file, source.system)
            lines += if (bios.isEmpty()) {
                "⚠ ${source.system.name} needs a BIOS. Put your BIOS dump (.bin) in a \"bios\" folder (for example ps2/bios) and reopen this game."
            } else {
                "BIOS: ${bios.values.first().name}"
            }
        }
        if (source.system.isExternal) {
            if (installedEmulators.isEmpty()) {
                val names = source.system.external.map { it.name }.distinct().joinToString(" or ")
                lines += "⚠ Install $names first; this game app opens the game in it."
            } else {
                lines += "Opens in ${installedEmulators.getOrNull(emulatorSpinner.selectedItemPosition)?.name ?: installedEmulators.first().name}. " +
                    "The game app asks once for file access, then hands the file over."
            }
        } else if (source.system.webUrl) {
            lines += "Esta carpeta es la version de Windows de un juego de Unity y no corre en el telefono."
            lines += "Si el juego tiene version web (en itch.io, el boton \"Run game\"), pega aca la direccion de su pagina: " +
                "la app la abre en pantalla completa. Necesita internet la primera vez; despues puede abrir sin conexion con lo ya guardado, " +
                "pero no esta garantizado."
        } else if (source.system.folderGame) {
            lines += "El juego se lee desde su carpeta (${romFile.parentFile?.name ?: "?"}): no la muevas ni la borres. " +
                "La app pide permiso de archivos la primera vez."
        } else if (embedRadio.isChecked) {
            val need = Converter.spaceNeeded(source, true)
            val free = Converter.freeSpace(this)
            lines += "Needs about ${formatSize(need)} free while converting (${formatSize(free)} available)."
            if (free < need) lines += "⚠ Not enough space. Choose \"Play from where the file is now\"."
        } else {
            lines += "The game will ask once for file access, then read ${romFile.name} from its current folder."
        }
        info.text = lines.joinToString("\n")
    }

    private fun refreshPreview(debounce: Boolean = false) {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            if (debounce) delay(400)
            val title = currentTitle()
            val img = image
            val bitmap = withContext(Dispatchers.Default) { runCatching { IconMaker.preview(title, img, dp(112)) }.getOrNull() }
            iconView.setImageBitmap(bitmap)
            iconNote.text = img?.name ?: "Title icon (no image)"
        }
    }

    private fun startConversion() {
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            statusText.visibility = View.VISIBLE
            statusText.setTextColor(Palette.TEXT)
            statusText.text = "Allow RaineyWrapper to install apps (one-time), then press Convert again."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        var gameUrl: String? = null
        if (source.system.webUrl) {
            gameUrl = normalizeGameUrl(urlInput.text.toString())
            if (gameUrl == null) {
                statusText.visibility = View.VISIBLE
                statusText.setTextColor(Palette.TEXT)
                statusText.text = "Escribi la direccion de la pagina del juego (tiene que empezar con https://)."
                urlInput.requestFocus()
                return
            }
        }
        val request = Converter.Request(
            source, currentTitle(), appIdFor(currentTitle()), image, embedRadio.isChecked,
            if (source.system.isExternal) emulatorOrder() else emptyList(),
            gameUrl,
        )
        setBusy(true)
        lifecycleScope.launch {
            var apk: File? = null
            try {
                apk = withContext(Dispatchers.IO) {
                    Converter.convert(this@ConvertActivity, request) { text, p -> runOnUiThread { showStatus(text, p) } }
                }
                savedCopyName = null
                if (saveCopyCheck.isChecked) {
                    showStatus("Guardando una copia en Descargas…", null)
                    val built: File = apk!!
                    savedCopyName = withContext(Dispatchers.IO) { saveApkCopy(built, request.title) }
                }
                showStatus("Installing… (confirm on the next screen)", 0f)
                val result = Installer.install(this@ConvertActivity, apk, request.appId) { p ->
                    runOnUiThread { progress.progress = (p * 1000).toInt() }
                }
                when (result) {
                    is Installer.Result.Success -> showDone(request)
                    is Installer.Result.Failure -> showError(result.message)
                }
            } catch (e: UserError) {
                showError(e.message!!)
            } catch (e: Exception) {
                showError("Conversion failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(Dispatchers.IO) { apk?.delete(); Converter.buildDir(this@ConvertActivity).deleteRecursively() }
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listOf(titleInput, systemSpinner, emulatorSpinner, urlInput, convertButton).forEach { it.isEnabled = !value }
        embedRadio.isEnabled = !value && !source.system.isExternal && !source.system.folderGame
        linkRadio.isEnabled = !value && source.canLink && source.system.isExternal
        if (value) {
            doneRow.visibility = View.GONE
            progress.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.setTextColor(Palette.TEXT)
        }
    }

    private fun showStatus(text: String, p: Float?) {
        statusText.text = text
        progress.isIndeterminate = p == null
        if (p != null) progress.progress = (p * 1000).toInt()
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.setTextColor(Palette.ERROR)
        statusText.text = message
        defaultAction = convertButton
        convertButton.requestFocus()
    }

    /** Copia el APK a Descargas (MediaStore en Android 10+). Devuelve el nombre del archivo o null si fallo. */
    private fun saveApkCopy(apk: File, title: String): String? = try {
        val name = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "game" } + ".apk"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { out -> apk.inputStream().use { it.copyTo(out) } }
            val done = android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            name
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            apk.copyTo(File(dir, name), overwrite = true)
            name
        }
    } catch (e: Exception) {
        null
    }

    private fun showDone(request: Converter.Request) {
        progress.visibility = View.GONE
        statusText.setTextColor(Palette.TEXT)
        statusText.text = "✓ ${request.title} is installed. Find it on your home screen or app drawer." +
            (savedCopyName?.let { "\nCopia del APK guardada en Descargas: $it" } ?: "")
        doneRow.removeAllViews()
        val play = button("Play now", primary = true) {
            packageManager.getLaunchIntentForPackage(request.appId)?.let { startActivity(it) }
        }
        doneRow.addView(play, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) })
        doneRow.addView(button("Convert another") { finish() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        doneRow.visibility = View.VISIBLE
        refreshInfo()
        defaultAction = play
        play.requestFocus()
    }

    private fun showFatal(message: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        box.addView(label(message, 17f).apply { gravity = Gravity.CENTER })
        val back = button("Back") { finish() }
        box.addView(back, LinearLayout.LayoutParams(dp(160), dp(48)).apply { topMargin = dp(24) })
        setContentView(box)
        back.requestFocus()
    }
}
