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
import android.graphics.Color
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
import androidx.camera.core.CameraFilter
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.ImageView

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: Button
    private lateinit var cameraButton: Button
    private lateinit var recordIcon: TextView
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var zoomText: TextView
    private lateinit var stabilizationText: TextView
    private lateinit var horizonText: TextView
    private lateinit var exposureText: TextView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var lastGyroUpdateNs = 0L
    private var gyroMotion = 0f

    private var recorder: Recorder? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var zoomRatio = 1f
    private var stabilizationOn = true
    private var exposureIndex = 0
    private var wideMode = false
    private var horizonLockOn = true
    private var loopRecordingOn = true
    private var loopDurationMs = 3 * 60 * 1000L
    private var recordingStartedAt = 0L
    private var audioEnhancementOn = true
    private var noiseSuppressorAvailable = false
    private var segmentNumber = 1
    private var stoppingForLoop = false
    private var cameraActive = false
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
        if (cameraGranted) {
            startCamera(audioGranted)
        } else {
            Toast.makeText(this, "Izin kamera diperlukan untuk membuka kamera", Toast.LENGTH_LONG).show()
            cameraButton.text = "BUKA KAMERA"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        noiseSuppressorAvailable = try { android.media.audiofx.NoiseSuppressor.isAvailable() } catch (_: Throwable) { false }
        buildUi()
        // V15: kamera TIDAK otomatis aktif saat aplikasi dibuka.
        // Pengguna harus menekan "BUKA KAMERA" terlebih dahulu.
        cameraActive = false
        statusText.text = "CAM OFF"
        cameraButton.text = "BUKA KAMERA"
        recordButton.isEnabled = false
        recordButton.alpha = 0.45f
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
        val title = textView("JEJAK CAM", 20f, true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        top.addView(title, LinearLayout.LayoutParams(0, dp(54), 1f))

        val resolution = textView("1080p  •  30 FPS", 13f, true).apply {
            background = getDrawable(com.jejakcam.app.R.drawable.bg_chip)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        top.addView(resolution, LinearLayout.LayoutParams(dp(138), dp(40)).apply { gravity = Gravity.CENTER_VERTICAL })
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
        statusBox.addView(statusText, LinearLayout.LayoutParams(dp(50), -1))
        timerText = textView("00:00", 12f, true)
        statusBox.addView(timerText, LinearLayout.LayoutParams(dp(50), -1))
        root.addView(statusBox, FrameLayout.LayoutParams(dp(124), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(76) })

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

        // Stabilization is enabled at the camera/video pipeline when the device supports it.
        // The button is intentionally informational so it cannot accidentally disable EIS
        // while riding.
        stabilizationText = textView("STAB  AUTO", 12f, true).apply {
            background = getDrawable(R.drawable.bg_toggle)
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Stabilisasi mengikuti kemampuan kamera HP", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(stabilizationText, FrameLayout.LayoutParams(dp(112), dp(38), Gravity.END or Gravity.TOP).apply { rightMargin = dp(16); topMargin = dp(76) })

        val audioText = textView("MIC ENH", 11f, true).apply {
            background = getDrawable(R.drawable.bg_toggle)
            setOnClickListener {
                audioEnhancementOn = !audioEnhancementOn
                text = if (audioEnhancementOn) "MIC ENH" else "MIC RAW"
                val note = if (noiseSuppressorAvailable) "DSP noise suppression tersedia di perangkat" else "DSP audio bergantung pada HP"
                Toast.makeText(this@MainActivity, if (audioEnhancementOn) "Audio enhancement ON • $note" else "Audio enhancement OFF", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(audioText, FrameLayout.LayoutParams(dp(96), dp(34), Gravity.END or Gravity.TOP).apply { rightMargin = dp(16); topMargin = dp(118) })

        // Quick exposure controls for changing light while riding.
        val exposurePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_control)
        }
        val exposureMinus = Button(this).apply {
            text = "−"; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); background = getDrawable(R.drawable.bg_zoom)
            setOnClickListener { changeExposure(-1) }
        }
        exposureText = textView("EV 0", 11f, true)
        val exposurePlus = Button(this).apply {
            text = "+"; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); background = getDrawable(R.drawable.bg_zoom)
            setOnClickListener { changeExposure(1) }
        }
        exposurePanel.addView(exposureMinus, LinearLayout.LayoutParams(dp(42), dp(42)))
        exposurePanel.addView(exposureText, LinearLayout.LayoutParams(dp(48), dp(42)))
        exposurePanel.addView(exposurePlus, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(exposurePanel, FrameLayout.LayoutParams(dp(140), dp(48), Gravity.START or Gravity.BOTTOM).apply { leftMargin = dp(14); bottomMargin = dp(170) })

        // Horizon assist: sensor-driven level indicator. The camera/video stabilization
        // remains hardware/device controlled; this indicator helps keep the motorcycle
        // mount level while recording.
        horizonText = textView("— LEVEL • GYRO —", 12f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            alpha = 0.88f
        }
        root.addView(horizonText, FrameLayout.LayoutParams(dp(120), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(120) })

        val horizonToggle = textView("HORIZON ON", 11f, true).apply {
            background = getDrawable(R.drawable.bg_toggle)
            setOnClickListener {
                horizonLockOn = !horizonLockOn
                text = if (horizonLockOn) "HORIZON ON" else "HORIZON OFF"
                Toast.makeText(this@MainActivity, if (horizonLockOn) "Horizon Lock aktif" else "Horizon Lock nonaktif", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(horizonToggle, FrameLayout.LayoutParams(dp(112), dp(36), Gravity.START or Gravity.TOP).apply { leftMargin = dp(14); topMargin = dp(76) })

        // Bottom controls
        val bottom = FrameLayout(this).apply { background = getDrawable(R.drawable.bg_bottom) }
        val hint = textView("Tap untuk fokus  •  Rekam video teknisi", 12f).apply { alpha = 0.78f }
        bottom.addView(hint, FrameLayout.LayoutParams(-1, dp(30), Gravity.TOP).apply { topMargin = dp(7) })

        cameraButton = Button(this).apply {
            text = "BUKA KAMERA"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.bg_control)
            elevation = dp(5).toFloat()
            setOnClickListener { openCameraFromButton() }
        }
        bottom.addView(cameraButton, FrameLayout.LayoutParams(dp(126), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })

        recordButton = Button(this).apply {
            text = ""
            background = getDrawable(R.drawable.bg_record)
            elevation = dp(8).toFloat()
            setOnClickListener { toggleRecording() }
        }
        bottom.addView(recordButton, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER).apply { topMargin = dp(76) })

        recordIcon = textView("●", 30f, true).apply { setTextColor(0xFF111111.toInt()); isClickable = false }
        bottom.addView(recordIcon, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER).apply { topMargin = dp(76) })

        val gallery = textView("▣", 26f).apply { background = getDrawable(R.drawable.bg_control) }
        gallery.setOnClickListener { Toast.makeText(this, "Video tersimpan otomatis di Galeri", Toast.LENGTH_SHORT).show() }
        bottom.addView(gallery, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(28); topMargin = dp(26) })

        val flip = textView("0.5×", 18f, true).apply { background = getDrawable(R.drawable.bg_control) }
        flip.setOnClickListener { toggleWideCamera() }
        bottom.addView(flip, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(28); topMargin = dp(26) })

        val quickRec = textView("QUICK", 11f, true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener { toggleRecording() }
        }
        bottom.addView(quickRec, FrameLayout.LayoutParams(dp(64), dp(44), Gravity.START or Gravity.BOTTOM).apply { leftMargin = dp(88); bottomMargin = dp(16) })

        val loopButton = textView("LOOP 3m", 10f, true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener {
                loopRecordingOn = !loopRecordingOn
                text = if (loopRecordingOn) "LOOP 3m" else "LOOP OFF"
                Toast.makeText(this@MainActivity, if (loopRecordingOn) "Loop Recording aktif • 3 menit" else "Loop Recording nonaktif", Toast.LENGTH_SHORT).show()
            }
        }
        bottom.addView(loopButton, FrameLayout.LayoutParams(dp(72), dp(44), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(88); bottomMargin = dp(16) })

        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(164), Gravity.BOTTOM))
        setContentView(root)

        previewView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                try {
                    camera?.cameraControl?.startFocusAndMetering(
                        androidx.camera.core.FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(1, TimeUnit.SECONDS)
                            .build()
                    )
                } catch (_: Exception) { }
            }
            true
        }
    }

    private fun supportsVideoStabilization(cameraInfo: CameraInfo): Boolean {
        return try {
            val modes = Camera2CameraInfo.from(cameraInfo)
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                )
            modes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun openCameraFromButton() {
        if (cameraActive) {
            Toast.makeText(this, "Kamera sudah aktif", Toast.LENGTH_SHORT).show()
            return
        }
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted || !audioGranted) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            startCamera(true)
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
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
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
                    currentCameraSelector(),
                    preview,
                    videoCapture
                )
                cameraActive = true
                cameraButton.text = "KAMERA AKTIF"
                cameraButton.alpha = 0.7f
                recordButton.isEnabled = true
                recordButton.alpha = 1f
                setZoom(0f)
                exposureIndex = 0
                camera?.cameraControl?.setExposureCompensationIndex(0)
                exposureText.text = "EV 0"
                statusText.text = if (supportsVideoStabilization(camera?.cameraInfo ?: return@addListener)) if (wideMode) "WIDE • EIS" else "ACTION • EIS" else if (wideMode) "WIDE" else "ACTION"
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
                        currentCameraSelector(),
                        fallbackPreview,
                        videoCapture
                    )
                    cameraActive = true
                    cameraButton.text = "KAMERA AKTIF"
                    cameraButton.alpha = 0.7f
                    recordButton.isEnabled = true
                    recordButton.alpha = 1f
                    setZoom(0f)
                    exposureIndex = 0
                    camera?.cameraControl?.setExposureCompensationIndex(0)
                    exposureText.text = "EV 0"
                    statusText.text = if (supportsVideoStabilization(camera?.cameraInfo ?: return@addListener)) if (wideMode) "WIDE • EIS" else "ACTION • EIS" else if (wideMode) "WIDE" else "ACTION"
                } catch (fallbackError: Exception) {
                    Toast.makeText(this, "Kamera gagal dibuka: ${fallbackError.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Prefer the widest rear physical camera exposed by CameraX when Wide mode is on.
     * Many phones expose 0.5x as a separate camera ID; devices that do not expose one
     * safely fall back to the normal rear camera.
     */
    private fun currentCameraSelector(): CameraSelector {
        if (!wideMode) return CameraSelector.DEFAULT_BACK_CAMERA
        return try {
            CameraSelector.Builder()
                .addCameraFilter(object : CameraFilter {
                    override fun filter(cameraInfos: MutableList<CameraInfo>): MutableList<CameraInfo> {
                        val candidates = cameraInfos.filter { info ->
                            try {
                                val facing = Camera2CameraInfo.from(info)
                                    .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                                facing == CameraMetadata.LENS_FACING_BACK
                            } catch (_: Exception) { false }
                        }
                        if (candidates.isEmpty()) return mutableListOf()
                        val widest = candidates.minByOrNull { info ->
                            try {
                                val focals = Camera2CameraInfo.from(info)
                                    .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                focals?.minOrNull() ?: Float.MAX_VALUE
                            } catch (_: Exception) { Float.MAX_VALUE }
                        }
                        return if (widest != null) mutableListOf(widest) else mutableListOf()
                    }
                })
                .build()
        } catch (_: Exception) {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun toggleWideCamera() {
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman sebelum mengganti kamera", Toast.LENGTH_SHORT).show()
            return
        }
        wideMode = !wideMode
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            try {
                provider.unbindAll()
                // Rebuild through startCamera so the same AF/stabilization settings apply.
                startCamera(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                Toast.makeText(
                    this,
                    if (wideMode) "WIDE / ULTRA-WIDE aktif" else "KAMERA UTAMA aktif",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                wideMode = !wideMode
                Toast.makeText(this, "Mode wide tidak tersedia di HP ini", Toast.LENGTH_SHORT).show()
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

    private fun changeExposure(delta: Int) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        exposureIndex = (exposureIndex + delta).coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(exposureIndex)
        exposureText.text = if (exposureIndex == 0) "EV 0" else String.format("EV %+d", exposureIndex)
    }

    private fun makeOutputOptions(): MediaStoreOutputOptions {
        val name = String.format("JejakCam_%tY%<tm%<td_%<tH%<tM%<tS_S%02d.mp4", java.util.Date(), segmentNumber)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JejakCam")
        }
        return MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
    }

    private fun resetRecordUi() {
        timerHandler.removeCallbacks(timerRunnable)
        timerText.text = "00:00"
        statusText.text = "READY"
        recordButton.background = getDrawable(R.drawable.bg_record)
        recordIcon.text = "●"
        recordIcon.setTextColor(0xFF111111.toInt())
    }

    private fun startSegment() {
        val r = recorder ?: return
        val pending = r.prepareRecording(this, makeOutputOptions())
        val prepared = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        } else pending
        recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAt = System.currentTimeMillis()
                    statusText.text = if (loopRecordingOn) "REC • LOOP" else "REC"
                    timerHandler.removeCallbacks(timerRunnable)
                    timerHandler.post(timerRunnable)
                    recordButton.background = getDrawable(R.drawable.bg_record_active)
                    recordIcon.text = "■"
                    recordIcon.setTextColor(0xFFFFFFFF.toInt())
                    if (loopRecordingOn) {
                        timerHandler.postDelayed({
                            if (recording != null && !stoppingForLoop && loopRecordingOn) {
                                stoppingForLoop = true
                                recording?.stop()
                            }
                        }, loopDurationMs)
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Toast.makeText(this, "Gagal menyimpan video: ${event.error}", Toast.LENGTH_LONG).show()
                        recording = null
                        stoppingForLoop = false
                        resetRecordUi()
                    } else if (stoppingForLoop && loopRecordingOn) {
                        recording = null
                        segmentNumber++
                        stoppingForLoop = false
                        startSegment()
                    } else {
                        recording = null
                        resetRecordUi()
                    }
                }
            }
        }
    }

    private fun toggleRecording() {
        if (!cameraActive || recorder == null) {
            Toast.makeText(this, "Tekan BUKA KAMERA terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording != null) {
            stoppingForLoop = false
            recording?.stop()
            recording = null
            resetRecordUi()
            return
        }
        segmentNumber = 1
        stoppingForLoop = false
        startSegment()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Motion indicator only. Actual frame correction is left to the camera's
            // hardware/CameraX video stabilization so we do not distort the recording.
            val magnitude = kotlin.math.sqrt(
                event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
            )
            gyroMotion = (gyroMotion * 0.82f + magnitude * 0.18f)
            return
        }

        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        var roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        if (roll > 180f) roll -= 360f
        if (roll < -180f) roll += 360f

        val clamped = roll.coerceIn(-20f, 20f)
        horizonText.rotation = if (horizonLockOn) -clamped else 0f
        val level = if (kotlin.math.abs(roll) < 2.5f) "LEVEL" else String.format("%.0f°", roll)
        val motion = when {
            gyroMotion < 0.25f -> "SMOOTH"
            gyroMotion < 0.8f -> "MOVE"
            else -> "SHAKE"
        }
        horizonText.text = "— $level • $motion —"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        recording?.stop()
        timerHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        cameraActive = false
        super.onDestroy()
    }
}
