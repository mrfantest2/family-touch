package win.fantest.familytouch

/** Each participant must independently renew consent; a withdrawal is terminal. */
internal class RecordingProofLease(private val peers: Set<String>, private val ttlMs: Long) {
    private val renewed = mutableMapOf<String, Long>()
    private var revoked = false
    @Synchronized fun renew(peer: String, accepted: Boolean, now: Long) {
        if (peer !in peers || revoked) return
        if (!accepted) revoked = true else renewed[peer] = now
    }
    @Synchronized fun revoke() { revoked = true }
    @Synchronized fun valid(now: Long): Boolean = !revoked && peers.isNotEmpty() && peers.all {
        val time = renewed[it]
        time != null && now >= time && now - time < ttlMs
    }
}
