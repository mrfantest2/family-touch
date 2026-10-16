package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test

class VpnConnectionStatusTest {
    @Test fun permissionOrServiceAloneDoesNotMeanConnected() {
        assertFalse(VpnConnectionStatus.fromBackend("NeedsLogin", false, null, null).connected)
    }
    @Test fun runningWithoutAndroidTunnelIsStillConnecting() {
        assertFalse(VpnConnectionStatus.fromBackend("Running", false, "100.70.1.2", "100.70.1.2").connected)
    }
    @Test fun unrelatedVpnCannotMakeEmbeddedEngineConnected() {
        assertFalse(VpnConnectionStatus.fromBackend("Running", true, "100.70.1.2", "100.70.1.3").connected)
    }
    @Test fun matchingNativeAndAndroidTunnelAddressIsConnected() {
        assertTrue(VpnConnectionStatus.fromBackend("Running", true, "100.70.1.2", "100.70.1.2").connected)
    }
    @Test fun missingEnrollmentExplainsFamilySetupWithoutClaimingConnection() {
        assertEquals("Family setup needed", VpnConnectionStatus.fromBackend("NeedsLogin", false, null, null).label)
    }
    @Test fun machineApprovalDoesNotMeanConnected() {
        assertFalse(VpnConnectionStatus.fromBackend("NeedsMachineAuth", true, "100.70.1.2", "100.70.1.2").connected)
    }
    @Test fun retainedTunnelWithoutInternetIsNotConnected() {
        val status = VpnConnectionStatus.fromBackend("Running", true, "100.70.1.2", "100.70.1.2", false)
        assertFalse(status.connected)
        assertEquals("Waiting for internet", status.label)
    }
}
