package com.chetbox.mousecursor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat

class TouchpadService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var touchpadView: View
    private lateinit var cursorView: ImageView
    private lateinit var sharedPreferences: SharedPreferences

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    // Start cursor in the center of the screen initially
    private var cursorX: Float = 500f
    private var cursorY: Float = 500f

    private lateinit var inputInjector: ShizukuInputInjector

    private val CHANNEL_ID = "TouchpadServiceChannel"

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("MousePrefs", Context.MODE_PRIVATE)
        inputInjector = ShizukuInputInjector(this)

        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Action to stop service
        val stopIntent = Intent(this, TouchpadService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mouse Touchpad Running")
            .setContentText("Tap 'Stop' to disable the touchpad.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupCursor()
        setupTouchpad()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        startBackgroundDragInjector()

        // Try to show the native system mouse cursor initially, though many devices
        // won't render it without a physical mouse plugged in. Our custom cursor
        // will serve as the guaranteed fallback.
        inputInjector.injectMouseMove(cursorX, cursorY)
    }

    private fun setupCursor() {
        val cursorSizeMultiplier = sharedPreferences.getFloat("cursor_size", 1.0f)
        val baseCursorSize = 64
        val finalCursorSize = (baseCursorSize * cursorSizeMultiplier).toInt()

        cursorView = ImageView(this).apply {
            setImageResource(R.drawable.mouse_cursor)
        }

        val cursorParams = WindowManager.LayoutParams(
            finalCursorSize,
            finalCursorSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()

            // Allow drawing inside the display cutout (notch/punch-hole) area.
            // Without this, the system pushes the view down below the status bar,
            // destroying the 1:1 pixel mapping with our Shizuku hardware injection.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(cursorView, cursorParams)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "cursor_size") {
            val cursorSizeMultiplier = sharedPreferences?.getFloat("cursor_size", 1.0f) ?: 1.0f
            val baseCursorSize = 64
            val finalCursorSize = (baseCursorSize * cursorSizeMultiplier).toInt()

            if (::cursorView.isInitialized) {
                val params = cursorView.layoutParams as WindowManager.LayoutParams
                params.width = finalCursorSize
                params.height = finalCursorSize
                windowManager.updateViewLayout(cursorView, params)
            }
        } else if (key == "touchpad_alpha" || key == "touchpad_size") {
            // Live update the touchpad without completely removing and recreating it
            // to preserve its current dragged location on the screen.
            if (::touchpadView.isInitialized) {
                val touchpadAlpha = sharedPreferences?.getFloat("touchpad_alpha", 0.5f) ?: 0.5f
                val sizeMultiplier = sharedPreferences?.getFloat("touchpad_size", 1.0f) ?: 1.0f

                touchpadView.alpha = touchpadAlpha

                val params = touchpadView.layoutParams as WindowManager.LayoutParams
                params.width = (400 * sizeMultiplier).toInt()
                params.height = (400 * sizeMultiplier).toInt()
                windowManager.updateViewLayout(touchpadView, params)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @Volatile private var isDragging = false
    @Volatile private var isServiceRunning = true

    private val displayMetrics = android.util.DisplayMetrics()

    // Background thread for Shizuku IPC. This prevents the UI thread from freezing during drags.
    private var dragThread: Thread? = null

    // Extracts the dx/dy pointer tracking logic so both the touchpad area AND the buttons
    // can move the cursor. This fixes the issue where holding a button stops cursor movement.
    private fun updateCursorPosition(dx: Float, dy: Float) {
        val sensitivity = sharedPreferences.getFloat("mouse_sensitivity", 1.5f)

        cursorX += dx * sensitivity
        cursorY += dy * sensitivity

        // Use Resources.getSystem() to guarantee we get absolute maximum device hardware pixels,
        // rather than window-constrained or orientation-lagged metrics from WindowManager.
        // This is crucial for SOURCE_TOUCHSCREEN injection boundaries.
        val width = android.content.res.Resources.getSystem().displayMetrics.widthPixels.toFloat()
        val height = android.content.res.Resources.getSystem().displayMetrics.heightPixels.toFloat()

        // Allow the cursor to move freely in the logical display space.
        cursorX = cursorX.coerceIn(0f, width)
        cursorY = cursorY.coerceIn(0f, height)

        // Update visual cursor position on the UI thread
        if (::cursorView.isInitialized) {
            val cursorParams = cursorView.layoutParams as WindowManager.LayoutParams
            cursorParams.x = cursorX.toInt()
            cursorParams.y = cursorY.toInt()
            windowManager.updateViewLayout(cursorView, cursorParams)
        }
    }

    private fun startBackgroundDragInjector() {
        dragThread = Thread {
            while (isServiceRunning) {
                if (isDragging) {
                    // Injecting via Shizuku here prevents locking the main UI thread.
                    inputInjector.injectMouseMove(cursorX, cursorY)
                }
                // Throttle the loop to roughly 60fps (16ms)
                Thread.sleep(16)
            }
        }
        dragThread?.start()
    }

    private fun setupTouchpad() {
        val touchpadAlpha = sharedPreferences.getFloat("touchpad_alpha", 0.5f)
        val sizeMultiplier = sharedPreferences.getFloat("touchpad_size", 1.0f)

        // Inflate the new layout that includes the L and R buttons
        touchpadView = android.view.LayoutInflater.from(this).inflate(R.layout.touchpad_layout, null).apply {
            alpha = touchpadAlpha
        }

        val handle = touchpadView.findViewById<View>(R.id.drag_handle)
        val touchpadArea = touchpadView.findViewById<View>(R.id.touchpad_area)
        val btnLeft = touchpadView.findViewById<android.widget.Button>(R.id.btn_left_click)
        val btnRight = touchpadView.findViewById<android.widget.Button>(R.id.btn_right_click)

        handle.setOnTouchListener { _, event ->
            val params = touchpadView.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(touchpadView, params)
                    true
                }
                else -> false
            }
        }

        // Dedicated physical-like L and R buttons!
        // This entirely replaces the flaky gesture detector for dragging and right clicking.

        // Let's add tracking back to the buttons as well, but using rawX/rawY.
        // This fully covers Scenario A (holding the button and sliding that SAME finger to drag).
        // Scenario B (holding button, dragging on touchpad) is handled by the touchpadArea below.

        var btnLastX = 0f
        var btnLastY = 0f

        btnLeft.setOnTouchListener { _, event ->
            if (event.deviceId == 1337) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    btnLastX = event.rawX
                    btnLastY = event.rawY
                    isDragging = true // Enable moving the item while button is held
                    inputInjector.injectMouseDown(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                    btnLeft.setBackgroundColor(0x88FFFFFF.toInt()) // Visual feedback
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - btnLastX
                    val dy = event.rawY - btnLastY
                    updateCursorPosition(dx, dy)
                    btnLastX = event.rawX
                    btnLastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    inputInjector.injectMouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                    btnLeft.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
            true
        }

        btnRight.setOnTouchListener { _, event ->
            if (event.deviceId == 1337) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    btnLastX = event.rawX
                    btnLastY = event.rawY
                    isDragging = true // Enable dragging/drawing with right click held down
                    inputInjector.injectMouseDown(cursorX, cursorY, MotionEvent.BUTTON_SECONDARY)
                    btnRight.setBackgroundColor(0x88FFFFFF.toInt())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - btnLastX
                    val dy = event.rawY - btnLastY
                    updateCursorPosition(dx, dy)
                    btnLastX = event.rawX
                    btnLastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    inputInjector.injectMouseUp(cursorX, cursorY, MotionEvent.BUTTON_SECONDARY)
                    btnRight.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
            true
        }

        val width = (400 * sizeMultiplier).toInt()
        val height = (400 * sizeMultiplier).toInt()

        // FLAG_NOT_TOUCH_MODAL ensures that native mouse clicks injected through Shizuku
        // that fall outside our transparent bounding box hit the background apps underneath.
        // Because the user controls a floating window trackpad, the cursor is almost always
        // outside the bounds of the trackpad, ensuring clicks pass through 100% reliably
        // without any hacky window flag toggling or sleep delays.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // المتغيرات الخاصة بلوحة اللمس
        var lastX = 0f
        var lastY = 0f
        var isTwoFingerScroll = false
        var touchpadDownTime = 0L
        var totalMoveX = 0f
        var totalMoveY = 0f

        // المتغيرات الجديدة للحل الذكي (السحب والإفلات بالنقر المزدوج)
        var lastTapTime = 0L
        var isDraggingGesture = false

        touchpadArea.setOnTouchListener { _, event ->
            if (event.deviceId == 1337) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    touchpadDownTime = System.currentTimeMillis()
                    totalMoveX = 0f
                    totalMoveY = 0f
                    isTwoFingerScroll = false

                    // النقر المزدوج للسحب
                    if (touchpadDownTime - lastTapTime < 250) {
                        isDraggingGesture = true
                        inputInjector.injectMouseDown(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isTwoFingerScroll = true

                        // إصلاح خطأ الـ Scroll: إذا بدأ سحب بالخطأ، قم بإلغائه فوراً
                        if (isDraggingGesture) {
                            isDraggingGesture = false
                            inputInjector.injectMouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                        }

                        lastY = event.getY(0)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerScroll && event.pointerCount == 2) {
                        val currentY = event.getY(0)
                        val dy = currentY - lastY
                        if (Math.abs(dy) > 10) {
                            val scrollAmount = (dy / -20f)
                            inputInjector.injectMouseScroll(cursorX, cursorY, scrollAmount)
                            lastY = currentY
                        }
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        totalMoveX += Math.abs(dx)
                        totalMoveY += Math.abs(dy)
                        updateCursorPosition(dx, dy)
                        lastX = event.x
                        lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val timeHeld = System.currentTimeMillis() - touchpadDownTime

                    if (isDraggingGesture) {
                        isDraggingGesture = false
                        inputInjector.injectMouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                        lastTapTime = 0L

                    } else if (!isTwoFingerScroll && timeHeld < 200 && totalMoveX < 15f && totalMoveY < 15f) {
                        inputInjector.injectMouseClick(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                        lastTapTime = System.currentTimeMillis()

                    } else {
                        lastTapTime = 0L
                    }

                    isTwoFingerScroll = false // تصفير حالة التمرير
                    true
                }
                else -> false
            }
        }

        windowManager.addView(touchpadView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Touchpad Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        dragThread?.interrupt()

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        if (::touchpadView.isInitialized) windowManager.removeView(touchpadView)
        if (::cursorView.isInitialized) windowManager.removeView(cursorView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
