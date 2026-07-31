package com.songnotes.core.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject

/**
 * Key derivation, matching the desktop web app's `src/crypto/kdf.js` port-for-port:
 * Argon2id (m=64 MiB, t=3, p=1) is the WRITER path for new wraps, PBKDF2-HMAC-SHA256
 * at 600,000 iterations is a READER-only path for envelopes that predate Argon2id.
 * See kdf.js's own doc comment for the "why Argon2id" reasoning -- unchanged here.
 */
sealed class KdfParams {
    abstract val salt: ByteArray

    data class Argon2id(
        override val salt: ByteArray,
        val memorySizeKiB: Int = DEFAULT_MEMORY_KIB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = DEFAULT_PARALLELISM,
        val hashLength: Int = DEFAULT_HASH_LENGTH,
    ) : KdfParams() {
        companion object {
            const val DEFAULT_MEMORY_KIB = 65536 // 64 MiB
            const val DEFAULT_ITERATIONS = 3
            const val DEFAULT_PARALLELISM = 1
            const val DEFAULT_HASH_LENGTH = 32 // bytes -- AES-256 key material
        }
    }

    data class Pbkdf2(
        override val salt: ByteArray,
        val iterations: Int = 600_000,
        val hash: String = "SHA-256",
    ) : KdfParams()

    /** True if these params are below the current writer policy (i.e. still PBKDF2). */
    fun isBelowCurrentPolicy(): Boolean = this is Pbkdf2

    fun toJson(): JSONObject = when (this) {
        is Argon2id -> JSONObject()
            .put("name", "Argon2id")
            .put("memorySize", memorySizeKiB)
            .put("iterations", iterations)
            .put("parallelism", parallelism)
            .put("hashLength", hashLength)
            .put("salt", Base64.getEncoder().encodeToString(salt))
        is Pbkdf2 -> JSONObject()
            .put("name", "PBKDF2")
            .put("hash", hash)
            .put("iterations", iterations)
            .put("salt", Base64.getEncoder().encodeToString(salt))
    }

    companion object {
        fun fromJson(json: JSONObject): KdfParams {
            val salt = Base64.getDecoder().decode(json.getString("salt"))
            return when (json.getString("name")) {
                "PBKDF2" -> Pbkdf2(salt = salt, iterations = json.getInt("iterations"), hash = json.getString("hash"))
                "Argon2id" -> Argon2id(
                    salt = salt,
                    memorySizeKiB = json.getInt("memorySize"),
                    iterations = json.getInt("iterations"),
                    parallelism = json.getInt("parallelism"),
                    hashLength = json.getInt("hashLength"),
                )
                else -> error("Unknown KDF name: ${json.getString("name")}")
            }
        }

        fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}

/** Derives a raw AES-256 KEK from a passphrase, dispatching on the params' concrete type. */
fun deriveKEK(passphrase: String, params: KdfParams): ByteArray = when (params) {
    is KdfParams.Argon2id -> deriveKekArgon2id(passphrase, params)
    is KdfParams.Pbkdf2 -> deriveKekPbkdf2(passphrase, params)
}

private fun deriveKekArgon2id(passphrase: String, params: KdfParams.Argon2id): ByteArray {
    val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(params.iterations)
        .withMemoryAsKB(params.memorySizeKiB)
        .withParallelism(params.parallelism)
        .withSalt(params.salt)
    val generator = Argon2BytesGenerator().apply { init(builder.build()) }
    val out = ByteArray(params.hashLength)
    generator.generateBytes(passphrase.toByteArray(Charsets.UTF_8), out)
    return out
}

private fun deriveKekPbkdf2(passphrase: String, params: KdfParams.Pbkdf2): ByteArray {
    require(params.hash == "SHA-256") { "Only SHA-256 PBKDF2 is supported (matches kdf.js's default)" }
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(passphrase.toCharArray(), params.salt, params.iterations, 256)
    return factory.generateSecret(spec).encoded
}
