#!/usr/bin/env bash
# Writes the Markdown release notes for the commits since the last release, grouped by Conventional Commit type:
#
#   💥 Breaking changes   type!: subjects, or a BREAKING CHANGE: / BREAKING: footer (version.yml's major rule)
#   🚀 Features           feat
#   🐛 Bug fixes          fix
#   👷 CI                 ci
#   📦 Others             every other type, docs: and test: included
#
# Each entry reads "type(scope): subject #PR", oldest first, taking the PR from the " (#N)" a squash merge ends its
# subject with; a commit pushed without a PR links itself instead. Empty sections are left out.
#
# Usage: release-notes.sh <previous-tag> [ref] [tag]
#   previous-tag  the last release's tag; empty for the first release (every commit up to ref)
#   ref           the commit being released (default HEAD)
#   tag           the release's own tag, for the Full Changelog link (default: ref's short commit)
#   GITHUB_REPOSITORY  owner/repo for the Full Changelog link (default: from the origin remote)
set -euo pipefail

prev_tag=${1-}
ref=${2:-HEAD}
to=${3:-$(git rev-parse --short "$ref")}
repo=${GITHUB_REPOSITORY:-$(git remote get-url origin | sed -E 's#^(https://github\.com/|git@github\.com:)##; s#\.git$##')}

range=$ref
if [ -n "$prev_tag" ]; then range="$prev_tag..$ref"; fi

breaking=() features=() fixes=() ci=() others=()

# Listed first, so a bad range fails the script instead of giving empty notes.
commits=$(git rev-list --reverse "$range")
for sha in $commits; do
  subject=$(git log -1 --format=%s "$sha")
  message=$(git log -1 --format=%B "$sha")

  if [[ $subject =~ ^(.*)\ \(#([0-9]+)\)$ ]]; then
    entry="* ${BASH_REMATCH[1]} #${BASH_REMATCH[2]}"
  else
    entry="* $subject ${sha::7}"
  fi

  if grep -qE '!:|BREAKING CHANGE:|BREAKING:' <<<"$message"; then
    breaking+=("$entry")
  elif [[ $subject =~ ^feat(\(.*\))?: ]]; then
    features+=("$entry")
  elif [[ $subject =~ ^fix(\(.*\))?: ]]; then
    fixes+=("$entry")
  elif [[ $subject =~ ^ci(\(.*\))?: ]]; then
    ci+=("$entry")
  else
    others+=("$entry")
  fi
done

section() {
  local title=$1
  shift
  if [ $# -gt 0 ]; then
    printf '## %s\n\n' "$title"
    printf '%s\n' "$@"
    printf '\n'
  fi
}

section "💥 Breaking changes" "${breaking[@]}"
section "🚀 Features" "${features[@]}"
section "🐛 Bug fixes" "${fixes[@]}"
section "👷 CI" "${ci[@]}"
section "📦 Others" "${others[@]}"

if [ -n "$prev_tag" ]; then
  printf '**Full Changelog**: https://github.com/%s/compare/%s...%s\n' "$repo" "$prev_tag" "$to"
else
  printf '**Full Changelog**: https://github.com/%s/commits/%s\n' "$repo" "$to"
fi
