#!/bin/bash

DEST="~/Documents/git_commit_diff_export"
COMMIT1="$1"
COMMIT2="$2"

git diff --name-only --diff-filter=AM "$COMMIT1" "$COMMIT2" |
while read -r file
do
    mkdir -p "$DEST/$(dirname "$file")"
    cp "$file" "$DEST/$file"
done
