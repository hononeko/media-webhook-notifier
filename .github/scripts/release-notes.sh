#!/usr/bin/env bash
# Writes the Markdown release notes for the commits since the last release, grouped by Conventional Commit type:
#
#   💥 Breaking changes   type!: subjects, or a BREAKING CHANGE: / BREAKING: footer (version.yml's major rule)
#   🚀 Features           feat
#   🐛 Bug fixes          fix
#   📦 Others             every other type, docs: and test: included
#
# Each entry reads "type(scope): subject #PR by @author", oldest first; empty sections are left out. The PR and its
# author come from the GitHub API (gh), falling back to the commit when it didn't come from a PR.
#
# Usage: release-notes.sh <previous-tag> [ref] [tag]
#   previous-tag  the last release's tag; empty for the first release (every commit up to ref)
#   ref           the commit being released (default HEAD)
#   tag           the release's own tag, for the Full Changelog link (default: ref's short commit)
#   GITHUB_REPOSITORY  owner/repo for the API and links (default: the current repository, via gh)
set -euo pipefail

prev_tag=${1-}
ref=${2:-HEAD}
to=${3:-$(git rev-parse --short "$ref")}
repo=${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}

range=$ref
if [ -n "$prev_tag" ]; then range="$prev_tag..$ref"; fi

breaking=() features=() fixes=() others=()

# Listed first, so a bad range fails the script instead of giving empty notes.
commits=$(git rev-list --reverse "$range")
for sha in $commits; do
  subject=$(git log -1 --format=%s "$sha")
  message=$(git log -1 --format=%B "$sha")

  # The PR this commit was merged by (a squash merge's own commit), else any PR that contains it.
  pr=$(gh api "repos/$repo/commits/$sha/pulls" \
    --jq "(map(select(.merge_commit_sha == \"$sha\")) + .)[0] // empty | \"\(.number) \(.user.login)\"" \
    2>/dev/null || true)
  if [ -n "$pr" ]; then
    number=${pr%% *}
    by="@${pr#* }"
    link="#$number"
    # Squash merges end the subject with " (#N)"; the link is added back after it.
    subject=${subject% (#"$number")}
  else
    login=$(gh api "repos/$repo/commits/$sha" --jq '.author.login // empty' 2>/dev/null || true)
    if [ -n "$login" ]; then by="@$login"; else by=$(git log -1 --format=%an "$sha"); fi
    link=${sha::7}
  fi
  entry="* $subject $link by $by"

  if grep -qE '!:|BREAKING CHANGE:|BREAKING:' <<<"$message"; then
    breaking+=("$entry")
  elif [[ $subject =~ ^feat(\(.*\))?: ]]; then
    features+=("$entry")
  elif [[ $subject =~ ^fix(\(.*\))?: ]]; then
    fixes+=("$entry")
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
section "📦 Others" "${others[@]}"

if [ -n "$prev_tag" ]; then
  printf '**Full Changelog**: https://github.com/%s/compare/%s...%s\n' "$repo" "$prev_tag" "$to"
else
  printf '**Full Changelog**: https://github.com/%s/commits/%s\n' "$repo" "$to"
fi
