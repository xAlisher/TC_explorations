# Project Update: Keycard NDEF Writer Android App

## 🎉 Major Milestone Achieved!

We've successfully built an Android application that enables secure NFC communication with Status Keycard hardware wallets, allowing users to write NDEF records to their Keycard that are readable by standard NFC readers.

## What We Built

**Complete Secure Channel Implementation**: The app establishes a secure encrypted channel with the Keycard using the official Keycard SDK, following the secure channel protocol for mutual authentication.

**User Flow**:
1. User enters their Keycard PIN
2. App verifies PIN by scanning the card
3. User enters their Funding The Commons profile ID
4. App writes the profile URL (`https://platform.fundingthecommons.io/profiles/{id}`) to the Keycard
5. Data is readable by any NFC reader

## Technical Highlights

- **Status Keycard Java SDK v3.1.2** integration
- **Secure Channel**: Full pairing and encrypted communication
- **NFC ReaderMode**: Active listening for reliable card detection
- **Jetpack Compose**: Modern Android UI
- **NDEF Writing**: Secure storage of profile URLs on Keycard

## Repository

All code is open-source and available on GitHub: https://github.com/xAlisher/TC_explorations

## Next Steps

🚧 **In Progress**: Signing data with Keycard private keys  
📋 **Planned**: Verifiable Credentials support for tamper-proof credentials

---

*This project enables users to carry their Funding The Commons profile on their Keycard, making it accessible via NFC while maintaining security through hardware wallet protection.*

