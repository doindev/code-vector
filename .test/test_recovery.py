"""
End-to-end test for LocalhostSessionRecoveryFilter.

Sequence:
  1. POST /mcp initialize → capture sessionId A
  2. POST /mcp tools/list with A → expect 200 (real session, baseline)
  (3. user kills and restarts the dashboard — done by the surrounding shell)
  4. POST /mcp tools/list with A again → expect 200 (filter recovered) + 33 cv_* tools

If step 4 returns 404, the filter didn't catch the stale session.
If step 4 returns 200 and the recovery filter logged a `recovered stale localhost session …`
line, we're good.
"""
import json
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:2969/mcp"


def post(payload, session_id=None, internal=False):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BASE, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    })
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)
    if internal:
        req.add_header("X-Cvector-Recovery", "1")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, dict(r.headers.items()), r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers.items()) if e.headers else {}, (e.read().decode("utf-8") if e.fp else "")


def wait_ready():
    for _ in range(60):
        try:
            with urllib.request.urlopen("http://127.0.0.1:2969/api/health", timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False


def parse_body(raw):
    if not raw or not raw.strip():
        return None
    if raw.lstrip().startswith("{"):
        return json.loads(raw)
    for line in raw.splitlines():
        if line.startswith("data:"):
            try:
                return json.loads(line[5:].strip())
            except json.JSONDecodeError:
                pass
    return None


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "fresh"
    persisted_id_file = ".test/recovery-session-id.txt"

    if not wait_ready():
        print("FAIL  dashboard never came up", flush=True)
        return 1
    print(f"[recovery:{mode}] dashboard ready", flush=True)

    if mode == "fresh":
        # 1. initialize
        status, headers, raw = post({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {"tools": {}},
                "clientInfo": {"name": "recovery-test", "version": "1.0"},
            },
        })
        session_id = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
        print(f"[recovery:fresh] init http={status} session={session_id}", flush=True)
        if not session_id:
            print("FAIL  no session id on initialize", flush=True)
            return 1
        # notifications/initialized
        post({"jsonrpc": "2.0", "method": "notifications/initialized"}, session_id=session_id)
        # 2. tools/list (baseline)
        status, _, raw = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, session_id=session_id)
        body = parse_body(raw)
        if status != 200 or not body or "result" not in body:
            print(f"FAIL  baseline tools/list http={status} body={raw[:200]}", flush=True)
            return 1
        tools = body["result"].get("tools", [])
        print(f"[recovery:fresh] tools/list http=200 cv_*={sum(1 for t in tools if str(t.get('name','')).startswith('cv_'))}", flush=True)
        open(persisted_id_file, "w").write(session_id)
        print(f"[recovery:fresh] session id persisted to {persisted_id_file} — kill and restart now", flush=True)
        return 0

    # mode == "stale": reuse the persisted session id against the freshly-restarted dashboard
    session_id = open(persisted_id_file).read().strip()
    print(f"[recovery:stale] reusing session id {session_id}", flush=True)
    status, _, raw = post({"jsonrpc": "2.0", "id": 3, "method": "tools/list"}, session_id=session_id)
    print(f"[recovery:stale] tools/list http={status}", flush=True)
    body = parse_body(raw)
    if status != 200 or not body or "result" not in body:
        print(f"FAIL  stale tools/list http={status} body={raw[:300]}", flush=True)
        return 1
    tools = body["result"].get("tools", [])
    cv = sum(1 for t in tools if str(t.get("name", "")).startswith("cv_"))
    print(f"[recovery:stale] tools/list ok  cv_*={cv}", flush=True)
    if cv == 0:
        print("FAIL  no cv_* tools returned", flush=True)
        return 1
    print("\n[recovery] PASS — stale session id was transparently recovered", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
