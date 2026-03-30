package com.chetbox.mousecursor

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuInputInjector(private val context: Context) {

    fun injectClick(x: Float, y: Float, leftClick: Boolean = true) {
        if (!Shizuku.pingBinder()) {
            Log.e("ShizukuInputInjector", "Shizuku is not running!")
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("ShizukuInputInjector", "Shizuku permission not granted!")
            return
        }

        Thread {
            try {
                val command = if (leftClick) {
                    // Standard tap for left click
                    "input tap $x $y"
                } else {
                    // Long press for right click (swipe from same point to same point over 1000ms)
                    "input swipe $x $y $x $y 1000"
                }

                val shizukuCommand = arrayOf("sh", "-c", command)
                // Use reflection to call Shizuku.newProcess as it may have different signatures
                val processClass = Class.forName("rikka.shizuku.Shizuku")
                val newProcessMethod = processClass.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                val process = newProcessMethod.invoke(null, shizukuCommand, null, null) as Process
                process.waitFor()

            } catch (e: Exception) {
                Log.e("ShizukuInputInjector", "Failed to inject click via Shizuku shell: ${e.message}")
            }
        }.start()
    }
}
