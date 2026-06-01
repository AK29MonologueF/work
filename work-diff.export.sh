#!/bin/bash

DEST="~Documents/git_working_diff_export"

git status --porcelain | while read -r line
do
    status="${line:0:2}"
    file="${line:3}"

    case "$status" in
        " M"|"??")
            mkdir -p "$DEST/$(dirname "$file")"
            cp "$file" "$DEST/$file"
            ;;
    esac
done
