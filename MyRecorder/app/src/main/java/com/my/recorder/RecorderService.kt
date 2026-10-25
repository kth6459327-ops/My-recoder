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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.my.recorder.START"
        const val ACTION_STOP = "com.my.recorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO = "audio_mode"

        // 소리 모드
        const val AUDIO_NONE = 0
        const val AUDIO_INTERNAL = 1   // 내부 소리 (유튜브·게임 등)
        const val AUDIO_MIC = 2        // 마이크
        const val AUDIO_BOTH = 3       // 내부 소리 + 마이크

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val FOLDER = "Movies/MyRecorder"

        private const val SAMPLE_RATE = 44_100
        private const val FRAMES_PER_READ = 1024   // 한 번에 읽는 오디오 프레임 수

        @Volatile
        var isRecording = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null

    // 인코더 / 먹서
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var expectedTracks = 1
    private val pending = ArrayList<Triple<Int, ByteArray, MediaCodec.BufferInfo>>()
    @Volatile private var samplesWritten = 0

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var stopFlag = false

    private var overlay: View? = null
    private var timerText: TextView? = null
    private var startTime = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> if (!isRecording) startRecording(intent)
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
        val audioMode = intent.getIntExtra(EXTRA_AUDIO, AUDIO_NONE)
        if (data == null) { stopSelf(); return }

        // 1) 포그라운드 서비스 (MediaProjection 얻기 전에 먼저)
        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audioMode != AUDIO_NONE && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, buildNotification(), type)

        try {
            // 2) 화면 녹화 권한
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("화면 녹화 권한을 얻지 못했어요")
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { handler.post { stopRecording() } }
            }, handler)
            projection = proj

            // 3) 화면 크기 (짧은 변 최대 1080, 16의 배수)
            val dm = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            var w = dm.widthPixels
            var h = dm.heightPixels
            val shortSide = minOf(w, h)
            if (shortSide > 1080) {
                val scale = 1080f / shortSide
                w = (w * scale).toInt(); h = (h * scale).toInt()
            }
            w -= w % 16; h -= h % 16

            // 4) 갤러리에 저장할 파일
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
            val fd = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("저장 파일을 열 수 없어요")
            pfd = fd
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 5) 오디오 준비 (실패하면 소리 없이 진행)
            var audioOk = false
            if (audioMode != AUDIO_NONE) {
                try {
                    setupAudio(audioMode, proj)
                    audioOk = true
                } catch (e: Exception) {
                    releaseAudio()
                    handler.post {
                        Toast.makeText(this, "소리 녹음을 시작하지 못해 화면만 녹화해요", Toast.LENGTH_LONG).show()
                    }
                }
            }
            expectedTracks = if (audioOk) 2 else 1

            // 6) 영상 인코더 (안 되면 해상도 낮춰 재시도)
            val (vw, vh) = setupVideoEncoder(w, h)

            virtualDisplay = proj.createVirtualDisplay(
                "MyRecorder", vw, vh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            // 7) 시작
            stopFlag = false
            samplesWritten = 0
            videoEncoder!!.start()
            videoThread = Thread({ drainVideo() }, "video-enc").also { it.start() }
            if (audioOk) {
                audioEncoder!!.start()
                internalRec?.startRecording()
                micRec?.startRecording()
                audioThread = Thread({ audioLoop() }, "audio-enc").also { it.start() }
            }

            isRecording = true
            stopping = false
            startTime = SystemClock.elapsedRealtime()
            showOverlay()
            handler.post(tick)
        } catch (e: Exception) {
            Toast.makeText(this, "녹화를 시작하지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
            finish(saveFile = false)
        }
    }

    private fun setupVideoEncoder(w0: Int, h0: Int): Pair<Int, Int> {
        var w = w0; var h = h0
        for (attempt in 0 until 3) {
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, minOf(w * h * 5, 10_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // 화면이 멈춰 있어도 일정하게 프레임 생성
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: Exception) {
                    enc.release(); throw e
                }
                inputSurface = enc.createInputSurface()
                videoEncoder = enc
                return Pair(w, h)
            } catch (e: Exception) {
                if (attempt == 2) throw e
                w = (w * 2 / 3).let { it - it % 16 }
                h = (h * 2 / 3).let { it - it % 16 }
            }
        }
        throw IllegalStateException("영상 인코더를 준비하지 못했어요")
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio(mode: Int, proj: MediaProjection) {
        val minStereo = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val minMono = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        if (mode == AUDIO_INTERNAL || mode == AUDIO_BOTH) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minStereo, FRAMES_PER_READ * 4 * 8))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("internal") }
            internalRec = rec
        }
        if (mode == AUDIO_MIC || mode == AUDIO_BOTH) {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minMono, FRAMES_PER_READ * 2 * 8)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("mic") }
            micRec = rec
        }

        val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_READ * 4 * 4)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder = enc
    }

    // ───────────────────────── 영상 인코더 출력 → 파일 ─────────────────────────
    private fun drainVideo() {
        val enc = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, enc.outputFormat)
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        writeSample(true, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (_: Exception) { }
    }

    // ───────────────────────── 오디오 읽기·섞기·인코딩 ─────────────────────────
    private fun audioLoop() {
        val enc = audioEncoder ?: return
        val stereo = ShortArray(FRAMES_PER_READ * 2)
        val internalBuf = ShortArray(FRAMES_PER_READ * 2)
        val micBuf = ShortArray(FRAMES_PER_READ)
        val bytes = ByteBuffer.allocate(FRAMES_PER_READ * 4).order(ByteOrder.LITTLE_ENDIAN)
        val info = MediaCodec.BufferInfo()
        val startUs = System.nanoTime() / 1000
        var framesDone = 0L

        try {
            while (!stopFlag) {
                val ir = internalRec
                val mr = micRec
                // 읽기
                if (ir != null) readFully(ir, internalBuf) else internalBuf.fill(0)
                if (mr != null) readFully(mr, micBuf) else micBuf.fill(0)
                // 섞기 (마이크는 모노 → 양쪽 채널)
                for (i in 0 until FRAMES_PER_READ) {
                    val m = micBuf[i].toInt()
                    val l = internalBuf[i * 2].toInt() + m
                    val r = internalBuf[i * 2 + 1].toInt() + m
                    stereo[i * 2] = l.coerceIn(-32768, 32767).toShort()
                    stereo[i * 2 + 1] = r.coerceIn(-32768, 32767).toShort()
                }
                bytes.clear(); bytes.asShortBuffer().put(stereo)
                // 소리가 한동안 안 들어온 경우(무음 구간 등) 영상과 시간 맞추기
                val chunkUs = FRAMES_PER_READ * 1_000_000L / SAMPLE_RATE
                val wallUs = System.nanoTime() / 1000 - chunkUs
                if (wallUs - (startUs + framesDone * 1_000_000L / SAMPLE_RATE) > 200_000L) {
                    framesDone = (wallUs - startUs) * SAMPLE_RATE / 1_000_000L
                }
                val pts = startUs + framesDone * 1_000_000L / SAMPLE_RATE
                queueAudio(enc, bytes.array(), FRAMES_PER_READ * 4, pts, 0)
                framesDone += FRAMES_PER_READ
                drainAudio(enc, info, false)
            }
            // 끝 신호
            val pts = startUs + framesDone * 1_000_000L / SAMPLE_RATE
            queueAudio(enc, ByteArray(0), 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainAudio(enc, info, true)
        } catch (_: Exception) { }
    }

    private fun readFully(rec: AudioRecord, buf: ShortArray) {
        var off = 0
        while (off < buf.size && !stopFlag) {
            val n = rec.read(buf, off, buf.size - off)
            if (n <= 0) { buf.fill(0, off); return }
            off += n
        }
        if (off < buf.size) buf.fill(0, off)
    }

    private fun queueAudio(enc: MediaCodec, data: ByteArray, len: Int, pts: Long, flags: Int) {
        while (true) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val inBuf = enc.getInputBuffer(idx) ?: return
                inBuf.clear()
                inBuf.put(data, 0, minOf(len, inBuf.remaining()))
                enc.queueInputBuffer(idx, 0, minOf(len, inBuf.capacity()), pts, flags)
                return
            }
            if (stopFlag && flags == 0) return
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return else continue
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, enc.outputFormat)
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    writeSample(false, buf, info)
                }
                enc.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    // ───────────────────────── 먹서 (영상+소리 합치기) ─────────────────────────
    private fun addTrack(video: Boolean, format: MediaFormat) {
        synchronized(muxLock) {
            val m = muxer ?: return
            if (video && videoTrack < 0) videoTrack = m.addTrack(format)
            if (!video && audioTrack < 0) audioTrack = m.addTrack(format)
            val ready = (videoTrack >= 0) && (expectedTracks == 1 || audioTrack >= 0)
            if (ready && !muxerStarted) {
                m.start()
                muxerStarted = true
                for ((isVideo, bytes, inf) in pending) {
                    m.writeSampleData(if (isVideo == 1) videoTrack else audioTrack, ByteBuffer.wrap(bytes), inf)
                    samplesWritten++
                }
                pending.clear()
            }
        }
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            val m = muxer ?: return
            buf.position(info.offset); buf.limit(info.offset + info.size)
            if (muxerStarted) {
                m.writeSampleData(if (video) videoTrack else audioTrack, buf, info)
                samplesWritten++
            } else if (pending.size < 2000) {
                val copy = ByteArray(info.size); buf.get(copy)
                val inf = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending.add(Triple(if (video) 1 else 0, copy, inf))
            }
        }
    }

    // ───────────────────────── 녹화 정지 & 저장 ─────────────────────────
    private fun stopRecording() {
        if (stopping) return
        stopping = true
        if (!isRecording) { finish(saveFile = false); return }
        isRecording = false
        handler.removeCallbacks(tick)
        removeOverlay()

        // 인코더 마무리는 시간이 걸릴 수 있어 별도 스레드에서
        Thread {
            stopFlag = true
            try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
            try { videoThread?.join(3000) } catch (_: Exception) {}
            try { audioThread?.join(3000) } catch (_: Exception) {}
            var ok = samplesWritten > 0
            synchronized(muxLock) {
                try {
                    if (muxerStarted) muxer?.stop() else ok = false
                } catch (_: Exception) { ok = false }
            }
            handler.post {
                finish(saveFile = ok)
                Toast.makeText(
                    this,
                    if (ok) "갤러리에 저장됐어요 ($FOLDER)" else "녹화가 너무 짧아 저장하지 못했어요",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun releaseAudio() {
        try { internalRec?.stop() } catch (_: Exception) {}
        try { internalRec?.release() } catch (_: Exception) {}
        internalRec = null
        try { micRec?.stop() } catch (_: Exception) {}
        try { micRec?.release() } catch (_: Exception) {}
        micRec = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
    }

    private fun finish(saveFile: Boolean) {
        isRecording = false
        stopFlag = true
        handler.removeCallbacks(tick)
        removeOverlay()

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        releaseAudio()
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false; videoTrack = -1; audioTrack = -1; pending.clear()

        val proj = projection
        projection = null
        try { proj?.stop() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null

        videoUri?.let { uri ->
            try {
                if (saveFile) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, v, null, null)
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
            text = "00:00"; textSize = 16f; setTextColor(Color.WHITE)
        }
        timerText = time
        box.addView(time, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(12) })

        val stopBtn = TextView(this).apply {
            text = "■ 정지"; textSize = 15f; setTextColor(Color.WHITE)
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
            x = dp(16); y = dp(120)
        }

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
        if (isRecording) {
            stopFlag = true
            finish(saveFile = false)
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
