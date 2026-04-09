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
    private var downTime: Long = 0L

    // الحل الجذري الأول: طابور خلفي مستقل (يمنع التجمد والاختناق تماماً)
    private val injectionExecutor = Executors.newSingleThreadExecutor()

    init {
        setupIInputManager()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (e: Exception) {}
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
                injectInputEventMethod?.invoke(iInputManager, event, 0)
            } catch (e: Exception) {
                Log.e("ShizukuInputInjector", "Injection failed: ${e.message}")
            } finally {
                event.recycle() // إعادة تدوير الحدث هنا بعد الانتهاء منه
            }
        }
    }

    // الحل الجذري الثاني: SOURCE_TOUCHSCREEN لتدمير المنطقة الميتة + size = 0.01f لدقة الإبرة
    private fun createPointerCoords(x: Float, y: Float): Array<MotionEvent.PointerCoords> {
        return Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 0.01f } }
    }

    private fun createPointerProps(): Array<MotionEvent.PointerProperties> {
        return Array(1) { MotionEvent.PointerProperties().apply { id = 10; toolType = MotionEvent.TOOL_TYPE_FINGER } }
    }

    fun injectMouseMove(x: Float, y: Float) {
        if (downTime > 0) {
            val eventTime = SystemClock.uptimeMillis()
            val moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 1, createPointerProps(), createPointerCoords(x, y), 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectEventAsync(moveEvent)
        }
    }

    fun injectMouseDown(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1, createPointerProps(), createPointerCoords(x, y), 0, buttonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        injectEventAsync(downEvent)
    }

    fun injectMouseUp(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        if (downTime == 0L) downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, 1, createPointerProps(), createPointerCoords(x, y), 0, buttonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        injectEventAsync(upEvent)
        downTime = 0L
    }

    fun injectMouseClick(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        injectMouseMove(x, y)
        injectMouseDown(x, y, buttonState)
        injectMouseUp(x, y, buttonState)
    }

    fun injectMouseScroll(x: Float, y: Float, scrollY: Float) {
        val eventTime = SystemClock.uptimeMillis()
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY) } }
        val scrollEvent = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_SCROLL, 1, createPointerProps(), coords, 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)
        injectEventAsync(scrollEvent)
    }
}
