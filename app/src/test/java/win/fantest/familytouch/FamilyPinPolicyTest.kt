package win.fantest.familytouch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyPinPolicyTest {
    @Test fun pinRequiresSixAsciiDigits() {
        assertTrue(FamilyPin.valid("123456"))
        assertTrue(FamilyPin.valid("000000"))
        assertFalse(FamilyPin.valid("12345"))
        assertFalse(FamilyPin.valid("1234567"))
        assertFalse(FamilyPin.valid("12345a"))
        assertFalse(FamilyPin.valid("１２３４５６"))
        assertFalse(FamilyPin.valid(""))
    }
}
