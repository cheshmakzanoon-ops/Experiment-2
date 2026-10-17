#!/bin/bash
# fetch.sh URL DEST   -- download with retries.
# Maven Central (behind Cloudflare) rate-limits bursts: it answers 429, and sometimes a bogus 404 that carries a
# Retry-After header.  Those are retried with a growing pause; a 404/403/410 without Retry-After is final.
# Uses the session proxy and CA exactly as configured in the environment (never disables TLS checks).
# Exit codes: 0 ok, 22 final HTTP error (404/403/410), 1 gave up.
set -u
url="$1"; dest="$2"
mkdir -p "$(dirname "$dest")"
tmp="$dest.part.$$"; hdr="$dest.hdr.$$"
trap 'rm -f "$tmp" "$hdr"' EXIT
for attempt in $(seq 1 14); do
  code=$(curl -sS -L --max-time 900 -D "$hdr" -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null) || code=000
  case "$code" in
    200) mv "$tmp" "$dest"; exit 0 ;;
    403|410) echo "fetch: HTTP $code for $url" >&2; exit 22 ;;
    404) if ! grep -qi '^retry-after:' "$hdr" 2>/dev/null; then echo "fetch: HTTP 404 for $url" >&2; exit 22; fi ;;
  esac
  pause=$(( attempt * 5 )); [ "$pause" -gt 45 ] && pause=45
  sleep "$pause"
done
echo "fetch: giving up on $url (last code $code)" >&2
exit 1
