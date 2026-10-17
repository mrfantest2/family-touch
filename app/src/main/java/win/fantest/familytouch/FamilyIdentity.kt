package win.fantest.familytouch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal object FamilyIdentity {
    private const val alias = "family-touch-directory-v1"
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    @Synchronized private fun key(): java.security.PublicKey {
        if (!store.containsAlias(alias)) KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        return store.getCertificate(alias).publicKey
    }
    fun publicKey(): String = Base64.encodeToString(key().encoded, Base64.NO_WRAP)
    fun fingerprint(): String = DirectorySecurity.hex(MessageDigest.getInstance("SHA-256").digest(key().encoded))
    fun sign(value: String): String {
        key()
        return Base64.encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(alias, null) as java.security.PrivateKey)
            update(value.toByteArray(Charsets.UTF_8)); sign()
        }, Base64.NO_WRAP)
    }
    fun probe(challenge: String): String {
        require(DirectorySecurity.validChallenge(challenge))
        return sign("FamilyTouch probe v1\n" + challenge)
    }
}
