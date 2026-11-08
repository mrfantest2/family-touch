package win.fantest.familytouch

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Direct-chat location sessions: no ambient tracking, no group-wide live shares. */
internal data class LiveLocationFix(
    val sessionId: String,
    val expiresAt: Long,
    val latitude: Double,
    val longitude: Double,
    val ended: Boolean
)

internal object LiveLocationProtocol {
    const val PREFIX = "FTLIVE1:"
    const val MINUTE_MS = 60_000L
    const val MAX_DURATION_MS = 8 * 60 * MINUTE_MS
    val allowedDurations = setOf(15 * MINUTE_MS, 60 * MINUTE_MS, MAX_DURATION_MS)

    fun validCoordinates(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun mayStart(durationMs: Long): Boolean = durationMs in allowedDurations

    fun encode(fix: LiveLocationFix): String {
        require(validCoordinates(fix.latitude, fix.longitude))
        UUID.fromString(fix.sessionId)
        require(fix.expiresAt > 0)
        return PREFIX + fix.sessionId + "|" + fix.expiresAt + "|" +
            String.format(Locale.US, "%.6f|%.6f", fix.latitude, fix.longitude) +
            "|" + (if (fix.ended) "ended" else "active")
    }

    fun parse(value: String): LiveLocationFix? = runCatching {
        if (!value.startsWith(PREFIX) || value.length > 160) return@runCatching null
        val fields = value.removePrefix(PREFIX).split('|')
        if (fields.size != 5) return@runCatching null
        val id = UUID.fromString(fields[0]).toString()
        val end = fields[1].toLong()
        val lat = fields[2].toDouble()
        val lon = fields[3].toDouble()
        if (end <= 0 || !validCoordinates(lat, lon) ||
            fields[4] !in setOf("ended", "active")) return@runCatching null
        LiveLocationFix(id, end, lat, lon, fields[4] == "ended")
    }.getOrNull()

    fun validWire(fix: LiveLocationFix, now: Long): Boolean =
        validCoordinates(fix.latitude, fix.longitude) &&
        fix.expiresAt in (now - MAX_DURATION_MS - MINUTE_MS)..(now + MAX_DURATION_MS + MINUTE_MS)

    fun remainingMs(fix: LiveLocationFix, now: Long): Long =
        if (fix.ended) 0L else (fix.expiresAt - now).coerceAtLeast(0L)

    fun payload(fix: LiveLocationFix): JSONObject =
        JSONObject().put("sessionId", fix.sessionId).put("expiresAt", fix.expiresAt)
            .put("latitude", fix.latitude).put("longitude", fix.longitude)
            .put("status", if (fix.ended) "ended" else "active")

    fun fromWire(json: JSONObject, now: Long): LiveLocationFix {
        val sessionId = UUID.fromString(json.getString("sessionId")).toString()
        val end = json.getLong("expiresAt")
        val latitude = json.getDouble("latitude")
        val longitude = json.getDouble("longitude")
        val status = json.getString("status")
        require(status == "active" || status == "ended") { "Invalid location session status" }
        val fix = LiveLocationFix(sessionId, end, latitude, longitude, status == "ended")
        require(validWire(fix, now)) { "Invalid or stale live location" }
        return fix
    }
}
