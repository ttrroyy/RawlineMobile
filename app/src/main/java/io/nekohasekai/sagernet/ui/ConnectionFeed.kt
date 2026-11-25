package app.rawline.mobile.ui

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal data class ActiveConnection(
    val id: String,
    val process: String,
    val destination: String,
    val protocol: String,
    val route: String,
    val upload: Long,
    val download: Long,
)

internal class ConnectionFeed {
    private val active = LinkedHashMap<String, ActiveConnection>()

    fun accept(frame: Buffer): List<ActiveConnection> {
        val batch = frame.protoFields()
        if (batch.number(2) == 1L) active.clear()
        for (item in batch[1].orEmpty()) {
            val event = (item as? Buffer)?.protoFields() ?: continue
            val id = event.string(2)
            if (id.isEmpty()) continue
            when (event.number(1).toInt()) {
                0 -> {
                    val connection = event.message(3) ?: continue
                    if (connection.number(13) != 0L) continue
                    val owner = connection.message(22)
                    val process = owner?.string(5)?.takeIf(String::isNotEmpty)
                        ?: owner?.string(4)?.substringAfterLast('/').orEmpty()
                    val route = connection[21].orEmpty().filterIsInstance<Buffer>().map { it.clone().readUtf8() }
                        .joinToString(" → ").ifEmpty { connection.string(19) }
                    if (id in active || active.size < MAX_ACTIVE) active[id] = ActiveConnection(
                        id, process, connection.string(8).ifEmpty { connection.string(7) },
                        connection.string(9).ifEmpty { connection.string(5) }, route,
                        connection.number(16), connection.number(17),
                    )
                }
                1 -> active[id]?.let { row ->
                    active[id] = row.copy(
                        upload = row.upload + event.number(4),
                        download = row.download + event.number(5),
                    )
                }
                2 -> active.remove(id)
            }
        }
        return active.values.toList()
    }

    private fun Buffer.protoFields(): Map<Int, List<Any>> {
        val fields = mutableMapOf<Int, MutableList<Any>>()
        while (!exhausted()) {
            val tag = readVarint()
            val number = (tag ushr 3).toInt()
            if (number <= 0) throw IOException("Invalid protobuf field")
            val value = when (tag.toInt() and 7) {
                0 -> readVarint()
                1 -> { skip(8); continue }
                2 -> {
                    val length = readVarint()
                    if (length !in 0..size) throw IOException("Invalid protobuf length")
                    Buffer().also { it.write(this, length) }
                }
                5 -> { skip(4); continue }
                else -> throw IOException("Invalid protobuf encoding")
            }
            fields.getOrPut(number) { mutableListOf() }.add(value)
        }
        return fields
    }

    private fun Buffer.readVarint(): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            val byte = readByte().toInt() and 255
            if (shift == 63 && byte > 1) throw IOException("Invalid protobuf integer")
            value = value or ((byte and 127).toLong() shl shift)
            if (byte < 128) return value
        }
        throw IOException("Invalid protobuf integer")
    }

    private fun Map<Int, List<Any>>.number(field: Int) = get(field)?.firstOrNull() as? Long ?: 0L
    private fun Map<Int, List<Any>>.string(field: Int) =
        (get(field)?.firstOrNull() as? Buffer)?.clone()?.readUtf8().orEmpty()
    private fun Map<Int, List<Any>>.message(field: Int) =
        (get(field)?.firstOrNull() as? Buffer)?.clone()?.protoFields()

    companion object {
        private const val MAX_ACTIVE = 4096
        private const val MAX_FRAME = 4 * 1024 * 1024
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

        fun subscribe(port: Int, secret: String) = callbackFlow {
            require(port in 1..65535 && secret.isNotBlank())
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/daemon.StartedService/SubscribeConnections")
                .header("Authorization", "Bearer $secret")
                .header("X-Grpc-Web", "1")
                // The request uses the daemon's default traffic update interval of one second.
                .post(ByteArray(5).toRequestBody("application/grpc-web+proto".toMediaType()))
                .build()
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { close(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Connection feed HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Missing connection feed")
                            val feed = ConnectionFeed()
                            while (!call.isCanceled() && !source.exhausted()) {
                                val flags = source.readByte().toInt() and 255
                                val size = source.readInt()
                                if (size !in 0..MAX_FRAME) throw IOException("Connection frame too large")
                                val frame = Buffer()
                                source.readFully(frame, size.toLong())
                                if (flags == 128) break
                                if (flags != 0) throw IOException("Unsupported connection frame")
                                trySend(feed.accept(frame))
                            }
                        }
                        close(IOException("Connection feed ended"))
                    } catch (failure: Exception) {
                        close(failure)
                    }
                }
            })
            awaitClose { call.cancel() }
        }
    }
}
