package eu.kanade.tachiyomi.extension.ar.mangapro

import android.util.Base64
import keiyoushi.utils.parseAs
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProChanSecurity {

    fun decryptImage(map: ScrambledData, key: String): ScrambledImageTokenValue? {
        return try {
            val token = map.token ?: return null
            val jsonString = decrypt(token, key)
            jsonString.parseAs<ScrambledImageTokenValue>()
        } catch (e: Exception) {
            null
        }
    }

    private fun decrypt(encryptedData: String, keyString: String): String {
        val key = SecretKeySpec(keyString.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        
        val data = Base64.decode(encryptedData, Base64.DEFAULT)
        val iv = data.sliceArray(0 until 16)
        val content = data.sliceArray(16 until data.size)
        
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return String(cipher.doFinal(content))
    }
}
