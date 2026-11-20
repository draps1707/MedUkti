package com.medukti.keyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {

    data class DownloadState(
        val isRunning: Boolean = false,
        val packId: String = "",
        val label: String = "",
        val percent: Int = 0,
        val statusText: String = "",
        val error: String? = null
    )

    companion object {
        private const val CHANNEL_ID = "medukti_model_dl"
        private const val NOTIF_ID = 101
        const val EXTRA_PACK_ID = "pack_id"

        private val _state = MutableStateFlow(DownloadState())
        val state: StateFlow<DownloadState> = _state

        fun startPack(ctx: Context, packId: String) {
            val intent = Intent(ctx, ModelDownloadService::class.java).putExtra(EXTRA_PACK_ID, packId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dlJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Model Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packId = intent?.getStringExtra(EXTRA_PACK_ID) ?: return START_NOT_STICKY
        if (dlJob?.isActive == true) return START_STICKY

        val (label, artifacts) = when (packId) {
            "whisper_turbo" -> "Whisper Large-v3-Turbo" to listOf(
                ModelDownloader.SILERO_VAD,
                ModelDownloader.WHISPER_TURBO_TOKENS,
                ModelDownloader.WHISPER_TURBO_DEC,
                ModelDownloader.WHISPER_TURBO_ENC
            )
            "streaming_draft" -> "Indian-EN Streaming Draft" to listOf(
                ModelDownloader.SILERO_VAD,
                ModelDownloader.STREAMING_ZIP_TOKENS,
                ModelDownloader.STREAMING_ZIP_ENC,
                ModelDownloader.STREAMING_ZIP_DEC,
                ModelDownloader.STREAMING_ZIP_JOINER
            )
            "parakeet_indic" -> "Parakeet-Indic v1" to listOf(
                ModelDownloader.SILERO_VAD,
                ModelDownloader.PARAKEET_INDIC_TOKENS,
                ModelDownloader.PARAKEET_INDIC_DEC,
                ModelDownloader.PARAKEET_INDIC_JOINER,
                ModelDownloader.PARAKEET_INDIC_ENC
            )
            "sravaani" -> "SraVaani 1.0" to listOf(
                ModelDownloader.SILERO_VAD,
                ModelDownloader.SRAVAANI_DEC,
                ModelDownloader.SRAVAANI_ENC
            )
            else -> return START_NOT_STICKY
        }

        val notif = buildNotification("Downloading $label...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        _state.value = DownloadState(isRunning = true, packId = packId, label = label, percent = 0, statusText = "Starting $label...")

        dlJob = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                for ((idx, art) in artifacts.withIndex()) {
                    if (ModelDownloader.isInstalled(this@ModelDownloadService, art)) continue
                    val stepPrefix = "Downloading $label (${idx + 1}/${artifacts.size}: ${art.fileName})"
                    _state.value = DownloadState(isRunning = true, packId = packId, label = label, percent = 0, statusText = stepPrefix)
                    nm?.notify(NOTIF_ID, buildNotification(stepPrefix, 0))

                    val res = ModelDownloader.download(this@ModelDownloadService, art) { p ->
                        val msg = "Downloading ${art.fileName}: $p%"
                        _state.value = DownloadState(isRunning = true, packId = packId, label = label, percent = p, statusText = msg)
                        nm?.notify(NOTIF_ID, buildNotification("Downloading $label: $p%", p))
                    }
                    if (res.isFailure) {
                        val err = res.exceptionOrNull()?.message ?: "Download failed"
                        _state.value = DownloadState(isRunning = false, packId = packId, label = label, percent = 0, statusText = "Error: $err", error = err)
                        return@launch
                    }
                }
                _state.value = DownloadState(isRunning = false, packId = packId, label = label, percent = 100, statusText = "$label ready offline!")
                OfflineAsrEngines.preload(this@ModelDownloadService)
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Medଉକ୍ତି")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
