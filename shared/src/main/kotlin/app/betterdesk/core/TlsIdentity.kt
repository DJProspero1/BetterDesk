package app.betterdesk.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TlsIdentity(val context: SSLContext, val fingerprint: String) {
    companion object {
        fun create(): TlsIdentity {
            val provider = BouncyCastleProvider()
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val name = X500Name("CN=BetterDesk temporary session")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()), Date(now - 60_000), Date(now + 24 * 60 * 60_000), name, pair.public)
            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(pair.private)
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
            val password = Invitation.secret().toCharArray()
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null); setKeyEntry("session", pair.private, password, arrayOf(cert))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
            password.fill('\u0000')
            return TlsIdentity(context, digest(cert))
        }

        fun client(expected: String): SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client certificates not used")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || !constantEquals(digest(chain[0]), expected)) throw java.security.cert.CertificateException("Invitation does not match this device")
                    chain[0].checkValidity()
                }
            }), SecureRandom())
        }

        fun digest(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun constantEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
