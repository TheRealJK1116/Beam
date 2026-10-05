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
        when (intent?.action) {
            ACTION_SEND_GUARD -> {
                // The sending activity can be backgrounded or the screen locked mid-transfer;
                // the transfer itself lives in TransferCoordinator at application scope, so
                // this exists purely to keep the process alive and the radios awake.
                startAsForeground(sendingNotification())
                acquireLocks()
                observeStateOnce()
            }

            else -> {
                val encoded = intent?.getByteArrayExtra(EXTRA_TOKEN)
                val token = encoded?.let { SessionTokenCodec.decode(it).getOrNull() }
                if (token == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground(waitingNotification())
                acquireLocks()
                beginReceiving(token)
                observeStateOnce()
                announceIncoming()
            }
        }
        return START_NOT_STICKY
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * A multi-gigabyte transfer outlasts the screen timeout. Without these the CPU sleeps and
     * the Wi-Fi chip powers down mid-stream, which the peer sees as the connection dying.
     */
    private var locksAcquired = false

    private fun acquireLocks() {
        // Guarding on `wakeLock != null` was wrong: if that half threw or returned null, a
        // second call ran the body again and overwrote an already-acquired Wi-Fi lock,
        // leaking it for the life of the process. Each lock is also taken independently, so
        // one failing no longer silently costs us the other.
        if (locksAcquired) return
        locksAcquired = true

        runCatching {
            val power = getSystemService(android.os.PowerManager::class.java)
            wakeLock = power?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "beam:transfer")
                ?.apply { acquire(MAX_TRANSFER_MILLIS) }
        }.onFailure { android.util.Log.w(TAG, "Could not hold a wake lock; a long transfer may stall", it) }

        runCatching {
            val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "beam:transfer",
            )?.apply { acquire() }
        }.onFailure { android.util.Log.w(TAG, "Could not hold a Wi-Fi lock; the radio may sleep", it) }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        locksAcquired = false
    }

    private fun sendingNotification(): Notification =
        baseNotification()
            .setContentTitle(getString(R.string.notif_sending_title))
            .setProgress(0, 0, true)
            .build()

    private fun beginReceiving(token: SessionToken) {
        val autoAcceptEnabled = container.settingsSnapshot.value.autoAcceptKnownDevices
        container.coordinator.beginReceive(token) { peer ->
            // "Known" means a beam from this device has completed here before.
            autoAcceptEnabled && runCatching {
                container.database.transfers().isKnownPeer(peer.stableId)
            }.getOrDefault(false)
        }
    }

    private var observing = false

    /**
     * A send guard followed by a receive start - or any redelivery - used to launch a second
     * pair of collectors into the same long-lived scope, which meant duplicate progress
     * notifications and, worse, a second full-screen ReceiveActivity for one incoming beam.
     */
    private fun observeStateOnce() {
        if (observing) return
        observing = true
        observeState()
    }

    private fun observeState() {
        scope.launch {
            container.coordinator.pendingDecision.collectLatest { pending ->
                if (pending != null) promptForAcceptance(pending.peer.displayName, pending.manifest.describe())
            }
        }
        scope.launch {
            // A terminal state seen before any active one belongs to the *previous* beam:
            // the service starts fractionally before the transfer it is guarding begins, and
            // acting on that leftover made it stop itself immediately - which is how a
            // backgrounded send lost its foreground service and died.
            var sawActive = false

            container.coordinator.state.collectLatest { state ->
                if (!state.isTerminal && state !is HandshakeState.Idle) sawActive = true
                if (state.isTerminal && !sawActive) return@collectLatest

                when (state) {
                    is HandshakeState.Transferring -> notify(transferNotification(state))
                    is HandshakeState.Complete -> {
                        // The same service now guards sending, so this has to know which way
                        // the bytes went.
                        val title = if (state.role == dev.beam.core.session.Role.SENDER) {
                            getString(R.string.notif_sent_title)
                        } else {
                            getString(R.string.notif_received_title)
                        }
                        notifyResult(title, state.peer.displayName)
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
        releaseLocks()
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

    private fun transferNotification(state: HandshakeState.Transferring): Notification {
        val title = if (state.role == dev.beam.core.session.Role.SENDER) {
            getString(R.string.notif_sending_to_title, state.peer.displayName)
        } else {
            getString(R.string.notif_receiving_title, state.peer.displayName)
        }
        return baseNotification()
            .setContentTitle(title)
            // "3 of 12 · holiday/beach.jpg" while a folder is moving.
            .setContentText(
                if (state.fileCount > 1) {
                    getString(
                        R.string.notif_file_of,
                        state.fileIndex + 1,
                        state.fileCount,
                        state.fileName.orEmpty(),
                    )
                } else {
                    state.fileName
                }
            )
            .setProgress(100, (state.fraction * 100).toInt(), state.totalBytes <= 0)
            .build()
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_beam_notification)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    /**
     * Put the receive screen up the moment a tap is answered, before anything is known about
     * what is coming.
     *
     * Previously nothing appeared until the manifest arrived, so a handshake that died
     * earlier - the radios failing to meet, the sender walking away - looked exactly like
     * Beam being broken. The screen can say "incoming" and then say why it failed; showing
     * nothing can do neither.
     */
    private fun announceIncoming() {
        val intent = Intent(this, ReceiveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(intent) }
    }

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
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BeamService"
        private const val EXTRA_TOKEN = "dev.beam.extra.TOKEN"
        private const val ACTION_SEND_GUARD = "dev.beam.action.SEND_GUARD"

        /** Upper bound on a single beam; the lock is released on completion long before this. */
        private const val MAX_TRANSFER_MILLIS = 30L * 60L * 1000L
        private const val ID_PROGRESS = 1001
        private const val ID_RESULT = 1002
        private const val ID_SETUP = 1003

        const val CHANNEL_TRANSFER = "beam_transfer"
        const val CHANNEL_INCOMING = "beam_incoming"

        /**
         * Called from the HCE service's binder thread the instant the phones touch. Fire and
         * forget: the token is already on its way back over NFC.
         */
        /**
         * Called when a send begins, so the transfer survives the user leaving the screen or
         * the phone locking. The service holds no state of its own; it guards the process.
         */
        fun startSending(context: Context) {
            val intent = Intent(context, BeamTransferService::class.java).setAction(ACTION_SEND_GUARD)
            runCatching { context.startForegroundService(intent) }
        }

        fun startReceiving(context: Context, token: SessionToken) {
            val intent = Intent(context, BeamTransferService::class.java)
                .putExtra(EXTRA_TOKEN, SessionTokenCodec.encode(token))
            context.startForegroundService(intent)
        }

        /**
         * A tap arrived but Beam cannot act on it. Told as a notification because there is no
         * UI in front of the user at this moment - their phone was sitting on a table.
         */
        fun notifySetupRequired(context: Context) {
            createChannels(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, dev.beam.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            runCatching {
                NotificationManagerCompat.from(context).notify(
                    ID_SETUP,
                    NotificationCompat.Builder(context, CHANNEL_INCOMING)
                        .setSmallIcon(R.drawable.ic_beam_notification)
                        .setContentTitle(context.getString(R.string.notif_setup_title))
                        .setContentText(context.getString(R.string.notif_setup_text))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
            }
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