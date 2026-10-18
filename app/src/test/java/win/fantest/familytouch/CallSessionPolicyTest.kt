package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class CallSessionPolicyTest {
    @Test fun capsCallsAtFourAndRejectsForeignSessionSignals() {
        assertTrue(CallSessionPolicy.validParticipants(setOf("a", "b", "c", "d"), "a"))
        assertFalse(CallSessionPolicy.validParticipants(setOf("a", "b", "c", "d", "e"), "a"))
        assertFalse(CallSessionPolicy.validParticipants(setOf("b", "c"), "a"))
        assertTrue(CallSessionPolicy.acceptsSignal("s", "s", setOf("a", "b"), "b"))
        assertFalse(CallSessionPolicy.acceptsSignal("s", "old", setOf("a", "b"), "b"))
        assertFalse(CallSessionPolicy.acceptsSignal("s", "s", setOf("a", "b"), "stranger"))
    }
}
