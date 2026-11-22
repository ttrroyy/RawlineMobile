package app.rawline.mobile.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QRCodeDialogTest {
    @Test
    fun fitsMultipleLinksIncludingUnicodeNames() {
        assertTrue(QRCodeDialog.fits("socks5://example.com:1080#Швеция\nsocks5://example.org:1080#Финляндия"))
    }

    @Test
    fun offersFallbackForPayloadBeyondQrCapacity() {
        assertFalse(QRCodeDialog.fits("socks5://example.com:1080#profile\n".repeat(200)))
    }
}
