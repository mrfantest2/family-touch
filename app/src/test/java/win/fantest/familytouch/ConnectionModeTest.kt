package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class ConnectionModeTest {
    @Test fun embeddedAlwaysMigratesLegacyExternalToBuiltIn() {
        assertEquals(ConnectionMode.BUILT_IN, ConnectionMode.restore(null, true))
        assertEquals(ConnectionMode.BUILT_IN, ConnectionMode.restore("invalid", true))
        assertEquals(ConnectionMode.BUILT_IN, ConnectionMode.restore("external", true))
        assertEquals(ConnectionMode.EXTERNAL, ConnectionMode.restore(null, false))
    }
}
