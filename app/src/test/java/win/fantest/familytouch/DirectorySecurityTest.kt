package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test

class DirectorySecurityTest {
    @Test fun refusesMalformedOrNonPrivateAddresses() {
        listOf("100.x.70.1.2", "100.70.1.2.3", "100.063.1.2", "192.168.1.2", "100.128.1.2", "100.70.1.999").forEach {
            assertFalse(it, DirectorySecurity.isPrivateIp(it))
        }
        assertTrue(DirectorySecurity.isPrivateIp("100.70.1.2"))
    }
    @Test fun signatureBindsTheBodyPathAndNonce() {
        val a = DirectorySecurity.canonical("POST", "/api/v1/enroll", "device", "123", "nonce", "{}".toByteArray())
        assertNotEquals(a, DirectorySecurity.canonical("POST", "/api/v1/sync", "device", "123", "nonce", "{}".toByteArray()))
        assertNotEquals(a, DirectorySecurity.canonical("POST", "/api/v1/enroll", "device", "123", "other", "{}".toByteArray()))
        assertNotEquals(a, DirectorySecurity.canonical("POST", "/api/v1/enroll", "device", "123", "nonce", "{\"x\":1}".toByteArray()))
        assertEquals("POST\n/api/v1/enroll\ndevice\n123\nnonce\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", a)
    }
    @Test fun probeChallengesCannotBeArbitrarySignedMessages() {
        assertTrue(DirectorySecurity.validChallenge("a".repeat(64)))
        assertFalse(DirectorySecurity.validChallenge("POST\n/api/v1/sync"))
        assertFalse(DirectorySecurity.validChallenge("a".repeat(63)))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsRosterWithDuplicateDeviceIdentity() {
        val peer = Peer("123456789012345678901234567890123456", "Family", "100.70.1.2", "a".repeat(64))
        DirectorySecurity.validateRoster(listOf(peer, peer), "self")
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsRosterWithWeakPeerCredentials() {
        DirectorySecurity.validateRoster(listOf(Peer("123456789012345678901234567890123456", "Family", "100.70.1.2", "guess")), "self")
    }
}
