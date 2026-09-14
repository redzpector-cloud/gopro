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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
    private var imageCapture: ImageCapture? = null
    private var photoMode = false
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
        // V17: kamera TIDAK otomatis aktif saat aplikasi dibuka.
        // Pengguna harus menekan "BUKA KAMERA" terlebih dahulu.
        cameraActive = false
        previewView.visibility = View.GONE
        cameraScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
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
        root.setBackgroundColor(Color.BLACK)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            visibility = View.GONE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // ===================== HOME / CAMERA OFF =====================
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
        }
        val logo = textView("◆", 42f, true).apply { setTextColor(0xFFFFD21F.toInt()) }
        home.addView(logo, LinearLayout.LayoutParams(-1, dp(60)))
        val brand = textView("JEJAKCAM", 30f, true)
        home.addView(brand, LinearLayout.LayoutParams(-1, dp(42)))
        val sub = textView("ACTION CAMERA", 13f, true).apply { setTextColor(0xFFFFD21F.toInt()); letterSpacing = 0.18f }
        home.addView(sub, LinearLayout.LayoutParams(-1, dp(32)))
        val tagline = textView("STABIL  •  SIMPLE  •  POWERFUL", 11f).apply { alpha = .7f }
        home.addView(tagline, LinearLayout.LayoutParams(-1, dp(34)))

        val openCard = Button(this).apply {
            text = "▶   BUKA KAMERA"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = getDrawable(R.drawable.bg_record)
            setOnClickListener { openCameraFromButton() }
        }
        cameraButton = openCard
        home.addView(openCard, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(28) })

        val homeInfo = textView("Kamera OFF saat aplikasi dibuka\nTekan tombol di atas untuk mulai", 12f).apply { alpha = .65f }
        home.addView(homeInfo, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        val homeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val galleryHome = Button(this).apply {
            text = "GALERI"; textSize = 12f; setTextColor(Color.WHITE); background = getDrawable(R.drawable.bg_control)
            setOnClickListener { Toast.makeText(this@MainActivity, "Video tersimpan di Galeri > Movies > JejakCam", Toast.LENGTH_SHORT).show() }
        }
        val settingsHome = Button(this).apply {
            text = "PENGATURAN"; textSize = 12f; setTextColor(Color.WHITE); background = getDrawable(R.drawable.bg_control)
            setOnClickListener { Toast.makeText(this@MainActivity, "V17: 1080p 30fps • EIS • Gyro • Loop 3m", Toast.LENGTH_SHORT).show() }
        }
        homeRow.addView(galleryHome, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        homeRow.addView(settingsHome, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        home.addView(homeRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        val version = textView("V17  •  JEJAK TEKNISI", 10f).apply { alpha = .45f }
        home.addView(version, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(24) })
        homeScreen = home
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // ===================== ACTION-CAM HUD =====================
        val hud = FrameLayout(this).apply { visibility = View.GONE }

        val topLeft = textView("ACTION", 13f, true).apply { background = getDrawable(R.drawable.bg_chip); setPadding(dp(12),0,dp(12),0) }
        hud.addView(topLeft, FrameLayout.LayoutParams(dp(94), dp(38), Gravity.TOP or Gravity.START).apply { leftMargin=dp(14); topMargin=dp(14) })

        val resolution = textView("1080P  •  30", 13f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(10),0,dp(10),0) }
        hud.addView(resolution, FrameLayout.LayoutParams(dp(108), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(14) })

        val battery = textView("●  92%", 12f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(9),0,dp(9),0) }
        hud.addView(battery, FrameLayout.LayoutParams(dp(76), dp(38), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(14) })

        val quickSettings = textView("⚙", 20f, true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener {
                val info = if (photoMode) "FOTO • 1.0x • EV $exposureIndex"
                else "VIDEO • FHD 30fps • EIS • ${if (horizonLockOn) "HORIZON ON" else "HORIZON OFF"}"
                Toast.makeText(this@MainActivity, info, Toast.LENGTH_SHORT).show()
            }
        }
        hud.addView(quickSettings, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(62) })

        statusText = textView("READY", 12f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(10),0,dp(10),0) }
        hud.addView(statusText, FrameLayout.LayoutParams(dp(96), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(62) })
        timerText = textView("00:00", 15f, true)
        hud.addView(timerText, FrameLayout.LayoutParams(dp(100), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(94) })

        horizonText = textView("— LEVEL • SMOOTH —", 11f, true).apply { background=getDrawable(R.drawable.bg_chip); alpha=.88f }
        hud.addView(horizonText, FrameLayout.LayoutParams(dp(142), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(136) })

        val horizonToggle = textView("HORIZON ON", 11f, true).apply {
            background=getDrawable(R.drawable.bg_toggle)
            setOnClickListener { horizonLockOn=!horizonLockOn; text=if(horizonLockOn) "HORIZON ON" else "HORIZON OFF" }
        }
        hud.addView(horizonToggle, FrameLayout.LayoutParams(dp(112), dp(36), Gravity.TOP or Gravity.START).apply { leftMargin=dp(14); topMargin=dp(64) })

        stabilizationText = textView("STAB  AUTO", 11f, true).apply {
            background=getDrawable(R.drawable.bg_toggle)
            setOnClickListener { Toast.makeText(this@MainActivity,"EIS mengikuti kemampuan kamera HP",Toast.LENGTH_SHORT).show() }
        }
        hud.addView(stabilizationText, FrameLayout.LayoutParams(dp(112), dp(36), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(64) })

        val zoomPanel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; background=getDrawable(R.drawable.bg_control); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        val plus=Button(this).apply { text="+"; textSize=20f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_zoom); setOnClickListener{setZoom(.5f)} }
        val minus=Button(this).apply { text="−"; textSize=20f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_zoom); setOnClickListener{setZoom(-.5f)} }
        zoomText=textView("1.0×",11f,true)
        zoomPanel.addView(plus,LinearLayout.LayoutParams(dp(46),dp(46)))
        zoomPanel.addView(zoomText,LinearLayout.LayoutParams(dp(46),dp(28)))
        zoomPanel.addView(minus,LinearLayout.LayoutParams(dp(46),dp(46)))
        hud.addView(zoomPanel,FrameLayout.LayoutParams(dp(58),dp(130),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=dp(12)})

        val exposurePanel=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;background=getDrawable(R.drawable.bg_control)}
        val em=Button(this).apply{text="−";textSize=18f;setTextColor(Color.WHITE);background=getDrawable(R.drawable.bg_zoom);setOnClickListener{changeExposure(-1)}}
        exposureText=textView("EV 0",11f,true)
        val ep=Button(this).apply{text="+";textSize=18f;setTextColor(Color.WHITE);background=getDrawable(R.drawable.bg_zoom);setOnClickListener{changeExposure(1)}}
        exposurePanel.addView(em,LinearLayout.LayoutParams(dp(42),dp(42))); exposurePanel.addView(exposureText,LinearLayout.LayoutParams(dp(48),dp(42))); exposurePanel.addView(ep,LinearLayout.LayoutParams(dp(42),dp(42)))
        hud.addView(exposurePanel,FrameLayout.LayoutParams(dp(136),dp(46),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(12)})

        val bottomShade=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(18),dp(10),dp(18),dp(10));background=getDrawable(R.drawable.bg_bottom)}
        val modeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val videoMode=textView("VIDEO",12f,true).apply{setPadding(dp(18),0,dp(18),0)}
        val photoModeView=textView("FOTO",12f,true).apply{alpha=.55f;setPadding(dp(18),0,dp(18),0);setOnClickListener{setPhotoMode(true)}}
        videoMode.setOnClickListener { setPhotoMode(false) }
        modeRow.addView(videoMode); modeRow.addView(photoModeView); bottomShade.addView(modeRow,LinearLayout.LayoutParams(-1,dp(30)))

        val controls=FrameLayout(this)
        val gallery=textView("▣",25f).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{Toast.makeText(this@MainActivity,"Galeri JejakCam",Toast.LENGTH_SHORT).show()}}
        controls.addView(gallery,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.START or Gravity.CENTER_VERTICAL))
        recordButton=Button(this).apply{text="";background=getDrawable(R.drawable.bg_record);elevation=dp(7).toFloat();setOnClickListener{toggleRecording()}}
        controls.addView(recordButton,FrameLayout.LayoutParams(dp(84),dp(84),Gravity.CENTER))
        recordIcon=textView("●",29f,true).apply{setTextColor(0xFF111111.toInt());isClickable=false}
        controls.addView(recordIcon,FrameLayout.LayoutParams(dp(84),dp(84),Gravity.CENTER))
        val flip=textView("0.5×",16f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{toggleWideCamera()}}
        val close=textView("✕",18f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{closeCamera()}}
        hud.addView(close,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply{rightMargin=dp(14);topMargin=dp(14)})
        controls.addView(flip,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.END or Gravity.CENTER_VERTICAL))
        bottomShade.addView(controls,LinearLayout.LayoutParams(-1,dp(90)))

        val quickRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val quick=textView("QUICK REC",10f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{toggleRecording()}}
        val loop=textView("LOOP 3m",10f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{loopRecordingOn=!loopRecordingOn;text=if(loopRecordingOn)"LOOP 3m" else "LOOP OFF"}}
        val mic=textView("MIC ENH",10f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{audioEnhancementOn=!audioEnhancementOn;text=if(audioEnhancementOn)"MIC ENH" else "MIC RAW"}}
        quickRow.addView(quick,LinearLayout.LayoutParams(dp(88),dp(38)).apply{rightMargin=dp(5)});quickRow.addView(loop,LinearLayout.LayoutParams(dp(76),dp(38)).apply{leftMargin=dp(5);rightMargin=dp(5)});quickRow.addView(mic,LinearLayout.LayoutParams(dp(76),dp(38)).apply{leftMargin=dp(5)})
        bottomShade.addView(quickRow,LinearLayout.LayoutParams(-1,dp(40)))
        hud.addView(bottomShade,FrameLayout.LayoutParams(-1,dp(184),Gravity.BOTTOM))

        root.addView(hud,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)

        previewView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                try { camera?.cameraControl?.startFocusAndMetering(
                    androidx.camera.core.FocusMeteringAction.Builder(point).setAutoCancelDuration(1, TimeUnit.SECONDS).build()
                ) } catch (_: Exception) { }
            }
            true
        }

        // Keep the camera screen hidden until the user explicitly opens the camera.
        cameraScreen = hud
    }

    private lateinit var cameraScreen: View
    private lateinit var homeScreen: View

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

            val image = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = image

            provider.unbindAll()
            try {
                camera = provider.bindToLifecycle(
                    this,
                    currentCameraSelector(),
                    preview,
                    videoCapture,
                    image
                )
                cameraActive = true
                previewView.visibility = View.VISIBLE
                cameraScreen.visibility = View.VISIBLE
                homeScreen.visibility = View.GONE
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
                        videoCapture,
                        image
                    )
                    cameraActive = true
                    previewView.visibility = View.VISIBLE
                    cameraScreen.visibility = View.VISIBLE
                    homeScreen.visibility = View.GONE
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

    private fun setPhotoMode(enabled: Boolean) {
        if (!cameraActive) return
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman dulu", Toast.LENGTH_SHORT).show()
            return
        }
        photoMode = enabled
        recordIcon.text = if (enabled) "○" else "●"
        statusText.text = if (enabled) "PHOTO READY" else "READY"
        recordButton.background = getDrawable(R.drawable.bg_record)
        Toast.makeText(this, if (enabled) "Mode FOTO" else "Mode VIDEO", Toast.LENGTH_SHORT).show()
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = String.format("JejakCam_%tY%<tm%<td_%<tH%<tM%<tS.jpg", java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JejakCam")
        }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                statusText.text = "PHOTO SAVED"
                Toast.makeText(this@MainActivity, "Foto tersimpan di Galeri > Pictures > JejakCam", Toast.LENGTH_SHORT).show()
            }
            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Gagal mengambil foto: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun closeCamera() {
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        camera = null
        recorder = null
        imageCapture = null
        cameraActive = false
        previewView.visibility = View.GONE
        cameraScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
        cameraButton.text = "▶   BUKA KAMERA"
        cameraButton.alpha = 1f
        recordButton.isEnabled = false
        recordButton.alpha = .45f
        statusText.text = "CAM OFF"
    }

    private fun toggleRecording() {
        if (!cameraActive || recorder == null) {
            Toast.makeText(this, "Tekan BUKA KAMERA terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (photoMode) {
            capturePhoto()
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
