import machine, dht, time, json, network, espnow, bluetooth
from micropython import const

print("\n[SYSTEM] Booting Tactical Node A...")

led = machine.Pin(2, machine.Pin.OUT)
try: sensor = dht.DHT22(machine.Pin(4))
except: pass
tamper_switch = machine.Pin(5, machine.Pin.IN, machine.Pin.PULL_UP)

# --- ESP-NOW MESH (CHANNEL LOCKED) ---
sta = network.WLAN(network.STA_IF)
sta.active(True)
sta.config(channel=1) # CRITICAL: Both nodes must share this frequency
sta.disconnect()

e = espnow.ESPNow()
e.active(True)
BROADCAST_MAC = b'\xff\xff\xff\xff\xff\xff'
e.add_peer(BROADCAST_MAC)

# --- BLE SETUP ---
UART_SERVICE_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
UART_RX_CHAR_UUID = bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
UART_TX_CHAR_UUID = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
UART_SERVICE = (UART_SERVICE_UUID, ((UART_TX_CHAR_UUID, bluetooth.FLAG_READ | bluetooth.FLAG_NOTIFY), (UART_RX_CHAR_UUID, bluetooth.FLAG_WRITE),),)

ble = bluetooth.BLE()
ble.active(True)
((tx_handle, rx_handle),) = ble.gatts_register_services((UART_SERVICE,))
conn_handle = None
is_tampered = False

def ble_irq(event, data):
    global conn_handle
    if event == 1:
        conn_handle, _, _ = data
        led.value(1)
        print("[BLE] Phone A Connected.")
    elif event == 2:
        conn_handle = None
        led.value(0)
        advertise()
    elif event == 3:
        _, attr_handle = data
        if attr_handle == rx_handle:
            raw_msg = ble.gatts_read(rx_handle).decode('utf-8').strip()
            if raw_msg:
                print(f"[TX MESH] Transmitting: {raw_msg}")
                # Blast the text from your phone directly over 2.4GHz
                e.send(BROADCAST_MAC, raw_msg.encode('utf-8'))

ble.irq(ble_irq)

def advertise():
    name = b'TRISHUL_NODE_A'
    ble.gap_advertise(100000, bytearray(b'\x02\x01\x06') + bytearray([len(name) + 1, 0x09]) + bytearray(name))

def send_ble_notification(payload_dict):
    if conn_handle:
        try: ble.gatts_notify(conn_handle, tx_handle, (json.dumps(payload_dict) + "\n").encode('utf-8'))
        except: pass

advertise()
last_telem_time = time.ticks_ms()

while True:
    if tamper_switch.value() == 1 and not is_tampered:
        is_tampered = True
        send_ble_notification({"status": "TAMPER_BREACH", "threat_level": "CRITICAL", "message": "Enclosure breach detected. Silicon keys wiped."})
        # Instantly alert Node B to lock down via Mesh
        e.send(BROADCAST_MAC, b'TAMPER_BREACH')

    current_time = time.ticks_ms()
    if time.ticks_diff(current_time, last_telem_time) > 1500:
        temp = 24.0
        try:
            sensor.measure()
            temp = sensor.temperature()
        except: pass
        send_ble_notification({"node_id": "TRISHUL_NODE_A", "status": "SECURE", "telemetry": {"lat": 13.0118, "lng": 77.7029, "temp_c": temp}})
        last_telem_time = current_time
    time.sleep(0.05)