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
        val iterations: Int = DEFAULT_ITERATIONS,
        val hash: String = DEFAULT_HASH,
    ) : KdfParams() {
        companion object {
            const val DEFAULT_ITERATIONS = 600_000
            const val DEFAULT_HASH = "SHA-256"
        }
    }

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
        /**
         * Missing numeric fields fall back to the canonical wire values rather
         * than throwing, matching kdf.js's `params.x ?? DEFAULT_KDF_PARAMS.x`.
         * Whatever comes out is then bounds-checked by [validateKdfParams] -- a
         * default is a convenience for a sparse envelope, never a bypass.
         */
        fun fromJson(json: JSONObject): KdfParams {
            val salt = Base64.getDecoder().decode(json.getString("salt"))
            val params = when (val name = json.getString("name")) {
                "PBKDF2" -> Pbkdf2(
                    salt = salt,
                    iterations = json.optInt("iterations", Pbkdf2.DEFAULT_ITERATIONS),
                    hash = json.optString("hash", Pbkdf2.DEFAULT_HASH),
                )
                "Argon2id" -> Argon2id(
                    salt = salt,
                    memorySizeKiB = json.optInt("memorySize", Argon2id.DEFAULT_MEMORY_KIB),
                    iterations = json.optInt("iterations", Argon2id.DEFAULT_ITERATIONS),
                    parallelism = json.optInt("parallelism", Argon2id.DEFAULT_PARALLELISM),
                    hashLength = json.optInt("hashLength", Argon2id.DEFAULT_HASH_LENGTH),
                )
                // kdf.js's equivalent branch used to fall through to Argon2id
                // for an unrecognized name, deriving a garbage key that then
                // surfaced as "incorrect password" -- naming the real failure
                // is the whole point of having this case.
                else -> throw KdfParameterException("Unsupported KDF algorithm: " + name)
            }
            return validateKdfParams(params)
        }

        fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}

/** Thrown when an envelope's KDF parameters are unrecognized or outside [KdfLimits]. */
class KdfParameterException(message: String) : IllegalArgumentException(message)

/**
 * Bounds for KDF parameters read off a stored envelope, ported value-for-value
 * from `kdf.js`'s `KDF_LIMITS` -- an envelope valid on one client must stay valid
 * on the other, so these are a shared wire contract, not a local policy choice.
 *
 * **These are DoS and structural guards, NOT a security floor**, and the
 * distinction is deliberate. [deriveKEK] dispatches on parameters that arrive
 * from the server (`user_keys.envelope`, which this client does not author on a
 * fresh sign-in) and passed them to Argon2id unchecked: an envelope claiming
 * `memorySize: 4194304` (4 GiB) or a nine-digit iteration count would OOM or hang
 * the process on every unlock attempt -- an unrecoverable lockout on that device,
 * since unlocking is the only way back in.
 *
 * What these must NOT do is reject weak-but-legitimate parameters.
 * WIRE-FORMAT-v2 3.3 ("KDF upgrade on unlock") requires a client to *read* a
 * below-policy wrap -- a legacy PBKDF2 entry, or a lower Argon2 t/m than today's
 * default -- precisely so [migrateWrapIfNeeded] can rewrap it at current policy
 * afterwards. A floor here would make those envelopes permanently unreadable
 * instead of upgradeable. So the floors are set at "structurally sane", not
 * "currently recommended".
 *
 * Canonical on-the-wire values, for reference (WIRE-FORMAT-v2 3): Argon2id
 * memorySize=65536 KiB, iterations=3, parallelism=1, hashLength=32, 16-byte salt;
 * legacy PBKDF2 SHA-256 at 600,000 iterations. Every bound below admits those.
 */
object KdfLimits {
    val ARGON2_MEMORY_KIB = 1024..262_144 // 1 MiB .. 256 MiB (canonical 65536)
    val ARGON2_ITERATIONS = 1..16 // canonical 3
    val ARGON2_PARALLELISM = 1..16 // canonical 1
    val PBKDF2_ITERATIONS = 1..10_000_000 // canonical 600000; the cap is the DoS guard
    val PBKDF2_HASHES = setOf("SHA-256", "SHA-384", "SHA-512")

