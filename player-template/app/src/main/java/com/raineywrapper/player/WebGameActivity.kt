package com.raineywrapper.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
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

/**
 * Juegos HTML5 (RPG Maker MV / MZ, Unity WebGL, novelas visuales web, plataformas) y Flash (.swf, con Ruffle):
 * se sirven desde la carpeta del juego a un WebView, con los controles en pantalla convertidos en teclas.
 * Las partidas se guardan en el localStorage del WebView.
 */
class WebGameActivity : ComponentActivity() {
    private lateinit var container: FrameLayout
    private lateinit var config: GameConfig
    private var web: NoImeWebView? = null
    private var overlay: TouchOverlay? = null
    private var root: File? = null
    private var indexName = "index.html"
    private var dialog: AlertDialog? = null
    private var mode = "rpgmv"
    private var fit = false
    private val prefs get() = getSharedPreferences("web_game", MODE_PRIVATE)

    private fun isMoved() = prefs.getString(KEY_MOVED, null)?.let { File(it, indexName).isFile } == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Evita que el equipo baje la velocidad del procesador a los pocos minutos de juego (si lo soporta).
        try { if (Build.VERSION.SDK_INT >= 24) window.setSustainedPerformanceMode(true) } catch (e: Exception) { }
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)
        enterImmersiveMode()
        config = GameConfig.load(this)
        val index = File(config.rom)
        indexName = index.name
        val moved = prefs.getString(KEY_MOVED, null)?.let { File(it) }?.takeIf { File(it, indexName).isFile }
        root = moved ?: index.parentFile
        mode = pickMode()
        fit = prefs.getBoolean(fitKey(), false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    override fun onResume() {
        super.onResume()
        web?.let { it.onResume(); focusGame(); return }
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

    /**
     * Devuelve el foco al juego (al volver a la app, cerrar el menu o recargar). Ruffle solo lee teclas cuando tiene el foco,
     * que normalmente le da un toque: se hace uno en el borde de la pantalla, donde no hay nada del juego.
     */
    private fun focusGame() {
        val v = web ?: return
        v.requestFocusFromTouch()
        if (!isSwf()) return
        for (delay in longArrayOf(400, 1500, 3500, 7000)) {
            v.postDelayed({
                val w = web ?: return@postDelayed
                if (w.width <= 0 || w.height <= 0) return@postDelayed
                val t = SystemClock.uptimeMillis()
                val x = 3f
                val y = w.height / 2f
                val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                val up = android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0)
                w.dispatchTouchEvent(down)
                w.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }, delay)
        }
    }

    inner class SwfBridge {
        @JavascriptInterface
        fun ready() {
            runOnUiThread { focusGame() }
        }
    }

    override fun onPause() {
        releaseKeys()
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
        if (hasFocus) { enterImmersiveMode(); focusGame() } else releaseKeys()
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
        val view = NoImeWebView(this)
        web = view
        // El proceso del WebView (donde corre Ruffle) mantiene prioridad alta mientras se juega.
        try { if (Build.VERSION.SDK_INT >= 26) view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (e: Exception) { }
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
        // La pagina de Ruffle avisa cuando el juego ya cargo, para devolverle el foco justo entonces.
        if (isSwf()) view.addJavascriptInterface(SwfBridge(), "RaineyNative")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url, request.requestHeaders ?: emptyMap())

            // Tras recargar (o cerrar el menu) el WebView devuelve el foco al juego: sin foco no le llegan las teclas.
            override fun onPageFinished(view: WebView, url: String?) {
                focusGame()
            }
        }
        container.addView(view, webParams())

        // Los juegos de Unity WebGL traen sus controles: los de teclado empiezan ocultos.
        setupOverlay(hidden = isUnityWebGl())
        view.loadUrl("https://$HOST/" + (if (isSwf()) "__swf.html" else Uri.encode(indexName)))
        view.requestFocus()
        val imeHider = object : Runnable {
            override fun run() {
                val w = web ?: return
                if (!w.allowIme) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(w.windowToken, 0)
                }
                w.postDelayed(this, 400)
            }
        }
        view.postDelayed(imeHider, 400)
    }

    // ---------------------------------------------------------------- controles en pantalla

    private fun fitKey() = "fit_" + config.rom.hashCode()

    /** Con "entre controles" el juego ocupa el centro y los botones quedan a los costados, sin tapar su pantalla. */
    private fun webParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val dm = resources.displayMetrics
        if (fit && dm.widthPixels > dm.heightPixels) {
            val m = (dm.widthPixels * 0.19f).toInt()
            lp.setMargins(m, 0, m, 0)
        }
        return lp
    }

    // Calidad de dibujo de Ruffle: baja (sin suavizado de bordes) arranca como predeterminada, es lo mas liviano.
    private fun swfQuality() = if (swfLite()) "low" else prefs.getString("swf_quality", "low").let { if (it == "medium" || it == "high") it else "low" }

    /** Motor con el que Ruffle dibuja: automatico, canvas 2D o WebGL (para probar cual anda mejor en cada equipo). */
    private fun swfRenderer() = prefs.getString("swf_renderer", "auto").let { if (it == "canvas" || it == "webgl") it else "auto" }

    private fun rendererLabel() = when (swfRenderer()) {
        "canvas" -> "Canvas"
        "webgl" -> "WebGL"
        else -> "Automatico"
    }

    /** Flechas del juego Flash: se mandan como eventos de teclado desde JavaScript (las del WebView pueden perder el foco). */
    private fun jsArrows() = isSwf() && prefs.getBoolean("swf_js_arrows", true)

    /** Tecla de Android -> (key, code, keyCode) de un evento de teclado web. */
    private fun jsKeyInfo(k: Int): Triple<String, String, Int>? {
        if (k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val c = 'a' + (k - KeyEvent.KEYCODE_A)
            return Triple(c.toString(), "Key" + c.uppercaseChar(), 65 + (k - KeyEvent.KEYCODE_A))
        }
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val d = '0' + (k - KeyEvent.KEYCODE_0)
            return Triple(d.toString(), "Digit$d", 48 + (k - KeyEvent.KEYCODE_0))
        }
        return when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> Triple("ArrowUp", "ArrowUp", 38)
            KeyEvent.KEYCODE_DPAD_DOWN -> Triple("ArrowDown", "ArrowDown", 40)
            KeyEvent.KEYCODE_DPAD_LEFT -> Triple("ArrowLeft", "ArrowLeft", 37)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Triple("ArrowRight", "ArrowRight", 39)
            KeyEvent.KEYCODE_SPACE -> Triple(" ", "Space", 32)
            KeyEvent.KEYCODE_ENTER -> Triple("Enter", "Enter", 13)
            KeyEvent.KEYCODE_ESCAPE -> Triple("Escape", "Escape", 27)
            KeyEvent.KEYCODE_TAB -> Triple("Tab", "Tab", 9)
            KeyEvent.KEYCODE_SHIFT_LEFT -> Triple("Shift", "ShiftLeft", 16)
            KeyEvent.KEYCODE_CTRL_LEFT -> Triple("Control", "ControlLeft", 17)
            KeyEvent.KEYCODE_COMMA -> Triple(",", "Comma", 188)
            KeyEvent.KEYCODE_PERIOD -> Triple(".", "Period", 190)
            else -> null
        }
    }

    /** El contador de FPS se muestra siempre en los juegos Flash. */
    private fun swfFps() = true

    /** Sonido apagado: sirve para probar si el audio le quita velocidad a un juego pesado. */
    private fun swfMuted() = prefs.getBoolean("swf_mute", false)

    /**
     * Modo ligero: calidad baja siempre, menos tareas en segundo plano en la pagina. Por defecto se activa solo en equipos
     * con poca memoria (4 GB o menos); se puede cambiar desde el menu.
     */
    private fun swfLite(): Boolean {
        val saved = if (prefs.contains("swf_lite")) prefs.getBoolean("swf_lite", true) else null
        if (saved != null) return saved
        val info = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        return info.totalMem <= (4500L shl 20)
    }

    private fun qualityLabel() = when (swfQuality()) {
        "low" -> "Baja (mas rapido)"
        "high" -> "Alta"
        else -> "Media"
    }

    private fun modeKey() = "mode_" + config.rom.hashCode()

    /** Controles guardados para este juego; si no hay, se adivinan por los archivos de la carpeta. */
    private fun pickMode(): String {
        val saved = prefs.getString(modeKey(), null)
        if (saved != null && saved in MODES) return saved
        if (isSwf()) return if (indexName.contains("superfighter", ignoreCase = true)) "webfight" else "webflash"
        val dir = root ?: return "webplat"
        if (File(dir, "js/rpg_core.js").isFile || File(dir, "js/rmmz_core.js").isFile || File(dir, "js/rpg_managers.js").isFile) return "rpgmv"
        if (isUnityWebGl()) return "webplat2"
        val head = try {
            File(dir, indexName).inputStream().use { input ->
                val buf = ByteArray(200_000)
                val n = input.read(buf)
                String(buf, 0, maxOf(n, 0)).lowercase()
            }
        } catch (e: Exception) {
            ""
        }
        val novel = File(dir, "tyrano").isDirectory || File(dir, "renpy.wasm").isFile || File(dir, "renpy.js").isFile ||
            head.contains("tw-storydata") || head.contains("monogatari") || head.contains("renpy") || head.contains("tyrano")
        return if (novel) "webvn" else "webplat"
    }

    private fun modeLabel(m: String) = when (m) {
        "rpgmv" -> "Teclado RPG (Z/X)"
        "webplat" -> "Plataformas (Z/X/C)"
        "webplat2" -> "Accion (ESPACIO/J/H/L)"
        "webvn" -> "Novela visual"
        "webfight" -> "Combate (N/M/coma/punto)"
        "webflash" -> "Flash clasico (A/S/D/W + Z/X/C/SPC)"
        else -> "Ninguno (solo tactil)"
    }

    private fun setupOverlay(hidden: Boolean = false) {
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
            it.onPass = { ev ->
                ev.offsetLocation(-view.left.toFloat(), -view.top.toFloat())
                view.dispatchTouchEvent(ev)
            }
            if (hidden) it.visibility = View.GONE
            container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private val downKeys = mutableSetOf<Int>()

    private fun sendKey(action: Int, key: Int) {
        if (key == KeyEvent.KEYCODE_BUTTON_1) {
            // AGARRAR (Superfighters): para cambiar de arma hay que agacharse (abajo) y apretar golpe (N) junto al arma.
            val v = web ?: return
            if (action == KeyEvent.ACTION_DOWN) {
                sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
                v.postDelayed({ sendKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N) }, 70)
            } else {
                v.postDelayed({
                    sendKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N)
                    sendKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)
                }, 150)
            }
            return
        }
        if (action == KeyEvent.ACTION_DOWN) downKeys.add(key) else downKeys.remove(key)
        val info = if (jsArrows()) jsKeyInfo(key) else null
        if (info != null) {
            val down = if (action == KeyEvent.ACTION_DOWN) 1 else 0
            web?.evaluateJavascript("window.__rk&&window.__rk('${info.first}','${info.second}',${info.third},$down)", null)
            return
        }
        val t = SystemClock.uptimeMillis()
        web?.dispatchKeyEvent(KeyEvent(t, t, action, key, 0))
    }

    /** Suelta todas las teclas apretadas: si se abre un menu o la app pasa a segundo plano, el personaje no queda caminando solo. */
    private fun releaseKeys() {
        for (k in downKeys.toList()) sendKey(KeyEvent.ACTION_UP, k)
    }

    // ---------------------------------------------------------------- Flash (Ruffle)

    private fun isSwf() = indexName.endsWith(".swf", ignoreCase = true)

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Pagina que abre el .swf con Ruffle (reproductor de Flash que viaja dentro del APK, en assets/ruffle). */
    private fun swfPage(): WebResourceResponse {
        val lite = swfLite()
        val renderer = if (swfRenderer() == "auto") "" else ", preferredRenderer: \"" + swfRenderer() + "\""
        // Contador de FPS (los de la pantalla) y teclas que la pagina ve apretadas: sirve para medir y para cazar teclas pegadas.
        val fpsJs = if (swfFps()) """
<script>
(function () {
  var d = document.createElement('div');
  d.style.cssText = 'position:fixed;left:50%;top:2px;transform:translateX(-50%);font:12px monospace;color:#0f0;background:rgba(0,0,0,.6);padding:1px 6px;z-index:99;pointer-events:none';
  document.body.appendChild(d);
  var n = 0, t = performance.now(), fps = 0, down = {};
  window.addEventListener('keydown', function (e) { down[e.code] = 1; }, true);
  window.addEventListener('keyup', function (e) { delete down[e.code]; }, true);
  function f(now) { n++; if (now - t >= 1000) { fps = Math.round(n * 1000 / (now - t)); n = 0; t = now; } requestAnimationFrame(f); }
  requestAnimationFrame(f);
  setInterval(function () { d.textContent = fps + ' FPS' + (window.__swfFps ? ' (juego ' + window.__swfFps + ')' : '') + ' | foco=' + (document.hasFocus() ? 'si' : 'NO') + ' ' + (document.activeElement ? document.activeElement.tagName : '-') + ' | rk=' + (window.__rkN || 0) + (window.__rkPl ? ' pl=' + window.__rkPl : ' sin-player') + (window.__err ? ' ERR ' + window.__err : '') + ' | ' + Object.keys(down).join(' '); }, ${if (lite) 500 else 100});
})();
</script>""" else ""
        val html = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100vw;height:100vh}</style>
