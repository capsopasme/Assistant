package io.github.capsopasme.assistant.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.capsopasme.assistant.R
import io.github.capsopasme.assistant.settings.SettingsActivity
import io.github.capsopasme.assistant.util.Prefs
import io.github.capsopasme.assistant.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the assistant's process out of the freezer.
 *
 * HyperOS (and Android's cached-app freezer) freeze background processes. The power key reaches
 * the assistant through a one-way binder call; to a frozen process that call is only queued, so
 * the press does nothing and the panel pops up later, when the app is next opened. A foreground
 * service is the documented way to stay unfrozen. It does no work at all: no wake lock, no timer,
 * no CPU while idle. Without the notification permission (never requested) its notification is
 * not shown; it only appears in the system's list of active apps.
 *
 * It also watches the two settings the power key depends on (voice_interaction_service and
 * long_press_power_key). The system can clear the first one by itself, which unbinds the
 * assistant service, so nothing else in the app would notice. A content observer costs nothing
 * while idle: it only fires when the setting is written.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recheck = Runnable {
        if (!Prefs.get(this).keepAssistant) return@Runnable
        scope.launch {
            runCatching { AssistantSetup.ensure(applicationContext, "设置被改动") }
                .onFailure { Log.w(TAG, "ensure failed", it) }
        }
    }
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // the system often writes several settings in a row: check once it has settled
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, 3_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // keep Audio Input's recognizer (and its loaded model) bound while we are alive
        VoiceInput.setResident(this, Prefs.get(this).voiceResident)
        runCatching {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor("voice_interaction_service"), false, settingsObserver)
            contentResolver.registerContentObserver(Settings.Secure.getUriFor("assistant"), false, settingsObserver)
            contentResolver.registerContentObserver(Settings.System.getUriFor("long_press_power_key"), false, settingsObserver)
        }.onFailure { Log.w(TAG, "could not watch the assistant settings: ${it.message}") }
    }

    override fun onDestroy() {
        instance = null
        runCatching { contentResolver.unregisterContentObserver(settingsObserver) }
        handler.removeCallbacks(recheck)
        scope.cancel()
        VoiceInput.setResident(this, false)
        super.onDestroy()
    }

    /** While dictating, the foreground service also covers the microphone. */
    private fun updateTypes(microphone: Boolean) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            startForeground(NOTIFICATION_ID, notification(), types)
        } catch (e: Exception) {
            // not allowed right now (e.g. no visible window): recording still works while the
            // assistant window is showing
            Log.w(TAG, "foreground type change refused: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first in every case: a service started with startForegroundService()
        // that stops without it crashes the app
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.get(this).keepAlive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "保持可唤起", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "让长按电源键随时都能唤起助手"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("助手")
            .setContentText("长按电源键随时可唤起")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 1

        @Volatile private var instance: KeepAliveService? = null

        /** Adds / removes the microphone type while the assistant is listening. Main thread. */
        fun setMicrophone(on: Boolean) {
            instance?.updateTypes(on)
        }

        /** Starts or stops the service to match the setting. Safe to call from anywhere. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, KeepAliveService::class.java)
            if (Prefs.get(app).keepAlive) {
                // may be refused when called from the background without an exemption; the
                // assistant window, the settings screen and boot all qualify
                runCatching { app.startForegroundService(intent) }
                    .onFailure { Log.w(TAG, "could not start: ${it.message}") }
            } else {
                app.stopService(intent)
            }
        }
    }
}
