package app.betterdesk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.AlgorithmParameters
import java.util.Base64

/** Private signing keys stay behind the platform boundary. No trust state goes over the wire. */
interface DeviceIdentity {
    val publicKey: String
    fun sign(bytes: ByteArray): ByteArray
}

@Serializable
data class RememberedDevice(val key: String, val name: String)

object RememberedDevices {
    const val LIMIT = 16
    private val json = Json { ignoreUnknownKeys = false }
    fun read(value: String?): List<RememberedDevice> = runCatching {
        require(value != null && value.length <= 6000)
        json.decodeFromString<List<RememberedDevice>>(value).also { devices ->
            require(devices.size <= LIMIT && devices.map { it.key }.distinct().size == devices.size)
            devices.forEach { require(it.name.isNotBlank() && it.name.length <= 40); publicKey(it.key) }
        }
    }.getOrDefault(emptyList())
    fun nameAvailable(devices: List<RememberedDevice>, name: String) =
        name.isNotBlank() && name.length <= 40 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
            devices.none { it.name.equals(name, ignoreCase = true) }
    fun suggestName(devices: List<RememberedDevice>, name: String): String {
        if (nameAvailable(devices, name)) return name
        for (number in 2..LIMIT + 1) {
            val suffix = " ($number)"
            val candidate = name.take(40 - suffix.length) + suffix
            if (nameAvailable(devices, candidate)) return candidate
        }
        error("No device name available")
    }
    fun encode(devices: List<RememberedDevice>): String = json.encodeToString(devices)
    fun publicKey(value: String): ECPublicKey {
        require(value.length in 100..160 && value.matches(Regex("[A-Za-z0-9_-]+")))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(value))) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator && key.params.order == expected.order && key.params.cofactor == expected.cofactor)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(key.encoded) == value)
        return key
    }
    fun verify(key: String, bytes: ByteArray, proof: String): Boolean = runCatching {
        require(proof.length in 90..100 && proof.matches(Regex("[A-Za-z0-9_-]+")))
        Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey(key)); update(bytes) }.verify(Base64.getUrlDecoder().decode(proof))
    }.getOrDefault(false)
}
