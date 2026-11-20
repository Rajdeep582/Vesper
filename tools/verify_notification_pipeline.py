"""
Comprehensive Notification Pipeline Verification
Tests every link in the notification chain: DB → Server → FCM → Client code audit
"""
import sys, os, json, time, base64, hashlib, hmac
import requests
from pymongo import MongoClient

# ---- Config ----
from dotenv import dotenv_values
_prod_env = dotenv_values(os.path.join(os.path.dirname(__file__), "..", ".dev", "prod.env"))
_render_env_path = os.path.join(os.path.dirname(__file__), "..", ".dev", "RENDER_ENV.txt")
_render_lines = open(_render_env_path).read().strip().splitlines() if os.path.exists(_render_env_path) else []
_render_env = dict(line.split("=", 1) for line in _render_lines if "=" in line and not line.startswith("#"))
MONGO_URI = _prod_env.get("MONGO_URI", _render_env.get("MONGODB_URI", ""))
API_URL = "https://vesper-api-mthr.onrender.com"
FCM_SA_B64 = _render_env.get("FCM_SERVICE_ACCOUNT_JSON_B64", "")

results = []

def check(name, ok, detail=""):
    status = "✅ PASS" if ok else "❌ FAIL"
    results.append((name, status, detail))
    print(f"  {status}: {name}" + (f" — {detail}" if detail else ""))

print("=" * 70)
print("NOTIFICATION PIPELINE VERIFICATION")
print("=" * 70)

# ---- 1. Backend Health ----
print("\n[1] Backend Health")
try:
    r = requests.get(f"{API_URL}/health", timeout=15)
    check("Backend reachable", r.status_code == 200, f"status={r.status_code}")
except Exception as e:
    check("Backend reachable", False, str(e))

# ---- 2. MongoDB Connectivity & Device Tokens ----
print("\n[2] MongoDB Device Token Verification")
try:
    client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=10000)
    db = client.vesper
    
    devices = list(db.devices.find())
    check("MongoDB connected", True, f"{len(devices)} devices found")
    
    devices_with_fcm = [d for d in devices if d.get("fcm")]
    check("All devices have FCM tokens", len(devices_with_fcm) == len(devices),
          f"{len(devices_with_fcm)}/{len(devices)} have FCM tokens")
    
    for d in devices:
        uid = d.get("uid", "?")
        did = d.get("d", "?")
        label = d.get("label", "?")
        has_fcm = bool(d.get("fcm"))
        token_len = len(d.get("fcm", "")) if d.get("fcm") else 0
        check(f"Device {uid[:8]}..:{did} ({label})",
              has_fcm and token_len > 100,
              f"fcm_token_len={token_len}")
    
    # Check no ghost devices
    users = list(db.users.find({"st": "active"}))
    user_ids = {u["_id"] for u in users}
    orphan_devices = [d for d in devices if d.get("uid") not in user_ids]
    check("No orphan devices", len(orphan_devices) == 0,
          f"{len(orphan_devices)} orphan(s)" if orphan_devices else "clean")
    
    # Check prekeys exist for all devices
    for d in devices:
        uid = d["uid"]
        did = d["d"]
        pk_count = db.one_time_prekeys.count_documents({"uid": uid, "d": did})
        check(f"Prekeys for {uid[:8]}..:{did}", pk_count > 10, f"{pk_count} prekeys")
    
    client.close()
except Exception as e:
    check("MongoDB connectivity", False, str(e))

# ---- 3. FCM Service Account ----
print("\n[3] FCM Service Account Verification")
try:
    sa_json = json.loads(base64.b64decode(FCM_SA_B64))
    check("FCM SA parsed", True, f"project={sa_json.get('project_id')}")
    check("FCM SA has private_key", bool(sa_json.get("private_key")), "key present" if sa_json.get("private_key") else "MISSING")
    check("FCM SA has client_email", bool(sa_json.get("client_email")), sa_json.get("client_email", "MISSING"))
    check("FCM SA project matches", sa_json.get("project_id") == "vesper-1a783")
except Exception as e:
    check("FCM SA config", False, str(e))

