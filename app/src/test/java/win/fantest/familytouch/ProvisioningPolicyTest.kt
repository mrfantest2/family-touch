package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class ProvisioningPolicyTest {
 @Test fun expiredCredentialRejected() { assertFalse(ProvisioningPolicy.accepts("device-a", "device-a", 100, 101)) }
 @Test fun differentDeviceRejected() { assertFalse(ProvisioningPolicy.accepts("device-a", "device-b", 200, 100)) }
 @Test fun longLivedCredentialRejected() { assertFalse(ProvisioningPolicy.accepts("a", "a", 100 + 3601, 100)) }
 @Test fun validDeviceBoundCredentialAccepted() { assertTrue(ProvisioningPolicy.accepts("a", "a", 200, 100)) }
}
