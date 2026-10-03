package dev.beam.feature.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import dev.beam.core.model.TransferError
import dev.beam.core.session.SessionToken
import dev.beam.core.session.SessionTokenCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.IOException

/** What a tap produced. */
sealed interface TapResult {
    data class Success(val token: SessionToken) : TapResult

    /** Something was tapped but it was not a usable Beam receiver. */
    data class Failure(val error: TransferError) : TapResult
}

/**
 * The sending half of the tap.
 *
 * Puts the activity's NFC radio into reader mode and talks to the other phone's
 * [BeamHceService]. `FLAG_READER_SKIP_NDEF_CHECK` matters: without it the platform spends time
 * probing for an NDEF message that a Beam receiver will never have, which is dead time while
 * two people are holding phones together.
 *
 * Collect [taps] while the send screen is in the foreground; the reader mode is torn down when
 * collection stops.
 */
class BeamReaderController(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isNfcAvailable: Boolean get() = adapter != null
    val isNfcEnabled: Boolean get() = adapter?.isEnabled == true

    fun taps(): Flow<TapResult> = callbackFlow {
        val nfc = adapter
        if (nfc == null || !nfc.isEnabled) {
            trySend(TapResult.Failure(TransferError.NfcUnavailable()))
            close()
            return@callbackFlow
        }

        val scope = CoroutineScope(coroutineContext)
        val callback = NfcAdapter.ReaderCallback { tag ->
            // Called on NFC's own thread; the exchange itself is blocking I/O.
            scope.launch(Dispatchers.IO) { trySend(readToken(tag)) }
        }

        nfc.enableReaderMode(
            activity,
            callback,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply {
                // Keep presence checks out of the way of the short exchange.
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
            },
        )

        awaitClose { runCatching { nfc.disableReaderMode(activity) } }
    }.flowOn(Dispatchers.IO)

    /** Runs the two-command exchange against a tapped device. */
    private fun readToken(tag: Tag): TapResult {
        val isoDep = IsoDep.get(tag)
            ?: return TapResult.Failure(TransferError.MalformedToken("That device is not running Beam"))

        return try {
            isoDep.timeout = TRANSCEIVE_TIMEOUT_MS
            isoDep.connect()

            val selectResponse = isoDep.transceive(BeamApdu.selectCommand())
            if (BeamApdu.responseBody(selectResponse) == null) {
                return TapResult.Failure(
                    TransferError.MalformedToken("That device is not running Beam, or receiving is off")
                )
            }

            val sessionResponse = isoDep.transceive(BeamApdu.getSessionCommand())
            val body = BeamApdu.responseBody(sessionResponse)
                ?: return TapResult.Failure(
                    TransferError.MalformedToken("The other device is not accepting beams right now")
                )

            SessionTokenCodec.decode(body).fold(
                onSuccess = { TapResult.Success(it) },
                onFailure = { TapResult.Failure(SessionTokenCodec.errorOf(it)) },
            )
        } catch (e: IOException) {
            // Overwhelmingly this is the phones being separated mid-exchange.
            TapResult.Failure(TransferError.Io("Hold the phones together a moment longer"))
        } catch (e: SecurityException) {
            TapResult.Failure(TransferError.NfcUnavailable())
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private companion object {
        const val TRANSCEIVE_TIMEOUT_MS = 2_000
        const val PRESENCE_CHECK_DELAY_MS = 1_000
    }
}
