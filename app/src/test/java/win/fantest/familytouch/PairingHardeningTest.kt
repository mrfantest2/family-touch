package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test

class PairingHardeningTest {
    @Test fun secretsMatchOnlyIdenticalNonEmptyValues() {
        val token = "a".repeat(64)
        assertTrue(DirectorySecurity.secretsMatch(token, token))
        assertFalse(DirectorySecurity.secretsMatch(token, "b".repeat(64)))
        assertFalse(DirectorySecurity.secretsMatch(token.dropLast(1), token))
        assertFalse(DirectorySecurity.secretsMatch("", ""))
        assertFalse(DirectorySecurity.secretsMatch(token, ""))
    }

    @Test fun newDevicesMayPair() {
        assertTrue(PairingPolicy.mayPair(null, "100.70.1.2"))
    }

    @Test fun sameTailnetAddressMayRepairAfterALostResponse() {
        assertTrue(PairingPolicy.mayPair("100.70.1.2", "100.70.1.2"))
    }

    @Test fun anotherTailnetDeviceCannotTakeOverAnExistingContact() {
        assertFalse(PairingPolicy.mayPair("100.70.1.2", "100.70.9.9"))
    }
}
