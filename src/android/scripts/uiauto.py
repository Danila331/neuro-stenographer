#!/usr/bin/env python3
"""Минимальная автоматизация интерфейса через uiautomator (для предрелизной проверки на эмуляторе; без внешних зависимостей).

  uiauto.py dump                      # видимые узлы: текст / content-desc, bounds, clickable
  uiauto.py tap "<подстрока>" [n]     # нажать n-й (с 0) узел, у которого text или content-desc содержит подстроку (без учёта регистра)
  uiauto.py wait "<подстрока>" [сек]  # ждать появления узла (код 0 -- появился, 1 -- нет)
  uiauto.py has "<подстрока>"         # код 0, если узел есть
  uiauto.py xy X Y | back | home | text "<ASCII>" | key KEYCODE_...
  uiauto.py scroll down|up
Переменные: ADB (по умолчанию adb), ANDROID_SERIAL.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")


def sh(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def dump():
    for _ in range(4):
        out = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout
        m = re.search(r"<\?xml.*</hierarchy>", out, re.S)
        if m:
            return ET.fromstring(m.group(0))
        time.sleep(1)
    return None


def nodes(root):
    out = []
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        b = re.findall(r"\d+", n.get("bounds") or "")
        if (t or d) and len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append({"text": t, "desc": d, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "b": (x1, y1, x2, y2), "click": n.get("clickable") == "true"})
    return out


def find(sub, root=None):
    root = root or dump()
    if root is None:
        return []
    s = sub.lower()
    return [n for n in nodes(root) if s in n["text"].lower() or s in n["desc"].lower()]


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def main():
    c = sys.argv[1]
    if c == "dump":
        for n in nodes(dump()):
            print(f"{n['cx']:4d},{n['cy']:4d} {'*' if n['click'] else ' '} {n['text'][:70]!r} {('[' + n['desc'][:50] + ']') if n['desc'] else ''}")
    elif c == "tap":
        k = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        f = find(sys.argv[2])
        if len(f) <= k:
            print("не найдено:", sys.argv[2], file=sys.stderr)
            sys.exit(1)
        tap(f[k]["cx"], f[k]["cy"])
        print("tap", f[k]["text"] or f[k]["desc"], f[k]["cx"], f[k]["cy"])
    elif c in ("wait", "has"):
        limit = float(sys.argv[3]) if len(sys.argv) > 3 and c == "wait" else 0
        t0 = time.time()
        while True:
            if find(sys.argv[2]):
                sys.exit(0)
            if time.time() - t0 >= limit:
                sys.exit(1)
            time.sleep(2)
    elif c == "xy":
        tap(sys.argv[2], sys.argv[3])
    elif c == "back":
        sh("shell", "input", "keyevent", "KEYCODE_BACK")
    elif c == "home":
        sh("shell", "input", "keyevent", "KEYCODE_HOME")
    elif c == "text":
        sh("shell", "input", "text", sys.argv[2].replace(" ", "%s"))
    elif c == "key":
        sh("shell", "input", "keyevent", sys.argv[2])
    elif c == "scroll":
        w, h = 540, 1200
        if sys.argv[2] == "down":
            sh("shell", "input", "swipe", str(w), "1700", str(w), "600", "300")
        else:
            sh("shell", "input", "swipe", str(w), "600", str(w), "1700", "300")


if __name__ == "__main__":
    main()
