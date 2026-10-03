package dev.beam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.beam.core.session.HandshakeState
import dev.beam.core.session.SessionToken
import dev.beam.core.session.SessionTokenCodec
import dev.beam.ui.ReceiveActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the receive side alive for the few seconds between the tap and the last byte.
 *
 * This is the only background component Beam runs, and it runs only inside a transfer: started
 * from the tap, stopped the moment the handshake reaches a terminal state. There is no
 * always-on advertising to drain the battery — the advertisement exists for one session and is
 * withdrawn with it.
 *
 * The transfer itself belongs to [TransferCoordinator] at application scope; this service's job
 * is purely to tell Android the process is doing something the user asked for, and to put the
 * confirmation in front of them.
 */
class BeamTransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as BeamApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val encoded = intent?.getByteArrayExtra(EXTRA_TOKEN)
        val token = encoded?.let { SessionTokenCodec.decode(it).getOrNull() }
        if (token == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground(waitingNotification())
        beginReceiving(token)
        observeState()
        return START_NOT_STICKY
    }

    private fun beginReceiving(token: SessionToken) {
        val autoAcceptEnabled = container.settingsSnapshot.value.autoAcceptKnownDevices
        container.coordinator.beginReceive(token) { peer ->
            // "Known" means a beam from this device has completed here before.
            autoAcceptEnabled && runCatching {
                container.database.transfers().isKnownPeer(peer.stableId)
            }.getOrDefault(false)
        }
    }

    private fun observeState() {
        scope.launch {
            container.coordinator.pendingDecision.collectLatest { pending ->
                if (pending != null) promptForAcceptance(pending.peer.displayName, pending.manifest.describe())
            }
        }
        scope.launch {
            container.coordinator.state.collectLatest { state ->
                when (state) {
                    is HandshakeState.Transferring -> notify(transferNotification(state))
                    is HandshakeState.Complete -> {
                        notifyResult(getString(R.string.notif_received_title), state.peer.displayName)
                        finish()
                    }
                    is HandshakeState.Failed -> {
                        // A declined or cancelled beam is not worth a notification.
                        finish()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------- notifications

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID_PROGRESS, notification)
        }
    }

    private fun waitingNotification(): Notification =
        baseNotification()
            .setContentTitle(getString(R.string.notif_incoming_title))
            .setContentText(getString(R.string.notif_incoming_text))
            .setProgress(0, 0, true)
            .build()

    private fun transferNotification(state: HandshakeState.Transferring): Notification =
        baseNotification()
            .setContentTitle(getString(R.string.notif_receiving_title, state.peer.displayName))
            .setProgress(100, (state.fraction * 100).toInt(), state.totalBytes <= 0)
            .build()

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_beam_notification)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    /**
     * The receiver may be looking at their lock screen, so the confirmation is a full-screen
     * intent rather than something they have to find.
     */
    private fun promptForAcceptance(peerName: String, what: String) {
        val intent = Intent(this, ReceiveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        notify(
            NotificationCompat.Builder(this, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_beam_notification)
                .setContentTitle(getString(R.string.notif_offer_title, peerName))
                .setContentText(what)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pending, true)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )

        startActivity(intent)
    }

    private fun notifyResult(title: String, text: String) {
        notify(
            NotificationCompat.Builder(this, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_beam_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
            id = ID_RESULT,
        )
    }

    /**
     * Posting is best-effort: the user can refuse notifications and the beam still works — the
     * foreground service keeps running and the receive screen still appears. Only the progress
     * readout is lost.
     */
    private fun notify(notification: Notification, id: Int = ID_PROGRESS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TOKEN = "dev.beam.extra.TOKEN"
        private const val ID_PROGRESS = 1001
        private const val ID_RESULT = 1002

        const val CHANNEL_TRANSFER = "beam_transfer"
        const val CHANNEL_INCOMING = "beam_incoming"

        /**
         * Called from the HCE service's binder thread the instant the phones touch. Fire and
         * forget: the token is already on its way back over NFC.
         */
        fun startReceiving(context: Context, token: SessionToken) {
            val intent = Intent(context, BeamTransferService::class.java)
                .putExtra(EXTRA_TOKEN, SessionTokenCodec.encode(token))
            context.startForegroundService(intent)
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRANSFER,
                    context.getString(R.string.channel_transfer),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INCOMING,
                    context.getString(R.string.channel_incoming),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
    }
}