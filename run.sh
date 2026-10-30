#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/Applications/android-studio/jbr/bin/java" ]]; then
    export JAVA_HOME="$HOME/Applications/android-studio/jbr"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is required. On Ubuntu: sudo apt install maven"
    exit 1
fi
if [[ "${1:-}" == "--test" ]]; then
    exec mvn test
elif [[ "${1:-}" == "--integration-test" ]]; then
    exec mvn -Pintegration verify
fi
mvn -q -DskipTests package
exec java -jar target/placement-application-tracker-2.0.0.jar "$@"
