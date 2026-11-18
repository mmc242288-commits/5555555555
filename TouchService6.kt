package com.touchpilot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class TouchService : Service() {
    private lateinit var windowManager: WindowManager
    private val pointViews = mutableListOf<View>()
    private val pointCoords = mutableListOf<Pair<Float, Float>>()
    private var panel: View? = null
    private var runButton: TextView? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var running = false
    private var longPress = false
    private var overlaysHidden = false
    private val handler = Handler(Looper.getMainLooper())
    private val notificationId = 91

    private val repeatTask = object : Runnable {
        override fun run() {
            if (!running) return
            val accessibility = TouchAccessibilityService.instance
            if (TouchAccessibilityService.isTargetForeground && accessibility != null && pointCoords.isNotEmpty()) {
                val duration = if (longPress) 1200L else 70L
                val accepted = accessibility.dispatchTogether(pointCoords.toList(), duration)
                if (!accepted) {
                    running = false
                    refreshTouchability()
                    updateRunButton()
                    Toast.makeText(this@TouchService, "تعذر إرسال اللمسات. تأكد من تفعيل خدمة TouchPilot.", Toast.LENGTH_LONG).show()
                    return
                }
                // Never start a new dispatch while the previous long gesture is still active.
                handler.postDelayed(this, duration + 180L)
            } else {
                handler.postDelayed(this, 350L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> startProjection(intent)
            ACTION_SHOW_PANEL -> showPanel()
            ACTION_REFRESH_POINTS -> refreshPointViews()
            ACTION_RESTORE_OVERLAYS -> restoreOverlays()
        }
        return START_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
        if (resultCode != Activity.RESULT_OK || data == null) return
        startForeground(notificationId, buildNotification(), if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection?.stop()
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureResources()
                projection = null
            }
        }, handler)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("touchpilot_capture", "TouchPilot screen capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, "touchpilot_capture").setContentTitle("TouchPilot").setContentText("إذن التقاط الشاشة نشط").setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(pending).build()
        else Notification.Builder(this).setContentTitle("TouchPilot").setContentText("إذن التقاط الشاشة نشط").setSmallIcon(android.R.drawable.ic_menu_camera).build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // NOT_FOCUSABLE: the overlay must not steal focus from the target app.
    // NOT_TOUCH_MODAL: touches outside the overlay must reach the app behind it.
    private fun baseFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun overlayParams(x: Int, y: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = baseFlags()
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y
            // Without this, on notched/punch-hole screens the window is pushed below the cutout,
            // so the chip is drawn lower than the coordinates we send to dispatchGesture.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // Real display size (including system bars) so the screenshot, the saved normalized
    // points and the gesture coordinates all use the same reference.
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun cleanOldCaptures() {
        getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("capture-") && f.name.endsWith(".png") }
            ?.forEach { try { it.delete() } catch (_: Exception) { } }
    }

    private fun chip(label: String, color: Int) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.WHITE) }
    }

    private fun pointChip(label: String, color: Int) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        minWidth = dp(44); minHeight = dp(44)
        background = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL; setStroke(dp(1), Color.WHITE) }
    }

    private fun styleButton(view: TextView, color: Int) {
        view.apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minHeight = dp(44); minWidth = dp(190)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
    }

    private fun updateRunButton() {
        runButton?.text = if (running) "إيقاف" else "تشغيل"
        runButton?.let { styleButton(it, if (running) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()) }
    }

    private fun setOverlaysHidden(hidden: Boolean) {
        // alpha instead of INVISIBLE: the overlay windows stay alive, which Android requires
        // for TouchPilot to be allowed to open the editor from the background.
        overlaysHidden = hidden
        (listOfNotNull(panel) + pointViews).forEach { it.alpha = if (hidden) 0f else 1f }
        // Invisible windows must also be untouchable, otherwise they swallow taps meant for
        // the point editor (and the hidden chips could be dragged by accident).
        refreshTouchability()
    }

    private fun setNotTouchable(view: View, notTouchable: Boolean) {
        val p = view.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = baseFlags() or (if (notTouchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        if (p.flags == flags) return
        p.flags = flags
        try { windowManager.updateViewLayout(view, p) } catch (_: Exception) { }
    }

    private fun refreshTouchability() {
        panel?.let { setNotTouchable(it, overlaysHidden) }
        pointViews.forEach { setNotTouchable(it, overlaysHidden || running) }
    }

    private var menu: LinearLayout? = null

    private fun setMenuOpen(open: Boolean) {
        menu?.visibility = if (open) View.VISIBLE else View.GONE
        val box = panel ?: return
        try { windowManager.updateViewLayout(box, box.layoutParams) } catch (_: Exception) { }
    }

    private fun toggleMenu() = setMenuOpen(menu?.visibility != View.VISIBLE)

    // Small round logo: purple disc, white ring, centre dot (touch-target look).
    private inner class LogoView : View(this@TouchService) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(48), dp(48))
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = width / 2f - dp(2)
            p.style = Paint.Style.FILL; p.color = 0xE66750A4.toInt(); c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
            c.drawCircle(cx, cy, r * 0.55f, p)
            p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * 0.2f, p)
        }
    }

    private fun showPanel() {
        if (panel != null) { panel?.visibility = View.VISIBLE; return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logo = LogoView()
        box.addView(logo, LinearLayout.LayoutParams(-2, -2))

        val menuBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(0xEE172033.toInt()); cornerRadius = dp(14).toFloat() }
            visibility = View.GONE
        }
        menu = menuBox
        box.addView(menuBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        addPanelAction(menuBox, "التقاط صورة وتحديد النقاط") { setMenuOpen(false); captureScreen() }
        // "تاتش": asks how many copies (1..6) to add, then drops that many numbered points.
        val countBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(0, dp(6), 0, dp(2))
        }
        countBox.addView(TextView(this).apply {
            text = "كم نسخة تريد؟"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        val countRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (n in 1..6) {
            countRow.addView(TextView(this).apply {
                text = n.toString(); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE64A79.toInt()) }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(3), dp(6), dp(3), dp(2)) }
                setOnClickListener { countBox.visibility = View.GONE; addTouchCopies(n) }
            })
        }
        countBox.addView(countRow)
        addPanelAction(menuBox, "تاتش", 0xFFE64A79.toInt()) {
            countBox.visibility = if (countBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        menuBox.addView(countBox)
        val modeAction = TextView(this).apply {
            text = if (longPress) "نوع الضغط: مطوّل" else "نوع الضغط: عادي"
            styleButton(this, 0xFF37474F.toInt())
            setOnClickListener {
                longPress = !longPress
                text = if (longPress) "نوع الضغط: مطوّل" else "نوع الضغط: عادي"
                Toast.makeText(this@TouchService, if (longPress) "تم اختيار الضغط المطوّل" else "تم اختيار الضغط العادي", Toast.LENGTH_SHORT).show()
            }
        }
        menuBox.addView(modeAction)
        runButton = addPanelAction(menuBox, if (running) "إيقاف" else "تشغيل") { toggleRun(); setMenuOpen(false) }
        updateRunButton()
        addPanelAction(menuBox, "إغلاق اللوحة", 0xFF6D4C41.toInt()) { stopSelf() }
        panel = box
        windowManager.addView(box, overlayParams(24, 170))
        logo.setOnTouchListener(dragListener(box) { toggleMenu() })
        loadSavedPoints()
    }

    private fun addPanelAction(parent: LinearLayout, title: String, color: Int = 0xFF455A9E.toInt(), action: () -> Unit): TextView {
        val button = TextView(this).apply {
            text = title
            styleButton(this, color)
            setOnClickListener { action() }
        }
        parent.addView(button)
        return button
    }

    private fun dragListener(view: View, onTap: () -> Unit): View.OnTouchListener {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var dragged = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        return View.OnTouchListener { _, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragged && kotlin.math.hypot(dx, dy) > slop) dragged = true
                    if (dragged) {
                        params.x = originX + dx.toInt()
                        params.y = originY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) onTap(); true }
                else -> true
            }
        }
    }

    private fun addTouchCopies(count: Int) {
        val (screenW, screenH) = screenSize()
        val gap = dp(56)
        for (i in 0 until count) {
            // Spread the copies horizontally around the screen centre so they don't overlap.
            val x = screenW / 2f + (i - (count - 1) / 2f) * gap
            addPoint(x, screenH / 2f)
        }
        persistPoints()
        Toast.makeText(this, "تمت إضافة $count", Toast.LENGTH_SHORT).show()
    }

    private fun addPoint(x: Float, y: Float) {
        val index = pointCoords.size + 1
        pointCoords.add(x to y)
        val view = pointChip(index.toString(), 0xFFE64A79.toInt())
        pointViews.add(view)
        view.alpha = if (overlaysHidden) 0f else 1f
        windowManager.addView(view, overlayParams(max(0, x.toInt() - dp(22)), max(0, y.toInt() - dp(22)), !running && !overlaysHidden))
        view.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = v.layoutParams as WindowManager.LayoutParams
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=p.x; oy=p.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        p.x=ox+(e.rawX-sx).toInt(); p.y=oy+(e.rawY-sy).toInt()
                        windowManager.updateViewLayout(v,p)
                        val idx=pointViews.indexOf(v)
                        if (idx>=0) pointCoords[idx]=p.x+v.width/2f to p.y+v.height/2f
                        return true
                    }
                    MotionEvent.ACTION_UP -> { persistPoints(); return true }
                }
                return false
            }
        })
    }

    private fun loadSavedPoints() {
        val raw = getSharedPreferences("tp", 0).getString("points", null) ?: return
        try {
            val array = JSONArray(raw)
            val (screenW, screenH) = screenSize()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                addPoint(item.getDouble("x").toFloat() * screenW, item.getDouble("y").toFloat() * screenH)
            }
        } catch (_: Exception) { }
    }

    private fun refreshPointViews() {
        panel?.visibility = View.VISIBLE
        pointViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) { } }
        pointViews.clear()
        pointCoords.clear()
        loadSavedPoints()
        restoreOverlays()
    }

    private fun persistPoints() {
        val (screenW, screenH) = screenSize()
        val array = JSONArray()
        pointCoords.forEach { (x, y) ->
            array.put(org.json.JSONObject().put("x", (x / screenW).toDouble()).put("y", (y / screenH).toDouble()))
        }
        getSharedPreferences("tp", 0).edit().putString("points", array.toString()).apply()
    }

    private fun toggleRun() {
        if (!running && pointCoords.isEmpty()) {
            Toast.makeText(this, "حدد نقطة واحدة على الأقل قبل التشغيل", Toast.LENGTH_LONG).show()
            return
        }
        if (!running && TouchAccessibilityService.instance == null) {
            Toast.makeText(this, "فعّل خدمة إمكانية الوصول الخاصة بـ TouchPilot أولاً", Toast.LENGTH_LONG).show()
            return
        }
        running = !running
        refreshTouchability()
        updateRunButton()
        if (running) handler.post(repeatTask) else handler.removeCallbacks(repeatTask)
        Toast.makeText(this, if (running) "بدأ التنفيذ عند فتح التطبيق المحدد" else "تم الإيقاف", Toast.LENGTH_SHORT).show()
    }

    private fun captureScreen() {
        if (projection == null) {
            Toast.makeText(this, "فعّل التقاط الشاشة من التطبيق الرئيسي أولاً", Toast.LENGTH_LONG).show()
            return
        }
        // Hide controls so the screenshot is a clean view of the target app.
        setOverlaysHidden(true)
        handler.postDelayed({
            try {
                val dm = resources.displayMetrics
                val (screenW, screenH) = screenSize()
                try { imageReader?.close() } catch (_: Exception) { }
                imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
                if (virtualDisplay == null) {
                    virtualDisplay = projection?.createVirtualDisplay(
                        "TouchPilotCapture", screenW, screenH, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler
                    )
                } else {
                    virtualDisplay?.setSurface(imageReader?.surface)
                }
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * image.width
                        val padded = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
                        )
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        padded.recycle()
                        cleanOldCaptures()
                        val file = File(getExternalFilesDir(null), "capture-${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        virtualDisplay?.setSurface(null)
                        try { imageReader?.close() } catch (_: Exception) { }
                        imageReader = null
                        val editor = Intent(this, PointEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        editor.putExtra("image_path", file.absolutePath)
                        try {
                            startActivity(editor)
                        } catch (e: Exception) {
                            Toast.makeText(this, "تعذر فتح المحرر: ${e.message}", Toast.LENGTH_LONG).show()
                            restoreOverlays()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "فشل حفظ الصورة: ${e.message}", Toast.LENGTH_LONG).show()
                        detachCaptureReader()
                        restoreOverlays()
                    } finally { image.close() }
                }, handler)
                handler.postDelayed({
                    if (imageReader != null) {
                        Toast.makeText(this, "لم تصل صورة من النظام. جرّب مرة أخرى.", Toast.LENGTH_LONG).show()
                        detachCaptureReader(); restoreOverlays()
                    }
                }, 2500)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر التقاط الشاشة: ${e.message}", Toast.LENGTH_LONG).show()
                detachCaptureReader(); restoreOverlays()
            }
        }, 250)
    }

    private fun restoreOverlays() {
        setOverlaysHidden(false)
        (listOfNotNull(panel) + pointViews).forEach { it.visibility = View.VISIBLE }
    }

    private fun detachCaptureReader() {
        try { virtualDisplay?.setSurface(null) } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
    }

    private fun releaseCaptureResources() {
        detachCaptureReader()
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(repeatTask)
        releaseCaptureResources()
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        (listOfNotNull(panel) + pointViews).forEach { try { windowManager.removeView(it) } catch (_: Exception) { } }
        pointViews.clear()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START_PROJECTION = "com.touchpilot.START_PROJECTION"
        const val ACTION_SHOW_PANEL = "com.touchpilot.SHOW_PANEL"
        const val ACTION_REFRESH_POINTS = "com.touchpilot.REFRESH_POINTS"
        const val ACTION_RESTORE_OVERLAYS = "com.touchpilot.RESTORE_OVERLAYS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
