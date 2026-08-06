package com.nikhil.updeskfield

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.security.SecureRandom
import java.util.UUID

/**
 * Stable per-install Ed25519 identity — identical scheme to the desktop/host app.
 *
 * The public key is exported as base64 **SPKI DER** to match the server, which
 * reads the raw 32-byte key from the tail of that structure. The signature is
 * base64 over the raw challenge-nonce bytes.
 */
class Identity private constructor(
    val id: String,
    private val priv: Ed25519PrivateKeyParameters,
) {
    val publicKeyB64: String by lazy {
        val spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(priv.generatePublicKey())
        Base64.encodeToString(spki.encoded, Base64.NO_WRAP)
    }

    /** Ed25519 signature over [message], base64 (NO_WRAP). */
    fun sign(message: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
    }

    companion object {
        private const val PREFS = "updesk-field"

        fun load(ctx: Context): Identity {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var id = prefs.getString("device-id", null)
            var seedB64 = prefs.getString("priv-seed", null)
            if (id == null || seedB64 == null) {
                val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
                seedB64 = Base64.encodeToString(seed, Base64.NO_WRAP)
                id = "field-" + UUID.randomUUID().toString().substring(0, 8)
                prefs.edit().putString("device-id", id).putString("priv-seed", seedB64).apply()
            }
            val seed = Base64.decode(seedB64, Base64.NO_WRAP)
            return Identity(id, Ed25519PrivateKeyParameters(seed, 0))
        }

        /**
         * The device's FIXED unattended password — the credential a controller
         * uses to connect at any time (like the native host's password, not a
         * per-session PIN). Generated once on first use and persisted forever;
         * the operator can overwrite it via [setPassword].
         */
        fun getPassword(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var pw = prefs.getString("unattended-pw", null)
            if (pw.isNullOrEmpty()) {
                pw = generatePassword()
                prefs.edit().putString("unattended-pw", pw).apply()
            }
            return pw
        }

        fun setPassword(ctx: Context, pw: String) {
            val clean = pw.trim()
            if (clean.isEmpty()) return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("unattended-pw", clean).apply()
        }

        /** Replace the password with a fresh random one and return it. */
        fun regeneratePassword(ctx: Context): String {
            val pw = generatePassword()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("unattended-pw", pw).apply()
            return pw
        }

        // 8 chars from an unambiguous alphabet (no 0/O/1/l/I) — strong enough for
        // a permanently-online device, still easy to read off and type.
        private fun generatePassword(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
