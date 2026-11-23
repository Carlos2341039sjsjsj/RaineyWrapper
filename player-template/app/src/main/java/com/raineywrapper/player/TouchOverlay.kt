package com.raineywrapper.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Minimal on-screen controller: a d-pad on the left, face buttons on the right,
 * shoulders in the top corners, Select/Start/Menu along the bottom middle. Systems with analog
 * sticks get an on-screen stick in the d-pad's place (the d-pad moves up), and a second stick
 * above the face buttons when the console had one.
 * Multi-touch; each finger can press one control and slide between them; a finger that lands on
 * a stick keeps steering it until lifted.
 *
 * Keys are sent as RetroPad buttons (KEYCODE_BUTTON_A = RetroPad A, the right face button).
 */
class TouchOverlay(
    context: Context,
    private val layout: Layout,
    private val onKey: (action: Int, keyCode: Int) -> Unit,
    /** Analog stick position, -1..1 on each axis; [right] tells which stick. */
    private val onAnalog: (right: Boolean, x: Float, y: Float) -> Unit,
    private val onMenu: () -> Unit,
) : View(context) {

    data class Face(val label: String, val keyCode: Int, /** position on a unit circle, (0,0) = centre */ val x: Float, val y: Float)

    data class Layout(
        val face: List<Face>,
        val shoulders: List<Pair<String, Int>>,
        val selectLabel: String = "SELECT",
        val selectKey: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
        val startLabel: String = "START",
        val startKey: Int = KeyEvent.KEYCODE_BUTTON_START,
        /** On-screen analog sticks, for systems whose games are played with them. */
        val leftStick: Boolean = false,
        val rightStick: Boolean = false,
        /** Teclas de teclado (juegos web): se dibujan como teclas negras. */
        val keyboard: Boolean = false,
        /** Sin cruz (novelas visuales: se juega tocando la pantalla). */
        val noDpad: Boolean = false,
    )

    companion object {
        private const val A = KeyEvent.KEYCODE_BUTTON_A
        private const val B = KeyEvent.KEYCODE_BUTTON_B
        private const val X = KeyEvent.KEYCODE_BUTTON_X
        private const val Y = KeyEvent.KEYCODE_BUTTON_Y
        private const val L = KeyEvent.KEYCODE_BUTTON_L1
        private const val R = KeyEvent.KEYCODE_BUTTON_R1
        private const val L2 = KeyEvent.KEYCODE_BUTTON_L2
        private const val R2 = KeyEvent.KEYCODE_BUTTON_R2
        private const val MENU = -1
        const val STYLE_COUNT = 5 // Clasico, PlayStation, Xbox, Wii U / Switch

        fun layoutFor(system: String, wiiController: WiiController = WiiController.NUNCHUK): Layout = when (system) {
            "nes", "lynx", "ngp" -> Layout(listOf(Face("B", B, -0.45f, 0.25f), Face("A", A, 0.45f, -0.25f)), emptyList())
            "pce" -> Layout(listOf(Face("II", B, -0.45f, 0.25f), Face("I", A, 0.45f, -0.25f)), emptyList())
            "atari2600" -> Layout(listOf(Face("FIRE", B, 0f, 0f)), emptyList())
            // RPG Maker MV/MZ en un WebView: Z = aceptar, X = cancelar, SHIFT = correr, CTRL = saltar texto.
            // Juegos web de plataformas / accion: flechas + Z, X, C, ESPACIO; SHIFT, CTRL, E, Q.
            "webplat" -> Layout(
                listOf(
                    Face("C", KeyEvent.KEYCODE_C, 0f, -0.6f), Face("Z", KeyEvent.KEYCODE_Z, 0.6f, 0f),
                    Face("X", KeyEvent.KEYCODE_X, 0f, 0.6f), Face("SPC", KeyEvent.KEYCODE_SPACE, -0.6f, 0f),
                ),
                listOf(
                    "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT, "E" to KeyEvent.KEYCODE_E,
                    "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT, "Q" to KeyEvent.KEYCODE_Q,
                ),
                selectLabel = "ESC", selectKey = KeyEvent.KEYCODE_ESCAPE,
                startLabel = "ENTER", startKey = KeyEvent.KEYCODE_ENTER,
                keyboard = true,
            )
            // Otra distribucion de accion: ESPACIO salta, J/H/L atacan, SHIFT esquiva, E interactua, R cura, M mapa, TAB menu.
            "webplat2" -> Layout(
                listOf(
                    Face("H", KeyEvent.KEYCODE_H, 0f, -0.6f), Face("J", KeyEvent.KEYCODE_J, 0.6f, 0f),
                    Face("SPC", KeyEvent.KEYCODE_SPACE, 0f, 0.6f), Face("L", KeyEvent.KEYCODE_L, -0.6f, 0f),
                ),
                listOf(
                    "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT, "E" to KeyEvent.KEYCODE_E,
                    "R" to KeyEvent.KEYCODE_R, "M" to KeyEvent.KEYCODE_M,
                ),
                selectLabel = "ESC", selectKey = KeyEvent.KEYCODE_ESCAPE,
                startLabel = "TAB", startKey = KeyEvent.KEYCODE_TAB,
                keyboard = true,
            )
            // Novelas visuales web (RenPy, TyranoScript, Twine...): se toca la pantalla; botones para avanzar, auto, saltar y volver.
            // Superfighters y juegos de combate parecidos: flechas + N (golpe), M (disparo), coma (granada), punto (camara lenta).
            "webfight" -> Layout(
                listOf(
                    Face("M", KeyEvent.KEYCODE_M, 0f, -0.6f), Face("N", KeyEvent.KEYCODE_N, 0.6f, 0f),
                    Face(",", KeyEvent.KEYCODE_COMMA, 0f, 0.6f), Face(".", KeyEvent.KEYCODE_PERIOD, -0.6f, 0f),
                ),
                emptyList(),
                selectLabel = "ESC", selectKey = KeyEvent.KEYCODE_ESCAPE,
                startLabel = "ENTER", startKey = KeyEvent.KEYCODE_ENTER,
                keyboard = true,
            )
            "webvn" -> Layout(
                listOf(Face("ENTER", KeyEvent.KEYCODE_ENTER, 0f, 0f)),
                listOf(
                    "AUTO" to KeyEvent.KEYCODE_A, "SKIP" to KeyEvent.KEYCODE_CTRL_LEFT,
                    "ATRAS" to KeyEvent.KEYCODE_PAGE_UP, "H" to KeyEvent.KEYCODE_H,
                ),
                selectLabel = "ESC", selectKey = KeyEvent.KEYCODE_ESCAPE,
                startLabel = "TAB", startKey = KeyEvent.KEYCODE_TAB,
                keyboard = true, noDpad = true,
            )
            "rpgmv" -> Layout(
                listOf(Face("Z", KeyEvent.KEYCODE_Z, 0.45f, -0.25f), Face("X", KeyEvent.KEYCODE_X, -0.45f, 0.25f)),
                listOf(
                    "Q" to KeyEvent.KEYCODE_Q, "W" to KeyEvent.KEYCODE_W,
                    "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT, "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
                ),
                selectLabel = "ESC", selectKey = KeyEvent.KEYCODE_ESCAPE,
                startLabel = "ENTER", startKey = KeyEvent.KEYCODE_ENTER,
            )
            "gba" -> Layout(listOf(Face("B", B, -0.45f, 0.25f), Face("A", A, 0.45f, -0.25f)), listOf("L" to L, "R" to R))
            "gb", "gb2", "sms", "gg" -> Layout(listOf(Face(if (system == "gb" || system == "gb2") "B" else "1", B, -0.45f, 0.25f), Face(if (system == "gb" || system == "gb2") "A" else "2", A, 0.45f, -0.25f)), emptyList())
            // Genesis Plus GX: RetroPad Y/B/A = Genesis A/B/C, Select = Mode.
            "genesis" -> Layout(listOf(Face("A", Y, -0.6f, 0.2f), Face("B", B, 0f, 0f), Face("C", A, 0.6f, -0.2f)), emptyList(), selectLabel = "MODE")
            // Mupen64Plus-Next: RetroPad B/Y = N64 A/B, L2 = Z. C-buttons are on the right stick.
            "n64" -> Layout(
                listOf(Face("A", B, 0.1f, 0.35f), Face("B", Y, -0.5f, -0.15f)),
                listOf("L" to L, "R" to R, "Z" to L2),
                leftStick = true, rightStick = true, // right stick = C buttons
            )
            // Flycast: RetroPad B/A/Y/X = Dreamcast A/B/X/Y, L2/R2 = triggers.
            "dreamcast" -> Layout(
                listOf(Face("Y", X, 0f, -0.55f), Face("B", A, 0.55f, 0f), Face("A", B, 0f, 0.55f), Face("X", Y, -0.55f, 0f)),
                listOf("L" to L2, "R" to R2),
                leftStick = true,
            )
            // Dolphin: RetroPad A/B/X/Y = GameCube A/B/X/Y, L2/R2 = triggers, R1 = Z.
            "gamecube" -> Layout(
                listOf(Face("A", A, 0f, 0.1f), Face("B", B, -0.55f, 0.45f), Face("X", X, 0.6f, -0.2f), Face("Y", Y, -0.2f, -0.6f)),
                listOf("L" to L2, "R" to R2, "Z" to R),
                leftStick = true, rightStick = true, // right stick = C stick
            )
            // Dolphin's Wii layouts; see GamepadInput.wiiRemap for the RetroPad assignments.
            "wii" -> when (wiiController) {
                WiiController.NUNCHUK -> Layout(
                    listOf(Face("C", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Z", Y, -0.55f, 0f)),
                    listOf("1" to KeyEvent.KEYCODE_BUTTON_START, "SHAKE" to R2, "2" to KeyEvent.KEYCODE_BUTTON_SELECT),
                    selectLabel = "−", selectKey = L, startLabel = "+", startKey = R,
                    leftStick = true, rightStick = true, // Nunchuk stick; right stick aims the pointer
                )
                WiiController.CLASSIC -> Layout(
                    listOf(Face("X", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Y", Y, -0.55f, 0f)),
                    listOf("L" to L2, "R" to R2, "ZL" to L, "ZR" to R),
                    selectLabel = "−", startLabel = "+",
                    leftStick = true, rightStick = true,
                )
                WiiController.SIDEWAYS -> Layout(
                    listOf(Face("1", B, -0.45f, 0.25f), Face("2", A, 0.45f, -0.25f)),
                    listOf("A" to X, "B" to Y, "SHAKE" to R2),
                    selectLabel = "−", startLabel = "+",
                )
            }
            // Azahar: RetroPad A/B/X/Y = 3DS A/B/X/Y, L/R = L/R, el stick izquierdo es el circle pad.
            "3ds" -> Layout(
                listOf(Face("X", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Y", Y, -0.55f, 0f)),
                listOf("L" to L, "R" to R),
                leftStick = true,
            )
            "psp" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L" to L, "R" to R),
                leftStick = true,
            )
            "ps2" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L1" to L, "R1" to R, "L2" to L2, "R2" to R2),
                leftStick = true, rightStick = true,
            )
            "psx" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L1" to L, "R1" to R, "L2" to L2, "R2" to R2),
            )
            else -> Layout( // SNES and anything else with a 4-button diamond
                listOf(Face("X", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Y", Y, -0.55f, 0f)),
                listOf("L" to L, "R" to R),
            )
        }
    }

    private class Control(val label: String, val keyCode: Int, val rect: RectF, val round: Boolean)

    private val controls = mutableListOf<Control>()
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadR = 0f

    /** pointerId -> keys that pointer is holding (a d-pad diagonal holds two). */
    private val held = mutableMapOf<Int, Set<Int>>()

    /** Si se define, los toques que no caen sobre ningun control se reenvian al juego (lapiz de DS en horizontal). */
    var onPass: ((MotionEvent) -> Unit)? = null
    private var passId = -1
    private var passX = 0f
    private var passY = 0f
    private fun forward(action: Int, x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, x, y, 0)
        onPass?.invoke(ev)
        ev.recycle()
    }
    private val pressedKeys = mutableMapOf<Int, Int>() // keyCode -> number of pointers holding it

    /** An on-screen analog stick; the knob follows the finger that grabbed it. */
    private class Stick(val right: Boolean) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pointerId = -1
        var x = 0f // -1..1
        var y = 0f

        fun contains(px: Float, py: Float) = hypot(px - cx, py - cy) <= r * 1.3f
    }

    private val sticks = listOfNotNull(
        Stick(right = false).takeIf { layout.leftStick },
        Stick(right = true).takeIf { layout.rightStick },
    )

    /** 0 = Clasico, 1 = PlayStation, 2 = Xbox, 3 = Wii U / Switch (aplica a todas las consolas). */
    var controlStyle: Int = 0
    init {
        try {
            controlStyle = context.getSharedPreferences("rainey_controls", android.content.Context.MODE_PRIVATE)
                .getInt("control_style", 0).coerceIn(0, STYLE_COUNT - 1)
        } catch (e: Exception) { controlStyle = 0 }
    }

    fun controlStyleName(): String = when (controlStyle) {
        1 -> "PlayStation"
        2 -> "Xbox"
        3 -> "Wii U / Switch"
        4 -> "PS2 (DualShock)"
        else -> "Clásico"
    }

    fun setStyle(style: Int) {
        saveControlStyle(style)
        if (width > 0) relayout() else invalidate()
    }

    /** Rombo de 4 botones (SNES / PS / Dreamcast): se puede traducir por posicion entre estilos. */
    private val faceDiamond: Boolean = layout.face.size == 4 &&
        layout.face.map { it.label }.toSet().let { it == setOf("X", "A", "B", "Y") || it == setOf("△", "○", "✕", "□") }

    /** 0 = arriba, 1 = derecha, 2 = abajo, 3 = izquierda. */
    private fun faceSlot(label: String): Int {
        val f = layout.face.firstOrNull { it.label == label } ?: return -1
        return if (kotlin.math.abs(f.y) > kotlin.math.abs(f.x)) (if (f.y < 0) 0 else 2) else (if (f.x > 0) 1 else 3)
    }

    private fun faceLabel(label: String): String {
        if (!faceDiamond) return label
        val s = faceSlot(label)
        if (s < 0) return label
        return when (controlStyle) {
            1, 4 -> listOf("△", "○", "✕", "□")[s]
            2 -> listOf("Y", "B", "A", "X")[s]
            else -> label
        }
    }

    private fun faceColor(label: String): Int = when (controlStyle) {
        1, 4 -> when (label) {
            "△", "Y" -> Color.rgb(0, 200, 90)
            "○", "A" -> Color.rgb(230, 50, 50)
            "✕", "B" -> Color.rgb(60, 120, 255)
            "□", "X" -> Color.rgb(255, 110, 180)
            else -> Color.WHITE
        }
        2 -> when (label) {
            "Y" -> Color.rgb(255, 205, 0)
            "B" -> Color.rgb(235, 55, 55)
            "A" -> Color.rgb(0, 185, 85)
            "X" -> Color.rgb(50, 120, 255)
            else -> Color.WHITE
        }
        else -> Color.WHITE
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }
    private val fillLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }
    private val fillLightPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pathA = android.graphics.Path()
    private val pathB = android.graphics.Path()
    private val textColor = Color.argb(220, 255, 255, 255)

    // ---- Editor de controles ----
    private var editMode = false
    private var draggingEl = false
    private var selectedKey: String? = null
    private var lastX = 0f
    private var lastY = 0f
    private val edits: MutableMap<String, FloatArray> by lazy { loadEdits() }
    private val layoutPrefs: android.content.SharedPreferences
        get() = context.getSharedPreferences("touch_layout", 0)
    private val panelLabels = listOf("-", "+", "Reset", "OK")
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 214, 0); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 20, 30) }
    private val panelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private fun loadEdits(): MutableMap<String, FloatArray> {
        val m = mutableMapOf<String, FloatArray>()
        for ((k, v) in layoutPrefs.all) {
            if (!k.startsWith("k:") || v !is String) continue
            val p = v.split(",").mapNotNull { it.toFloatOrNull() }
            if (p.size == 3) m[k.substring(2)] = floatArrayOf(p[0], p[1], p[2])
        }
        return m
    }

    private fun saveEdits() {
        val e = layoutPrefs.edit()
        for (k in layoutPrefs.all.keys) if (k.startsWith("k:")) e.remove(k)
        for ((k, v) in edits) e.putString("k:" + k, v[0].toString() + "," + v[1].toString() + "," + v[2].toString())
        e.apply()
    }

    fun beginEdit() {
        releaseAll()
        editMode = true
        selectedKey = null
        invalidate()
    }

    private fun applyUserLayout(w: Float, h: Float) {
        edits["dpad"]?.let { e ->
            dpadCx += e[0] * w
            dpadCy += e[1] * h
            dpadR *= e[2]
        }
        for (stick in sticks) {
            val e = edits["s:" + stick.right] ?: continue
            stick.cx += e[0] * w
            stick.cy += e[1] * h
            stick.r *= e[2]
        }
        controls.forEachIndexed { i, c ->
            val e = edits["c" + i + ":" + c.label] ?: return@forEachIndexed
            val cx = c.rect.centerX() + e[0] * w
            val cy = c.rect.centerY() + e[1] * h
            val hw = c.rect.width() / 2f * e[2]
            val hh = c.rect.height() / 2f * e[2]
            c.rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        }
    }

    private fun relayout() {
        onSizeChanged(width, height, width, height)
        invalidate()
    }

    private fun keyAt(x: Float, y: Float): String? {
        for (i in controls.indices.reversed()) {
            val c = controls[i]
            if (c.rect.contains(x, y)) return "c" + i + ":" + c.label
        }
        for (stick in sticks) {
            if (hypot(x - stick.cx, y - stick.cy) <= stick.r) return "s:" + stick.right
        }
        if (hypot(x - dpadCx, y - dpadCy) <= dpadR) return "dpad"
        return null
    }

    private fun panelRects(): List<RectF> {
        val u = min(width, height) / 10f
        val bw = u * 1.7f
        val bh = u * 0.9f
        val gap = u * 0.2f
        val total = bw * 4 + gap * 3
        val x0 = width / 2f - total / 2f
        val y0 = height / 2f - bh / 2f
        return (0 until 4).map { RectF(x0 + it * (bw + gap), y0, x0 + it * (bw + gap) + bw, y0 + bh) }
    }

    private fun panelPress(b: Int) {
        when (b) {
            0, 1 -> {
                val k = selectedKey ?: return
                val e = edits.getOrPut(k) { floatArrayOf(0f, 0f, 1f) }
                e[2] = (e[2] * (if (b == 0) 0.9f else 1.1f)).coerceIn(0.4f, 2.5f)
                relayout()
            }
            2 -> {
                edits.clear()
                relayout()
            }
            3 -> {
                saveEdits()
                editMode = false
                selectedKey = null
                invalidate()
            }
        }
    }

    private fun editTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val o = optRects().indexOfFirst { it.contains(x, y) }
                if (o >= 0) {
                    optPress(o)
                    invalidate()
                    return true
                }
                val b = panelRects().indexOfFirst { it.contains(x, y) }
                if (b >= 0) {
                    draggingEl = false
                    panelPress(b)
                } else {
                    selectedKey = keyAt(x, y)
                    draggingEl = selectedKey != null
                    lastX = x
                    lastY = y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = selectedKey
                if (draggingEl && k != null) {
                    val e = edits.getOrPut(k) { floatArrayOf(0f, 0f, 1f) }
                    e[0] += (x - lastX) / width
                    e[1] += (y - lastY) / height
                    lastX = x
                    lastY = y
                    relayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> draggingEl = false
        }
        return true
    }

    private fun drawEditor(canvas: Canvas) {
        if (!editMode) return
        drawOptions(canvas)
        val rects = panelRects()
        panelText.textSize = rects[0].height() * 0.4f
        rects.forEachIndexed { i, r ->
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, panelFill)
            canvas.drawText(panelLabels[i], r.centerX(), r.centerY() + panelText.textSize / 3, panelText)
        }
        canvas.drawText("Drag a control, then - / +", width / 2f, rects[0].top - 24f, panelText)
        val k = selectedKey ?: return
        if (k == "dpad") {
            canvas.drawCircle(dpadCx, dpadCy, dpadR + 8f, selPaint)
        } else if (k.startsWith("s:")) {
            sticks.firstOrNull { "s:" + it.right == k }?.let {
                canvas.drawCircle(it.cx, it.cy, it.r + 8f, selPaint)
            }
        } else {
            val i = k.substring(1, k.indexOf(':')).toIntOrNull()
            if (i != null && i in controls.indices) {
                val r = controls[i].rect
                canvas.drawRect(r.left - 8f, r.top - 8f, r.right + 8f, r.bottom + 8f, selPaint)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        controls.clear()
        val unit = min(w, h) / 10f
        val margin = unit * 0.4f

        // The bottom-left spot belongs to the left stick when there is one, and the d-pad moves up
        // into the space between it and the shoulder buttons; a right stick goes above the face buttons.
        val mainR = unit * 1.7f
        val mainCx = margin + mainR
        val mainCy = h - margin - mainR - unit * 0.3f
        val upperR = unit * 1.05f
        val upperCy = (margin + 2 * unit * 0.85f + unit * 0.25f + (mainCy - mainR * 1.45f)) / 2
        val wiiu = controlStyle == 3 && layout.rightStick
        for (stick in sticks) {
            if (stick.right) {
                if (wiiu) { stick.r = mainR * 0.75f; stick.cx = w - margin - mainR; stick.cy = mainCy }
                else { stick.r = upperR; stick.cx = w - margin - mainR; stick.cy = upperCy }
            } else {
                stick.r = mainR; stick.cx = mainCx; stick.cy = mainCy
            }
        }
        if (layout.leftStick) {
            dpadR = upperR; dpadCx = mainCx; dpadCy = upperCy
        } else {
            dpadR = mainR; dpadCx = mainCx; dpadCy = mainCy
        }
        if (layout.noDpad) { dpadR = 0f; dpadCx = -10000f }

        val faceR = unit * 0.62f
        val faceCx = w - margin - mainR
        val faceCy = if (wiiu) upperCy else mainCy
        val faceScale = if (wiiu) mainR * 0.95f else mainR * 1.15f
        for (f in layout.face) {
            val cx = faceCx + f.x * faceScale
            val cy = faceCy + f.y * faceScale
            controls += Control(f.label, f.keyCode, RectF(cx - faceR, cy - faceR, cx + faceR, cy + faceR), true)
        }

        val sw = unit * 1.9f
        val sh = unit * 0.85f
        layout.shoulders.forEachIndexed { i, (label, key) ->
            val left = i % 2 == 0
            val row = i / 2
            val x = if (left) margin else w - margin - sw
            val y = margin + row * (sh + unit * 0.25f)
            controls += Control(label, key, RectF(x, y, x + sw, y + sh), false)
        }

        val pw = unit * (if (controlStyle == 3) 1.0f else 1.5f)
        val ph = unit * 0.6f
        val by = h - margin - ph
        controls += Control(layout.selectLabel, layout.selectKey, RectF(w / 2f - pw * 1.6f, by, w / 2f - pw * 0.6f, by + ph), false)
        controls += Control(layout.startLabel, layout.startKey, RectF(w / 2f + pw * 0.6f, by, w / 2f + pw * 1.6f, by + ph), false)
        controls += Control("☰", MENU, RectF(w / 2f - ph, margin, w / 2f + ph, margin + ph * 1.4f), false)
        applyUserLayout(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        drawEditor(canvas)
        drawDpad(canvas)
        for (stick in sticks) drawStick(canvas, stick)
        for (c in controls) drawControl(canvas, c)
        text.color = textColor
    }

    private fun ringStyle(color: Int, width: Float): Paint {
        ring.color = color
        ring.strokeWidth = width
        return ring
    }

    private fun drawDpad(canvas: Canvas) {
        if (webLayout) {
            drawKeyDpad(canvas)
            return
        }
        val o = dpadR * 0.62f
        val dirs = listOf(
            Triple("▲", 0f, -o) to KeyEvent.KEYCODE_DPAD_UP, Triple("▼", 0f, o) to KeyEvent.KEYCODE_DPAD_DOWN,
            Triple("◀", -o, 0f) to KeyEvent.KEYCODE_DPAD_LEFT, Triple("▶", o, 0f) to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        when (controlStyle) {
            1 -> { // cuatro botones separados, solo borde
                val s = dpadR * 0.3f
                for ((t, key) in dirs) {
                    val (label, dx, dy) = t
                    val r = RectF(dpadCx + dx * 1.15f - s, dpadCy + dy * 1.15f - s, dpadCx + dx * 1.15f + s, dpadCy + dy * 1.15f + s)
                    if (isPressed(key)) {
                        tint.color = Color.argb(110, 255, 255, 255)
                        canvas.drawRoundRect(r, s * 0.4f, s * 0.4f, tint)
                    }
                    canvas.drawRoundRect(r, s * 0.4f, s * 0.4f, ringStyle(Color.argb(225, 255, 255, 255), 4f))
                    text.color = Color.WHITE
                    text.textSize = s * 0.9f
                    canvas.drawText(label, r.centerX(), r.centerY() + text.textSize / 3, text)
                }
            }
            2 -> { // una sola cruz redondeada con punto central
                val arm = dpadR * 0.34f
                val len = dpadR * 0.98f
                pathA.reset(); pathB.reset()
                pathA.addRoundRect(RectF(dpadCx - len, dpadCy - arm, dpadCx + len, dpadCy + arm), arm * 0.55f, arm * 0.55f, android.graphics.Path.Direction.CW)
                pathB.addRoundRect(RectF(dpadCx - arm, dpadCy - len, dpadCx + arm, dpadCy + len), arm * 0.55f, arm * 0.55f, android.graphics.Path.Direction.CW)
                pathA.op(pathB, android.graphics.Path.Op.UNION)
                tint.color = Color.argb(95, 255, 255, 255)
                canvas.drawPath(pathA, tint)
                canvas.drawPath(pathA, ringStyle(Color.argb(200, 255, 255, 255), 3f))
                for ((t, key) in dirs) {
                    val (_, dx, dy) = t
                    if (isPressed(key)) {
                        tint.color = Color.argb(150, 255, 255, 255)
                        canvas.drawCircle(dpadCx + dx * 1.1f, dpadCy + dy * 1.1f, arm * 0.8f, tint)
                    }
                }
                tint.color = Color.argb(150, 255, 255, 255)
                canvas.drawCircle(dpadCx, dpadCy, arm * 0.38f, tint)
            }
            4 -> { // PS2: una cruz oscura con relieve
                val arm = dpadR * 0.36f
                val len = dpadR * 0.98f
                pathA.reset(); pathB.reset()
                pathA.addRoundRect(RectF(dpadCx - len, dpadCy - arm, dpadCx + len, dpadCy + arm), arm * 0.3f, arm * 0.3f, android.graphics.Path.Direction.CW)
                pathB.addRoundRect(RectF(dpadCx - arm, dpadCy - len, dpadCx + arm, dpadCy + len), arm * 0.3f, arm * 0.3f, android.graphics.Path.Direction.CW)
                pathA.op(pathB, android.graphics.Path.Op.UNION)
                gradPaint.shader = android.graphics.LinearGradient(
                    dpadCx - len, dpadCy - len, dpadCx + len, dpadCy + len,
                    Color.rgb(96, 100, 110), Color.rgb(22, 24, 30), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawPath(pathA, gradPaint)
                gradPaint.shader = null
                canvas.drawPath(pathA, ringStyle(Color.argb(200, 185, 190, 200), 3f))
                text.textSize = dpadR * 0.28f
                for ((t, key) in dirs) {
                    val (label, dx, dy) = t
                    if (isPressed(key)) {
                        tint.color = Color.argb(120, 255, 255, 255)
                        canvas.drawCircle(dpadCx + dx * 1.1f, dpadCy + dy * 1.1f, arm * 0.7f, tint)
                    }
                    text.color = Color.argb(230, 205, 210, 220)
                    canvas.drawText(label, dpadCx + dx * 1.1f, dpadCy + dy * 1.1f + text.textSize / 3, text)
                }
            }
            3 -> { // disco oscuro con cuatro flechas
                tint.color = Color.argb(120, 25, 28, 38)
                canvas.drawCircle(dpadCx, dpadCy, dpadR, tint)
                canvas.drawCircle(dpadCx, dpadCy, dpadR, ringStyle(Color.argb(200, 255, 255, 255), 3f))
                text.color = Color.WHITE
                text.textSize = dpadR * 0.32f
                for ((t, key) in dirs) {
                    val (label, dx, dy) = t
                    if (isPressed(key)) {
                        tint.color = Color.argb(150, 255, 255, 255)
                        canvas.drawCircle(dpadCx + dx, dpadCy + dy, dpadR * 0.3f, tint)
                    }
                    canvas.drawText(label, dpadCx + dx, dpadCy + dy + text.textSize / 3, text)
                }
            }
            else -> {
                canvas.drawCircle(dpadCx, dpadCy, dpadR, fill)
                canvas.drawCircle(dpadCx, dpadCy, dpadR, stroke)
                text.textSize = dpadR * 0.32f
                for ((t, key) in dirs) {
                    val (label, dx, dy) = t
                    if (isPressed(key)) canvas.drawCircle(dpadCx + dx, dpadCy + dy, dpadR * 0.3f, fillPressed)
                    canvas.drawText(label, dpadCx + dx, dpadCy + dy + text.textSize / 3, text)
                }
            }
        }
    }

    private fun drawStick(canvas: Canvas, stick: Stick) {
        val kx = stick.cx + stick.x * stick.r * 0.6f
        val ky = stick.cy + stick.y * stick.r * 0.6f
        val active = stick.pointerId >= 0
        when (controlStyle) {
            1 -> { // anillos finos
                canvas.drawCircle(stick.cx, stick.cy, stick.r, ringStyle(Color.argb(210, 255, 255, 255), 3f))
                if (active) {
                    tint.color = Color.argb(70, 255, 255, 255)
                    canvas.drawCircle(kx, ky, stick.r * 0.4f, tint)
                }
                canvas.drawCircle(kx, ky, stick.r * 0.4f, ringStyle(Color.argb(230, 255, 255, 255), 3f))
            }
            2 -> {
                tint.color = Color.argb(70, 255, 255, 255)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, tint)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, ringStyle(Color.argb(170, 255, 255, 255), 3f))
                tint.color = Color.argb(if (active) 215 else 150, 255, 255, 255)
                canvas.drawCircle(kx, ky, stick.r * 0.42f, tint)
            }
            4 -> { // PS2: pozo oscuro con tapa de relieve
                tint.color = Color.argb(150, 12, 13, 17)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, tint)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, ringStyle(Color.argb(190, 185, 190, 200), 3f))
                canvas.drawCircle(stick.cx, stick.cy, stick.r * 0.78f, ringStyle(Color.argb(70, 185, 190, 200), 2f))
                val kr = stick.r * 0.44f
                gradPaint.shader = android.graphics.RadialGradient(
                    kx - kr * 0.3f, ky - kr * 0.35f, kr * 1.5f,
                    if (active) Color.rgb(150, 154, 165) else Color.rgb(104, 108, 118), Color.rgb(28, 30, 36),
                    android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(kx, ky, kr, gradPaint)
                gradPaint.shader = null
                canvas.drawCircle(kx, ky, kr, ringStyle(Color.argb(210, 190, 195, 205), 3f))
                canvas.drawCircle(kx, ky, kr * 0.62f, ringStyle(Color.argb(90, 190, 195, 205), 2f))
            }
            3 -> {
                tint.color = Color.argb(120, 25, 28, 38)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, tint)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, ringStyle(Color.argb(190, 255, 255, 255), 3f))
                tint.color = Color.argb(if (active) 225 else 165, 200, 205, 215)
                canvas.drawCircle(kx, ky, stick.r * 0.42f, tint)
            }
            else -> {
                canvas.drawCircle(stick.cx, stick.cy, stick.r, fill)
                canvas.drawCircle(stick.cx, stick.cy, stick.r, stroke)
                val knob = if (active) fillPressed else fill
                canvas.drawCircle(kx, ky, stick.r * 0.42f, knob)
                canvas.drawCircle(kx, ky, stick.r * 0.42f, stroke)
            }
        }
    }

    /** Juegos de PC (RPG Maker MV/MZ): los dos botones de cara son Z y X. */
    private val webLayout: Boolean = layout.keyboard || layout.face.map { it.label }.toSet() == setOf("Z", "X")

    /** Una tecla de teclado: tapa negra con degradado, borde gris y letras blancas; se aclara al apretarla. */
    private fun drawKey(canvas: Canvas, r: RectF, label: String, pressed: Boolean, size: Float) {
        val rad = minOf(r.width(), r.height()) * 0.22f
        val lift = r.height() * 0.07f
        tint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(RectF(r.left, r.top + lift, r.right, r.bottom + lift), rad, rad, tint)
        val top = if (pressed) Color.rgb(78, 78, 88) else Color.rgb(54, 54, 60)
        val bottom = if (pressed) Color.rgb(40, 40, 46) else Color.rgb(14, 14, 18)
        gradPaint.shader = android.graphics.LinearGradient(
            r.left, r.top, r.left, r.bottom, top, bottom, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, rad, rad, gradPaint)
        gradPaint.shader = null
        canvas.drawRoundRect(r, rad, rad, ringStyle(Color.argb(210, 120, 120, 132), 2.5f))
        text.color = Color.WHITE
        text.textSize = size
        while (text.measureText(label) > r.width() * 0.84f && text.textSize > 8f) text.textSize -= 1f
        canvas.drawText(label, r.centerX(), r.centerY() + text.textSize / 3, text)
    }

    /** La cruz como cuatro teclas de flecha. */
    private fun drawKeyDpad(canvas: Canvas) {
        if (dpadR <= 0f) return
        val s = dpadR * 0.34f
        val o = dpadR * 0.66f
        val dirs = listOf(
            Triple("\u25B2", 0f, -o) to KeyEvent.KEYCODE_DPAD_UP, Triple("\u25BC", 0f, o) to KeyEvent.KEYCODE_DPAD_DOWN,
            Triple("\u25C0", -o, 0f) to KeyEvent.KEYCODE_DPAD_LEFT, Triple("\u25B6", o, 0f) to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        for ((t, key) in dirs) {
            val (label, dx, dy) = t
            drawKey(canvas, RectF(dpadCx + dx - s, dpadCy + dy - s, dpadCx + dx + s, dpadCy + dy + s), label, isPressed(key), s * 0.9f)
        }
    }

    private fun drawControl(canvas: Canvas, c: Control) {
        val pressed = isPressed(c.keyCode)
        val face = c.round
        if (webLayout) {
            drawKey(canvas, c.rect, c.label, pressed, c.rect.height() * 0.42f)
            return
        }
        var label = if (face) faceLabel(c.label) else c.label
        if (controlStyle == 3 && !face) {
            if (label == "SELECT") label = "−"
            if (label == "START") label = "+"
        }
        val accent = if (face) faceColor(label) else Color.WHITE
        val rr = c.rect.height() / 2
        var textCol = Color.WHITE
        fun shape(p: Paint) {
            if (face) canvas.drawOval(c.rect, p) else canvas.drawRoundRect(c.rect, rr, rr, p)
        }
        when (controlStyle) {
            1 -> { // anillo hueco de color, letra de color (pildoras blancas)
                if (pressed) {
                    tint.color = withAlpha(accent, 110)
                    shape(tint)
                }
                shape(ringStyle(withAlpha(accent, 235), 4f))
                textCol = accent
            }
            2 -> { // circulo grande relleno teñido, borde claro, letra blanca
                tint.color = if (face) withAlpha(accent, if (pressed) 200 else 120) else Color.argb(if (pressed) 150 else 85, 255, 255, 255)
                shape(tint)
                shape(ringStyle(Color.argb(210, 255, 255, 255), 3f))
            }
            4 -> { // PS2: boton oscuro con relieve; simbolo de color en los de cara
                val light = if (pressed) Color.rgb(150, 154, 165) else Color.rgb(98, 102, 112)
                val dark = Color.rgb(24, 26, 32)
                gradPaint.shader = if (face) {
                    val r = c.rect.height() / 2
                    android.graphics.RadialGradient(c.rect.centerX() - r * 0.3f, c.rect.centerY() - r * 0.35f, r * 1.4f,
                        light, dark, android.graphics.Shader.TileMode.CLAMP)
                } else {
                    android.graphics.LinearGradient(c.rect.left, c.rect.top, c.rect.left, c.rect.bottom,
                        light, dark, android.graphics.Shader.TileMode.CLAMP)
                }
                shape(gradPaint)
                gradPaint.shader = null
                shape(ringStyle(Color.argb(205, 190, 195, 205), 3f))
                textCol = accent
            }
            3 -> { // oscuro translucido con borde claro
                tint.color = Color.argb(if (pressed) 200 else 120, 25, 28, 38)
                shape(tint)
                shape(ringStyle(Color.argb(200, 255, 255, 255), 3f))
            }
            else -> {
                val paint = if (pressed) fillPressed else fill
                shape(paint)
                shape(stroke)
            }
        }
        text.color = textCol
        text.textSize = c.rect.height() * if (face) 0.45f else 0.42f
        canvas.drawText(label, c.rect.centerX(), c.rect.centerY() + text.textSize / 3, text)
    }

    private fun isPressed(key: Int) = (pressedKeys[key] ?: 0) > 0

    /** Keys under a touch point. The d-pad has a generous radius and supports diagonals. */
    private fun keysAt(x: Float, y: Float): Set<Int> {
        val dx = x - dpadCx
        val dy = y - dpadCy
        val dist = hypot(dx, dy)
        if (dist <= dpadR * 1.25f && dist > dpadR * 0.15f) {
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
            val keys = mutableSetOf<Int>()
            if (angle in -67.5..67.5) keys += KeyEvent.KEYCODE_DPAD_RIGHT
            if (angle >= 112.5 || angle <= -112.5) keys += KeyEvent.KEYCODE_DPAD_LEFT
            if (angle in 22.5..157.5) keys += KeyEvent.KEYCODE_DPAD_DOWN
            if (angle in -157.5..-22.5) keys += KeyEvent.KEYCODE_DPAD_UP
            return keys
        }
        // Slightly enlarged hit boxes make the buttons easier to hit without looking.
        val hit = controls.firstOrNull { c ->
            val pad = c.rect.height() * 0.2f
            x >= c.rect.left - pad && x <= c.rect.right + pad && y >= c.rect.top - pad && y <= c.rect.bottom + pad
        }
        return hit?.let { setOf(it.keyCode) } ?: emptySet()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return editTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val stick = sticks.firstOrNull { it.pointerId < 0 && it.contains(event.getX(i), event.getY(i)) }
                if (stick != null) {
                    stick.pointerId = event.getPointerId(i)
                    moveStick(stick, event.getX(i), event.getY(i))
                    return true
                }
                val keys = keysAt(event.getX(i), event.getY(i))
                if (MENU in keys) {
                    onMenu()
                    return true
                }
                if (keys.isEmpty() && onPass != null && passId < 0) {
                    passId = event.getPointerId(i)
                    passX = event.getX(i); passY = event.getY(i)
                    forward(MotionEvent.ACTION_DOWN, passX, passY)
                    return true
                }
                update(event.getPointerId(i), keys)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                if (id == passId) {
                    passX = event.getX(i); passY = event.getY(i)
                    forward(MotionEvent.ACTION_MOVE, passX, passY)
                    continue
                }
                val stick = sticks.firstOrNull { it.pointerId == id }
                if (stick != null) moveStick(stick, event.getX(i), event.getY(i))
                else if (id in held) update(id, keysAt(event.getX(i), event.getY(i)) - MENU)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == passId) {
                    forward(MotionEvent.ACTION_UP, event.getX(event.actionIndex), event.getY(event.actionIndex))
                    passId = -1
                    return true
                }
                val stick = sticks.firstOrNull { it.pointerId == id }
                if (stick != null) releaseStick(stick) else update(id, emptySet())
            }
            MotionEvent.ACTION_CANCEL -> {
                if (passId >= 0) { forward(MotionEvent.ACTION_UP, passX, passY); passId = -1 }
                releaseAll()
            }
        }
        return true
    }

    private fun update(pointerId: Int, keys: Set<Int>) {
        val before = held[pointerId] ?: emptySet()
        if (before == keys) return
        for (k in before - keys) {
            val n = (pressedKeys[k] ?: 1) - 1
            pressedKeys[k] = n
            if (n == 0) onKey(KeyEvent.ACTION_UP, k)
        }
        for (k in keys - before) {
            val n = (pressedKeys[k] ?: 0) + 1
            pressedKeys[k] = n
            if (n == 1) onKey(KeyEvent.ACTION_DOWN, k)
        }
        if (keys.isEmpty()) held.remove(pointerId) else held[pointerId] = keys
        invalidate()
    }

    /** Stick deflection from the finger's offset; full tilt at 60% of the radius, like the knob's travel. */
    private fun moveStick(stick: Stick, px: Float, py: Float) {
        var x = (px - stick.cx) / (stick.r * 0.6f)
        var y = (py - stick.cy) / (stick.r * 0.6f)
        val len = hypot(x, y)
        if (len > 1f) {
            x /= len
            y /= len
        }
        stick.x = x
        stick.y = y
        onAnalog(stick.right, x, y)
        if (!stick.right && stickDpad) stickToDpad(x, y)
        invalidate()
    }

    private var stickDpad = layoutPrefs.getBoolean("o:dpad", true)
    private var deadzone = layoutPrefs.getFloat("o:dead", 0.5f)

    private fun stickToDpad(x: Float, y: Float) {
        val t = deadzone
        val keys = mutableSetOf<Int>()
        if (x < -t) keys += KeyEvent.KEYCODE_DPAD_LEFT
        if (x > t) keys += KeyEvent.KEYCODE_DPAD_RIGHT
        if (y < -t) keys += KeyEvent.KEYCODE_DPAD_UP
        if (y > t) keys += KeyEvent.KEYCODE_DPAD_DOWN
        update(-100, keys)
    }

    private fun optRects(): List<RectF> {
        val p = panelRects()[0]
        val u = min(width, height) / 10f
        val bw = u * 3.4f
        val gap = u * 0.2f
        val x0 = width / 2f - (bw * 2 + gap) / 2f
        val y0 = p.bottom + gap
        return (0 until 2).map { RectF(x0 + it * (bw + gap), y0, x0 + it * (bw + gap) + bw, y0 + p.height()) }
    }

    private fun optLabel(i: Int): String =
        if (i == 0) "Stick>Cruz: " + (if (stickDpad) "ON" else "OFF")
        else "Zona: " + Math.round(deadzone * 100) + "%"

    private fun optPress(i: Int) {
        val e = layoutPrefs.edit()
        if (i == 0) {
            stickDpad = !stickDpad
            e.putBoolean("o:dpad", stickDpad)
        } else {
            deadzone = if (deadzone >= 0.69f) 0.3f else deadzone + 0.1f
            e.putFloat("o:dead", deadzone)
        }
        e.apply()
    }

    private fun drawOptions(canvas: Canvas) {
        val r = optRects()
        panelText.textSize = r[0].height() * 0.38f
        r.forEachIndexed { i, rc ->
            canvas.drawRoundRect(rc, rc.height() / 2, rc.height() / 2, panelFill)
            canvas.drawText(optLabel(i), rc.centerX(), rc.centerY() + panelText.textSize / 3, panelText)
        }
    }

    private fun releaseStick(stick: Stick) {
        stick.pointerId = -1
        stick.x = 0f
        stick.y = 0f
        onAnalog(stick.right, 0f, 0f)
        if (!stick.right) update(-100, emptySet())
        invalidate()
    }

    fun releaseAll() {
        for ((k, n) in pressedKeys) if (n > 0) onKey(KeyEvent.ACTION_UP, k)
        pressedKeys.clear()
        held.clear()
        sticks.filter { it.pointerId >= 0 || it.x != 0f || it.y != 0f }.forEach(::releaseStick)
        invalidate()
    }

    fun saveControlStyle(style: Int) {
        controlStyle = style
        try {
            val prefs = context.getSharedPreferences("rainey_controls", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("control_style", style).apply()
        } catch (e: Exception) {}
    }
}
