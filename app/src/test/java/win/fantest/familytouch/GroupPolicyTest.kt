package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test

class GroupPolicyTest {
    private val original = GroupMembership("owner", setOf("owner", "a", "b"), 1)
    @Test fun ownerControlsMembershipAndRevisionMustAdvance() {
        assertTrue(original.canUpdate("owner", "owner", setOf("owner", "a"), 2))
        assertFalse(original.canUpdate("a", "owner", setOf("owner", "a"), 2))
        assertFalse(original.canUpdate("owner", "owner", setOf("owner", "a"), 1))
        assertTrue(original.canUpdate("owner", "owner", setOf("owner", "a"), 3))
        assertFalse(original.canUpdate("owner", "a", setOf("owner", "a"), 2))
    }
    @Test fun onlyCurrentMembersCanSendAndDepartedMembersCannot() {
        assertTrue(original.canSend("a", 1))
        assertFalse(original.canSend("stranger", 1))
        assertFalse(original.canSend("a", 0))
        assertFalse(GroupMembership("owner", setOf("owner", "b"), 2).canSend("a", 2))
    }
    @Test fun creatorDeletionIsRevisionedAndRevokesFormerMembers() {
        // The delete operation propagates an owner-only snapshot to former members.
        val dissolved = GroupMembership("owner", setOf("owner"), original.revision + 1)
        assertTrue(original.canUpdate("owner", dissolved.owner, dissolved.members, dissolved.revision))
        assertFalse(original.canUpdate("a", dissolved.owner, dissolved.members, dissolved.revision))
        assertFalse(dissolved.canSend("a", dissolved.revision))
        assertFalse(dissolved.canSend("b", dissolved.revision))
        assertFalse(dissolved.canUpdate("a", "owner", original.members, dissolved.revision + 1))
        assertFalse(dissolved.canUpdate("owner", "owner", original.members, original.revision))
    }
    @Test fun invalidMembershipAndExcessiveGroupRejected() {
        assertFalse(original.canUpdate("owner", "owner", setOf("a"), 2))
        assertFalse(original.canUpdate("owner", "owner", (1..33).map { "m$it" }.toSet() + "owner", 2))
    }
}
