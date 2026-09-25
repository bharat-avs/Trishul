import machine, network, espnow, bluetooth, json, time, neopixel
from micropython import const

# --- VISUAL & AUDIO HARDWARE ---
NUM_PIXELS = 8
np = neopixel.NeoPixel(machine.Pin(13), NUM_PIXELS)

def set_ring_color(r, g, b):
    for i in range(NUM_PIXELS): np[i] = (r, g, b)
    np.write()

buzzer = machine.Pin(14, machine.Pin.OUT)
buzzer.value(0)

def beep(duration=0.1, count=1):
    for _ in range(count):
        buzzer.value(1)
        time.sleep(duration)
        buzzer.value(0)
        time.sleep(duration)

# Boot visual
set_ring_color(0, 0, 50)
beep(0.1, 2)
set_ring_color(0, 50, 0)

# --- ESP-NOW ---
sta = network.WLAN(network.STA_IF)
sta.active(True)
sta.config(channel=1)
sta.disconnect()

e = espnow.ESPNow()
e.active(True)

# --- BLE GATT SERVER ---
_IRQ_CENTRAL_CONNECT = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)

UART_SERVICE_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
UART_TX_CHAR_UUID = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
UART_SERVICE = (
    UART_SERVICE_UUID,
    ((UART_TX_CHAR_UUID, bluetooth.FLAG_READ | bluetooth.FLAG_NOTIFY),),
)

ble = bluetooth.BLE()
ble.active(True)
((tx_handle,),) = ble.gatts_register_services((UART_SERVICE,))
conn_handle = None
node_compromised = False

def ble_irq(event, data):
    global conn_handle
    if event == _IRQ_CENTRAL_CONNECT:
        conn_handle, _, _ = data
        print("\n[BLE] >>> PHONE 2 CONNECTED <<<")
    elif event == _IRQ_CENTRAL_DISCONNECT:
        conn_handle = None
        print("\n[BLE] <<< PHONE 2 DISCONNECTED >>>")
        advertise()

ble.irq(ble_irq)

def advertise():
    name = b'TRISHUL_NODE_B'
    ble.gap_advertise(100000, bytearray(b'\x02\x01\x06') + bytearray([len(name) + 1, 0x09]) + bytearray(name))

advertise()
print("[NODE B] Active. Ring Green. Listening for Mesh traffic...")

while True:
    host, msg = e.recv(10)
    if msg:
        decoded = msg.decode('utf-8').strip()
        print(f"[RX MESH] Incoming: {decoded}")

        if "REVOKE" in decoded or "TAMPER" in decoded:
            node_compromised = True
            set_ring_color(50, 0, 0)
            buzzer.value(1)
        elif not node_compromised:
            set_ring_color(0, 0, 50)
            beep(0.1, 1)
            set_ring_color(0, 50, 0)

        # Notify Phone 2
        if conn_handle is not None:
            payload = {
                "status": "COMPROMISED" if node_compromised else "SECURE",
                "node_id": "TRISHUL_NODE_B",
                "incoming_comms": decoded
            }
            raw_data = (json.dumps(payload) + "\n").encode('utf-8')
            try:
                ble.gatts_notify(conn_handle, tx_handle, raw_data)
                print(f"[BLE NOTIFY SENT TO PHONE 2]: {decoded}")
            except Exception as ex:
                print(f"[BLE ERROR]: {ex}")
    time.sleep(0.01)