#!/usr/bin/env bash
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
version=''; mode=dry-run; output=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) mode=dry-run ;; --write) mode=write ;; --output) shift; [ "$#" -gt 0 ] || { echo 'Missing --output value' >&2; exit 2; }; output=$1 ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;; *) [ -z "$version" ] || { echo 'Only one version allowed' >&2; exit 2; }; version=$1 ;;
  esac; shift
done
[ -n "$version" ] || { echo 'Usage: package_submission.sh <version> [--dry-run | --write --output DIR]' >&2; exit 2; }
exec python3 - "$root" "$version" "$mode" "$output" <<'PY'
import hashlib, os, pathlib, re, shutil, subprocess, sys
root, version, mode, dest = sys.argv[1:]
r = pathlib.Path(root)
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', version): sys.exit('Некорректная версия')
if mode == 'write' and not dest: sys.exit('Для --write требуется --output DIR')
files = subprocess.check_output(['git','-C',root,'ls-files','-z']).decode().split('\0')[:-1]
bad = []
for line in subprocess.check_output(['git','-C',root,'ls-files','-s','-z']).split(b'\0'):
    if not line: continue
    meta, path = line.split(b'\t',1); modebits, oid, stage = meta.decode().split()
    size = int(subprocess.check_output(['git','-C',root,'cat-file','-s',oid]))
    name = os.fsdecode(path)
    if size > 95*1024*1024 or name.lower().endswith(('.onnx','.gguf')): bad.append(f'{name} ({size} bytes)')
print('Проверка файлов git:', 'FAIL' if bad else 'PASS')
for item in bad: print('  ', item)
required = ['docs/ARCHITECTURE.md','docs/LIMITATIONS.md','docs/TEST_RESULTS.md','docs/INSTALL.md','docs/MODELS.md','artifacts/demo/README.md']
problems=[]
for f in required:
    p=r/f
    if not p.is_file(): problems.append(f'{f}: отсутствует'); continue
    for n,line in enumerate(p.read_text(encoding='utf-8').splitlines(),1):
        if 'TODO(финал)' in line: problems.append(f'{f}:{n}: {line.strip()}')
print('Документы:', 'FAIL' if problems else 'PASS')
for p in problems: print('  ',p)
apks=sorted((r/'artifacts/apk').glob('*release*.apk'))
# Release deliverables may be renamed without the word release.
if not apks: apks=sorted((r/'artifacts/apk').glob('*.apk'))
print('APK:', len(apks))
rows=[]
for p in apks:
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024), b''): h.update(chunk)
    rows.append((p.name,p.stat().st_size,h.hexdigest()))
    print(f'  {p.name} {p.stat().st_size} bytes {h.hexdigest()}')
if not apks: print('FAIL: нет release APK в artifacts/apk/')
checks=[('APK',bool(apks)),('Исходный код', (r/'src').is_dir()),('Конфигурация и схема JSON',(r/'config/transcript.schema.json').is_file()),('Документация',not problems),('Результаты тестов',any((r/'artifacts/test-results').glob('**/*'))),('Демо-видео',any((r/'artifacts/demo').glob('*.mp4'))),('Презентация',any((r/'artifacts/presentation').glob('*.pptx')) or any((r/'artifacts/presentation').glob('*.html')))]
print('Чек-лист материалов (состав уточнить по ТЗ):')
for name, ok in checks: print(f'  [{"x" if ok else " "}] {name}')
if bad or problems or not apks: print('Упаковка заблокирована; публикация не выполнялась.'); sys.exit(1)
if mode == 'dry-run': print('DRY-RUN: файлы не созданы'); sys.exit(0)
out=pathlib.Path(dest).expanduser().resolve()
if out.exists(): sys.exit(f'Каталог уже существует: {out}')
out.mkdir(parents=True)
for name in files:
    if not (name == 'README.md' or name.startswith(('artifacts/','config/','docs/','src/'))): continue
    src=r/name; target=out/name
    if src.is_symlink(): sys.exit(f'Символическая ссылка не поддерживается: {name}')
    if src.is_file(): target.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,target)
folder=out/'artifacts/apk'; folder.mkdir(parents=True,exist_ok=True)
for p in apks: shutil.copy2(p,folder/p.name)
(folder/'SHA256SUMS').write_text(''.join(f'{sha}  {name}\n' for name,_,sha in rows))
(folder/'MANIFEST.md').write_text('# APK '+version+'\n\n| Файл / вариант | Размер, байт | SHA-256 | Минимальные требования |\n|---|---:|---|---|\n'+''.join(f'| {n} | {s} | `{h}` | См. docs/INSTALL.md и требования варианта |\n' for n,s,h in rows))
print('Создано:',out)
PY
