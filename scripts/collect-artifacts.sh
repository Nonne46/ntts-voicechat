#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 4 ]]; then
  echo "Usage: $0 <fabric|forge|all> <minecraft-version> [destination] [build-root]" >&2
  exit 2
fi

loader=$1
minecraft_version=$2
destination=${3:-dist}
build_root=${4:-.}
mkdir -p "$destination"

case "$loader" in
  fabric|forge) loaders=("$loader") ;;
  all) loaders=(fabric forge) ;;
  *)
    echo "Unknown loader: $loader" >&2
    exit 2
    ;;
esac

for current_loader in "${loaders[@]}"; do
  mapfile -t jars < <(
    find "$build_root/$current_loader/build/libs" -maxdepth 1 -type f \
      -name "ntts-${current_loader}-${minecraft_version}-*.jar" \
      ! -name '*-dev.jar' \
      ! -name '*-sources.jar' \
      | sort
  )
  if [[ ${#jars[@]} -ne 1 ]]; then
    echo "Expected one release jar for ${current_loader} ${minecraft_version}, found ${#jars[@]}" >&2
    printf '  %s\n' "${jars[@]:-}" >&2
    exit 1
  fi
  unzip -tq "${jars[0]}" >/dev/null
  cp "${jars[0]}" "$destination/"
done
