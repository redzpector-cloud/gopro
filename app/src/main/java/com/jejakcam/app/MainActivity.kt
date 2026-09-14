package com.jejakcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private var recorder: Recorder? = null
    private var recording: Recording? = null
    private lateinit var recordButton: Button
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) startCamera()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildUi()
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted || !audioGranted) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            startCamera()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply { text = "JEJAK CAM"; textSize = 20f; setTextColor(0xffffffff.toInt()); setPadding(24, 24, 0, 0) }
        root.addView(title, FrameLayout.LayoutParams(-1, 80, Gravity.TOP))

        recordButton = Button(this).apply { text = "●"; textSize = 28f; setOnClickListener { toggleRecording() } }
        val p = FrameLayout.LayoutParams(150, 110, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL); p.bottomMargin = 35
        root.addView(recordButton, p)

        val info = TextView(this).apply { text = "1080p • 30 FPS"; textSize = 14f; setTextColor(0xffffffff.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, 28) }
        root.addView(info, FrameLayout.LayoutParams(-1, 90, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
            val videoCapture = VideoCapture.withOutput(recorder!!)
            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Kamera gagal dibuka: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        val r = recorder ?: return
        if (recording != null) { recording!!.stop(); recording = null; recordButton.text = "●"; return }
        val name = "JejakCam_${System.currentTimeMillis()}.mp4"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        recording = r.prepareRecording(this, options).withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Start) recordButton.text = "■"
        }
    }
}
