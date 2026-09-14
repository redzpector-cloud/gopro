package com.jejakcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: Button
    private lateinit var recordIcon: TextView
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var zoomText: TextView
    private lateinit var stabilizationText: TextView

    private var recorder: Recorder? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var zoomRatio = 1f
    private var stabilizationOn = true
    private var recordingStartedAt = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (recording != null) {
                val elapsed = System.currentTimeMillis() - recordingStartedAt
                val seconds = elapsed / 1000
                timerText.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
                timerHandler.postDelayed(this, 500)
            }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) startCamera(audioGranted) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted || !audioGranted) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            startCamera(true)
        }
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    private fun textView(text: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFFFFFFF.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // Top action-camera HUD
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), 0)
        }
        val title = textView("JEJAK CAM", 21f, true)
        top.addView(title, LinearLayout.LayoutParams(0, dp(54), 1f))

        val resolution = textView("1080p  •  30 FPS", 13f, true).apply {
            background = getDrawable(com.jejakcam.app.R.drawable.bg_chip)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        top.addView(resolution, LinearLayout.LayoutParams(dp(130), dp(42)).apply { gravity = Gravity.CENTER_VERTICAL })
        root.addView(top, FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP))

        // Recording status
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val dot = TextView(this).apply { text = "●"; textSize = 12f; setTextColor(0xFFFFFFFF.toInt()) }
        statusBox.addView(dot, LinearLayout.LayoutParams(dp(18), -1))
        statusText = textView("READY", 12f, true)
        statusBox.addView(statusText, LinearLayout.LayoutParams(dp(58), -1))
        timerText = textView("00:00", 12f, true)
        statusBox.addView(timerText, LinearLayout.LayoutParams(dp(52), -1))
        root.addView(statusBox, FrameLayout.LayoutParams(dp(132), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(76) })

        // Vertical zoom controls
        val zoomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = getDrawable(R.drawable.bg_control)
        }
        val plus = Button(this).apply {
            text = "+"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); background = getDrawable(R.drawable.bg_zoom)
            setOnClickListener { setZoom(0.5f) }
        }
        val minus = Button(this).apply {
            text = "−"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); background = getDrawable(R.drawable.bg_zoom)
            setOnClickListener { setZoom(-0.5f) }
        }
        zoomText = textView("1.0×", 12f, true)
        zoomPanel.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
        zoomPanel.addView(zoomText, LinearLayout.LayoutParams(dp(48), dp(30)))
        zoomPanel.addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(zoomPanel, FrameLayout.LayoutParams(dp(62), dp(150), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(14) })

        // Stabilization toggle
        stabilizationText = textView("STAB  ON", 12f, true).apply {
            background = getDrawable(R.drawable.bg_toggle)
            setOnClickListener {
                stabilizationOn = !stabilizationOn
                text = if (stabilizationOn) "STAB  ON" else "STAB  OFF"
            }
        }
        root.addView(stabilizationText, FrameLayout.LayoutParams(dp(92), dp(38), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(16) })

        // Bottom controls
        val bottom = FrameLayout(this).apply { background = getDrawable(R.drawable.bg_bottom) }
        val hint = textView("Tap untuk fokus  •  Rekam video teknisi", 12f).apply { alpha = 0.78f }
        bottom.addView(hint, FrameLayout.LayoutParams(-1, dp(30), Gravity.TOP).apply { topMargin = dp(8) })

        recordButton = Button(this).apply {
            text = ""
            background = getDrawable(R.drawable.bg_record)
            elevation = dp(8).toFloat()
            setOnClickListener { toggleRecording() }
        }
        bottom.addView(recordButton, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER).apply { topMargin = dp(22) })

        recordIcon = textView("●", 30f, true).apply { setTextColor(0xFF111111.toInt()); isClickable = false }
        bottom.addView(recordIcon, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER).apply { topMargin = dp(22) })

        val gallery = textView("▣", 26f).apply { background = getDrawable(R.drawable.bg_control) }
        gallery.setOnClickListener { Toast.makeText(this, "Video tersimpan otomatis di Galeri", Toast.LENGTH_SHORT).show() }
        bottom.addView(gallery, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(28); topMargin = dp(18) })

        val flip = textView("↻", 28f).apply { background = getDrawable(R.drawable.bg_control) }
        flip.setOnClickListener { Toast.makeText(this, "Kamera depan/belakang akan ditambahkan berikutnya", Toast.LENGTH_SHORT).show() }
        bottom.addView(flip, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(28); topMargin = dp(18) })

        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(155), Gravity.BOTTOM))
        setContentView(root)

        previewView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                try {
                    camera?.cameraControl?.startFocusAndMetering(
                        androidx.camera.core.FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(2, TimeUnit.SECONDS)
                            .build()
                    )
                } catch (_: Exception) { }
            }
            true
        }
    }

    private fun startCamera(withAudio: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            // Action-camera tuning: continuous video AF + video stabilization.
            // CameraX 1.4+ provides hardware/device video stabilization when supported.
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    previewBuilder.setPreviewStabilizationEnabled(true)
                } catch (_: Exception) {
                    // Some HALs expose video stabilization but reject preview stabilization.
                }
            }
            val preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()

            val videoBuilder = VideoCapture.Builder(recorder!!)
            // Apply continuous video autofocus to the actual recording use case,
            // not only the preview. This keeps AF tracking active while riding.
            Camera2Interop.Extender(videoBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON
                )
            val videoCapture = try {
                videoBuilder
                    .setVideoStabilizationEnabled(true)
                    .build()
            } catch (_: Exception) {
                // Fall back to normal recording if this phone's camera HAL does not
                // support CameraX video stabilization.
                VideoCapture.withOutput(recorder!!)
            }

            provider.unbindAll()
            try {
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
                setZoom(0f)
                statusText.text = if (withAudio) "ACTION • STAB" else "ACTION • VIDEO"
            } catch (e: Exception) {
                // If preview stabilization causes a device-specific HAL error, retry
                // once without preview stabilization but keep recording stabilization.
                try {
                    provider.unbindAll()
                    val fallbackPreviewBuilder = Preview.Builder()
                    Camera2Interop.Extender(fallbackPreviewBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                    val fallbackPreview = fallbackPreviewBuilder.build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        fallbackPreview,
                        videoCapture
                    )
                    setZoom(0f)
                    statusText.text = if (withAudio) "ACTION • STAB" else "ACTION • VIDEO"
                } catch (fallbackError: Exception) {
                    Toast.makeText(this, "Kamera gagal dibuka: ${fallbackError.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(delta: Float) {
        val cam = camera ?: return
        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f
        val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        zoomRatio = if (delta == 0f) 1f else (zoomRatio + delta).coerceIn(min, max)
        cam.cameraControl.setZoomRatio(zoomRatio)
        zoomText.text = String.format("%.1f×", zoomRatio)
    }

    private fun toggleRecording() {
        val r = recorder ?: return
        if (recording != null) {
            recording?.stop()
            recording = null
            timerHandler.removeCallbacks(timerRunnable)
            timerText.text = "00:00"
            statusText.text = "READY"
            recordButton.background = getDrawable(R.drawable.bg_record)
            recordIcon.text = "●"
            recordIcon.setTextColor(0xFF111111.toInt())
            return
        }

        val name = "JejakCam_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JejakCam")
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()

        val pending = r.prepareRecording(this, options)
        val prepared = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        } else pending

        recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAt = System.currentTimeMillis()
                    statusText.text = "REC"
                    timerHandler.post(timerRunnable)
                    recordButton.background = getDrawable(R.drawable.bg_record_active)
                    recordIcon.text = "■"
                    recordIcon.setTextColor(0xFFFFFFFF.toInt())
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) Toast.makeText(this, "Gagal menyimpan video: ${event.error}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        recording?.stop()
        timerHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