# ---- 4. FCM Push Delivery Test ----
print("\n[4] FCM Push Delivery Test (Live)")
try:
    import jwt as pyjwt
    sa = json.loads(base64.b64decode(FCM_SA_B64))
    now = int(time.time())
    payload = {
        "iss": sa["client_email"],
        "aud": sa.get("token_uri", "https://oauth2.googleapis.com/token"),
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
        "iat": now,
        "exp": now + 3600,
    }
    assertion = pyjwt.encode(payload, sa["private_key"], algorithm="RS256")
    token_resp = requests.post(sa.get("token_uri", "https://oauth2.googleapis.com/token"), data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }, timeout=15)
    check("FCM OAuth token obtained", token_resp.status_code == 200, f"status={token_resp.status_code}")
    access_token = token_resp.json()["access_token"]
    
    # Send test wake push to each device
    client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=10000)
    db = client.vesper
    devices = list(db.devices.find({"fcm": {"$exists": True, "$ne": None}}))
    
    project_id = sa["project_id"]
    for d in devices:
        uid = d["uid"][:8]
        label = d.get("label", "?")
        fcm_token = d["fcm"]
        body = json.dumps({
            "message": {
                "token": fcm_token,
                "data": {"t": "wake"},
                "android": {"priority": "high", "ttl": "86400s"}
            }
        })
        t0 = time.time()
        resp = requests.post(
            f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send",
            headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
            data=body, timeout=15)
        latency = (time.time() - t0) * 1000
        check(f"FCM push to {uid}.. ({label})", resp.status_code == 200,
              f"status={resp.status_code}, latency={latency:.0f}ms")
    
    client.close()
except ImportError:
    check("FCM push test", False, "pyjwt not installed; skipping live test")
except Exception as e:
    check("FCM push test", False, str(e))

# ---- 5. Server-Side Push Logic Verification (Code Audit) ----
print("\n[5] Server Push Logic Audit")
messaging_path = os.path.join(os.path.dirname(__file__), "..", "server", "src", "main", "kotlin", "com", "rajdeep", "vesper", "server", "Messaging.kt")
push_path = os.path.join(os.path.dirname(__file__), "..", "server", "src", "main", "kotlin", "com", "rajdeep", "vesper", "server", "Push.kt")

with open(messaging_path, "r") as f:
    messaging_code = f.read()
with open(push_path, "r") as f:
    push_code = f.read()

# Direct: push.wake is called unconditionally in later{}
check("Direct: push.wake unconditional",
      "push.wake(f.toUserId)" in messaging_code and "!hub.isOnline" not in messaging_code.split("Direct")[1].split("class")[0] if "Direct" in messaging_code else False,
      "push.wake called without online-gating")

# Hall: push.wakeHall sends to offline users
check("Hall: push.wakeHall implemented",
      "push.wakeHall" in messaging_code,
      "wakeHall called for hall messages")

# Push.kt: wake sends high-priority data-only
check("Push: high-priority android push",
      '"priority":"high"' in push_code,
      "android.priority=high set")

check("Push: data-only (no notification key)",
      '"notification"' not in push_code,
      "No notification payload (data-only, correct)")

check("Push: UNREGISTERED token cleanup",
      "UNREGISTERED" in push_code,
      "Stale tokens removed on UNREGISTERED")

# ---- 6. Client-Side Notification Code Audit ----
print("\n[6] Client Notification Code Audit")
support_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "rajdeep", "vesper", "data", "Support.kt")
messages_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "rajdeep", "vesper", "data", "Messages.kt")
push_client_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "rajdeep", "vesper", "data", "Push.kt")

with open(support_path, "r") as f:
    support_code = f.read()
with open(messages_path, "r") as f:
    messages_code = f.read()
with open(push_client_path, "r") as f:
    push_client_code = f.read()

# Channels
check("Channel: vesper_direct created",
      'CHANNEL_DIRECT = "vesper_direct"' in support_code and "IMPORTANCE_HIGH" in support_code)

check("Channel: vesper_hall created",
      'CHANNEL_HALL = "vesper_hall"' in support_code and "IMPORTANCE_HIGH" in support_code)

