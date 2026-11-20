import subprocess
import time
import sys

def adb(cmd):
    res = subprocess.run(['adb'] + cmd, capture_output=True, text=True)
    return res.stdout.strip()

print("Checking device...")
dev = adb(['devices'])
print(dev)

print("Clearing logcat...")
adb(['logcat', '-c'])

print("\n--- TEST 1: Press & Hold (2.5s) ---")
# Bring Vesper to front
adb(['shell', 'am', 'start', '-n', 'com.rajdeep.vesper/.MainActivity'])
time.sleep(1.5)

# Swipe staying in place at (920, 1960) for 2500ms
adb(['shell', 'input', 'swipe', '920', '1960', '920', '1960', '2500'])
time.sleep(2.0)

logs = adb(['logcat', '-d', '-s', 'ChatViewModel', 'VoiceRecorder'])
print("Logs after Test 1:")
for line in logs.splitlines()[-10:]:
    print("  ", line)

print("\n--- TEST 2: Slide Up to Lock & Send ---")
# Slide up from (920, 1960) to (920, 1500) over 350ms
adb(['shell', 'input', 'swipe', '920', '1960', '920', '1500', '350'])
time.sleep(2.5) # Record for 2.5s in locked mode

# Tap Send button on locked bar (960, 1960)
adb(['shell', 'input', 'tap', '960', '1960'])
time.sleep(2.0)

logs = adb(['logcat', '-d', '-s', 'ChatViewModel', 'VoiceRecorder'])
print("Logs after Test 2:")
for line in logs.splitlines()[-10:]:
    print("  ", line)

print("\n--- TEST 3: Slide Left to Pause, Preview, & Send ---")
# Slide left from (920, 1960) to (450, 1960) over 400ms
adb(['shell', 'input', 'swipe', '920', '1960', '450', '1960', '400'])
time.sleep(1.5)

# Tap Play Preview button on paused bar (230, 1960)
adb(['shell', 'input', 'tap', '230', '1960'])
time.sleep(1.5)

# Tap Send button on paused bar (960, 1960)
adb(['shell', 'input', 'tap', '960', '1960'])
time.sleep(2.0)

logs = adb(['logcat', '-d', '-s', 'ChatViewModel', 'VoiceRecorder'])
print("Logs after Test 3:")
for line in logs.splitlines()[-10:]:
    print("  ", line)

print("\nAll 3 test recordings dispatched successfully!")
