import json
import time
import urllib.request
import urllib.parse
import jwt
from cryptography.hazmat.primitives import serialization

def run_fcm_tests():
    with open('.dev/fcm-service-account.json') as f:
        sa = json.load(f)

    project_id = sa['project_id']
    client_email = sa['client_email']
    token_uri = sa.get('token_uri', 'https://oauth2.googleapis.com/token')

    print(f"[+] Loaded Service Account: {client_email} for project: {project_id}")

    now = int(time.time())
    payload = {
        'iss': client_email,
        'sub': client_email,
        'aud': token_uri,
        'iat': now,
        'exp': now + 3600,
        'scope': 'https://www.googleapis.com/auth/firebase.messaging'
    }

    private_key = serialization.load_pem_private_key(sa['private_key'].encode(), password=None)
    token = jwt.encode(payload, private_key, algorithm='RS256')

    req_data = urllib.parse.urlencode({
        'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        'assertion': token
    }).encode()

    t0 = time.time()
    req = urllib.request.Request(token_uri, data=req_data, headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with urllib.request.urlopen(req) as resp:
        oauth_res = json.loads(resp.read().decode())
    t1 = time.time()

    access_token = oauth_res['access_token']
    print(f"[+] OAuth2 token obtained in {(t1 - t0)*1000:.1f}ms (expires_in: {oauth_res.get('expires_in')}s)")

    target_tokens = [
        ("rajdeep (CPH2717)", "c7Xg5J1LRL6M9DH_sJgNXe:APA91bFvL50nqPgbB6lvOsw5ic7ZLmJLaat346wwNZBmdPQUQEpCWj98EdTJFi513Aap0ncO16iKCq83c03MK09k7-bY45vsKiGxldrabq6pyX-TSwh5Pmw"),
        ("deep2003 (moto g96 5G - NEW)", "fu54k8-1TuaE_LJR3UQJRj:APA91bEXDJr8QrTUxQgbMM0WxUIcDcKKYwSePi0no0rHokl4SWd7yEMS9Xudw9LeTMxf8rU7n0miZNooV-F5UVTx90ZO5QFlBZfnhE3EpPgF83t-RRujgEU"),
        ("account2 (2109119DI)", "ea5HGPiKTAqHcWJ0PoP9ij:APA91bE2HcB9yAntncyzfcvn8rhTdetvz2hMyy8D4zxycKodYEXXJWgsFZLPrpsqPvScwV8pfsyJyCjxCl1SfE47eR0__wE-VFSf6LyhX6n4l8mY4ceNH_c")
    ]

    endpoint = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"

    print("\n--- Testing Push Delivery to All Devices in Database ---")
    for name, fcm_tok in target_tokens:
        body_send = {
            'validate_only': False,
            'message': {
                'token': fcm_tok,
                'data': {'t': 'wake'},
                'android': {'priority': 'high', 'ttl': '86400s'}
            }
        }
        t0 = time.time()
        try:
            req = urllib.request.Request(endpoint, data=json.dumps(body_send).encode(), headers={'Authorization': f'Bearer {access_token}', 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req) as resp:
                latency = (time.time() - t0) * 1000
                res_body = json.loads(resp.read().decode())
                print(f"[SUCCESS] Target: {name} -> 200 OK in {latency:.1f}ms, MessageId: {res_body.get('name')}")
        except urllib.error.HTTPError as e:
            latency = (time.time() - t0) * 1000
            err_body = e.read().decode()
            print(f"[FAIL] Target: {name} -> HTTP {e.code} in {latency:.1f}ms:\n    {err_body}")

if __name__ == '__main__':
    run_fcm_tests()
