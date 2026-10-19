package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test

class ExternalVpnPolicyTest {
    @Test fun requiresInstalledClientAndPrivateVpnAddress() {
        assertFalse(ExternalVpnPolicy.status(false, "100.100.1.2").connected)
        assertFalse(ExternalVpnPolicy.status(true, null).connected)
        assertFalse(ExternalVpnPolicy.status(true, "192.168.1.10").connected)
        assertFalse(ExternalVpnPolicy.status(true, "100.128.1.1").connected)
        assertTrue(ExternalVpnPolicy.status(true, "100.64.0.1").connected)
        assertEquals("100.127.255.254", ExternalVpnPolicy.status(true, "100.127.255.254").address)
    }
}
