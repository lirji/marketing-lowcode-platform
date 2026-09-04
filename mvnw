#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES")
VERSION=$(printf '%s' "$URL" | sed -n 's#.*apache-maven-\([0-9][0-9.]*\)-bin.zip#\1#p')
WRAPPER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$VERSION"
MAVEN_HOME="$WRAPPER_HOME/apache-maven-$VERSION"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$WRAPPER_HOME"
  ARCHIVE="$WRAPPER_HOME/apache-maven-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$URL" --output "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget --quiet "$URL" --output-document="$ARCHIVE"
  else
    echo "curl or wget is required to bootstrap Maven" >&2
    exit 1
  fi
  unzip -q -o "$ARCHIVE" -d "$WRAPPER_HOME"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
