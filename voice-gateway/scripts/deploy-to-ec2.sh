#!/bin/bash
# Deploy Nova VoIP Gateway to EC2 instance via S3 and SSM

set -e

# Get project root (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Parse command line arguments
PRESIGNED_URL=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --presigned-url)
            PRESIGNED_URL="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--presigned-url URL]"
            exit 1
            ;;
    esac
done

# Configuration
REGION="${AWS_REGION:-us-west-2}"
S3_BUCKET="${S3_BUCKET:-voip-gateway-deployment-1761445331}"
INSTANCE_TAG="${INSTANCE_TAG:-NovaSonicVoIPGatewayInstance}"
JAR_NAME="s2s-voip-gateway-0.6-SNAPSHOT.jar"
JAR_PATH="$PROJECT_ROOT/target/${JAR_NAME}"

echo "🚀 Deploying Nova VoIP Gateway to EC2..."
echo ""

# Check if we have a presigned URL or local JAR
if [ -z "$PRESIGNED_URL" ]; then
    # Check if JAR exists locally
    if [ ! -f "$JAR_PATH" ]; then
        echo "❌ JAR file not found: $JAR_PATH"
        echo "   Run ./scripts/build.sh first or provide --presigned-url"
        exit 1
    fi
fi

# Get all instance IDs
echo "🔍 Finding EC2 instances..."
INSTANCE_IDS=$(aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=$INSTANCE_TAG" "Name=instance-state-name,Values=running" \
    --region "$REGION" \
    --query "Reservations[*].Instances[*].InstanceId" \
    --output text)

if [ -z "$INSTANCE_IDS" ]; then
    echo "❌ No running EC2 instances found with tag: $INSTANCE_TAG"
    exit 1
fi

# Convert to array
INSTANCE_ARRAY=($INSTANCE_IDS)
INSTANCE_COUNT=${#INSTANCE_ARRAY[@]}

echo "   Found $INSTANCE_COUNT instance(s): $INSTANCE_IDS"
echo ""

# Determine download method
if [ -n "$PRESIGNED_URL" ]; then
    echo "📥 Using presigned URL for deployment"
    echo "   URL: ${PRESIGNED_URL:0:60}..."
    # Escape the URL for JSON by replacing " with \"
    ESCAPED_URL="${PRESIGNED_URL//\"/\\\"}"
    DOWNLOAD_CMD="curl -fsSL -o ${JAR_NAME} \"${ESCAPED_URL}\""
else
    # Upload to S3 first (once for all instances)
    echo "☁️  Uploading JAR to S3..."
    aws s3 cp "$JAR_PATH" "s3://${S3_BUCKET}/${JAR_NAME}" --region "$REGION" --no-progress
    echo "   Upload complete"
    DOWNLOAD_CMD="aws s3 cp s3://${S3_BUCKET}/${JAR_NAME} . --region ${REGION}"
fi
echo ""

# Deploy to each instance
for INSTANCE_ID in "${INSTANCE_ARRAY[@]}"; do
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📦 Deploying to instance: $INSTANCE_ID"
    echo ""

    # Stop the service
    echo "⏸️  Stopping nova-gateway service..."
    STOP_CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters 'commands=["sudo systemctl stop nova-gateway"]' \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

    # Wait for stop command
    sleep 3
    aws ssm wait command-executed \
        --command-id "$STOP_CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" 2>/dev/null || true

    echo "   Service stopped"
    echo ""

    # Deploy and start service
    echo "📥 Deploying JAR and starting service..."

    # Create a temporary file for the parameters to avoid JSON escaping issues
    PARAMS_FILE=$(mktemp)
    cat > "$PARAMS_FILE" <<EOF
{
  "commands": [
    "cd /home/ec2-user",
    $(printf '%s' "$DOWNLOAD_CMD" | jq -R .),
    "sudo mv ${JAR_NAME} /opt/nova-gateway/",
    "sudo systemctl start nova-gateway",
    "sleep 3",
    "sudo systemctl status nova-gateway --no-pager"
  ]
}
EOF

    DEPLOY_CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters "file://$PARAMS_FILE" \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

    rm -f "$PARAMS_FILE"

    # Wait for deployment
    echo "   Waiting for deployment to complete..."
    sleep 15

    # Get deployment status
    echo "   Service status:"
    aws ssm get-command-invocation \
        --command-id "$DEPLOY_CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query "StandardOutputContent" \
        --output text | tail -20 | sed 's/^/   /'

    echo ""
    echo "✅ Deployment complete for $INSTANCE_ID"
    echo ""
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ All deployments complete!"
echo ""
echo "Deployed to $INSTANCE_COUNT instance(s)"
