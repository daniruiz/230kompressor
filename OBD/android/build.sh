#!/usr/bin/env bash

set -e

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
tools_dir="$project_dir/.build-tools"
gradle_version=8.11.1
sdk_tools_version=11076708

if command -v pacman > /dev/null; then
  sudo pacman -S --noconfirm jdk21-openjdk curl unzip
elif command -v apt-get > /dev/null; then
  sudo apt-get update
  sudo apt-get install -y openjdk-21-jdk-headless ca-certificates curl unzip
else
  echo "System not supported"
  exit 1
fi

export JAVA_HOME=$(echo /usr/lib/jvm/java-21-openjdk*)
export ANDROID_HOME="$tools_dir/android-sdk"
export ANDROID_USER_HOME="$tools_dir/android-user"
export GRADLE_USER_HOME="$tools_dir/gradle-cache"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$tools_dir" "$ANDROID_USER_HOME"

temporary_dir="$(mktemp -d "$tools_dir/download.XXXXXX")"
trap 'rm -rf -- "$temporary_dir"' EXIT

download() {
    curl --fail --location --retry 3 --connect-timeout 30 "$1" --output "$2"
}

gradle="$tools_dir/gradle-$gradle_version/bin/gradle"
if [[ ! -x "$gradle" ]]; then
    archive="gradle-$gradle_version-bin.zip"
    download "https://services.gradle.org/distributions/$archive" "$temporary_dir/$archive"
    download "https://services.gradle.org/distributions/$archive.sha256" "$temporary_dir/gradle.sha256"
    printf '%s  %s\n' "$(cat "$temporary_dir/gradle.sha256")" "$temporary_dir/$archive" | sha256sum --check -
    unzip -q -o "$temporary_dir/$archive" -d "$tools_dir"
fi

sdk_tools_dir="$ANDROID_HOME/cmdline-tools/$sdk_tools_version"
if [[ ! -f "$sdk_tools_dir/lib/sdkmanager-classpath.jar" ]]; then
    download "https://dl.google.com/android/repository/commandlinetools-linux-${sdk_tools_version}_latest.zip" "$temporary_dir/sdk.zip"
    unzip -q "$temporary_dir/sdk.zip" -d "$temporary_dir/sdk"
    mkdir -p "$ANDROID_HOME/cmdline-tools/$sdk_tools_version"
    cp -a "$temporary_dir/sdk/cmdline-tools/." "$ANDROID_HOME/cmdline-tools/$sdk_tools_version/"
fi

# El lanzador sdkmanager de esta versión divide las opciones JVM cuando la
# ruta contiene espacios. Invocamos la misma clase Java con argumentos citados.
run_sdkmanager() {
    "$JAVA_HOME/bin/java" "-Dcom.android.sdklib.toolsdir=$sdk_tools_dir" \
        -classpath "$sdk_tools_dir/lib/sdkmanager-classpath.jar" \
        com.android.sdklib.tool.sdkmanager.SdkManagerCli "$@"
}

yes | run_sdkmanager --sdk_root="$ANDROID_HOME" --licenses
run_sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0'

"$gradle" --project-dir "$project_dir" --no-daemon --console=plain \
    -Dorg.gradle.jvmargs=-Xmx2g :app:assembleDebug

mv "$project_dir/app/build/outputs/apk/debug/app-debug.apk" "$project_dir/../MercedesDashboard.apk"
