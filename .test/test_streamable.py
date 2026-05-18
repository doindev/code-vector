"""
End-to-end smoke test for the cvector MCP server over the Streamable HTTP transport
(MCP spec 2025-03-26). Assumes the server is already running with
`spring.ai.mcp.server.protocol=STREAMABLE` and the streamable endpoint at /mcp.

Flow:
  1. POST initialize to /mcp — server returns Mcp-Session-Id response header.
  2. POST notifications/initialized — header carries the session.
  3. POST tools/list — header carries the session.

Streamable HTTP responses may come back as JSON in the body OR as an SSE stream
inside the POST response. Spring AI's WebMvcStreamableServerTransportProvider chooses
based on whether the JSON-RPC request expects a response. We Accept both content types
and parse whichever the server sent.

Success: tools/list returns at least one cv_* tool.
"""
import json
import sys
import time
import urllib.request
import urllib.error


BASE = "http://127.0.0.1:2969/mcp"


def post(payload: dict, session_id: str | None) -> tuple[int, dict, dict | None]:
    """POST a JSON-RPC envelope, return (status, response-headers-dict, parsed-body-or-None)."""
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.status
            headers = dict(resp.headers.items())
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        status = e.code
        headers = dict(e.headers.items()) if e.headers else {}
        raw = e.read().decode("utf-8", errors="replace") if e.fp else ""

    parsed: dict | None = None
    if raw.strip():
        # Streamable HTTP can answer either as a raw JSON-RPC envelope or as an
        # SSE-encoded stream embedded in the POST response. Both forms wrap one
        # response per JSON-RPC request, so we just look for the first JSON object.
        if raw.lstrip().startswith("{"):
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                pass
        else:
            # SSE format: lines of `event: …` and `data: …`. Take the first `data:`.
            for line in raw.splitlines():
                if line.startswith("data:"):
                    try:
                        parsed = json.loads(line[5:].strip())
                        break
                    except json.JSONDecodeError:
                        continue
    return status, headers, parsed


def main() -> int:
    # Pre-flight: confirm the dashboard is reachable.
    for attempt in range(60):
        try:
            with urllib.request.urlopen("http://127.0.0.1:2969/api/health", timeout=2) as r:
                if r.status == 200:
                    break
        except Exception:
            pass
        time.sleep(1)
    else:
        print("FAIL  dashboard /api/health never returned 200", flush=True)
        return 1
    print("[streamable] dashboard ready", flush=True)

    failures: list[str] = []

    # 1) initialize — captures Mcp-Session-Id header.
    status, headers, init_resp = post({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2025-03-26",
            "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
            "clientInfo": {"name": "cvector-transport-test", "version": "1.0"},
        },
    }, session_id=None)
    print(f"[streamable] init  http={status} session={headers.get('Mcp-Session-Id')}", flush=True)
    if status != 200:
        failures.append(f"initialize HTTP {status}, headers={headers}, body={init_resp}")
    session_id = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
    if not session_id:
        failures.append(f"initialize: no Mcp-Session-Id header. headers={headers}")
    if init_resp and "result" in init_resp:
        pv = init_resp["result"].get("protocolVersion")
        print(f"[streamable] init  ok  protocolVersion={pv}", flush=True)
    elif init_resp:
        failures.append(f"initialize: no result. resp={init_resp}")

    if not session_id:
        # Can't continue without a session
        for f in failures:
            print("FAIL ", f, flush=True)
        return 1

    # 2) notifications/initialized (no response expected — fire and forget).
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, session_id=session_id)

    # 3) tools/list
    status, headers, tools_resp = post({
        "jsonrpc": "2.0", "id": 2, "method": "tools/list"
    }, session_id=session_id)
    print(f"[streamable] tools http={status}", flush=True)
    if status != 200:
        failures.append(f"tools/list HTTP {status}, body={tools_resp}")
    elif not tools_resp or "result" not in tools_resp:
        failures.append(f"tools/list: no result. resp={str(tools_resp)[:300]}")
    else:
        tools = tools_resp["result"].get("tools", [])
        cv_tools = [t for t in tools if str(t.get("name", "")).startswith("cv_")]
        print(f"[streamable] tools ok  total={len(tools)} cv_*={len(cv_tools)}", flush=True)
        print(f"[streamable]       sample={[t['name'] for t in cv_tools[:5]]}", flush=True)
        if not cv_tools:
            failures.append(f"tools/list: no cv_* tools. total={len(tools)}")

    if failures:
        print("\n=== FAILURES ===", flush=True)
        for f in failures:
            print("FAIL ", f, flush=True)
        return 1
    print("\n[streamable] PASS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