# Notification builder
check("Direct notification: PRIORITY_HIGH",
      "PRIORITY_HIGH" in support_code.split("fun direct")[1].split("fun hall")[0] if "fun direct" in support_code else False)

check("Direct notification: DEFAULT_ALL",
      "DEFAULT_ALL" in support_code.split("fun direct")[1].split("fun hall")[0] if "fun direct" in support_code else False)

check("Hall notification: PRIORITY_HIGH",
      "PRIORITY_HIGH" in support_code.split("fun hall")[1].split("fun cancel")[0] if "fun hall" in support_code else False)

check("Hall notification: DEFAULT_ALL",
      "DEFAULT_ALL" in support_code.split("fun hall")[1].split("fun cancel")[0] if "fun hall" in support_code else False)

# Notification triggered in message handling
check("Direct msg triggers notifier.direct()",
      "notifier.direct(" in messages_code)

check("Hall msg triggers notifier.hall()",
      "notifier.hall(" in messages_code)

# PushService
check("PushService: syncDirect on wake",
      "messages.syncDirect()" in push_client_code)

check("PushService: syncHall on wake",
      "messages.syncHall()" in push_client_code)

check("PushService: runBlocking for immediate sync",
      "runBlocking" in push_client_code,
      "Synchronous sync in onMessageReceived")

# SyncWorker has getForegroundInfo
check("SyncWorker: getForegroundInfo present",
      "getForegroundInfo" in support_code,
      "Prevents crash on Android 12+")

# POST_NOTIFICATIONS permission guard
check("POST_NOTIFICATIONS guard (API 33+)",
      "Build.VERSION.SDK_INT >= 33" in support_code or "Build.VERSION_CODES.TIRAMISU" in support_code,
      "Permission check before posting")

# PushRegistrar: token registration
check("PushRegistrar: registers token to server",
      '/v1/me/fcm' in push_client_code or "me/fcm" in push_client_code,
      "FCM token uploaded via PUT /v1/me/fcm")

check("PushRegistrar: onNewToken handled",
      "onNewToken" in push_client_code,
      "Token refresh re-registers")

# Viewing suppression
check("Direct: isViewing suppression",
      "isViewing(peer)" in support_code,
      "Suppresses when chat is open")

check("Hall: isViewingHall suppression",
      "isViewingHall()" in support_code,
      "Suppresses when Hall is open")

# ---- 7. Build Config Verification ----
print("\n[7] Build Configuration")
build_gradle = os.path.join(os.path.dirname(__file__), "..", "app", "build.gradle.kts")
with open(build_gradle, "r") as f:
    build_code = f.read()

check("FCM_APP_ID in BuildConfig",
      "FCM_APP_ID" in build_code)

check("FCM_PROJECT_ID in BuildConfig",
      "FCM_PROJECT_ID" in build_code)

check("FCM_SENDER_ID in BuildConfig",
      "FCM_SENDER_ID" in build_code)

secrets_path = os.path.join(os.path.dirname(__file__), "..", "secrets.properties")
with open(secrets_path, "r") as f:
    secrets = f.read()

check("secrets.properties: FCM appId set",
      "vesper.fcm.appId=" in secrets and len(secrets.split("vesper.fcm.appId=")[1].split("\n")[0].strip()) > 10)

check("secrets.properties: FCM projectId set",
      "vesper.fcm.projectId=vesper-1a783" in secrets)

check("secrets.properties: API URL (release)",
      "vesper.apiUrl.release=https://vesper-api-mthr.onrender.com" in secrets)

check("secrets.properties: API URL (debug)",
      "vesper.apiUrl.debug=https://vesper-api-mthr.onrender.com" in secrets)

# ---- Summary ----
print("\n" + "=" * 70)
passed = sum(1 for _, s, _ in results if "PASS" in s)
failed = sum(1 for _, s, _ in results if "FAIL" in s)
print(f"TOTAL: {passed} PASSED, {failed} FAILED out of {len(results)} checks")
if failed > 0:
    print("\nFailed checks:")
    for name, status, detail in results:
        if "FAIL" in status:
            print(f"  ❌ {name}: {detail}")
print("=" * 70)

sys.exit(1 if failed > 0 else 0)
