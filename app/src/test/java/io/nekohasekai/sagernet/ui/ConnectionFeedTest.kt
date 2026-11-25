package app.rawline.mobile.ui

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class ConnectionFeedTest {
    private fun Buffer.varint(value: Int): Buffer {
        var remaining = value
        while (remaining >= 128) {
            writeByte((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        return writeByte(remaining)
    }

    private fun Buffer.message(tag: Int, body: Buffer): Buffer =
        varint((tag shl 3) or 2).varint(body.size.toInt()).apply { write(body, body.size) }

    private fun Buffer.text(tag: Int, text: String) = message(tag, Buffer().writeUtf8(text))

    @Test fun appliesNewTrafficAndClosedEvents() {
        val feed = ConnectionFeed()
        val row = Buffer().text(8, "example.org").text(9, "tls")
            .message(22, Buffer().text(5, "app.example"))
        val opened = Buffer().message(1, Buffer().text(2, "id").message(3, row))
        assertEquals("app.example", feed.accept(opened).single().process)
        val update = Buffer().text(2, "id").writeByte(8).writeByte(1).writeByte(32).writeByte(12)
        val changed = feed.accept(Buffer().message(1, update)).single()
        assertEquals(12L, changed.upload)
        assertEquals("example.org", changed.destination)
        val closed = Buffer().text(2, "id").writeByte(8).writeByte(2)
        assertTrue(feed.accept(Buffer().message(1, closed)).isEmpty())
    }

    @Test fun readsAStreamFromTheAuthenticatedLoopbackEndpoint() {
        val seenAuth = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/daemon.StartedService/SubscribeConnections") { exchange ->
            seenAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            val row = Buffer().text(8, "example.org")
            val response = Buffer().message(1, Buffer().text(2, "auth-id").message(3, row))
            val frame = Buffer().writeByte(0).writeInt(response.size.toInt()).apply {
                write(response, response.size)
            }.readByteArray()
            exchange.requestBody.close()
            exchange.responseHeaders.set("Content-Type", "application/grpc-web+proto")
            exchange.sendResponseHeaders(200, frame.size.toLong())
            exchange.responseBody.use { it.write(frame) }
        }
        server.start()
        try {
            val rows = runBlocking {
                withTimeout(5_000) { ConnectionFeed.subscribe(server.address.port, "fixture-key").first() }
            }
            assertEquals("Bearer fixture-key", seenAuth.get())
            assertEquals("example.org", rows.single().destination)
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = IOException::class) fun rejectsOversizedProtobufField() {
        ConnectionFeed().accept(Buffer().writeByte(10).writeByte(30).writeByte(0))
    }
}
