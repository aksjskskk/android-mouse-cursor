package com.chetbox.mousecursor

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ShizukuInputInjector(private val context: Context) {

    // --- NATIVE IINPUTMANAGER REFLECTION INJECTION --- //
    // The previous implementation used `sh -c input tap` which is extremely reliable but lacks
    // precise control for continuous dragging (like double-tap-to-drag), because `input swipe`
    // does not allow dynamic coordinate updates.
    //
    // Here we restore the powerful `IInputManager` reflection method via `ShizukuBinderWrapper`,
    // but correctly apply `INJECT_INPUT_EVENT_MODE_ASYNC` to avoid the deadlocks we experienced earlier.

    private var iInputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    // We maintain a consistent event timeline for drags/holds
    private var downTime: Long = 0L

    init {
        setupIInputManager()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (e: Exception) {
                // Ignore if method not found
            }
        }
    }

    private fun setupIInputManager() {
        if (!Shizuku.pingBinder()) {
            Log.e("ShizukuInputInjector", "Shizuku is not running!")
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("ShizukuInputInjector", "Shizuku permission not granted!")
            return
        }

        try {
            // Get the raw input binder from SystemServiceHelper
            val inputBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            // Wrap the binder with Shizuku's proxy, which executes remote calls with Shizuku's privileges
            val shizukuBinder = ShizukuBinderWrapper(inputBinder)

            // Get IInputManager.Stub
            val iInputManagerStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = iInputManagerStubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)

            // Generate the IInputManager proxy object using the wrapped binder
            iInputManager = asInterfaceMethod.invoke(null, shizukuBinder)

            // Find the injectInputEvent method on the IInputManager proxy
            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager")
            injectInputEventMethod = iInputManagerClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

        } catch (e: Exception) {
            Log.e("ShizukuInputInjector", "Failed to setup IInputManager via reflection: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun injectEvent(event: MotionEvent) {
        if (iInputManager == null || injectInputEventMethod == null) {
            Log.w("ShizukuInputInjector", "IInputManager not initialized, retrying setup...")
            setupIInputManager()
            if (iInputManager == null) return
        }

        // Set displayId explicitly to DEFAULT_DISPLAY (0) so rotation mapping works correctly.
        // Without this, landscape injected coordinates out of portrait bounds get dropped.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                setDisplayIdMethod?.invoke(event, 0) // Display.DEFAULT_DISPLAY = 0
            } catch (e: Exception) {
                // Ignore
            }
        }

        try {
            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            // We use async injection for all events (moves, scrolls, clicks) to avoid deadlocking
            // the main thread if the injected event targets our own app's window surface.
            injectInputEventMethod?.invoke(iInputManager, event, 0)
        } catch (e: Exception) {
            Log.e("ShizukuInputInjector", "Failed to inject event via Shizuku IInputManager: ${e.message}")
            e.printStackTrace()
        }
    }

    fun injectMouseMove(x: Float, y: Float) {
        val eventTime = SystemClock.uptimeMillis()

        // If a finger is currently holding down a drag, we inject ACTION_MOVE to pull the item.
        if (downTime > 0) {
            val props = Array(1) { MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
            val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

            val moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 1, props, coords, 0, MotionEvent.BUTTON_PRIMARY, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            injectEvent(moveEvent)
            moveEvent.recycle()
        } else {
            val hoverEvent = MotionEvent.obtain(
                eventTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, x, y, 0
            )
            hoverEvent.source = InputDevice.SOURCE_MOUSE
            injectEvent(hoverEvent)
            hoverEvent.recycle()
        }
    }

    fun injectMouseDown(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        // Important: When starting a drag, we also need to sync the OS cursor position
        injectMouseMove(x, y)

        downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 1, props, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        val buttonPressEvent = MotionEvent.obtain(downTime, eventTime + 5, MotionEvent.ACTION_BUTTON_PRESS, 1, props, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val setActionButtonMethod = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
                setActionButtonMethod.invoke(buttonPressEvent, buttonState)
            } catch (e: Exception) {}
        }

        injectEvent(downEvent)
        injectEvent(buttonPressEvent)
        downEvent.recycle()
        buttonPressEvent.recycle()
    }

    fun injectMouseUp(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        if (downTime == 0L) downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

        val buttonReleaseEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_BUTTON_RELEASE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime + 5, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val setActionButtonMethod = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
                setActionButtonMethod.invoke(buttonReleaseEvent, buttonState)
            } catch (e: Exception) {}
        }

        injectEvent(buttonReleaseEvent)
        injectEvent(upEvent)
        buttonReleaseEvent.recycle()
        upEvent.recycle()

        downTime = 0L // Reset drag state
    }

    fun injectMouseClick(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        // Sync the hardware cursor location right before clicking,
        // as we no longer constantly inject hover moves to save performance!
        injectMouseMove(x, y)
        injectMouseDown(x, y, buttonState)
        injectMouseUp(x, y, buttonState)
    }

    fun injectMouseScroll(x: Float, y: Float, scrollY: Float) {
        // Sync hardware pointer
        injectMouseMove(x, y)

        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) {
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        }
        val coords = Array(1) {
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY)
            }
        }

        val scrollEvent = MotionEvent.obtain(
            eventTime, eventTime, MotionEvent.ACTION_SCROLL,
            1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )

        injectEvent(scrollEvent)
        scrollEvent.recycle()
    }
}
