package pl.sinusoide.sinuschat.link

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkProximityTest {

    @Test
    fun bothSidesDeriveTheSameToken() {
        val phone = LinkIdentity.generate()
        val pc = LinkIdentity.generate()
        val phoneKey = LinkProximity.key(phone, pc.publicBytes)
        val pcKey = LinkProximity.key(pc, phone.publicBytes)
        assertContentEquals(phoneKey, pcKey)

        val now = 1_800_000_000_000L
        val payload = LinkProximity.payload(phoneKey, now)
        assertEquals(LinkProximity.PAYLOAD_BYTES, payload.size)
        assertTrue(LinkProximity.matches(pcKey, payload, now))
        assertTrue(LinkProximity.matches(pcKey, payload, now + LinkProximity.SLOT_MS))
        assertFalse(LinkProximity.matches(pcKey, payload, now + 3 * LinkProximity.SLOT_MS))
    }

    @Test
    fun strangersAndRotationDoNotMatch() {
        val phone = LinkIdentity.generate()
        val pc = LinkIdentity.generate()
        val other = LinkIdentity.generate()
        val key = LinkProximity.key(phone, pc.publicBytes)
        val now = 1_800_000_000_000L
        val payload = LinkProximity.payload(key, now)
        assertFalse(LinkProximity.matches(LinkProximity.key(other, phone.publicBytes), payload, now))
        assertFalse(LinkProximity.payload(key, now).contentEquals(LinkProximity.payload(key, now + 2 * LinkProximity.SLOT_MS)))
        assertFalse(LinkProximity.matches(key, payload.copyOf(5), now))
        val tampered = payload.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertFalse(LinkProximity.matches(key, tampered, now))
    }
}
