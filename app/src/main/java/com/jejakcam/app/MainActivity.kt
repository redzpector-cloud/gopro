package com.jejakcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ScaleGestureDetector
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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: Button
    private lateinit var cameraButton: Button
    private lateinit var recordIcon: TextView
    private lateinit var timerText: TextView
    private lateinit var loopText: TextView
    private lateinit var statusText: TextView
    private lateinit var zoomText: TextView
    private lateinit var stabilizationText: TextView
    private lateinit var horizonText: TextView
    private lateinit var exposureText: TextView
    private lateinit var tiltText: TextView
    private lateinit var batteryText: TextView
    private lateinit var storageText: TextView
    private lateinit var gpsText: TextView
    private lateinit var compassText: TextView
    private lateinit var gpsStatsText: TextView
    private lateinit var pauseButton: TextView
    private var batteryReceiver: BroadcastReceiver? = null
    private lateinit var locationManager: LocationManager
    private var gpsEnabled = false
    private var lastSpeedKmh = 0f
    private var lastGpsAccuracy = 0f
    private var gpsTrackPoints = mutableListOf<GpsPoint>()
    private var gpsTrackRecording = false
    private var gpsDistanceMeters = 0f
    private var gpsMaxSpeedKmh = 0f
    private var gpsSpeedSum = 0f
    private var gpsSpeedSamples = 0
    private var lastTrackLocation: Location? = null
    private data class GpsPoint(val timeMs: Long, val latitude: Double, val longitude: Double, val speedKmh: Float, val accuracyM: Float, val bearing: Float)
    private val gpsLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) lastSpeedKmh = (location.speed * 3.6f).coerceAtLeast(0f)
            if (location.hasAccuracy()) lastGpsAccuracy = location.accuracy
            if (gpsTrackRecording && recording != null && !recordingPaused) recordGpsPoint(location)
            updateGpsHud(location)
        }
        override fun onProviderEnabled(provider: String) { updateGpsStatus() }
        override fun onProviderDisabled(provider: String) { updateGpsStatus() }
    }
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var lastGyroUpdateNs = 0L
    private var gyroMotion = 0f
    private var filteredRoll = 0f
    private var filteredPitch = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var swipeZooming = false
    private lateinit var scaleDetector: ScaleGestureDetector

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
    // V34: loop segment duration is selectable from the camera HUD.
    private var loopDurationMs = 3 * 60 * 1000L
    private var loopDurationLabel = "3m"
    private var recordingStartedAt = 0L
    private var audioEnhancementOn = true
    private var audioEnabled = true
    private var noiseSuppressorAvailable = false
    private var segmentNumber = 1
    private var stoppingForLoop = false
    private var stopRequestedByUser = false
    private var countdownSeconds = 0
    private var countdownActive = false
    private var recordingPaused = false
    private var loopDeadlineMs = 0L
    private var loopRemainingMs = 0L
    private var selectedQuality = Quality.FHD
    private var selectedQualityLabel = "1080P"
    private var cameraActive = false
    private var requestedPhotoMode = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private val storageHandler = Handler(Looper.getMainLooper())
    private val storageRunnable = object : Runnable {
        override fun run() {
            if (::storageText.isInitialized) updateStorageHud()
            storageHandler.postDelayed(this, 3000L)
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (recording != null) {
                val elapsed = System.currentTimeMillis() - recordingStartedAt
                val seconds = elapsed / 1000
                timerText.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
                if (loopRecordingOn && loopDeadlineMs > 0L && !recordingPaused) {
                    val remaining = (loopDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    val rm = remaining / 60000
                    val rs = (remaining / 1000) % 60
                    loopText.text = String.format("SEG %02d • NEXT %02d:%02d", segmentNumber, rm, rs)
                    loopText.alpha = if (remaining <= 10000L) .95f else .72f
                } else {
                    loopText.text = if (recordingPaused) "PAUSED • SEG %02d".format(segmentNumber) else "SEG %02d".format(segmentNumber)
                    loopText.alpha = .72f
                }
                updateStorageHud()
                timerHandler.postDelayed(this, 500)
            }
        }
    }
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!countdownActive) return
            if (countdownSeconds <= 0) {
                countdownActive = false
                statusText.text = "REC • START"
                startSegment()
                return
            }
            statusText.text = "START DALAM $countdownSeconds"
            timerText.text = String.format("00:0%d", countdownSeconds)
            countdownSeconds--
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startGpsUpdates()
        else {
            gpsEnabled = false
            gpsText.text = "GPS OFF"
            Toast.makeText(this, "Izin lokasi ditolak • GPS tetap OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            photoMode = requestedPhotoMode
            startCamera(!requestedPhotoMode && audioGranted)
        } else {
            Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_LONG).show()
            statusText.text = "CAM OFF"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        noiseSuppressorAvailable = try { android.media.audiofx.NoiseSuppressor.isAvailable() } catch (_: Throwable) { false }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!cameraActive || recording != null) return true
                val cam = camera ?: return true
                val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f
                val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                zoomRatio = (zoomRatio * detector.scaleFactor).coerceIn(min, max)
                cam.cameraControl.setZoomRatio(zoomRatio)
                zoomText.text = String.format("%.1f×", zoomRatio)
                return true
            }
        })
        buildUi()
        updateGpsStatus()
        storageHandler.post(storageRunnable)
        updateStorageHud()
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
            setOnClickListener { Toast.makeText(this@MainActivity, "V34: 4K/1080P • EIS • Gyro • Horizon HUD • MIC • TIMER • PAUSE • LOOP 1/3/5/10m", Toast.LENGTH_SHORT).show() }
        }
        homeRow.addView(galleryHome, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        homeRow.addView(settingsHome, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        home.addView(homeRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        val version = textView("V35  •  JEJAK TEKNISI", 10f).apply { alpha = .45f }
        home.addView(version, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(24) })
        homeScreen = home
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // ===================== ACTION-CAM HUD =====================
        val hud = FrameLayout(this).apply { visibility = View.GONE }

        val topLeft = textView("ACTION", 13f, true).apply { background = getDrawable(R.drawable.bg_chip); setPadding(dp(12),0,dp(12),0) }
        hud.addView(topLeft, FrameLayout.LayoutParams(dp(94), dp(38), Gravity.TOP or Gravity.START).apply { leftMargin=dp(14); topMargin=dp(14) })

        val resolution = textView("1080P  •  AUTO", 13f, true).apply {
            background=getDrawable(R.drawable.bg_chip); setPadding(dp(10),0,dp(10),0)
            setOnClickListener {
                if (recording != null) {
                    Toast.makeText(this@MainActivity, "Hentikan rekaman untuk mengganti kualitas", Toast.LENGTH_SHORT).show()
                } else {
                    cycleVideoQuality()
                    text = "$selectedQualityLabel  •  AUTO"
                }
            }
        }
        hud.addView(resolution, FrameLayout.LayoutParams(dp(108), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(14) })

        batteryText = textView("●  --%", 12f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(9),0,dp(9),0) }
        hud.addView(batteryText, FrameLayout.LayoutParams(dp(76), dp(38), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(14) })

        storageText = textView("STOR --", 9f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                Toast.makeText(this@MainActivity, storageDetails(), Toast.LENGTH_SHORT).show()
            }
        }
        hud.addView(storageText, FrameLayout.LayoutParams(dp(112), dp(30), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(106) })

        gpsText = textView("GPS OFF", 10f, true).apply {
            background = getDrawable(R.drawable.bg_toggle)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { toggleGps() }
        }
        hud.addView(gpsText, FrameLayout.LayoutParams(dp(104), dp(34), Gravity.TOP or Gravity.START).apply { leftMargin=dp(14); topMargin=dp(106) })

        compassText = textView("N 000°", 10f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(8), 0, dp(8), 0)
        }
        hud.addView(compassText, FrameLayout.LayoutParams(dp(104), dp(34), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(106) })

        gpsStatsText = textView("MAX 0 • AVG 0 • 0.0 km", 9f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(8), 0, dp(8), 0)
        }
        hud.addView(gpsStatsText, FrameLayout.LayoutParams(dp(230), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(132) })

        val quickSettings = textView("⚙", 20f, true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener {
                val info = if (photoMode) "FOTO • 1.0x • EV $exposureIndex"
                else "VIDEO • $selectedQualityLabel • FPS AUTO • EIS • ${if (horizonLockOn) "HORIZON ON" else "HORIZON OFF"}"
                Toast.makeText(this@MainActivity, info, Toast.LENGTH_SHORT).show()
            }
        }
        hud.addView(quickSettings, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin=dp(14); topMargin=dp(62) })

        statusText = textView("READY", 12f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(10),0,dp(10),0) }
        hud.addView(statusText, FrameLayout.LayoutParams(dp(96), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(62) })
        timerText = textView("00:00", 15f, true)
        hud.addView(timerText, FrameLayout.LayoutParams(dp(100), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(94) })
        loopText = textView("SEG 01", 9f, true).apply { alpha = .72f }
        hud.addView(loopText, FrameLayout.LayoutParams(dp(170), dp(26), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(122) })

        horizonText = textView("— LEVEL • SMOOTH —", 11f, true).apply { background=getDrawable(R.drawable.bg_chip); alpha=.88f }
        hud.addView(horizonText, FrameLayout.LayoutParams(dp(142), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(152) })

        tiltText = textView("ROLL 0°  •  PITCH 0°", 10f, true).apply { background=getDrawable(R.drawable.bg_chip); alpha=.78f }
        hud.addView(tiltText, FrameLayout.LayoutParams(dp(150), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(190) })

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
        val photoModeView=textView("FOTO",12f,true).apply{alpha=.55f;setPadding(dp(18),0,dp(18),0);setOnClickListener{activateCameraMode(true)}}
        videoMode.setOnClickListener { activateCameraMode(false) }
        modeRow.addView(videoMode); modeRow.addView(photoModeView); bottomShade.addView(modeRow,LinearLayout.LayoutParams(-1,dp(30)))

        val controls=FrameLayout(this)
        val gallery=textView("▣",25f).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{Toast.makeText(this@MainActivity,"Galeri JejakCam",Toast.LENGTH_SHORT).show()}}
        controls.addView(gallery,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.START or Gravity.CENTER_VERTICAL))
        recordButton=Button(this).apply{text="";background=getDrawable(R.drawable.bg_record);elevation=dp(7).toFloat();setOnClickListener{toggleRecording()}}
        controls.addView(recordButton,FrameLayout.LayoutParams(dp(84),dp(84),Gravity.CENTER))
        recordIcon=textView("●",29f,true).apply{setTextColor(0xFF111111.toInt());isClickable=false}
        controls.addView(recordIcon,FrameLayout.LayoutParams(dp(84),dp(84),Gravity.CENTER))
        // V22: GoPro-style lens/zoom presets. 0.5x switches to the widest rear camera
        // when the device exposes one; the other presets use CameraX digital zoom.
        val flip=textView("0.5×",16f,true).apply{
            background=getDrawable(R.drawable.bg_control)
            setOnClickListener{
                if (recording != null) { Toast.makeText(this@MainActivity,"Hentikan rekaman sebelum mengganti lensa",Toast.LENGTH_SHORT).show() }
                else { if (!wideMode) toggleWideCamera() else setZoomToPreset(1f) }
            }
        }
        val close=textView("✕",18f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{closeCamera()}}
        hud.addView(close,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply{rightMargin=dp(14);topMargin=dp(14)})
        controls.addView(flip,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.END or Gravity.CENTER_VERTICAL))
        bottomShade.addView(controls,LinearLayout.LayoutParams(-1,dp(90)))

        val lensRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        fun lensButton(label:String, ratio:Float): TextView = textView(label,10f,true).apply{
            background=getDrawable(R.drawable.bg_control)
            setOnClickListener{setLensPreset(ratio)}
        }
        lensRow.addView(lensButton("0.5×",0.5f),LinearLayout.LayoutParams(dp(62),dp(34)).apply{rightMargin=dp(4)})
        lensRow.addView(lensButton("1×",1f),LinearLayout.LayoutParams(dp(62),dp(34)).apply{rightMargin=dp(4)})
        lensRow.addView(lensButton("2×",2f),LinearLayout.LayoutParams(dp(62),dp(34)).apply{rightMargin=dp(4)})
        lensRow.addView(lensButton("4×",4f),LinearLayout.LayoutParams(dp(62),dp(34)))
        bottomShade.addView(lensRow,LinearLayout.LayoutParams(-1,dp(38)))

        val quickRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val quick=textView("QUICK REC",10f,true).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{toggleRecording()}}
        val loop=textView("LOOP 3m",10f,true).apply{
            background=getDrawable(R.drawable.bg_control)
            setOnClickListener{
                if (recording != null || countdownActive) {
                    Toast.makeText(this@MainActivity, "Hentikan/ batalkan REC dulu untuk mengubah LOOP", Toast.LENGTH_SHORT).show()
                } else {
                    // V34: OFF -> 1m -> 3m -> 5m -> 10m -> OFF
                    when {
                        !loopRecordingOn -> {
                            loopRecordingOn = true
                            loopDurationMs = 1 * 60 * 1000L
                            loopDurationLabel = "1m"
                        }
                        loopDurationMs == 1 * 60 * 1000L -> {
                            loopDurationMs = 3 * 60 * 1000L
                            loopDurationLabel = "3m"
                        }
                        loopDurationMs == 3 * 60 * 1000L -> {
                            loopDurationMs = 5 * 60 * 1000L
                            loopDurationLabel = "5m"
                        }
                        loopDurationMs == 5 * 60 * 1000L -> {
                            loopDurationMs = 10 * 60 * 1000L
                            loopDurationLabel = "10m"
                        }
                        else -> {
                            loopRecordingOn = false
                            loopDurationLabel = "OFF"
                        }
                    }
                    text = if (loopRecordingOn) "LOOP $loopDurationLabel" else "LOOP OFF"
                    loopText.text = if (loopRecordingOn) "LOOP $loopDurationLabel • SEG 01" else "SEG 01"
                    statusText.text = if (loopRecordingOn) "LOOP $loopDurationLabel • SIAP" else "LOOP OFF • SIAP"
                }
            }
        }
        val mic=textView("MIC ON",10f,true).apply{
            background=getDrawable(R.drawable.bg_control)
            setOnClickListener{
                if (recording != null) {
                    Toast.makeText(this@MainActivity,"Hentikan rekaman untuk mengubah MIC",Toast.LENGTH_SHORT).show()
                } else {
                    audioEnabled=!audioEnabled
                    audioEnhancementOn=audioEnabled
                    text=if(audioEnabled)"MIC ON" else "MIC OFF"
                    Toast.makeText(this@MainActivity, if(audioEnabled) "Mic aktif • audio akan direkam" else "Mic mati • video tanpa audio", Toast.LENGTH_SHORT).show()
                }
            }
        }
        quickRow.addView(quick,LinearLayout.LayoutParams(dp(88),dp(38)).apply{rightMargin=dp(5)});quickRow.addView(loop,LinearLayout.LayoutParams(dp(76),dp(38)).apply{leftMargin=dp(5);rightMargin=dp(5)});quickRow.addView(mic,LinearLayout.LayoutParams(dp(76),dp(38)).apply{leftMargin=dp(5)})
        bottomShade.addView(quickRow,LinearLayout.LayoutParams(-1,dp(40)))

        val actionRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val countdownBtn=textView("TIMER OFF",10f,true).apply{
            background=getDrawable(R.drawable.bg_control)
            setOnClickListener{
                if(recording != null || countdownActive){
                    Toast.makeText(this@MainActivity,"Hentikan/ batalkan proses REC dulu",Toast.LENGTH_SHORT).show()
                } else {
                    countdownSeconds = when(countdownSeconds){0 -> 3; 3 -> 5; 5 -> 10; else -> 0}
                    text=if(countdownSeconds==0) "TIMER OFF" else "TIMER ${countdownSeconds}s"
                }
            }
        }
        pauseButton=textView("PAUSE",10f,true).apply{
            background=getDrawable(R.drawable.bg_control)
            alpha=.5f
            setOnClickListener{togglePauseResume(this)}
        }
        actionRow.addView(countdownBtn,LinearLayout.LayoutParams(dp(92),dp(36)).apply{rightMargin=dp(5)})
        actionRow.addView(pauseButton,LinearLayout.LayoutParams(dp(82),dp(36)).apply{leftMargin=dp(5)})
        bottomShade.addView(actionRow,LinearLayout.LayoutParams(-1,dp(38)))
        hud.addView(bottomShade,FrameLayout.LayoutParams(-1,dp(232),Gravity.BOTTOM))

        root.addView(hud,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val level = intent?.getIntExtra("level", -1) ?: -1
                val scale = intent?.getIntExtra("scale", 100) ?: 100
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100 / scale).coerceIn(0, 100)
                    batteryText.text = "●  $pct%"
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = System.currentTimeMillis()
                    swipeZooming = event.x > previewView.width * 0.70f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (swipeZooming && cameraActive && recording == null && !scaleDetector.isInProgress) {
                        val dy = touchDownY - event.y
                        if (kotlin.math.abs(dy) > dp(8)) {
                            setZoom(dy / (previewView.height.coerceAtLeast(1) / 5f))
                            touchDownY = event.y
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchDownTime
                    val moved = kotlin.math.abs(event.x - touchDownX) + kotlin.math.abs(event.y - touchDownY)
                    if (!swipeZooming && duration < 300 && moved < dp(18)) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        try { camera?.cameraControl?.startFocusAndMetering(
                            androidx.camera.core.FocusMeteringAction.Builder(point).setAutoCancelDuration(1, TimeUnit.SECONDS).build()
                        ) } catch (_: Exception) { }
                    }
                    swipeZooming = false
                    true
                }
                else -> true
            }
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
        // Tombol ini hanya membuka layar GoPro. Hardware kamera tetap OFF.
        // Kamera baru benar-benar dinyalakan setelah pengguna menekan VIDEO atau FOTO.
        previewView.visibility = View.VISIBLE
        cameraScreen.visibility = View.VISIBLE
        homeScreen.visibility = View.GONE
        statusText.text = "CAM OFF • PILIH MODE"
        recordButton.isEnabled = false
        recordButton.alpha = .45f
        recordIcon.text = "●"
        Toast.makeText(this, "Pilih VIDEO untuk menyalakan kamera", Toast.LENGTH_SHORT).show()
    }

    private fun activateCameraMode(photo: Boolean) {
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman dulu", Toast.LENGTH_SHORT).show()
            return
        }
        photoMode = photo
        requestedPhotoMode = photo
        if (cameraActive) {
            setPhotoMode(photo)
            return
        }
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            permissions.launch(if (photo || !audioEnabled) arrayOf(Manifest.permission.CAMERA) else arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else if (!photo && audioEnabled && !audioGranted) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            startCamera(!photo)
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

            // V23: pilih kualitas video yang diminta, dengan fallback aman ke FHD.
            // FPS tetap AUTO agar mengikuti kemampuan sensor/HAL perangkat.
            val qualitySelector = QualitySelector.from(
                selectedQuality,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )
            recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
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
                stabilizationText.text = if (supportsVideoStabilization(camera?.cameraInfo ?: return@addListener)) "STAB  HW" else "STAB  AUTO"
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
                stabilizationText.text = if (supportsVideoStabilization(camera?.cameraInfo ?: return@addListener)) "STAB  HW" else "STAB  AUTO"
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

    private fun setLensPreset(ratio: Float) {
        if (!cameraActive || camera == null) {
            Toast.makeText(this, "Pilih VIDEO atau FOTO terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman sebelum mengganti lensa", Toast.LENGTH_SHORT).show()
            return
        }
        if (ratio == 0.5f) {
            if (!wideMode) toggleWideCamera() else setZoomToPreset(1f)
        } else {
            if (wideMode) {
                wideMode = false
                val providerFuture = ProcessCameraProvider.getInstance(this)
                providerFuture.addListener({
                    try {
                        providerFuture.get().unbindAll()
                        startCamera(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        setZoomToPreset(ratio)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Kamera utama tidak tersedia", Toast.LENGTH_SHORT).show()
                    }
                }, ContextCompat.getMainExecutor(this))
            } else setZoomToPreset(ratio)
        }
    }

    private fun setZoomToPreset(ratio: Float) {
        val cam = camera ?: return
        val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f
        zoomRatio = ratio.coerceIn(min, max)
        cam.cameraControl.setZoomRatio(zoomRatio)
        zoomText.text = String.format("%.1f×", zoomRatio)
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
        timerHandler.removeCallbacks(countdownRunnable)
        countdownActive = false
        recordingPaused = false
        loopDeadlineMs = 0L
        loopRemainingMs = 0L
        timerText.text = "00:00"
        loopText.text = "SEG 01"
        loopText.alpha = .72f
        statusText.text = "READY"
        if (::pauseButton.isInitialized) { pauseButton.text = "PAUSE"; pauseButton.alpha = .5f }
        recordButton.background = getDrawable(R.drawable.bg_record)
        recordIcon.text = "●"
        recordIcon.setTextColor(0xFF111111.toInt())
    }

    private fun startSegment() {
        val r = recorder ?: return
        if (!gpsTrackRecording && gpsEnabled && segmentNumber == 1) beginGpsRecording()
        val pending = r.prepareRecording(this, makeOutputOptions())
        val prepared = if (audioEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        } else pending
        recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAt = System.currentTimeMillis()
                    recordingPaused = false
                    statusText.text = if (loopRecordingOn) "● REC • LOOP" else "● REC"
                    loopText.text = if (loopRecordingOn) "SEG %02d • NEXT %s".format(segmentNumber, loopDurationLabel) else "SEG %02d".format(segmentNumber)
                    timerHandler.removeCallbacks(timerRunnable)
                    timerHandler.post(timerRunnable)
                    recordButton.background = getDrawable(R.drawable.bg_record_active)
                    recordIcon.text = "■"
                    recordIcon.setTextColor(0xFFFFFFFF.toInt())
                    loopDeadlineMs = if (loopRecordingOn) System.currentTimeMillis() + loopDurationMs else 0L
                    if (loopRecordingOn) scheduleLoopStop(loopDurationMs)
                }
                is VideoRecordEvent.Pause -> {
                    recordingPaused = true
                    statusText.text = "PAUSED"
                    timerHandler.removeCallbacks(timerRunnable)
                }
                is VideoRecordEvent.Resume -> {
                    recordingPaused = false
                    statusText.text = if (loopRecordingOn) "● REC • LOOP" else "● REC"
                    loopText.text = if (loopRecordingOn) "SEG %02d • NEXT %s".format(segmentNumber, loopDurationLabel) else "SEG %02d".format(segmentNumber)
                    timerHandler.removeCallbacks(timerRunnable)
                    timerHandler.post(timerRunnable)
                    if (loopRecordingOn && loopRemainingMs > 0L) {
                        loopDeadlineMs = System.currentTimeMillis() + loopRemainingMs
                        scheduleLoopStop(loopRemainingMs)
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Toast.makeText(this, "Gagal menyimpan video: ${event.error}", Toast.LENGTH_LONG).show()
                        recording = null
                        stoppingForLoop = false
                        stopRequestedByUser = false
                        finishGpsRecording(saveTrack = true)
                        resetRecordUi()
                    } else if (stoppingForLoop && loopRecordingOn && !stopRequestedByUser) {
                        recording = null
                        segmentNumber++
                        loopText.text = "SEG %02d".format(segmentNumber)
                        stoppingForLoop = false
                        startSegment()
                    } else {
                        recording = null
                        stoppingForLoop = false
                        stopRequestedByUser = false
                        finishGpsRecording(saveTrack = true)
                        resetRecordUi()
                    }
                }
            }
        }
    }

    private fun scheduleLoopStop(delayMs: Long) {
        timerHandler.removeCallbacksAndMessages("LOOP_STOP")
        timerHandler.postAtTime({
            if (recording != null && !stoppingForLoop && !stopRequestedByUser && loopRecordingOn && !recordingPaused) {
                stoppingForLoop = true
                stopRequestedByUser = false
                loopRemainingMs = 0L
                recording?.stop()
            }
        }, "LOOP_STOP", SystemClock.uptimeMillis() + delayMs.coerceAtLeast(1L))
    }

    private fun cycleVideoQuality() {
        if (photoMode) return
        selectedQuality = if (selectedQuality == Quality.FHD) Quality.UHD else Quality.FHD
        selectedQualityLabel = if (selectedQuality == Quality.UHD) "4K" else "1080P"
        statusText.text = "$selectedQualityLabel • SIAP"
        Toast.makeText(
            this,
            if (selectedQuality == Quality.UHD) "4K dipilih • jika HP tidak mendukung, otomatis turun ke 1080P" else "1080P dipilih",
            Toast.LENGTH_SHORT
        ).show()
        if (cameraActive) {
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
                startCamera(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            } catch (_: Exception) {
                Toast.makeText(this, "Kualitas belum dapat diterapkan", Toast.LENGTH_SHORT).show()
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
        gpsStatsText.text = "MAX 0 • AVG 0 • 0.0 km"
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
        if (countdownActive) {
            countdownActive = false
            timerHandler.removeCallbacks(countdownRunnable)
            timerText.text = "00:00"
            statusText.text = "READY"
            return
        }
        if (recording != null) {
            stopRequestedByUser = true
            stoppingForLoop = false
            loopRemainingMs = 0L
            recording?.stop()
            return
        }
        segmentNumber = 1
        stoppingForLoop = false
        stopRequestedByUser = false
        recordingPaused = false
        if (gpsEnabled) resetGpsTrackStats()
        if (countdownSeconds > 0) {
            countdownActive = true
            timerText.text = String.format("00:0%d", countdownSeconds)
            statusText.text = "START DALAM $countdownSeconds"
            countdownSeconds--
            timerHandler.removeCallbacks(countdownRunnable)
            timerHandler.postDelayed(countdownRunnable, 1000)
        } else {
            startSegment()
        }
    }

    private fun togglePauseResume(button: TextView) {
        val active = recording
        if (active == null) {
            Toast.makeText(this, "Belum merekam", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (!recordingPaused) {
                if (loopRecordingOn && loopDeadlineMs > 0L) {
                    loopRemainingMs = (loopDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                }
                active.pause()
                button.text = "RESUME"
                button.alpha = 1f
            } else {
                active.resume()
                button.text = "PAUSE"
                button.alpha = .85f
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Pause/Resume tidak didukung kamera ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cardinalDirection(degrees: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((degrees + 22.5f) / 45f).toInt() % 8]
    }

    private fun resetGpsTrackStats() {
        gpsTrackPoints.clear()
        gpsDistanceMeters = 0f
        gpsMaxSpeedKmh = 0f
        gpsSpeedSum = 0f
        gpsSpeedSamples = 0
        lastTrackLocation = null
        gpsTrackRecording = false
        if (::gpsStatsText.isInitialized) gpsStatsText.text = "MAX 0 • AVG 0 • 0.0 km"
    }

    private fun beginGpsRecording() {
        if (!gpsEnabled) return
        gpsTrackRecording = true
        lastTrackLocation = null
        updateGpsStatsHud()
    }

    private fun recordGpsPoint(location: Location) {
        val speed = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else lastSpeedKmh
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val previous = lastTrackLocation
        if (previous != null && (!location.hasAccuracy() || accuracy <= 80f) && (!previous.hasAccuracy() || previous.accuracy <= 80f)) {
            gpsDistanceMeters += previous.distanceTo(location).coerceAtLeast(0f)
        }
        lastTrackLocation = Location(location)
        gpsTrackPoints.add(GpsPoint(System.currentTimeMillis(), location.latitude, location.longitude, speed, accuracy, bearing))
        gpsMaxSpeedKmh = maxOf(gpsMaxSpeedKmh, speed)
        gpsSpeedSum += speed
        gpsSpeedSamples++
        updateGpsStatsHud()
    }

    private fun updateGpsStatsHud() {
        if (!::gpsStatsText.isInitialized) return
        val avg = if (gpsSpeedSamples > 0) gpsSpeedSum / gpsSpeedSamples else 0f
        gpsStatsText.text = String.format("MAX %.0f • AVG %.0f • %.1f km", gpsMaxSpeedKmh, avg, gpsDistanceMeters / 1000f)
    }

    private fun finishGpsRecording(saveTrack: Boolean) {
        if (!gpsTrackRecording) return
        gpsTrackRecording = false
        if (saveTrack && gpsTrackPoints.isNotEmpty()) saveGpsTrackCsv()
        updateGpsStatsHud()
    }

    private fun saveGpsTrackCsv() {
        try {
            val avg = if (gpsSpeedSamples > 0) gpsSpeedSum / gpsSpeedSamples else 0f
            val sb = StringBuilder()
            sb.append("timestamp,latitude,longitude,speed_kmh,accuracy_m,bearing_deg\n")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
            gpsTrackPoints.forEach { point ->
                sb.append(fmt.format(java.util.Date(point.timeMs))).append(',')
                    .append(String.format(java.util.Locale.US, "%.7f", point.latitude)).append(',')
                    .append(String.format(java.util.Locale.US, "%.7f", point.longitude)).append(',')
                    .append(String.format(java.util.Locale.US, "%.2f", point.speedKmh)).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f", point.accuracyM)).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f", point.bearing)).append('\n')
            }
            val name = String.format("JejakCam_GPS_%tY%<tm%<td_%<tH%<tM%<tS.csv", java.util.Date())
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/JejakCam/GPS")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                Toast.makeText(this, String.format("GPS track tersimpan • %.1f km • AVG %.0f km/h", gpsDistanceMeters / 1000f, avg), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Video tersimpan, tetapi GPS track gagal disimpan", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleGps() {
        if (gpsEnabled) {
            stopGpsUpdates()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGpsUpdates()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun startGpsUpdates() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gpsEnabled = false
            gpsText.text = "GPS OFF"
            Toast.makeText(this, "GPS HP sedang mati • aktifkan GPS lalu tekan tombol lagi", Toast.LENGTH_LONG).show()
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, gpsLocationListener, Looper.getMainLooper())
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, gpsLocationListener, Looper.getMainLooper())
            }
            gpsEnabled = true
            gpsText.text = "GPS ON • 0 km/h"
            Toast.makeText(this, "GPS aktif • kecepatan ditampilkan di HUD", Toast.LENGTH_SHORT).show()
            updateGpsStatus()
        } catch (e: SecurityException) {
            gpsEnabled = false
            gpsText.text = "GPS OFF"
        }
    }

    private fun stopGpsUpdates() {
        try { locationManager.removeUpdates(gpsLocationListener) } catch (_: Exception) {}
        gpsEnabled = false
        gpsText.text = "GPS OFF"
        lastSpeedKmh = 0f
        lastGpsAccuracy = 0f
        if (recording == null) resetGpsTrackStats()
    }

    private fun updateGpsStatus() {
        if (!gpsEnabled) { gpsText.text = "GPS OFF"; return }
        val enabled = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        gpsText.text = if (enabled) String.format("GPS ON • %.0f km/h", lastSpeedKmh) else "GPS WAIT"
    }

    private fun updateGpsHud(location: Location) {
        if (!gpsEnabled) return
        val accuracy = if (location.hasAccuracy()) String.format(" • ±%.0fm", location.accuracy) else ""
        gpsText.text = String.format("GPS ON • %.0f km/h%s", lastSpeedKmh, accuracy)
    }

    private fun storageInfo(): Pair<Long, Long> {
        return try {
            val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            Pair(free, total)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    private fun formatStorage(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 10) String.format(java.util.Locale.US, "%.0f GB", gb)
        else String.format(java.util.Locale.US, "%.1f GB", gb)
    }

    private fun estimatedBitrateBytesPerSecond(): Double {
        return if (selectedQuality == Quality.UHD) 20_000_000.0 / 8.0 else 8_000_000.0 / 8.0
    }

    private fun storageDetails(): String {
        val (free, total) = storageInfo()
        if (free <= 0L || total <= 0L) return "Penyimpanan tidak dapat dibaca"
        val hours = (free / estimatedBitrateBytesPerSecond() / 3600.0)
        val remaining = if (hours >= 1) String.format(java.util.Locale.US, "%.1f jam", hours)
        else String.format(java.util.Locale.US, "%.0f menit", hours * 60.0)
        return "Kosong ${formatStorage(free)} dari ${formatStorage(total)} • estimasi ${remaining}"
    }

    private fun updateStorageHud() {
        if (!::storageText.isInitialized) return
        val (free, total) = storageInfo()
        if (free <= 0L || total <= 0L) {
            storageText.text = "STOR --"
            return
        }
        val freePct = (free.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
        val minutes = free / estimatedBitrateBytesPerSecond() / 60.0
        val time = if (minutes >= 60) String.format(java.util.Locale.US, "%.1fh", minutes / 60.0)
        else String.format(java.util.Locale.US, "%.0fm", minutes)
        storageText.text = "FREE ${formatStorage(free)} • $time"
        storageText.alpha = if (freePct <= 5.0) .98f else .82f
        if (freePct <= 2.0) {
            storageText.setTextColor(0xFFFF5252.toInt())
        } else if (freePct <= 5.0) {
            storageText.setTextColor(0xFFFFC107.toInt())
        } else {
            storageText.setTextColor(Color.WHITE)
        }
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
        if (gpsEnabled) stopGpsUpdates()
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
            gyroMotion = (gyroMotion * 0.90f + magnitude * 0.10f)
            return
        }

        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0f) azimuth += 360f
        compassText.text = String.format("%s %03d°", cardinalDirection(azimuth), azimuth.roundToInt().coerceIn(0, 359))
        var roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        var pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        if (roll > 180f) roll -= 360f
        if (roll < -180f) roll += 360f
        if (pitch > 180f) pitch -= 360f
        if (pitch < -180f) pitch += 360f

        // Low-pass filtering makes the HUD steadier when the phone is mounted on a vehicle.
        // This is a live level indicator; actual frame rotation is not performed here.
        filteredRoll = filteredRoll * 0.88f + roll * 0.12f
        filteredPitch = filteredPitch * 0.88f + pitch * 0.12f
        val clamped = filteredRoll.coerceIn(-20f, 20f)
        horizonText.rotation = if (horizonLockOn) -clamped else 0f
        val level = if (kotlin.math.abs(filteredRoll) < 2.5f) "LEVEL" else String.format("%+.0f°", filteredRoll)
        val motion = when {
            gyroMotion < 0.25f -> "SMOOTH"
            gyroMotion < 0.8f -> "MOVE"
            else -> "SHAKE"
        }
        horizonText.text = "— $level • $motion —"
        tiltText.text = String.format("ROLL %+d°  •  PITCH %+d°", filteredRoll.roundToInt(), filteredPitch.roundToInt())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        storageHandler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)
        recording?.stop()
        timerHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        stopGpsUpdates()
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        batteryReceiver = null
        cameraActive = false
        super.onDestroy()
    }
}
