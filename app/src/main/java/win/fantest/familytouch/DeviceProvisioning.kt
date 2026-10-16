package win.fantest.familytouch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher

/** Only the intended installation can decrypt a provisioning envelope. No auth key is persisted. */
class DeviceProvisioning(private val context: Context) {
    private val alias = "family-touch-provisioning-v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized private fun publicKey(): java.security.PublicKey {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(3072).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA1).build())
            }.generateKeyPair()
        }
        return store.getCertificate(alias).publicKey
    }
    fun deviceId(): String = MessageDigest.getInstance("SHA-256").digest(publicKey().encoded)
        .joinToString("") { "%02x".format(it) }
    fun request(): String = JSONObject().put("v", 1).put("device", deviceId())
        .put("publicKey", Base64.encodeToString(publicKey().encoded, Base64.NO_WRAP)).toString()
    fun decrypt(envelope: ByteArray): String {
        require(envelope.size <= 4096) { "Provisioning file too large" }
        publicKey()
        val encrypted = Base64.decode(envelope, Base64.DEFAULT)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, store.getKey(alias, null))
        val clear = cipher.doFinal(encrypted)
        try {
            val json = JSONObject(String(clear, Charsets.UTF_8))
            require(ProvisioningPolicy.accepts(deviceId(), json.getString("device"), json.getLong("expires"), System.currentTimeMillis()/1000)) {
                "Provisioning expired or belongs to another installation"
            }
            val key = json.getString("key")
            require(key.startsWith("tskey-auth-") && key.length in 30..150) { "Invalid device auth key" }
            return key
        } finally { clear.fill(0) }
    }
}
