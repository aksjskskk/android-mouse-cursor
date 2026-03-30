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

    private var iInputManager: Any? = null
    private var injectInputEventMethod: Method? = null

    init {
        setupIInputManager()
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

        try {
            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            injectInputEventMethod?.invoke(iInputManager, event, 0)
        } catch (e: Exception) {
            Log.e("ShizukuInputInjector", "Failed to inject event via Shizuku IInputManager: ${e.message}")
            e.printStackTrace()
        }
    }

    fun injectClick(x: Float, y: Float, leftClick: Boolean = true) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        // Create ACTION_DOWN event
        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0
        )
        downEvent.source = InputDevice.SOURCE_TOUCHSCREEN

        if (leftClick) {
            // Standard Left Click
            val upEvent = MotionEvent.obtain(
                downTime, eventTime + 50, MotionEvent.ACTION_UP, x, y, 0
            )
            upEvent.source = InputDevice.SOURCE_TOUCHSCREEN

            injectEvent(downEvent)
            injectEvent(upEvent)

            downEvent.recycle()
            upEvent.recycle()
        } else {
            // Right Click (Long Press)
            val longPressUpTime = eventTime + 1000 // 1 second long press
            val upEventLong = MotionEvent.obtain(
                downTime, longPressUpTime, MotionEvent.ACTION_UP, x, y, 0
            )
            upEventLong.source = InputDevice.SOURCE_TOUCHSCREEN

            injectEvent(downEvent)

            // We need a small delay here if we are literally injecting touch events,
            // but the `eventTime` difference on the UP event *might* be enough for the OS
            // to register it as a long press. To be safe, we will execute the UP event
            // slightly delayed to match the timestamp.
            Thread {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    // Ignore
                }
                injectEvent(upEventLong)
                downEvent.recycle()
                upEventLong.recycle()
            }.start()
        }
    }
}
