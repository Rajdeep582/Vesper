import urllib.request
import urllib.parse
import hashlib
import time
import json
from dotenv import dotenv_values

env = dotenv_values('.dev/prod.env')
cloud = env.get('CLOUDINARY_CLOUD_NAME')
api_key = env.get('CLOUDINARY_API_KEY')
api_secret = env.get('CLOUDINARY_API_SECRET')

print('Testing Cloudinary configuration...')
print('Cloud Name:', cloud)
print('API Key:', api_key[:4] + '****' if api_key else 'None')
print('API Secret configured:', bool(api_secret))

now = int(time.time())
to_sign = f'folder=vesper/test&timestamp={now}{api_secret}'
sig = hashlib.sha1(to_sign.encode()).hexdigest()

data = urllib.parse.urlencode({
    'timestamp': str(now),
    'api_key': api_key,
    'signature': sig,
    'file': 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
    'folder': 'vesper/test'
}).encode()

url = f'https://api.cloudinary.com/v1_1/{cloud}/image/upload'
try:
    req = urllib.request.Request(url, data=data)
    with urllib.request.urlopen(req) as resp:
        res = json.loads(resp.read().decode())
        public_id = res.get('public_id')
        print(f'[SUCCESS] Cloudinary upload successful! Public ID: {public_id}')
        
        # Clean up
        del_to_sign = f'public_id={public_id}&timestamp={now}{api_secret}'
        del_sig = hashlib.sha1(del_to_sign.encode()).hexdigest()
        del_data = urllib.parse.urlencode({
            'public_id': public_id,
            'timestamp': str(now),
            'api_key': api_key,
            'signature': del_sig
        }).encode()
        del_req = urllib.request.Request(f'https://api.cloudinary.com/v1_1/{cloud}/image/destroy', data=del_data)
        with urllib.request.urlopen(del_req) as del_resp:
            print('[SUCCESS] Cloudinary test asset destroyed cleanly!')
except Exception as e:
    print(f'[FAIL] Cloudinary test failed: {e}')
