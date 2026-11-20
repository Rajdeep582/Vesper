import asyncio
import websockets
import time
import json
import jwt

uid = 'f0774f65ea713a6f0ef86a43365e0408' # rajdeep
did = 1

# Try secret from RENDER_ENV.txt
jwt_secret = '8wxCm3oe1RxKPqH3rOV5BTdUM4JptaIWG3qNvDoJyqw='
now = int(time.time())
token = jwt.encode({
    'iss': 'vesper',
    'uid': uid,
    'did': did,
    'role': 'admin',
    'jti': 'test-jti-123',
    'iat': now,
    'exp': now + 3600
}, jwt_secret, algorithm='HS256')

async def test_ws():
    url = 'wss://vesper-api-mthr.onrender.com/v1/ws'
    headers = {'Authorization': f'Bearer {token}'}
    print(f'Connecting to {url} with RENDER_ENV secret...')
    try:
        async with websockets.connect(url, additional_headers=headers) as ws:
            print('[SUCCESS] WebSocket CONNECTED!')
            ping = json.dumps({'t': 'ping', 'clientTime': int(time.time() * 1000)})
            await ws.send(ping)
            resp = await asyncio.wait_for(ws.recv(), timeout=5.0)
            print('[SUCCESS] Received response frame:', resp)
    except Exception as e:
        print('[FAILED] WebSocket error:', type(e), e)

if __name__ == '__main__':
    asyncio.run(test_ws())
