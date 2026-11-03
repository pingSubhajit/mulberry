#!/usr/bin/env bash
# Installs an agent skill into <repo>/.agents/skills/<name> and links it at
# <repo>/.claude/skills/<name> -> ../../.agents/skills/<name>.
#
# usage: install.sh <source> [--path SUBDIR] [--name NAME] [--force]
#   source  GitHub repo URL, GitHub /tree/<ref>/<dir> URL, any git URL, or a local folder
#   --path  folder inside the source that holds the skill's SKILL.md
#   --name  install under this name instead of the SKILL.md `name:` field
#   --force replace an existing install of the same name
#
# Exit codes: 0 installed, 1 usage/fetch error, 2 no SKILL.md found,
#             3 several skills found (re-run with --path), 4 name already installed.
#
# The skill's own files are copied, never executed.

set -euo pipefail

usage() { sed -n '5,10p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 1; }

source_arg=""
subpath=""
name_override=""
force=0
while [ $# -gt 0 ]; do
  case "$1" in
    --path) subpath="${2:-}"; shift 2 ;;
    --path=*) subpath="${1#--path=}"; shift ;;
    --name) name_override="${2:-}"; shift 2 ;;
    --name=*) name_override="${1#--name=}"; shift ;;
    --force) force=1; shift ;;
    -h|--help) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) [ -z "$source_arg" ] || usage; source_arg="$1"; shift ;;
  esac
done
[ -n "$source_arg" ] || usage

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "run this from inside the project's git repo" >&2; exit 1; }
agents_dir="$repo_root/.agents/skills"
claude_dir="$repo_root/.claude/skills"

work="$(mktemp -d "${TMPDIR:-/tmp}/install-skill.XXXXXX")"
trap 'rm -rf "$work"' EXIT

commit="n/a (local folder)"
if [ -d "$source_arg" ]; then
  src_root="$(cd "$source_arg" && pwd)"
  if git -C "$src_root" rev-parse HEAD >/dev/null 2>&1; then
    commit="$(git -C "$src_root" rev-parse --short HEAD) (local checkout)"
  fi
else
  url="$source_arg"
  ref=""
  # https://github.com/<owner>/<repo>/tree/<ref>/<dir...> or /blob/<ref>/<dir>/SKILL.md
  if printf '%s' "$url" | grep -Eq '^https?://github\.com/[^/]+/[^/]+/(tree|blob)/'; then
    rest="$(printf '%s' "$url" | sed -E 's#^https?://github\.com/[^/]+/[^/]+/(tree|blob)/##')"
    ref="${rest%%/*}"
    tree_path=""
    [ "$rest" = "$ref" ] || tree_path="${rest#*/}"
    tree_path="${tree_path%/SKILL.md}"
    url="$(printf '%s' "$url" | sed -E 's#^(https?://github\.com/[^/]+/[^/]+)/(tree|blob)/.*#\1#')"
    [ -n "$subpath" ] || subpath="$tree_path"
  fi
  url="${url%/}"
  src_root="$work/src"
  if [ -n "$ref" ]; then
    git clone --quiet --depth 1 --branch "$ref" "$url" "$src_root" 2>/dev/null \
      || { echo "could not clone $url at '$ref' (branch names with slashes: pass the repo URL and --path instead)" >&2; exit 1; }
  else
    git clone --quiet --depth 1 "$url" "$src_root" || { echo "could not clone $url" >&2; exit 1; }
  fi
  commit="$(git -C "$src_root" rev-parse --short HEAD)"
fi

base="$src_root"
if [ -n "$subpath" ]; then
  base="$src_root/${subpath#/}"
  [ -d "$base" ] || { echo "no folder '$subpath' in the source" >&2; exit 2; }
fi

if [ -f "$base/SKILL.md" ]; then
  skill_dir="$base"
