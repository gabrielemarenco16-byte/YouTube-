package it.shortsgen.app.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.shortsgen.app.MainActivity
import it.shortsgen.app.R
import it.shortsgen.app.core.ErrorMessages
import it.shortsgen.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano che esegue la generazione: continua anche se l'app
 * viene chiusa, mostrando l'avanzamento nella notifica.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (job?.isActive == true) job?.cancel() else stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RETRY -> {
                startInForeground("Preparazione…", 0f)
                if (job?.isActive != true) {
                    job = scope.launch { generate(resume = intent.action == ACTION_RETRY) }
                }
            }
            ACTION_REPLACE_CLIP -> {
                startInForeground("Cambio clip…", 0f)
                val scene = intent.getIntExtra(EXTRA_SCENE, -1)
                if (job?.isActive != true && scene >= 0) {
                    job = scope.launch { replaceClip(scene) }
                } else if (job?.isActive != true) {
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun generate(resume: Boolean) {
        acquireWakeLock()
        val jobs = JobStore(this)
        var pipeline: GenerationPipeline? = null
        try {
            val settings = SettingsRepository(this).load()
            val dir = (if (resume) jobs.currentJob() else null) ?: jobs.newJob()
            GenerationRepository.update(GenerationState.Running(Phase.SCRIPT, 0f, "Avvio…"))
            pipeline = GenerationPipeline(this, settings, onState = { running ->
                GenerationRepository.update(running)
                updateProgressNotification(running)
            })
            val result = pipeline.run(dir)
            jobs.saveResult(result)
            GenerationRepository.update(GenerationState.Success(result))
            notifyFinished("Video pronto!", result.title)
        } catch (e: CancellationException) {
            GenerationRepository.update(GenerationState.Failed("Generazione annullata.", pipeline?.phase))
        } catch (e: Throwable) {
            val phase = pipeline?.phase ?: Phase.SCRIPT
            val message = ErrorMessages.toItalian(e, phase.label)
            android.util.Log.e(TAG, "Generazione fallita", e)
            GenerationRepository.update(GenerationState.Failed(message, phase))
            notifyFinished("Generazione non riuscita", message)
        } finally {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Sostituisce la clip di una scena e rifà solo il montaggio. */
    private suspend fun replaceClip(scene: Int) {
        acquireWakeLock()
        val previous = GenerationRepository.lastResult.value ?: JobStore(this).loadLastResult()
        try {
            if (previous == null) throw IllegalStateException("Nessun video da modificare")
            GenerationRepository.setNotice(null)
            GenerationRepository.update(GenerationState.Running(Phase.IMAGES, 0f, "Scena ${scene + 1}: cerco un'altra clip…"))
            val pipeline = GenerationPipeline(this, SettingsRepository(this).load(), onState = { running ->
                GenerationRepository.update(running)
                updateProgressNotification(running)
            })
            val result = pipeline.replaceClip(previous, scene)
            JobStore(this).saveResult(result)
            GenerationRepository.update(GenerationState.Success(result))
            GenerationRepository.setNotice("Clip della scena ${scene + 1} sostituita e video rimontato.")
        } catch (e: CancellationException) {
            previous?.let { GenerationRepository.update(GenerationState.Success(it)) }
            GenerationRepository.setNotice("Cambio clip annullato: il video non è stato modificato.")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Cambio clip fallito", e)
            previous?.let { GenerationRepository.update(GenerationState.Success(it)) }
            GenerationRepository.setNotice(ErrorMessages.toItalian(e, "Cambio clip"))
        } finally {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground(text: String, progress: Float) {
        val notification = progressNotification(text, progress)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_PROGRESS, notification, type)
    }

    private fun updateProgressNotification(state: GenerationState.Running) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < 700 && state.progress < 1f) return
        lastNotificationAt = now
        val text = "Fase ${state.phase.number}/${Phase.COUNT} – ${state.phase.label}: ${state.detail}"
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_PROGRESS, progressNotification(text, state.progress)) }
    }

    private fun progressNotification(text: String, progress: Float) =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Generazione video in corso")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Annulla", cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun notifyFinished(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_DONE, notification) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, GenerationService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShortsGenerator:generation").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GenerationService"
        const val ACTION_START = "it.shortsgen.app.START"
        const val ACTION_RETRY = "it.shortsgen.app.RETRY"
        const val ACTION_CANCEL = "it.shortsgen.app.CANCEL"
        const val ACTION_REPLACE_CLIP = "it.shortsgen.app.REPLACE_CLIP"
        const val EXTRA_SCENE = "scene"
        private const val CHANNEL_PROGRESS = "generation_progress"
        private const val CHANNEL_DONE = "generation_done"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_DONE = 2

        fun start(context: Context, retry: Boolean) {
            val intent = Intent(context, GenerationService::class.java)
                .setAction(if (retry) ACTION_RETRY else ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** "Cambia clip" per la scena [scene] (0 = prima scena) dell'ultimo video. */
        fun replaceClip(context: Context, scene: Int) {
            val intent = Intent(context, GenerationService::class.java)
                .setAction(ACTION_REPLACE_CLIP)
                .putExtra(EXTRA_SCENE, scene)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, GenerationService::class.java).setAction(ACTION_CANCEL))
        }

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Avanzamento generazione", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Video completati", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
