from keycard.keycard import KeyCard
import sys

def get_new_pin():
    try:
        import tkinter.simpledialog
    except ImportError:
        raise RuntimeError("Tkinter enabled version of Python required")

    def is_six_digits(s):
        return isinstance(s, str) and s.isdigit() and len(s) == 6

    while True:
        pin = tkinter.simpledialog.askstring("Keycard PIN", "Enter new 6-digit PIN:", show='*')
        if pin is None:
            raise Exception("PIN entry cancelled.")
        if is_six_digits(pin):
            return pin

# --- 1. NEW SECRETS FOR THE CARD ---
NEW_PIN = get_new_pin()
NEW_PUK = "123456789012"
NEW_PAIRING_PASSWORD = "MyNewCardPassword"
# ------------------------------------

try:
    print("Connecting to Keycard... Place your 'empty card' in the reader.")
    card = KeyCard()
    card.select()
    
    print("Card selected. Initializing with new secrets...")
    
    # init() takes the secrets as arguments
    card.init(NEW_PIN, NEW_PUK, NEW_PAIRING_PASSWORD)
    
    print("\n--- SUCCESS: CARD INITIALIZED ---")
    print("Your new credentials are:")
    print(f"  PIN:              {NEW_PIN}")
    print(f"  PUK:              {NEW_PUK}")
    print(f"  Pairing Password: {NEW_PAIRING_PASSWORD}")
    print("\nGo to Step 2.")

except Exception as e:
    print(f"\n--- ERROR ---")
    print(f"An error occurred: {e}")

finally:
    try:
        card.close()
        print("Connection closed.")
    except:
        pass