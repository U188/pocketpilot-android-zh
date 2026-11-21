package com.example.security

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts PocketPilot server profile backups using:
 * - Key derivation: PBKDF2WithHmacSHA256 (120,000 iterations, 16-byte random salt, 256-bit key)
 * - Authenticated encryption: AES-256-GCM (12-byte random IV, 128-bit auth tag)
 *
 * Plaintext secrets are never written outside the encrypted ciphertext payload.
 */
object ProfileBackupCrypto {

    private const val FORMAT_ID = "pocketpilot-backup"
    private const val FORMAT_VERSION = 1
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    fun encrypt(plaintextJson: String, password: String): String {
        require(password.length >= 4) { "文件密码至少需要 4 个字符" }

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val keyBytes = deriveKey(password, salt, PBKDF2_ITERATIONS)
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintextBytes = plaintextJson.toByteArray(StandardCharsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintextBytes)

            val encoder = Base64.getEncoder()
            return JSONObject().apply {
                put("format", FORMAT_ID)
                put("version", FORMAT_VERSION)
                put("kdf", KDF_ALGORITHM)
                put("iterations", PBKDF2_ITERATIONS)
                put("cipher", CIPHER_TRANSFORMATION)
                put("salt", encoder.encodeToString(salt))
                put("iv", encoder.encodeToString(iv))
                put("ciphertext", encoder.encodeToString(ciphertext))
            }.toString(2) + "\n"
        } finally {
            keyBytes.fill(0)
        }
    }

    fun decrypt(encryptedEnvelopeJson: String, password: String): String {
        require(password.isNotEmpty()) { "请输入密码以解密文件" }

        val envelope = runCatching { JSONObject(encryptedEnvelopeJson.trim()) }.getOrElse {
            throw IllegalArgumentException("备份文件格式无效")
        }
        val format = envelope.optString("format")
        val version = envelope.optInt("version", -1)
        if (format != FORMAT_ID || version != FORMAT_VERSION) {
            throw IllegalArgumentException("不支持的备份文件格式")
        }

        val iterations = envelope.optInt("iterations", PBKDF2_ITERATIONS).coerceIn(10_000, 1_000_000)
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(envelope.getString("salt")) }.getOrElse {
            throw IllegalArgumentException("加密参数（salt）已损坏")
        }
        val iv = runCatching { decoder.decode(envelope.getString("iv")) }.getOrElse {
            throw IllegalArgumentException("加密参数（iv）已损坏")
        }
        val ciphertext = runCatching { decoder.decode(envelope.getString("ciphertext")) }.getOrElse {
            throw IllegalArgumentException("加密数据已损坏")
        }

        val keyBytes = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainBytes = cipher.doFinal(ciphertext)
            return String(plainBytes, StandardCharsets.UTF_8)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("密码错误或备份文件已损坏")
        } catch (t: Throwable) {
            throw IllegalArgumentException("无法解密文件: ${t.message ?: "请检查密码"}")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
