package app.betterdesk.desktop

import app.betterdesk.core.DeviceIdentity
import com.sun.jna.platform.win32.Crypt32Util
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.prefs.Preferences

/** Windows DPAPI protects private identity and local grants for the current OS user. */
internal class DesktopDeviceIdentity(private val preferences: Preferences) : DeviceIdentity {
    private val pair = run {
        check(System.getProperty("os.name").startsWith("Windows"))
        val existing = preferences.get("deviceIdentity", null)
        if (existing != null) {
            val parts = unprotect(existing).split(":")
            require(parts.size == 2)
            val factory = KeyFactory.getInstance("EC")
            java.security.KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(parts[0]))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[1]))))
        } else {
            KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().also {
                val encoded = Base64.getEncoder().encodeToString(it.public.encoded) + ":" + Base64.getEncoder().encodeToString(it.private.encoded)
                preferences.put("deviceIdentity", protect(encoded)); preferences.flush()
            }
        }
    }
    override val publicKey: String = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
    override fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(bytes); sign()
    }
    companion object {
        fun protect(value: String): String = Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8)))
        fun unprotect(value: String): String = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(value)).toString(Charsets.UTF_8)
    }
}
