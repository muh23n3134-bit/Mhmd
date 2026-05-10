package eu.kanade.tachiyomi.extension.ar.mangapro

import keiyoushi.utils.parseAs
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

object ProChanSecurity {

    fun decodeScrambledImageToken(data: ScrambledImageToken, cid: Int): ScrambledImage {
        val value = String(urlSafeBase64(data.token), Charsets.UTF_8)
            .parseAs<ScrambledImageTokenValue>()

        val iv = urlSafeBase64(value.iv)
        val tag = urlSafeBase64(value.tag)
        val encryptedData = urlSafeBase64(value.data)

        val key = when (value.m) {
            "browser" -> {
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(
                        "prochan-browser-map:2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2:$cid"
                            .toByteArray(Charsets.UTF_8),
                    )
                SecretKeySpec(hash, "AES")
            }
            else -> throw Exception("Unknown encryption method")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            val spec = GCMParameterSpec(128, iv)
            init(Cipher.DECRYPT_MODE, key, spec)
        }

        val decryptedBytes = cipher.doFinal(encryptedData + tag)
        return String(decryptedBytes, Charsets.UTF_8).parseAs()
    }

    fun urlSafeBase64(data: String): ByteArray {
        return Base64.UrlSafe
            .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            .decode(data)
    }

    fun getSignaturePayload(imgUrl: String): String {
        return "{\"url\":\"$imgUrl\"}"
    }
}
