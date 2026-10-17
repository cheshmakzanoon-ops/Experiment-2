#!/bin/bash
# mvn.sh GROUP:ARTIFACT:VERSION[:EXT[:CLASSIFIER]]   -- download one artifact from Maven Central into dl/<g>_<a>_<v>/ and verify its SHA-1.
# Prints the path of the verified file on stdout.  Every artifact lives in its own directory (untrusted data).
# Environment: AC_ROOT = workspace root (defaults to the parent of this script's directory).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AC_ROOT="${AC_ROOT:-$(dirname "$HERE")}"
IFS=: read -r g a v ext cls <<<"$1"
ext="${ext:-jar}"
suffix=""; [ -n "${cls:-}" ] && suffix="-$cls"
base="https://repo.maven.apache.org/maven2/$(echo "$g" | tr . /)/$a/$v/$a-$v$suffix"
dir="$AC_ROOT/dl/${g}_${a}_${v}"
file="$dir/$a-$v$suffix.$ext"
if [ -s "$file" ] && [ -s "$file.sha1" ] && [ "$(sha1sum "$file" | cut -d' ' -f1)" = "$(tr -d ' \n' <"$file.sha1" | cut -c1-40)" ]; then
  echo "$file"; exit 0
fi
mkdir -p "$dir"
bash "$HERE/fetch.sh" "$base.$ext" "$file" >&2
bash "$HERE/fetch.sh" "$base.$ext.sha1" "$file.sha1" >&2
want="$(tr -d ' \n' <"$file.sha1" | cut -c1-40)"
have="$(sha1sum "$file" | cut -d' ' -f1)"
if [ "$want" != "$have" ]; then
  echo "mvn.sh: SHA-1 mismatch for $1 (want $want, have $have)" >&2
  rm -f "$file" "$file.sha1"
  exit 3
fi
echo "$file"
