package com.chetbox.mousecursor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.Executors

class ShizukuInputInjector(private val context: Context) {

    private var iInputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    // We maintain a consistent event timeline for drags/holds
    private var downTime: Long = 0L

    // The silver bullet for preventing Android UI freezing during continuous drags:
    // Pushing all Shizuku IPC reflection calls off the main thread entirely.
    private val injectionExecutor = Executors.newSingleThreadExecutor()

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
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val inputBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val shizukuBinder = ShizukuBinderWrapper(inputBinder)
            val iInputManagerStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = iInputManagerStubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            iInputManager = asInterfaceMethod.invoke(null, shizukuBinder)
            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager")
            injectInputEventMethod = iInputManagerClass.getDeclaredMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e("ShizukuInputInjector", "Setup failed: ${e.message}")
        }
    }

    private fun injectEventAsync(event: MotionEvent) {
        injectionExecutor.execute {
            if (iInputManager == null || injectInputEventMethod == null) {
                setupIInputManager()
                if (iInputManager == null) return@execute
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try { setDisplayIdMethod?.invoke(event, 0) } catch (e: Exception) {}
            }
            try {
                // INJECT_INPUT_EVENT_MODE_ASYNC = 0
                injectInputEventMethod?.invoke(iInputManager, event, 0)
            } catch (e: Exception) {
                Log.e("ShizukuInputInjector", "Injection failed: ${e.message}")
            } finally {
                event.recycle()
            }
        }
    }

    fun injectMouseMove(x: Float, y: Float, buttonState: Int = 0) {
        val eventTime = SystemClock.uptimeMillis()

        // If a finger is currently holding down a drag, we inject ACTION_MOVE to pull the item.
        // The buttonState must be provided, or Android will drop the move entirely.
        if (downTime > 0) {
            val props = Array(1) { MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
            val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

            val moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 1, props, coords, 0, buttonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)
            injectEventAsync(moveEvent)
        } else {
            // Revert back to injecting HOVER so the OS hardware cursor stays perfectly synced
            // with our logical cursor, meaning clicks will never have an invisible offset.
            val hoverEvent = MotionEvent.obtain(
                eventTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, x, y, 0
            )
            hoverEvent.source = InputDevice.SOURCE_MOUSE

            // Set deviceId via reflection since the field is not public in all Android versions
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val setDeviceIdMethod = MotionEvent::class.java.getMethod("setDeviceId", Int::class.javaPrimitiveType)
                    setDeviceIdMethod.invoke(hoverEvent, 1337)
                } catch (e: Exception) {}
            }

            injectEventAsync(hoverEvent)
        }
    }

    fun injectMouseDown(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        // Important: When starting a drag, we also need to sync the OS cursor position
        injectMouseMove(x, y, buttonState)

        downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

        // MOUSE injection MUST have ACTION_BUTTON_PRESS or the OS will completely ignore drag commands.
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 1, props, coords, 0, buttonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)
        val buttonPressEvent = MotionEvent.obtain(downTime, eventTime + 5, MotionEvent.ACTION_BUTTON_PRESS, 1, props, coords, 0, buttonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val setActionButtonMethod = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
                setActionButtonMethod.invoke(buttonPressEvent, buttonState)
            } catch (e: Exception) {}
        }

        injectEventAsync(downEvent)
        injectEventAsync(buttonPressEvent)
    }

    fun injectMouseUp(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        if (downTime == 0L) downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

        // MOUSE injection MUST have ACTION_BUTTON_RELEASE.
        val buttonReleaseEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_BUTTON_RELEASE, 1, props, coords, 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime + 5, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val setActionButtonMethod = MotionEvent::class.java.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType)
                setActionButtonMethod.invoke(buttonReleaseEvent, buttonState)
            } catch (e: Exception) {}
        }

        injectEventAsync(buttonReleaseEvent)
        injectEventAsync(upEvent)

        downTime = 0L // Reset drag state
    }

    fun injectMouseClick(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        // Sync the hardware cursor location right before clicking
        injectMouseMove(x, y, buttonState)
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
            1, props, coords, 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0
        )

        injectEventAsync(scrollEvent)
    }
}
