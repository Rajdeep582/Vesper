import urllib.request
import json
import time
import jwt

jwt_secret = '8wxCm3oe1RxKPqH3rOV5BTdUM4JptaIWG3qNvDoJyqw='
now = int(time.time())
token = jwt.encode({
    'iss': 'vesper',
    'uid': 'f0774f65ea713a6f0ef86a43365e0408',
    'did': 1,
    'role': 'admin',
    'jti': 'test-jti-123',
    'iat': now,
    'exp': now + 3600
}, jwt_secret, algorithm='HS256')

base = 'https://vesper-api-mthr.onrender.com'
for uid, name in [('f0774f65ea713a6f0ef86a43365e0408', 'rajdeep'), ('df65e1567a1c37c8a7251e0f1b9ff4f2', 'deep2003')]:
    url = f'{base}/v1/keys/{uid}'
    req = urllib.request.Request(url, headers={'Authorization': f'Bearer {token}'})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        print(f'{name} key bundles:')
        for d in data['devices']:
            print(f"  Device {d['deviceId']}: reg={d['registrationId']}, ik={d['identityKey'][:10]}..., spk_id={d['signedPreKey']['id']}")
