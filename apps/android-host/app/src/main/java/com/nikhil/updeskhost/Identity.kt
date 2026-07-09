package com.nikhil.updeskhost

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.security.SecureRandom
import java.util.UUID

/**
 * Stable per-install Ed25519 identity.
 *
 * The public key is exported as base64 **SPKI DER** to match the server, which
 * reads the raw 32-byte key from the tail of that structure. The signature is
 * base64 over the raw challenge-nonce bytes — identical to the desktop client.
 *
 * Baseline: the private seed lives in SharedPreferences. For production, move it
 * into the Android Keystore (hardware-backed) — see README security notes.
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
        fun load(ctx: Context): Identity {
            val prefs = ctx.getSharedPreferences("updesk", Context.MODE_PRIVATE)
            var id = prefs.getString("device-id", null)
            var seedB64 = prefs.getString("priv-seed", null)
            if (id == null || seedB64 == null) {
                val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
                seedB64 = Base64.encodeToString(seed, Base64.NO_WRAP)
                id = "host-" + UUID.randomUUID().toString().substring(0, 8)
                prefs.edit().putString("device-id", id).putString("priv-seed", seedB64).apply()
            }
            val seed = Base64.decode(seedB64, Base64.NO_WRAP)
            return Identity(id, Ed25519PrivateKeyParameters(seed, 0))
        }
    }
}
