# Keycard POC Use Cases Android App

## Project Overview

This Android application demonstrates various Proof of Concept (POC) use cases for the Status Keycard beyond just hardware wallet functionality. The app provides a collection of NFC-based use cases that leverage the Keycard's secure storage and cryptographic capabilities for NDEF (NFC Data Exchange Format) operations.

The app is organized as a list of use cases, each demonstrating a different capability of the Keycard:

## Accomplishments

### ✅ Environment Setup

- **Android Studio Project**: Configured with Kotlin and Jetpack Compose
- **Minimum SDK**: Android 6.0 (API 23) for NFC support
- **Target SDK**: Android 14 (API 36)
- **Build Tools**: Gradle with Kotlin DSL

### ✅ Dependencies

#### Core Android Libraries
- **Jetpack Compose**: Modern declarative UI framework
  - `androidx.compose.bom:2024.09.00`
  - `androidx.compose.ui:ui`
  - `androidx.compose.material3:material3`
  - `androidx.activity:activity-compose:1.11.0`

#### Keycard SDK
- **Status Keycard Java SDK v3.1.2**:
  - `com.github.status-im.status-keycard-java:android:3.1.2`
  - `com.github.status-im:status-keycard-java:3.1.2`
  
  Provides:
  - `NFCCardChannel`: Android NFC transport layer
  - `CommandSet`: High-level Keycard applet commands
  - Secure channel cryptography via BouncyCastle

#### Additional Libraries
- **Kotlin Coroutines**: `1.8.0` for asynchronous operations
- **AndroidX Core KTX**: `1.17.0`
- **AndroidX Lifecycle**: `2.9.4`

## Use Cases

### 1. ✅ Write URL to NDEF (Ready)

Write any URL to an NDEF record on the Keycard, making it readable by standard NFC readers.

**User Flow:**
1. Select "Write URL to NDEF" from the main screen
2. **PIN Entry**: Enter Keycard PIN via secure password dialog
3. **Card Scanning (PIN Verification)**: Tap Keycard to verify PIN
   - App detects NFC card via ReaderMode
   - Establishes `IsoDep` connection
   - Creates `NFCCardChannel` for APDU communication
   - Verifies PIN with Keycard
4. **URL Entry**: Enter any URL (e.g., `https://example.com`)
   - App creates NDEF URI record from the URL
5. **Card Scanning (NDEF Write)**: Tap Keycard again to write NDEF
   - **Secure Channel Establishment**:
     - `CommandSet.select()` - Selects Keycard applet
     - `CommandSet.autoPair(pairingPassword)` - Pairs with card using pairing password
     - `CommandSet.autoOpenSecureChannel()` - Opens encrypted secure channel
   - **PIN Authentication**:
     - `CommandSet.verifyPIN(pin)` - Verifies user PIN on card
   - **NDEF Write**:
     - `CommandSet.setNDEF(ndefBytes)` - Writes NDEF record to card
     - Automatically adds 2-byte length prefix (as per Keycard SDK spec)
6. **Success Display**: Shows NDEF hex payload (length prefix + NDEF message)

### 2. Write VC to NDEF (Coming Soon)

Write Verifiable Credentials to NDEF records on the Keycard.

### 3. Read VC from NDEF (Coming Soon)

Read and verify Verifiable Credentials from NDEF records on the Keycard.

### 4. Sign data and write to NDEF (Coming Soon)

Sign data using Keycard's private keys and write the signed data to NDEF records.

### 5. Read signed data from NDEF (Coming Soon)

Read and verify signed data from NDEF records on the Keycard.

### ✅ Technical Implementation Highlights

