#!/usr/bin/env bash
# Downloads Vosk and JNA from Maven Central into libs/ and checks them against the pinned SHA-1.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p libs
fetch() { # <url> <file> <sha1>
    [ -f "libs/$2" ] || curl -fsSL -o "libs/$2" "$1"
    echo "$3  libs/$2" | sha1sum -c - >/dev/null || { echo "bad checksum: $2" >&2; rm -f "libs/$2"; exit 1; }
    echo "ok $2"
}
fetch https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar \
    jna-5.18.1.aar 8e93d068c4eab70ada4c79e010198a112aa3c581
fetch https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar \
    vosk-android-0.3.75.aar 40764b038a882055e1a57c33136c86ab9b7db2ee
