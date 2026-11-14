package app.rawline.mobile.group

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnounceLineBreaksTest {
    @Test fun aWrittenLineBreakBecomesOne() {
        assertEquals("first\nsecond\nthird", SubscriptionMetadata.lineBreaks("first\\nsecond\nthird"))
    }
}
