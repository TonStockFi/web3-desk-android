package com.example.myapplication
import com.web3desk.adr.DESCrypto
import com.web3desk.adr.padKeyTo8Bytes
import com.web3desk.adr.stringToHex
import org.junit.Assert.*
import org.junit.Test

class DESCryptoTest {

    @Test
    fun testEncryptDecrypt() {
        // Example key (must be a hex string, adjust length for DES key requirements)
        val key = "w1s2sa"
        // Create DESCrypto instance
        val desCrypto = DESCrypto(stringToHex(padKeyTo8Bytes(key)))
        // Plaintext to encrypt
        val plaintext = "Hello, World!"
        // Encrypt the plaintext
        val encrypted = desCrypto.encrypt(plaintext)
        //e3Z7a6M5sIojvOseqosnCA==
        println("Encrypted: $encrypted")
        // Decrypt the ciphertext
        val decrypted = desCrypto.decrypt(encrypted)
        println("Decrypted: $decrypted")

        // Assert that the decrypted value matches the original plaintext
        assertEquals(plaintext, decrypted)
    }
}
