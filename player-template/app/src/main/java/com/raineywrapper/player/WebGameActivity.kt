package com.raineywrapper.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Juegos HTML5 (RPG Maker MV / MZ): se sirven desde la carpeta del juego a un WebView, con los controles
 * en pantalla convertidos en teclas. Las partidas se guardan en el localStorage del WebView.
 */
class WebGameActivity : ComponentActivity() {
    private lateinit var container: FrameLayout
    private lateinit var config: GameConfig
    private var web: WebView? = null
    private var overlay: TouchOverlay? = null
    private var root: File? = null
    private var indexName = "index.html"
    private var dialog: AlertDialog? = null
    private val prefs get() = getSharedPreferences("web_game", MODE_PRIVATE)

    private fun isMoved() = prefs.getString(KEY_MOVED, null)?.let { File(it, indexName).isFile } == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)
        enterImmersiveMode()
        config = GameConfig.load(this)
        val index = File(config.rom)
        indexName = index.name
        val moved = prefs.getString(KEY_MOVED, null)?.let { File(it) }?.takeIf { File(it, indexName).isFile }
        root = moved ?: index.parentFile
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    override fun onResume() {
        super.onResume()
        web?.let { it.onResume(); return }
        if (dialog?.isShowing == true) return
        if (!isMoved() && !Environment.isExternalStorageManager()) {
            dialog = AlertDialog.Builder(this)
                .setTitle(config.title)
                .setMessage("Este juego se lee desde la memoria del telefono, asi que necesita permiso para acceder a los archivos.")
                .setCancelable(false)
                .setPositiveButton("Permitir acceso") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        if (root?.let { File(it, indexName).isFile } != true) {
            dialog = AlertDialog.Builder(this)
                .setTitle(config.title)
                .setMessage("No se encuentra el juego:\n${config.rom}\n\nSi lo moviste o lo borraste, vuelve a convertirlo.")
                .setCancelable(false)
                .setPositiveButton("Salir") { _, _ -> finishAffinity() }
                .show()
            return
        }
        startWeb()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        container.removeAllViews()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb() {
        if (usesBrotli()) {
            dialog = AlertDialog.Builder(this)
                .setTitle(config.title)
                .setMessage(
                    "Este build de Unity usa compresion Brotli (.br), que todavia no es compatible.\n\n" +
                        "Pedile al desarrollador un build con compresion Gzip o sin compresion.",
                )
                .setCancelable(false)
                .setPositiveButton("Salir") { _, _ -> finishAffinity() }
                .show()
            return
        }
        val view = WebView(this)
        web = view
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url)
        }
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        overlay = TouchOverlay(
            this, TouchOverlay.layoutFor("rpgmv"),
            onKey = { action, key -> sendKey(action, key) },
            onAnalog = { _, _, _ -> },
            onMenu = { showMenu() },
        ).also {
            // Lo que no es un control (tocar el mapa, los menus) pasa directo al juego.
            it.onPass = { ev -> view.dispatchTouchEvent(ev) }
            // Los juegos de Unity WebGL traen sus controles: los de RPG Maker (flechas, Z, X) empiezan ocultos.
            if (isUnityWebGl()) it.visibility = View.GONE
            container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        view.loadUrl("https://$HOST/" + Uri.encode(indexName))
        view.requestFocus()
    }

    private fun sendKey(action: Int, key: Int) {
        val t = SystemClock.uptimeMillis()
        web?.dispatchKeyEvent(KeyEvent(t, t, action, key, 0))
    }

    /** Sirve los archivos de la carpeta del juego como https://HOST/..., sin salir de esa carpeta. */
    private fun serve(uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        val base = root ?: return null
        val rel = Uri.decode(uri.encodedPath ?: "/").trimStart('/')
        var file = File(base, rel.ifEmpty { indexName })
        if (!file.isFile) {
            val alt = when (file.extension.lowercase()) {
                "m4a" -> "ogg"
                "rpgmvm" -> "rpgmvo"
                "ogg" -> "m4a"
                "rpgmvo" -> "rpgmvm"
                else -> null
            }
            if (alt != null) {
                val other = File(file.parentFile, file.nameWithoutExtension + "." + alt)
                if (other.isFile) file = other
            }
        }
        val ok = try {
            file.isFile && file.canonicalPath.startsWith(base.canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
        if (!ok) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        // Builds web de Unity con compresion Gzip (Kenzu.data.gz, Kenzu.wasm.gz...): un servidor los manda con
        // "Content-Encoding: gzip", pero aca no hay servidor, asi que se descomprimen al servirlos y el tipo
        // se saca del nombre sin el .gz (.wasm.gz -> application/wasm, .framework.js.gz -> javascript).
        val gzip = file.extension.equals("gz", ignoreCase = true) && isGzip(file)
        val ext = (if (gzip) File(file.nameWithoutExtension).extension else file.extension).lowercase()
        val mime = MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val text = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        val body = if (gzip) GZIPInputStream(FileInputStream(file)) else FileInputStream(file)
        return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", headers, body)
    }

    private fun isGzip(file: File): Boolean = try {
        FileInputStream(file).use { it.read() == 0x1f && it.read() == 0x8b }
    } catch (e: Exception) {
        false
    }

    private fun buildFiles(): List<File> = root?.let { File(it, "Build").listFiles()?.toList() }.orEmpty()

    /** Un build web de Unity trae su propio cargador (Build/<nombre>.loader.js) y sus propios controles tactiles. */
    private fun isUnityWebGl(): Boolean = buildFiles().any { it.name.endsWith(".loader.js", ignoreCase = true) }

    /** La compresion Brotli (.br) necesita un descompresor que todavia no esta en la app. */
    private fun usesBrotli(): Boolean = buildFiles().any { it.name.endsWith(".br", ignoreCase = true) }

    private fun showMenu() {
        if (dialog?.isShowing == true) return
        val controls = overlay?.visibility == View.VISIBLE
        val canMove = !isMoved()
        val labels = mutableListOf("Continuar", "Controles en pantalla: " + (if (controls) "si" else "no"), "Recargar el juego")
        if (canMove) labels += "Mover el juego dentro de la app (borrar el original)"
        labels += "Salir"
        dialog = AlertDialog.Builder(this)
            .setTitle(config.title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 1 -> overlay?.visibility = if (controls) View.GONE else View.VISIBLE
                    which == 2 -> web?.reload()
                    canMove && which == 3 -> confirmMove()
                    which == labels.lastIndex -> finishAffinity()
                }
            }
            .show()
    }

    /** Solo se mueve una carpeta que parezca un juego, nunca la memoria entera ni Descargas. */
    private fun isSafeToMove(src: File): Boolean {
        val p = src.absolutePath
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        return p != storage && p != downloads && src.parentFile != null && File(src, indexName).isFile && File(src, "data").isDirectory
    }

    private fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun fileCount(dir: File): Int = dir.walkTopDown().count { it.isFile }

    private fun confirmMove() {
        val src = root ?: return
        if (!isSafeToMove(src)) {
            Toast.makeText(this, "Esta carpeta no se puede mover de forma segura.", Toast.LENGTH_LONG).show()
            return
        }
        val mb = folderSize(src) shr 20
        dialog = AlertDialog.Builder(this)
            .setTitle("Mover el juego")
            .setMessage(
                "Se copia la carpeta del juego ($mb MB) dentro de la app y despues se borra la original:\n${src.absolutePath}\n\n" +
                    "Queda una sola copia. Si desinstalas la app, el juego se borra con ella. " +
                    "Si todavia tenes el .zip, borralo a mano para liberar mas espacio.",
            )
            .setPositiveButton("Mover y borrar") { _, _ -> doMove(src) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doMove(src: File) {
        val need = folderSize(src)
        if (StatFs(filesDir.absolutePath).availableBytes < need + (50L shl 20)) {
            Toast.makeText(this, "No hay espacio libre suficiente para copiar el juego.", Toast.LENGTH_LONG).show()
            return
        }
        val dest = File(filesDir, "webgame")
        val progress = AlertDialog.Builder(this)
            .setTitle("Moviendo el juego")
            .setMessage("No cierres la app hasta que termine.")
            .setCancelable(false)
            .show()
        Thread {
            val result = try {
                dest.deleteRecursively()
                src.copyRecursively(dest, overwrite = true)
                if (fileCount(src) != fileCount(dest) || folderSize(src) != folderSize(dest)) {
                    dest.deleteRecursively()
                    "La copia no coincide con el original. No se borro nada."
                } else {
                    prefs.edit().putString(KEY_MOVED, dest.absolutePath).commit()
                    if (src.deleteRecursively()) "OK" else "OK: no se pudo borrar todo el original, revisalo a mano."
                }
            } catch (e: Exception) {
                dest.deleteRecursively()
                "Error al mover el juego: ${e.message}"
            }
            runOnUiThread {
                progress.dismiss()
                if (result.startsWith("OK")) {
                    root = dest
                    web?.reload()
                }
                Toast.makeText(this, if (result == "OK") "Listo: el juego esta dentro de la app." else result, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val HOST = "rainey.game"
        const val KEY_MOVED = "moved_root"
        val MIME = mapOf(
            "js" to "text/javascript", "json" to "application/json", "wasm" to "application/wasm",
            "ogg" to "audio/ogg", "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "webm" to "video/webm", "mp4" to "video/mp4",
        )
    }
}
