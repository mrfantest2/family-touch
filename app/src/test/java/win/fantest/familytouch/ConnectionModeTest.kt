package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class ConnectionModeTest {
    @Test fun defaultsToEmbeddedAndRemembersExplicitExternalChoice() {
        assertEquals(ConnectionMode.BUILT_IN, ConnectionMode.restore(null, true))
        assertEquals(ConnectionMode.BUILT_IN, ConnectionMode.restore("invalid", true))
        assertEquals(ConnectionMode.EXTERNAL, ConnectionMode.restore("external", true))
        assertEquals(ConnectionMode.EXTERNAL, ConnectionMode.restore(null, false))
    }
}
