package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test

class LocationPolicyTest {
    @Test fun acceptsFreshApproximateFix() {
        assertTrue(LocationPolicy.accepts(25.2, 55.3, 1500f, 100_000, 110_000))
    }
    @Test fun rejectsStaleFutureAndInvalidCoordinates() {
        assertFalse(LocationPolicy.accepts(25.2, 55.3, 10f, 1, 120_001))
        assertFalse(LocationPolicy.accepts(25.2, 55.3, 10f, 120_001, 1))
        assertFalse(LocationPolicy.accepts(Double.NaN, 55.3, 10f, 1, 2))
        assertFalse(LocationPolicy.accepts(91.0, 55.3, 10f, 1, 2))
        assertFalse(LocationPolicy.accepts(25.0, 181.0, 10f, 1, 2))
        assertFalse(LocationPolicy.accepts(25.0, 55.0, -1f, 1, 2))
    }
}
