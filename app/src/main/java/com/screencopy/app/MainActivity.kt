package com.screencopy.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqCapture = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(48, 48, 48, 48)

        val info = TextView(this)
        info.text = "Start চাপলে স্ক্রিনে একটা ভাসমান আইকন আসবে।\n\n" +
            "আইকনে ট্যাপ করলে স্ক্রিন ফ্রিজ হবে। লেখার উপর আঙুল টেনে বক্স আঁকুন, তারপর Copy চাপুন।\n\n" +
            "আইকন বন্ধ করতে আইকনে ৩ সেকেন্ড চেপে ধরুন, অথবা নোটিফিকেশনের Stop চাপুন।"
        info.textSize = 16f
        info.setPadding(0, 0, 0, 48)

        val startBtn = Button(this)
        startBtn.text = "Start"
        startBtn.setOnClickListener { start() }

        val stopBtn = Button(this)
        stopBtn.text = "Stop"
        stopBtn.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        root.addView(info)
        root.addView(startBtn)
        root.addView(stopBtn)
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'Display over other apps' চালু করে ফিরে এসে আবার Start চাপুন", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                i.putExtra("resultCode", resultCode)
                i.putExtra("data", data)
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "স্ক্রিন ক্যাপচারের অনুমতি দিতে হবে", Toast.LENGTH_LONG).show()
            }
        }
    }
}
