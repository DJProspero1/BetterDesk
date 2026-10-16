package app.betterdesk

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.betterdesk.core.DeviceIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Per-install key; Android never exports its private bytes. Backups are disabled. */
internal class AndroidDeviceIdentity : DeviceIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
    override val publicKey: String = Base64.getUrlEncoder().withoutPadding().encodeToString(store.getCertificate(ALIAS).publicKey.encoded)
    override fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(ALIAS, null) as java.security.PrivateKey); update(bytes); sign()
    }
    companion object { private const val ALIAS = "BetterDeskDeviceV1" }
}
