package com.my.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 100
        private const val REQ_PERMS = 101
    }

    private lateinit var recordButton: TextView
    private lateinit var audioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(64), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "녹화 버튼을 누르면 화면 녹화가 시작되고,\n화면 위에 뜨는 [■ 정지] 버튼으로 멈출 수 있어요.\n영상은 갤러리(Movies/MyRecorder)에 자동 저장돼요.\n※ 내부 소리도 \"녹음\" 권한이 필요해요."
            textSize = 14f
            setTextColor(Color.parseColor("#616161"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }
        root.addView(sub)

        recordButton = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
            setOnClickListener { onRecordClicked() }
        }
        root.addView(recordButton, LinearLayout.LayoutParams(dp(170), dp(170)))

        val audioTitle = TextView(this).apply {
            text = "녹음할 소리"
            textSize = 15f
            setTextColor(Color.parseColor("#424242"))
        }
        root.addView(audioTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(32) })

        audioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            RecorderService.AUDIO_INTERNAL to "내부 소리 (유튜브·게임 소리)",
            RecorderService.AUDIO_BOTH to "내부 소리 + 마이크",
            RecorderService.AUDIO_MIC to "마이크만",
            RecorderService.AUDIO_NONE to "소리 없음"
        )
        for ((mode, label) in options) {
            audioGroup.addView(RadioButton(this).apply {
                id = 1000 + mode
                text = label
                textSize = 16f
            })
        }
        audioGroup.check(1000 + RecorderService.AUDIO_INTERNAL)
        root.addView(audioGroup)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun updateButton() {
        if (RecorderService.isRecording) {
            recordButton.text = "■\n녹화 정지"
            setAudioEnabled(false)
        } else {
            recordButton.text = "●\n녹화 시작"
            setAudioEnabled(true)
        }
    }

    private fun audioMode(): Int = audioGroup.checkedRadioButtonId - 1000

    private fun setAudioEnabled(on: Boolean) {
        for (i in 0 until audioGroup.childCount) audioGroup.getChildAt(i).isEnabled = on
    }

    private fun onRecordClicked() {
        // 이미 녹화 중이면 정지
        if (RecorderService.isRecording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            recordButton.postDelayed({ updateButton() }, 500)
            return
        }

        // 1) 다른 앱 위에 표시 권한 (떠 있는 정지 버튼용)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 켜고 다시 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        // 2) 알림 / 마이크 권한
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (audioMode() != RecorderService.AUDIO_NONE &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
            return
        }

        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (audioMode() != RecorderService.AUDIO_NONE &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                audioGroup.check(1000 + RecorderService.AUDIO_NONE)
                Toast.makeText(this, "소리 녹음 권한이 없어 소리 없이 녹화해요", Toast.LENGTH_LONG).show()
            }
            requestCapture()
        }
    }

    // 3) 화면 녹화 허용 창
    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 녹화가 취소됐어요", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecorderService.EXTRA_DATA, data)
            putExtra(RecorderService.EXTRA_AUDIO, audioMode())
        }
        startForegroundService(svc)
        // 녹화할 화면으로 이동할 수 있게 앱을 뒤로 보냄
        moveTaskToBack(true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
