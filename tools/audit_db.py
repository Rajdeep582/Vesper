from pymongo import MongoClient
from dotenv import dotenv_values
import datetime

env = dotenv_values('.dev/prod.env')
client = MongoClient(env['MONGO_URI'])
db = client[env.get('MONGO_DB', 'vesper')]

print("==================== DATABASE INTEGRITY AUDIT ====================")

# 1. Users Audit
print("\n--- 1. USERS ---")
valid_uids = set()
for u in db.users.find():
    valid_uids.add(u['_id'])
    print(f"UID: {u['_id']}, User: {u.get('u')}, Role: {u.get('role')}, Status: {u.get('st')}, Joined: {u.get('at')}")

# 2. Devices Audit
print("\n--- 2. DEVICES ---")
valid_device_keys = set()
for d in db.devices.find():
    valid_device_keys.add((d['uid'], d['d']))
    print(f"Device: {d['_id']}, UID: {d['uid']}, d: {d['d']}, Label: {d.get('label')}, Seen: {d.get('seen')}, HasFCM: {bool(d.get('fcm'))}")
    if d['uid'] not in valid_uids:
        print(f"  [ORPHAN DEVICE] User {d['uid']} does not exist in users collection!")

# 3. Refresh Tokens Audit
print("\n--- 3. REFRESH TOKENS ---")
orphaned_tokens = 0
active_tokens = 0
now = datetime.datetime.now(datetime.timezone.utc)
for rt in db.refresh_tokens.find():
    dev_key = (rt.get('uid'), rt.get('d'))
    exp = rt.get('exp')
    if exp and exp.tzinfo is None:
        exp = exp.replace(tzinfo=datetime.timezone.utc)
    is_expired = exp and exp < now
    if dev_key not in valid_device_keys:
        orphaned_tokens += 1
        print(f"  [ORPHAN REFRESH TOKEN] UID={rt.get('uid')}, d={rt.get('d')}, used={rt.get('used')}, exp={exp}")
    else:
        active_tokens += 1
print(f"Total Refresh Tokens: {active_tokens + orphaned_tokens} (Active devices: {active_tokens}, Orphaned: {orphaned_tokens})")

# 4. One-Time Prekeys Audit
print("\n--- 4. ONE-TIME PREKEYS ---")
orphaned_prekeys = 0
for pk in db.one_time_prekeys.find():
    dev_key = (pk.get('uid'), pk.get('d'))
    if dev_key not in valid_device_keys:
        orphaned_prekeys += 1
print(f"Total Prekeys: {db.one_time_prekeys.count_documents({})}, Orphaned (no active device): {orphaned_prekeys}")

# 5. Private Envelopes Audit
print("\n--- 5. PRIVATE ENVELOPES ---")
for env_doc in db.private_envelopes.find():
    print(f"Env: {env_doc['_id']}, from={env_doc.get('fromUid')}:{env_doc.get('fromD')} -> to={env_doc.get('toUid')}:{env_doc.get('toD')}, at={env_doc.get('at')}")

# 6. Conversations Audit
print("\n--- 6. CONVERSATIONS ---")
for conv in db.conversations.find():
    print(f"Conv ID: {conv['_id']}, A: {conv.get('a')}, B: {conv.get('b')}, First: {conv.get('first')}, Last: {conv.get('last')}")

# 7. Media Objects Audit
print("\n--- 7. MEDIA OBJECTS ---")
print(f"Total Media Objects: {db.media_objects.count_documents({})}")
orphan_media = 0
for m in db.media_objects.find():
    if m.get('uid') not in valid_uids:
        orphan_media += 1
        print(f"  [ORPHAN MEDIA] mid={m['_id']}, uid={m.get('uid')}")
print(f"Orphaned Media Objects: {orphan_media}")

print("\n==================== END OF AUDIT ====================")
