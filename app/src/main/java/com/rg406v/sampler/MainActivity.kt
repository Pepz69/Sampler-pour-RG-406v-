package com.rg406v.sampler

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rg406v.sampler.ui.SamplerApp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class MainActivity : ComponentActivity() {

    private lateinit var state: SamplerState
    private var pendingPad = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Pas grave : le sample marchera pour cette session seulement.
            }
            state.loadFromUri(pendingPad, uri)
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        state.onMicResult(ok)
    }

    // Correspondance bouton → pad (0..7). Avec L1 maintenu : +8.
    private val padKeys = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0,
        KeyEvent.KEYCODE_BUTTON_B to 1,
        KeyEvent.KEYCODE_BUTTON_X to 2,
        KeyEvent.KEYCODE_BUTTON_Y to 3,
        KeyEvent.KEYCODE_DPAD_UP to 4,
        KeyEvent.KEYCODE_DPAD_RIGHT to 5,
        KeyEvent.KEYCODE_DPAD_DOWN to 6,
        KeyEvent.KEYCODE_DPAD_LEFT to 7,
    )
    private val otherKeys = setOf(
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    )
    private val held = HashMap<Int, Int>() // keyCode → pad déclenché

    // Sticks, gâchettes et croix en axe (certaines manettes n'envoient pas de KeyEvent pour la croix).
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var l2Down = false; private var r2Down = false
    private val hatState = BooleanArray(4)
    private var hatActive = true

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            state.stickAdjust(lx, ly, rx, ry, 0.016f)
            handler.postDelayed(this, 16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = SamplerState(applicationContext)
        state.requestPick = { pad ->
            pendingPad = pad
            picker.launch(arrayOf("audio/*", "application/octet-stream"))
        }

        state.requestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { SamplerApp(state) }
    }

    override fun onResume() {
        super.onResume()
        state.resume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        held.clear()
        state.pause()
        super.onPause()
    }

    // ------------------------------------------------------------ boutons

    private fun handleButton(code: Int, down: Boolean, repeat: Boolean): Boolean {
        val slot = padKeys[code]
        val known = slot != null || code in otherKeys
        if (!known) return false
        if (down && repeat) return true

        if (slot != null) {
            if (down) {
                if (!held.containsKey(code)) {
                    val idx = slot + if (state.bankHeld) 8 else 0
                    if (state.punchHeld) {
                        // R1 maintenu : les boutons déclenchent les effets punch-in (codés en négatif)
                        if (idx < 12) { held[code] = -(idx + 1); state.punchDown(idx) }
                    } else {
                        held[code] = idx
                        state.padDown(idx)
                    }
                }
            } else {
                held.remove(code)?.let { v -> if (v < 0) state.punchUp(-v - 1) else state.padUp(v) }
            }
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_BUTTON_L1 -> state.bankHeld = down
            KeyEvent.KEYCODE_BUTTON_R1 -> state.punchHeld = down
            KeyEvent.KEYCODE_BUTTON_L2 -> if (down) {
                when (state.tab) {
                    TAB_FX, TAB_PADFX -> state.selectFx(-1)
                    TAB_SEQ, TAB_PIANO -> state.nextPattern(-1)
                }
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> if (down) {
                when (state.tab) {
                    TAB_FX, TAB_PADFX -> state.selectFx(1)
                    TAB_PAD -> state.toggleRecord()
                    TAB_SEQ, TAB_PIANO -> state.nextPattern(1)
                }
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> if (down) state.moveFx(-1)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (down) state.moveFx(1)
            // R1 + Start : arrêt d'urgence de tous les sons ; sinon Start = action de l'onglet
            KeyEvent.KEYCODE_BUTTON_START -> if (down) { if (state.punchHeld) state.panic() else state.startPressed() }
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (down) state.toggleTab()
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) hatActive = false
        if (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) {
            val used = handleButton(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount > 0)
            if (used) return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------ axes

    private fun dz(v: Float): Float {
        val a = abs(v)
        return if (a < 0.15f) 0f else sign(v) * (a - 0.15f) / 0.85f
    }

    private fun pickBigger(a: Float, b: Float) = if (abs(a) >= abs(b)) a else b

    private fun hat(code: Int, pressed: Boolean, idx: Int) {
        if (pressed != hatState[idx]) {
            hatState[idx] = pressed
            handleButton(code, pressed, false)
        }
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK != 0 && ev.action == MotionEvent.ACTION_MOVE) {
            lx = dz(ev.getAxisValue(MotionEvent.AXIS_X))
            ly = -dz(ev.getAxisValue(MotionEvent.AXIS_Y))
            rx = dz(pickBigger(ev.getAxisValue(MotionEvent.AXIS_Z), ev.getAxisValue(MotionEvent.AXIS_RX)))
            ry = -dz(pickBigger(ev.getAxisValue(MotionEvent.AXIS_RZ), ev.getAxisValue(MotionEvent.AXIS_RY)))

            val lt = max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rt = max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            if (lt > 0.6f && !l2Down) { l2Down = true; handleButton(KeyEvent.KEYCODE_BUTTON_L2, true, false) }
            else if (lt < 0.3f) l2Down = false
            if (rt > 0.6f && !r2Down) { r2Down = true; handleButton(KeyEvent.KEYCODE_BUTTON_R2, true, false) }
            else if (rt < 0.3f) r2Down = false

            if (hatActive) {
                val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
                hat(KeyEvent.KEYCODE_DPAD_LEFT, hx < -0.5f, 0)
                hat(KeyEvent.KEYCODE_DPAD_RIGHT, hx > 0.5f, 1)
                hat(KeyEvent.KEYCODE_DPAD_UP, hy < -0.5f, 2)
                hat(KeyEvent.KEYCODE_DPAD_DOWN, hy > 0.5f, 3)
            }
            return true
        }
        return super.onGenericMotionEvent(ev)
    }
}
