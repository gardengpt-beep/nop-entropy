#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
TARGET_DIR="$BASE_DIR/target"
MAIN_OUT="$TARGET_DIR/classes"
TEST_OUT="$TARGET_DIR/test-classes"

rm -rf "$TARGET_DIR"
mkdir -p "$MAIN_OUT" "$TEST_OUT"

MAIN_SOURCES=$(find "$BASE_DIR/src/main/java" -name "*.java")
if [ -n "$MAIN_SOURCES" ]; then
  javac --release 17 -d "$MAIN_OUT" $MAIN_SOURCES
fi

if [ -d "$BASE_DIR/src/main/resources" ]; then
  while IFS= read -r -d '' resource; do
    dest_dir="$MAIN_OUT/$(dirname "$resource")"
    mkdir -p "$dest_dir"
    cp "$BASE_DIR/src/main/resources/$resource" "$dest_dir/"
  done < <(cd "$BASE_DIR/src/main/resources" && find . -type f -print0)
fi

TEST_SOURCES=$(find "$BASE_DIR/src/test/java" -name "*.java")
if [ -n "$TEST_SOURCES" ]; then
  javac --release 17 -cp "$MAIN_OUT" -d "$TEST_OUT" $TEST_SOURCES
fi

java -cp "$MAIN_OUT:$TEST_OUT" com.company.erp.testing.TestRunner
