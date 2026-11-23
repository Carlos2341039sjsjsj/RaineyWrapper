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
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
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
    private var mode = "rpgmv"
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
        mode = pickMode()
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
                serve(request.url, request.requestHeaders ?: emptyMap())
        }
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setupOverlay()
        view.loadUrl("https://$HOST/" + (if (isSwf()) "__swf.html" else Uri.encode(indexName)))
        view.requestFocus()
    }

    private fun isSwf() = indexName.endsWith(".swf", ignoreCase = true)

    private fun webHeaders() = mutableMapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cross-Origin-Resource-Policy" to "cross-origin",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Embedder-Policy" to "credentialless",
        "Cache-Control" to "no-cache",
    )

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Pagina que abre el .swf con Ruffle (reproductor de Flash que viaja dentro del APK, en assets/ruffle). */
    private fun swfPage(): WebResourceResponse {
        val html = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100vw;height:100vh}</style>
<script>
window.RufflePlayer = window.RufflePlayer || {};
window.RufflePlayer.config = { autoplay: "on", unmuteOverlay: "hidden", letterbox: "on", splashScreen: false, warnOnUnsupportedContent: false };
</script>
<script src="/__ruffle/ruffle.js"></script>
</head><body><div id="p"></div>
<script>
window.addEventListener("DOMContentLoaded", function () {
  var ruffle = window.RufflePlayer.newest();
  var player = ruffle.createPlayer();
  player.style.width = "100%";
  player.style.height = "100%";
  document.getElementById("p").appendChild(player);
  player.ruffle().load("/${Uri.encode(indexName)}");
});
</script></body></html>"""
        return WebResourceResponse("text/html", "utf-8", 200, "OK", webHeaders(), ByteArrayInputStream(html.toByteArray()))
    }

    /** Archivos de Ruffle guardados en los assets del APK. */
    private fun ruffleAsset(rel: String): WebResourceResponse {
        val name = rel.substringBefore('?')
        if (name.isEmpty() || name.contains("..")) return notFound()
        return try {
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val text = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")
            WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", webHeaders(), assets.open("ruffle/$name"))
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun modeKey() = "mode_" + config.rom.hashCode()

    /** Controles guardados para este juego; si no hay, se adivinan por los archivos de la carpeta. */
    private fun pickMode(): String {
        val saved = try { getSharedPreferences("rainey_web", MODE_PRIVATE).getString(modeKey(), null) } catch (e: Exception) { null }
        if (saved != null && saved in MODES) return saved
        if (isSwf()) return "webplat"
        val dir = root ?: return "webplat"
        if (File(dir, "js/rpg_core.js").isFile || File(dir, "js/rmmz_core.js").isFile || File(dir, "js/rpg_managers.js").isFile) return "rpgmv"
        val head = try {
            File(dir, indexName).inputStream().use { input ->
                val buf = ByteArray(200_000)
                val n = input.read(buf)
                String(buf, 0, maxOf(n, 0)).lowercase()
            }
        } catch (e: Exception) { "" }
        val novel = File(dir, "tyrano").isDirectory || File(dir, "renpy.wasm").isFile || File(dir, "renpy.js").isFile ||
            head.contains("tw-storydata") || head.contains("monogatari") || head.contains("renpy") || head.contains("tyrano")
        return if (novel) "webvn" else "webplat"
    }

    private fun modeLabel(m: String) = when (m) {
        "rpgmv" -> "Teclado RPG (Z/X)"
        "webplat" -> "Plataformas (Z/X/C)"
        "webplat2" -> "Accion (ESPACIO/J/H/L)"
        "webvn" -> "Novela visual"
        else -> "Ninguno (solo tactil)"
    }

    private fun setupOverlay() {
        overlay?.let { container.removeView(it) }
        overlay = null
        val view = web ?: return
        if (mode == "none") return
        overlay = TouchOverlay(
            this, TouchOverlay.layoutFor(mode),
            onKey = { action, key -> sendKey(action, key) },
            onAnalog = { _, _, _ -> },
            onMenu = { showMenu() },
        ).also {
            // Lo que no es un control (tocar el mapa, los menus) pasa directo al juego.
            it.onPass = { ev -> view.dispatchTouchEvent(ev) }
            container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun sendKey(action: Int, key: Int) {
        val t = SystemClock.uptimeMillis()
        web?.dispatchKeyEvent(KeyEvent(t, t, action, key, 0))
    }

    /**
     * Sirve los archivos de la carpeta del juego como https://HOST/..., sin salir de esa carpeta.
     * Entiende pedidos por rangos (audio / video) y entrega descomprimido lo que viene en .gz (builds de Unity / Godot).
     */
    private fun serve(uri: Uri, req: Map<String, String>): WebResourceResponse? {
        if (uri.host != HOST) return null
        val base = root ?: return null
        val rel = Uri.decode(uri.encodedPath ?: "/").trimStart('/')
        if (rel == "__swf.html" && isSwf()) return swfPage()
        if (rel.startsWith("__ruffle/")) return ruffleAsset(rel.removePrefix("__ruffle/"))
        var file = File(base, rel.ifEmpty { indexName })
        if (file.isDirectory) file = File(file, "index.html")
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
        var gz = file.isFile && file.name.endsWith(".gz", ignoreCase = true)
        if (!file.isFile) {
            val packed = File(file.path + ".gz")
            if (packed.isFile) { file = packed; gz = true }
        }
        val ok = try {
            file.isFile && file.canonicalPath.startsWith(base.canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
        if (!ok) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val name = if (gz) file.name.dropLast(3) else file.name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val text = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")
        val enc = if (text) "utf-8" else null
        val headers = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cross-Origin-Resource-Policy" to "cross-origin",
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Embedder-Policy" to "credentialless",
            "Cache-Control" to "no-cache",
        )
        if (gz) {
            val stream: InputStream = try {
                GZIPInputStream(FileInputStream(file), 65536)
            } catch (e: Exception) {
                FileInputStream(file) // se llama .gz pero no lo esta
            }
            return WebResourceResponse(mime, enc, 200, "OK", headers, stream)
        }
        val len = file.length()
        val range = req["Range"] ?: req["range"]
        val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").matchEntire(it.trim()) }
        if (m != null && len > 0L) {
            val a = m.groupValues[1].toLongOrNull()
            val b = m.groupValues[2].toLongOrNull()
            if (a != null || b != null) {
                val start = if (a != null) a else maxOf(0L, len - (b ?: 0L))
                val end = if (a != null && b != null) minOf(b, len - 1) else len - 1
                if (start in 0 until len && end >= start) {
                    val input = FileInputStream(file)
                    input.channel.position(start)
                    headers["Content-Range"] = "bytes $start-$end/$len"
                    headers["Content-Length"] = (end - start + 1).toString()
                    headers["Accept-Ranges"] = "bytes"
                    return WebResourceResponse(mime, enc, 206, "Partial Content", headers, LimitedInput(input, end - start + 1))
                }
            }
        }
        headers["Accept-Ranges"] = "bytes"
        return WebResourceResponse(mime, enc, 200, "OK", headers, FileInputStream(file))
    }

    private fun showMenu() {
        if (dialog?.isShowing == true) return
        val controls = overlay?.visibility == View.VISIBLE
        val canMove = !isMoved()
        val labels = mutableListOf("Continuar", "Controles en pantalla: " + (if (controls) "si" else "no"), "Tipo de controles: " + modeLabel(mode), "Recargar el juego")
        if (canMove) labels += "Mover el juego dentro de la app (borrar el original)"
        labels += "Salir"
        dialog = AlertDialog.Builder(this)
            .setTitle(config.title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 1 -> overlay?.visibility = if (controls) View.GONE else View.VISIBLE
                    which == 2 -> {
                        mode = MODES[(MODES.indexOf(mode) + 1) % MODES.size]
                        try { getSharedPreferences("rainey_web", MODE_PRIVATE).edit().putString(modeKey(), mode).apply() } catch (e: Exception) { }
                        setupOverlay()
                    }
                    which == 3 -> web?.reload()
                    canMove && which == 4 -> confirmMove()
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
        val MODES = listOf("rpgmv", "webplat", "webplat2", "webvn", "none")
        const val KEY_MOVED = "moved_root"
        val MIME = mapOf(
            "js" to "text/javascript", "mjs" to "text/javascript", "json" to "application/json", "wasm" to "application/wasm",
            "ogg" to "audio/ogg", "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "webm" to "video/webm", "mp4" to "video/mp4",
        )
    }
}

/** Entrega solo los primeros [left] bytes del archivo (respuestas 206). */
private class LimitedInput(input: InputStream, private var left: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = super.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
}
