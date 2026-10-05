package com.app.newspaperss.ui.edition

import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.ui.edition.MoreAction.MARK_NOT_SENT
import com.app.newspaperss.ui.edition.MoreAction.OPEN_HERE
import com.app.newspaperss.ui.edition.MoreAction.OPEN_KINDLE_APP
import com.app.newspaperss.ui.edition.MoreAction.SEND
import com.app.newspaperss.ui.edition.MoreAction.SEND_AGAIN
import com.app.newspaperss.ui.edition.MoreAction.SEND_ANOTHER_WAY
import org.junit.Assert.assertEquals
import org.junit.Test

class EditionChoicesTest {
    private enum class Reader(val preferOpen: Boolean, val offerOpen: Boolean, val kindle: Boolean) {
        KINDLE(false, false, true), KOBO(false, false, false), BOOX(true, true, false), POCKETBOOK(false, true, false)
    }

    private fun choices(
        status: EditionStatus,
        reader: Reader,
        emailsKindle: Boolean = false,
        kindleApp: Boolean = true,
        hasFile: Boolean = true,
    ) = editionChoices(status, reader.preferOpen, reader.offerOpen, reader.kindle, emailsKindle, kindleApp, hasFile)

    @Test
    fun aReadyEditionHasOneButtonAndOpeningHereOnlyWhereThatMakesSense() {
        assertEquals(EditionChoices(NextStep.SEND, emptyList()), choices(EditionStatus.READY, Reader.KOBO))
        assertEquals(EditionChoices(NextStep.SEND, listOf(OPEN_HERE)), choices(EditionStatus.READY, Reader.POCKETBOOK))
        assertEquals(EditionChoices(NextStep.READ_NOW, listOf(SEND)), choices(EditionStatus.READY, Reader.BOOX))
    }

    @Test
    fun emailToAKindleOffersTheShareSheetAsAnotherWay() {
        assertEquals(EditionChoices(NextStep.SEND, listOf(SEND_ANOTHER_WAY)), choices(EditionStatus.READY, Reader.KINDLE, emailsKindle = true))
    }

    @Test
    fun theKindleAppIsAlwaysInTheMenuNeverAButton() {
        assertEquals(EditionChoices(null, listOf(SEND_AGAIN, MARK_NOT_SENT, OPEN_KINDLE_APP)), choices(EditionStatus.DELIVERED, Reader.KINDLE))
        assertEquals(EditionChoices(null, listOf(SEND_AGAIN, MARK_NOT_SENT)), choices(EditionStatus.DELIVERED, Reader.KINDLE, kindleApp = false))
    }

    @Test
    fun aSentEditionIsReadHereOnABooxAndHasNoButtonElsewhere() {
        assertEquals(EditionChoices(NextStep.READ, listOf(SEND_AGAIN, MARK_NOT_SENT)), choices(EditionStatus.DELIVERED, Reader.BOOX))
        assertEquals(EditionChoices(null, listOf(SEND_AGAIN, MARK_NOT_SENT, OPEN_HERE)), choices(EditionStatus.DELIVERED, Reader.POCKETBOOK))
        assertEquals(EditionChoices(null, listOf(SEND_AGAIN, MARK_NOT_SENT)), choices(EditionStatus.DELIVERED, Reader.KOBO))
    }

    @Test
    fun withoutItsBookOnlyTheKindleAppIsLeft() {
        assertEquals(EditionChoices(null, emptyList()), choices(EditionStatus.DELIVERED, Reader.BOOX, hasFile = false))
        assertEquals(EditionChoices(null, listOf(OPEN_KINDLE_APP)), choices(EditionStatus.DELIVERED, Reader.KINDLE, hasFile = false))
        assertEquals(emptyList<MoreAction>(), choices(EditionStatus.READY, Reader.POCKETBOOK, hasFile = false).more)
    }

    @Test
    fun aFailedOrBuildingEditionOffersNothing() {
        assertEquals(EditionChoices(null, emptyList()), choices(EditionStatus.FAILED, Reader.KINDLE))
        assertEquals(EditionChoices(null, emptyList()), choices(EditionStatus.BUILDING, Reader.BOOX))
    }
}
