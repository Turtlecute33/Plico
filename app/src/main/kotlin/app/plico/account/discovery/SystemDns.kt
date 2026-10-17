package app.plico.account.discovery

import android.net.DnsResolver
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * DNS through the system resolver ([DnsResolver]): the queries follow Private DNS and a VPN as every other app
 * query does. No other resolver is used.
 */
internal object SystemDns {
    private const val TIMEOUT_MS = 5_000L

    /** The SRV or MX records of [name]; null when the query failed or took more than 5 s. */
    suspend fun query(name: String, type: Int): List<DnsRecord>? = withContext(Dispatchers.IO) {
        val question = try {
            DnsWire.query(name, type)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        // The id must be the one of the question.
                        val sameId = answer.size >= 2 && answer[0] == question[0] && answer[1] == question[1]
                        continuation.resume(if (sameId) DnsWire.parse(answer) else null)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.resume(null)
                    }
                }
                DnsResolver.getInstance().rawQuery(
                    null, question, DnsResolver.FLAG_EMPTY, Dispatchers.IO.asExecutor(), cancel, callback,
                )
            }
        }
    }
}
