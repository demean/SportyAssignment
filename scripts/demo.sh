#!/usr/bin/env bash
#
# Walkthrough of the Jackpot Service API: bash + curl only (no jq).
#
#   scripts/demo.sh                          # against http://localhost:8080
#   scripts/demo.sh http://some-host:8080    # or: BASE_URL=... scripts/demo.sh
#
# Every run uses fresh bet ids, so the script can be run any number of times against the same stack. Before it
# snapshots a jackpot, it waits until earlier bets on that jackpot have been consumed (a 0.01 marker bet).
# Steps: list jackpots -> deterministic WON on jackpot-lucky (pool reset, next cycle) -> normal bet on
# jackpot-fixed -> duplicate bet id is a no-op -> unknown jackpot (422) -> error responses (400 / 404).
# Exit code: 0 when every check passed, 1 otherwise.

set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-30}"
RUN_ID="$(date +%Y%m%d%H%M%S)-$$"
USER_ID="demo-user"

HEADERS_FILE="$(mktemp "${TMPDIR:-/tmp}/jackpot-demo.XXXXXX")"
trap 'rm -f "$HEADERS_FILE"' EXIT

if [ -t 1 ]; then BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else BOLD=''; GREEN=''; RED=''; DIM=''; RESET=''; fi

FAILED_CHECKS=0
STEP=0
STATUS=''
BODY=''

step() {
    STEP=$((STEP + 1))
    printf '\n%s== %d. %s%s\n' "$BOLD" "$STEP" "$*" "$RESET"
}

note() {
    printf '%s# %s%s\n' "$DIM" "$*" "$RESET"
}

# request METHOD PATH [JSON_BODY]: prints the curl command, the status (+ Location / Retry-After) and the body;
# leaves the result in STATUS and BODY.
request() {
    local method="$1" path="$2" body="${3:-}" output header
    if [ -n "$body" ]; then
        printf "$ curl -X %s %s%s -H 'Content-Type: application/json' -d '%s'\n" "$method" "$BASE_URL" "$path" "$body"
        output="$(curl -sS -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body" \
            -D "$HEADERS_FILE" -w '\n%{http_code}')"
    else
        printf '$ curl %s%s\n' "$BASE_URL" "$path"
        output="$(curl -sS -X "$method" "$BASE_URL$path" -D "$HEADERS_FILE" -w '\n%{http_code}')"
    fi
    STATUS="${output##*$'\n'}"
    BODY="${output%$'\n'*}"
    printf 'HTTP %s\n' "$STATUS"
    for header in Location Retry-After; do
        grep -i "^${header}:" "$HEADERS_FILE" | tr -d '\r' || true
    done
    printf '%s\n' "$BODY"
}

# field NAME: value of the first top-level-looking "NAME":value pair of BODY (strings unquoted).
field() {
    printf '%s' "$BODY" | sed -n "s/^[^\"]*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p; s/.*[,{]\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -n 1
}

# check LABEL ACTUAL EXPECTED
check() {
    if [ "$2" = "$3" ]; then
        printf '%sOK%s   %s = %s\n' "$GREEN" "$RESET" "$1" "$2"
    else
        printf '%sFAIL%s %s = %s (expected %s)\n' "$RED" "$RESET" "$1" "$2" "$3"
        FAILED_CHECKS=$((FAILED_CHECKS + 1))
    fi
}

# await_processed BET_ID: polls the evaluation until the bet has been consumed (anything but 404), then prints it.
await_processed() {
    local bet_id="$1" attempts=0 max_attempts=$((POLL_TIMEOUT_SECONDS * 5)) code
    note "polling GET /api/v1/bets/$bet_id/evaluation until the consumer has processed the bet (404 = not yet)"
    while :; do
        code="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/bets/$bet_id/evaluation")"
        [ "$code" != "404" ] && break
        attempts=$((attempts + 1))
        if [ "$attempts" -ge "$max_attempts" ]; then
            printf '%sFAIL%s bet %s was not processed within %ss\n' "$RED" "$RESET" "$bet_id" "$POLL_TIMEOUT_SECONDS" >&2
            exit 1
        fi
        sleep 0.2
    done
    if [ "$attempts" -eq 0 ]; then note "already processed at the first poll"
    else note "processed after $attempts poll(s) of 200 ms"; fi
    request GET "/api/v1/bets/$bet_id/evaluation"
}

