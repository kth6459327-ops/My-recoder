package com.my.recorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.my.recorder.START"
        const val ACTION_STOP = "com.my.recorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val FOLDER = "Movies/MyRecorder"

        @Volatile
        var isRecording = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null

    private var overlay: View? = null
    private var timerText: TextView? = null
    private var startTime = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> {
                if (!isRecording) startRecording(intent)
            }
        }
        return START_NOT_STICKY
    }

    // ───────────────────────── 녹화 시작 ─────────────────────────
    private fun startRecording(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val mic = intent.getBooleanExtra(EXTRA_MIC, false)
        if (data == null) {
            stopSelf(); return
        }

        // 1) 포그라운드 서비스 시작 (MediaProjection 얻기 전에 반드시 먼저)
        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, buildNotification(), type)

        try {
            // 2) 화면 크기 계산 (짧은 변 최대 1080, 16의 배수)
            val dm = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            var w = dm.widthPixels
            var h = dm.heightPixels
            val shortSide = minOf(w, h)
            if (shortSide > 1080) {
                val scale = 1080f / shortSide
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            w -= w % 16
            h -= h % 16
            val dpi = dm.densityDpi

            // 3) 갤러리(MediaStore)에 저장할 파일 만들기
            val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("저장 파일을 만들 수 없어요")
            videoUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("저장 파일을 열 수 없어요")
            pfd = fd

            // 4) MediaRecorder 설정
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            if (mic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(fd.fileDescriptor)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(minOf(w * h * 5, 10_000_000))
            rec.prepare()
            recorder = rec

            // 5) 화면 녹화(MediaProjection) 연결
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("화면 녹화 권한을 얻지 못했어요")
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // 시스템이 녹화를 끊은 경우(예: 상단 '공유 중지')
                    handler.post { stopRecording() }
                }
            }, handler)
            projection = proj

            virtualDisplay = proj.createVirtualDisplay(
                "MyRecorder", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )

            // 6) 녹화 시작
            rec.start()
            isRecording = true
            stopping = false
            startTime = SystemClock.elapsedRealtime()
            showOverlay()
            handler.post(tick)
        } catch (e: Exception) {
            Toast.makeText(this, "녹화를 시작하지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
            cleanup(saveFile = false)
        }
    }

    // ───────────────────────── 녹화 정지 & 저장 ─────────────────────────
    private fun stopRecording() {
        if (stopping) return
        stopping = true
        if (!isRecording) {
            cleanup(saveFile = false); return
        }
        var ok = true
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            ok = false   // 너무 짧게 녹화하면 파일이 만들어지지 않을 수 있음
        }
        cleanup(saveFile = ok)
        Toast.makeText(
            this,
            if (ok) "갤러리에 저장됐어요 ($FOLDER)" else "녹화가 너무 짧아 저장하지 못했어요",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun cleanup(saveFile: Boolean) {
        isRecording = false
        handler.removeCallbacks(tick)
        removeOverlay()

        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        val proj = projection
        projection = null
        try { proj?.stop() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null

        videoUri?.let { uri ->
            try {
                if (saveFile) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, v, null, null)   // 갤러리에 표시됨
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) {}
        }
        videoUri = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ───────────────────────── 떠 있는 정지 버튼 ─────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#CC212121"))
            }
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF5252"))
            }
        }
        box.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })

        val time = TextView(this).apply {
            text = "00:00"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        timerText = time
        box.addView(time, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(12) })

        val stopBtn = TextView(this).apply {
            text = "■ 정지"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E53935"))
            }
            setOnClickListener { stopRecording() }
        }
        box.addView(stopBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        // 드래그로 위치 옮기기
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    try { wm.updateViewLayout(box, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(box, params)
            overlay = box
        } catch (e: Exception) {
            Toast.makeText(this, "정지 버튼을 띄우지 못했어요. 알림창에서 정지할 수 있어요", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        timerText = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val sec = (SystemClock.elapsedRealtime() - startTime) / 1000
            timerText?.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
            if (isRecording) handler.postDelayed(this, 500)
        }
    }

    // ───────────────────────── 알림 ─────────────────────────
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "화면 녹화", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("화면 녹화 중")
            .setContentText("정지하려면 '정지'를 누르세요")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "■ 정지", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        if (isRecording) cleanup(saveFile = false)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
