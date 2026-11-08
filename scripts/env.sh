# shellcheck shell=bash
# Окружение для сборки Android-части. Использовать:  source scripts/env.sh
# Работает из любой рабочей копии (git worktree): корень репозитория ($ROOT) определяется
# через `git rev-parse --git-common-dir`; все инструменты лежат в $ROOT/.local (вне git).
#
# Переменные, которые можно переопределить ДО source:
#   NEUROSTENO_JDK_HOME — путь к JDK 17 (по умолчанию $ROOT/.local/jdk17/Contents/Home или .../jdk17)

if [ -n "${BASH_VERSION:-}" ]; then
  _ns_self="${BASH_SOURCE[0]}"
elif [ -n "${ZSH_VERSION:-}" ]; then
  _ns_self="${(%):-%x}"
else
  _ns_self="$0"
fi
_ns_dir="$(cd "$(dirname "$_ns_self")" && pwd)"
_ns_common="$(git -C "$_ns_dir" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -n "$_ns_common" ]; then
  export NEUROSTENO_ROOT="$(dirname "$_ns_common")"
else
  # Рабочие копии без .git (например, распакованный архив): корень — родитель scripts/ или явный NEUROSTENO_ROOT.
  export NEUROSTENO_ROOT="${NEUROSTENO_ROOT:-$(cd "$_ns_dir/.." && pwd)}"
fi
ROOT="$NEUROSTENO_ROOT"
export ROOT
unset _ns_self _ns_dir _ns_common

LOCAL="$ROOT/.local"

# --- JDK 17 (Temurin, macOS tarball: <dir>/Contents/Home; Linux tarball: <dir>) ---
if [ -n "${NEUROSTENO_JDK_HOME:-}" ]; then
  JAVA_HOME="$NEUROSTENO_JDK_HOME"
elif [ -x "$LOCAL/jdk17/Contents/Home/bin/java" ]; then
  JAVA_HOME="$LOCAL/jdk17/Contents/Home"
elif [ -x "$LOCAL/jdk17/bin/java" ]; then
  JAVA_HOME="$LOCAL/jdk17"
else
  echo "env.sh: JDK 17 не найден в $LOCAL/jdk17 (см. src/android/README.md)" >&2
fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; fi

# --- Android SDK и пользовательские каталоги: всё внутри $ROOT/.local ---
export ANDROID_HOME="${ANDROID_HOME:-$LOCAL/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$LOCAL/android-user"
export ANDROID_AVD_HOME="$LOCAL/avd"
export GRADLE_USER_HOME="$LOCAL/gradle"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME" 2>/dev/null

# --- PATH (без дублей) ---
_ns_path_prepend() {
  case ":$PATH:" in
    *":$1:"*) ;;
    *) if [ -d "$1" ]; then PATH="$1:$PATH"; fi ;;
  esac
  return 0
}
_ns_path_prepend "$ANDROID_HOME/emulator"
_ns_path_prepend "$LOCAL/bin"
_ns_path_prepend "$ANDROID_HOME/platform-tools"
_ns_path_prepend "$ANDROID_HOME/cmdline-tools/latest/bin"
if [ -n "${JAVA_HOME:-}" ]; then _ns_path_prepend "$JAVA_HOME/bin"; fi
unset -f _ns_path_prepend
export PATH
unset LOCAL
