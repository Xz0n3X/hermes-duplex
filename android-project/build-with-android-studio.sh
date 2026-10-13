#!/bin/sh
set -eu

# Android Studio supplies the SDK/build tools. Its rolling bundled JBR may be
# newer than this project's Gradle version (Studio currently ships Java 25),
# so use the installed Java 17 toolchain Gradle and AGP 8.6 support.
build_jdk="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
studio="/Applications/Android Studio.app"
if [ ! -d "$studio" ]; then
    echo "Android Studio was not found at $studio" >&2
    exit 1
fi
if [ ! -x "$build_jdk/bin/java" ]; then
    echo "The compatible Java 17 build runtime was not found at:" >&2
    echo "  $build_jdk" >&2
    exit 1
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export JAVA_HOME="$build_jdk"
exec "$script_dir/gradlew" "$@"
