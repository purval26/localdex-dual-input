package com.localdex

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.localdex.scrcpy.ScrcpySession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fullscreen interactive view of the DeX display.
 *
 * Touch is forwarded to the mirrored display; the system Back gesture/button is
 * forwarded as a DeX Back key. Closing happens through the ✕ button (with
 * confirmation) or the persistent notification's Stop action.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var closeButton: ImageButton

    private var surfaceReady = false
    private var surfaceGivenToDecoder = false

    private val session: ScrcpySession?
        get() = ScrcpySession.current

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeSession = session
        if (activeSession == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_viewer)
        root = findViewById(R.id.viewerRoot)
        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.viewerStatus)
        closeButton = findViewById(R.id.closeButton)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                surfaceGivenToDecoder = false
                offerSurface()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surfaceGivenToDecoder = false
                session?.videoDecoder?.clearSurface()
            }
        })

        surfaceView.setOnTouchListener { view, event ->
            val s = session ?: return@setOnTouchListener false
            s.controller?.forwardMotionEvent(
                event, view.width, view.height, s.videoWidth, s.videoHeight
            )
            true
        }
        
        makeCloseButtonDraggable()

        // Fold/unfold and rotation change the container size without recreating the
        // activity (configChanges); keep the surface at the video's aspect ratio.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                val s = session ?: return@addOnLayoutChangeListener
                if (s.videoWidth > 0) applyAspectRatio(s.videoWidth, s.videoHeight)
            }
        }

        lifecycleScope.launch {
            activeSession.state.collectLatest { state ->
                when (state) {
                    is ScrcpySession.State.Starting -> statusText.text = state.message
                    is ScrcpySession.State.Running -> {
                        statusText.visibility = View.GONE
                        applyAspectRatio(state.videoWidth, state.videoHeight)
                        offerSurface()
                    }
                    is ScrcpySession.State.Stopped -> {
                        if (state.error != null) {
                            AlertDialog.Builder(this@ViewerActivity)
                                .setTitle("Session ended")
                                .setMessage(state.error)
                                .setPositiveButton("OK") { _, _ -> finish() }
                                .setOnDismissListener { finish() }
                                .show()
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
    }
        // Physical mouse input is delivered as generic MotionEvents rather than
        // normal touchscreen events. Keep this path separate from touch so both
        // input methods work at the same time.
        //
        // SOURCE_MOUSE includes movement, hover, buttons and wheel scrolling.
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                val s = session

                if (s != null) {
                    val handled = s.controller?.forwardMouseEvent(
                        event,
                        surfaceView.width,
                        surfaceView.height,
                        s.videoWidth,
                        s.videoHeight
                    ) == true

                    if (handled) {
                        return true
                    }
                }
            }

            return super.dispatchGenericMotionEvent(event)
        }
    /** Hands the surface to the decoder once both exist. Idempotent. */
    private fun offerSurface() {
        if (!surfaceReady || surfaceGivenToDecoder) return
        val decoder = session?.videoDecoder ?: return
        decoder.setSurface(surfaceView.holder.surface)
        surfaceGivenToDecoder = true
    }

    /** Sizes the SurfaceView to exactly the video aspect ratio, centered. */
    private fun applyAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) return
        root.post {
            val containerWidth = root.width
            val containerHeight = root.height
            if (containerWidth == 0 || containerHeight == 0) return@post

            val scale = minOf(
                containerWidth.toFloat() / videoWidth,
                containerHeight.toFloat() / videoHeight
            )
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            params.width = (videoWidth * scale).toInt()
            params.height = (videoHeight * scale).toInt()
            params.gravity = android.view.Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    /**
     * Tap: stop dialog. Tap-and-hold: drag the button anywhere — it sits where DeX
     * draws its window controls, so it has to be able to get out of the way.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeCloseButtonDraggable() {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        val startDrag = Runnable {
            dragging = true
            closeButton.alpha = 0.9f
            closeButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        closeButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTx = view.translationX
                    startTy = view.translationY
                    dragging = false
                    view.postDelayed(startDrag, 350)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging) {
                        view.translationX = startTx + dx
                        view.translationY = startTy + dy
                    } else if (dx * dx + dy * dy > slop * slop) {
                        // Moved before the hold completed: not a tap, not a drag.
                        view.removeCallbacks(startDrag)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(startDrag)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging) {
                        dragging = false
                        view.alpha = 0.5f
                    } else if (dx * dx + dy * dy <= slop * slop) {
                        confirmStop()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(startDrag)
                    if (dragging) {
                        dragging = false
                        view.alpha = 0.5f
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun confirmStop() {
        val displayId = session?.displayId ?: -1
        val displayNote = if (displayId >= 0) "\n\nVirtual display id: $displayId" else ""
        AlertDialog.Builder(this)
            .setTitle("Stop DeX?")
            .setMessage(
                "This ends the session and removes the virtual display. You can also " +
                    "leave with Home and come back via the notification.$displayNote"
            )
            .setPositiveButton("Stop") { _, _ ->
                DexService.stop(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Forward Back (hardware key and gesture alike) to DeX instead of leaving the
    // viewer; leaving is done via the ✕ button, Home, or the notification.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (session == null) finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
