package win.fantest.familytouch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LiveLocationProtocolTest {
    private val now = 1_800_000_000_000L

    @Test fun supportedPeriodsAreExactly15m1h8h() {
        assertTrue(LiveLocationProtocol.mayStart(15*60_000L))
        assertTrue(LiveLocationProtocol.mayStart(60*60_000L))
        assertTrue(LiveLocationProtocol.mayStart(8*60*60_000L))
        assertFalse(LiveLocationProtocol.mayStart(0))
        assertFalse(LiveLocationProtocol.mayStart(24*60*60_000L))
    }
    @Test fun coordinateAndSessionRoundtrip() {
        val id=UUID.randomUUID().toString()
        val fix=LiveLocationFix(id,now+3600_000L,25.400123,55.430456,false)
        val parsed=LiveLocationProtocol.parse(LiveLocationProtocol.encode(fix))
        assertNotNull(parsed)
        assertEquals(id,parsed?.sessionId)
        assertEquals(25.400123,parsed!!.latitude,0.000001)
        assertEquals(55.430456,parsed.longitude,0.000001)
        assertEquals(3600_000L, LiveLocationProtocol.remainingMs(parsed,now))
        assertEquals(0L,LiveLocationProtocol.remainingMs(parsed.copy(ended=true),now))
    }
    @Test fun malformedCoordinatesAndStatusRejected() {
        val id=UUID.randomUUID().toString()
        assertFalse(LiveLocationProtocol.validCoordinates(Double.NaN,23.0))
        assertFalse(LiveLocationProtocol.validCoordinates(94.0,23.0))
        assertNull(LiveLocationProtocol.parse("FTLIVE1:$id|$now|94.0|12.0|active"))
        assertNull(LiveLocationProtocol.parse("FTLIVE1:$id|$now|24.0|12.0|zombie"))
        assertNull(LiveLocationProtocol.parse("FTLIVE1:not-a-uuid|$now|24.0|12.0|active"))
        val json=JSONObject().put("sessionId",id).put("expiresAt",now+1000)
            .put("latitude",91.0).put("longitude",52.0).put("status","active")
        assertThrows(IllegalArgumentException::class.java){
            LiveLocationProtocol.fromWire(json,now)
        }
    }
    @Test fun forgedExcessiveExpiryRejected() {
        val id=UUID.randomUUID().toString()
        val json=JSONObject().put("sessionId",id)
            .put("expiresAt",now+9*60*60_000L)
            .put("latitude",24.0).put("longitude",54.0)
            .put("status","active")
        assertThrows(IllegalArgumentException::class.java) {
            LiveLocationProtocol.fromWire(json,now)
        }
    }
}
