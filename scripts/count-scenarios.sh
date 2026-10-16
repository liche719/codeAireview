#!/usr/bin/env bash
# Count scenario rows per section in docs/scenarios.md.
#
# Why this exists: the first version of that file shipped a summary table whose
# numbers did not match its own detail rows (A was 4/5/9 in the table but 3/5/10
# in the rows). Hand-counting 145 rows is how that happens, so this script makes
# the numbers reproducible and reviewable.
#
# A scenario row is a table line whose first cell looks like an id: letter(s) +
# digits, optionally wrapped in ** for emphasis. The summary table in section 0
# has numeric cells, and the S1-S3 finding rows also match the id pattern --
# hence the note in the doc that the raw total must have those 3 subtracted.
#
# Usage: bash scripts/count-scenarios.sh [path-to-scenarios.md]

set -u
file="${1:-docs/scenarios.md}"

if [ ! -f "$file" ]; then
  echo "no such file: $file" >&2
  exit 1
fi

awk '
  function flush() {
    if (section != "") {
      printf "%-52s rows=%-4d ok=%-4d partial=%-4d missing=%-4d\n", section, rows, ok, partial, missing
      total_rows += rows; total_ok += ok; total_partial += partial; total_missing += missing
    }
  }
  /^## / {
    flush()
    section = $0; sub(/^## /, "", section)
    rows = 0; ok = 0; partial = 0; missing = 0
    next
  }
  /^\|/ {
    n = split($0, f, "|")
    id = f[2]
    gsub(/^[ \t]+|[ \t]+$/, "", id)
    gsub(/\*/, "", id)
    if (id !~ /^[A-Z]+[0-9]+$/) next
    rows++
    if (index($0, "\342\234\205") > 0) ok++           # U+2705 check mark
    else if (index($0, "\342\232\240") > 0) partial++ # U+26A0 warning sign
    else if (index($0, "\342\235\214") > 0) missing++ # U+274C cross mark
    else printf "  !! row without a status emoji: %s\n", id
    next
  }
  END {
    flush()
    printf "%-52s rows=%-4d ok=%-4d partial=%-4d missing=%-4d\n", "TOTAL (includes the S1-S3 finding rows)", total_rows, total_ok, total_partial, total_missing
  }
' "$file"
