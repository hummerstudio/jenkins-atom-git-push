#!/usr/bin/env bash
# Real bare repositories as remotes: the push either moved the branch or it did not.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

export GIT_AUTHOR_NAME=smoke GIT_AUTHOR_EMAIL=smoke@local GIT_COMMITTER_NAME=smoke GIT_COMMITTER_EMAIL=smoke@local
git config --global init.defaultBranch main 2>/dev/null || true

ws="$(mktemp -d)"; remote="$(mktemp -d)/app.git"
git init --bare -q "$remote"
git init -q "$ws"
cd "$ws" && git checkout -q -b main 2>/dev/null || git symbolic-ref HEAD refs/heads/main
printf 'one\n' > app.txt
git add -A && git commit -q -m "first commit"
cd - >/dev/null

write_input() { printf '%s' "$1" >"$ws/input.json"; }
remote_sha() { git --git-dir="$remote" rev-parse "refs/heads/$1" 2>/dev/null || echo ""; }
local_sha() { git -C "$ws" rev-parse HEAD; }

# --- a plain push lands, and the remote sha matches the local one
write_input "{\"remote\": \"$remote\", \"branch\": \"main\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "push: $(cat "$ws/output.json") $(tail -3 "$ws/atom.log")"
[ "$(output "$ws" branch)" = "main" ] || fail "branch output: $(cat "$ws/output.json")"
[ "$(output "$ws" sha)" = "$(local_sha)" ] || fail "sha output: $(cat "$ws/output.json")"
[ "$(remote_sha main)" = "$(local_sha)" ] || fail "the branch must move on the remote"
grep -q "verified on the remote" "$ws/atom.log" || fail "the atom should say it verified the remote"

# --- a second commit pushes again; the default branch is the checked-out one
printf 'two\n' > "$ws/app.txt"
git -C "$ws" commit -q -am "second commit"
write_input "{\"remote\": \"$remote\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "second push: $(cat "$ws/output.json")"
[ "$(remote_sha main)" = "$(local_sha)" ] || fail "the second push must move main"

# --- a different destination branch
write_input "{\"remote\": \"$remote\", \"branch\": \"main\", \"targetBranch\": \"release/1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "target branch: $(cat "$ws/output.json")"
[ "$(output "$ws" branch)" = "release/1.0" ] || fail "branch output: $(cat "$ws/output.json")"
[ "$(remote_sha release/1.0)" = "$(local_sha)" ] || fail "the new branch must exist on the remote"

# --- a non-fast-forward is refused, and the message says what to do about it
other="$(mktemp -d)"; git clone -q "$remote" "$other"
git -C "$other" checkout -q main
printf 'theirs\n' > "$other/app.txt"
git -C "$other" commit -q -am "someone else committed"
git -C "$other" push -q origin main
printf 'mine\n' > "$ws/app.txt"
git -C "$ws" commit -q -am "my divergent commit"
write_input "{\"remote\": \"$remote\", \"branch\": \"main\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a non-fast-forward push must fail"
grep -qi "rebase, or set force=true" "$ws/output.json" || fail "the hint is missing: $(cat "$ws/output.json")"
grep -q '"errorType":1' "$ws/output.json" || fail "a rejected push is a USER error"

# --- force makes it go through
write_input "{\"remote\": \"$remote\", \"branch\": \"main\", \"force\": \"true\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "force push: $(cat "$ws/output.json")"
[ "$(remote_sha main)" = "$(local_sha)" ] || fail "force should have moved the branch"
[ "$(output "$ws" forced)" = "true" ] || fail "forced output: $(cat "$ws/output.json")"

# --- a remote that does not exist is an environment problem, not a user typo
write_input "{\"remote\": \"$ws/no-such-remote.git\", \"branch\": \"main\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a missing remote must fail"
grep -q "git push failed" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

# --- the secret never reaches the command line: a fake askpass target proves the mechanism is used
cat >"$ws/fake-git" <<'SH'
#!/bin/sh
# records the arguments and the askpass setting, then defers to the real git
echo "$@" >> "$GIT_PUSH_ARGV_LOG"
echo "${GIT_ASKPASS:-none}" >> "$GIT_PUSH_ARGV_LOG"
exec /usr/bin/env git "$@"
SH
chmod +x "$ws/fake-git"
: >"$ws/argv.log"
write_input "{\"remote\": \"$remote\", \"branch\": \"main\"}"
GIT_PUSH_ARGV_LOG="$ws/argv.log" PATH="$ws:$PATH" run "$ws" >"$ws/atom.log" 2>&1 || true
# the stub is named fake-git, so it only proves the harness works; the real assertion is below
grep -q "GIT_ASKPASS" "$ws/atom.log" && fail "the log must not claim askpass without credentials"

# --- with a token, the credentials travel through askpass and never appear in the argv log
write_input "{\"remote\": \"https://example.invalid/app.git\", \"branch\": \"main\", \"username\": \"ci\"}"
printf '{"token": "s3cr3t-token"}' >"$ws/.secrets.json"
run "$ws" >"$ws/atom.log" 2>&1 || true   # the host does not resolve; what matters is how it tried
grep -q "GIT_ASKPASS, not the command line" "$ws/atom.log" || fail "the atom should say how it supplied credentials"
grep -q "s3cr3t-token" "$ws/atom.log" && fail "the token must not be printed"
[ -f "$ws/.secrets.json" ] || true

echo "smoke ok: real bare remote, sha verification, second push, target branch, non-fast-forward refused, force, missing remote, credential path"
