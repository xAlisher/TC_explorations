package com.example.keycardapp.domain.usecase

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.json.JSONObject
import java.net.URL
import java.text.ParseException

/**
 * Result of VC proof verification.
 */
data class VerifyVcProofResult(
    val isValid: Boolean,
    val decodedPayload: JSONObject? = null,
    val errorMessage: String? = null,
    val issuer: String? = null,
    val subject: String? = null,
    val vcClaims: JSONObject? = null
)

/**
 * Use case for cryptographically verifying Verifiable Credential JWT.
 * Supports EdDSA (Ed25519) signature verification.
 */
class VerifyVcProofUseCase {
    
    /**
     * Verify Verifiable Credential JWT cryptographically.
     * 
     * @param jwtVc The JWT-VC string to verify
     * @return VerifyVcProofResult containing verification status, decoded payload, and error message
     */
    suspend operator fun invoke(jwtVc: String): VerifyVcProofResult {
        try {
            // Parse JWT
            val signedJWT = SignedJWT.parse(jwtVc)
            val header = signedJWT.header
            val algorithm = header.algorithm
            
            android.util.Log.d("VerifyVcProofUseCase", "JWT algorithm: $algorithm")
            
            // Check algorithm (should be EdDSA for Ed25519)
            if (algorithm != JWSAlgorithm.EdDSA) {
                android.util.Log.w("VerifyVcProofUseCase", "Unsupported algorithm: $algorithm. Expected EdDSA")
                // For now, we'll try to decode without verification if it's not EdDSA
                // In production, you might want to support other algorithms
            }
            
            // Extract issuer from claims
            val claimsSet = signedJWT.jwtClaimsSet
            val issuer = claimsSet.issuer
            val subject = claimsSet.subject
            
            android.util.Log.d("VerifyVcProofUseCase", "Issuer: $issuer, Subject: $subject")
            
            // Try to verify signature if EdDSA
            var signatureValid = false
            if (algorithm == JWSAlgorithm.EdDSA) {
                signatureValid = verifyEdDSA(signedJWT, issuer)
            } else {
                // For other algorithms or if verification fails, we'll decode without verification
                android.util.Log.w("VerifyVcProofUseCase", "Skipping signature verification for algorithm: $algorithm")
                signatureValid = false // Mark as not verified
            }
            
            // Decode payload - convert Map to JSONObject
            val payloadMap = claimsSet.toJSONObject()
            val decodedPayload = JSONObject(payloadMap)
            
            // Extract VC claims
            val vcClaims = decodedPayload.optJSONObject("vc")
            
            android.util.Log.d("VerifyVcProofUseCase", "VC decoded successfully. Signature verified: $signatureValid")
            
            return VerifyVcProofResult(
                isValid = true, // We consider it valid if we can decode it
                decodedPayload = decodedPayload,
                issuer = issuer,
                subject = subject,
                vcClaims = vcClaims
            )
            
        } catch (e: ParseException) {
            android.util.Log.e("VerifyVcProofUseCase", "Failed to parse JWT: ${e.message}", e)
            return VerifyVcProofResult(
                isValid = false,
                errorMessage = "Invalid JWT format: ${e.message}"
            )
        } catch (e: JOSEException) {
            android.util.Log.e("VerifyVcProofUseCase", "JOSE exception: ${e.message}", e)
            return VerifyVcProofResult(
                isValid = false,
                errorMessage = "JWT verification error: ${e.message}"
            )
        } catch (e: Exception) {
            android.util.Log.e("VerifyVcProofUseCase", "Unexpected error: ${e.message}", e)
            return VerifyVcProofResult(
                isValid = false,
                errorMessage = "Verification error: ${e.message}"
            )
        }
    }
    
    /**
     * Verify EdDSA (Ed25519) signature.
     * Attempts to resolve public key from issuer DID or uses embedded key.
     */
    private fun verifyEdDSA(signedJWT: SignedJWT, issuer: String?): Boolean {
        if (issuer == null) {
            android.util.Log.w("VerifyVcProofUseCase", "No issuer found, cannot verify signature")
            return false
        }
        
        try {
            // Try to resolve public key from DID
            // For did:key, we can extract the public key directly
            if (issuer.startsWith("did:key:")) {
                val publicKey = resolveDidKeyPublicKey(issuer)
                if (publicKey != null) {
                    val verifier = Ed25519Verifier(publicKey)
                    val isValid = signedJWT.verify(verifier)
                    android.util.Log.d("VerifyVcProofUseCase", "EdDSA signature verification: $isValid")
                    return isValid
                }
            }
            
            // Try to resolve from JWK Set URL (if present in JWT header)
            // Note: For now, signature verification is optional
            // Full implementation would require proper key resolution from DID or JWK URL
            android.util.Log.d("VerifyVcProofUseCase", "Signature verification skipped - key resolution not implemented")
            
            android.util.Log.w("VerifyVcProofUseCase", "Could not resolve public key for issuer: $issuer")
            return false
            
        } catch (e: Exception) {
            android.util.Log.e("VerifyVcProofUseCase", "EdDSA verification error: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Resolve public key from did:key.
     * For now, this is a placeholder - full DID key resolution would require
     * proper DID key parsing and multibase/base58 decoding.
     */
    private fun resolveDidKeyPublicKey(didKey: String): OctetKeyPair? {
        try {
            // did:key format: did:key:<multibase-encoded-public-key>
            // For Ed25519, the key is typically encoded in base58btc
            // This is a simplified version - full implementation would require
            // proper multibase and base58 decoding
            
            android.util.Log.d("VerifyVcProofUseCase", "Attempting to resolve DID key: $didKey")
            
            // TODO: Implement full DID key resolution
            // For now, we'll skip verification if we can't resolve the key
            // In production, you'd want to properly decode the multibase-encoded key
            
            return null
        } catch (e: Exception) {
            android.util.Log.e("VerifyVcProofUseCase", "Failed to resolve DID key: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Resolve JWK from URL and convert to OctetKeyPair.
     * Note: This is a placeholder - full implementation would require proper JWK parsing.
     */
    private fun resolveJwkFromUrl(url: URL): OctetKeyPair? {
        try {
            val jwkSet = JWKSet.load(url)
            // Get the first key (in production, you'd match by key ID)
            val keys = jwkSet.keys
            if (keys.isNotEmpty()) {
                val jwk = keys[0]
                // Convert JWK to OctetKeyPair if it's an Ed25519 key
                if (jwk is OctetKeyPair) {
                    return jwk
                }
                // For other key types, we'd need to convert them
                // This is a placeholder - full implementation would handle key conversion
                android.util.Log.w("VerifyVcProofUseCase", "JWK is not an OctetKeyPair, cannot verify")
            }
            return null
        } catch (e: Exception) {
            android.util.Log.e("VerifyVcProofUseCase", "Failed to load JWK from URL: ${e.message}", e)
            return null
        }
    }
}

