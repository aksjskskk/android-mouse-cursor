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

class TouchpadService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var touchpadView: View
    private lateinit var cursorView: ImageView
    private lateinit var sharedPreferences: SharedPreferences

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private var cursorX: Float = 0f
    private var cursorY: Float = 0f

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
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
            x = 500
            y = 500
        }

        cursorX = cursorParams.x.toFloat()
        cursorY = cursorParams.y.toFloat()

        windowManager.addView(cursorView, cursorParams)
    }

    private fun setupTouchpad() {
        val fullScreenMode = sharedPreferences.getBoolean("full_screen_mode", false)
        val touchpadAlpha = sharedPreferences.getFloat("touchpad_alpha", 0.5f)
        val sizeMultiplier = sharedPreferences.getFloat("touchpad_size", 1.0f)

        touchpadView = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.touchpad_bg)
            alpha = if (fullScreenMode) 0f else touchpadAlpha
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

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                inputInjector.injectClick(cursorX, cursorY, leftClick = true)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                inputInjector.injectClick(cursorX, cursorY, leftClick = false) // Right click (long press)
            }
        })

        var lastX = 0f
        var lastY = 0f

        touchpadView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY

                    // Mouse acceleration / sensitivity
                    val sensitivity = 1.5f

                    cursorX += dx * sensitivity
                    cursorY += dy * sensitivity

                    // Clamp to screen
                    cursorX = cursorX.coerceIn(0f, displayMetrics.widthPixels.toFloat())
                    cursorY = cursorY.coerceIn(0f, displayMetrics.heightPixels.toFloat())

                    val cursorParams = cursorView.layoutParams as WindowManager.LayoutParams
                    cursorParams.x = cursorX.toInt()
                    cursorParams.y = cursorY.toInt()
                    windowManager.updateViewLayout(cursorView, cursorParams)

                    lastX = event.rawX
                    lastY = event.rawY
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
        if (::touchpadView.isInitialized) windowManager.removeView(touchpadView)
        if (::cursorView.isInitialized) windowManager.removeView(cursorView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
