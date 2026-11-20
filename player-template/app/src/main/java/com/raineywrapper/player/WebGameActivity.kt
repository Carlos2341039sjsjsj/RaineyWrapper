package com.raineywrapper.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)
        enterImmersiveMode()
        config = GameConfig.load(this)
        val index = File(config.rom)
        root = index.parentFile
        indexName = index.name
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    override fun onResume() {
        super.onResume()
        web?.let { it.onResume(); return }
        if (dialog?.isShowing == true) return
        if (!Environment.isExternalStorageManager()) {
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
        if (!File(config.rom).isFile) {
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
        val file = File(base, rel.ifEmpty { indexName })
        val ok = try {
            file.isFile && file.canonicalPath.startsWith(base.canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
        if (!ok) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val ext = file.extension.lowercase()
        val mime = MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val text = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", headers, FileInputStream(file))
    }

    private fun showMenu() {
        if (dialog?.isShowing == true) return
        val controls = overlay?.visibility == View.VISIBLE
        dialog = AlertDialog.Builder(this)
            .setTitle(config.title)
            .setItems(
                arrayOf("Continuar", "Controles en pantalla: " + (if (controls) "si" else "no"), "Recargar el juego", "Salir"),
            ) { _, which ->
                when (which) {
                    1 -> overlay?.visibility = if (controls) View.GONE else View.VISIBLE
                    2 -> web?.reload()
                    3 -> finishAffinity()
                }
            }
            .show()
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
        val MIME = mapOf(
            "js" to "text/javascript", "json" to "application/json", "wasm" to "application/wasm",
            "ogg" to "audio/ogg", "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "webm" to "video/webm", "mp4" to "video/mp4",
        )
    }
}
