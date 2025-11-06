package com.example.keycardapp.data.repository

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.example.keycardapp.domain.repository.KeycardRepository
import im.status.keycard.android.NFCCardChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of KeycardRepository.
 * Handles all Keycard operations using the Keycard SDK.
 */
@Singleton
class KeycardRepositoryImpl @Inject constructor() : KeycardRepository {
    
    override suspend fun verifyPin(tag: Tag, pin: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: return@withContext Result.failure(
            IllegalStateException("IsoDep not available for tag")
        )
        
        return@withContext try {
            Log.d("KeycardRepository", "Connecting IsoDep for PIN verification...")
            isoDep.connect()
            isoDep.timeout = 120000
            
            // Create a Keycard channel (APDU transport)
            val channel = NFCCardChannel(isoDep)
            Log.d("KeycardRepository", "IsoDep connected; channel ready")
            
            // TODO: Replace with real CommandSet flow: select applet, pair/open secure channel, verify PIN
            // For now, keep a simple placeholder check so the UI flow is testable.
            val isValid = pin == "123456"
            
            if (isValid) {
                Log.d("KeycardRepository", "PIN verification successful")
            } else {
                Log.d("KeycardRepository", "PIN verification failed")
            }
            
            Result.success(isValid)
        } catch (e: Exception) {
            Log.e("KeycardRepository", "Error during PIN verification: ${e.message}", e)
            Result.failure(e)
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }
    
    override suspend fun writeNdef(
        tag: Tag,
        ndefBytes: ByteArray,
        pairingPassword: String,
        pin: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: return@withContext Result.failure(
            IllegalStateException("IsoDep not available")
        )
        
        return@withContext try {
            Log.d("KeycardRepository", "=== Starting writeNdef ===")
            Log.d("KeycardRepository", "NDEF bytes: ${ndefBytes.size}, pairing password length: ${pairingPassword.length}, PIN length: ${pin.length}")
            
            Log.d("KeycardRepository", "Connecting to IsoDep...")
            isoDep.connect()
            isoDep.timeout = 120000
            Log.d("KeycardRepository", "IsoDep connected, timeout set to ${isoDep.timeout}ms")
            
            val channel = NFCCardChannel(isoDep)
            Log.d("KeycardRepository", "NFCCardChannel created")
            
            // Load CommandSet reflectively to avoid compile-time API coupling
            Log.d("KeycardRepository", "Loading CommandSet class...")
            val cardChannelClass = Class.forName("im.status.keycard.io.CardChannel")
            Log.d("KeycardRepository", "CardChannel class found: ${cardChannelClass.name}")
            
            val candidateCommands = listOf(
                "im.status.keycard.applet.CommandSet",
                "im.status.keycard.applet.KeycardCommandSet",
                "im.status.keycard.applet.CardCommandSet"
            )
            Log.d("KeycardRepository", "Searching for CommandSet in: ${candidateCommands.joinToString()}")
            
            val commandSetClass = candidateCommands.firstOrNull { name ->
                try {
                    val found = Class.forName(name) != null
                    if (found) Log.d("KeycardRepository", "Found CommandSet class: $name")
                    found
                } catch (_: Throwable) {
                    false
                }
            }?.let { Class.forName(it) } ?: run {
                Log.e("KeycardRepository", "CommandSet not found in any candidate classes")
                return@withContext Result.failure(
                    IllegalStateException("Keycard SDK not on classpath: none of ${candidateCommands.joinToString()} found")
                )
            }
            
            Log.d("KeycardRepository", "Using CommandSet class: ${commandSetClass.name}")
            val cmd = commandSetClass.getConstructor(cardChannelClass).newInstance(channel)
            Log.d("KeycardRepository", "CommandSet instance created")
            
            // Debug: List all available methods to find unpair, pairing management, or data storage methods
            val allMethodNames = commandSetClass.methods.map { "${it.name}(${it.parameterTypes.joinToString { it.simpleName }})" }
            Log.d("KeycardRepository", "Available methods (${allMethodNames.size}): ${allMethodNames.take(20).joinToString(", ")}${if (allMethodNames.size > 20) "..." else ""}")
            val unpairMethods = allMethodNames.filter { it.lowercase().contains("unpair") }
            if (unpairMethods.isNotEmpty()) {
                Log.d("KeycardRepository", "Unpair-related methods found: ${unpairMethods.joinToString()}")
            }
            // Find all methods that might store data
            val storeMethods = allMethodNames.filter { 
                it.lowercase().contains("store") || 
                it.lowercase().contains("write") || 
                it.lowercase().contains("data") ||
                it.lowercase().contains("ndef")
            }
            if (storeMethods.isNotEmpty()) {
                Log.d("KeycardRepository", "Data storage methods found: ${storeMethods.joinToString()}")
            }
            
            // Select applet
            Log.d("KeycardRepository", "Selecting Keycard applet...")
            commandSetClass.getMethod("select").invoke(cmd)
            Log.d("KeycardRepository", "Keycard applet selected")
            
            // Step 1: Try to unpair any existing pairings first (to avoid pairing conflicts)
            tryUnpairExistingPairings(commandSetClass, cmd)
            
            // Step 2: Pair with the card
            pairWithCard(commandSetClass, cmd, pairingPassword)
            
            // Step 3: Open secure channel
            openSecureChannel(commandSetClass, cmd)
            
            // Step 4: Verify PIN
            verifyPinOnCard(commandSetClass, cmd, pin)
            
            // Step 5: Write NDEF
            writeNdefToCard(commandSetClass, cmd, ndefBytes)
            
            // Step 6: Unpair after successful write
            tryUnpairAfterWrite(commandSetClass, cmd)
            
            Log.d("KeycardRepository", "NDEF write completed successfully")
            Result.success(Unit)
            
        } catch (cnf: ClassNotFoundException) {
            Log.e("KeycardRepository", "ClassNotFoundException: ${cnf.message}", cnf)
            Result.failure(IllegalStateException("Keycard SDK not on classpath: ${cnf.message}"))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Unwrap InvocationTargetException to get the actual cause
            val cause = e.cause
            val errorMsg = cause?.message ?: e.message ?: "Unknown error"
            val errorClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val errorCode = extractErrorCode(errorMsg)
            Log.e("KeycardRepository", "Secure write exception: $errorClass - $errorMsg (error code: $errorCode)", e)
            Result.failure(Exception("Secure write exception: $errorClass - $errorMsg" + if (errorCode.isNotEmpty()) " (Error: $errorCode)" else ""))
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.toString()
            val errorClass = e.javaClass.simpleName
            Log.e("KeycardRepository", "Exception: $errorClass - $errorMsg", e)
            Result.failure(Exception("Secure write exception: $errorClass - $errorMsg"))
        } finally {
            try {
                Log.d("KeycardRepository", "Closing IsoDep connection...")
                isoDep.close()
                Log.d("KeycardRepository", "IsoDep connection closed")
            } catch (closeEx: Exception) {
                Log.d("KeycardRepository", "Error closing IsoDep: ${closeEx.message}")
            }
        }
    }
    
    /**
     * Try to unpair any existing pairings first (to avoid pairing conflicts).
     * Note: Keycard has max 5 pairing slots. Error 0x6A84 means slots are full.
     */
    private fun tryUnpairExistingPairings(commandSetClass: Class<*>, cmd: Any) {
        Log.d("KeycardRepository", "Attempting to unpair existing pairings...")
        var unpairMethod: java.lang.reflect.Method? = null
        
        try {
            // Try different unpair method signatures
            unpairMethod = try {
                commandSetClass.getMethod("unpair").also { Log.d("KeycardRepository", "Found unpair() method (no params)") }
            } catch (_: NoSuchMethodException) {
                try {
                    commandSetClass.getMethod("unpairAll").also { Log.d("KeycardRepository", "Found unpairAll() method") }
                } catch (_: NoSuchMethodException) {
                    try {
                        commandSetClass.getMethod("unpair", Int::class.javaPrimitiveType).also { Log.d("KeycardRepository", "Found unpair(int) method") }
                    } catch (_: NoSuchMethodException) {
                        try {
                            commandSetClass.getMethod("unpair", Byte::class.javaPrimitiveType).also { Log.d("KeycardRepository", "Found unpair(byte) method") }
                        } catch (_: NoSuchMethodException) {
                            val allMethods = commandSetClass.methods.filter { it.name.lowercase().contains("unpair") }
                            if (allMethods.isNotEmpty()) {
                                Log.d("KeycardRepository", "Found unpair-related methods: ${allMethods.joinToString { "${it.name}(${it.parameterTypes.joinToString()})" }}")
                                allMethods.firstOrNull { it.parameterTypes.isEmpty() }?.also {
                                    Log.d("KeycardRepository", "Using unpair method: ${it.name}")
                                    unpairMethod = it
                                }
                            }
                            unpairMethod
                        }
                    }
                }
            }
            
            if (unpairMethod != null) {
                try {
                    Log.d("KeycardRepository", "Calling unpair method: ${unpairMethod.name}...")
                    if (unpairMethod.parameterTypes.isEmpty()) {
                        unpairMethod.invoke(cmd)
                        Log.d("KeycardRepository", "Unpair successful (no params)")
                    } else {
                        Log.d("KeycardRepository", "Unpair method requires parameters, skipping (would need pairing index)")
                    }
                } catch (unpairEx: Exception) {
                    val unpairCause = if (unpairEx is java.lang.reflect.InvocationTargetException) unpairEx.cause else unpairEx
                    val unpairMsg = unpairCause?.message ?: unpairEx.message ?: "Unknown error"
                    Log.d("KeycardRepository", "Unpair failed (this is OK if card not paired or method needs params): $unpairMsg (${unpairCause?.javaClass?.simpleName ?: unpairEx.javaClass.simpleName})")
                    // Ignore unpair errors - card might not be paired, or pairing might be in different slot
                }
            } else {
                Log.d("KeycardRepository", "No unpair method available, skipping unpair step")
            }
        } catch (ex: Exception) {
            Log.d("KeycardRepository", "Exception while trying to unpair: ${ex.message} (${ex.javaClass.simpleName})")
            // If unpair is not available or fails, continue with pairing attempt
        }
    }
    
    /**
     * Pair with the card (sets pairing info in CommandSet).
     */
    private fun pairWithCard(commandSetClass: Class<*>, cmd: Any, pairingPassword: String) {
        Log.d("KeycardRepository", "Looking for autoPair method...")
        val autoPair = try {
            commandSetClass.getMethod("autoPair", String::class.java).also { Log.d("KeycardRepository", "Found autoPair(String) method") }
        } catch (_: NoSuchMethodException) {
            try {
                commandSetClass.getMethod("autoPair", ByteArray::class.java).also { Log.d("KeycardRepository", "Found autoPair(ByteArray) method") }
            } catch (_: NoSuchMethodException) {
                Log.e("KeycardRepository", "autoPair method not found")
                throw IllegalStateException("Step 2 failed: autoPair method not found on CommandSet")
            }
        }
        
        Log.d("KeycardRepository", "Attempting to pair with password length: ${pairingPassword.length}")
        try {
            if (autoPair.parameterTypes[0] == String::class.java) {
                Log.d("KeycardRepository", "Calling autoPair with String parameter...")
                autoPair.invoke(cmd, pairingPassword)
                Log.d("KeycardRepository", "autoPair successful")
            } else {
                Log.d("KeycardRepository", "Calling autoPair with ByteArray parameter...")
                autoPair.invoke(cmd, pairingPassword.toByteArray())
                Log.d("KeycardRepository", "autoPair successful")
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            val errorMsg = cause?.message ?: e.message ?: "Unknown error"
            val errorClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val errorCode = extractErrorCode(errorMsg)
            Log.e("KeycardRepository", "Step 2 (Pairing) failed: $errorMsg ($errorClass), error code: $errorCode")
            
            // Handle "Invalid card cryptogram" - may indicate wrong pairing password or card needs unpairing
            if (errorMsg.contains("Invalid card cryptogram", ignoreCase = true) || 
                errorMsg.contains("cryptogram", ignoreCase = true)) {
                Log.e("KeycardRepository", "Pairing failed with 'Invalid card cryptogram'. This may indicate:")
                Log.e("KeycardRepository", "  1. Wrong pairing password")
                Log.e("KeycardRepository", "  2. Card needs to be unpaired first")
                Log.e("KeycardRepository", "  3. Card applet may need to be updated")
                // Try to unpair and retry
                try {
                    Log.d("KeycardRepository", "Attempting to unpair and retry due to cryptogram error...")
                    retryPairingAfterUnpair(commandSetClass, cmd, autoPair, pairingPassword, errorClass, errorMsg, errorCode)
                } catch (retryEx: Exception) {
                    throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg. This may indicate wrong pairing password or card needs unpairing. Please verify the pairing password and try unpairing the card first.")
                }
            }
            // If pairing fails with 0x6A84, try to unpair first and retry
            else if (errorMsg.contains("0x6A84", ignoreCase = true) || errorMsg.contains("6A84") || errorMsg.contains("Pairing failed", ignoreCase = true)) {
                Log.d("KeycardRepository", "Pairing failed with 0x6A84, attempting unpair and retry...")
                retryPairingAfterUnpair(commandSetClass, cmd, autoPair, pairingPassword, errorClass, errorMsg, errorCode)
            } else {
                Log.e("KeycardRepository", "Pairing failed with non-0x6A84 error, not retrying")
                throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg" + if (errorCode.isNotEmpty()) " (Error: $errorCode)" else "")
            }
        }
    }
    
    /**
     * Retry pairing after unpairing (when pairing slots are full).
     */
    private fun retryPairingAfterUnpair(
        commandSetClass: Class<*>,
        cmd: Any,
        autoPair: java.lang.reflect.Method,
        pairingPassword: String,
        errorClass: String,
        errorMsg: String,
        errorCode: String
    ) {
        try {
            val retryUnpairMethod = try {
                commandSetClass.getMethod("unpair").also { Log.d("KeycardRepository", "Found unpair() for retry") }
            } catch (_: NoSuchMethodException) {
                try {
                    commandSetClass.getMethod("unpairAll").also { Log.d("KeycardRepository", "Found unpairAll() for retry") }
                } catch (_: NoSuchMethodException) {
                    Log.d("KeycardRepository", "No unpair method found for retry")
                    null
                }
            }
            
            if (retryUnpairMethod != null) {
                try {
                    Log.d("KeycardRepository", "Calling unpair before retry...")
                    retryUnpairMethod.invoke(cmd)
                    Log.d("KeycardRepository", "Unpair successful, retrying pairing...")
                    // Retry pairing after unpair
                    if (autoPair.parameterTypes[0] == String::class.java) {
                        autoPair.invoke(cmd, pairingPassword)
                    } else {
                        autoPair.invoke(cmd, pairingPassword.toByteArray())
                    }
                    Log.d("KeycardRepository", "Pairing retry successful after unpair")
                } catch (retryEx: Exception) {
                    val retryCause = if (retryEx is java.lang.reflect.InvocationTargetException) retryEx.cause else retryEx
                    val retryMsg = retryCause?.message ?: retryEx.message ?: "Unknown error"
                    val retryClass = retryCause?.javaClass?.simpleName ?: retryEx.javaClass.simpleName
                    Log.e("KeycardRepository", "Step 2 (Pairing retry) failed after unpair: $retryMsg ($retryClass)")
                    throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Retry after unpair also failed: $retryMsg")
                }
            } else {
                // Try to find any unpair method by listing all methods
                val allUnpairMethods = commandSetClass.methods.filter {
                    it.name.lowercase().contains("unpair")
                }
                if (allUnpairMethods.isNotEmpty()) {
                    Log.d("KeycardRepository", "Found unpair methods via reflection: ${allUnpairMethods.joinToString { "${it.name}(${it.parameterTypes.joinToString()})" }}")
                    val noParamUnpair = allUnpairMethods.firstOrNull { it.parameterTypes.isEmpty() }
                    if (noParamUnpair != null) {
                        try {
                            Log.d("KeycardRepository", "Trying unpair method: ${noParamUnpair.name}...")
                            noParamUnpair.invoke(cmd)
                            Log.d("KeycardRepository", "Unpair successful via reflection, retrying pairing...")
                            // Retry pairing after unpair
                            if (autoPair.parameterTypes[0] == String::class.java) {
                                autoPair.invoke(cmd, pairingPassword)
                            } else {
                                autoPair.invoke(cmd, pairingPassword.toByteArray())
                            }
                            Log.d("KeycardRepository", "Pairing retry successful after unpair")
                        } catch (retryEx: Exception) {
                            val retryCause = if (retryEx is java.lang.reflect.InvocationTargetException) retryEx.cause else retryEx
                            val retryMsg = retryCause?.message ?: retryEx.message ?: "Unknown error"
                            Log.e("KeycardRepository", "Pairing retry failed after unpair: $retryMsg")
                            throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Unpair found but retry failed: $retryMsg")
                        }
                    } else {
                        Log.e("KeycardRepository", "Cannot retry pairing: unpair methods found but all require parameters")
                        throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Card pairing slots are full (0x6A84). Unpair methods require parameters we don't have.")
                    }
                } else {
                    Log.e("KeycardRepository", "Cannot retry pairing: no unpair method available")
                    throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Card pairing slots are full (0x6A84). No unpair method available. Please unpair the card using another tool or app.")
                }
            }
        } catch (retryException: Exception) {
            val retryCause = if (retryException is java.lang.reflect.InvocationTargetException) retryException.cause else retryException
            Log.e("KeycardRepository", "Exception during pairing retry: ${retryCause?.message ?: retryException.message} (${retryCause?.javaClass?.simpleName ?: retryException.javaClass.simpleName})")
            throw Exception("Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Retry exception: ${retryCause?.message ?: retryException.message}")
        }
    }
    
    /**
     * Open secure channel (uses pairing info already set by autoPair).
     */
    private fun openSecureChannel(commandSetClass: Class<*>, cmd: Any) {
        Log.d("KeycardRepository", "Opening secure channel...")
        val autoOpenSC = try {
            commandSetClass.getMethod("autoOpenSecureChannel").also { Log.d("KeycardRepository", "Found autoOpenSecureChannel() method") }
        } catch (_: NoSuchMethodException) {
            Log.e("KeycardRepository", "autoOpenSecureChannel() not found")
            throw IllegalStateException("autoOpenSecureChannel() not found on CommandSet")
        }
        
        try {
            autoOpenSC.invoke(cmd)
            Log.d("KeycardRepository", "Secure channel opened successfully")
        } catch (scEx: Exception) {
            val scCause = if (scEx is java.lang.reflect.InvocationTargetException) scEx.cause else scEx
            val scMsg = scCause?.message ?: scEx.message ?: "Unknown error"
            val scClass = scCause?.javaClass?.simpleName ?: scEx.javaClass.simpleName
            Log.e("KeycardRepository", "Failed to open secure channel: $scMsg ($scClass)")
            throw scCause ?: scEx
        }
    }
    
    /**
     * Verify PIN: try verifyPIN(String) then verifyPIN(byte[]).
     */
    private fun verifyPinOnCard(commandSetClass: Class<*>, cmd: Any, pin: String) {
        Log.d("KeycardRepository", "Verifying PIN...")
        val verifyMethod = try {
            commandSetClass.getMethod("verifyPIN", String::class.java).also { Log.d("KeycardRepository", "Found verifyPIN(String) method") }
        } catch (_: NoSuchMethodException) {
            try {
                commandSetClass.getMethod("verifyPIN", ByteArray::class.java).also { Log.d("KeycardRepository", "Found verifyPIN(ByteArray) method") }
            } catch (_: NoSuchMethodException) {
                try {
                    commandSetClass.getMethod("verifyPin", String::class.java).also { Log.d("KeycardRepository", "Found verifyPin(String) method") }
                } catch (_: NoSuchMethodException) {
                    try {
                        commandSetClass.getMethod("verifyPin", ByteArray::class.java).also { Log.d("KeycardRepository", "Found verifyPin(ByteArray) method") }
                    } catch (_: NoSuchMethodException) {
                        Log.e("KeycardRepository", "verifyPIN/verifyPin method not found")
                        throw IllegalStateException("verifyPIN/verifyPin method not found on CommandSet")
                    }
                }
            }
        }
        
        val pinOk = try {
            if (verifyMethod.parameterTypes[0] == String::class.java) {
                Log.d("KeycardRepository", "Calling verifyPIN with String parameter...")
                (verifyMethod.invoke(cmd, pin) as? Boolean) ?: true
            } else {
                Log.d("KeycardRepository", "Calling verifyPIN with ByteArray parameter...")
                (verifyMethod.invoke(cmd, pin.toByteArray()) as? Boolean) ?: true
            }
        } catch (pinEx: Exception) {
            val pinCause = if (pinEx is java.lang.reflect.InvocationTargetException) pinEx.cause else pinEx
            val pinMsg = pinCause?.message ?: pinEx.message ?: "Unknown error"
            val pinClass = pinCause?.javaClass?.simpleName ?: pinEx.javaClass.simpleName
            Log.e("KeycardRepository", "PIN verification exception: $pinMsg ($pinClass)")
            throw Exception("PIN verification exception: $pinClass - $pinMsg")
        }
        
        if (!pinOk) {
            Log.e("KeycardRepository", "PIN verification failed: card returned false")
            throw IllegalStateException("PIN verification failed on card")
        }
        Log.d("KeycardRepository", "PIN verification successful")
    }
    
    /**
     * Write NDEF - Uses setNDEF which now supports automatic chunking for payloads up to 500 bytes.
     * The updated SDK automatically splits large data into chunks.
     */
    private fun writeNdefToCard(commandSetClass: Class<*>, cmd: Any, ndefBytes: ByteArray) {
        Log.d("KeycardRepository", "Writing NDEF data (${ndefBytes.size} bytes) - SDK will automatically chunk if needed...")
        
        // Log info about chunking support
        if (ndefBytes.size > 256) {
            Log.d("KeycardRepository", "NDEF payload (${ndefBytes.size} bytes) will be automatically chunked by SDK (supports up to 500 bytes)")
        }
        val setNdefMethod = commandSetClass.methods.firstOrNull { m ->
            m.name == "setNDEF" && m.parameterTypes.size == 1 && m.parameterTypes[0] == ByteArray::class.java
        }
        
        if (setNdefMethod == null) {
            throw IllegalStateException("setNDEF() method not found on CommandSet. This should not happen with the updated SDK.")
        }
        
        // setNDEF now supports automatic chunking for payloads up to 500 bytes
        Log.d("KeycardRepository", "Calling setNDEF() method (payload size: ${ndefBytes.size} bytes) - SDK will automatically chunk if needed...")
        try {
            setNdefMethod.invoke(cmd, ndefBytes)
            Log.d("KeycardRepository", "setNDEF() successful (chunking handled automatically by SDK)")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            val ndefMsg = cause?.message ?: e.message ?: "Unknown error"
            val ndefClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            
            // Handle AssertionError - may indicate payload exceeds 500 bytes or other issue
            if (cause is AssertionError) {
                Log.e("KeycardRepository", "setNDEF() failed with AssertionError (payload ${ndefBytes.size} bytes): $ndefMsg. This may indicate payload exceeds 500 bytes limit or secure channel issue.")
                throw IllegalStateException("setNDEF() failed: Payload (${ndefBytes.size} bytes) may exceed 500 bytes limit or secure channel issue. Error: $ndefMsg")
            }
            
            Log.e("KeycardRepository", "setNDEF() failed: $ndefMsg ($ndefClass)")
            throw cause ?: e
        } catch (e: AssertionError) {
            // Handle AssertionError directly (not wrapped in InvocationTargetException)
            Log.e("KeycardRepository", "setNDEF() failed with AssertionError (payload ${ndefBytes.size} bytes): ${e.message}. This may indicate payload exceeds 500 bytes limit or secure channel issue.")
            throw IllegalStateException("setNDEF() failed: Payload (${ndefBytes.size} bytes) may exceed 500 bytes limit or secure channel issue. Error: ${e.message}", e)
        }
        
        Log.d("KeycardRepository", "NDEF write completed successfully")
    }
    
    /**
     * Unpair after successful write (non-critical operation).
     */
    private fun tryUnpairAfterWrite(commandSetClass: Class<*>, cmd: Any) {
        try {
            Log.d("KeycardRepository", "Unpairing card after write...")
            val unpairMethods = commandSetClass.methods.filter {
                it.name.lowercase().contains("unpair") && it.parameterTypes.isEmpty()
            }
            if (unpairMethods.isNotEmpty()) {
                val unpairMethod = unpairMethods.first()
                try {
                    unpairMethod.invoke(cmd)
                    Log.d("KeycardRepository", "Unpair successful after write")
                } catch (unpairEx: Exception) {
                    Log.d("KeycardRepository", "Unpair failed (non-critical): ${unpairEx.message}")
                }
            } else {
                Log.d("KeycardRepository", "No unpair method available (non-critical)")
            }
        } catch (e: Exception) {
            Log.d("KeycardRepository", "Exception during unpair (non-critical): ${e.message}")
        }
    }
    
    override suspend fun readNdef(
        tag: Tag,
        pairingPassword: String,
        pin: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: return@withContext Result.failure(
            IllegalStateException("IsoDep not available")
        )
        
        return@withContext try {
            Log.d("KeycardRepository", "=== Starting readNdef ===")
            Log.d("KeycardRepository", "Pairing password length: ${pairingPassword.length}, PIN length: ${pin.length}")
            
            Log.d("KeycardRepository", "Connecting to IsoDep...")
            isoDep.connect()
            isoDep.timeout = 120000
            Log.d("KeycardRepository", "IsoDep connected, timeout set to ${isoDep.timeout}ms")
            
            val channel = NFCCardChannel(isoDep)
            Log.d("KeycardRepository", "NFCCardChannel created")
            
            // Load CommandSet reflectively
            Log.d("KeycardRepository", "Loading CommandSet class...")
            val cardChannelClass = Class.forName("im.status.keycard.io.CardChannel")
            Log.d("KeycardRepository", "CardChannel class found: ${cardChannelClass.name}")
            
            val candidateCommands = listOf(
                "im.status.keycard.applet.CommandSet",
                "im.status.keycard.applet.KeycardCommandSet",
                "im.status.keycard.applet.CardCommandSet"
            )
            
            val commandSetClass = candidateCommands.firstOrNull { name ->
                try {
                    Class.forName(name) != null
                } catch (_: Throwable) {
                    false
                }
            }?.let { Class.forName(it) } ?: run {
                Log.e("KeycardRepository", "CommandSet not found")
                return@withContext Result.failure(
                    IllegalStateException("Keycard SDK not on classpath")
                )
            }
            
            Log.d("KeycardRepository", "Using CommandSet class: ${commandSetClass.name}")
            val cmd = commandSetClass.getConstructor(cardChannelClass).newInstance(channel)
            Log.d("KeycardRepository", "CommandSet instance created")
            
            // Select applet
            Log.d("KeycardRepository", "Selecting Keycard applet...")
            commandSetClass.getMethod("select").invoke(cmd)
            Log.d("KeycardRepository", "Keycard applet selected")
            
            // Step 1: Try to unpair any existing pairings first
            tryUnpairExistingPairings(commandSetClass, cmd)
            
            // Step 2: Pair with the card
            pairWithCard(commandSetClass, cmd, pairingPassword)
            
            // Step 3: Open secure channel
            openSecureChannel(commandSetClass, cmd)
            
            // Step 4: Verify PIN
            verifyPinOnCard(commandSetClass, cmd, pin)
            
            // Step 5: Read NDEF
            val ndefBytes = readNdefFromCard(commandSetClass, cmd)
            
            // Step 6: Unpair after successful read
            tryUnpairAfterWrite(commandSetClass, cmd)
            
            Log.d("KeycardRepository", "NDEF read completed successfully (${ndefBytes.size} bytes)")
            Result.success(ndefBytes)
            
        } catch (cnf: ClassNotFoundException) {
            Log.e("KeycardRepository", "ClassNotFoundException: ${cnf.message}", cnf)
            Result.failure(IllegalStateException("Keycard SDK not on classpath: ${cnf.message}"))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            val errorMsg = cause?.message ?: e.message ?: "Unknown error"
            val errorClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val errorCode = extractErrorCode(errorMsg)
            Log.e("KeycardRepository", "Secure read exception: $errorClass - $errorMsg (error code: $errorCode)", e)
            Result.failure(Exception("Secure read exception: $errorClass - $errorMsg" + if (errorCode.isNotEmpty()) " (Error: $errorCode)" else ""))
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.toString()
            val errorClass = e.javaClass.simpleName
            Log.e("KeycardRepository", "Exception: $errorClass - $errorMsg", e)
            Result.failure(Exception("Secure read exception: $errorClass - $errorMsg"))
        } finally {
            try {
                Log.d("KeycardRepository", "Closing IsoDep connection...")
                isoDep.close()
                Log.d("KeycardRepository", "IsoDep connection closed")
            } catch (closeEx: Exception) {
                Log.d("KeycardRepository", "Error closing IsoDep: ${closeEx.message}")
            }
        }
    }
    
    /**
     * Read NDEF from card - Uses getNDEF which supports automatic chunking.
     */
    private fun readNdefFromCard(commandSetClass: Class<*>, cmd: Any): ByteArray {
        Log.d("KeycardRepository", "Reading NDEF data from card...")
        
        val getNdefMethod = commandSetClass.methods.firstOrNull { m ->
            (m.name == "getNDEF" || m.name == "getNdef") && 
            m.parameterTypes.isEmpty() &&
            m.returnType == ByteArray::class.java
        }
        
        if (getNdefMethod == null) {
            // Try alternative: methods that return data
            val alternativeMethods = commandSetClass.methods.filter { m ->
                m.name.lowercase().contains("ndef") && 
                m.name.lowercase().contains("get") &&
                m.parameterTypes.isEmpty()
            }
            if (alternativeMethods.isNotEmpty()) {
                Log.d("KeycardRepository", "Found alternative NDEF read methods: ${alternativeMethods.joinToString { it.name }}")
                val method = alternativeMethods.first()
                val result = method.invoke(cmd)
                return when (result) {
                    is ByteArray -> result
                    is String -> result.toByteArray()
                    else -> throw IllegalStateException("getNDEF returned unexpected type: ${result?.javaClass?.name}")
                }
            }
            throw IllegalStateException("getNDEF() or getNdef() method not found on CommandSet")
        }
        
        Log.d("KeycardRepository", "Calling getNDEF() method...")
        try {
            val result = getNdefMethod.invoke(cmd)
            if (result is ByteArray) {
                Log.d("KeycardRepository", "getNDEF() successful (${result.size} bytes)")
                return result
            } else {
                throw IllegalStateException("getNDEF returned unexpected type: ${result?.javaClass?.name}")
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            val ndefMsg = cause?.message ?: e.message ?: "Unknown error"
            val ndefClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            Log.e("KeycardRepository", "getNDEF() failed: $ndefMsg ($ndefClass)")
            throw cause ?: e
        }
    }
    
    /**
     * Extract error code from error message (0x... or SW=...).
     */
    private fun extractErrorCode(errorMsg: String): String {
        return when {
            errorMsg.contains("0x") -> {
                errorMsg.substring(errorMsg.indexOf("0x")).takeWhile { it.isLetterOrDigit() || it == 'x' || it == 'X' || it == ' ' }
            }
            errorMsg.contains("SW=") -> {
                errorMsg.substring(errorMsg.indexOf("SW=")).takeWhile { it.isLetterOrDigit() || it == '=' || it == ' ' }
            }
            else -> ""
        }
    }
}

