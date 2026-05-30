#!/bin/bash

set -e

FILE_NAME="$1"

if [ -z "$FILE_NAME" ]; then
    echo "Usage: deploy.sh tsFileName"
    exit 1
fi

# top/pj/deploy.sh の場合
SRC_TS="../pj/web/ts/${FILE_NAME}.ts"
SRC_JS="../pj/web/js/${FILE_NAME}.js"
SRC_MAP="../pj/web/js/${FILE_NAME}.js.map"

DST_TS="../out/artifacts/pj_exploded/ts/${FILE_NAME}.ts"
DST_JS="../out/artifacts/pj_exploded/js/${FILE_NAME}.js"
DST_MAP="../out/artifacts/pj_exploded/js/${FILE_NAME}.js.map"

# コピー元チェック
for file in "$SRC_TS" "$SRC_JS" "$SRC_MAP"
do
    if [ ! -f "$file" ]; then
        echo "Source file not found: $file"
        exit 1
    fi
done

# コピー先チェック
for file in "$DST_TS" "$DST_JS" "$DST_MAP"
do
    if [ ! -f "$file" ]; then
        echo "Destination file not found: $file"
        exit 1
    fi
done

cp "$SRC_TS" "$DST_TS"
cp "$SRC_JS" "$DST_JS"
cp "$SRC_MAP" "$DST_MAP"

echo "Deployed:"
echo "  $SRC_TS -> $DST_TS"
echo "  $SRC_JS -> $DST_JS"
echo "  $SRC_MAP -> $DST_MAP"
