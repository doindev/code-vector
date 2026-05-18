"""
End-to-end smoke test for the cvector MCP server over the stdio transport.

Spawns `java -jar cvector-app/target/cvector.jar serve`, sends the standard MCP
initialize handshake on stdin, then `notifications/initialized`, then `tools/list`,
and reports whether the server answered with a list of tools.

Treats success as: initialize result has `protocolVersion`, AND tools/list returns
at least one tool whose name starts with `cv_`. Exit code 0 on pass, 1 on fail.
"""
import json
import os
import subprocess
import sys
import threading
import time
from collections import deque


REPO = os.path.abspath(os.path.dirname(__file__) + "/..")
JAR = os.path.join(REPO, "cvector-app", "target", "cvector.jar")


def main() -> int:
    if not os.path.exists(JAR):
        print(f"FAIL  jar not found at {JAR}", flush=True)
        return 1

    print(f"[stdio] spawning: java -jar {JAR} serve", flush=True)
    proc = subprocess.Popen(
        ["java", "-jar", JAR, "serve"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,  # line buffering on the byte side
        cwd=REPO,
    )

    responses: deque[dict] = deque()
    stderr_tail: deque[str] = deque(maxlen=80)
    done = threading.Event()

    def read_stdout() -> None:
        try:
            while not done.is_set():
                line = proc.stdout.readline()
                if not line:
                    break
                line_str = line.decode("utf-8", errors="replace").strip()
                if not line_str:
                    continue
                try:
                    msg = json.loads(line_str)
                    responses.append(msg)
                except json.JSONDecodeError:
                    # Non-JSON line (banner / log) — ignore but capture
                    stderr_tail.append(f"[stdout non-json] {line_str[:200]}")
        except Exception as e:
            stderr_tail.append(f"[stdout reader err] {e}")

    def read_stderr() -> None:
        try:
            while not done.is_set():
                line = proc.stderr.readline()
                if not line:
                    break
                stderr_tail.append(line.decode("utf-8", errors="replace").rstrip())
        except Exception:
            pass

    t_out = threading.Thread(target=read_stdout, daemon=True)
    t_err = threading.Thread(target=read_stderr, daemon=True)
    t_out.start()
    t_err.start()

    def send(obj: dict) -> None:
        payload = (json.dumps(obj) + "\n").encode("utf-8")
        proc.stdin.write(payload)
        proc.stdin.flush()
        print(f"[stdio] sent  {obj.get('method')!r:35s} id={obj.get('id')}", flush=True)

    def wait_for(rid: int, timeout: float = 30.0) -> dict | None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            for msg in list(responses):
                if msg.get("id") == rid:
                    responses.remove(msg)
                    return msg
            if proc.poll() is not None:
                return None
            time.sleep(0.05)
        return None

    failures = []
    try:
        # 1) initialize
        send({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
                "clientInfo": {"name": "cvector-transport-test", "version": "1.0"},
            },
        })
        init_resp = wait_for(1)
        if not init_resp or "result" not in init_resp:
            failures.append(f"initialize: no result. resp={init_resp}")
        else:
            pv = init_resp["result"].get("protocolVersion")
            print(f"[stdio] init  ok  protocolVersion={pv}", flush=True)

        # 2) notifications/initialized
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})

        # 3) tools/list
        send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        tools_resp = wait_for(2)
        tool_count = 0
        cv_tool_count = 0
        sample = []
        if not tools_resp or "result" not in tools_resp:
            failures.append(f"tools/list: no result. resp={str(tools_resp)[:300]}")
        else:
            tools = tools_resp["result"].get("tools", [])
            tool_count = len(tools)
            cv_tools = [t for t in tools if str(t.get("name", "")).startswith("cv_")]
            cv_tool_count = len(cv_tools)
            sample = [t["name"] for t in cv_tools[:5]]
            print(f"[stdio] tools ok  total={tool_count} cv_*={cv_tool_count}", flush=True)
            print(f"[stdio]       sample={sample}", flush=True)
            if cv_tool_count == 0:
                failures.append(f"tools/list: no cv_* tools. total={tool_count}")
    finally:
        done.set()
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        t_out.join(timeout=2)
        t_err.join(timeout=2)

    if failures:
        print("\n=== STDERR TAIL ===", flush=True)
        for line in list(stderr_tail)[-30:]:
            print(line, flush=True)
        print("\n=== FAILURES ===", flush=True)
        for f in failures:
            print("FAIL ", f, flush=True)
        return 1
    print("\n[stdio] PASS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
