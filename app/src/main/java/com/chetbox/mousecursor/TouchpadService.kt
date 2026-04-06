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

    private var isDragging = false

    private val displayMetrics = android.util.DisplayMetrics()

    // Extracts the dx/dy pointer tracking logic so both the touchpad area AND the buttons
    // can move the cursor. This fixes the issue where holding a button stops cursor movement.
    private fun updateCursorPosition(dx: Float, dy: Float) {
        val sensitivity = sharedPreferences.getFloat("mouse_sensitivity", 1.5f)

        cursorX += dx * sensitivity
        cursorY += dy * sensitivity

        // Dynamically fetch screen size so boundary clamping works after orientation changes
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        // Allow the cursor to move freely in the logical display space.
        cursorX = cursorX.coerceIn(0f, displayMetrics.widthPixels.toFloat())
        cursorY = cursorY.coerceIn(0f, displayMetrics.heightPixels.toFloat())

        // Update visual cursor position
        if (::cursorView.isInitialized) {
            val cursorParams = cursorView.layoutParams as WindowManager.LayoutParams
            cursorParams.x = cursorX.toInt()
            cursorParams.y = cursorY.toInt()
            windowManager.updateViewLayout(cursorView, cursorParams)
        }

        // If the user is dragging, inject movement so the OS knows where the item is going.
        if (isDragging) {
            inputInjector.injectMouseMove(cursorX, cursorY)
        }
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

        var btnLastX = 0f
        var btnLastY = 0f

        btnLeft.setOnTouchListener { _, event ->
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

        var lastX = 0f
        var lastY = 0f
        var isTwoFingerScroll = false
        var touchpadDownTime = 0L
        var totalMoveX = 0f
        var totalMoveY = 0f

        touchpadArea.setOnTouchListener { _, event ->
            // Prevent infinite loops by totally ignoring our own injected native mouse events
            if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    touchpadDownTime = System.currentTimeMillis()
                    totalMoveX = 0f
                    totalMoveY = 0f
                    isTwoFingerScroll = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isTwoFingerScroll = true
                        lastY = event.getY(0) // Track vertical movement of the first finger for scrolling
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerScroll && event.pointerCount == 2) {
                        val currentY = event.getY(0)
                        val dy = currentY - lastY

                        // Optimize scroll speed: inject proportional scroll values based on distance swiped
                        if (Math.abs(dy) > 10) {
                            val scrollAmount = (dy / -20f) // negative is up, positive is down, adjust divisor for speed

                            // Inject scroll natively. Since we are using IInputManager reflection,
                            // this happens instantly and smoothly!
                            inputInjector.injectMouseScroll(cursorX, cursorY, scrollAmount)

                            lastY = currentY
                        }
                    } else if (event.pointerCount == 1) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY

                        totalMoveX += Math.abs(dx)
                        totalMoveY += Math.abs(dy)

                        updateCursorPosition(dx, dy)

                        // NOTE: If they are NOT dragging, we purposely DO NOT inject ACTION_HOVER_MOVE
                        // through Shizuku 120 times a second. Doing so causes massive IPC binder lag,
                        // which completely freezes and glitches the UI thread on many devices.
                        // The custom visual ImageView cursor handles the visual feedback perfectly.

                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Detect a quick tap to simulate a normal left click without using the L button
                    val timeHeld = System.currentTimeMillis() - touchpadDownTime
                    if (timeHeld < 200 && totalMoveX < 15f && totalMoveY < 15f) {
                        inputInjector.injectMouseClick(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
                    }
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
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        if (::touchpadView.isInitialized) windowManager.removeView(touchpadView)
        if (::cursorView.isInitialized) windowManager.removeView(cursorView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
