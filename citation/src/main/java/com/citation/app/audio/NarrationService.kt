package com.citation.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.citation.app.MainActivity
import com.citation.app.R
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.SkipGranularity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the book talking when nothing is on screen, and puts it where a listener expects to find it:
 * the lock screen, the notification shade, the headset button, the car.
 *
 * This is the difference between a "read aloud" button and something a person will actually listen
 * to a book with, and almost none of it is about speech. A foreground service is what stops the
 * system reclaiming the process the moment the reader locks their phone. A [MediaSession] is what
 * makes the platform treat this as media — so the pause button on a headset works, the lock screen
 * shows the book rather than a bare notification, and Android Auto and Assistant can drive it.
 *
 * It is written against the platform's own media session and notification rather than a media
 * library, because there is nothing here to play: no file, no container, no seekable stream, just an
 * engine and a position. A playback library would add a dependency and a player object for a service
 * that never touches either.
 *
 * The service owns none of the decisions. It reflects [Narrator.state] into a notification and a
 * playback state, turns transport controls back into narrator calls, and stops itself when the
 * narrator goes idle.
 */
class NarrationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var narrator: Narrator
    private var session: MediaSession? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        narrator = Narrator.get(this)
        createChannel()
        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(callback)
            isActive = true
        }
        // Explicitly not exported: this receiver is the system's to trigger, nobody else's.
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            narrator.state.collectLatest { render() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> narrator.toggle()
            ACTION_PAUSE -> narrator.pause()
            ACTION_PLAY -> narrator.resume()
            ACTION_NEXT -> narrator.skip(SkipGranularity.PARAGRAPH, forward = true)
            ACTION_PREVIOUS -> narrator.skip(SkipGranularity.PARAGRAPH, forward = false)
            ACTION_STOP -> {
                narrator.stop()
                return START_NOT_STICKY
            }
        }
        // Whatever the action, render before returning: a service started in the foreground must
        // show its notification within seconds or the system kills it.
        render()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        session?.isActive = false
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Reflect the narrator into the notification, the session, and this service's own lifetime.
     *
     * An idle narrator stops the service rather than leaving a dead notification behind: the reader
     * closed the book, and a stale "now playing" is a small lie the shade keeps telling.
     */
    private fun render() {
        val state = narrator.state.value
        val speaking = state.status == NarrationStatus.SPEAKING || state.status == NarrationStatus.PREPARING

        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    state.rate
                )
                .build()
        )
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(state.chapterOrdinal))
                .build()
        )

        // Foreground first, always. A service the system was asked to start in the foreground must
        // show a notification before it does anything else — including deciding to stop.
        val notification = buildNotification(speaking)
        if (started) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        } else {
            startForegroundCompat(notification)
            started = true
        }

        if (state.status == NarrationStatus.IDLE) {
            // The reader closed the book: a "now playing" left in the shade is a small lie.
            stopForegroundCompat()
            stopSelf()
            started = false
        }
    }

    private fun buildNotification(speaking: Boolean): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title())
            .setContentText(subtitle(narrator.state.value.chapterOrdinal))
            .setContentIntent(openReader())
            .setOnlyAlertOnce(true)
            .setOngoing(speaking)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (speaking) action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setDeleteIntent(service(ACTION_STOP))

        session?.sessionToken?.let { token ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    // The three that survive into the collapsed notification and the lock screen.
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun title(): String = narrator.bookTitle ?: "Reading aloud"

    /** The chapter's own title where the source gave one, and its number where it did not. */
    private fun subtitle(chapterOrdinal: Int): String =
        narrator.chapterTitle(chapterOrdinal) ?: "Chapter ${chapterOrdinal + 1}"

    private fun action(icon: Int, label: String, action: String) =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            label,
            service(action)
        ).build()

    private fun service(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, NarrationService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openReader(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reading aloud", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls while a book is being read aloud."
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    /** Unplugging the headphones pauses, as it does for every other kind of media. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) narrator.pause()
        }
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() { narrator.resume() }
        override fun onPause() { narrator.pause() }
        override fun onStop() { narrator.stop() }
        override fun onSkipToNext() { narrator.skip(SkipGranularity.PARAGRAPH, forward = true) }
        override fun onSkipToPrevious() { narrator.skip(SkipGranularity.PARAGRAPH, forward = false) }
    }

    companion object {
        private const val CHANNEL_ID = "citation.narration"
        private const val SESSION_TAG = "CitationNarration"
        private const val NOTIFICATION_ID = 4711

        const val ACTION_PLAY = "com.citation.app.audio.PLAY"
        const val ACTION_PAUSE = "com.citation.app.audio.PAUSE"
        const val ACTION_TOGGLE = "com.citation.app.audio.TOGGLE"
        const val ACTION_NEXT = "com.citation.app.audio.NEXT"
        const val ACTION_PREVIOUS = "com.citation.app.audio.PREVIOUS"
        const val ACTION_STOP = "com.citation.app.audio.STOP"

        /**
         * Bring the service up alongside playback the narrator has already started.
         *
         * Started rather than bound: the reader's activity may well be gone before the chapter is,
         * and a bound service would follow it out.
         */
        fun start(context: Context) {
            val intent = Intent(context, NarrationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the service; the narrator keeps the reader's position. */
        fun stop(context: Context) {
            context.startService(Intent(context, NarrationService::class.java).setAction(ACTION_STOP))
        }
    }
}
