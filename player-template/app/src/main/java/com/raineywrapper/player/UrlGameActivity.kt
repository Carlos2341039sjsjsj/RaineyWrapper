package com.raineywrapper.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Juegos web que se abren desde un enlace (por ejemplo la version "Run game" de un juego de Unity en itch.io).
 * No hay archivos del juego en el telefono: la pagina se carga desde internet en un WebView a pantalla completa.
 * El juego trae sus propios controles tactiles, asi que no se pone ningun control encima.
 *
 * Si el enlace es una pagina de itch.io, se busca la direccion del juego embebido y se abre esa directamente,
 * para que ocupe toda la pantalla sin la pagina alrededor. Si no se encuentra, se deja la pagina tal cual.
 */
class UrlGameActivity : ComponentActivity() {
    private lateinit var container: FrameLayout
    private lateinit var config: GameConfig
    private var web: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var dialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var itchAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)
        enterImmersiveMode()
        config = GameConfig.load(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) hideCustomView() else showMenu()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        web?.let { it.onResume(); return }
        if (dialog?.isShowing == true) return
        if (!config.url.startsWith("https://")) {
            dialog = AlertDialog.Builder(this)
                .setTitle(config.title)
                .setMessage("Este juego no tiene una direccion valida. Vuelve a convertirlo.")
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
        handler.removeCallbacksAndMessages(null)
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
            // Usa lo que ya se descargo aunque este vencido y solo va a internet por lo que falta: asi,
            // despues de jugar una vez con conexion, el juego puede abrir sin internet (no esta garantizado).
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        view.webChromeClient = object : WebChromeClient() {
            // Pantalla completa pedida por la pagina (el boton de pantalla completa del juego).
            override fun onShowCustomView(v: View?, callback: CustomViewCallback?) {
                if (v == null) return
                customView = v
                customCallback = callback
                web?.visibility = View.GONE
                container.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() = hideCustomView()
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (uri.scheme == "https" && isAllowedHost(uri.host)) return false
                // Cualquier otro enlace (publicidad, redes, otras paginas) se abre en el navegador, no dentro del juego.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (isItchPage(url)) findItchGame()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }
        }
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.loadUrl(config.url)
        view.requestFocus()
    }

    /** Dentro de la app solo se navega por el sitio del juego y por itch.io / itch.zone (donde vive el juego embebido). */
    private fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        val own = Uri.parse(config.url).host
        return host == own || host == "itch.io" || host.endsWith(".itch.io") || host.endsWith(".itch.zone")
    }

    private fun isItchPage(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host } ?: return false
        return host == "itch.io" || host.endsWith(".itch.io")
    }

    /**
     * En la pagina de un juego de itch.io, busca el iframe del juego (la direccion esta en el atributo
     * data-iframe del boton "Run game") y abre esa direccion como pagina principal. Reintenta unas veces porque
     * la pagina arma ese bloque con JavaScript. No lo pude probar contra la pagina real: si la estructura es otra,
     * simplemente no hace nada y el juego se juega desde la pagina de itch.io.
     */
    private fun findItchGame() {
        val view = web ?: return
        if (itchAttempts >= MAX_ITCH_ATTEMPTS) return
        itchAttempts++
        view.evaluateJavascript(ITCH_SCRIPT) { result ->
            if (result?.contains("redirected") == true) return@evaluateJavascript
            handler.postDelayed({ if (web != null && isItchPage(web?.url)) findItchGame() }, 1500)
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        container.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web?.visibility = View.VISIBLE
    }

    private fun showOffline() {
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(this)
            .setTitle(config.title)
            .setMessage("No se pudo cargar el juego. Revisa tu conexion a internet.\n\nEste juego se juega desde la web: necesita conexion la primera vez. Despues puede abrir sin internet con lo que ya se guardo, pero depende del juego y no esta garantizado.")
            .setCancelable(false)
            .setPositiveButton("Reintentar") { _, _ -> web?.loadUrl(config.url) }
            .setNegativeButton("Salir") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showMenu() {
        if (dialog?.isShowing == true) return
        val labels = arrayOf("Continuar", "Recargar el juego", "Volver a descargar el juego (borra lo guardado)", "Salir")
        dialog = AlertDialog.Builder(this)
            .setTitle(config.title)
            .setItems(labels) { _, which ->
                when (which) {
                    1 -> {
                        itchAttempts = 0
                        web?.loadUrl(config.url)
                    }
                    2 -> {
                        itchAttempts = 0
                        web?.clearCache(true)
                        web?.loadUrl(config.url)
                    }
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
        const val MAX_ITCH_ATTEMPTS = 4

        /** Devuelve "redirected" si encontro la direccion del juego y navego a ella. */
        const val ITCH_SCRIPT = """
            (function () {
              try {
                var amp = function (s) { return s.replace(/&amp;/g, '&'); };
                var els = document.querySelectorAll('[data-iframe]');
                for (var i = 0; i < els.length; i++) {
                  var m = /src=["']([^"']+)["']/i.exec(els[i].getAttribute('data-iframe') || '');
                  if (m && /^https:\/\//i.test(amp(m[1]))) { location.replace(amp(m[1])); return 'redirected'; }
                }
                var f = document.querySelector('iframe[src*="itch.zone"]');
                if (f && f.src) { location.replace(f.src); return 'redirected'; }
                var b = document.querySelector('.load_iframe_btn');
                if (b) { b.click(); return 'clicked'; }
              } catch (e) {}
              return 'none';
            })()
        """
    }
}