#### Secure Channel Flow
Following the [Keycard SDK Secure Channel documentation](https://keycard.tech/developers/sdk/securechannel):

```kotlin
// 1. Select applet
cmd.select()

// 2. Pair with card (sets pairing info in CommandSet)
cmd.autoPair(pairingPassword)

// 3. Open secure channel (uses pairing info already set)
cmd.autoOpenSecureChannel()

// 4. Verify PIN
cmd.verifyPIN(pin)

// 5. Write NDEF (adds 2-byte length prefix automatically)
cmd.setNDEF(ndefBytes)
```

#### NFC Communication
- **ReaderMode**: Active listening for NFC tags (more reliable than intent-based)
- **IsoDep**: ISO 14443-4 communication protocol
- **Timeout**: 120 seconds for card operations
- **Error Handling**: Comprehensive logging and user feedback

#### UI/UX Features
- Real-time status messages
- On-screen operation logs
- Secure PIN input (masked)
- Connection state indicators ("Searching...", "Connection established...")
- NDEF hex output display

## Next Steps

### 🔐 Explore Signing Data with Keycard Keys

**Goal**: Sign data inside the app using private keys stored on the Keycard and make the signed data available to NFC readers.

**Implementation Approach**:
- Use `CommandSet.sign()` or `CommandSet.signWithPath()` methods
- Derive signing key from Keycard using `CommandSet.deriveKey()`
- Create signed NDEF records containing:
  - Original data
  - Digital signature
  - Public key for verification
- Write signed NDEF to card for NFC readers to verify

**Use Cases**:
- Authenticated profile links
- Signed credentials
- Tamper-proof data storage

### 📜 Write Simple Verifiable Credentials (VCs)

**Goal**: Store Verifiable Credentials on the Keycard and enable apps/websites to read and verify them.

**Implementation Approach**:
1. **VC Creation**:
   - Define simple VC schema (e.g., profile credential, membership credential)
   - Include: issuer, subject, claims, expiration
   - Format as JSON-LD or JSON

2. **VC Signing**:
   - Sign VC with Keycard private key
   - Embed signature in VC document
   - Create proof object with public key reference

3. **VC Storage**:
   - Store signed VC as NDEF record on Keycard
   - Include verification metadata (public key, signature algorithm)

4. **VC Verification**:
   - NFC reader extracts VC from NDEF
   - App/website verifies signature using embedded public key
   - Validates VC structure and expiration
   - Displays credential claims

**Technical Considerations**:
- Use W3C Verifiable Credentials standard format
- Support JSON-LD or JWT VC formats
- Implement signature verification (ECDSA, EdDSA)
- Handle credential expiration and revocation
- Consider privacy-preserving selective disclosure

**Potential VC Types**:
- Profile credentials (Funding The Commons profile)
- Membership credentials
- Event attendance credentials
- Achievement badges

## Project Structure

```
keycardapp/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/keycardapp/
│   │       │   └── MainActivity.kt          # Main NFC & Keycard logic
│   │       ├── AndroidManifest.xml          # NFC permissions
│   │       └── res/                         # UI resources
│   └── build.gradle.kts                     # App dependencies
├── gradle/
│   └── libs.versions.toml                  # Version catalog
├── PythonKeycard/
│   ├── keycard-ndef.py                     # Python reference implementation
│   └── readme.txt
└── README.md                                # This file
```

## Building and Running

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK with API 23+ and 36
- Physical Android device with NFC support (emulators don't support NFC)

### Build Steps
1. Clone the repository
2. Open in Android Studio
3. Sync Gradle dependencies
4. Connect NFC-enabled Android device
5. Enable NFC on device
6. Run the app

### Usage
1. Launch the app
2. You'll see a list of use cases on the main screen
3. Select "Write URL to NDEF" (or any other use case)
4. For "Write URL to NDEF":
   - Enter your Keycard PIN when prompted
   - Tap your Keycard to verify PIN
   - Enter any URL you want to write
   - Tap your Keycard again to write NDEF data
   - Verify the NDEF hex output matches expectations

## References

- [Keycard SDK Documentation](https://keycard.tech/docs/sdk)
- [Keycard Secure Channel Guide](https://keycard.tech/developers/sdk/securechannel)
- [Status Keycard Java SDK](https://github.com/status-im/status-keycard-java)
- [NDEF Specification](https://developer.android.com/reference/android/nfc/NdefMessage)
- [W3C Verifiable Credentials](https://www.w3.org/TR/vc-data-model/)

## Project Status

✅ **Use Case 1 Complete**: Write URL to NDEF  
📋 **Use Case 2 Planned**: Write VC to NDEF  
📋 **Use Case 3 Planned**: Read VC from NDEF  
📋 **Use Case 4 Planned**: Sign data and write to NDEF  
📋 **Use Case 5 Planned**: Read signed data from NDEF

---

*Last Updated: November 2025*

