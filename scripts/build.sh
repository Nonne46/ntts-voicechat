#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
original_path=$PATH

java_major() {
  "$1/bin/java" -version 2>&1 | awk -F '[\".]' '/version/ { print ($2 == 1 ? $3 : $2); exit }'
}

select_java_home() {
  local generation=$1
  local target=$2
  local env_name="JAVA_HOME_${target}_X64"
  local env_home=${!env_name:-}
  local current_java current_home candidate major
  current_java=$(PATH="$original_path" command -v java || true)
  current_home=
  if [[ -n "$current_java" ]]; then
    current_home=$(dirname "$(dirname "$(readlink -f "$current_java")")")
  fi

  for candidate in "${JAVA_HOME:-}" "$env_home" "/usr/lib/jvm/java-${target}-openjdk" "$current_home" /usr/lib/jvm/java-*-openjdk; do
    [[ -x "$candidate/bin/java" ]] || continue
    major=$(java_major "$candidate")
    case "$generation" in
      doggyman)
        (( major >= target && major <= 23 )) && { printf '%s\n' "$candidate"; return; }
        ;;
      ping_9)
        (( major >= target && major <= 26 )) && { printf '%s\n' "$candidate"; return; }
        ;;
      unlimited_damage)
        (( major >= 25 && major <= 26 )) && { printf '%s\n' "$candidate"; return; }
        ;;
    esac
  done

  echo "No compatible JDK found for Minecraft profile targeting Java ${target} (${generation} build)." >&2
  if [[ "$generation" == unlimited_damage ]]; then
    echo 'Install JDK 25 or 26, or set JAVA_HOME_25_X64.' >&2
  else
    echo "Install JDK ${target} through 23, or set ${env_name}." >&2
  fi
  return 1
}

usage() {
  cat <<'EOF'
Usage:
  scripts/build.sh [fabric|forge|all] [minecraft-version|all] [mod-version]
  scripts/build.sh <minecraft-version> [mod-version]

Defaults: loader=all, minecraft-version=all

Examples:
  scripts/build.sh                       # both loaders, every supported version
  scripts/build.sh fabric                # Fabric, every supported version
  scripts/build.sh forge 1.20.1          # Forge 1.20.1
  scripts/build.sh 1.21.11               # both loaders, Minecraft 1.21.11
  scripts/build.sh all all 1.1.0         # release-style build of everything
EOF
}

loader=all
minecraft_version=all
mod_version=

if [[ $# -gt 0 ]]; then
  case "$1" in
    fabric|forge|all)
      loader=$1
      shift
      if [[ $# -gt 0 ]]; then
        minecraft_version=$1
        shift
      fi
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      minecraft_version=$1
      shift
      ;;
  esac
fi

if [[ $# -gt 0 ]]; then
  mod_version=$1
  shift
fi
if [[ $# -gt 0 ]]; then
  usage >&2
  exit 2
fi

case "$loader" in
  fabric) build_task=:fabric:build ;;
  forge) build_task=:forge:build ;;
  all) build_task=buildAll ;;
  *)
    echo "Unknown loader: $loader" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ "$minecraft_version" == all ]]; then
  mapfile -t minecraft_versions < <(grep -Ev '^\s*(#|$)' versions/supported.txt)
else
  if [[ ! -f "versions/${minecraft_version}.properties" ]]; then
    echo "Unknown Minecraft version: ${minecraft_version}" >&2
    echo 'Available profiles:' >&2
    find versions -maxdepth 1 -name '*.properties' -printf '  %f\n' | sed 's/\.properties$//' | sort >&2
    exit 2
  fi
  minecraft_versions=("$minecraft_version")
fi

if [[ ${#minecraft_versions[@]} -eq 0 ]]; then
  echo 'No Minecraft versions are listed in versions/supported.txt' >&2
  exit 1
fi

rm -rf dist
mkdir -p dist

for version in "${minecraft_versions[@]}"; do
  generation=$(awk -F= '$1 == "build_generation" { print $2 }' "versions/${version}.properties")
  generation=${generation:-doggyman}

  case "$generation" in
    doggyman)
      wrapper=./gradlew
      project_dir=.
      artifact_root=.
      ;;
    ping_9)
      wrapper=./ping_9/gradlew
      project_dir=ping_9
      artifact_root=ping_9
      ;;
    unlimited_damage)
      wrapper=./unlimited_damage/gradlew
      project_dir=unlimited_damage
      artifact_root=unlimited_damage
      ;;
    *)
      echo "Unknown build generation '${generation}' in versions/${version}.properties" >&2
      exit 1
      ;;
  esac

  target_java=$(awk -F= '$1 == "java_version" { print $2 }' "versions/${version}.properties")
  build_java_home=$(select_java_home "$generation" "$target_java")

  echo "==> Building ${loader} for Minecraft ${version} (${generation} toolchain, Java $(java_major "$build_java_home"))"
  arguments=(--no-daemon --project-dir "$project_dir" clean "$build_task" "-PmcVersion=${version}")
  if [[ -n "$mod_version" ]]; then
    arguments+=("-PmodVersion=${mod_version}")
  fi
  env JAVA_HOME="$build_java_home" PATH="$build_java_home/bin:$original_path" bash "$wrapper" "${arguments[@]}"
  bash scripts/collect-artifacts.sh "$loader" "$version" dist "$artifact_root"
done

printf '\nBuilt artifacts:\n'
find dist -maxdepth 1 -type f -name '*.jar' -printf '%f\n' | sort
