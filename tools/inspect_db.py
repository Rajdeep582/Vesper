import os
from pymongo import MongoClient
from dotenv import dotenv_values

env = dotenv_values('.dev/prod.env')
mongo_uri = env.get('MONGO_URI')
mongo_db = env.get('MONGO_DB', 'vesper')

print(f"Connecting to MongoDB {mongo_db}...")
client = MongoClient(mongo_uri)
db = client[mongo_db]

print("\n--- USERS ---")
users = list(db.users.find())
for u in users:
    print(f"User: _id={u.get('_id')}, username={u.get('u')}, role={u.get('role')}, st={u.get('st')}, joined={u.get('at')}")

print("\n--- DEVICES ---")
devices = list(db.devices.find())
for d in devices:
    print(f"Device: _id={d.get('_id')}, uid={d.get('uid')}, d={d.get('d')}, label={d.get('label')}, seen={d.get('seen')}, has_fcm={bool(d.get('fcm'))}")

print("\n--- PREKEYS COUNT PER DEVICE ---")
for d in devices:
    pk_count = db.one_time_prekeys.count_documents({"uid": d.get("uid"), "d": d.get("d")})
    print(f"User {d.get('uid')} did {d.get('d')}: {pk_count} prekeys")

print("\n--- LATEST HALL MESSAGES ---")
hall_msgs = list(db.hall_messages.find().sort("seq", -1).limit(5))
for h in hall_msgs:
    print(f"Hall: seq={h.get('seq')}, uid={h.get('uid')}, at={h.get('at')}, exp={h.get('exp')}, mid={h.get('mid')}")

print("\n--- PENDING ENVELOPES ---")
envs = list(db.private_envelopes.find())
for e in envs:
    print(f"Env: _id={e.get('_id')}, from={e.get('fromUid')}:{e.get('fromD')} -> to={e.get('toUid')}:{e.get('toD')}, at={e.get('at')}, exp={e.get('exp')}")

print("\n--- LATEST ACTIVITY LOGS ---")
acts = list(db.activity_events.find().sort("at", -1).limit(10))
for a in acts:
    print(f"Activity: at={a.get('at')}, event={a.get('ev')}, uid={a.get('uid')}, u={a.get('u')}, details={a.get('d')}")
