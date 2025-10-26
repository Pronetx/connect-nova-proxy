#!/bin/bash
# Build and deploy Nova VoIP Gateway in one step

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🏗️  BUILD AND DEPLOY"
echo "===================="
echo ""

# Build
"${SCRIPT_DIR}/build.sh"
echo ""

# Deploy
"${SCRIPT_DIR}/deploy-to-ec2.sh"