# drain JACKPOT_ID: waits until every earlier bet on the jackpot has been consumed, so the "before" snapshot that
# follows is not changed by a backlog (e.g. right after a restart of a lagging consumer group). It publishes a 0.01
# marker bet: its record key is the jackpot id (same partition, consumed in order), and it contributes 0.00, so it
# is never drawn and changes neither the pool nor the cycle.
drain() {
    local jackpot_id="$1" bet_id="demo-drain-$1-${RUN_ID}" code
    note "draining $jackpot_id: a 0.01 marker bet (contributes 0.00, never drawn) is consumed after any earlier bet"
    code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/bets" -H 'Content-Type: application/json' \
        -d "{\"betId\":\"$bet_id\",\"userId\":\"$USER_ID\",\"jackpotId\":\"$jackpot_id\",\"betAmount\":0.01}")"
    if [ "$code" != "202" ]; then
        printf '%sFAIL%s marker bet on %s answered HTTP %s\n' "$RED" "$RESET" "$jackpot_id" "$code" >&2
        exit 1
    fi
    await_processed "$bet_id" > /dev/null
}

# decimal arithmetic without bc/awk dependencies on the caller's locale: amounts have exactly 2 decimals
to_cents() { local v="$1"; printf '%s' "$((10#${v%.*} * 100 + 10#${v#*.}))"; }
from_cents() { printf '%d.%02d' "$(($1 / 100))" "$(($1 % 100))"; }

printf '%sJackpot Service demo%s  base URL: %s  run: %s\n' "$BOLD" "$RESET" "$BASE_URL" "$RUN_ID"
if ! curl -sS -o /dev/null --max-time 5 "$BASE_URL/api/v1/jackpots"; then
    printf '%sThe service is not reachable at %s.%s Start it with: docker compose up --build\n' "$RED" "$BASE_URL" "$RESET"
    exit 1
fi

# ---------------------------------------------------------------------------------------------------------------
step "List the seeded jackpots (pools and policies)"
request GET /api/v1/jackpots
check "HTTP status" "$STATUS" "200"

# ---------------------------------------------------------------------------------------------------------------
step "Deterministic WON on jackpot-lucky: state before"
drain jackpot-lucky
request GET /api/v1/jackpots/jackpot-lucky
LUCKY_INITIAL="$(field initialPoolAmount)"
LUCKY_POOL_BEFORE="$(field currentPoolAmount)"
LUCKY_CYCLE_BEFORE="$(field cycle)"
note "variable contribution 20 % (-1 % per 100 above the initial pool, min 5 %); variable chance, 100 % at pool >= 150"
note "a 250.00 stake always lifts this pool to >= 150.00, so the bet wins for sure; on a fresh pool: +50.00 -> 150.00"

step "Place a 250.00 bet on jackpot-lucky"
LUCKY_BET="demo-lucky-${RUN_ID}"
request POST /api/v1/bets "{\"betId\":\"$LUCKY_BET\",\"userId\":\"$USER_ID\",\"jackpotId\":\"jackpot-lucky\",\"betAmount\":250.00}"
check "HTTP status" "$STATUS" "202"

step "Did it win? (the evaluation stored while the bet was consumed)"
await_processed "$LUCKY_BET"
check "outcome" "$(field outcome)" "WON"
check "won" "$(field won)" "true"
check "winChancePercentage" "$(field winChancePercentage)" "100.0000"
LUCKY_REWARD="$(field rewardAmount)"

step "Its contribution (Current Jackpot Amount = pool right after this bet)"
request GET "/api/v1/bets/$LUCKY_BET/contribution"
check "HTTP status" "$STATUS" "200"
check "rewardAmount = currentJackpotAmount (the whole pool is paid out)" "$LUCKY_REWARD" "$(field currentJackpotAmount)"
if [ "$LUCKY_POOL_BEFORE" = "$LUCKY_INITIAL" ]; then
    check "contributionAmount on a fresh pool (20 % of 250.00)" "$(field contributionAmount)" "50.00"
fi

step "jackpot-lucky after the win: pool reset to its initial value, next cycle"
request GET /api/v1/jackpots/jackpot-lucky
check "currentPoolAmount" "$(field currentPoolAmount)" "$LUCKY_INITIAL"
check "cycle" "$(field cycle)" "$((LUCKY_CYCLE_BEFORE + 1))"

