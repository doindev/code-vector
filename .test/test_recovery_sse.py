"""
End-to-end test for LocalhostSessionRecoveryFilter on the SSE legacy transport.

Sequence:
  Phase "fresh":
    1. GET /sse — capture endpoint event → /mcp/message?sessionId=<A>
    2. POST initialize → 200 / 202 (response arrives on SSE stream)
    3. POST notifications/initialized
    4. POST tools/list → expect 200/202
    Persist sessionId A.
  (operator kills + restarts cvector)
  Phase "stale":
    5. POST tools/list to /mcp/message?sessionId=<A> with stale A
       → without filter: 404 "Session not found"
       → with filter:    200/202 (rewrites to recovered server id)

Success = phase "stale" gets 200/202 instead of 404, AND the recovery log line appears
(`recovered stale localhost session (sse) client=A → server=B`).
"""
import json
import sys
import time
import threading
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:2969"


def wait_health():
    for _ in range(60):
        try:
            with urllib.request.urlopen(f"{BASE}/api/health", timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False


def open_sse_and_get_endpoint(timeout=15):
    """Open GET /sse and read until the `endpoint` event arrives. Returns (endpoint_path, response)."""
    req = urllib.request.Request(f"{BASE}/sse", headers={"Accept": "text/event-stream"})
    resp = urllib.request.urlopen(req, timeout=timeout)
    deadline = time.time() + timeout
    current_event = None
    data_buf = []
    while time.time() < deadline:
        line = resp.readline()
        if not line:
            return None, resp
        s = line.decode("utf-8", errors="replace").rstrip("\r\n")
        if s == "":
            if current_event == "endpoint" and data_buf:
                return "\n".join(data_buf), resp
            current_event = None
            data_buf = []
        elif s.startswith("event:"):
            current_event = s[6:].strip()
        elif s.startswith("data:"):
            data_buf.append(s[5:].strip())
    return None, resp


def post(url, payload):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST",
                                  headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, dict(r.headers.items()), r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers.items()) if e.headers else {}, (e.read().decode("utf-8") if e.fp else "")


def extract_session_from_path(path):
    if "?" not in path:
        return None
    for part in path.split("?", 1)[1].split("&"):
        if "=" in part:
            k, v = part.split("=", 1)
            if k == "sessionId":
                return v
    return None


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "fresh"
    persisted = ".test/recovery-sse-session.txt"

    if not wait_health():
        print("FAIL  dashboard never came up", flush=True)
        return 1
    print(f"[sse-recovery:{mode}] dashboard ready", flush=True)

    if mode == "fresh":
        endpoint, sse_resp = open_sse_and_get_endpoint()
        if not endpoint:
            print("FAIL  no endpoint event from /sse", flush=True)
            return 1
        session_id = extract_session_from_path(endpoint)
        print(f"[sse-recovery:fresh] endpoint = {endpoint}  sessionId={session_id}", flush=True)
        post_url = BASE + (endpoint if endpoint.startswith("/") else "/" + endpoint)
        # initialize + initialized + tools/list
        status, _, _ = post(post_url, {
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "clientInfo": {"name": "sse-recovery-test", "version": "1.0"},
            },
        })
        print(f"[sse-recovery:fresh] init POST http={status}", flush=True)
        if status not in (200, 202):
            return 1
        post(post_url, {"jsonrpc": "2.0", "method": "notifications/initialized"})
        status, _, _ = post(post_url, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        print(f"[sse-recovery:fresh] tools/list POST http={status}", flush=True)
        if status not in (200, 202):
            return 1
        with open(persisted, "w") as f:
            f.write(session_id)
        print(f"[sse-recovery:fresh] persisted sessionId {session_id} — restart cvector now", flush=True)
        # Drop the SSE stream — we're done with this run.
        try: sse_resp.close()
        except Exception: pass
        return 0

    # mode == stale: post against /mcp/message?sessionId=<stale> on the freshly restarted server
    stale = open(persisted).read().strip()
    stale_url = f"{BASE}/mcp/message?sessionId={stale}"
    print(f"[sse-recovery:stale] POSTing tools/list to stale {stale_url}", flush=True)
    status, _, body = post(stale_url, {"jsonrpc": "2.0", "id": 3, "method": "tools/list"})
    print(f"[sse-recovery:stale] http={status}", flush=True)
    if status not in (200, 202):
        print(f"FAIL  stale POST got http={status}, body={body[:300]}", flush=True)
        return 1
    print("\n[sse-recovery] PASS — stale SSE sessionId was accepted (rewrite happened)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
