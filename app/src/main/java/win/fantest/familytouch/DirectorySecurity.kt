package win.fantest.familytouch
internal object DirectorySecurity {
    fun isPrivateIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
        val n = parts.map { it.toInt() }
        return n.all { it in 0..255 } && n[0] == 100 && n[1] in 64..127
    }
    fun canonical(method: String, path: String, device: String, time: String, nonce: String, body: ByteArray) =
        listOf(method, path, device, time, nonce, hex(java.security.MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun validChallenge(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    fun validateRoster(peers: List<Peer>, self: String): List<Peer> {
        require(peers.size <= 64)
        require(peers.map { it.id }.distinct().size == peers.size)
        require(peers.map { it.ip }.distinct().size == peers.size)
        peers.forEach {
            require(it.id != self && it.id.matches(Regex("[A-Za-z0-9-]{20,80}")))
            require(it.name.isNotBlank() && it.name.length <= 36 && isPrivateIp(it.ip))
            require(validChallenge(it.secret))
        }
        return peers
    }
}
