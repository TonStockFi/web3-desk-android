package com.web3desk.adr
import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class DESCrypto(key: String) {

    @SuppressLint("GetInstance")
    private val cipher: Cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
    private val keySpec = SecretKeySpec(hexStringToByteArray(key), "DES")

    // Encrypt plaintext to ciphertext
    fun encrypt(plaintext: String): String {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    // Decrypt ciphertext to plaintext
    fun decrypt(ciphertext: String): String {
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        val decryptedBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return String(decryptedBytes, Charsets.UTF_8)
    }

    // Helper function to convert hex string to byte array
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((s[i].digitToInt(16) shl 4) + s[i + 1].digitToInt(16)).toByte()
        }
        return data
    }
}
