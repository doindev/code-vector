"""
End-to-end smoke test for the cvector MCP server over the legacy HTTP+SSE transport
(MCP spec 2024-11-05). Assumes the server is already running with
`spring.ai.mcp.server.protocol=SSE`.

Flow:
  1. GET /sse — open the long-lived event stream. Server emits an `event: endpoint`
     with `data: /mcp/message?sessionId=<uuid>` — the per-session POST URL.
  2. POST initialize to that URL. Response arrives via the SSE stream as `event: message`.
  3. POST notifications/initialized (no response expected).
  4. POST tools/list. Response arrives via SSE.

Success: tools/list returns at least one cv_* tool.
"""
import json
import queue
import sys
import threading
import time
import urllib.request
import urllib.error


BASE = "http://127.0.0.1:2969"


def main() -> int:
    # Pre-flight
    for attempt in range(60):
        try:
            with urllib.request.urlopen(f"{BASE}/api/health", timeout=2) as r:
                if r.status == 200:
                    break
        except Exception:
            pass
        time.sleep(1)
    else:
        print("FAIL  dashboard /api/health never returned 200", flush=True)
        return 1
    print("[sse] dashboard ready", flush=True)

    # 1) Open SSE stream
    events: "queue.Queue[dict]" = queue.Queue()
    endpoint_path = [None]  # filled by the SSE reader
    sse_done = threading.Event()

    def sse_reader() -> None:
        req = urllib.request.Request(f"{BASE}/sse", headers={"Accept": "text/event-stream"})
        try:
            resp = urllib.request.urlopen(req, timeout=30)
            event_name = None
            data_buf: list[str] = []
            while not sse_done.is_set():
                line = resp.readline()
                if not line:
                    break
                s = line.decode("utf-8", errors="replace").rstrip("\r\n")
                if s == "":
                    # Event boundary — dispatch
                    if event_name and data_buf:
                        payload = "\n".join(data_buf)
                        if event_name == "endpoint":
                            endpoint_path[0] = payload
                            print(f"[sse] endpoint = {payload}", flush=True)
                        elif event_name == "message":
                            try:
                                events.put(json.loads(payload))
                            except json.JSONDecodeError:
                                pass
                    event_name = None
                    data_buf = []
                    continue
                if s.startswith("event:"):
                    event_name = s[6:].strip()
                elif s.startswith("data:"):
                    data_buf.append(s[5:].lstrip())
        except Exception as e:
            print(f"[sse] reader error: {e}", flush=True)

    t = threading.Thread(target=sse_reader, daemon=True)
    t.start()

    # Wait up to 10 s for the endpoint event.
    for _ in range(100):
        if endpoint_path[0]:
            break
        time.sleep(0.1)
    if not endpoint_path[0]:
        print("FAIL  never received endpoint event from /sse", flush=True)
        sse_done.set()
        return 1

    post_url = BASE.rstrip("/") + endpoint_path[0]
    # Spring AI's endpoint event encodes its path absolute-from-root, e.g.
    #   /mcp/message?sessionId=…
    # Some 2.0.x builds emit a full URL — accept either form.
    if endpoint_path[0].startswith("http://") or endpoint_path[0].startswith("https://"):
        post_url = endpoint_path[0]

    def post(payload: dict) -> int:
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(post_url, data=body, method="POST",
                                      headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def wait_for(rid: int, timeout: float = 30.0) -> dict | None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                msg = events.get(timeout=0.2)
            except queue.Empty:
                continue
            if msg.get("id") == rid:
                return msg
            # Other ids — just drop on the floor for this smoke test
        return None

    failures: list[str] = []

    # 2) initialize
    status = post({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
            "clientInfo": {"name": "cvector-transport-test", "version": "1.0"},
        },
    })
    print(f"[sse] init POST http={status}", flush=True)
    if status != 200 and status != 202:
        failures.append(f"initialize POST {status}")
    init_resp = wait_for(1)
    if not init_resp or "result" not in init_resp:
        failures.append(f"initialize: no SSE result. resp={init_resp}")
    else:
        pv = init_resp["result"].get("protocolVersion")
        print(f"[sse] init ok  protocolVersion={pv}", flush=True)

    # 3) notifications/initialized
    post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    # 4) tools/list
    status = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    print(f"[sse] tools POST http={status}", flush=True)
    tools_resp = wait_for(2)
    if not tools_resp or "result" not in tools_resp:
        failures.append(f"tools/list: no SSE result. resp={str(tools_resp)[:300]}")
    else:
        tools = tools_resp["result"].get("tools", [])
        cv_tools = [t for t in tools if str(t.get("name", "")).startswith("cv_")]
        print(f"[sse] tools ok  total={len(tools)} cv_*={len(cv_tools)}", flush=True)
        print(f"[sse]        sample={[t['name'] for t in cv_tools[:5]]}", flush=True)
        if not cv_tools:
            failures.append(f"tools/list: no cv_* tools. total={len(tools)}")

    sse_done.set()
    if failures:
        print("\n=== FAILURES ===", flush=True)
        for f in failures:
            print("FAIL ", f, flush=True)
        return 1
    print("\n[sse] PASS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
