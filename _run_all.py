# -*- coding: utf-8 -*-
"""一次性跑完注入脚本反解 + 各桩测试，输出统一落盘为 UTF-8
（PowerShell 的 *> 重定向会写成 UTF-16，Read 读不了，所以走这里）。"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable

env = dict(os.environ)
node_dirs = [
    r"C:\Program Files\nodejs",
    r"C:\Users\Administrator\.workbuddy\binaries\node\versions\22.22.2-3",
]
env["PATH"] = os.pathsep.join(node_dirs) + os.pathsep + env.get("PATH", "")

steps = [
    ("extract_js.py", [PY, "extract_js.py"]),
    ("test_fav.js", ["node", "test_fav.js"]),
    ("test_shelf.js", ["node", "test_shelf.js"]),
    ("test_nav_stub.js", ["node", "test_nav_stub.js"]),
    ("test_drawer.js", ["node", "test_drawer.js"]),
    ("test_cover.js", ["node", "test_cover.js"]),
    ("test_favbtn.py", [PY, "test_favbtn.py"]),
    ("test_longpress.py", [PY, "test_longpress.py"]),
    ("test_returnlogic.py", [PY, "test_returnlogic.py"]),
    ("test_progress.py", [PY, "test_progress.py"]),
]

buf = []
ok = True
for name, cmd in steps:
    buf.append("===== %s =====" % name)
    r = subprocess.run(cmd, cwd=HERE, env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    buf.append((r.stdout or "") + (r.stderr or ""))
    buf.append("[exit=%d]" % r.returncode)
    if r.returncode != 0:
        ok = False
    buf.append("")

buf.append("ALL_OK = %s" % ok)
out = "\n".join(buf)
with open(os.path.join(HERE, "_jscheck.txt"), "w", encoding="utf-8") as f:
    f.write(out)
sys.exit(0 if ok else 1)
