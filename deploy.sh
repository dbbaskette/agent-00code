#!/usr/bin/env bash
set -euo pipefail

# Build, inject config into the JAR, and push to CF.

./mvnw clean package -DskipTests

JAR="$(pwd)/target/agent-00code-0.1.0.jar"

# Stage config files into BOOT-INF/classes/ and update the JAR
STAGE=$(mktemp -d)
mkdir -p "$STAGE/BOOT-INF/classes"

cp AGENTS.md "$STAGE/BOOT-INF/classes/"

if [ -f mcp-servers.local.yml ]; then
  cp mcp-servers.local.yml "$STAGE/BOOT-INF/classes/"
  echo "Included mcp-servers.local.yml"
fi

(cd "$STAGE" && jar uf "$JAR" BOOT-INF/)
rm -rf "$STAGE"

cf push
