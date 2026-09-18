package com.localdex.scrcpy

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Sends scrcpy control messages (v4.1 wire format) and drains device messages.
 *
 * Touch input is translated to MOUSE events (scrcpy's "sdk mouse" model:
 * pointerId -1, hover moves, button state), not finger touches. This matters:
 * One UI 9's desktop-mode shell crashes (SystemUI NPE in
 * DesktopModeVisualIndicator) when a window caption is dragged by a
 * touchscreen pointer, while the mouse drag path works — it's the same reason
 * window dragging works from desktop scrcpy.
 *
 * Gestures: one finger = mouse click/drag; two fingers = scroll wheel.
 *
 * Writes happen on a dedicated thread so touch handling never blocks the UI
 * thread on a socket.
 */
class Controller(
    private val output: OutputStream,
    private val input: InputStream,
) {
    companion object {
        private const val TAG = "Controller"

        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3

        private const val POINTER_ID_MOUSE = -1L
        private const val BUTTON_PRIMARY = 1 // MotionEvent.BUTTON_PRIMARY

        /** Finger travel (in video px) equal to one scroll-wheel notch. */
        private const val SCROLL_PX_PER_TICK = 64f
    }

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = true

    private val senderThread = Thread({
        try {
            while (running) {
                val message = queue.take()
                output.write(message)
                output.flush()
            }
        } catch (e: InterruptedException) {
            // stop() interrupts us; done.
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Control socket write failed", e)
        }
    }, "localdex-control-send")

    // The server pushes device messages (clipboard etc.) on this socket; they must be
    // consumed or the server's writer eventually blocks. We have no use for them.
    private val drainThread = Thread({
        val buffer = ByteArray(4096)
        try {
            while (running && input.read(buffer) != -1) {
                // Discard.
            }
        } catch (e: IOException) {
            // Socket closed; done.
        }
    }, "localdex-control-drain")

    fun start() {
        senderThread.start()
        drainThread.start()
    }

    fun stop() {
        running = false
        senderThread.interrupt()
    }

    /** Sends a full key press (down + up) to the mirrored display's focus. */
    fun sendKeyPress(keycode: Int) {
        sendKey(KeyEvent.ACTION_DOWN, keycode)
        sendKey(KeyEvent.ACTION_UP, keycode)
    }

    private fun sendKey(action: Int, keycode: Int) {
        val buffer = ByteBuffer.allocate(14)
        buffer.put(TYPE_INJECT_KEYCODE.toByte())
        buffer.put(action.toByte())
        buffer.putInt(keycode)
        buffer.putInt(0) // repeat
        buffer.putInt(0) // metaState
        queue.offer(buffer.array())
    }

    private fun sendMouse(
        action: Int,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) {
        val buffer = ByteBuffer.allocate(32)
        buffer.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buffer.put(action.toByte())
        buffer.putLong(POINTER_ID_MOUSE)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(pressureToU16FixedPoint(pressure))
        buffer.putInt(actionButton)
        buffer.putInt(buttons)
        queue.offer(buffer.array())
    }
    private fun sendScroll(
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int = 0,
    ) {
        val buffer = ByteBuffer.allocate(21)
        buffer.put(TYPE_INJECT_SCROLL_EVENT.toByte())
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(scrollToI16FixedPoint(hScroll))
        buffer.putShort(scrollToI16FixedPoint(vScroll))
        buffer.putInt(buttons)
        queue.offer(buffer.array())
    }
    
    // -- Gesture translation ----------------------------------------------------------

    private enum class Gesture { NONE, MOUSE, SCROLL, DONE }

    private var gesture = Gesture.NONE
    private var lastX = 0
    private var lastY = 0

    /**
     * Forwards a [MotionEvent] from a view of size [viewWidth]x[viewHeight] that shows
     * the video letterbox-free (the viewer sizes its SurfaceView to the exact aspect
     * ratio, so a plain scale maps view space to video space).
     */
    fun forwardMotionEvent(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return

        fun videoX(pointerIndex: Int) =
            (event.getX(pointerIndex) * videoWidth / viewWidth).toInt().coerceIn(0, videoWidth - 1)

        fun videoY(pointerIndex: Int) =
            (event.getY(pointerIndex) * videoHeight / viewHeight).toInt().coerceIn(0, videoHeight - 1)

        fun centroidX(): Int {
            var sum = 0
            for (i in 0 until event.pointerCount) sum += videoX(i)
            return sum / event.pointerCount
        }

        fun centroidY(): Int {
            var sum = 0
            for (i in 0 until event.pointerCount) sum += videoY(i)
            return sum / event.pointerCount
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.MOUSE
                lastX = videoX(0)
                lastY = videoY(0)
                // Move the pointer to the spot first, like a real mouse would.
                sendMouse(MotionEvent.ACTION_HOVER_MOVE, lastX, lastY, videoWidth, videoHeight, 0f, 0, 0)
                sendMouse(MotionEvent.ACTION_DOWN, lastX, lastY, videoWidth, videoHeight, 1f, BUTTON_PRIMARY, BUTTON_PRIMARY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture == Gesture.MOUSE) {
                    // Second finger: this is a scroll, not a drag — release the button.
                    sendMouse(MotionEvent.ACTION_UP, lastX, lastY, videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                    gesture = Gesture.SCROLL
                    lastX = centroidX()
                    lastY = centroidY()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.MOUSE -> {
                        lastX = videoX(0)
                        lastY = videoY(0)
                        sendMouse(MotionEvent.ACTION_MOVE, lastX, lastY, videoWidth, videoHeight, 1f, 0, BUTTON_PRIMARY)
                    }
                    Gesture.SCROLL -> {
                        val cx = centroidX()
                        val cy = centroidY()
                        val dx = cx - lastX
                        val dy = cy - lastY
                        if (dx != 0 || dy != 0) {
                            sendScroll(
                                cx, cy, videoWidth, videoHeight,
                                dx / SCROLL_PX_PER_TICK,
                                dy / SCROLL_PX_PER_TICK,
                            )
                            lastX = cx
                            lastY = cy
                        }
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.SCROLL && event.pointerCount <= 2) {
                    // Dropping back to one finger; ignore the remainder of the gesture.
                    gesture = Gesture.DONE
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, videoX(0), videoY(0), videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                }
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, lastX, lastY, videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                }
                gesture = Gesture.NONE
            }
        }
    }
        /**
     * Forwards a real physical mouse MotionEvent.
     *
     * Physical mouse events are kept separate from touchscreen gestures so
     * both input methods continue to work simultaneously.
     */
    fun forwardMouseEvent(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): Boolean {
        if (viewWidth == 0 || viewHeight == 0 ||
            videoWidth == 0 || videoHeight == 0
        ) {
            return false
        }

        fun videoX(): Int =
            (event.x * videoWidth / viewWidth)
                .toInt()
                .coerceIn(0, videoWidth - 1)

        fun videoY(): Int =
            (event.y * videoHeight / viewHeight)
                .toInt()
                .coerceIn(0, videoHeight - 1)

        val x = videoX()
        val y = videoY()
        val buttons = event.buttonState

        when (event.actionMasked) {

            // Mouse movement without buttons = hover.
            // Movement while a button is held = drag.
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_MOVE -> {
                sendMouse(
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    videoWidth,
                    videoHeight,
                    if (buttons != 0) 1f else 0f,
                    0,
                    buttons,
                )
                return true
            }

            // Left / right / middle / side button press.
            MotionEvent.ACTION_BUTTON_PRESS -> {
                sendMouse(
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    videoWidth,
                    videoHeight,
                    1f,
                    event.actionButton,
                    buttons,
                )
                return true
            }

            // Button release.
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                sendMouse(
                    MotionEvent.ACTION_UP,
                    x,
                    y,
                    videoWidth,
                    videoHeight,
                    0f,
                    event.actionButton,
                    buttons,
                )
                return true
            }

            // Physical mouse wheel.
            MotionEvent.ACTION_SCROLL -> {
                val hScroll =
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL)

                val vScroll =
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL)

                if (hScroll != 0f || vScroll != 0f) {
                    sendScroll(
                        x,
                        y,
                        videoWidth,
                        videoHeight,
                        hScroll,
                        vScroll,
                        buttons,
                    )
                }

                return true
            }
        }

        return false
    }

    private fun pressureToU16FixedPoint(pressure: Float): Short {
        val clamped = pressure.coerceIn(0f, 1f)
        return if (clamped == 1f) 0xffff.toShort() else (clamped * 0x10000).toInt().toShort()
    }

    /** The wire encodes scroll values as i16 fixed point over the range [-16, 16]. */
    private fun scrollToI16FixedPoint(value: Float): Short {
        val clamped = (value / 16f).coerceIn(-1f, 1f)
        return if (clamped == 1f) 0x7fff.toShort() else (clamped * 0x8000).toInt().coerceIn(-0x8000, 0x7fff).toShort()
    }
}
