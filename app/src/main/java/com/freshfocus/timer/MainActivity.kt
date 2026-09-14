package com.freshfocus.timer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private enum class Mode { TIMER, STOPWATCH }
    private var mode = Mode.TIMER
    private var running = false

    private var totalMillis = 0L
    private var elapsedMillis = 0L
    private var lastWholeSecond = -1L

    private val handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    private lateinit var mainTimeText: TextView
    private lateinit var focusReadoutText: TextView
    private lateinit var sastClockText: TextView
    private lateinit var startPauseBtn: Button
    private lateinit var timerMinutesInput: EditText
    private lateinit var cameraPreview: TextureView

    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null

    // Legacy Camera1 API, used on API < 21 where Camera2 doesn't exist.
    private var legacyCamera: android.hardware.Camera? = null
    private var legacyFocusPollRunnable: Runnable? = null

    private val sastFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Africa/Johannesburg") // SAST, UTC+2
    }
    private var clockRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainTimeText = findViewById(R.id.mainTimeText)
        focusReadoutText = findViewById(R.id.focusReadoutText)
        sastClockText = findViewById(R.id.sastClockText)
        startPauseBtn = findViewById(R.id.startPauseBtn)
        timerMinutesInput = findViewById(R.id.timerMinutesInput)
        cameraPreview = findViewById(R.id.cameraPreview)

        findViewById<Button>(R.id.tabTimerBtn).setOnClickListener { switchMode(Mode.TIMER) }
        findViewById<Button>(R.id.tabStopwatchBtn).setOnClickListener { switchMode(Mode.STOPWATCH) }
        startPauseBtn.setOnClickListener { toggleStartPause() }
        findViewById<Button>(R.id.resetBtn).setOnClickListener { resetTimer() }

        renderTime()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }

        cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        AlarmScheduler.scheduleAllDailyAlarms(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED && cameraPreview.isAvailable
        ) {
            openCamera()
        }
    }

    // ---------- Clock (SAST) ----------

    override fun onResume() {
        super.onResume()
        clockRunnable = object : Runnable {
            override fun run() {
                sastClockText.text = "SAST " + sastFormat.format(Date())
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(clockRunnable!!)
    }

    override fun onPause() {
        super.onPause()
        clockRunnable?.let { handler.removeCallbacks(it) }
    }

    // ---------- Timer / Stopwatch ----------

    private fun switchMode(newMode: Mode) {
        if (running) return
        mode = newMode
        resetTimer()
    }

    private fun toggleStartPause() {
        if (!running) {
            if (mode == Mode.TIMER && totalMillis == 0L) {
                val minutes = timerMinutesInput.text.toString().toIntOrNull() ?: 0
                if (minutes <= 0) return
                totalMillis = minutes * 60_000L
                elapsedMillis = 0L
            }
            running = true
            startPauseBtn.text = "Pause"
            lastWholeSecond = -1L
            startTicking()
        } else {
            running = false
            startPauseBtn.text = "Start"
            tickRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    private fun resetTimer() {
        running = false
        elapsedMillis = 0L
        totalMillis = 0L
        startPauseBtn.text = "Start"
        tickRunnable?.let { handler.removeCallbacks(it) }
        renderTime()
    }

    private fun startTicking() {
        tickRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                elapsedMillis += 200

                val remainingMillis = totalMillis - elapsedMillis
                if (mode == Mode.TIMER && remainingMillis <= 0) {
                    running = false
                    startPauseBtn.text = "Start"
                    playBeep(long = true)
                    mainTimeText.text = "00:00"
                    return
                }

                val currentWholeSecond =
                    if (mode == Mode.TIMER) (remainingMillis / 1000) else (elapsedMillis / 1000)

                if (currentWholeSecond != lastWholeSecond) {
                    lastWholeSecond = currentWholeSecond
                    onWholeSecondPassed(currentWholeSecond, remainingMillis)
                }

                renderTime()
                handler.postDelayed(this, 200)
            }
        }
        handler.post(tickRunnable!!)
    }

    /** Beep every full minute, and every single second during the timer's final 20 seconds. */
    private fun onWholeSecondPassed(secondsValue: Long, remainingMillis: Long) {
        val inFinalCountdown = mode == Mode.TIMER && remainingMillis in 1..20_000
        if (inFinalCountdown) {
            playBeep(long = false)
            return
        }
        val secondsElapsedTotal = if (mode == Mode.TIMER) {
            (totalMillis - remainingMillis) / 1000
        } else {
            secondsValue
        }
        if (secondsElapsedTotal > 0 && secondsElapsedTotal % 60 == 0L) {
            playBeep(long = false)
        }
    }

    private fun playBeep(long: Boolean) {
        tone.startTone(
            if (long) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP,
            if (long) 800 else 150
        )
    }

    private fun renderTime() {
        val millisToShow = if (mode == Mode.TIMER) (totalMillis - elapsedMillis).coerceAtLeast(0) else elapsedMillis
        val totalSeconds = millisToShow / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        mainTimeText.text = String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    // ---------- Camera focus readout ----------

    private fun openCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            openCamera2()
        } else {
            openLegacyCamera()
        }
    }

    private fun openCamera2() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val backId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return
            cameraId = backId

            manager.openCamera(backId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startPreview(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun startPreview(device: CameraDevice) {
        val texture = cameraPreview.surfaceTexture ?: return
        val surface = Surface(texture)

        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(surface)
        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(
                        requestBuilder.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: android.hardware.camera2.TotalCaptureResult
                            ) {
                                val focusDistance = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
                                val afState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
                                runOnUiThread {
                                    val distanceText = focusDistance?.let {
                                        if (it <= 0f) "infinity" else String.format(Locale.getDefault(), "%.2f diopters", it)
                                    } ?: "n/a"
                                    focusReadoutText.text = "Focus distance: $distanceText  |  AF state: ${afState ?: "n/a"}"
                                }
                            }
                        },
                        handler
                    )
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            handler
        )
    }

    /** Camera1 fallback for API < 21 (no CameraManager/Camera2 on those devices). */
    private fun openLegacyCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            @Suppress("DEPRECATION")
            val camera = android.hardware.Camera.open() ?: return
            legacyCamera = camera

            @Suppress("DEPRECATION")
            val params = camera.parameters
            @Suppress("DEPRECATION")
            if (params.supportedFocusModes?.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                @Suppress("DEPRECATION")
                params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            @Suppress("DEPRECATION")
            camera.parameters = params

            @Suppress("DEPRECATION")
            camera.setPreviewTexture(cameraPreview.surfaceTexture)
            @Suppress("DEPRECATION")
            camera.startPreview()

            pollLegacyFocusState(camera)
        } catch (_: Exception) {
            focusReadoutText.text = "Focus distance: unavailable on this camera"
        }
    }

    /** Old Camera1 API exposes only a focus-locked callback, not a live distance value. */
    private fun pollLegacyFocusState(camera: android.hardware.Camera) {
        legacyFocusPollRunnable = object : Runnable {
            override fun run() {
                if (legacyCamera == null) return
                try {
                    @Suppress("DEPRECATION")
                    camera.autoFocus { success, _ ->
                        focusReadoutText.text = if (success) {
                            "Focus: locked (continuous autofocus)"
                        } else {
                            "Focus: searching..."
                        }
                    }
                } catch (_: Exception) {
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(legacyFocusPollRunnable!!)
    }

    private fun closeLegacyCamera() {
        legacyFocusPollRunnable?.let { handler.removeCallbacks(it) }
        legacyFocusPollRunnable = null
        legacyCamera?.let {
            @Suppress("DEPRECATION")
            it.stopPreview()
            it.release()
        }
        legacyCamera = null
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        closeLegacyCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        tone.release()
    }
}
