package com.chuest.romtools.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.RomPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class RomBuildService : Service() {

    companion object {
        private const val CH_ID = "rom-build"
        private const val NOTIF_ID = 1
        const val EXTRA_ROM_URI = "rom_uri"
        const val EXTRA_WORK_DIR = "work_dir"
        const val EXTRA_PLUGINS = "plugins"
        const val EXTRA_SUPERKEY = "superkey"

        @Volatile var running: Boolean = false; private set

        fun start(ctx: Context, romUri: Uri, workDir: File, enabledPlugins: Set<String>, superKey: String? = null) {
            val i = Intent(ctx, RomBuildService::class.java).apply {
                putExtra(EXTRA_ROM_URI, romUri)
                putExtra(EXTRA_WORK_DIR, workDir.absolutePath)
                putStringArrayListExtra(EXTRA_PLUGINS, ArrayList(enabledPlugins))
                putExtra(EXTRA_SUPERKEY, superKey)
            }
            ctx.startForegroundService(i)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Initializing…"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RomTools:build")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        val uri = intent.getParcelableExtra<Uri>(EXTRA_ROM_URI) ?: run { stopSelf(); return START_NOT_STICKY }
        val work = File(intent.getStringExtra(EXTRA_WORK_DIR) ?: filesDir.absolutePath)
        val plugins = intent.getStringArrayListExtra(EXTRA_PLUGINS)?.toSet() ?: Plugins.DEFAULT_ENABLED
        val superKey = intent.getStringExtra(EXTRA_SUPERKEY)
        running = true
        job = scope.launch {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { /* one-shot uri */ }
            try {
                Logger.n("Pipeline start: rom=$uri  work=$work  plugins=${plugins.sorted()}")
                RomPipeline(
                    ctx = this@RomBuildService,
                    romZipUri = uri,
                    workRoot = work,
                    features = RomPipeline.Features(enabled = plugins, apatchSuperKey = superKey)
                ).run()
                Logger.n("Pipeline finished")
            } catch (t: Throwable) {
                Logger.e("Pipeline failed", t)
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { wakeLock?.release() }
        scope.cancel()
        running = false
    }

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CH_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_ID, "ROM build", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RomTools")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
