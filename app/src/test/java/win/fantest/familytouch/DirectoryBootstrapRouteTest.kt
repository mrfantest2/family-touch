package win.fantest.familytouch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryBootstrapRouteTest {
    @Test fun externalVpnWithoutEmbeddedServiceUsesDefaultHttpsRoute() {
        assertFalse(DirectoryBootstrapRoute.usePhysicalUnderlay(false, false))
    }

    @Test fun embeddedVpnServiceUsesPhysicalUnderlayForAuthenticationBootstrap() {
        assertTrue(DirectoryBootstrapRoute.usePhysicalUnderlay(true, false))
    }

    @Test fun stoppingEmbeddedServiceKeepsPhysicalUnderlayUntilShutdown() {
        assertTrue(DirectoryBootstrapRoute.usePhysicalUnderlay(false, true))
    }
}
