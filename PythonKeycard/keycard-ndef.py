import argparse
from keycard.keycard import KeyCard
from keycard.constants import StorageSlot

def get_pin():
    try:
        import tkinter.simpledialog
    except ImportError:
        raise "Tkinter enabled version of Python required"
    return tkinter.simpledialog.askstring("Keycard PIN", "Enter PIN:", show='*')

def keycard_set_ndef(data):
    print(data)
    with KeyCard() as card:
        card.select()
        pairing_index, pairing_key = card.pair("MyNewCardPassword")
        card.open_secure_channel(pairing_index, pairing_key)

        pin = get_pin()

        while not card.verify_pin(pin):
            pin = get_pin()
        
        try:
            card.store_data(data, StorageSlot.NDEF)
        finally:
            card.unpair(pairing_index)

def main():
    parser = argparse.ArgumentParser(description='Set Keycard NDEF record')
    parser.add_argument('-d', '--hex-data', help="the hex representation of the NDEF record")
    args = parser.parse_args()
    keycard_set_ndef(bytes.fromhex(args.hex_data))

if __name__ == "__main__":
    main()