# ---------------------------------------------------------------------------------------------------------------
step "A normal bet: 100.00 on jackpot-fixed (5 % contribution, 1 % win chance)"
drain jackpot-fixed
request GET /api/v1/jackpots/jackpot-fixed
FIXED_POOL_BEFORE="$(field currentPoolAmount)"
FIXED_BET="demo-fixed-${RUN_ID}"
request POST /api/v1/bets "{\"betId\":\"$FIXED_BET\",\"userId\":\"$USER_ID\",\"jackpotId\":\"jackpot-fixed\",\"betAmount\":100.00}"
check "HTTP status" "$STATUS" "202"
await_processed "$FIXED_BET"
check "winChancePercentage" "$(field winChancePercentage)" "1.0000"
note "outcome $(field outcome): a fixed 1 % chance, so LOST in 99 of 100 runs"
request GET "/api/v1/bets/$FIXED_BET/contribution"
check "contributionAmount (5 % of 100.00)" "$(field contributionAmount)" "5.00"
check "currentJackpotAmount" "$(field currentJackpotAmount)" "$(from_cents $(($(to_cents "$FIXED_POOL_BEFORE") + 500)))"
request GET "/api/v1/bets/$FIXED_BET"
check "status" "$(field status)" "CONTRIBUTED"

# ---------------------------------------------------------------------------------------------------------------
step "Duplicate bet id: re-publishing $FIXED_BET is accepted but never processed twice"
request GET /api/v1/jackpots/jackpot-fixed
FIXED_POOL_AFTER_FIRST="$(field currentPoolAmount)"
request POST /api/v1/bets "{\"betId\":\"$FIXED_BET\",\"userId\":\"$USER_ID\",\"jackpotId\":\"jackpot-fixed\",\"betAmount\":100.00}"
check "HTTP status" "$STATUS" "202"
note "a marker bet on the same jackpot shares the partition (key = jackpotId), so it is consumed after the duplicate"
MARKER_BET="demo-marker-${RUN_ID}"
request POST /api/v1/bets "{\"betId\":\"$MARKER_BET\",\"userId\":\"$USER_ID\",\"jackpotId\":\"jackpot-fixed\",\"betAmount\":20.00}"
await_processed "$MARKER_BET" > /dev/null
request GET "/api/v1/bets/$MARKER_BET/contribution"
check "marker currentJackpotAmount (pool + 1.00 only: the duplicate added nothing)" \
    "$(field currentJackpotAmount)" "$(from_cents $(($(to_cents "$FIXED_POOL_AFTER_FIRST") + 100)))"

# ---------------------------------------------------------------------------------------------------------------
step "A bet for an unknown jackpot is stored, but does not contribute"
UNKNOWN_BET="demo-unknown-${RUN_ID}"
request POST /api/v1/bets "{\"betId\":\"$UNKNOWN_BET\",\"userId\":\"$USER_ID\",\"jackpotId\":\"jackpot-does-not-exist\",\"betAmount\":10.00}"
check "HTTP status" "$STATUS" "202"
await_processed "$UNKNOWN_BET"
check "evaluation HTTP status" "$STATUS" "422"
check "code" "$(field code)" "BET_NOT_CONTRIBUTING"
request GET "/api/v1/bets/$UNKNOWN_BET"
check "status" "$(field status)" "NO_MATCHING_JACKPOT"

# ---------------------------------------------------------------------------------------------------------------
step "Errors are RFC 9457 problem details with a stable code"
request POST /api/v1/bets "{\"betId\":\"\",\"userId\":\"$USER_ID\",\"jackpotId\":\"jackpot-fixed\",\"betAmount\":0.001}"
check "HTTP status" "$STATUS" "400"
check "code" "$(field code)" "VALIDATION_FAILED"
request GET "/api/v1/bets/demo-never-placed-${RUN_ID}/evaluation"
check "HTTP status" "$STATUS" "404"
check "code" "$(field code)" "BET_NOT_FOUND"
request GET /api/v1/jackpots/jackpot-does-not-exist
check "HTTP status" "$STATUS" "404"
check "code" "$(field code)" "JACKPOT_NOT_FOUND"

# ---------------------------------------------------------------------------------------------------------------
printf '\n'
if [ "$FAILED_CHECKS" -eq 0 ]; then
    printf '%sAll checks passed.%s\n' "$GREEN" "$RESET"
else
    printf '%s%d check(s) failed.%s\n' "$RED" "$FAILED_CHECKS" "$RESET"
    exit 1
fi