<script>
window.addEventListener("error", function (e) { window.__err = String(e.message || e).slice(0, 60); });
window.RufflePlayer = window.RufflePlayer || {};
window.RufflePlayer.config = { autoplay: "on", unmuteOverlay: "hidden", letterbox: "on", splashScreen: false, warnOnUnsupportedContent: false, contextMenu: "off", quality: "${swfQuality()}"$renderer };
</script>
<script src="/__ruffle/ruffle.js"></script>
</head><body><div id="p"></div>
<script>
var player = null;
// Teclas desde los botones de la pantalla: eventos de teclado directos al reproductor.
window.__rkN = 0;
window.__rk = function (key, code, kc, down) {
  window.__rkN++;
  if (!player) return;
  var init = { key: key, code: code, keyCode: kc, which: kc, bubbles: true, cancelable: true };
  var type = down ? "keydown" : "keyup";
  player.dispatchEvent(new KeyboardEvent(type, init));
  var c = player.shadowRoot && player.shadowRoot.querySelector("canvas");
  if (c) c.dispatchEvent(new KeyboardEvent(type, init));
};
window.__pp = function (p) { try { if (p) player.pause(); else player.play(); } catch (e) { } };
window.addEventListener("DOMContentLoaded", function () {
  var ruffle = window.RufflePlayer.newest();
  player = ruffle.createPlayer();
  window.__rkPl = "ok";
  player.style.width = "100%";
  player.style.height = "100%";
  player.tabIndex = 0;
  document.getElementById("p").appendChild(player);
  var notified = 0;
  function ready() {
    if (notified < 3 && window.RaineyNative) { notified++; window.RaineyNative.ready(); }
  }
  var MUTE = ${if (swfMuted()) "true" else "false"};
  if (MUTE) { try { player.volume = 0; } catch (e) { } }
  player.addEventListener("loadedmetadata", function () {
    try { window.__swfFps = Math.round(player.metadata.frameRate); } catch (e) { }
    if (MUTE) { try { player.volume = 0; } catch (e) { } }
    ready();
  });
  var res = player.ruffle().load("/${Uri.encode(indexName)}");
  if (res && res.then) res.then(ready, function () { });
  // Ruffle crea campos de texto ocultos cuando el juego tiene un cuadro de texto: se les quita el teclado del celular.
  setInterval(function () {
    var roots = [document, player.shadowRoot];
    for (var i = 0; i < roots.length; i++) {
      if (!roots[i]) continue;
      var els = roots[i].querySelectorAll("input,textarea");
      for (var j = 0; j < els.length; j++) els[j].setAttribute("inputmode", "none");
    }
  }, ${if (lite) 1000 else 300});
  // Si el foco se va del reproductor (navegacion con la cruz), se le devuelve: sin foco el juego deja de recibir teclas.
  setInterval(function () { if (document.activeElement !== player) { try { player.focus(); } catch (e) { } } }, ${if (lite) 1000 else 250});
});
</script>$fpsJs</body></html>"""
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(html.toByteArray()))
    }

    /** Archivos de Ruffle guardados en los assets del APK. */
    private fun ruffleAsset(rel: String): WebResourceResponse {
        val name = rel.substringBefore('?')
        if (name.isEmpty() || name.contains("..")) return notFound()
        return try {
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val text = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")
            val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
            WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", headers, assets.open("ruffle/$name"))
        } catch (e: Exception) {
            notFound()
        }
    }

    // ---------------------------------------------------------------- servidor de archivos

    /**
     * Sirve los archivos de la carpeta del juego como https://HOST/..., sin salir de esa carpeta.
     * Entiende pedidos por rangos (audio / video) y descomprime los .gz (builds de Unity).
     */
    private fun serve(uri: Uri, req: Map<String, String>): WebResourceResponse? {
        if (uri.host != HOST) return null
        val base = root ?: return null
        val rel = Uri.decode(uri.encodedPath ?: "/").trimStart('/')
        if (rel == "__swf.html" && isSwf()) return swfPage()
        if (rel.startsWith("__ruffle/")) return ruffleAsset(rel.removePrefix("__ruffle/"))
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
        if (!ok) return notFound()
        // Builds web de Unity con compresion Gzip (Kenzu.data.gz, Kenzu.wasm.gz...): un servidor los manda con
        // "Content-Encoding: gzip", pero aca no hay servidor, asi que se descomprimen al servirlos y el tipo
        // se saca del nombre sin el .gz (.wasm.gz -> application/wasm, .framework.js.gz -> javascript).
        val gzip = file.extension.equals("gz", ignoreCase = true) && isGzip(file)
        val ext = (if (gzip) File(file.nameWithoutExtension).extension else file.extension).lowercase()
        val mime = MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val text = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")
        val enc = if (text) "utf-8" else null
        val headers = mutableMapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        if (gzip) return WebResourceResponse(mime, enc, 200, "OK", headers, GZIPInputStream(FileInputStream(file)))

        // Pedido por rango ("Range: bytes=a-b"): lo usan el audio y el video de muchos juegos web.
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

    // ---------------------------------------------------------------- menu y mover el juego

    private fun showMenu() {
        if (dialog?.isShowing == true) return
        releaseKeys()
        val controls = overlay?.visibility == View.VISIBLE
        val canMove = !isMoved()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add("Continuar" to { })
        items.add("Controles en pantalla: " + (if (controls) "si" else "no") to {
            overlay?.visibility = if (controls) View.GONE else View.VISIBLE
        })
        items.add("Tipo de controles: " + modeLabel(mode) to {
            val hidden = overlay?.visibility == View.GONE
            mode = MODES[(MODES.indexOf(mode) + 1) % MODES.size]
            prefs.edit().putString(modeKey(), mode).apply()
            setupOverlay(hidden)
        })
        items.add("Pantalla: " + (if (fit) "entre los controles" else "completa") to {
            fit = !fit
            prefs.edit().putBoolean(fitKey(), fit).apply()
            web?.layoutParams = webParams()
        })
        if (isSwf()) {
            items.add("Calidad de Flash: " + (if (swfLite()) "Baja (modo ligero)" else qualityLabel()) to {
                val next = when (swfQuality()) { "low" -> "medium"; "medium" -> "high"; else -> "low" }
                prefs.edit().putString("swf_quality", next).apply()
                web?.reload()
            })
            items.add("Dibujo de Flash (probar): " + rendererLabel() to {
                val next = when (swfRenderer()) { "auto" -> "canvas"; "canvas" -> "webgl"; else -> "auto" }
                prefs.edit().putString("swf_renderer", next).apply()
                web?.reload()
            })
            items.add("Teclas: " + (if (jsArrows()) "por JavaScript" else "nativas") to {
                prefs.edit().putBoolean("swf_js_arrows", !jsArrows()).apply()
                releaseKeys()
            })
            items.add("Sonido de Flash: " + (if (swfMuted()) "apagado" else "si") to {
                prefs.edit().putBoolean("swf_mute", !swfMuted()).apply()
                web?.reload()
            })
            items.add("Modo ligero: " + (if (swfLite()) "si" else "no") to {
                prefs.edit().putBoolean("swf_lite", !swfLite()).apply()
                web?.reload()
            })
        }
        items.add("Teclado del celular: " + (if (web?.allowIme == true) "si" else "no") to {
            web?.let { w ->
                w.allowIme = !w.allowIme
                if (!w.allowIme) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(w.windowToken, 0)
                Unit
            }
        })
        items.add("Recargar el juego" to { web?.reload() })
        if (canMove) items.add("Mover el juego dentro de la app (borrar el original)" to { confirmMove() })
        items.add("Salir" to { finishAffinity() })
        web?.evaluateJavascript("window.__pp&&window.__pp(1)", null)
        dialog = AlertDialog.Builder(this)
            .setTitle(config.title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setOnDismissListener {
                web?.evaluateJavascript("window.__pp&&window.__pp(0)", null)
                focusGame()
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
        if (isSwf()) {
            // Un .swf suelto: se mueve solo ese archivo (la carpeta puede ser Descargas u otra con cosas de la persona).
            val file = File(src, indexName)
            if (!file.isFile) return
            dialog = AlertDialog.Builder(this)
                .setTitle("Mover el juego")
                .setMessage(
                    "Se copia el archivo del juego (${file.length() shr 20} MB) dentro de la app y despues se borra el original:\n${file.absolutePath}\n\n" +
                        "Solo se toca ese archivo, no la carpeta. Si desinstalas la app, el juego se borra con ella.",
                )
                .setPositiveButton("Mover y borrar") { _, _ -> doMoveSwf(file) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
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

    private fun doMoveSwf(file: File) {
        if (StatFs(filesDir.absolutePath).availableBytes < file.length() + (50L shl 20)) {
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
                dest.mkdirs()
                val copy = File(dest, file.name)
                file.copyTo(copy, overwrite = true)
                if (copy.length() != file.length()) {
                    dest.deleteRecursively()
                    "La copia no coincide con el original. No se borro nada."
                } else {
                    prefs.edit().putString(KEY_MOVED, dest.absolutePath).commit()
                    if (file.delete()) "OK" else "OK: no se pudo borrar el original, revisalo a mano."
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
        val MODES = listOf("rpgmv", "webplat", "webplat2", "webfight", "webflash", "webvn", "none")
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

/** WebView que no abre el teclado del celular al tocarlo (estorba en los juegos); se puede activar desde el menu. */
private class NoImeWebView(context: android.content.Context) : WebView(context) {
    var allowIme = false

    override fun onCheckIsTextEditor() = allowIme

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? =
        if (allowIme) super.onCreateInputConnection(outAttrs) else null
}
