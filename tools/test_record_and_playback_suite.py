import subprocess
import time

def adb(cmd):
    res = subprocess.run(['adb'] + cmd, capture_output=True, text=True)
    return res.stdout.strip()

print("==================================================")
print("VOICE RECORDING ACTIVATION SOUND VERIFICATION")
print("==================================================")

adb(['shell', 'am', 'start', '-n', 'com.rajdeep.vesper/.MainActivity'])
time.sleep(1.5)

# Clear logcat to track new recordings
adb(['logcat', '-c'])

print("\n[Sample 1] Testing Press & Hold (3.0s)...")
# Hold button at (920, 1960) for 3000ms
adb(['shell', 'input', 'swipe', '920', '1960', '920', '1960', '3000'])
time.sleep(2.5)

log1 = adb(['logcat', '-d', '-s', 'ChatViewModel'])
print("Sample 1 Logcat:")
for l in log1.splitlines()[-5:]:
    print("  ", l)

print("\n[Sample 2] Testing Slide Up to Lock (record 3s, send)...")
# Slide up from (920, 1960) to (920, 1500) over 300ms
adb(['shell', 'input', 'swipe', '920', '1960', '920', '1500', '300'])
time.sleep(3.0)

# Tap Send on locked bar (960, 1960)
adb(['shell', 'input', 'tap', '960', '1960'])
time.sleep(2.5)

log2 = adb(['logcat', '-d', '-s', 'ChatViewModel'])
print("Sample 2 Logcat:")
for l in log2.splitlines()[-5:]:
    print("  ", l)

print("\n[Sample 3] Testing Locked Bar Pause & Resume (then send)...")
# Slide up to lock
adb(['shell', 'input', 'swipe', '920', '1960', '920', '1500', '300'])
time.sleep(1.5)

# Tap Pause button (820, 1960)
adb(['shell', 'input', 'tap', '820', '1960'])
time.sleep(1.5)

# Tap Resume button (830, 1960)
adb(['shell', 'input', 'tap', '830', '1960'])
time.sleep(2.0)

# Tap Send button (960, 1960)
adb(['shell', 'input', 'tap', '960', '1960'])
time.sleep(2.5)

log3 = adb(['logcat', '-d', '-s', 'ChatViewModel'])
print("Sample 3 Logcat:")
for l in log3.splitlines()[-5:]:
    print("  ", l)

print("\n[Playback Test] Dumping UI hierarchy and testing playback...")
adb(['shell', 'uiautomator', 'dump', '/sdcard/uidump_pb.xml'])
adb(['pull', '/sdcard/uidump_pb.xml', './uidump_pb.xml'])

import xml.etree.ElementTree as ET
tree = ET.parse('uidump_pb.xml')
play_buttons = []
for node in tree.iter():
    if node.attrib.get('content-desc') == 'Play voice message':
        bounds = node.attrib.get('bounds')
        play_buttons.append(bounds)

print(f"Found {len(play_buttons)} voice message play buttons in chat.")
if play_buttons:
    # Play the newest voice message
    last_bounds = play_buttons[-1]
    print(f"Playing newest voice message at {last_bounds}...")
    import re
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', last_bounds)
    if m:
        cx = (int(m.group(1)) + int(m.group(3))) // 2
        cy = (int(m.group(2)) + int(m.group(4))) // 2
        adb(['shell', 'input', 'tap', str(cx), str(cy)])
        time.sleep(2.0)
        pb_logs = adb(['logcat', '-d', '-s', 'AudioTrack'])
        print("AudioTrack logs:")
        for l in pb_logs.splitlines()[-6:]:
            print("  ", l)

print("\n==================================================")
print("VERIFICATION COMPLETE!")
print("==================================================")
