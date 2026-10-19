package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class ReactionPolicyTest {
    @Test fun reactionsAreBoundedAndCannotTargetAnotherConversation() {
        assertTrue(ReactionPolicy.valid("👍", "chat", "chat"))
        assertTrue(ReactionPolicy.valid("", "chat", "chat"))
        assertFalse(ReactionPolicy.valid("👍", "chat", "other"))
        assertFalse(ReactionPolicy.valid("arbitrary text", "chat", "chat"))
    }
}
