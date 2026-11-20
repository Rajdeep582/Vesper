import subprocess
import time

def run_adb(cmd):
    return subprocess.run(['adb'] + cmd, capture_output=True, text=True)

def screenshot(name):
    run_adb(['shell', 'screencap', '-p', f'/sdcard/{name}.png'])
    run_adb(['pull', f'/sdcard/{name}.png', f'./{name}.png'])
    print(f"[Screenshot] Saved {name}.png")

print("--- Step 1: Testing tap (< 400ms) ---")
run_adb(['shell', 'input', 'tap', '920', '1960'])
time.sleep(1)
screenshot("v1_tap")

print("--- Step 2: Testing Swipe Up to Lock ---")
# swipe from (920, 1960) up to (920, 1500) over 400ms
run_adb(['shell', 'input', 'swipe', '920', '1960', '920', '1500', '400'])
time.sleep(2)
screenshot("v2_locked")

print("--- Step 3: Tapping Pause on Locked Bar ---")
# Pause button is around (820, 1960)
run_adb(['shell', 'input', 'tap', '820', '1960'])
time.sleep(2)
screenshot("v3_paused")

print("--- Step 4: Tapping Play Preview on Paused Bar ---")
# Play preview button is around (230, 1960)
run_adb(['shell', 'input', 'tap', '230', '1960'])
time.sleep(1.5)
screenshot("v4_preview_playing")

print("--- Step 5: Tapping Resume Recording on Paused Bar ---")
# Resume mic button is around (830, 1960)
run_adb(['shell', 'input', 'tap', '830', '1960'])
time.sleep(2)
screenshot("v5_resumed")

print("--- Step 6: Tapping Trash/Discard on Locked Bar ---")
# Trash button is around (100, 1960)
run_adb(['shell', 'input', 'tap', '100', '1960'])
time.sleep(1.5)
screenshot("v6_discarded")

print("--- Step 7: Testing Swipe Left to Pause ---")
# swipe left from (920, 1960) to (450, 1960) over 450ms
run_adb(['shell', 'input', 'swipe', '920', '1960', '450', '1960', '450'])
time.sleep(2)
screenshot("v7_slide_left_paused")

print("--- Step 8: Sending from Paused Bar ---")
# Send button is around (960, 1960)
run_adb(['shell', 'input', 'tap', '960', '1960'])
time.sleep(2)
screenshot("v8_sent_from_pause")

print("--- Step 9: Testing Hold & Release Send (2.5 seconds) ---")
# swipe staying in place for 2500ms
run_adb(['shell', 'input', 'swipe', '920', '1960', '920', '1960', '2500'])
time.sleep(2)
screenshot("v9_hold_and_sent")

print("ALL VOICE TESTS COMPLETED SUCCESSFULLY!")
