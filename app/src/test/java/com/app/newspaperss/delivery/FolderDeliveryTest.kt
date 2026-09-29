package com.app.newspaperss.delivery

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderDeliveryTest {
    @Test
    fun fileNamesAreSafeOnEveryProvider() {
        assertEquals("Tuesday Morning Edition (2).epub", FolderDelivery.fileName("Tuesday Morning Edition (2)"))
        assertEquals("Q-A- this-that.epub", FolderDelivery.fileName("Q/A: this|that"))
    }
}
