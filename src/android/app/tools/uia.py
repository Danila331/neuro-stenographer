#!/usr/bin/env python3
"""Минимальная автоматизация интерфейса через adb + uiautomator (для смоука и скриншотов).

    uia.py tap  <text|id:resource-id|desc:content-desc>   # тап по центру найденного элемента (подстрока, без регистра)
    uia.py wait <селектор> [секунд]                       # ждать появления элемента
    uia.py has  <селектор>                                # код 0, если элемент есть
    uia.py text <селектор>                                # текст элемента
    uia.py type <текст> [пакет]                           # ввод: ASCII напрямую, кириллица через буфер (debug-сборка)
    uia.py shot <файл.png>                                # скриншот
    uia.py dump                                           # XML иерархии
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


PKG = "ru.neurosteno.app.medium"


def adb(*args, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def dump():
    for _ in range(5):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        i = out.find("<?xml")
        if i >= 0:
            return out[i:out.rfind("</hierarchy>") + len("</hierarchy>")]
        time.sleep(0.5)
    raise SystemExit("uiautomator dump не удался")


def find(selector):
    root = ET.fromstring(dump())
    kind, _, value = selector.partition(":") if selector.split(":", 1)[0] in ("id", "desc", "text") else ("any", "", selector)
    v = value.lower()
    best = None
    for n in root.iter("node"):
        text, desc, rid = n.get("text", ""), n.get("content-desc", ""), n.get("resource-id", "")
        ok = (
            (kind == "id" and rid.lower().endswith(v))
            or (kind == "desc" and v in desc.lower())
            or (kind == "text" and v in text.lower())
            or (kind == "any" and (v in text.lower() or v in desc.lower() or rid.lower().endswith(v)))
        )
        if ok:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                cand = ((x1 + x2) // 2, (y1 + y2) // 2, text or desc, n)
                # предпочитаем кликабельные и более «точные» (короткие) совпадения
                score = (n.get("clickable") == "true", -len(text or desc))
                if best is None or score > best[0]:
                    best = (score, cand)
    return best[1] if best else None


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "dump":
        print(dump())
    elif cmd == "shot":
        open(args[0], "wb").write(adb("exec-out", "screencap", "-p", binary=True))
    elif cmd == "type":
        # ASCII -- через input text; иначе (кириллица) -- через буфер обмена debug-сборки и KEYCODE_PASTE
        if args[0].isascii():
            adb("shell", "input", "text", args[0].replace(" ", "%s"))
        else:
            adb("shell", "am", "broadcast", "-a", "ru.neurosteno.app.DEBUG_CLIP", "-p", sys.argv[3] if len(sys.argv) > 3 else PKG,
                "--es", "text", args[0])
            time.sleep(0.5)
            adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")  # заменяем текущее содержимое поля
            adb("shell", "input", "keyevent", "KEYCODE_PASTE")
    elif cmd in ("tap", "has", "text"):
        f = find(args[0])
        if cmd == "has":
            sys.exit(0 if f else 1)
        if not f:
            raise SystemExit(f"элемент не найден: {args[0]}")
        if cmd == "text":
            print(f[2])
        else:
            adb("shell", "input", "tap", str(f[0]), str(f[1]))
    elif cmd == "wait":
        end = time.time() + float(args[1] if len(args) > 1 else 30)
        while time.time() < end:
            if find(args[0]):
                return
            time.sleep(1)
        raise SystemExit(f"не дождались: {args[0]}")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
