#!/usr/bin/env bash
# Plants session material in a throwaway repo and checks that .githooks/pre-commit blocks it.
# Secret-looking strings are assembled at runtime so this file never contains one itself.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

git -C "$tmp" init -q
git -C "$tmp" config user.email guard@test.invalid
git -C "$tmp" config user.name guard
mkdir -p "$tmp/.githooks"
hook_src="${HOOK_SRC:-$root/.githooks/pre-commit}"
cp "$hook_src" "$tmp/.githooks/pre-commit"
chmod +x "$tmp/.githooks/pre-commit"
git -C "$tmp" config core.hooksPath .githooks

planted_sid="session""id=1234567890%3A$(printf 'Ab%.0s' $(seq 1 12))"
planted_cookie="Coo""kie: session""id=x"

# check <blocked|allowed> <description> <path> <content>
check() {
  local want="$1" what="$2" path="$3" content="$4" got
  mkdir -p "$tmp/$(dirname "$path")"
  printf '%s\n' "$content" > "$tmp/$path"
  git -C "$tmp" add "$path"
  if git -C "$tmp" commit -q -m "$what" >/dev/null 2>&1; then got=allowed; else got=blocked; fi
  if [ "$got" = blocked ]; then
    git -C "$tmp" reset -q
    rm -f "$tmp/$path"
  fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $what was $got, expected $want"
    exit 1
  fi
  echo "ok: $what ($got)"
}

# check_bytes <blocked|allowed> <description> <path> <printf-format> [args...]
# Writes bytes directly to disk (not through bash variables) and tests with UTF-8 locale
check_bytes() {
  local want="$1" what="$2" path="$3" fmt="$4" got
  shift 4
  mkdir -p "$tmp/$(dirname "$path")"
  printf "$fmt" "$@" > "$tmp/$path"
  git -C "$tmp" add "$path"
  if LC_ALL=en_US.UTF-8 git -C "$tmp" commit -q -m "$what" >/dev/null 2>&1; then got=allowed; else got=blocked; fi
  if [ "$got" = blocked ]; then
    git -C "$tmp" reset -q
    rm -f "$tmp/$path"
  fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $what was $got, expected $want"
    exit 1
  fi
  echo "ok: $what ($got)"
}

check allowed "ordinary file" "README.md" "hello"
check allowed "prose that mentions sessionid" "docs/howto.md" "Paste your sessionid; never share the Cookie: header"
check blocked "HAR capture" "captures/run.har" "{}"
check blocked "session json" "app/session.json" "{}"
check blocked "cookies dump" "tmp/cookies-www.txt" "x"
check blocked "planted sessionid value" "notes.txt" "token $planted_sid"
check blocked "planted Cookie header" "Api.kt" "val h = \"$planted_cookie\""
# Regression test for fail-open on large diffs (SIGPIPE with grep -q + pipefail)
large_file="$(printf '%s\n%s' "token $planted_sid" "$(head -c 200000 /dev/zero | tr '\0' 'a' | fold -w 100)")"
check blocked "planted sessionid in a large file" "bigfile.txt" "$large_file"
sid_name="session""id"
csrf_name="csrf""token"
check blocked "JSON cookie export" "export/cookies.json" "[{\"name\": \"$sid_name\", \"value\": \"abc\", \"domain\": \".instagram.com\"}]"
check blocked "JSON csrftoken export" "export/state.json" "{\"name\":\"$csrf_name\",\"value\":\"abc\"}"
check blocked "Netscape cookie line" "export/jar.txt" "$(printf '.instagram.com\tTRUE\t/\tTRUE\t1999999999\t%s\t%s' "$sid_name" "abc")"
check blocked "csrftoken value" "notes2.txt" "$csrf_name=$(printf 'Ab%.0s' $(seq 1 10))"
check blocked "++ b/ line with sessionid (no space)" "pp2.txt" "++ b/$planted_sid"
check blocked "++ /dev/null line with sessionid (no space)" "pp3.txt" "++ /dev/null$planted_sid"
csrf_token_32="$(printf 'Ab%.0s' $(seq 1 16))"
check blocked "++ b/ line with csrftoken" "pp4.txt" "++ b/$csrf_name=$csrf_token_32"
flat_json="{\"$sid_name\": \"1234567890%3A$(printf 'Ab%.0s' $(seq 1 12))\"}"
check blocked "flat JSON sessionid in non-session file" "config.txt" "$flat_json"
colon_sid="$sid_name: 1234567890%3A$(printf 'Ab%.0s' $(seq 1 12))"
check blocked "colon-format sessionid" "notes3.txt" "$colon_sid"
py_repr="{'"'"'$sid_name'"'"': '"'"'1234567890%3A$(printf 'Ab%.0s' $(seq 1 12))'"'"'}"
check blocked "Python repr with sessionid" "config.py" "$py_repr"
csrf_token_long="$(printf 'Ab%.0s' $(seq 1 16))"
check blocked "X-CSRFToken header" "headers.txt" "X-CSRFToken: $csrf_token_long"
check blocked "JSON csrftoken with 32 chars" "state.json" "{\"$csrf_name\": \"$csrf_token_long$csrf_token_long\"}"
# Binary regression cases: test with bytes written directly to disk
check_bytes blocked "invalid UTF-8 with sessionid" "binary-utf8.txt" '\xff\xfe%s\n' "$planted_sid"
# Create file with sessionid, 9000 a's, then NUL byte (no newline between)
check_bytes blocked "sessionid with NUL bytes in middle" "padded-nul.bin" '%s' "$(printf '%s' "$planted_sid"; head -c 9000 /dev/zero | tr '\0' 'a'; printf '\0')"
# UTF-16LE encoding of planted_sid (may be detected due to --text flag; mark as allowed if detected)
utf16le_sid="$(printf '%s' "$planted_sid" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null || printf '%s' "$planted_sid")"
check_bytes blocked "UTF-16LE sessionid (out of scope but detected by --text)" "utf16le.txt" '%s' "$utf16le_sid"
check allowed "prose that mentions csrftoken" "docs/csrf.md" "The csrftoken cookie is read from the jar"
check allowed "csrftoken with 15-char value (boundary)" "notes4.txt" "$csrf_name=$(printf 'Ab%.0s' $(seq 1 7))A"
check allowed "prose sessionid colon" "docs/auth.md" "sessionid: authentication cookie"
check allowed "prose about cookie name: sessionid" "docs/desc.md" "The cookie name: sessionid is required"
check allowed "code with name: csrftokenStore" "Store.kt" "val name: csrftokenStore"
check allowed "X-CSRFToken in code" "Api.kt" ".header(\"X-CSRFToken\", it)"
check allowed "csrftoken function call" "Cookies.kt" "cookies.cookieValue(url, \"csrftoken\")"
echo "secret guard: all checks passed"
