package com.example.apptranslate.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.apptranslate.MainActivity
import com.example.apptranslate.R
import com.example.apptranslate.ui.overlay.FloatingBubbleView // SỬA: Đổi tên import
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Service quản lý nút nổi và các chức năng overlay
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        // Broadcast actions để thông báo trạng thái
        const val BROADCAST_SERVICE_STARTED = "com.example.apptranslate.SERVICE_STARTED"
        const val BROADCAST_SERVICE_STOPPED = "com.example.apptranslate.SERVICE_STOPPED"
        const val BROADCAST_SERVICE_ERROR = "com.example.apptranslate.SERVICE_ERROR"
        
        // Action để yêu cầu hiển thị Bottom Sheet chọn ngôn ngữ
        const val ACTION_SHOW_LANGUAGE_SHEET = "com.example.apptranslate.SHOW_LANGUAGE_SHEET"
        
        // Action để cập nhật ngôn ngữ trong panel
        const val ACTION_UPDATE_LANGUAGES = "com.example.apptranslate.UPDATE_LANGUAGES"
        const val EXTRA_SOURCE_LANG = "SOURCE_LANG"
        const val EXTRA_TARGET_LANG = "TARGET_LANG"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"

        // Static reference để kiểm tra service đang chạy
        var isRunning = false
            private set
    }

    // Views và Managers
    private lateinit var windowManager: WindowManager
    // SỬA: Đổi tên biến và kiểu dữ liệu
    private var floatingBubbleView: FloatingBubbleView? = null
    private var mediaProjection: MediaProjection? = null

    // MediaProjection data
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    // Coroutine scope cho service
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundImmediately()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                mediaProjectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                mediaProjectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                Log.d(TAG, "MediaProjection - ResultCode: $mediaProjectionResultCode, Data: ${mediaProjectionData != null}")

                // SỬA: Sửa lại điều kiện kiểm tra resultCode
                if (mediaProjectionResultCode == Activity.RESULT_OK && mediaProjectionData != null) {
                    updateNotification()
                    initializeMediaProjection()
                    showFloatingBubble()
                    isRunning = true
                    // SỬA: Gửi broadcast an toàn
                    sendSafeBroadcast(BROADCAST_SERVICE_STARTED)
                    Log.d(TAG, "Service started successfully")
                } else {
                    Log.e(TAG, "Invalid MediaProjection data, stopping service")
                    // SỬA: Gửi broadcast an toàn
                    sendSafeBroadcast(BROADCAST_SERVICE_ERROR)
                    stopSelf()
                }
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping service")
                stopService()
            }
            ACTION_UPDATE_LANGUAGES -> {
                // Nhận thông tin ngôn ngữ từ Intent
                val sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "vi"
                val targetCode = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "en"
                
                // Cập nhật hiển thị ngôn ngữ trên panel
                floatingBubbleView?.updateLanguageDisplay(sourceCode, targetCode)
                
                Log.d(TAG, "Updated languages: $sourceCode → $targetCode")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dịch vụ nút nổi đang chạy"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundImmediately() {
        val notification = createBasicNotification("Đang khởi động dịch vụ...")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createBasicNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("AppTranslate")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_translate)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun updateNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AppTranslate đang chạy")
            .setContentText("Nút nổi đang hoạt động")
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Dừng", stopPendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun initializeMediaProjection() {
        try {
            Log.d(TAG, "Initializing MediaProjection...")
            if (mediaProjectionData == null) {
                throw IllegalStateException("MediaProjection data is null")
            }

            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(
                mediaProjectionResultCode,
                mediaProjectionData!!
            )

            if (mediaProjection == null) {
                throw IllegalStateException("MediaProjection creation failed")
            }
            Log.d(TAG, "MediaProjection initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}", e)
            // SỬA: Gửi broadcast an toàn
            sendSafeBroadcast(BROADCAST_SERVICE_ERROR)
            stopSelf()
        }
    }

    private fun showFloatingBubble() {
        try {
            // Sử dụng ContextThemeWrapper để đảm bảo có theme Material Components
            Log.d(TAG, "Creating FloatingBubbleView...")
            val themedContext = android.view.ContextThemeWrapper(
                this, 
                com.google.android.material.R.style.Theme_MaterialComponents_Light
            )
            floatingBubbleView = FloatingBubbleView(themedContext, serviceScope).apply {
                setMediaProjection(mediaProjection)
                setOnLanguageSelectClick {
                    // Gửi broadcast để hiển thị Language Bottom Sheet
                    sendSafeBroadcast(ACTION_SHOW_LANGUAGE_SHEET)
                }
            }
            Log.d(TAG, "FloatingBubbleView created, adding to WindowManager...")

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            floatingBubbleView?.setViewLayoutParams(params)
            windowManager.addView(floatingBubbleView, params)
            Log.d(TAG, "FloatingBubbleView added to WindowManager successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating bubble", e)
            // SỬA: Gửi broadcast an toàn
            sendSafeBroadcast(BROADCAST_SERVICE_ERROR)
            stopSelf()
        }
    }

    private fun stopService() {
        if (!isRunning) return // Tránh gọi nhiều lần
        isRunning = false

        // SỬA: Gửi broadcast an toàn
        sendSafeBroadcast(BROADCAST_SERVICE_STOPPED)

        floatingBubbleView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating bubble view", e)
            }
        }
        floatingBubbleView = null

        mediaProjection?.stop()
        mediaProjection = null

        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Service stopped and cleaned up")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService()
    }

    /**
     * SỬA: Hàm helper để gửi broadcast một cách an toàn, chỉ trong nội bộ ứng dụng.
     */
    private fun sendSafeBroadcast(action: String) {
        val intent = Intent(action).apply {
            `package` = packageName
        }
        sendBroadcast(intent)
    }
}
