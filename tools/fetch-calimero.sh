#!/usr/bin/env bash
# Fetches the pinned Calimero sources into third_party/src/<name> (shallow, exact commit).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party/src
grep -v '^#' third_party/PINS.txt | while read -r name url sha; do
  [ -z "${name:-}" ] && continue
  dir="third_party/src/$name"
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD)" = "$sha" ]; then
    echo "$name already at $sha"; continue
  fi
  rm -rf "$dir"; mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote add origin "$url"
  git -C "$dir" fetch -q --depth 1 origin "$sha"
  git -C "$dir" checkout -q FETCH_HEAD
  for pt in third_party/patches/"$name"-*.patch; do   # vKNX patches (see third_party/patches/README.md)
    [ -f "$pt" ] && git -C "$dir" apply --ignore-whitespace --whitespace=nowarn "$(pwd)/$pt" && echo "  applied $pt"
  done
  echo "$name -> $(git -C "$dir" rev-parse HEAD)"
done
