package com.example.keycardapp.domain.usecase

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Result of VC read operation.
 */
data class ReadVcResult(
    val success: Boolean,
    val jwtVc: String? = null,
    val errorMessage: String? = null,
    val isTagLost: Boolean = false
)

/**
 * Use case for reading Verifiable Credential from NDEF on Keycard.
 * Reads NDEF directly without PIN or secure channel (like standard NFC tags).
 * Handles NDEF parsing and JWT-VC extraction.
 */
class ReadVcFromNdefUseCase {
    companion object {
        private const val MAX_RETRIES = 3
        private const val MIME_TYPE = "application/vc+jwt"
    }
    
    /**
     * Read Verifiable Credential from NDEF on Keycard.
     * Reads NDEF directly using standard Android NDEF APIs (no PIN required).
     * 
     * @param tag The NFC tag representing the Keycard
     * @return ReadVcResult containing success status, JWT-VC string, and error message
     */
    suspend operator fun invoke(tag: Tag): ReadVcResult = withContext(Dispatchers.IO) {
        return@withContext try {
            // Debug: Log all available technologies
            val techList = tag.techList
            android.util.Log.d("ReadVcFromNdefUseCase", "Tag technologies: ${techList.joinToString(", ")}")
            android.util.Log.d("ReadVcFromNdefUseCase", "Tag ID: ${tag.id.contentToString()}")
            
            // Try to get NDEF technology
            android.util.Log.d("ReadVcFromNdefUseCase", "Attempting to get NDEF technology...")
            var ndef = Ndef.get(tag)
            
            if (ndef == null) {
                android.util.Log.w("ReadVcFromNdefUseCase", "Ndef.get(tag) returned null")
                android.util.Log.d("ReadVcFromNdefUseCase", "Available tech classes: ${techList.map { it.split(".").lastOrNull() }.joinToString(", ")}")
                
                // Try to manually check if NDEF is in the tech list
                val hasNdef = techList.any { it.contains("Ndef", ignoreCase = true) }
                android.util.Log.d("ReadVcFromNdefUseCase", "Tech list contains NDEF: $hasNdef")
                
                // Try to manually instantiate NDEF if it's in the tech list
                if (hasNdef) {
                    android.util.Log.d("ReadVcFromNdefUseCase", "NDEF is in tech list, trying to manually instantiate...")
                    try {
                        val ndefTech = techList.firstOrNull { it.contains("Ndef", ignoreCase = true) }
                        if (ndefTech != null) {
                            android.util.Log.d("ReadVcFromNdefUseCase", "Found NDEF tech class: $ndefTech")
                            // Try to get NDEF using reflection
                            val ndefClass = Class.forName(ndefTech)
                            val getMethod = ndefClass.getMethod("get", Tag::class.java)
                            val ndefInstance = getMethod.invoke(null, tag)
                            if (ndefInstance is Ndef) {
                                android.util.Log.d("ReadVcFromNdefUseCase", "Successfully instantiated NDEF manually")
                                ndef = ndefInstance
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ReadVcFromNdefUseCase", "Failed to manually instantiate NDEF: ${e.message}")
                    }
                }
                
                if (ndef == null) {
                    // Keycard might need IsoDep to access NDEF
                    android.util.Log.d("ReadVcFromNdefUseCase", "NDEF not directly available, checking if IsoDep is available...")
                    val hasIsoDep = techList.any { it.contains("IsoDep", ignoreCase = true) }
                    android.util.Log.d("ReadVcFromNdefUseCase", "Tech list contains IsoDep: $hasIsoDep")
                    
                    if (hasIsoDep) {
                        android.util.Log.w("ReadVcFromNdefUseCase", "Keycard detected but NDEF not directly accessible. TagInfo can read it, so this might be a timing or access issue.")
                        android.util.Log.w("ReadVcFromNdefUseCase", "Trying alternative: NDEF might be accessible through IsoDep or needs different access method.")
                        return@withContext ReadVcResult(
                            success = false,
                            errorMessage = "Keycard detected but NDEF not directly accessible. TagInfo can read it, so this might be a timing or access issue. Available techs: ${techList.joinToString(", ")}"
                        )
                    }
                    
                    return@withContext ReadVcResult(
                        success = false,
                        errorMessage = "Tag does not support NDEF. Available technologies: ${techList.joinToString(", ")}"
                    )
                }
            }
            
            android.util.Log.d("ReadVcFromNdefUseCase", "NDEF technology obtained successfully")
            android.util.Log.d("ReadVcFromNdefUseCase", "Reading NDEF from tag...")
            
            try {
                android.util.Log.d("ReadVcFromNdefUseCase", "Connecting to NDEF...")
                ndef.connect()
                android.util.Log.d("ReadVcFromNdefUseCase", "NDEF connected successfully")
            } catch (e: Exception) {
                android.util.Log.e("ReadVcFromNdefUseCase", "Failed to connect to NDEF: ${e.message}", e)
                android.util.Log.e("ReadVcFromNdefUseCase", "Connection exception type: ${e.javaClass.name}")
                e.printStackTrace()
                return@withContext ReadVcResult(
                    success = false,
                    errorMessage = "Failed to connect to NDEF: ${e.message}",
                    isTagLost = isTagLostError(e.message ?: "")
                )
            }
            
            val ndefMessage = try {
                android.util.Log.d("ReadVcFromNdefUseCase", "Reading NDEF message...")
                val message = ndef.ndefMessage
                android.util.Log.d("ReadVcFromNdefUseCase", "NDEF message read: ${if (message != null) "not null" else "null"}")
                message
            } catch (e: Exception) {
                android.util.Log.e("ReadVcFromNdefUseCase", "Failed to read NDEF message: ${e.message}", e)
                android.util.Log.e("ReadVcFromNdefUseCase", "Read exception type: ${e.javaClass.name}")
                e.printStackTrace()
                try { ndef.close() } catch (_: Exception) {}
                return@withContext ReadVcResult(
                    success = false,
                    errorMessage = "Failed to read NDEF message: ${e.message}",
                    isTagLost = isTagLostError(e.message ?: "")
                )
            } finally {
                try {
                    android.util.Log.d("ReadVcFromNdefUseCase", "Closing NDEF connection...")
                    ndef.close()
                    android.util.Log.d("ReadVcFromNdefUseCase", "NDEF connection closed")
                } catch (e: Exception) {
                    android.util.Log.w("ReadVcFromNdefUseCase", "Error closing NDEF: ${e.message}")
                }
            }
            
            if (ndefMessage == null) {
                android.util.Log.w("ReadVcFromNdefUseCase", "NDEF message is null")
                return@withContext ReadVcResult(
                    success = false,
                    errorMessage = "No NDEF message found on tag"
                )
            }
            
            android.util.Log.d("ReadVcFromNdefUseCase", "NDEF message read successfully (${ndefMessage.toByteArray().size} bytes)")
            
            // Extract JWT-VC from NDEF records
            val jwtVc = extractJwtVcFromNdef(ndefMessage)
            
            if (jwtVc == null) {
                return@withContext ReadVcResult(
                    success = false,
                    errorMessage = "No VC found in NDEF message. Expected MIME type: $MIME_TYPE"
                )
            }
            
            android.util.Log.d("ReadVcFromNdefUseCase", "JWT-VC extracted: ${jwtVc.length} chars")
            
            ReadVcResult(
                success = true,
                jwtVc = jwtVc
            )
            
        } catch (e: Exception) {
            android.util.Log.e("ReadVcFromNdefUseCase", "Unexpected error reading VC: ${e.message}", e)
            ReadVcResult(
                success = false,
                errorMessage = "Error reading VC: ${e.message}",
                isTagLost = isTagLostError(e.message ?: "")
            )
        }
    }
    
    /**
     * Extract JWT-VC from NDEF message.
     * Looks for MIME type "application/vc+jwt" or any text record.
     */
    private fun extractJwtVcFromNdef(ndefMessage: NdefMessage): String? {
        val records = ndefMessage.records
        
        for (record in records) {
            // Check for MIME type record with "application/vc+jwt"
            if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                val mimeType = String(record.type, StandardCharsets.UTF_8)
                android.util.Log.d("ReadVcFromNdefUseCase", "Found MIME record: $mimeType")
                
                if (mimeType == MIME_TYPE || mimeType.contains("vc") || mimeType.contains("jwt")) {
                    val payload = record.payload
                    val jwtVc = String(payload, StandardCharsets.UTF_8).trim()
                    if (jwtVc.isNotEmpty()) {
                        return jwtVc
                    }
                }
            }
            
            // Also check for text records (fallback)
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && 
                record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                val payload = record.payload
                if (payload.isNotEmpty()) {
                    // Text records have encoding byte at start
                    val text = String(payload.sliceArray(1 until payload.size), StandardCharsets.UTF_8)
                    val trimmed = text.trim()
                    // Check if it looks like a JWT (starts with eyJ)
                    if (trimmed.startsWith("eyJ")) {
                        android.util.Log.d("ReadVcFromNdefUseCase", "Found JWT in text record")
                        return trimmed
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Check if error is due to tag loss (can be retried).
     */
    private fun isTagLostError(error: String): Boolean {
        return error.contains("TagLostException", ignoreCase = true) ||
               error.contains("Tag was lost", ignoreCase = true) ||
               error.contains("TagLost", ignoreCase = true) ||
               error.contains("IOException", ignoreCase = true) ||
               error.contains("connection", ignoreCase = true)
    }
}

