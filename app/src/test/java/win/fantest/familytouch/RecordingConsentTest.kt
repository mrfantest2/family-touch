package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class RecordingConsentTest {
    private fun policy() = RecordingConsent("session", "recording", setOf("a", "b", "c"))
    @Test fun silenceAndPartialConsentNeverPermitCapture() {
        val p = policy()
        assertFalse(p.allowed)
        assertTrue(p.accept("session", "recording", "a"))
        assertTrue(p.accept("session", "recording", "b"))
        assertFalse(p.allowed)
        assertTrue(p.accept("session", "recording", "c"))
        assertTrue(p.allowed)
    }
    @Test fun forgedOrOldSessionAcknowledgmentsAreRejected() {
        val p = policy()
        assertFalse(p.accept("other", "recording", "a"))
        assertFalse(p.accept("session", "old", "a"))
        assertFalse(p.accept("session", "recording", "stranger"))
        assertFalse(p.allowed)
    }
    @Test fun withdrawalAndMembershipChangePermanentlyStopThisRequest() {
        val p = policy()
        listOf("a", "b", "c").forEach { p.accept("session", "recording", it) }
        p.withdraw("b")
        assertFalse(p.allowed)
        assertFalse(p.accept("session", "recording", "b"))
        val q = policy()
        listOf("a", "b", "c").forEach { q.accept("session", "recording", it) }
        q.membershipChanged(setOf("a", "b", "c", "d"))
        assertFalse(q.allowed)
    }
}
