package com.screencopy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var iconView: View? = null
    private var selectView: View? = null
    private var lastBitmap: Bitmap? = null
    private var screenW = 0
    private var screenH = 0
    private var scheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startForegroundCompat()

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        setupCapture()
        showIcon()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("sc", "ScreenCopy", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = Intent(this, OverlayService::class.java).setAction("STOP")
        val pi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "sc")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("ScreenCopy চলছে")
            .setContentText("বন্ধ করতে Stop চাপুন")
            .addAction(android.R.drawable.ic_delete, "Stop", pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupCapture() {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels

        val p = projection!!
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ scheduleConvert() }, handler)
        reader = r
        vdisplay = p.createVirtualDisplay(
            "sc", screenW, screenH, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    private fun scheduleConvert() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            grabFrame()
        }, 150)
    }

    private fun grabFrame() {
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) return
        try {
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * screenW
            val wide = Bitmap.createBitmap(
                screenW + rowPadding / plane.pixelStride, screenH, Bitmap.Config.ARGB_8888
            )
            wide.copyPixelsFromBuffer(plane.buffer)
            lastBitmap = Bitmap.createBitmap(wide, 0, 0, screenW, screenH)
        } catch (e: Exception) {
            // ignore
        } finally {
            img.close()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) {
        Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
    }

    private fun showIcon() {
        val size = dp(52)
        val tv = TextView(this)
        tv.text = "✂"
        tv.textSize = 22f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC1565C0"))
        tv.background = bg

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(200)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - downTime > 3000) {
                            stopSelf()
                        } else {
                            startCapture()
                        }
                    }
                }
            }
            true
        }

        wm.addView(tv, lp)
        iconView = tv
    }

    private fun startCapture() {
        val icon = iconView ?: return
        icon.visibility = View.INVISIBLE
        handler.postDelayed({
            val bmp = lastBitmap
            if (bmp == null) {
                toast("স্ক্রিন ধরা যায়নি, আবার চেষ্টা করুন")
                icon.visibility = View.VISIBLE
            } else {
                showSelection(bmp)
            }
        }, 450)
    }

    private fun showSelection(bmp: Bitmap) {
        val frame = FrameLayout(this)
        val sel = SelView(this, bmp)
        frame.addView(sel, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.setBackgroundColor(Color.parseColor("#EE202020"))
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))

        val copyBtn = Button(this)
        copyBtn.text = "Copy"
        copyBtn.setOnClickListener { doCopy(sel, bmp) }
        val cancelBtn = Button(this)
        cancelBtn.text = "Cancel"
        cancelBtn.setOnClickListener { closeSelection() }
        bar.addView(copyBtn)
        bar.addView(cancelBtn)

        val barLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        barLp.bottomMargin = dp(56)
        frame.addView(bar, barLp)

        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(frame, lp)
        selectView = frame
    }

    private fun closeSelection() {
        selectView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        selectView = null
        iconView?.visibility = View.VISIBLE
    }

    private fun doCopy(sel: SelView, bmp: Bitmap) {
        val r = sel.rect
        if (r == null || r.width() < 20 || r.height() < 20) {
            toast("আগে লেখার উপর আঙুল টেনে বক্স আঁকুন")
            return
        }
        val l = r.left.toInt().coerceIn(0, bmp.width - 1)
        val t = r.top.toInt().coerceIn(0, bmp.height - 1)
        val w = r.width().toInt().coerceAtMost(bmp.width - l)
        val h = r.height().toInt().coerceAtMost(bmp.height - t)
        val crop = Bitmap.createBitmap(bmp, l, t, w, h)
        closeSelection()

        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { res ->
                val text = res.text.trim()
                if (text.isEmpty()) {
                    toast("কোনো লেখা পাওয়া যায়নি")
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ScreenCopy", text))
                    toast("কপি হয়েছে:\n" + text.take(120))
                }
            }
            .addOnFailureListener { toast("এরর: " + it.message) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        iconView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        selectView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        vdisplay?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }

    private class SelView(ctx: Context, val bmp: Bitmap) : View(ctx) {
        var rect: RectF? = null
        private var sx = 0f
        private var sy = 0f
        private val dim = Paint().apply { color = Color.parseColor("#99000000") }
        private val border = Paint().apply {
            color = Color.parseColor("#FF42A5F5")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val hint = Paint().apply {
            color = Color.WHITE
            textSize = 44f
            isAntiAlias = true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
            val r = rect
            if (r == null) {
                c.drawRect(0f, 0f, w, h, dim)
                c.drawText("লেখার উপর আঙুল টেনে বক্স আঁকুন", 40f, 200f, hint)
            } else {
                c.drawRect(0f, 0f, w, r.top, dim)
                c.drawRect(0f, r.bottom, w, h, dim)
                c.drawRect(0f, r.top, r.left, r.bottom, dim)
                c.drawRect(r.right, r.top, w, r.bottom, dim)
                c.drawRect(r, border)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.x
                    sy = e.y
                    rect = RectF(sx, sy, sx, sy)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    rect = RectF(
                        minOf(sx, e.x), minOf(sy, e.y),
                        maxOf(sx, e.x), maxOf(sy, e.y)
                    )
                    invalidate()
                }
            }
            return true
        }
    }
}
