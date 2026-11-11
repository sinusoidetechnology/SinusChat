package pl.sinusoide.sinuschat.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkFrameTest {

    private fun roundTrip(frame: LinkFrame): LinkFrame? = LinkFrame.decode(LinkFrame.encode(frame))

    @Test
    fun messageChangesSurviveEncoding() {
        val frames = listOf(
            LinkFrame.PinMessage("a_b", "m1", true),
            LinkFrame.PinMessage("a_b", "m1", false),
            LinkFrame.ReactMessage("a_b", "m2", "❤️‍🔥"),
            LinkFrame.ReactMessage("a_b", "m2"),
            LinkFrame.EditMessage("a_b", "m3", "nowy tekst\nz drugą linią"),
            LinkFrame.PinnedRequest("g1"),
            LinkFrame.PinnedList("g1", listOf(ChatMessage(id = "m4", conv = "g1", sender = "x", at = 5L, pinned = true)))
        )
        for (f in frames) assertEquals(f, roundTrip(f))
    }

    @Test
    fun frameNamesStayStable() {
        assertTrue(LinkFrame.encode(LinkFrame.PinMessage("c", "i", true)).contains("\"t\":\"pin_message\""))
        assertTrue(LinkFrame.encode(LinkFrame.ReactMessage("c", "i", "x")).contains("\"t\":\"react\""))
        assertTrue(LinkFrame.encode(LinkFrame.EditMessage("c", "i", "x")).contains("\"t\":\"edit_message\""))
        assertTrue(LinkFrame.encode(LinkFrame.PinnedRequest("c")).contains("\"t\":\"pinned_get\""))
        assertTrue(LinkFrame.encode(LinkFrame.PinnedList("c", emptyList())).contains("\"t\":\"pinned\""))
    }

    @Test
    fun unknownFramesAreIgnored() {
        assertNull(LinkFrame.decode("{\"t\":\"from_the_future\",\"conv\":\"c\"}"))
        assertEquals(LinkFrame.PinnedRequest("c"), LinkFrame.decode("{\"t\":\"pinned_get\",\"conv\":\"c\",\"extra\":1}"))
    }
}
