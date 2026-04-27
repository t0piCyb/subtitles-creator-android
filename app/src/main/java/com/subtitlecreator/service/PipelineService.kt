package com.subtitlecreator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.subtitlecreator.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that owns the long-running pipeline operations
 * (model download, transcription, export) so they survive screen-off,
 * Doze mode, and the activity being destroyed.
 *
 * Communication with the UI is one-way via [PipelineStore] (StateFlow).
 */
class PipelineService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val whisper = WhisperService()
    private lateinit var ffmpeg: FFmpegService
    private lateinit var burner: SubtitleBurner
    private lateinit var modelDl: ModelDownloadService
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ffmpeg = FFmpegService(applicationContext)
        burner = SubtitleBurner(applicationContext)
        modelDl = ModelDownloadService(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("Ready", 0)
        when (intent?.action) {
            ACTION_DOWNLOAD  -> startDownload()
            ACTION_TRANSCRIBE -> startTranscribe(intent.getStringExtra(EXTRA_VIDEO_PATH) ?: return stopAndReturn())
            ACTION_EXPORT     -> startExport(intent.getStringExtra(EXTRA_VIDEO_PATH) ?: return stopAndReturn())
            ACTION_CANCEL     -> cancelActive()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        whisper.release()
        super.onDestroy()
    }

    // --- operations -------------------------------------------------------

    private fun startDownload() {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            acquireWakeLock()
            try {
                updateNotification("Downloading Whisper model…", 0)
                modelDl.download { done, total ->
                    PipelineStore.update { it.copy(modelDownloadMb = done, modelTotalMb = total) }
                    val pct = if (total > 0) (done * 100 / total) else 0
                    updateNotification("Downloading model $done / $total MB", pct)
                }
                PipelineStore.update { it.copy(modelReady = true) }
            } catch (t: Throwable) {
                Log.e(TAG, "download failed", t)
                PipelineStore.update { it.copy(error = "Model download failed: ${t.message}") }
            } finally {
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun startTranscribe(videoPath: String) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            acquireWakeLock()
            try {
                PipelineStore.update { it.copy(transcribing = true, transcribeProgress = 0, error = null) }
                updateNotification("Extracting audio…", 0)

                val video = File(videoPath)
                whisper.ensureLoaded(modelDl.modelFile())
                val wav = File(video.parentFile, video.nameWithoutExtension + ".wav")
                ffmpeg.extractAudio16kMono(video, wav)

                updateNotification("Transcribing on-device…", 0)
                val result = whisper.transcribe(wav, language = "auto", nThreads = 8) { p ->
                    PipelineStore.update { it.copy(transcribeProgress = p) }
                    updateNotification("Transcribing $p%", p)
                }
                PipelineStore.update {
                    it.copy(transcribing = false, subtitles = result.subtitles, transcribeProgress = 100)
                }
                updateNotification("Transcription done", 100)
            } catch (t: Throwable) {
                Log.e(TAG, "transcribe failed", t)
                PipelineStore.update { it.copy(transcribing = false, error = "Transcription failed: ${t.message}") }
            } finally {
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun startExport(videoPath: String) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            acquireWakeLock()
            try {
                val subs = PipelineStore.current().subtitles
                if (subs.isEmpty()) { stopSelf(); return@launch }

                PipelineStore.update { it.copy(exporting = true, exportProgress = 0, error = null) }
                updateNotification("Burning subtitles…", 0)

                val video = File(videoPath)
                val out = FileUtils.outputVideoFile(applicationContext)
                val cur = PipelineStore.current()
                val fontSize = cur.subtitleFontSize
                // Media3 TextOverlay uses pixel size scaled to the video frame's bitmap;
                // ~2x the ASS fontsize looks right on 1080p.
                val pxSize = (fontSize * 2).coerceIn(48, 400)
                burner.burn(
                    videoIn = video,
                    subtitles = subs,
                    videoOut = out,
                    fontSize = pxSize,
                    positionFraction = cur.subtitlePositionFraction
                ) { p ->
                    PipelineStore.update { it.copy(exportProgress = p) }
                    updateNotification("Exporting $p%", p)
                }
                val uri = FileUtils.saveToGallery(applicationContext, out, out.name)
                PipelineStore.update {
                    it.copy(exporting = false, exportedFile = out, savedUri = uri, exportProgress = 100)
                }
                updateNotification("Export done — saved to gallery", 100)
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
                PipelineStore.update { it.copy(exporting = false, error = "Export failed: ${t.message}") }
            } finally {
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun cancelActive() {
        activeJob?.cancel()
        stopSelf()
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    // --- notification + wake lock ----------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio/video processing"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Subtitles Creator")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress in 1..99) b.setProgress(100, progress, false)
        else if (progress == 0) b.setProgress(0, 0, true) // indeterminate
        return b.build()
    }

    private fun startForegroundCompat(text: String, progress: Int) {
        val notif = buildNotification(text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubtitlesCreator:Pipeline").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 min safety ceiling
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "PipelineService"
        private const val CHANNEL_ID = "pipeline"
        private const val NOTIF_ID = 42

        const val ACTION_DOWNLOAD = "com.subtitlecreator.ACTION_DOWNLOAD"
        const val ACTION_TRANSCRIBE = "com.subtitlecreator.ACTION_TRANSCRIBE"
        const val ACTION_EXPORT = "com.subtitlecreator.ACTION_EXPORT"
        const val ACTION_CANCEL = "com.subtitlecreator.ACTION_CANCEL"
        const val EXTRA_VIDEO_PATH = "videoPath"
    }
}
