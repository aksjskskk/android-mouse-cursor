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

class ShizukuInputInjector(private val context: Context) {

    private var iInputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var downTime: Long = 0L

    // الحل السحري لمنع التجمد: حفظ حالة الزر المضغوط أثناء السحب
    private var currentButtonState: Int = 0

    init {
        setupIInputManager()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try { setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (e: Exception) {}
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

    private fun injectEvent(event: MotionEvent) {
        if (iInputManager == null) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try { setDisplayIdMethod?.invoke(event, 0) } catch (e: Exception) {}
        }
        try { injectInputEventMethod?.invoke(iInputManager, event, 0) } catch (e: Exception) {}
    }

    // استخدمنا TOUCHSCREEN لقتل الـ Dead Zone، و TOOL_TYPE_MOUSE مع id=10 للدقة
    private fun createPointerCoords(x: Float, y: Float): Array<MotionEvent.PointerCoords> {
        return Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }
    }

    private fun createPointerProps(): Array<MotionEvent.PointerProperties> {
        return Array(1) { MotionEvent.PointerProperties().apply { id = 10; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
    }

    fun injectMouseMove(x: Float, y: Float) {
        if (downTime > 0) {
            val eventTime = SystemClock.uptimeMillis()
            val props = Array(1) { MotionEvent.PointerProperties().apply { id = 10; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
            val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

            // تمرير currentButtonState بدلاً من 0 يمنع التجمد نهائياً!
            val moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 1, props, coords, 0, currentButtonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectEvent(moveEvent)
            moveEvent.recycle()
        }
    }

    fun injectMouseDown(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        downTime = SystemClock.uptimeMillis()
        currentButtonState = buttonState // نحفظ الزر هنا
        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 10; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, 1, props, coords, 0, buttonState, 1f, 1f, 1337, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        injectEvent(downEvent)
        downEvent.recycle()
    }

    fun injectMouseUp(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        if (downTime == 0L) downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 10; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f } }

        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        injectEvent(upEvent)
        upEvent.recycle()

        downTime = 0L
        currentButtonState = 0 // تصفير الزر
    }

    fun injectMouseClick(x: Float, y: Float, buttonState: Int = MotionEvent.BUTTON_PRIMARY) {
        injectMouseMove(x, y)
        injectMouseDown(x, y, buttonState)
        injectMouseUp(x, y, buttonState)
    }

    fun injectMouseScroll(x: Float, y: Float, scrollY: Float) {
        injectMouseMove(x, y)
        val eventTime = SystemClock.uptimeMillis()
        val props = Array(1) { MotionEvent.PointerProperties().apply { id = 10; toolType = MotionEvent.TOOL_TYPE_MOUSE } }
        val coords = Array(1) { MotionEvent.PointerCoords().apply { this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY) } }

        // الـ Scroll يبقى MOUSE ليعمل مع كل التطبيقات
        val scrollEvent = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 1337, 0, InputDevice.SOURCE_MOUSE, 0)
        injectEvent(scrollEvent)
        scrollEvent.recycle()
    }
}
