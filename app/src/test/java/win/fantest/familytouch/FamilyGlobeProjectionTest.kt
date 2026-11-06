package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test

class FamilyGlobeProjectionTest {
    @Test fun centerProjectsAtOrigin() {
        val p=FamilyGlobeProjection.project(0.0,0.0,0.0,0.0)!!
        assertEquals(0.0,p.x,1e-9);assertEquals(0.0,p.y,1e-9)
    }
    @Test fun backAndHorizonHidden() {
        assertNull(FamilyGlobeProjection.project(0.0,180.0,0.0,0.0))
        assertNull(FamilyGlobeProjection.project(0.0,90.0,0.0,0.0))
    }
    @Test fun datelineWrap() {
        val p=FamilyGlobeProjection.project(0.0,-170.0,0.0,190.0)
        assertNotNull(p);assertEquals(0.0,p!!.x,1e-8)
    }
    @Test fun badInputHidden() {
        assertNull(FamilyGlobeProjection.project(99.0,0.0,0.0,0.0))
        assertNull(FamilyGlobeProjection.project(Double.NaN,0.0,0.0,0.0))
    }
}
