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
        } else if (key == "touchpad_alpha" || key == "touchpad_size" || key == "full_screen_mode") {
            // Re-setup touchpad to apply these changes properly
            if (::touchpadView.isInitialized) {
                windowManager.removeView(touchpadView)
            }
            setupTouchpad()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // Queue to hold clicks that occur while the user is still touching the screen.
    // Toggling window flags while a finger is down breaks the active gesture, causing a freeze.
    private val pendingClicks = mutableListOf<Int>()
    private var isFingerDown = false
    private var isDragging = false

    private fun injectClickPassthrough(buttonState: Int) {
        if (isFingerDown) {
            // Defer the click until the user completely lifts all fingers (ACTION_UP)
            pendingClicks.add(buttonState)
            return
        }

        executeClickPassthrough(buttonState)
    }

    private fun executeClickPassthrough(buttonState: Int) {
        if (!::touchpadView.isInitialized) return

        // To allow the native hardware mouse click to actually hit the apps underneath our
        // full-screen transparent touchpad, we must momentarily make our overlay NOT_TOUCHABLE.
        val params = touchpadView.layoutParams as WindowManager.LayoutParams
        val originalFlags = params.flags

        params.flags = originalFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(touchpadView, params)

        // Inject the native system mouse click while the window is completely transparent to touches
        Thread {
            inputInjector.injectMouseClick(cursorX, cursorY, buttonState)

            // Because we are using ASYNC injection, we must sleep just long enough to let the
            // Android OS input dispatcher route the click to the background app before we
            // restore the touchpad's touchability.
            Thread.sleep(100)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (::touchpadView.isInitialized) {
                    params.flags = originalFlags
                    windowManager.updateViewLayout(touchpadView, params)
                }
            }
        }.start()
    }

    private fun setupTouchpad() {
        val fullScreenMode = sharedPreferences.getBoolean("full_screen_mode", false)
        val touchpadAlpha = sharedPreferences.getFloat("touchpad_alpha", 0.5f)
        val sizeMultiplier = sharedPreferences.getFloat("touchpad_size", 1.0f)

        touchpadView = FrameLayout(this).apply {
            if (fullScreenMode) {
                // Use a practically invisible color (#01000000) so it still intercepts touches
                // A full 0f alpha or completely transparent color sometimes causes Android to pass touches through
                setBackgroundColor(0x01000000)
            } else {
                setBackgroundResource(R.drawable.touchpad_bg)
                alpha = touchpadAlpha
            }
        }

        // Add a drag handle if not fullscreen
        if (!fullScreenMode) {
            val handle = View(this).apply {
                setBackgroundColor(0x88000000.toInt())
            }
            val handleParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 40
            ).apply {
                gravity = Gravity.TOP
            }
            (touchpadView as FrameLayout).addView(handle, handleParams)

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
        }

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val width = if (fullScreenMode) WindowManager.LayoutParams.MATCH_PARENT else (400 * sizeMultiplier).toInt()
        val height = if (fullScreenMode) WindowManager.LayoutParams.MATCH_PARENT else (400 * sizeMultiplier).toInt()

        // FLAG_NOT_TOUCH_MODAL ensures that native mouse clicks injected through Shizuku
        // that fall outside our transparent bounding box (when not in full screen) hit the app underneath.
        // When in full screen, we must intercept ALL touches to map them to the mouse, but we explicitly
        // ignore our own injected SOURCE_MOUSE events (handled in the onTouchListener).
        // Since we are using the native hardware mouse injection, the Android window manager will bypass
        // our TYPE_APPLICATION_OVERLAY window for hardware mouse clicks if we set FLAG_NOT_TOUCHABLE.
        // However, we want to intercept finger touches. The window manager prioritizes the front-most
        // touchable window. The native hardware mouse injection (SOURCE_MOUSE) is handled deeper in the
        // stack. But wait, if our transparent window covers the entire screen, won't it intercept the
        // SOURCE_MOUSE clicks too?
        // Yes, but we filter them out by returning `false` for SOURCE_MOUSE events in `onTouchListener`.
        // This tells the window manager to pass the mouse event down to the window below!
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (fullScreenMode) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else 0

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
            gravity = if (fullScreenMode) Gravity.FILL else Gravity.CENTER
        }

        var isDragging = false
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                injectClickPassthrough(MotionEvent.BUTTON_PRIMARY)
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Start of the second tap in a double-tap: Start dragging!
                        isDragging = true
                        Thread { inputInjector.injectMouseDown(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY) }.start()
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            isDragging = false
                            Thread { inputInjector.injectMouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY) }.start()
                        }
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long press acts as a normal click holding down
                isDragging = true
                Thread { inputInjector.injectMouseDown(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY) }.start()
            }
        })

        var lastX = 0f
        var lastY = 0f
        var isTwoFingerScroll = false

        touchpadView.setOnTouchListener { _, event ->
            // Prevent infinite loops by totally ignoring our own injected native mouse events
            if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                return@setOnTouchListener false
            }

            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isFingerDown = true
                    lastX = event.rawX
                    lastY = event.rawY
                    isTwoFingerScroll = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isFingerDown = true
                    if (event.pointerCount == 2) {
                        isTwoFingerScroll = true
                        lastY = event.getY(0) // Track vertical movement of the first finger for scrolling
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount == 3) {
                        // 3-finger tap for right click
                        injectClickPassthrough(MotionEvent.BUTTON_SECONDARY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isFingerDown = false
                    if (isDragging) {
                        isDragging = false
                        Thread { inputInjector.injectMouseUp(cursorX, cursorY, MotionEvent.BUTTON_PRIMARY) }.start()
                    }

                    // Process any queued clicks now that the user's fingers are completely off the screen.
                    // This prevents modifying window flags while fingers are down, eliminating freezing.
                    if (pendingClicks.isNotEmpty()) {
                        val clicksToInject = pendingClicks.toList()
                        pendingClicks.clear()

                        Thread {
                            clicksToInject.forEach { button ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    executeClickPassthrough(button)
                                }
                                Thread.sleep(200) // Small delay between executing queued clicks
                            }
                        }.start()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerScroll && event.pointerCount == 2) {
                        val currentY = event.getY(0)
                        val dy = currentY - lastY

                        // Inject scroll (scale down the pixel delta to scroll amount)
                        if (Math.abs(dy) > 10) {
                            val scrollAmount = if (dy > 0) -1f else 1f
                            inputInjector.injectMouseScroll(cursorX, cursorY, scrollAmount)
                            lastY = currentY
                        }
                    } else if (event.pointerCount == 1) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY

                        val sensitivity = sharedPreferences.getFloat("mouse_sensitivity", 1.5f)

                        cursorX += dx * sensitivity
                        cursorY += dy * sensitivity

                        cursorX = cursorX.coerceIn(0f, displayMetrics.widthPixels.toFloat())
                        cursorY = cursorY.coerceIn(0f, displayMetrics.heightPixels.toFloat())

                        // Update visual cursor position
                        if (::cursorView.isInitialized) {
                            val cursorParams = cursorView.layoutParams as WindowManager.LayoutParams
                            cursorParams.x = cursorX.toInt()
                            cursorParams.y = cursorY.toInt()
                            windowManager.updateViewLayout(cursorView, cursorParams)
                        }

                        // If the user is double-tap dragging, we MUST inject the movement so the
                        // Android system knows where they are dragging the item.
                        // However, we throttle it slightly so we don't choke the Shizuku IPC binder.
                        if (isDragging) {
                            inputInjector.injectMouseMove(cursorX, cursorY)
                        }

                        // NOTE: If they are NOT dragging, we purposely DO NOT inject ACTION_HOVER_MOVE
                        // through Shizuku 120 times a second. Doing so causes massive IPC binder lag,
                        // which completely freezes and glitches the UI thread on many devices.
                        // The custom visual ImageView cursor handles the visual feedback perfectly.

                        lastX = event.rawX
                        lastY = event.rawY
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
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
