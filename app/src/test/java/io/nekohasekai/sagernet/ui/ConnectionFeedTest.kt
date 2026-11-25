package app.rawline.mobile.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        val row = Buffer().text(8, "example.org")
        val response = Buffer().message(1, Buffer().text(2, "auth-id").message(3, row))
        val frame = Buffer().writeByte(0).writeInt(response.size.toInt()).apply {
            write(response, response.size)
        }.readByteArray()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val request = FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val headers = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val lines = generateSequence { headers.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    repeat(5) { assertEquals(0, headers.read()) }
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/grpc-web+proto\r\n" +
                        "Content-Length: ${frame.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(frame)
                    output.flush()
                    lines
                }
            }
            thread(isDaemon = true) { request.run() }
            val rows = runBlocking {
                withTimeout(5_000) { ConnectionFeed.subscribe(server.localPort, "fixture-key").first() }
            }
            val headers = request.get(5, TimeUnit.SECONDS)
            assertEquals("POST /daemon.StartedService/SubscribeConnections HTTP/1.1", headers.first())
            assertTrue(headers.any { it.equals("Authorization: Bearer fixture-key", ignoreCase = true) })
            assertEquals("example.org", rows.single().destination)
        }
    }

    @Test(expected = IOException::class) fun rejectsOversizedProtobufField() {
        ConnectionFeed().accept(Buffer().writeByte(10).writeByte(30).writeByte(0))
    }
}
