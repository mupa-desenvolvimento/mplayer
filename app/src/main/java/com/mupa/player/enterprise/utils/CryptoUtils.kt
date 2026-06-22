package com.mupa.player.enterprise.utils

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val SECRET_SEED = "mupa_player_enterprise_secure_seed_2026"

    private val keySpec: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(SECRET_SEED.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    private val ivSpec: IvParameterSpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(SECRET_SEED.toByteArray(Charsets.UTF_8))
        IvParameterSpec(keyBytes.copyOfRange(0, 16))
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return plainText
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            plainText
        }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isBlank()) return cipherText
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decodedBytes = Base64.decode(cipherText, Base64.NO_WRAP)
            String(cipher.doFinal(decodedBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            cipherText
        }
    }
}
