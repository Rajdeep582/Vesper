from pymongo import MongoClient
from dotenv import dotenv_values

env = dotenv_values('.dev/prod.env')
client = MongoClient(env['MONGO_URI'])
db = client[env.get('MONGO_DB', 'vesper')]

print("=== PRIVATE META (All 15) ===")
for m in db.private_meta.find().sort('at', -1):
    del_str = str(m.get('del')) if m.get('del') else 'pending'
    seen_str = str(m.get('seen')) if m.get('seen') else 'unseen'
    print(f"ID={m['_id'][:8]}.. from={m['fromUid'][:8]}.. to={m['toUid'][:8]}.. k={m['k']} at={m['at']} del={del_str} seen={seen_str}")

print("\n=== LAST 10 HALL MESSAGES ===")
for h in db.hall_messages.find().sort('seq', -1).limit(10):
    print(f"Seq={h['seq']} uid={h['uid'][:8]}.. k={h['k']} text={repr(h.get('text'))}")