else
  found="$(find "$base" -name SKILL.md -not -path '*/.git/*' -not -path '*/node_modules/*' | sort)"
  count="$(printf '%s' "$found" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then
    echo "no SKILL.md found in the source" >&2
    exit 2
  elif [ "$count" -gt 1 ]; then
    echo "several skills found; re-run with --path <one of these>:" >&2
    printf '%s\n' "$found" | while IFS= read -r f; do
      rel="$(dirname "${f#"$src_root"/}")"
      desc="$(awk '
        NR==1 && $0!="---" {exit}
        NR>1 && $0=="---" {exit}
        block { sub(/^[ ]+/, ""); print substr($0, 1, 100); exit }
        /^description:/ { sub(/^description:[ ]*/, ""); if ($0 ~ /^[>|][-+]?$/) { block=1; next } print substr($0, 1, 100); exit }
      ' "$f")"
      echo "  $rel  —  $desc" >&2
    done
    exit 3
  fi
  skill_dir="$(dirname "$found")"
fi

fm_name="$(awk 'NR==1&&$0!="---"{exit} NR>1&&$0=="---"{exit} /^name:/{sub(/^name:[ ]*/,"");gsub(/["'\'']/,"");print;exit}' "$skill_dir/SKILL.md")"
name="${name_override:-${fm_name:-$(basename "$skill_dir")}}"
if ! printf '%s' "$name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*$'; then
  echo "unusable skill name '$name'; pass --name" >&2
  exit 1
fi

dest="$agents_dir/$name"
link="$claude_dir/$name"
if [ -e "$dest" ] || [ -L "$link" ] || [ -e "$link" ]; then
  if [ "$force" -ne 1 ]; then
    echo "'$name' is already installed:" >&2
    [ -e "$dest" ] && echo "  $dest" >&2
    { [ -L "$link" ] || [ -e "$link" ]; } && ls -ld "$link" >&2
    echo "re-run with --force to replace it, or --name to install alongside" >&2
    exit 4
  fi
  if [ -e "$link" ] && [ ! -L "$link" ]; then
    echo "$link is a real folder, not a link; move it aside by hand first" >&2
    exit 4
  fi
  rm -rf "$dest"
  rm -f "$link"
fi

mkdir -p "$agents_dir" "$claude_dir"
cp -R "$skill_dir" "$dest"
rm -rf "$dest/.git"
find "$dest" -name .DS_Store -delete
ln -s "../../.agents/skills/$name" "$link"
[ -f "$link/SKILL.md" ] || { echo "link $link does not resolve to SKILL.md" >&2; exit 1; }

echo "installed: $name"
echo "source:    $source_arg${subpath:+ (path: $subpath)}"
echo "commit:    $commit"
echo "folder:    ${dest#"$repo_root"/}"
echo "link:      ${link#"$repo_root"/} -> ../../.agents/skills/$name"
license="$(awk 'NR==1&&$0!="---"{exit} NR>1&&$0=="---"{exit} /^license:/{sub(/^license:[ ]*/,"");print;exit}' "$dest/SKILL.md")"
[ -z "$license" ] && for f in "$skill_dir"/LICENSE* "$src_root"/LICENSE*; do [ -f "$f" ] && { license="$(head -1 "$f")"; break; }; done
echo "license:   ${license:-not stated}"
echo "contents:"
(cd "$dest" && find . -mindepth 1 -maxdepth 2 -not -name '.*' | sed 's#^\./#  #' | sort)

# Setup notes from the source README (deleted with the temp clone after this).
for readme in "$skill_dir/README.md" "$src_root/README.md"; do
  [ -f "$readme" ] || continue
  notes="$(awk '
    /^#+[ ]/ { keep = tolower($0) ~ /(install|requirement|setup|dependenc|prerequisite)/; if (keep) print; next }
    keep { print }
  ' "$readme")"
  if [ -n "$notes" ]; then
    echo "readme setup notes (${readme#"$src_root"/}):"
    printf '%s\n' "$notes" | sed 's/^/  | /'
  fi
  break
done
