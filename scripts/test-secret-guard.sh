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
cp "$root/.githooks/pre-commit" "$tmp/.githooks/pre-commit"
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
check blocked "added line starting with ++" "pp.txt" "++token $planted_sid"
check allowed "prose that mentions csrftoken" "docs/csrf.md" "The csrftoken cookie is read from the jar"
echo "secret guard: all checks passed"
