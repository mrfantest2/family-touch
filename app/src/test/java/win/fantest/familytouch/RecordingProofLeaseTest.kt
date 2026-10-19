package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class RecordingProofLeaseTest {
    @Test fun everyParticipantMustRenewAndExpiredConsentStopsCapture() {
        val lease = RecordingProofLease(setOf("a", "b"), 15000)
        lease.renew("a", true, 1000)
        assertFalse(lease.valid(1001))
        lease.renew("b", true, 2000)
        assertTrue(lease.valid(2001))
        lease.renew("b", true, 16001)
        assertFalse(lease.valid(16001))
        lease.renew("a", true, 16002)
        assertTrue(lease.valid(16002))
    }
    @Test fun withdrawalCannotBeUndoneByLateProofsAndUnknownPeersCannotRenew() {
        val lease = RecordingProofLease(setOf("a"), 15000)
        lease.renew("stranger", true, 1000)
        assertFalse(lease.valid(1000))
        lease.renew("a", true, 1000)
        assertTrue(lease.valid(1000))
        lease.renew("a", false, 1100)
        lease.renew("a", true, 1200)
        assertFalse(lease.valid(1200))
    }
}
