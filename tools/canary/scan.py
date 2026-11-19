"""Plaintext-leak canary scan (docs/THREAT_MODEL.md).

Usage: python tools/canary/scan.py <mongo-uri> <db> <canary> [media-dir] [log-file]
Searches every document of every collection (raw BSON bytes), local media blobs and an optional server log
for the canary string. Exit code 1 if found anywhere. Requires: pip install pymongo
"""
import os
import sys

import bson
from pymongo import MongoClient

uri, db_name, canary = sys.argv[1], sys.argv[2], sys.argv[3].encode()
media_dir = sys.argv[4] if len(sys.argv) > 4 else None
log_file = sys.argv[5] if len(sys.argv) > 5 else None
hits = []
db = MongoClient(uri)[db_name]
for name in db.list_collection_names():
    for doc in db[name].find():
        if canary in bson.encode(doc):
            hits.append(f"mongo:{name}:{doc.get('_id')}")
if media_dir and os.path.isdir(media_dir):
    for f in os.listdir(media_dir):
        with open(os.path.join(media_dir, f), "rb") as fh:
            if canary in fh.read():
                hits.append(f"media:{f}")
if log_file and os.path.exists(log_file):
    with open(log_file, "rb") as fh:
        if canary in fh.read():
            hits.append(f"log:{log_file}")
print("CLEAN — canary not found" if not hits else "LEAK:\n" + "\n".join(hits))
sys.exit(1 if hits else 0)
