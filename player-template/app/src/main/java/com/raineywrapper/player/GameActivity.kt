package com.raineywrapper.player

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.GLRetroEvents
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RaineyWrapper"
        private const val SRAM_AUTOSAVE_MS = 30_000L
        private const val PREFS = "player"
        private const val PREF_TOUCH = "touch_controls"
        private const val PREF_FILTER = "screen_filter"
        private const val PREF_RESOLUTION = "resolution_level"
  const val PREF_MULTIPLAYER = "multiplayer_level"
  const val PREF_DOLPHIN_OPT = "dolphin_opt_level"
        private const val PREF_WII_CONTROLLER = "wii_controller"
        private const val PREF_FPS = "fps_counter"
        private const val PREF_PAD_MODE = "pad_mode"
        private const val PREF_MOD_PATH = "mod_path"
        private const val PREF_PSP_GFX = "psp_gfx_level"
        private const val PREF_DOLPHIN_CLOCK = "dolphin_clock_level"
        private const val PREF_PSP_FS = "psp_frameskip_level"
        private const val PREF_PSP_CHEATS = "psp_cheats"
        private const val PREF_NET_SCAN = "psp_net_scan"
        private const val PREF_N64_OPT = "n64_opt_level"
        private const val PREF_MOVED_PATH = "moved_path"
        private const val PREF_MOVED_FROM = "moved_from"
        private const val PREF_ASKED_MOVE = "asked_move"
        private const val PREF_MOD_TEX = "mod_textures"
        private const val PREF_PSP_BACKEND = "psp_backend"
        private const val PREF_BACKEND_TRIAL = "psp_backend_trial"
        private const val PREF_MP_IP = "mp_server_ip"
        private const val PREF_MP_MAC = "mp_mac"
        private const val PREF_LITE = "lite_mode"
        private const val PREF_SUSTAINED = "sustained_perf"
        private const val PREF_LITE_ASKED = "lite_asked"
        private const val PREF_DS_LAND = "ds_landscape"
        private const val PREF_DS_PERF = "ds_perf_high"
        private const val PREF_DS_SOUND = "ds_sound_level"
        private const val PREF_DS_WFC = "ds_wfc_server"
    }

    private lateinit var container: FrameLayout
    private var redirected = false
    private lateinit var config: GameConfig
    private lateinit var saves: SaveStore
    private lateinit var menu: GameMenu
    private var retroView: GLRetroView? = null
    private var gamepad: GamepadInput? = null
    private var overlay: TouchOverlay? = null
    private var message: View? = null
    private var fpsLabel: TextView? = null
    private var starting = false

    /** True once the core has loaded the game; SRAM and states can't be touched before that. */
    private var gameRunning = false

    /** Resolution level the core was started with; a different saved level needs a restart. */
    private var startedResolution = 0

    /** Wii controller the core was started with; like resolution, a change needs a restart. */
    private var startedMultiplayer = 0
    private var startedDolphin = 1
    private var startedPspGfx = 0
    private var startedPspFs = 0
    private var startedN64 = 0
    private var currentRom: File? = null
    private var startedPspBackend = 0
    private var startedWiiController = WiiController.NUNCHUK

    /** Physical Start/Select currently held, for the Start+Select menu shortcut. */
    private val heldCombo = mutableSetOf<Int>()

    private val pickMod = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMod(uri)
    }
    private var skinKey: String? = null
    private val pickSkin = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = skinKey
        if (uri != null && k != null) overlay?.applySkin(k, uri)
    }
    private val pickStartSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = AudioPack.importStart(this, uri)
            Toast.makeText(this, if (ok) "Sonido de inicio guardado. Suena al abrir el juego" else "No se pudo usar ese archivo (maximo 40 MB)", Toast.LENGTH_LONG).show()
            menu.refresh()
        }
    }
    private val pickSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSave(uri)
    }
    private val pickModZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModZip(uri)
    }
    private val pickPatch = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) applyPatch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try { System.loadLibrary("c++_shared") } catch (e: Throwable) { android.util.Log.w("Rainey", "c++_shared no cargo: $e") }
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        // Juegos web: los abre otra pantalla, sin nucleo libretro.
        // rpgmv = RPG Maker MV/MZ (carpeta local), unityweb = juego de Unity que se abre desde un enlace.
        val webScreen = try {
            when (GameConfig.load(this).system) {
                "rpgmv", "swf" -> WebGameActivity::class.java
                "unityweb" -> UrlGameActivity::class.java
                else -> null
            }
        } catch (e: Exception) { null }
        if (webScreen != null) {
            redirected = true
            startActivity(Intent(this, webScreen))
            finish()
            return
        }
        installCrashLog()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySustained()
        preferSixtyHz()
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)
        enterImmersiveMode()

        config = GameConfig.load(this)
        if (isDualScreen() && !dsLandscape()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        saves = SaveStore(this)
        PpssppMultiplayer.customIp = prefs().getString(PREF_MP_IP, null)
        PpssppMultiplayer.mac = prefs().getString(PREF_MP_MAC, null) ?: newMac()
        revertFailedBackendTrial()
        revertFailedAzaharTrial()
        markAzaharTrial()
        menu = GameMenu(this, config.title).apply { setItems(menuItems()) }
        if (savedInstanceState == null) AudioPack.playStart(this)
    }

    /** Writes uncaught Java/Kotlin errors to Download/RaineyWrapper-crash.txt (falls back to the app's own folder). */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "== " + java.util.Date() + " " + packageName + "\n" + Log.getStackTraceString(e) + "\n"
                val dirs = listOfNotNull(File(Environment.getExternalStorageDirectory(), "Download"), getExternalFilesDir(null))
                for (dir in dirs) {
                    try {
                        File(dir, "RaineyWrapper-crash.txt").appendText(text)
                        break
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!redirected && retroView == null && !starting) prepareAndStart()
    }

    private fun prepareAndStart() {
        if (config.linked && !Environment.isExternalStorageManager()) {
            showMessage(
                "${config.title}\n\nThis game plays its ROM from your device storage, so it needs " +
                    "permission to read files.",
                "Allow file access",
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
            return
        }
        if (config.external.isNotEmpty()) {
            launchExternal()
            return
        }
        starting = true
        lifecycleScope.launch {
            val loading = if (config.isExtracted(this@GameActivity)) null else showLoading()
            val rom = try {
                withContext(Dispatchers.IO) {
                    config.installSystemFiles(this@GameActivity, saves.systemDir)
                    config.installCoreData(this@GameActivity, saves.systemDir)
                    var lastPercent = -1
                    movedFile()?.also { File(config.rom).delete() } ?: config.extractRom(this@GameActivity) { progress ->
                        val percent = (progress * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            loading?.post { loading.text = loadingText(percent) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare game files", e)
                loading?.let { container.removeView(it) }
                showMessage(e.message ?: "Failed to prepare game files", "Quit") { finishAndRemoveTask() }
                starting = false
                return@launch
            }
            loading?.let { container.removeView(it) }
            clearMessage()
            val sram = withContext(Dispatchers.IO) {
                saves.applyPendingImports()
                saves.readSram()
            }
            currentRom = rom
            markBackendTrial()
            startEmulator(modFile() ?: rom, sram)
            if (config.system == "psp") detectPspMultiplayer(rom)
            offerLiteMode { offerMoveGame() }
        }
    }

    private fun startEmulator(rom: File, sram: ByteArray?) {
        val data = GLRetroViewData(this).apply {
            coreFilePath = config.core
            gameFilePath = rom.absolutePath
            systemDirectory = saves.systemDir.absolutePath
            savesDirectory = saves.savesDir.absolutePath
            saveRAMState = sram
            shader = screenFilter().config
            preferLowLatencyAudio = lowLatencyAudio()
            rumbleEventsEnabled = false
            startedResolution = resolutionLevel()
        startedMultiplayer = multiplayerLevel()
        startedDolphin = dolphinLevel()
        startedPspGfx = pspGfxLevel()
        startedPspFs = pspFsLevel()
        startedN64 = n64Level()
        startedPspBackend = pspBackend()
            startedWiiController = wiiController()
            variables = coreVariables(config.system) +
                listOfNotNull(InternalResolution.levels(config.system).getOrNull(startedResolution)?.variable) + (if (config.system == "psp") PpssppMultiplayer.levels().getOrNull(startedMultiplayer)?.variables.orEmpty() else emptyList()) + (if (config.system == "psp") PpssppGraphics.levels().getOrNull(startedPspGfx)?.variables.orEmpty() else emptyList()) + (if (config.system == "n64") N64Options.levels().getOrNull(startedN64)?.variables.orEmpty() else emptyList()) + (if (config.system == "psp") PspFrameskip.levels().getOrNull(startedPspFs)?.variables.orEmpty() else emptyList()) + (if (config.system in listOf("wii", "gamecube", "gc")) dolphinClockVars() else emptyList()) + modTextureVariables() + pspBackendVariables() + (if (config.system == "psp" && prefs().getBoolean(PREF_PSP_CHEATS, false)) listOf(Variable("ppsspp_cheats", "enabled")) else emptyList()) + (if (config.system in listOf("wii", "gamecube", "gc")) DolphinOptimize.levels().getOrNull(startedDolphin)?.variables.orEmpty() else emptyList())
        }

        // Imagen invertida (3DS): el codigo nativo lee esta variable al crear el video.
        try {
            android.system.Os.setenv(
                "RAINEY_FLIP_Y",
                if (config.system == "3ds" && prefs().getBoolean(PREF_3DS_FLIP, false)) "1" else "0",
                true,
            )
        } catch (e: Exception) { }
        val view = GLRetroView(this, data)
        retroView = view
        gamepad = GamepadInput(view, config.system, startedWiiController)
        gamepad?.padMode = if (multiPadSystem()) padModeLevel() else 0
        lifecycle.addObserver(view)
        // Registered after the view's own observers, so on resume this runs after
        // LibretroDroid restarts emulation and can re-pause it if the menu is open.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && menu.isOpen) pauseEmulation()
        })
        val dsSplit = dsSplit()
        container.addView(
            view,
            if (dsSplit != null) FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dsSplit.first, Gravity.TOP)
            else FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        overlay = TouchOverlay(this, TouchOverlay.layoutFor(config.system, startedWiiController),
            onKey = { action, key ->
                view.sendKeyEvent(action, key, 0)
                if (config.system == "n64" && key == android.view.KeyEvent.KEYCODE_BUTTON_L2) {
                    view.sendKeyEvent(action, android.view.KeyEvent.KEYCODE_BUTTON_R2, 0)
                }
            },
            onAnalog = { right, x, y ->
                val source = if (right) GLRetroView.MOTION_SOURCE_ANALOG_RIGHT else GLRetroView.MOTION_SOURCE_ANALOG_LEFT
                view.sendMotionEvent(source, x, y, 0)
            },
            onMenu = { openMenu() },
        ).also {
            it.visibility = if (touchControlsEnabled()) View.VISIBLE else View.GONE
            it.onPickImage = { key ->
                skinKey = key
                pickSkin.launch(arrayOf("image/*"))
            }
            // DS en horizontal: el overlay cubre toda la pantalla; lo que no es un control va al juego (lapiz).
            if (isDualScreen() && dsLandscape()) it.onPass = { ev -> view.onTouchEvent(ev) }
            if (dsSplit != null) {
                // DS: el panel de controles ocupa solo la parte de abajo, asi los toques sobre las pantallas llegan al juego.
                it.setBackgroundColor(0xFF1E2026.toInt())
                container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dsSplit.second, Gravity.BOTTOM))
            } else {
                container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
        // FPS counter: top middle, just below the touch controls' menu button (which ends at 0.9 of
        // TouchOverlay's size unit, a tenth of the short side).
        fpsLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 14f
            visibility = if (fpsCounterEnabled()) View.VISIBLE else View.GONE
        }.also {
            val top = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 13 / 100
            container.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = top })
        }
        container.addView(menu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        lifecycleScope.launch {
            view.getGLRetroEvents().first { it is GLRetroEvents.FrameRendered }
            gameRunning = true
        }

        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                Log.e(TAG, "Emulator error $code")
                Toast.makeText(this@GameActivity, "Emulator error ($code)", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    fpsLabel?.takeIf { it.visibility == View.VISIBLE && gameRunning }?.let {
                        it.text = String.format(java.util.Locale.US, "%.1f FPS", view.getFps())
                    }
                    delay(1_000)
                }
            }
        }

        // Periodic SRAM flush so a crash or force-stop doesn't lose in-game saves.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SRAM_AUTOSAVE_MS)
                    if (!gameRunning || menu.isOpen) continue
                    val bytes = view.serializeSRAM()
                    withContext(Dispatchers.IO) { saves.writeSram(bytes) }
                }
            }
        }
    }

    /** Core options that differ from the core's defaults. */
    private fun coreVariables(system: String): Array<Variable> = when (system) {
        "gamecube" -> dolphinVariables()
        "wii" -> dolphinVariables() + arrayOf(
            Variable("dolphin_wiimote_default_device", startedWiiController.device),
            // The right stick moves the Wii pointer and it stays put when released (the default snaps
            // back to the centre), so you can aim, let go and press A with the same thumb.
            Variable("dolphin_ir_mode", "0"),
        )
        "ps2" -> arrayOf(
            // Vulkan: far lower per-draw CPU cost than GL ES on mobile GPUs (GL ES was driver-bound).
            Variable("pcsx2_renderer", "Vulkan"),
            // Mali has no dual-source blending, which PCSX2's Vulkan blending otherwise assumes;
            // maximum accuracy does those blends in the shader via framebuffer fetch instead.
            Variable("pcsx2_blending_accuracy", "Maximum"),
            // The emulated PS2 CPU (EE) is then the bottleneck; most games don't use its full budget,
            // so emulate fewer cycles (the usual Android speed hack).
            Variable("pcsx2_ee_cycle_rate", "60% (Underclock)"),
            Variable("pcsx2_ee_cycle_skip", "Mild Underclock"),
            Variable("pcsx2_ee_accurate_fpu", "disabled"),
        )
        // TGB Dual: emulate two Game Boys side by side, linked by cable (restart to apply).
        "gb2" -> arrayOf(Variable("tgbdual_gblink_enable", "enabled"))
        "3ds" -> azaharVariables()
        // melonDS: renderizador de software en su propio hilo y compilador JIT (nombres comprobados con
        // `strings`; los valores "enabled" siguen la convencion de libretro y no estan verificados).
        "nds" -> arrayOf(
            Variable("melonds_threaded_renderer", "enabled"),
            Variable("melonds_jit_enable", "enabled"),
            // Pantalla tactil: el modo "Touch" lee el puntero que manda el reproductor (el modo por
            // defecto lee un mouse y por eso el dedo no hacia nada). Nombre comprobado con `strings`.
            Variable("melonds_touch_mode", "touch"),
            // Nucleo nuevo: sin firmware original arranca en modo directo con el BIOS integrado.
            Variable("melonds_boot_mode", "direct"),
            Variable("melonds_sysfile_mode", "builtin"),
        ) + (if (prefs().getBoolean(PREF_DS_PERF, false)) arrayOf(
            // "Rendimiento alto": optimizaciones extra del JIT (se pueden quitar desde el menu).
            Variable("melonds_jit_fast_memory", "enabled"),
            Variable("melonds_jit_branch_optimisations", "enabled"),
            Variable("melonds_jit_literal_optimisations", "enabled"),
        ) else emptyArray<Variable>()) + dsSoundVars() + arrayOf(Variable("melonds_firmware_wfc_dns", dsWfcValue()))
        else -> emptyArray()
    }

    private fun dolphinVariables() = arrayOf(
        // Compile shaders in the background and skip drawing until they're ready, instead of
        // freezing the game each time a new effect appears (Dolphin's main source of stutter).
        // Dolphin logs every disc read at its default level; errors only.
        Variable("dolphin_log_level", "2"),
        // No "Video Info" banner and other emulator messages over the game.
        Variable("dolphin_osd_enabled", "disabled"),
    )

    override fun onPause() {
        // A normal pause means the chosen backend didn't crash the start.
        prefs().edit().putBoolean(PREF_BACKEND_TRIAL, false).apply()
        // The emulation thread is already paused at this point (lifecycle observers
        // see ON_PAUSE first), so read SRAM directly instead of queueing onto it.
        if (gameRunning) retroView?.let { saves.writeSram(it.serializeSRAM(false)) }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Systems without a bundled core (PS2, Dreamcast) open the game in an installed emulator app.
     * Single-file games are shared through this app's FileProvider, so the emulator needs no
     * permission of its own. Multi-file discs (.gdi/.cue) need their sibling tracks, which a
     * single shared file can't provide, so they're passed as a storage document URI the way
     * frontends like ES-DE do; the emulator must already have access to that folder.
     */
    private fun launchExternal() {
        val file = File(config.rom)
        if (!file.exists()) {
            showMessage("Game file not found:\n${config.rom}\n\nIt may have been moved, renamed or deleted, or its SD card removed.", "Quit") {
                finishAndRemoveTask()
            }
            return
        }
        val emulator = config.external.firstOrNull { isInstalled(it.packageName) }
        if (emulator == null) {
            val names = config.external.map { it.name }.distinct().joinToString(" or ")
            showMessage("${config.title}\n\nThis game needs $names installed to play.", "Quit") { finishAndRemoveTask() }
            return
        }
        val intent = Intent(emulator.action)
            .setComponent(ComponentName(emulator.packageName, emulator.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (emulator.passPath && emulator.extra != null) {
            intent.putExtra(emulator.extra, file.absolutePath)
        } else {
            val multiFile = file.extension.lowercase() in setOf("gdi", "cue", "m3u")
            val uri = if (multiFile) storageDocumentUri(file) else FileProvider.getUriForFile(this, "$packageName.files", file)
            if (!multiFile) {
                grantUriPermission(emulator.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (emulator.extra != null) {
                intent.putExtra(emulator.extra, uri.toString())
                intent.clipData = ClipData.newRawUri(file.name, uri) // carries the read grant with the extra
            } else {
                intent.data = uri
            }
        }
        try {
            startActivity(intent)
            finishAndRemoveTask()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start ${emulator.name}", e)
            showMessage("Couldn't open ${emulator.name}: ${e.message}", "Quit") { finishAndRemoveTask() }
        }
    }

    /** /storage/emulated/0/a/b.gdi -> content://com.android.externalstorage.documents/document/primary%3Aa%2Fb.gdi */
    private fun storageDocumentUri(file: File): Uri {
        val path = file.canonicalPath
        val internal = Environment.getExternalStorageDirectory().canonicalPath
        val docId = if (path.startsWith("$internal/")) {
            "primary:" + path.removePrefix("$internal/")
        } else {
            val parts = path.removePrefix("/storage/").split("/", limit = 2) // [volume id, relative path]
            parts[0] + ":" + parts.getOrElse(1) { "" }
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // --- menu ---------------------------------------------------------------

    private fun menuItems() = listOfNotNull(
        GameMenu.Item({ "Resume" }) { closeMenu() },
        GameMenu.Item({ "Edit controls" }, { touchControlsEnabled() }) {
            closeMenu()
            overlay?.beginEdit()
        },
        GameMenu.Item({ "Save state" }, { gameRunning }) { saveState() },
        GameMenu.Item({ "Load state" }, { gameRunning && saves.hasState() }) { loadState() },
        GameMenu.Item({ "Reset game" }, { gameRunning }) {
            retroView?.reset(false)
            closeMenu()
        },
        GameMenu.Item({ "Screen filter: " + screenFilter().label }) {
            val next = ScreenFilter.entries[(screenFilter().ordinal + 1) % ScreenFilter.entries.size]
            prefs().edit().putString(PREF_FILTER, next.name).apply()
            retroView?.shader = next.config
            menu.refresh()
        },
        GameMenu.Item(
            { "Resolution: " + resolutionLabel() },
            { InternalResolution.levels(config.system).isNotEmpty() },
        ) {
            val count = InternalResolution.levels(config.system).size
            prefs().edit().putInt(PREF_RESOLUTION, (resolutionLevel() + 1) % count).apply()
            menu.refresh()
        },
        GameMenu.Item({ "Asistente multijugador PSP (detectado: " + pspNetLabel() + ")" }) {
            closeMenu()
            pspWizard()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "Red PSP (modo): " + multiplayerLabel() }) {
            prefs().edit().putInt(PREF_MULTIPLAYER, (multiplayerLevel() + 1) % PpssppMultiplayer.levels().size).apply()
            menu.refresh()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "PSP graphics: " + (PpssppGraphics.levels().getOrNull(pspGfxLevel())?.label ?: "-") }) {
            prefs().edit().putInt(PREF_PSP_GFX, (pspGfxLevel() + 1) % PpssppGraphics.levels().size).apply()
            menu.refresh()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "PSP backend: " + pspBackendLabel() }) {
            prefs().edit().putInt(PREF_PSP_BACKEND, (pspBackend() + 1) % 3).apply()
            menu.refresh()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "Red PSP - MAC propia: " + (PpssppMultiplayer.mac ?: "-").chunked(2).joinToString(":") }) {
            PpssppMultiplayer.mac = newMac()
            Toast.makeText(this, "Nueva MAC. Reinicia el juego", Toast.LENGTH_LONG).show()
            menu.refresh()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "Red PSP - IP del anfitrion: " + (PpssppMultiplayer.customIp ?: "sin definir") }) {
            closeMenu()
            ipSavedCb = null
            askServerIp()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "DS rendimiento: " + (if (prefs().getBoolean(PREF_DS_PERF, false)) "alto" else "normal") }) {
            prefs().edit().putBoolean(PREF_DS_PERF, !prefs().getBoolean(PREF_DS_PERF, false)).commit()
            restartGame()
        }.takeIf { config.system == "nds" },
        GameMenu.Item({ "Servidor online DS: " + dsWfcLabel() }) {
            prefs().edit().putInt(PREF_DS_WFC, (dsWfcLevel() + 1) % 3).commit()
            restartGame()
        }.takeIf { config.system == "nds" },
        GameMenu.Item({ "Sonido DS: " + dsSoundLabel() }) {
            prefs().edit().putInt(PREF_DS_SOUND, (dsSoundLevel() + 1) % 3).commit()
            restartGame()
        }.takeIf { config.system == "nds" },
        GameMenu.Item({ "Modo estable (100% de velocidad)" }) {
            setStable()
            restartGame()
        }.takeIf { config.system in listOf("nds", "3ds", "wii", "gamecube", "gc") },
        GameMenu.Item({ "Pantallas DS: " + (if (dsLandscape()) "horizontal" else "vertical") }) {
            prefs().edit().putBoolean(PREF_DS_LAND, !dsLandscape()).commit()
            restartGame()
        }.takeIf { isDualScreen() },
        GameMenu.Item({ "Mi dispositivo" }) { showDeviceInfo() },
        GameMenu.Item({ "Modo ligero: " + (if (liteMode()) "si" else "no") }) {
            setLite(!liteMode())
            Toast.makeText(this, "Reinicia el juego para aplicar", Toast.LENGTH_LONG).show()
            menu.refresh()
        }.takeIf { config.system in listOf("psp", "wii", "gamecube", "gc", "3ds", "nds", "n64") },
        GameMenu.Item({ "Rendimiento sostenido: " + (if (sustained()) "si" else "no") }) {
            prefs().edit().putBoolean(PREF_SUSTAINED, !sustained()).commit()
            applySustained()
            menu.refresh()
        },
        GameMenu.Item({ "Controllers: " + listOf("1 player", "Pads = P1, P2...", "Touch = P1, pads = P2...")[padModeLevel()] }) {
            prefs().edit().putInt(PREF_PAD_MODE, (padModeLevel() + 1) % 3).apply()
            gamepad?.let { it.padMode = padModeLevel(); it.resetPorts() }
            menu.refresh()
        }.takeIf { multiPadSystem() },
        GameMenu.Item({ "3DS renderer: " + azaharApiLabel() }) {
            prefs().edit().putInt(PREF_3DS_API, (azaharApi() + 1) % 4).apply()
            menu.refresh()
            Toast.makeText(this, "Reinicia el juego para aplicar el renderizador", Toast.LENGTH_SHORT).show()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "Invertir imagen: " + (if (prefs().getBoolean(PREF_3DS_FLIP, false)) "si" else "no") }) {
            prefs().edit().putBoolean(PREF_3DS_FLIP, !prefs().getBoolean(PREF_3DS_FLIP, false)).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({
            "3DS resolucion: " + (if (azaharRes() == 0) "1x (nativa)" else "${azaharRes() + 1}x") +
                (if (prefs().getBoolean(PREF_3DS_PERF, false)) " (el modo alto fuerza 1x)" else "")
        }) {
            prefs().edit().putInt(PREF_3DS_RES, (azaharRes() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({
            "3DS texturas: " + listOf("normal", "ScaleForce (suave)", "xBRZ (pesado)", "Bicubic")[azaharTex()]
        }) {
            prefs().edit().putInt(PREF_3DS_TEX, (azaharTex() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS reloj CPU: " + listOf("100% (normal)", "80%", "70%", "60%")[azaharClock()] }) {
            prefs().edit().putInt(PREF_3DS_CLOCK, (azaharClock() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS rendimiento: " + (if (prefs().getBoolean(PREF_3DS_PERF, false)) "alto" else "normal") }) {
            prefs().edit().putBoolean(PREF_3DS_PERF, !prefs().getBoolean(PREF_3DS_PERF, false)).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "N64 optimizacion: " + (N64Options.levels().getOrNull(n64Level())?.label ?: "-") }) {
            prefs().edit().putInt(PREF_N64_OPT, (n64Level() + 1) % N64Options.levels().size).commit()
            menu.refresh()
        }.takeIf { config.system == "n64" },
        GameMenu.Item({ "PSP salto de cuadros: " + (PspFrameskip.levels().getOrNull(pspFsLevel())?.label ?: "-") }) {
            prefs().edit().putInt(PREF_PSP_FS, (pspFsLevel() + 1) % PspFrameskip.levels().size).commit()
            menu.refresh()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "PSP cheats internos: " + (if (prefs().getBoolean(PREF_PSP_CHEATS, false)) "si" else "no") }) {
            prefs().edit().putBoolean(PREF_PSP_CHEATS, !prefs().getBoolean(PREF_PSP_CHEATS, false)).commit()
            restartGame()
        }.takeIf { config.system == "psp" },
        GameMenu.Item({ "Reloj CPU: " + listOf("100% (normal)", "80%", "70%", "60%")[dolphinClock()] }) {
            prefs().edit().putInt(PREF_DOLPHIN_CLOCK, (dolphinClock() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system in listOf("wii", "gamecube", "gc") },
        GameMenu.Item({ "Optimize: " + dolphinLabel() }) {
            prefs().edit().putInt(PREF_DOLPHIN_OPT, (dolphinLevel() + 1) % 4).apply()
            menu.refresh()
        }.takeIf { config.system in listOf("wii", "gamecube", "gc") },
        GameMenu.Item({ "Wii controller: " + wiiController().label }) {
            val next = WiiController.entries[(wiiController().ordinal + 1) % WiiController.entries.size]
            prefs().edit().putString(PREF_WII_CONTROLLER, next.name).apply()
            menu.refresh()
        }.takeIf { config.system == "wii" },
        GameMenu.Item({ "Estilo de controles: " + (overlay?.controlStyleName() ?: "") }) {
            overlay?.let { it.setStyle((it.controlStyle + 1) % TouchOverlay.STYLE_COUNT) }
            menu.refresh()
        },
            GameMenu.Item({ when {
                resolutionLevel() != startedResolution -> "Restart game to apply resolution"
                multiplayerLevel() != startedMultiplayer -> "Restart game to apply multiplayer"
                dolphinLevel() != startedDolphin -> "Restart game to apply optimize"
                pspGfxLevel() != startedPspGfx -> "Restart game to apply graphics"
                pspFsLevel() != startedPspFs -> "Restart game to apply frameskip"
                n64Level() != startedN64 -> "Restart game to apply N64 optimizacion"
                pspBackend() != startedPspBackend -> "Restart game to apply backend"
                wiiController() != startedWiiController -> "Restart game to apply controller"
                else -> "Restart game"
            } }, { gameRunning }) { restartGame() },
        GameMenu.Item({ "FPS counter: " + if (fpsCounterEnabled()) "On" else "Off" }) {
            val enabled = !fpsCounterEnabled()
            prefs().edit().putBoolean(PREF_FPS, enabled).apply()
            fpsLabel?.visibility = if (enabled) View.VISIBLE else View.GONE
            menu.refresh()
        },
        GameMenu.Item({ "Touch controls: " + if (touchControlsEnabled()) "On" else "Off" }) {
            setTouchControls(!touchControlsEnabled())
            menu.refresh()
        },
        GameMenu.Item({ "Patch game (IPS/BPS/PPF)" }, { gameRunning && config.external.isEmpty() && currentRom != null }) {
            closeMenu()
            pickPatch.launch(arrayOf("*/*"))
        },
        GameMenu.Item({ "Import mod (zip)" }, { gameRunning && config.external.isEmpty() }) {
            closeMenu()
            pickModZip.launch(arrayOf("*/*"))
        },
        GameMenu.Item({ "Sonido de inicio: " + (if (AudioPack.hasStart(this)) "si (tocar para cambiar)" else "elegir un archivo") }) {
            closeMenu()
            pickStartSound.launch(arrayOf("audio/*"))
        },
        GameMenu.Item({ "Quitar sonido de inicio" }, { AudioPack.hasStart(this) }) {
            AudioPack.clearStart(this)
            menu.refresh()
        },
        GameMenu.Item({ "Import save" }, { gameRunning }) {
            closeMenu()
            pickSave.launch(arrayOf("*/*"))
        },
        GameMenu.Item({ "Load game / mod" }, { config.external.isEmpty() }) {
            closeMenu()
            pickMod.launch(arrayOf("*/*"))
        },
        GameMenu.Item({ "Back to original game" }, { modFile() != null }) { clearMod() },
        GameMenu.Item(
            { if (movedFile() != null) "Game is stored in the app" else "Move game into app (free space)" },
            { canMoveGame() },
        ) {
            closeMenu()
            moveGameIntoApp()
        }.takeIf { config.linked },
        GameMenu.Item({
            "Audio: " + listOf(
                if (isHeavySystem()) "auto (estable)" else "auto (baja latencia)",
                "baja latencia",
                "estable (menos cortes)",
            )[audioMode()]
        }) {
            prefs().edit().putInt(PREF_AUDIO_MODE, (audioMode() + 1) % 3).commit()
            restartGame()
        },
        GameMenu.Item({ "Quit" }) { finishAndRemoveTask() },
    )

    // --- graphics -------------------------------------------------------------

    /** DS en vertical: (alto del juego, alto del panel de controles); null para el resto de sistemas. */
    private fun dsSplit(): Pair<Int, Int>? {
        if (!isDualScreen() || dsLandscape()) return null
        val dm = resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels)
        val h = maxOf(dm.widthPixels, dm.heightPixels)
        // DS: dos pantallas de 256x192 (2:3). 3DS: 400x240 sobre 320x240 (5:6).
        val ratio = if (config.system == "3ds") 6f / 5f else 3f / 2f
        val gameH = minOf((w * ratio).toInt(), h * 62 / 100)
        return gameH to (h - gameH)
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // --- modo ligero ---------------------------------------------------------

    private fun liteMode() = prefs().getBoolean(PREF_LITE, false)

    private fun dolphinClock() = prefs().getInt(PREF_DOLPHIN_CLOCK, 0).coerceIn(0, 3)

    /** dolphin_cpu_clock_rate: null = el valor normal del nucleo (100%). Los valores siguen el estilo "80%" de Azahar. */
    private fun dolphinClockVars(): List<Variable> =
        listOfNotNull(listOf(null, "80%", "70%", "60%")[dolphinClock()]?.let { Variable("dolphin_cpu_clock_rate", it) })

    /** Muestra la GPU y su nivel (0 a 4) para ver que perfil le toca; se puede copiar y mandar. */
    private fun showDeviceInfo() {
        lifecycleScope.launch {
            val gpu = withContext(Dispatchers.Default) { DeviceProfile.gpuName() }
            val level = DeviceProfile.fullTier(gpu)
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "-"
            val ram = "%.1f".format(mi.totalMem / 1073741824.0)
            val weak = if (DeviceProfile.weakChip(gpu)) " (GPU muy floja)" else ""
            val text = "GPU: " + (gpu ?: "no se pudo leer") + "\n" +
                "Nivel: " + DeviceProfile.describe(gpu) + "\n" +
                "Modo ligero sugerido: " + (if (level in 0..1) "si" else "no") + weak + "\n" +
                "Equipo: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" +
                "Chip: " + soc + "\n" +
                "Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n" +
                "RAM: " + ram + " GB\n" +
                "Nucleos CPU: " + Runtime.getRuntime().availableProcessors() + "\n" +
                "Juego: " + config.system
            if (isFinishing || isDestroyed) return@launch
            android.app.AlertDialog.Builder(this@GameActivity)
                .setTitle("Mi dispositivo")
                .setMessage(text)
                .setPositiveButton("Copiar") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Mi dispositivo", text))
                    Toast.makeText(this@GameActivity, "Copiado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    /**
     * Ajustes por nivel de GPU (0 muy debil .. 4 tope), que se suman al modo ligero cuando se acepta el aviso:
     * rendimiento sostenido siempre; en 3DS, los niveles 0 y 1 usan el preset de rendimiento alto y el nivel 2 solo 1x.
     */
    private fun applyGpuTier(level: Int) {
        val e = prefs().edit().putBoolean(PREF_SUSTAINED, true)
        if (config.system == "3ds") e.putBoolean(PREF_3DS_PERF, level <= 1).putInt(PREF_3DS_RES, 0)
        e.commit()
        applySustained()
    }

    private fun sustained() = prefs().getBoolean(PREF_SUSTAINED, false)

    /** Modo de rendimiento sostenido de Android (evita que el celular se frene al calentarse). */
    private fun applySustained() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (android.os.Build.VERSION.SDK_INT >= 24 && pm.isSustainedPerformanceModeSupported) {
                window.setSustainedPerformanceMode(sustained())
            }
        } catch (_: Throwable) {
        }
    }

    private fun audioMode() = prefs().getInt(PREF_AUDIO_MODE, 0).coerceIn(0, 2)

    /** Sistemas que en un celular de gama media/baja suelen ir por debajo de la velocidad real. */
    private fun isHeavySystem() = config.system in listOf("psp", "3ds", "nds", "wii", "gamecube", "gc", "n64", "ps2", "dreamcast")

    /** Baja latencia = buffer chico (4 cuadros): responde antes pero corta el sonido si el juego se atrasa. */
    private fun lowLatencyAudio(): Boolean = when (audioMode()) {
        1 -> true
        2 -> false
        else -> !isHeavySystem()
    }

    private fun dsLandscape() = prefs().getBoolean(PREF_DS_LAND, false)

    /** Sonido DS: 0 Estable (None, 10-bit: lo mas liviano, suena seco), 1 Equilibrado (Cosine, 16-bit), 2 Calidad (Cubic, 16-bit). */
    private fun dsSoundLevel() = prefs().getInt(PREF_DS_SOUND, 1).coerceIn(0, 2)

    /** Servidor online de DS: 0 Kaeru WFC (por defecto del nucleo), 1 Wiimmfi (el mas usado en Mario Kart DS), 2 AltWFC. Todos los jugadores deben usar el mismo. */
    private fun dsWfcLevel() = prefs().getInt(PREF_DS_WFC, 0).coerceIn(0, 2)

    private fun dsWfcLabel() = listOf("Kaeru WFC", "Wiimmfi", "AltWFC")[dsWfcLevel()]

    private fun dsWfcValue() = listOf("Kaeru WFC (178.62.43.212)", "Wiimmfi (95.217.77.181)", "AltWFC (172.104.88.237)")[dsWfcLevel()]

    private fun dsSoundLabel() = listOf("Estable", "Equilibrado", "Calidad")[dsSoundLevel()]

    private fun dsSoundVars(): Array<Variable> = when (dsSoundLevel()) {
        0 -> arrayOf(Variable("melonds_audio_interpolation", "None"), Variable("melonds_audio_bitdepth", "10-bit"))
        2 -> arrayOf(Variable("melonds_audio_interpolation", "cubic"), Variable("melonds_audio_bitdepth", "16-bit"))
        else -> arrayOf(Variable("melonds_audio_interpolation", "cosine"), Variable("melonds_audio_bitdepth", "16-bit"))
    }

    /**
     * Modo estable para las consolas pesadas (DS, 3DS, Wii, GameCube): velocidad al 100% (sin bajar el reloj),
     * resolucion 1x, rendimiento sostenido, audio con buffer grande y los presets de rendimiento ya probados.
     */
    private fun setStable() {
        val e = prefs().edit().putBoolean(PREF_SUSTAINED, true).putInt(PREF_AUDIO_MODE, 2)
        when (config.system) {
            "nds" -> e.putBoolean(PREF_DS_PERF, true).putInt(PREF_DS_SOUND, 1)
            "3ds" -> e.putBoolean(PREF_3DS_PERF, true).putInt(PREF_3DS_RES, 0).putInt(PREF_3DS_CLOCK, 0)
            else -> e.putInt(PREF_DOLPHIN_OPT, 0).putInt(PREF_RESOLUTION, 0).putInt(PREF_DOLPHIN_CLOCK, 0)
        }
        e.commit()
        applySustained()
    }

    /** DS y 3DS: dos pantallas apiladas, la de abajo es tactil. */
    private fun isDualScreen() = config.system == "nds" || config.system == "3ds"

    /** 0 = Auto, 1 = OpenGL, 2 = Vulkan, 3 = Software (citra_graphics_api). */
    private fun azaharApi() = prefs().getInt(PREF_3DS_API, 0).coerceIn(0, 3)

    private fun azaharApiLabel() = when (azaharApi()) {
        1 -> "OpenGL"
        2 -> "Vulkan"
        3 -> "Software"
        else -> "Auto"
    }

    private fun azaharVariables(): Array<Variable> = azaharExtra() + azaharApiVars()

    private fun azaharApiVars(): Array<Variable> = when (azaharApi()) {
        1 -> arrayOf(Variable("citra_graphics_api", "OpenGL"))
        2 -> arrayOf(Variable("citra_graphics_api", "Vulkan"))
        3 -> arrayOf(Variable("citra_graphics_api", "Software"))
        else -> emptyArray()
    }

    private fun azaharTex() = prefs().getInt(PREF_3DS_TEX, 0).coerceIn(0, 3)

    /** Valor de citra_texture_filter (null = no tocar la opcion). */
    private fun azaharTexLabel(): String? = when (azaharTex()) {
        1 -> "ScaleForce"
        2 -> "xBRZ"
        3 -> "Bicubic"
        else -> null
    }

    private fun azaharRes() = prefs().getInt(PREF_3DS_RES, 0).coerceIn(0, 3)

    private fun azaharClock() = prefs().getInt(PREF_3DS_CLOCK, 0).coerceIn(0, 3)

    /** null = el valor normal del nucleo (100%). */
    private fun azaharClockValue(): String? = listOf(null, "80%", "70%", "60%")[azaharClock()]

    private fun azaharResLabel() = if (azaharRes() == 0) "1x (Native)" else "${azaharRes() + 1}x"

    /**
     * Diseno de pantallas (lado a lado en horizontal) y, si se pidio, el preset de rendimiento.
     * Los valores del diseno y la resolucion salen del core de Citra (`strings`); en Azahar pueden
     * variar: si el core no reconoce un valor, lo ignora y queda el predeterminado.
     */
    private fun azaharExtra(): Array<Variable> {
        val v = mutableListOf(
            Variable("citra_layout_option", if (dsLandscape()) "Side by Side" else "Default Top-Bottom Screen"),
        )
        v += Variable("citra_use_disk_shader_cache", "enabled")
        azaharTexLabel()?.let { v += Variable("citra_texture_filter", it) }
        v += Variable("citra_audio_emulation", "hle")
        val perf = prefs().getBoolean(PREF_3DS_PERF, false)
        v += Variable("citra_resolution_factor", if (perf) "1x (Native)" else azaharResLabel())
        azaharClockValue()?.let { v += Variable("citra_cpu_clock_percentage", it) }
        if (perf) {
            v += Variable("citra_use_cpu_jit", "enabled")
            v += Variable("citra_use_shader_jit", "enabled")
            v += Variable("citra_shaders_accurate_mul", "disabled")
        }
        return v.toTypedArray()
    }

    /** Con un renderizador distinto de Auto: si la app muere antes de 15 s, el proximo arranque vuelve a Auto. */
    private fun markAzaharTrial() {
        if (config.system != "3ds" || azaharApi() == 0) return
        prefs().edit().putBoolean(PREF_3DS_TRIAL, true).commit()
        container.postDelayed({ prefs().edit().putBoolean(PREF_3DS_TRIAL, false).apply() }, 15_000L)
    }

    private fun revertFailedAzaharTrial() {
        if (!prefs().getBoolean(PREF_3DS_TRIAL, false)) return
        prefs().edit().putBoolean(PREF_3DS_TRIAL, false).putInt(PREF_3DS_API, 0).commit()
        Toast.makeText(this, "El renderizador elegido no arranco. Volvi a Auto.", Toast.LENGTH_LONG).show()
    }

    /** Modo ligero = presets de rendimiento ya existentes: PSP "Rendimiento" o Dolphin "Rendimiento", resolucion 1x. */
    private fun setLite(on: Boolean) {
        val e = prefs().edit().putBoolean(PREF_LITE, on)
        if (config.system == "psp") e.putInt(PREF_PSP_GFX, if (on) 1 else 0)
        else if (config.system == "3ds") e.putBoolean(PREF_3DS_PERF, on).putInt(PREF_3DS_RES, 0).putInt(PREF_3DS_CLOCK, if (on) 1 else 0)
        else if (config.system == "nds") e.putBoolean(PREF_DS_PERF, on)
        else if (config.system == "n64") e.putInt(PREF_N64_OPT, if (on) 1 else 0)
        else e.putInt(PREF_DOLPHIN_OPT, if (on) 0 else 1)
        if (on) e.putInt(PREF_RESOLUTION, 0)
        e.putInt(PREF_AUDIO_MODE, if (on) 2 else 0)
        e.commit()
    }

    /** Primera vez: si la GPU es floja, ofrece el modo ligero. PSP solo en GPU basica; GameCube/Wii tambien en media. */
    /** Evita ofrecer "Liberar espacio" justo antes de reiniciar por haber activado el modo ligero. */
    private var liteRestarting = false

    private fun offerLiteMode(next: () -> Unit) {
        if (config.system !in listOf("psp", "wii", "gamecube", "gc", "3ds", "nds", "n64")) { next(); return }
        if (prefs().getBoolean(PREF_LITE_ASKED, false)) { next(); return }
        lifecycleScope.launch {
            val gpu = withContext(Dispatchers.Default) { DeviceProfile.gpuName() }
            val tier = DeviceProfile.tier(gpu)
            val heavy = config.system != "psp"
            if (tier > 1 || (tier == 1 && !heavy)) {
                prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply()
                next()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            showLiteDialog(gpu, tier, next)
        }
    }

    private fun showLiteDialog(gpu: String?, tier: Int, next: () -> Unit) {
        val gpuText = if (gpu.isNullOrBlank()) "tu GPU" else "$gpu (${DeviceProfile.describe(gpu)})"
        val details = if (config.system == "n64") {
            "- N64 optimizacion: Rendimiento (hilo de render, sin copias a RDRAM, sin suavizados)\n" +
                "- Rendimiento sostenido y audio estable\n\n"
        } else if (config.system == "nds") {
            "- Rendimiento alto de DS (audio sin interpolacion, 10 bits)\n" +
                "- Audio estable (buffer mas grande, menos cortes)\n" +
                "- Rendimiento sostenido (el celular no baja la velocidad al calentarse)\n\n"
        } else if (config.system == "3ds") {
            "- Resolucion interna 1x (nativa)\n" +
                "- Rendimiento alto (menos precision en el GPU, puede haber pequenos fallos graficos)\n" +
                "- La 3DS es muy exigente: aun asi puede no ir fluido en juegos pesados\n\n"
        } else if (config.system == "psp") {
            "- Graficos PSP: Rendimiento (salto de cuadros automatico, sin lecturas de GPU)\n" +
                "- Resolucion interna 1x (480x272)\n\n"
        } else {
            "- Resolucion interna 1x\n" +
                "- Optimizacion Dolphin: Rendimiento (shaders asincronos, fastmem, hilo de CPU, " +
                "profundidad y texturas rapidas, disco rapido, EFB a textura)\n\n"
        } + "Todo se puede cambiar despues en el menu."
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Modo ligero")
            .setMessage("Detectamos $gpuText. Tu dispositivo es mas debil. Activar el modo ligero para que el juego vaya mas fluido? Puedes cambiarlo despues en el menu.")
            .setPositiveButton("Activar") { _, _ ->
                setLite(true)
                prefs().edit().putBoolean(PREF_LITE_ASKED, true).commit()
                applyGpuTier(DeviceProfile.fullTier(gpu))
                liteRestarting = true
                restartGame()
            }
            .setNegativeButton("Ahora no") { _, _ -> prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply() }
            .setNeutralButton("Ver que cambia", null)
            .setOnCancelListener { prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply() }
            .setOnDismissListener { if (!liteRestarting && !isFinishing && !isDestroyed) next() }
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.setMessage(details)
            it.visibility = View.GONE
        }
    }

    /** Sistemas con juegos de varios jugadores en el mismo equipo (cada mando Bluetooth toma el siguiente puerto). DS, 3DS y PSP son de un solo jugador. */
    private fun multiPadSystem() = config.system in listOf("n64", "gb2", "wii", "gamecube", "gc", "ps2", "psx", "dreamcast", "snes", "nes", "genesis", "pce")

    private fun padModeLevel() = prefs().getInt(PREF_PAD_MODE, 0).coerceIn(0, 2)

    private fun pspGfxLevel() =
        prefs().getInt(PREF_PSP_GFX, 0).coerceIn(0, PpssppGraphics.levels().size - 1)

    private fun pspFsLevel() =
        prefs().getInt(PREF_PSP_FS, 0).coerceIn(0, PspFrameskip.levels().size - 1)

    private fun n64Level() =
        prefs().getInt(PREF_N64_OPT, 0).coerceIn(0, N64Options.levels().size - 1)

    // --- mods and save import ----------------------------------------------------

    private val modExtensions = setOf(
        "iso", "cso", "pbp", "chd", "z64", "n64", "v64", "gcm", "rvz", "wbfs", "ciso", "gcz", "wad", "nfs",
    )

    /** The mod/replacement game file, when the person loaded one. */
    private fun modFile(): File? =
        prefs().getString(PREF_MOD_PATH, null)?.let { File(it) }?.takeIf { it.exists() }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "mod.bin"

    private fun importMod(uri: Uri) {
        val name = displayName(uri).replace(Regex("[^A-Za-z0-9._ -]"), "_")
        if (name.substringAfterLast('.', "").lowercase() !in modExtensions) {
            Toast.makeText(this, "Formato no soportado: $name", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Copiando $name... puede tardar", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "mod").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val tmp = File(dir, "$name.tmp")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
                    }
                    val target = File(dir, name)
                    tmp.renameTo(target)
                    prefs().edit().putString(PREF_MOD_PATH, target.absolutePath).commit()
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import mod", e)
                    e.message ?: "error"
                }
            }
            if (error == null) {
                Toast.makeText(this@GameActivity, "Listo: $name", Toast.LENGTH_SHORT).show()
                restartGame()
            } else {
                Toast.makeText(this@GameActivity, "No se pudo copiar: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearMod() {
        File(filesDir, "mod").listFiles()?.forEach { it.delete() }
        prefs().edit().remove(PREF_MOD_PATH).commit()
        restartGame()
    }

    /** The copy of a linked game that was moved into app storage; only valid for the same source path. */
    private fun movedFile(): File? {
        if (!config.linked) return null
        if (prefs().getString(PREF_MOVED_FROM, null) != config.rom) return null
        return prefs().getString(PREF_MOVED_PATH, null)?.let { File(it) }?.takeIf { it.exists() }
    }

    private fun canMoveGame() =
        config.linked && config.files.size <= 1 && movedFile() == null && File(config.rom).exists()

    /**
     * Copies the linked game into app storage, then restarts. The original is deleted at the
     * next start (prepareAndStart), once the core no longer has it open.
     */
    private fun moveGameIntoApp() {
        val src = File(config.rom)
        val free = StatFs(filesDir.absolutePath).availableBytes
        if (free < src.length() + (50L shl 20)) {
            Toast.makeText(this, "No hay espacio libre suficiente para mover el juego", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Moviendo el juego... no cierres la app", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "game").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val tmp = File(dir, src.name + ".tmp")
                    src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    if (tmp.length() != src.length()) {
                        tmp.delete()
                        "la copia quedo incompleta"
                    } else {
                        val target = File(dir, src.name)
                        tmp.renameTo(target)
                        prefs().edit()
                            .putString(PREF_MOVED_PATH, target.absolutePath)
                            .putString(PREF_MOVED_FROM, config.rom)
                            .commit()
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to move game", e)
                    e.message ?: "error"
                }
            }
            if (error == null) {
                Toast.makeText(this@GameActivity, "Listo. Reiniciando para borrar el original", Toast.LENGTH_LONG).show()
                restartGame()
            } else {
                Toast.makeText(this@GameActivity, "No se pudo mover: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** First launch of a linked game: offer to keep a single copy inside the app. Asked once per source file. */
    private fun offerMoveGame() {
        if (isFinishing || isDestroyed) return
        if (!canMoveGame()) return
        if (prefs().getString(PREF_ASKED_MOVE, null) == config.rom) return
        prefs().edit().putString(PREF_ASKED_MOVE, config.rom).apply()
        val mb = File(config.rom).length() shr 20
        android.app.AlertDialog.Builder(this)
            .setTitle("Liberar espacio")
            .setMessage(
                "Mover el juego dentro de la app y borrar el ISO original? Queda una sola copia ($mb MB). " +
                    "Si desinstalas la app, el juego se borra con ella. Tambien podes hacerlo despues desde el menu.",
            )
            .setPositiveButton("Mover y borrar ISO") { _, _ -> moveGameIntoApp() }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /** Core options that make a core use an imported mod (texture packs, cheats). */
    private fun modTextureVariables(): List<Variable> {
        if (!prefs().getBoolean(PREF_MOD_TEX, false)) return emptyList()
        return when (config.system) {
            "psp" -> listOf(
                Variable("ppsspp_texture_replacement", "enabled"),
                Variable("ppsspp_cheats", "enabled"),
            )
            "n64" -> listOf(
                Variable("mupen64plus-txHiresEnable", "True"),
                Variable("mupen64plus-txHiresFullAlphaChannel", "True"),
            )
            "gamecube", "wii", "gc" -> listOf(
                Variable("dolphin_load_custom_textures", "enabled"),
                Variable("dolphin_cheats_enabled", "enabled"),
            )
            else -> emptyList()
        }
    }

    private fun importModZip(uri: Uri) {
        val name = displayName(uri)
        Toast.makeText(this, "Importando $name... puede tardar", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val key = (modFile() ?: currentRom)?.let { GameId.detect(it, config.system) }
                    val msg = contentResolver.openInputStream(uri)!!.use { saves.importMod(name, it, config.system, key) }
                    if (key != null) "$msg (juego: $key)" else "$msg (no pude leer el ID del juego)"
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import mod zip", e)
                    "Error: ${e.message}"
                }
            }
            Toast.makeText(this@GameActivity, result, Toast.LENGTH_LONG).show()
            if (result.startsWith("OK")) {
                prefs().edit().putBoolean(PREF_MOD_TEX, true).commit()
                restartGame()
            }
        }
    }

    /** Applies an IPS/BPS/PPF patch to the original game file and plays the result. */
    private fun applyPatch(uri: Uri) {
        val base = currentRom
        if (base == null || !base.exists()) {
            Toast.makeText(this, "No encuentro el juego base para parchear", Toast.LENGTH_LONG).show()
            return
        }
        val free = StatFs(filesDir.absolutePath).availableBytes
        if (free < base.length() + (50L shl 20)) {
            Toast.makeText(this, "No hay espacio libre suficiente para el juego parcheado", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Aplicando ${displayName(uri)}... puede tardar", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "mod").apply { mkdirs() }
                val tmp = File(dir, "patch.tmp")
                try {
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size > (200L shl 20)) return@withContext "El parche es demasiado grande"
                    val patch = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val res = RomPatcher.apply(base, patch, tmp)
                    if (res != "OK") {
                        tmp.delete()
                        return@withContext res
                    }
                    dir.listFiles()?.forEach { if (it != tmp) it.delete() }
                    val ext = base.extension
                    val target = File(dir, base.nameWithoutExtension + " (patched)" + if (ext.isEmpty()) "" else ".$ext")
                    tmp.renameTo(target)
                    prefs().edit().putString(PREF_MOD_PATH, target.absolutePath).commit()
                    "OK"
                } catch (e: Exception) {
                    tmp.delete()
                    Log.e(TAG, "Failed to apply patch", e)
                    "Error: ${e.message}"
                }
            }
            if (result == "OK") {
                Toast.makeText(this@GameActivity, "Parche aplicado. Reiniciando...", Toast.LENGTH_LONG).show()
                restartGame()
            } else {
                Toast.makeText(this@GameActivity, result, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 0 = core default, 1 = OpenGL, 2 = Vulkan. */
    private fun pspBackend() = prefs().getInt(PREF_PSP_BACKEND, 0).coerceIn(0, 2)

    private fun pspBackendLabel() = when (pspBackend()) {
        1 -> "OpenGL"
        2 -> "Vulkan"
        else -> "Auto"
    }

    private fun pspBackendVariables(): List<Variable> {
        if (config.system != "psp") return emptyList()
        return when (startedPspBackend) {
            1 -> listOf(Variable("ppsspp_backend", "opengl"))
            2 -> listOf(Variable("ppsspp_backend", "vulkan"))
            else -> emptyList()
        }
    }

    /** While a non-default backend is starting, remember it; if the app dies first, undo it next launch. */
    private fun markBackendTrial() {
        if (config.system != "psp" || pspBackend() == 0) return
        prefs().edit().putBoolean(PREF_BACKEND_TRIAL, true).commit()
        container.postDelayed({ prefs().edit().putBoolean(PREF_BACKEND_TRIAL, false).apply() }, 20_000L)
    }

    private fun revertFailedBackendTrial() {
        if (!prefs().getBoolean(PREF_BACKEND_TRIAL, false)) return
        prefs().edit().putBoolean(PREF_BACKEND_TRIAL, false).putInt(PREF_PSP_BACKEND, 0).commit()
        Toast.makeText(this, "El motor grafico elegido no arranco. Volvi a Auto.", Toast.LENGTH_LONG).show()
    }

    private var ipSavedCb: (() -> Unit)? = null

    private fun pspNetLabel(): String = when (prefs().getString(PREF_NET_SCAN, null)) {
        PspNet.YES -> "si, tiene multijugador"
        PspNet.MAYBE -> "posible"
        PspNet.NO -> "no detectado"
        PspNet.UNKNOWN -> "no se pudo analizar"
        else -> "analizando..."
    }

    /** Analiza el ISO una sola vez (en segundo plano) y avisa si parece tener multijugador. */
    private fun detectPspMultiplayer(rom: File) {
        if (prefs().getString(PREF_NET_SCAN, null) != null) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { PspNet.scan(rom) }
            prefs().edit().putString(PREF_NET_SCAN, r).apply()
            menu.refresh()
            when (r) {
                PspNet.YES -> Toast.makeText(this@GameActivity, "Este juego tiene multijugador. Menu > Asistente multijugador PSP", Toast.LENGTH_LONG).show()
                PspNet.MAYBE -> Toast.makeText(this@GameActivity, "Este juego podria tener multijugador. Menu > Asistente multijugador PSP", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyNet(level: Int) {
        prefs().edit().putInt(PREF_MULTIPLAYER, level).commit()
        restartGame()
    }

    /** Asistente: deja la red lista como en el PPSSPP (modo, IP, MAC propia) y reinicia el juego. */
    private fun pspWizard() {
        val opts = arrayOf(
            "Servidor publico (todos eligen esto)",
            "Ser anfitrion en mi red Wi-Fi",
            "Ser anfitrion con ZeroTier (escribo mi IP)",
            "Unirme a un anfitrion (escribo su IP)",
            "Apagar la red",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Multijugador PSP")
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> applyNet(2)
                    1 -> applyNet(3)
                    2 -> { ipSavedCb = { applyNet(6) }; askServerIp() }
                    3 -> { ipSavedCb = { applyNet(5) }; askServerIp() }
                    else -> applyNet(0)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askServerIp() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "192.168.1.5"
            setText(PpssppMultiplayer.customIp ?: "")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("IP del anfitrion")
            .setMessage("IP del telefono que hace de servidor (el que usa Multiplayer: Localhost). Vacio para quitarla.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val text = input.text.toString().trim()
                val octets = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})").matchEntire(text)
                    ?.groupValues?.drop(1)?.map { it.toInt() }
                if (text.isNotEmpty() && (octets == null || octets.any { it > 255 })) {
                    Toast.makeText(this, "IP no valida", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                PpssppMultiplayer.customIp = text.ifEmpty { null }
                prefs().edit().putString(PREF_MP_IP, text.ifEmpty { null }).apply()
                menu.refresh()
                if (text.isNotEmpty() && ipSavedCb != null) {
                    val cb = ipSavedCb
                    ipSavedCb = null
                    cb?.invoke()
                    return@setPositiveButton
                }
                if (text.isNotEmpty()) {
                    Toast.makeText(this, "Ahora elegi Multiplayer: IP propia y reinicia", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun importSave(uri: Uri) {
        val name = displayName(uri)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)!!.use { saves.importFile(name, it, config.system) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import save", e)
                    "Error: ${e.message}"
                }
            }
            Toast.makeText(this@GameActivity, result, Toast.LENGTH_LONG).show()
            if (result.startsWith("OK")) restartGame()
        }
    }

    private fun fpsCounterEnabled() = prefs().getBoolean(PREF_FPS, false)

    private fun screenFilter(): ScreenFilter =
        prefs().getString(PREF_FILTER, null)?.let { name -> ScreenFilter.entries.firstOrNull { it.name == name } } ?: (if (config.system == "3ds") ScreenFilter.UPSCALE else ScreenFilter.SHARP)

    private fun wiiController(): WiiController =
        prefs().getString(PREF_WII_CONTROLLER, null)?.let { name -> WiiController.entries.firstOrNull { it.name == name } }
            ?: WiiController.NUNCHUK

    /** A fresh MAC (02 + 10 random digits), saved so this install keeps it. */
    private fun newMac(): String {
        val m = "02" + (1..10).joinToString("") { kotlin.random.Random.nextInt(10).toString() }
        prefs().edit().putString(PREF_MP_MAC, m).apply()
        return m
    }

    private fun multiplayerLevel(): Int {
        return prefs().getInt(PREF_MULTIPLAYER, 0).coerceIn(0, PpssppMultiplayer.levels().size - 1)
    }
    private fun multiplayerLabel(): String {
        return PpssppMultiplayer.levels()[multiplayerLevel()].label
    }
    private fun dolphinLevel(): Int {
        return prefs().getInt(PREF_DOLPHIN_OPT, 1).coerceIn(0, 3)
    }
    private fun dolphinLabel(): String {
        return listOf("Rendimiento", "Equilibrado", "Calidad", "Maximo (GPU fuerte)")[dolphinLevel()]
    }
    private fun resolutionLevel(): Int {
        val count = InternalResolution.levels(config.system).size
        return prefs().getInt(PREF_RESOLUTION, 0).coerceIn(0, maxOf(count - 1, 0))
    }

    private fun resolutionLabel(): String {
        val levels = InternalResolution.levels(config.system)
        if (levels.isEmpty()) return "not available"
        val label = levels[resolutionLevel()].label
        return if (resolutionLevel() > 0) "$label (may slow down)" else label
    }

    /**
     * Cores read resolution options only at startup, and some (Vulkan PCSX2) can't be reloaded in
     * the same process, so restart the app in a fresh process after saving in-game progress.
     */
    private fun restartGame() {
        if (gameRunning) retroView?.let { saves.writeSram(it.serializeSRAM(false)) }
        startActivity(
            Intent(this, GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun openMenu() {
        if (menu.isOpen || retroView == null) return
        overlay?.releaseAll()
        heldCombo.clear()
        pauseEmulation()
        menu.open()
    }

    private fun closeMenu() {
        if (!menu.isOpen) return
        menu.close()
        resumeEmulation()
    }

    private fun pauseEmulation() {
        retroView?.apply {
            audioEnabled = false
            onPause()
        }
    }

    private fun resumeEmulation() {
        retroView?.apply {
            onResume()
            audioEnabled = true
        }
    }

    // While the menu is open the GL thread is paused, so states are read and
    // written directly rather than queued onto the emulation thread.
    private fun saveState() {
        val view = retroView ?: return
        val ok = runCatching { saves.writeState(view.serializeState(false)) }.getOrDefault(false)
        toast(if (ok) "State saved" else "Couldn't save state")
        closeMenu()
    }

    private fun loadState() {
        val view = retroView ?: return
        val data = saves.readState()
        val ok = data != null && runCatching { view.unserializeState(data, false) }.getOrDefault(false)
        toast(if (ok) "State loaded" else "Couldn't load state")
        closeMenu()
    }

    /**
     * Many screens switch between 60, 90 and 120 Hz. Games are made for 60 (or 50), and the emulator
     * paces frames by the refresh rate it sees at startup, so ask for the 60 Hz mode while playing:
     * steady pacing, no speed-up when the screen changes mode, and less battery.
     */
    @Suppress("DEPRECATION")
    private fun preferSixtyHz() {
        val display = windowManager.defaultDisplay
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    // --- touch controls -----------------------------------------------------

    private fun touchControlsEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return if (prefs.contains(PREF_TOUCH)) prefs.getBoolean(PREF_TOUCH, true) else !hasPhysicalGamepad()
    }

    private fun setTouchControls(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_TOUCH, enabled).apply()
        overlay?.releaseAll()
        overlay?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun hasPhysicalGamepad() = InputDevice.getDeviceIds().any { id ->
        val sources = InputDevice.getDevice(id)?.sources ?: 0
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    // --- input --------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (message != null) return super.dispatchKeyEvent(translateForUi(event))
        if (menu.isOpen) return handleMenuKey(event)

        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (event.action == KeyEvent.ACTION_DOWN) heldCombo += code else heldCombo -= code
            if (heldCombo.size == 2) {
                retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, 0)
                retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, 0)
                openMenu()
                return true
            }
        }
        if (gamepad?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleMenuKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) menu.clickFocused()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_UP) closeMenu()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Lets gamepad A press the focused on-screen button outside the game. */
    private fun translateForUi(event: KeyEvent): KeyEvent =
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) KeyEvent(event.action, KeyEvent.KEYCODE_DPAD_CENTER) else event

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // With the menu open, let Android turn stick movement into d-pad focus moves.
        if (!menu.isOpen && gamepad?.onMotionEvent(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // --- screens shown instead of the game ----------------------------------

    /** Shown on first launch while large games are copied out of the APK. */
    private fun showLoading(): TextView {
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            text = loadingText(0)
        }
        container.addView(
            text,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        return text
    }

    private fun loadingText(percent: Int) = "${config.title}\n\nPreparing game for first launch… $percent%"

    private fun showMessage(text: String, buttonLabel: String, onClick: () -> Unit) {
        clearMessage()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                this.text = text
            })
            addView(Button(context).apply {
                this.text = buttonLabel
                isAllCaps = false
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 48
            })
        }
        container.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        message = box
        box.getChildAt(1).requestFocus()
    }

    private fun clearMessage() {
        message?.let { container.removeView(it) }
        message = null
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}