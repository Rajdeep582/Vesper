import urllib.request
import time
import json

def check_backend():
    base_url = "https://vesper-api-mthr.onrender.com"
    endpoints = [
        ("/health", "GET", "Health probe (Liveness)"),
        ("/v1/auth/available?u=healthchecktest", "GET", "Auth availability (Database Query)"),
        ("/v1/me/devices", "GET", "Protected Route Security (Auth Enforcement)")
    ]
    
    print(f"=== Render Deployment Status Check ({base_url}) ===\n")
    
    for path, method, desc in endpoints:
        url = base_url + path
        t0 = time.time()
        try:
            req = urllib.request.Request(url, method=method, headers={"User-Agent": "Vesper-Check/1.0"})
            with urllib.request.urlopen(req, timeout=20) as resp:
                elapsed = (time.time() - t0) * 1000
                status = resp.status
                body = resp.read().decode("utf-8")
                print(f"[ONLINE - {status}] {desc}")
                print(f"  Endpoint: {path}")
                print(f"  Latency:  {elapsed:.1f}ms")
                print(f"  Response: {body}\n")
        except urllib.error.HTTPError as e:
            elapsed = (time.time() - t0) * 1000
            print(f"[EXPECTED STATUS - {e.code}] {desc}")
            print(f"  Endpoint: {path}")
            print(f"  Latency:  {elapsed:.1f}ms")
            print(f"  Response: {e.read().decode('utf-8')}\n")
        except Exception as e:
            elapsed = (time.time() - t0) * 1000
            print(f"[ERROR] {desc} failed in {elapsed:.1f}ms: {e}\n")

if __name__ == "__main__":
    check_backend()
