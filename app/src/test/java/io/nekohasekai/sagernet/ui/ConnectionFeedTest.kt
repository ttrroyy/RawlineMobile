package app.rawline.mobile.ui

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConnectionFeedTest {
    private fun Buffer.message(tag: Int, body: Buffer): Buffer =
        writeByte((tag shl 3) or 2).writeByte(body.size.toInt()).apply { write(body, body.size) }

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

    @Test(expected = IOException::class) fun rejectsOversizedProtobufField() {
        ConnectionFeed().accept(Buffer().writeByte(10).writeByte(30).writeByte(0))
    }
}
