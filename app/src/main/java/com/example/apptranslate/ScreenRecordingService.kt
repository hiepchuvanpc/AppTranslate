package com.example.apptranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordingService : Service() {
    
    companion object {
        private const val TAG = "ScreenRecordingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_recording_channel"
        
        // Intent keys
        const val INTENT_RESULT_CODE = "resultCode"
        const val INTENT_DATA = "data"
        
        // Actions
        const val ACTION_START = "start_recording"
        const val ACTION_STOP = "stop_recording"
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Create notification channel for foreground service
        createNotificationChannel()
        
        // Get screen metrics
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(INTENT_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(INTENT_DATA)
                
                if (resultCode != -1 && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startRecording(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Recording"
            val description = "Notification for screen recording service"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording")
            .setContentText("Recording your screen...")
            .setSmallIcon(R.drawable.ic_pause) // Use an appropriate icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
    
    private fun startRecording(resultCode: Int, data: Intent) {
        // Initialize MediaProjection
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        
        // Set up MediaRecorder
        setupMediaRecorder()
        
        // Create virtual display for recording
        createVirtualDisplay()
        
        // Start recording
        try {
            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun setupMediaRecorder() {
        val videoFile = createVideoFile()
        
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        
        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8 * 1000 * 1000) // 8Mbps
            setOutputFile(videoFile.absolutePath)
            
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private fun createVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecording_$timestamp.mp4"
        
        val storageDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        
        return File(storageDir, fileName)
    }
    
    private fun createVirtualDisplay() {
        mediaProjection?.let { projection ->
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecording",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )
        }
    }
    
    private fun stopRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                
                virtualDisplay?.release()
                mediaProjection?.stop()
                
                isRecording = false
                Log.d(TAG, "Recording stopped")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording: ${e.message}")
                e.printStackTrace()
            } finally {
                mediaRecorder = null
                virtualDisplay = null
                mediaProjection = null
            }
        }
    }
    
    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
