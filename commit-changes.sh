#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Stage all detected changes, commit, and optionally push.

Options:
  -m, --message MSG     Commit message (required unless -e is used)
  -e, --edit            Open editor to write commit message
  -p, --push            Push to remote after committing
  -a, --include-all     Also stage untracked files (default stages tracked changes only)
  -n, --dry-run         Show what would be staged/committed without doing it
  -y, --yes             Skip confirmation prompt
  -h, --help            Show this help and exit

Examples:
  $(basename "$0") -m "Fix crash on startup" -p
  $(basename "$0") --message "Add new feature" --include-all --push
  $(basename "$0") -e -p
  $(basename "$0") --dry-run
EOF
}

MESSAGE=""
EDIT_MSG=0
PUSH=0
INCLUDE_UNTRACKED=0
DRY_RUN=0
ASSUME_YES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -m|--message)
      [[ $# -lt 2 ]] && { echo "Error: $1 requires an argument" >&2; exit 2; }
      MESSAGE="$2"
      shift 2
      ;;
    -e|--edit)
      EDIT_MSG=1
      shift
      ;;
    -p|--push)
      PUSH=1
      shift
      ;;
    -a|--include-all)
      INCLUDE_UNTRACKED=1
      shift
      ;;
    -n|--dry-run)
      DRY_RUN=1
      shift
      ;;
    -y|--yes)
      ASSUME_YES=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Error: unknown option '$1'" >&2
      usage >&2
      exit 2
      ;;
    *)
      echo "Error: unexpected argument '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ $EDIT_MSG -eq 0 && -z "$MESSAGE" && $DRY_RUN -eq 0 ]]; then
  echo "Error: a commit message is required (use -m \"...\" or -e to edit)" >&2
  usage >&2
  exit 2
fi

echo "==> Sanity check: keystore / secrets must not be tracked"
if git ls-files | grep -iE "keystore|\.jks$|local\.properties|secret|password"; then
  echo "ABORT: sensitive file(s) tracked. Resolve before continuing."
  exit 1
fi

echo
echo "==> Detecting changes"

read_lines() {
  # Portable replacement for `mapfile -t VAR < <(cmd)` that works in bash 3.2.
  # Usage: read_lines VAR_NAME < <(cmd)
  local __var="$1"
  local __line
  eval "$__var=()"
  while IFS= read -r __line; do
    eval "$__var+=(\"\$__line\")"
  done
}

MODIFIED_FILES=()
STAGED_FILES=()
DELETED_FILES=()
UNTRACKED_FILES=()

read_lines MODIFIED_FILES   < <(git diff --name-only --diff-filter=ACMRTUXB)
read_lines STAGED_FILES     < <(git diff --cached --name-only)
read_lines DELETED_FILES    < <(git ls-files --deleted)
read_lines UNTRACKED_FILES  < <(git ls-files --others --exclude-standard)

if [[ ${#MODIFIED_FILES[@]} -eq 0 && ${#STAGED_FILES[@]} -eq 0 && ${#DELETED_FILES[@]} -eq 0 \
      && ( $INCLUDE_UNTRACKED -eq 0 || ${#UNTRACKED_FILES[@]} -eq 0 ) ]]; then
  echo "Nothing to commit (working tree clean)."
  exit 0
fi

print_list() {
  local label="$1"; shift
  if [[ $# -gt 0 ]]; then
    echo "  $label:"
    printf '    %s\n' "$@"
  fi
}

[[ ${#MODIFIED_FILES[@]} -gt 0 ]] && print_list "Modified"       "${MODIFIED_FILES[@]}"
[[ ${#STAGED_FILES[@]}   -gt 0 ]] && print_list "Already staged" "${STAGED_FILES[@]}"
[[ ${#DELETED_FILES[@]}  -gt 0 ]] && print_list "Deleted"        "${DELETED_FILES[@]}"
if [[ $INCLUDE_UNTRACKED -eq 1 ]]; then
  [[ ${#UNTRACKED_FILES[@]} -gt 0 ]] && print_list "Untracked (will add)" "${UNTRACKED_FILES[@]}"
elif [[ ${#UNTRACKED_FILES[@]} -gt 0 ]]; then
  echo "  Untracked (skipped, pass -a to include):"
  printf '    %s\n' "${UNTRACKED_FILES[@]}"
fi

echo
echo "==> Staging changes"
if [[ $DRY_RUN -eq 1 ]]; then
  echo "  (dry-run: skipping git add)"
else
  if [[ $INCLUDE_UNTRACKED -eq 1 ]]; then
    git add -A
  else
    git add -u
  fi
fi

echo
echo "==> Staged for commit:"
git diff --cached --stat

if [[ $DRY_RUN -eq 1 ]]; then
  echo
  echo "Dry run complete. No commit was made."
  exit 0
fi

if [[ -z "$(git diff --cached --name-only)" ]]; then
  echo "Nothing staged after add. Aborting."
  exit 0
fi

if [[ $ASSUME_YES -eq 0 ]]; then
  echo
  read -r -p "Proceed with commit? [y/N] " reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 1 ;;
  esac
fi

echo
echo "==> Committing"
if [[ $EDIT_MSG -eq 1 && -z "$MESSAGE" ]]; then
  git commit
elif [[ $EDIT_MSG -eq 1 ]]; then
  git commit -e -m "$MESSAGE"
else
  git commit -m "$MESSAGE"
fi

echo
echo "==> Recent log:"
git log --oneline -5

if [[ $PUSH -eq 1 ]]; then
  echo
  echo "==> Pushing"
  git push
else
  echo
  echo "Push with:  git push   (or re-run with -p)"
fi