    /** AES-256 key material. Both implementations write 32; anything else can't key AES-256 at all. */
    const val HASH_LENGTH = 32
    val SALT_BYTES = 8..64 // canonical 16
}

private fun requireInRange(value: Int, range: IntRange, label: String) {
    if (value !in range) {
        throw KdfParameterException(
            "Unsupported KDF parameter: " + label + "=" + value +
                " (expected an integer in " + range.first + ".." + range.last + ")",
        )
    }
}

/**
 * Validates the algorithm parameters deserialized from an envelope -- the Kotlin
 * twin of kdf.js's `validateKdfParams`. Returns [params] unchanged so it can wrap
 * a construction expression.
 *
 * Deliberately does NOT check the salt: kdf.js checks salt length inside
 * `deriveKEK`, not here, and [deriveKEK] below does the same. Keeping the split
 * matters because parsing and deriving are genuinely different moments --
 * inspecting or round-tripping an envelope (which several tests and
 * [WrapEntry.fromJson] do) shouldn't require a salt long enough to actually key
 * anything, while deriving from one absolutely should.
 *
 * @throws KdfParameterException with a legible message if anything is out of range.
 */
fun validateKdfParams(params: KdfParams): KdfParams {
    when (params) {
        is KdfParams.Argon2id -> {
            requireInRange(params.memorySizeKiB, KdfLimits.ARGON2_MEMORY_KIB, "memorySize")
            requireInRange(params.iterations, KdfLimits.ARGON2_ITERATIONS, "iterations")
            requireInRange(params.parallelism, KdfLimits.ARGON2_PARALLELISM, "parallelism")
            if (params.hashLength != KdfLimits.HASH_LENGTH) {
                throw KdfParameterException(
                    "Unsupported KDF parameter: hashLength=" + params.hashLength +
                        " (expected " + KdfLimits.HASH_LENGTH + ")",
                )
            }
        }
        is KdfParams.Pbkdf2 -> {
            requireInRange(params.iterations, KdfLimits.PBKDF2_ITERATIONS, "iterations")
            if (params.hash !in KdfLimits.PBKDF2_HASHES) {
                throw KdfParameterException("Unsupported KDF parameter: hash=" + params.hash)
            }
        }
    }
    return params
}

/**
 * Derives a raw AES-256 KEK from a passphrase, dispatching on the params'
 * concrete type. Re-validates rather than trusting the caller: [KdfParams] is a
 * public data class anyone can construct directly, so this -- not
 * [KdfParams.fromJson] -- is the choke point no derivation can get past.
 */
fun deriveKEK(passphrase: String, params: KdfParams): ByteArray {
    validateKdfParams(params)
    requireInRange(params.salt.size, KdfLimits.SALT_BYTES, "saltBytes")
    return when (params) {
        is KdfParams.Argon2id -> deriveKekArgon2id(passphrase, params)
        is KdfParams.Pbkdf2 -> deriveKekPbkdf2(passphrase, params)
    }
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

/**
 * SHA-384/512 are accepted as well as SHA-256, matching kdf.js's
 * `KDF_LIMITS.PBKDF2.hashes`. Nothing in either client has ever *written*
 * anything but SHA-256 (PBKDF2 is a reader-only legacy path), but this reader
 * used to hard-`require` SHA-256 while the shared wire contract admitted three --
 * a latent cross-client incompatibility that cost nothing to close. All three
 * `SecretKeyFactory` algorithms are available well below this module's minSdk 30.
 */
private fun deriveKekPbkdf2(passphrase: String, params: KdfParams.Pbkdf2): ByteArray {
    val algorithm = "PBKDF2WithHmac" + params.hash.replace("-", "")
    val factory = SecretKeyFactory.getInstance(algorithm)
    val spec = PBEKeySpec(passphrase.toCharArray(), params.salt, params.iterations, KdfLimits.HASH_LENGTH * 8)
    return factory.generateSecret(spec).encoded
}
