#!/bin/bash
# Build the Nova VoIP Gateway JAR

set -e

# Get project root (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

echo "🔨 Building Nova VoIP Gateway..."
mvn clean package

echo ""
echo "✅ Build complete!"
echo "📦 JAR location: target/s2s-voip-gateway-0.6-SNAPSHOT.jar"
