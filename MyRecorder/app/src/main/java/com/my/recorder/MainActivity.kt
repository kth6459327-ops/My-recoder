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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 100
        private const val REQ_PERMS = 101
    }

    private lateinit var recordButton: TextView
    private lateinit var micSwitch: Switch

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
            text = "녹화 버튼을 누르면 화면 녹화가 시작되고,\n화면 위에 뜨는 [■ 정지] 버튼으로 멈출 수 있어요.\n영상은 갤러리(Movies/MyRecorder)에 자동 저장돼요."
            textSize = 14f
            setTextColor(Color.parseColor("#616161"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(48))
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
        root.addView(recordButton, LinearLayout.LayoutParams(dp(200), dp(200)))

        micSwitch = Switch(this).apply {
            text = "마이크 소리도 녹음  "
            textSize = 16f
            isChecked = false
        }
        root.addView(micSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(40) })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun updateButton() {
        if (RecorderService.isRecording) {
            recordButton.text = "■\n녹화 정지"
            micSwitch.isEnabled = false
        } else {
            recordButton.text = "●\n녹화 시작"
            micSwitch.isEnabled = true
        }
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
        if (micSwitch.isChecked &&
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
            if (micSwitch.isChecked &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                micSwitch.isChecked = false
                Toast.makeText(this, "마이크 권한이 없어 소리 없이 녹화해요", Toast.LENGTH_SHORT).show()
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
            putExtra(RecorderService.EXTRA_MIC, micSwitch.isChecked)
        }
        startForegroundService(svc)
        // 녹화할 화면으로 이동할 수 있게 앱을 뒤로 보냄
        moveTaskToBack(true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
