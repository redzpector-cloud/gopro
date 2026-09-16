package com.jejakcam.app

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ScaleGestureDetector
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore.Images
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.CompoundButton
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
import androidx.camera.core.FocusMeteringAction
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
    // V74: throttle high-frequency sensor HUD updates to reduce UI/CPU load.
    private var lastSensorHudUpdateMs = 0L
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
    private var aeAfLockOn = false
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
    // V56: remembers that the user intentionally opened the camera so it can be restored after background/foreground.
    private var cameraRequested = false
    private var backgroundCameraRelease = false
    private var requestedPhotoMode = false
    // V54: invalidates queued CameraX callbacks when the camera is closed/reconfigured.
    private var cameraStartToken = 0L
    // V55: remembers a lens/zoom request while CameraX is rebinding.
    private var pendingZoomPreset: Float? = null
    private var cameraRebindInProgress = false
    // V58: lifecycle-safe recording finalization. Prevents a loop segment from
    // starting again after the Activity has been stopped/destroyed.
    private var activityStopping = false
    // V60: identifies the active recording session so late callbacks from an older
    // CameraX Recording cannot alter the current session or restart a loop segment.
    private var recordingSessionId = 0L
    // V61: blocks a new recording while CameraX is finalizing the previous file.
    private var recordingFinalizing = false
    // V77: if Android interrupts the camera session (for example an incoming call),
    // remember that an active recording should be restarted after the interruption.
    // The previous segment is finalized by CameraX first; we never reuse a stale Recording object.
    private var resumeRecordingAfterInterruption = false
    // V65: prevents rapid repeated PHOTO taps from queuing multiple captures.
    private var photoCaptureInProgress = false

    // V53: separate function state (AUTO/ON/OFF) from HUD visibility (SHOW/HIDE).
    private val prefs by lazy { getSharedPreferences("jejakcam_settings", MODE_PRIVATE) }
    private val hudViews = mutableMapOf<String, View>()
    private var settingsOverlay: View? = null
    private var stabilizerMode = "AUTO"
    private var horizonMode = "ON"
    private var micMode = "ON"
    private var gpsMode = "OFF"
    private var loopMode = "ON"
    private val timerHandler = Handler(Looper.getMainLooper())
    private val storageHandler = Handler(Looper.getMainLooper())
    // V64: thermal/battery safety monitor for long action-camera sessions.
    private val thermalHandler = Handler(Looper.getMainLooper())
    private var thermalStopTriggered = false
    private val thermalRunnable = object : Runnable {
        override fun run() {
            checkThermalAndBatterySafety()
            thermalHandler.postDelayed(this, 10000L)
        }
    }

    private fun checkThermalAndBatterySafety() {
        if (recording == null || recordingFinalizing || activityStopping) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thermal = pm.currentThermalStatus
                if (!thermalStopTriggered && thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                    thermalStopTriggered = true
                    stopRequestedByUser = true
                    stoppingForLoop = false
                    loopRemainingMs = 0L
                    recordingFinalizing = true
                    timerHandler.removeCallbacksAndMessages("LOOP_STOP")
                    Toast.makeText(this, "HP terlalu panas • rekaman dihentikan aman", Toast.LENGTH_LONG).show()
                    recording?.stop()
                    return
                }
            }
            val battery = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (!thermalStopTriggered && level in 0..4) {
                thermalStopTriggered = true
                stopRequestedByUser = true
                stoppingForLoop = false
                loopRemainingMs = 0L
                recordingFinalizing = true
                timerHandler.removeCallbacksAndMessages("LOOP_STOP")
                Toast.makeText(this, "Baterai sangat rendah • rekaman dihentikan aman", Toast.LENGTH_LONG).show()
                recording?.stop()
            }
        } catch (_: Throwable) {
            // Safety monitor must never crash the camera UI.
        }
    }
    // V62: storage safety guard. Stop the active recording before Android/MediaStore
    // reaches critically low free space, while leaving a small reserve for finalization.
    private var lowStorageStopTriggered = false
    private val storageRunnable = object : Runnable {
        override fun run() {
            if (::storageText.isInitialized) updateStorageHud()
            checkLowStorageSafety()
            storageHandler.postDelayed(this, 5000L)
        }
    }

    private fun checkLowStorageSafety() {
        if (recording == null || recordingFinalizing || activityStopping) return
        val (free, total) = storageInfo()
        if (free <= 0L || total <= 0L) return
        val freePct = free.toDouble() / total.toDouble() * 100.0
        // Keep a 256 MB reserve and also protect very small volumes at 1%.
        if (!lowStorageStopTriggered && (free <= 256L * 1024L * 1024L || freePct <= 1.0)) {
            lowStorageStopTriggered = true
            stopRequestedByUser = true
            stoppingForLoop = false
            loopRemainingMs = 0L
            recordingFinalizing = true
            timerHandler.removeCallbacksAndMessages("LOOP_STOP")
            Toast.makeText(this, "Penyimpanan hampir penuh • rekaman dihentikan aman", Toast.LENGTH_LONG).show()
            recording?.stop()
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
                timerHandler.postDelayed(this, 1000L)
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
        resumeRecordingAfterInterruption = savedInstanceState?.getBoolean("resume_recording_after_interruption", false) ?: false
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
        // V57: preserve camera intent across configuration changes (rotation/recreation)
        // without making a fresh app launch open the camera automatically.
        val restoredCamera = savedInstanceState?.getBoolean("camera_requested", false) == true
        val restoredPhoto = savedInstanceState?.getBoolean("requested_photo_mode", false) == true
        if (restoredCamera) {
            cameraRequested = true
            requestedPhotoMode = restoredPhoto
            photoMode = restoredPhoto
            backgroundCameraRelease = true
        }

        loadSettings()
        buildUi()
        updateGpsStatus()
        // V74: storage/thermal monitors run only while the camera screen is active.
        updateStorageHud()
        // V17: kamera TIDAK otomatis aktif saat aplikasi baru dibuka.
        // V57: onResume() may restore it only when state came from a prior
        // camera session (e.g. rotation/recreation/background).
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

        // V52: tap-to-focus + AE/AF lock. A tap meters/focuses at the selected point;
        // when LOCK is enabled, the focus action is held and the current EV is kept.
        previewView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && cameraActive && recording == null) {
                val cam = camera
                if (cam != null && !aeAfLockOn) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    Toast.makeText(this, "FOCUS / EXPOSURE", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

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
            setOnClickListener { showJejakCamGallery() }
        }
        val settingsHome = Button(this).apply {
            text = "PENGATURAN"; textSize = 12f; setTextColor(Color.WHITE); background = getDrawable(R.drawable.bg_control)
            setOnClickListener { showSettings() }
        }
        homeRow.addView(galleryHome, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        homeRow.addView(settingsHome, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        home.addView(homeRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        val version = textView("V79  •  JEJAK TEKNISI", 10f).apply { alpha = .45f }
        home.addView(version, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(24) })
        homeScreen = home
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // ===================== ACTION-CAM HUD / V61 UI POLISH =====================
        val hud = FrameLayout(this).apply { visibility = View.GONE }

        val topLeft = textView("ACTION", 13f, true).apply { background = getDrawable(R.drawable.bg_chip); setPadding(dp(12),0,dp(12),0) }
        hud.addView(topLeft, FrameLayout.LayoutParams(dp(94), dp(38), Gravity.TOP or Gravity.START).apply { leftMargin=dp(12); topMargin=dp(10) })

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
        hud.addView(resolution, FrameLayout.LayoutParams(dp(108), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(10) })
        hudViews["resolution"] = resolution

        batteryText = textView("●  --%", 12f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(9),0,dp(9),0) }
        hud.addView(batteryText, FrameLayout.LayoutParams(dp(76), dp(38), Gravity.TOP or Gravity.END).apply { rightMargin=dp(12); topMargin=dp(10) })
        hudViews["battery"] = batteryText

        storageText = textView("STOR --", 9f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                Toast.makeText(this@MainActivity, storageDetails(), Toast.LENGTH_SHORT).show()
            }
        }
        hud.addView(storageText, FrameLayout.LayoutParams(dp(112), dp(30), Gravity.TOP or Gravity.END).apply { rightMargin=dp(12); topMargin=dp(102) })
        hudViews["storage"] = storageText

        gpsText = textView("GPS OFF", 10f, true).apply {
            background = getDrawable(R.drawable.bg_toggle)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { toggleGps() }
        }
        hud.addView(gpsText, FrameLayout.LayoutParams(dp(104), dp(34), Gravity.TOP or Gravity.START).apply { leftMargin=dp(12); topMargin=dp(102) })
        hudViews["gps"] = gpsText

        compassText = textView("N 000°", 10f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(8), 0, dp(8), 0)
        }
        hud.addView(compassText, FrameLayout.LayoutParams(dp(104), dp(34), Gravity.TOP or Gravity.END).apply { rightMargin=dp(12); topMargin=dp(102) })
        hudViews["compass"] = compassText

        gpsStatsText = textView("MAX 0 • AVG 0 • 0.0 km", 9f, true).apply {
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(8), 0, dp(8), 0)
        }
        hud.addView(gpsStatsText, FrameLayout.LayoutParams(dp(230), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(132) })
        hudViews["gpsStats"] = gpsStatsText

        val quickSettings = textView("⚙", 20f, true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener { showSettings() }
        }
        hud.addView(quickSettings, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin=dp(12); topMargin=dp(58) })
        hudViews["quickSettings"] = quickSettings

        statusText = textView("READY", 12f, true).apply { background=getDrawable(R.drawable.bg_chip); setPadding(dp(10),0,dp(10),0) }
        hud.addView(statusText, FrameLayout.LayoutParams(dp(96), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(58) })
        hudViews["status"] = statusText
        timerText = textView("00:00", 15f, true)
        hud.addView(timerText, FrameLayout.LayoutParams(dp(100), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(92) })
        hudViews["timer"] = timerText
        loopText = textView("SEG 01", 9f, true).apply { alpha = .72f }
        hud.addView(loopText, FrameLayout.LayoutParams(dp(170), dp(26), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(120) })
        hudViews["loopText"] = loopText

        horizonText = textView("— LEVEL • SMOOTH —", 11f, true).apply { background=getDrawable(R.drawable.bg_chip); alpha=.88f }
        hud.addView(horizonText, FrameLayout.LayoutParams(dp(142), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(148) })
        hudViews["horizon"] = horizonText

        tiltText = textView("ROLL 0°  •  PITCH 0°", 10f, true).apply { background=getDrawable(R.drawable.bg_chip); alpha=.78f }
        hud.addView(tiltText, FrameLayout.LayoutParams(dp(150), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(184) })
        hudViews["tilt"] = tiltText

        val horizonToggle = textView("HORIZON ON", 11f, true).apply {
            background=getDrawable(R.drawable.bg_toggle)
            setOnClickListener { horizonLockOn=!horizonLockOn; horizonMode=if(horizonLockOn) "ON" else "OFF"; saveMode("horizonMode", horizonMode); text=if(horizonLockOn) "HORIZON ON" else "HORIZON OFF" }
        }
        hud.addView(horizonToggle, FrameLayout.LayoutParams(dp(112), dp(36), Gravity.TOP or Gravity.START).apply { leftMargin=dp(12); topMargin=dp(58) })
        hudViews["horizonToggle"] = horizonToggle

        stabilizationText = textView("STAB  AUTO", 11f, true).apply {
            background=getDrawable(R.drawable.bg_toggle)
            setOnClickListener { Toast.makeText(this@MainActivity,"EIS mengikuti kemampuan kamera HP",Toast.LENGTH_SHORT).show() }
        }
        hud.addView(stabilizationText, FrameLayout.LayoutParams(dp(112), dp(36), Gravity.TOP or Gravity.END).apply { rightMargin=dp(12); topMargin=dp(58) })
        hudViews["stabilization"] = stabilizationText

        val zoomPanel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; background=getDrawable(R.drawable.bg_control); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        val plus=Button(this).apply { text="+"; textSize=20f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_zoom); setOnClickListener{setZoom(.5f)} }
        val minus=Button(this).apply { text="−"; textSize=20f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_zoom); setOnClickListener{setZoom(-.5f)} }
        zoomText=textView("1.0×",11f,true)
        zoomPanel.addView(plus,LinearLayout.LayoutParams(dp(46),dp(46)))
        zoomPanel.addView(zoomText,LinearLayout.LayoutParams(dp(46),dp(28)))
        zoomPanel.addView(minus,LinearLayout.LayoutParams(dp(46),dp(46)))
        hud.addView(zoomPanel,FrameLayout.LayoutParams(dp(58),dp(130),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=dp(10)})
        hudViews["zoom"] = zoomPanel

        val exposurePanel=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;background=getDrawable(R.drawable.bg_control)}
        val em=Button(this).apply{text="−";textSize=18f;setTextColor(Color.WHITE);background=getDrawable(R.drawable.bg_zoom);setOnClickListener{changeExposure(-1)}}
        exposureText=textView("EV 0",11f,true)
        val ep=Button(this).apply{text="+";textSize=18f;setTextColor(Color.WHITE);background=getDrawable(R.drawable.bg_zoom);setOnClickListener{changeExposure(1)}}
        exposurePanel.addView(em,LinearLayout.LayoutParams(dp(42),dp(42))); exposurePanel.addView(exposureText,LinearLayout.LayoutParams(dp(48),dp(42))); exposurePanel.addView(ep,LinearLayout.LayoutParams(dp(42),dp(42)))
        hud.addView(exposurePanel,FrameLayout.LayoutParams(dp(136),dp(46),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(10)})
        hudViews["exposure"] = exposurePanel

        val aeAfLock = textView("AE/AF",10f,true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener {
                val cam = camera
                if (cam == null) {
                    Toast.makeText(this@MainActivity, "Kamera belum aktif", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                aeAfLockOn = !aeAfLockOn
                if (aeAfLockOn) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .disableAutoCancel()
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    cam.cameraControl.setExposureCompensationIndex(exposureIndex)
                    text = "AE/AF LOCK"
                    alpha = 1f
                    Toast.makeText(this@MainActivity, "AE/AF dikunci di tengah frame", Toast.LENGTH_SHORT).show()
                } else {
                    text = "AE/AF"
                    alpha = .72f
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    Toast.makeText(this@MainActivity, "AE/AF kembali AUTO", Toast.LENGTH_SHORT).show()
                }
            }
        }
        hud.addView(aeAfLock, FrameLayout.LayoutParams(dp(92),dp(40),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(146)})
        hudViews["aeaf"] = aeAfLock

        val bottomShade=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(18),dp(10),dp(18),dp(10));background=getDrawable(R.drawable.bg_bottom)}
        val modeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val videoMode=textView("VIDEO",12f,true).apply{setPadding(dp(18),0,dp(18),0)}
        val photoModeView=textView("FOTO",12f,true).apply{alpha=.55f;setPadding(dp(18),0,dp(18),0);setOnClickListener{activateCameraMode(true)}}
        videoMode.setOnClickListener { activateCameraMode(false) }
        modeRow.addView(videoMode); modeRow.addView(photoModeView); bottomShade.addView(modeRow,LinearLayout.LayoutParams(-1,dp(30)))

        val controls=FrameLayout(this)
        val gallery=textView("▣",25f).apply{background=getDrawable(R.drawable.bg_control);setOnClickListener{showJejakCamGallery()}}
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
        hud.addView(close,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply{rightMargin=dp(12);topMargin=dp(10)})
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
        hudViews["lensRow"] = lensRow

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
                    loopMode = if (loopRecordingOn) "ON" else "OFF"
                    saveMode("loopMode", loopMode)
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
                    micMode=if(audioEnabled) "ON" else "OFF"
                    saveMode("micMode", micMode)
                    text=if(audioEnabled)"MIC ON" else "MIC OFF"
                    Toast.makeText(this@MainActivity, if(audioEnabled) "Mic aktif • audio akan direkam" else "Mic mati • video tanpa audio", Toast.LENGTH_SHORT).show()
                }
            }
        }
        quickRow.addView(quick,LinearLayout.LayoutParams(dp(88),dp(38)).apply{rightMargin=dp(5)});quickRow.addView(loop,LinearLayout.LayoutParams(dp(76),dp(38)).apply{leftMargin=dp(5);rightMargin=dp(5)});quickRow.addView(mic,LinearLayout.LayoutParams(dp(76),dp(38)).apply{leftMargin=dp(5)})
        bottomShade.addView(quickRow,LinearLayout.LayoutParams(-1,dp(40)))
        hudViews["quickRow"] = quickRow

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
        hudViews["actionRow"] = actionRow
        hud.addView(bottomShade,FrameLayout.LayoutParams(-1,dp(244),Gravity.BOTTOM))
        hudViews["bottomControls"] = bottomShade

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

        applyHudPreferences()
        // Keep the camera screen hidden until the user explicitly opens the camera.
        cameraScreen = hud
    }

    private lateinit var cameraScreen: View
    private lateinit var homeScreen: View

    // V79: JejakCam-only gallery. It intentionally reads only media created in
    // Movies/JejakCam and Pictures/JejakCam, not the user's general Gallery.
    private data class JejakMedia(val uri: Uri, val isVideo: Boolean, val name: String, val size: Long)

    private fun showJejakCamGallery() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0B0B.toInt())
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = textView("GALERI JEJAKCAM", 19f, true)
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val close = Button(this).apply {
            text = "TUTUP"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener { (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(82), dp(44)))
        page.addView(header)

        val info = textView("Hanya foto/video dari JejakCam", 11f).apply { alpha = .65f }
        page.addView(info, LinearLayout.LayoutParams(-1, dp(28)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(18))
        }
        scroll.addView(list, ScrollView.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(page, FrameLayout.LayoutParams(-1, -1))

        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        loadJejakCamGallery(list)
    }

    private fun loadJejakCamGallery(list: LinearLayout) {
        list.removeAllViews()
        val items = mutableListOf<JejakMedia>()
        items += queryJejakMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies/JejakCam/")
        items += queryJejakMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "Pictures/JejakCam/")
        items.sortByDescending { it.name }

        if (items.isEmpty()) {
            val empty = textView("Belum ada foto atau video JejakCam", 14f).apply { gravity = Gravity.CENTER; alpha = .7f }
            list.addView(empty, LinearLayout.LayoutParams(-1, dp(100)))
            return
        }
        items.forEach { media -> addGalleryItem(list, media) }
    }

    private fun queryJejakMedia(uri: Uri, isVideo: Boolean, relativePath: String): List<JejakMedia> {
        val result = mutableListOf<JejakMedia>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        try {
            contentResolver.query(uri, projection, selection, arrayOf(relativePath),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "JejakCam"
                    val size = c.getLong(sizeCol)
                    val mime = c.getString(mimeCol) ?: ""
                    if (size <= 0L) continue
                    if (isVideo && !mime.startsWith("video/")) continue
                    if (!isVideo && !mime.startsWith("image/")) continue
                    result += JejakMedia(ContentUris.withAppendedId(uri, id), isVideo, name, size)
                }
            }
        } catch (_: Throwable) { }
        return result
    }

    private fun addGalleryItem(list: LinearLayout, media: JejakMedia) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = getDrawable(R.drawable.bg_control)
            isClickable = true
            setOnClickListener { openJejakMedia(media.uri, media.isVideo) }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF222222.toInt())
            try {
                val bmp = if (media.isVideo) {
                    ThumbnailUtils.createVideoThumbnail(this@MainActivity, media.uri, android.util.Size(dp(112), dp(72)))
                } else {
                    contentResolver.openInputStream(media.uri)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) setImageBitmap(bmp)
            } catch (_: Throwable) { }
        }
        card.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(72)).apply { rightMargin = dp(10) })
        val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        meta.addView(textView(if (media.isVideo) "VIDEO" else "FOTO", 10f, true))
        meta.addView(textView(media.name, 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        meta.addView(textView(formatMediaSize(media.size), 10f).apply { alpha = .6f })
        card.addView(meta, LinearLayout.LayoutParams(0, dp(72), 1f))
        val delete = Button(this).apply {
            text = "HAPUS"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener { confirmDeleteJejakMedia(media, list) }
        }
        card.addView(delete, LinearLayout.LayoutParams(dp(68), dp(46)))
        list.addView(card, LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(8) })
    }

    private fun confirmDeleteJejakMedia(media: JejakMedia, list: LinearLayout) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Hapus file?")
            .setMessage(media.name)
            .setNegativeButton("BATAL", null)
            .setPositiveButton("HAPUS") { _, _ ->
                try {
                    val deleted = contentResolver.delete(media.uri, null, null)
                    Toast.makeText(this, if (deleted > 0) "File dihapus" else "File tidak ditemukan", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    Toast.makeText(this, "File tidak bisa dihapus", Toast.LENGTH_SHORT).show()
                }
                loadJejakCamGallery(list)
            }.show()
    }

    private fun openJejakMedia(uri: Uri, isVideo: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (isVideo) "video/mp4" else "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka file ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatMediaSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun loadSettings() {
        stabilizerMode = prefs.getString("stabilizerMode", "AUTO") ?: "AUTO"
        horizonMode = prefs.getString("horizonMode", "ON") ?: "ON"
        micMode = prefs.getString("micMode", "ON") ?: "ON"
        gpsMode = prefs.getString("gpsMode", "OFF") ?: "OFF"
        loopMode = prefs.getString("loopMode", "ON") ?: "ON"
        stabilizationOn = stabilizerMode != "OFF"
        horizonLockOn = horizonMode != "OFF"
        audioEnabled = micMode != "OFF"
        audioEnhancementOn = audioEnabled
        loopRecordingOn = loopMode != "OFF"
    }

    private fun saveMode(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun hudVisible(key: String, defaultValue: Boolean = true): Boolean =
        prefs.getBoolean("show_$key", defaultValue)

    private fun setHudVisible(key: String, visible: Boolean) {
        prefs.edit().putBoolean("show_$key", visible).apply()
        hudViews[key]?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyHudPreferences() {
        if (hudViews.isEmpty()) return
        val defaults = mapOf(
            "resolution" to true, "battery" to true, "storage" to false,
            "gps" to false, "compass" to false, "gpsStats" to false,
            "quickSettings" to true, "status" to true, "timer" to true,
            "loopText" to false, "horizon" to false, "tilt" to false,
            "horizonToggle" to false, "stabilization" to false,
            "zoom" to true, "exposure" to false, "aeaf" to true,
            "lensRow" to true, "quickRow" to false, "actionRow" to false
        )
        defaults.forEach { (key, def) -> hudViews[key]?.visibility = if (hudVisible(key, def)) View.VISIBLE else View.GONE }
    }

    private fun showSettings() {
        if (settingsOverlay != null) return
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xFF05080C.toInt()) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = textView("PENGATURAN", 22f, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, dp(54), 1f))
        val close = textView("✕", 22f, true).apply {
            background = getDrawable(R.drawable.bg_control)
            setOnClickListener { hideSettings() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(52), dp(52)))
        content.addView(header)
        val hint = textView("Atur fungsi dan tampilan secara terpisah. HIDE hanya menyembunyikan indikator; fitur tetap bekerja.", 11f).apply { alpha = .7f; gravity = Gravity.START; setPadding(0,0,0,dp(14)) }
        content.addView(hint, LinearLayout.LayoutParams(-1, dp(58)))

        addSettingsSection(content, "FITUR KAMERA")
        addModeSetting(content, "Stabilizer", "stabilizerMode", stabilizerMode, listOf("AUTO", "ON", "OFF")) { v -> stabilizerMode=v; stabilizationOn=v!="OFF"; saveMode("stabilizerMode",v); updateStabilizationHud() }
        addModeSetting(content, "Horizon / Level", "horizonMode", horizonMode, listOf("AUTO", "ON", "OFF")) { v -> horizonMode=v; horizonLockOn=v!="OFF"; saveMode("horizonMode",v); updateHorizonHud() }
        addModeSetting(content, "Mic / Audio", "micMode", micMode, listOf("AUTO", "ON", "OFF")) { v -> micMode=v; audioEnabled=v!="OFF"; audioEnhancementOn=audioEnabled; saveMode("micMode",v); updateAudioUi() }
        addModeSetting(content, "GPS / Lokasi", "gpsMode", gpsMode, listOf("AUTO", "ON", "OFF")) { v -> gpsMode=v; saveMode("gpsMode",v); if(v=="OFF") stopGpsUpdates() else if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startGpsUpdates() else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        addModeSetting(content, "Loop Recording", "loopMode", loopMode, listOf("AUTO", "ON", "OFF")) { v -> loopMode=v; loopRecordingOn=v!="OFF"; saveMode("loopMode",v) }

        addSettingsSection(content, "INDIKATOR DI LAYAR KAMERA")
        val labels = linkedMapOf(
            "resolution" to "Resolusi & FPS",
            "battery" to "Baterai",
            "storage" to "Penyimpanan",
            "gps" to "GPS / Kecepatan",
            "compass" to "Kompas",
            "gpsStats" to "Statistik GPS",
            "status" to "Status Rekaman",
            "timer" to "Timer",
            "loopText" to "Loop / Segmen",
            "horizon" to "Garis Horizon",
            "tilt" to "Roll / Pitch",
            "stabilization" to "Status Stabilizer",
            "exposure" to "EV / Exposure",
            "aeaf" to "AE/AF",
            "quickSettings" to "Tombol Pengaturan",
            "zoom" to "Kontrol Zoom",
            "lensRow" to "Preset Lensa",
            "quickRow" to "Quick Rec / Loop / Mic",
            "actionRow" to "Timer / Pause"
        )
        labels.forEach { (key,label) -> addVisibilitySetting(content, key, label, hudViews[key]?.visibility == View.VISIBLE) }

        addSettingsSection(content, "PRESET")
        val presetRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        listOf("SIMPLE", "TEKNISI", "LENGKAP").forEach { preset ->
            val b = Button(this).apply {
                text = preset; textSize=10f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_control)
                setOnClickListener { applyPreset(preset); hideSettings(); Toast.makeText(this@MainActivity, "Preset $preset diterapkan", Toast.LENGTH_SHORT).show() }
            }
            presetRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin=dp(3); rightMargin=dp(3) })
        }
        content.addView(presetRow, LinearLayout.LayoutParams(-1, dp(50)))
        val reset = Button(this).apply {
            text="RESET KE DEFAULT"; textSize=11f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_control)
            setOnClickListener { prefs.edit().clear().apply(); loadSettings(); applyHudPreferences(); hideSettings(); Toast.makeText(this@MainActivity,"Pengaturan dikembalikan ke default",Toast.LENGTH_SHORT).show() }
        }
        content.addView(reset, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin=dp(12) })
        scroll.addView(content)
        overlay.addView(scroll, FrameLayout.LayoutParams(-1,-1))
        root.addView(overlay, FrameLayout.LayoutParams(-1,-1))
        settingsOverlay = overlay
    }

    private fun addSettingsSection(parent: LinearLayout, title: String) {
        val t = textView(title, 12f, true).apply { setTextColor(0xFFFFD21F.toInt()); gravity=Gravity.START; setPadding(dp(2),dp(12),0,dp(7)) }
        parent.addView(t, LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun addModeSetting(parent: LinearLayout, label: String, key: String, current: String, modes: List<String>, onChanged: (String)->Unit) {
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=getDrawable(R.drawable.bg_control); setPadding(dp(14),0,dp(8),0) }
        val tv = textView(label, 12f, true).apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL }
        row.addView(tv, LinearLayout.LayoutParams(0,dp(50),1f))
        val b = Button(this).apply {
            text=current; textSize=10f; setTextColor(Color.WHITE); background=getDrawable(R.drawable.bg_toggle)
            setOnClickListener { val next = modes[(modes.indexOf(text.toString()).coerceAtLeast(0)+1)%modes.size]; text=next; onChanged(next) }
        }
        row.addView(b, LinearLayout.LayoutParams(dp(92),dp(42)))
        parent.addView(row, LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(6) })
    }

    private fun addVisibilitySetting(parent: LinearLayout, key: String, label: String, checked: Boolean) {
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=getDrawable(R.drawable.bg_control); setPadding(dp(14),0,dp(8),0) }
        val tv = textView(label, 12f, true).apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL }
        row.addView(tv, LinearLayout.LayoutParams(0,dp(50),1f))
        val sw = Switch(this).apply { isChecked=checked; text=if(checked) "TAMPIL" else "HIDE"; textSize=9f; setTextColor(Color.WHITE) }
        sw.setOnCheckedChangeListener { _: CompoundButton, checkedNow: Boolean -> sw.text=if(checkedNow) "TAMPIL" else "HIDE"; setHudVisible(key, checkedNow) }
        row.addView(sw, LinearLayout.LayoutParams(dp(105),dp(50)))
        parent.addView(row, LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(6) })
    }

    private fun applyPreset(name: String) {
        val allKeys = listOf("resolution","battery","storage","gps","compass","gpsStats","quickSettings","status","timer","loopText","horizon","tilt","horizonToggle","stabilization","zoom","exposure","aeaf","lensRow","quickRow","actionRow")
        allKeys.forEach { setHudVisible(it, false) }
        val visible = when(name) {
            "SIMPLE" -> listOf("resolution","battery","quickSettings","status","timer","zoom","aeaf","lensRow")
            "LENGKAP" -> allKeys
            else -> listOf("resolution","battery","quickSettings","status","timer","zoom","aeaf","lensRow")
        }
        visible.forEach { setHudVisible(it, true) }
    }

    private fun hideSettings() {
        settingsOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        settingsOverlay = null
    }

    private fun updateStabilizationHud() {
        if (::stabilizationText.isInitialized) stabilizationText.text = "STAB  $stabilizerMode"
    }

    private fun updateHorizonHud() {
        if (::horizonText.isInitialized) horizonText.visibility = if (hudVisible("horizon", false)) View.VISIBLE else View.GONE
    }

    private fun updateAudioUi() { }

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
        cameraRequested = true
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
        val startToken = ++cameraStartToken
        cameraRebindInProgress = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (startToken != cameraStartToken || isFinishing || isDestroyed) return@addListener

            val provider = try {
                future.get()
            } catch (e: Exception) {
                cameraRebindInProgress = false
                cameraActive = false
                Toast.makeText(this, "Kamera tidak dapat disiapkan: ${e.message}", Toast.LENGTH_LONG).show()
                return@addListener
            }

            // V62: gunakan konfigurasi CameraX yang paling kompatibel.
            // Jangan memaksa CaptureRequest Camera2 karena beberapa HAL/vendor
            // menolak kombinasi request tertentu dan dapat membuat aplikasi crash.
            val preview = try {
                Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            } catch (e: Exception) {
                cameraRebindInProgress = false
                Toast.makeText(this, "Preview kamera tidak kompatibel: ${e.message}", Toast.LENGTH_LONG).show()
                return@addListener
            }

            val videoCapture = try {
                val qualitySelector = QualitySelector.from(
                    selectedQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                )
                recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                VideoCapture.withOutput(recorder!!)
            } catch (_: Exception) {
                try {
                    recorder = Recorder.Builder().build()
                    VideoCapture.withOutput(recorder!!)
                } catch (e: Exception) {
                    cameraRebindInProgress = false
                    recorder = null
                    Toast.makeText(this, "Video tidak didukung HP ini: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addListener
                }
            }

            val image = try {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
            } catch (e: Exception) {
                cameraRebindInProgress = false
                recorder = null
                Toast.makeText(this, "Foto tidak didukung HP ini: ${e.message}", Toast.LENGTH_LONG).show()
                return@addListener
            }
            imageCapture = image

            try {
                if (startToken != cameraStartToken || isFinishing || isDestroyed) {
                    cameraRebindInProgress = false
                    return@addListener
                }

                provider.unbindAll()
                // V63: pilih lensa hanya bila perangkat benar-benar mengekspos kamera
                // wide yang kompatibel. Jika tidak, selalu fallback ke kamera belakang utama.
                val requestedSelector = if (wideMode) currentCameraSelector() else CameraSelector.DEFAULT_BACK_CAMERA
                val selector = try {
                    if (wideMode && !provider.hasCamera(requestedSelector)) {
                        wideMode = false
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        requestedSelector
                    }
                } catch (_: Exception) {
                    wideMode = false
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    videoCapture,
                    image
                )

                cameraActive = true
                cameraRebindInProgress = false
                wideMode = false
                previewView.visibility = View.VISIBLE
                cameraScreen.visibility = View.VISIBLE
                homeScreen.visibility = View.GONE
                cameraButton.text = "KAMERA AKTIF"
                cameraButton.alpha = 0.7f
                recordButton.isEnabled = true
                recordButton.alpha = 1f
                setZoom(0f)
                pendingZoomPreset?.let { preset ->
                    pendingZoomPreset = null
                    setZoomToPreset(preset)
                }
                exposureIndex = 0
                aeAfLockOn = false
                camera?.cameraControl?.setExposureCompensationIndex(0)
                exposureText.text = "EV 0"
                statusText.text = "ACTION"
                stabilizationText.text = if (stabilizationOn) "STAB  AUTO" else "STAB  OFF"
                startPerformanceMonitors()
                // V77: CameraX may have finalized the old recording while the app was
                // interrupted. Once the camera is rebound, begin a fresh segment.
                if (resumeRecordingAfterInterruption && !photoMode && recording == null &&
                    !isFinishing && !isDestroyed) {
                    resumeRecordingAfterInterruption = false
                    timerHandler.postDelayed({
                        if (cameraActive && !photoMode && recording == null && !isFinishing && !isDestroyed) {
                            startSegment()
                        }
                    }, 350L)
                }
            } catch (e: Exception) {
                provider.unbindAll()
                camera = null
                cameraActive = false
                recorder = null
                imageCapture = null
                cameraRebindInProgress = false
                stopPerformanceMonitors()
                Toast.makeText(
                    this,
                    "Kamera tidak kompatibel dengan mode ini. Silakan coba lagi: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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
        val previousWide = wideMode
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                providerFuture.get().unbindAll()
                // Rebuild through startCamera; startCamera itself verifies the selector
                // and falls back to the normal rear camera when needed.
                startCamera(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                Toast.makeText(
                    this,
                    if (wideMode) "WIDE / ULTRA-WIDE aktif" else "KAMERA UTAMA aktif",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
                wideMode = previousWide
                Toast.makeText(this, "Mode wide tidak tersedia di HP ini", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setLensPreset(ratio: Float) {
        if ((!cameraActive || camera == null) && !cameraRebindInProgress) {
            Toast.makeText(this, "Pilih VIDEO atau FOTO terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman sebelum mengganti lensa", Toast.LENGTH_SHORT).show()
            return
        }
        if (ratio == 0.5f) {
            if (!wideMode) {
                pendingZoomPreset = 1f
                toggleWideCamera()
            } else {
                setZoomToPreset(1f)
            }
        } else {
            if (wideMode) {
                wideMode = false
                val providerFuture = ProcessCameraProvider.getInstance(this)
                providerFuture.addListener({
                    try {
                        providerFuture.get().unbindAll()
                        pendingZoomPreset = ratio
                        startCamera(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
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

    // V63: recording preflight guard. Do not start a new MediaStore recording
    // when storage is already critically low; this avoids creating an output
    // item that immediately fails during finalization.
    private fun hasSafeRecordingStorage(): Boolean {
        val (free, total) = storageInfo()
        if (free <= 0L || total <= 0L) return true // keep the existing fallback behavior
        val freePct = free.toDouble() / total.toDouble() * 100.0
        return free > 256L * 1024L * 1024L && freePct > 1.0
    }

    // V75: verify the finalized MediaStore item before reporting success.
    // Long recordings can fail late even when CameraX reaches Finalize without
    // an exception; never leave a zero-byte/broken item in the Gallery.
    private fun verifyFinalizedVideo(uri: Uri): Boolean {
        if (uri == Uri.EMPTY) return false
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                sizeIndex >= 0 && cursor.getLong(sizeIndex) > 0L
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun startSegment() {
        if (activityStopping || isFinishing || isDestroyed || !cameraActive) return
        val r = recorder ?: return
        if (recording != null || recordingFinalizing || stoppingForLoop || stopRequestedByUser) return
        if (!hasSafeRecordingStorage()) {
            lowStorageStopTriggered = true
            Toast.makeText(this, "Penyimpanan terlalu penuh • kosongkan ruang sebelum merekam", Toast.LENGTH_LONG).show()
            statusText.text = "STORAGE LOW"
            return
        }
        val sessionId = ++recordingSessionId
        if (!gpsTrackRecording && gpsEnabled && segmentNumber == 1) beginGpsRecording()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioEnabled && !micGranted) {
            Toast.makeText(this, "Izin mikrofon belum aktif • aktifkan Mikrofon untuk merekam audio", Toast.LENGTH_LONG).show()
            statusText.text = "MIC OFF / IZIN"
            return
        }

        val pending = try {
            r.prepareRecording(this, makeOutputOptions())
        } catch (e: Throwable) {
            Toast.makeText(this, "Tidak dapat menyiapkan rekaman: ${e.message ?: "error"}", Toast.LENGTH_LONG).show()
            return
        }

        val prepared = if (audioEnabled && micGranted) {
            try {
                // V76: audio is explicitly attached to every video recording.
                // Do not silently fall back to a silent video when the mic is enabled.
                pending.withAudioEnabled()
            } catch (e: Throwable) {
                Toast.makeText(this, "Audio mikrofon gagal diaktifkan: ${e.message ?: "error"}", Toast.LENGTH_LONG).show()
                statusText.text = "AUDIO ERROR"
                return
            }
        } else {
            pending
        }
        recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
            // V60: ignore callbacks belonging to a previous recording session.
            if (sessionId != recordingSessionId) return@start
            // V59: ignore late CameraX callbacks after Activity teardown.
            if (activityStopping || isFinishing || isDestroyed) {
                if (event is VideoRecordEvent.Finalize) {
                    if (sessionId == recordingSessionId) { recording = null; recordingFinalizing = false }
                    stoppingForLoop = false
                    stopRequestedByUser = false
                }
                return@start
            }
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAt = System.currentTimeMillis()
                    recordingPaused = false
                    thermalStopTriggered = false
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
                    timerHandler.removeCallbacksAndMessages("LOOP_STOP")
                    if (event.hasError()) {
                        // V69: if MediaStore created an output item but finalization failed,
                        // remove that failed item so the Gallery does not retain a broken video.
                        try {
                            val failedUri = event.outputResults.outputUri
                            if (failedUri != Uri.EMPTY) {
                                contentResolver.delete(failedUri, null, null)
                            }
                        } catch (_: Exception) { }
                        Toast.makeText(this, "Gagal menyimpan video: ${event.error}", Toast.LENGTH_LONG).show()
                        recording = null
                        recordingFinalizing = false
                        lowStorageStopTriggered = false
                        thermalStopTriggered = false
                        stoppingForLoop = false
                        stopRequestedByUser = false
                        finishGpsRecording(saveTrack = true)
                        resetRecordUi()
                    } else if (!verifyFinalizedVideo(event.outputResults.outputUri)) {
                        try {
                            val badUri = event.outputResults.outputUri
                            if (badUri != Uri.EMPTY) contentResolver.delete(badUri, null, null)
                        } catch (_: Throwable) { }
                        Toast.makeText(this, "Video selesai tetapi file tidak valid", Toast.LENGTH_LONG).show()
                        recording = null
                        recordingFinalizing = false
                        lowStorageStopTriggered = false
                        thermalStopTriggered = false
                        stoppingForLoop = false
                        stopRequestedByUser = false
                        finishGpsRecording(saveTrack = true)
                        resetRecordUi()
                    } else if (stoppingForLoop && loopRecordingOn && !stopRequestedByUser && !activityStopping && !isFinishing && !isDestroyed && cameraActive) {
                        recording = null
                        recordingFinalizing = false
                        segmentNumber++
                        loopText.text = "SEG %02d".format(segmentNumber)
                        stoppingForLoop = false
                        startSegment()
                    } else {
                        recording = null
                        recordingFinalizing = false
                        lowStorageStopTriggered = false
                        thermalStopTriggered = false
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
        val sessionId = recordingSessionId
        timerHandler.postAtTime({
            if (sessionId == recordingSessionId && recording != null && !stoppingForLoop && !stopRequestedByUser && loopRecordingOn && !recordingPaused) {
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

    // V71: photo preflight guard. Keep a small safety margin so the JPEG can
    // finish writing and MediaStore can publish it cleanly.
    private fun hasSafePhotoStorage(): Boolean {
        val (free, total) = storageInfo()
        if (free <= 0L || total <= 0L) return true
        val freePct = free.toDouble() / total.toDouble() * 100.0
        return free > 32L * 1024L * 1024L && freePct > 0.5
    }

    private fun capturePhoto() {
        if (activityStopping || isFinishing || isDestroyed || !cameraActive) return
        if (photoCaptureInProgress) {
            Toast.makeText(this, "Foto sedang diproses", Toast.LENGTH_SHORT).show()
            return
        }
        val capture = imageCapture ?: return
        if (!hasSafePhotoStorage()) {
            statusText.text = "STORAGE LOW"
            Toast.makeText(this, "Penyimpanan terlalu penuh • kosongkan ruang sebelum mengambil foto", Toast.LENGTH_LONG).show()
            return
        }
        photoCaptureInProgress = true
        val name = String.format("JejakCam_%tY%<tm%<td_%<tH%<tM%<tS_%<L.jpg", java.util.Date())
        // V66: create the MediaStore row first so a failed/aborted capture can be
        // removed cleanly instead of leaving an empty or half-written photo.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JejakCam")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        // CameraX 1.4.x requires the MediaStore collection AND ContentValues.
        // Let CameraX create the MediaStore row so the returned savedUri belongs
        // to the actual capture operation.
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                photoCaptureInProgress = false
                val savedUri = outputFileResults.savedUri
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedUri != null) {
                    try {
                        val publish = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        val updated = contentResolver.update(savedUri, publish, null, null)
                        if (updated <= 0) {
                            // V72: do not leave a pending MediaStore item if it cannot be published.
                            try { contentResolver.delete(savedUri, null, null) } catch (_: Exception) { }
                            statusText.text = "PHOTO PUBLISH FAILED"
                            Toast.makeText(this@MainActivity, "Foto gagal dipublikasikan ke Galeri", Toast.LENGTH_LONG).show()
                            return
                        }
                    } catch (_: Exception) {
                        // V72: best-effort cleanup of the exact capture URI.
                        try { contentResolver.delete(savedUri, null, null) } catch (_: Exception) { }
                        statusText.text = "PHOTO PUBLISH FAILED"
                        Toast.makeText(this@MainActivity, "Foto gagal diselesaikan", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                // V73: gallery verification. Do not report success until the
                // published MediaStore item still exists and has non-zero size.
                if (savedUri == null || !verifyPublishedPhoto(savedUri)) {
                    if (savedUri != null) {
                        try { contentResolver.delete(savedUri, null, null) } catch (_: Exception) { }
                    }
                    statusText.text = "PHOTO VERIFY FAILED"
                    Toast.makeText(this@MainActivity, "Foto belum terverifikasi di Galeri", Toast.LENGTH_LONG).show()
                    return
                }
                statusText.text = "PHOTO SAVED"
                Toast.makeText(this@MainActivity, "Foto tersimpan di Galeri > Pictures > JejakCam", Toast.LENGTH_SHORT).show()
            }
            override fun onError(exception: ImageCaptureException) {
                photoCaptureInProgress = false
                // V68: CameraX may have created the MediaStore row before the
                // capture failed. Remove that pending row so a failed capture
                // never leaves an empty/unfinished item in the gallery.
                try {
                    val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                        "${MediaStore.Images.Media.RELATIVE_PATH}=?"
                    val args = arrayOf(name, "Pictures/JejakCam/")
                    contentResolver.delete(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        selection,
                        args
                    )
                } catch (_: Exception) { }
                Toast.makeText(this@MainActivity, "Gagal mengambil foto: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    // V73: verify that MediaStore has a real, readable photo after finalization.
    private fun verifyPublishedPhoto(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                size > 0L && (mime == null || mime.equals("image/jpeg", ignoreCase = true))
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    // V57: release idle CameraX when the Activity goes to the background, but keep
    // the user's intent so onResume()/configuration recreation can reopen it automatically. This avoids stale
    // camera bindings after screen-off/app-switch while preserving the home UI state.
    // V74: avoid background polling when the camera is not visible.
    private fun startPerformanceMonitors() {
        storageHandler.removeCallbacks(storageRunnable)
        thermalHandler.removeCallbacks(thermalRunnable)
        storageHandler.post(storageRunnable)
        thermalHandler.post(thermalRunnable)
    }

    private fun stopPerformanceMonitors() {
        storageHandler.removeCallbacks(storageRunnable)
        thermalHandler.removeCallbacks(thermalRunnable)
    }

    private fun releaseCameraForBackground() {
        if (!cameraActive || recording != null) return
        cameraStartToken++
        cameraRebindInProgress = false
        pendingZoomPreset = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        camera = null
        recorder = null
        imageCapture = null
        cameraActive = false
        stopPerformanceMonitors()
        backgroundCameraRelease = true
    }

    private fun closeCamera() {
        cameraRequested = false
        backgroundCameraRelease = false
        cameraStartToken++
        cameraRebindInProgress = false
        pendingZoomPreset = null
        if (recording != null) {
            Toast.makeText(this, "Hentikan rekaman terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        camera = null
        recorder = null
        imageCapture = null
        cameraActive = false
        stopPerformanceMonitors()
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
            if (recordingFinalizing) return
            recordingFinalizing = true
            stopRequestedByUser = true
            stoppingForLoop = false
            loopRemainingMs = 0L
            recording?.stop()
            return
        }
        segmentNumber = 1
        recordingFinalizing = false
        lowStorageStopTriggered = false
        stoppingForLoop = false
        stopRequestedByUser = false
        recordingPaused = false
        recordingSessionId++
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

    override fun onSaveInstanceState(outState: Bundle) {
        // V57: retain only the user's camera intent. We deliberately do not
        // persist an active Recording object; CameraX owns that lifecycle.
        outState.putBoolean("camera_requested", cameraRequested)
        outState.putBoolean("requested_photo_mode", requestedPhotoMode)
        outState.putBoolean("resume_recording_after_interruption", resumeRecordingAfterInterruption)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        activityStopping = false
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        // V56: restore the camera only when the user had previously opened it.
        if (cameraRequested && !cameraActive && backgroundCameraRelease &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            backgroundCameraRelease = false
            photoMode = requestedPhotoMode
            startCamera(!requestedPhotoMode &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }

        // V77: if the camera stayed bound but Android finalized the recording during
        // an interruption, create a new segment without touching the previous file.
        if (resumeRecordingAfterInterruption && cameraActive && !photoMode && recording == null &&
            !recordingFinalizing && !isFinishing && !isDestroyed) {
            resumeRecordingAfterInterruption = false
            timerHandler.postDelayed({
                if (cameraActive && !photoMode && recording == null && !isFinishing && !isDestroyed) {
                    startSegment()
                }
            }, 350L)
        }
    }

    override fun onPause() {
        // V77: preserve the user's recording intent across lifecycle interruptions.
        // If the active Recording survives, nothing is restarted. If Android ends it
        // because another app takes the camera/mic (such as a call), onResume() will
        // rebind the camera and start a fresh, safe segment.
        if (recording != null && !recordingFinalizing) {
            resumeRecordingAfterInterruption = true
        }
        sensorManager.unregisterListener(this)
        if (gpsEnabled) stopGpsUpdates()
        super.onPause()
    }

    override fun onStop() {
        activityStopping = true
        // Do not tear down an active recording. CameraX can finish the recording
        // lifecycle without us destroying its use cases here. For an idle camera,
        // release the binding so Android can safely reclaim the camera while the app
        // is in the background.
        if (!isChangingConfigurations && !isFinishing && recording == null) {
            releaseCameraForBackground()
        }
        super.onStop()
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
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSensorHudUpdateMs < 100L) return
        lastSensorHudUpdateMs = nowMs
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
        activityStopping = true
        photoCaptureInProgress = false
        cameraRequested = false
        backgroundCameraRelease = false
        cameraStartToken++
        storageHandler.removeCallbacksAndMessages(null)
        thermalHandler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)
        countdownActive = false
        stoppingForLoop = false
        stopRequestedByUser = true
        recordingFinalizing = true
        val activeRecording = recording
        recording = null
        activeRecording?.stop()
        sensorManager.unregisterListener(this)
        stopGpsUpdates()
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        batteryReceiver = null
        cameraActive = false
        super.onDestroy()
    }
}
