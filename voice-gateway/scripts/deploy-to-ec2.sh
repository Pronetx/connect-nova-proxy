#!/bin/bash
# Deploy Nova VoIP Gateway to EC2 instance via S3 and SSM

set -e

# Get project root (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Configuration
REGION="${AWS_REGION:-us-west-2}"
S3_BUCKET="${S3_BUCKET:-voip-gateway-deployment-1761445331}"
INSTANCE_TAG="${INSTANCE_TAG:-NovaSonicVoIPGatewayInstance}"
JAR_NAME="s2s-voip-gateway-0.6-SNAPSHOT.jar"
JAR_PATH="$PROJECT_ROOT/target/${JAR_NAME}"

echo "🚀 Deploying Nova VoIP Gateway to EC2..."
echo ""

# Check if JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "❌ JAR file not found: $JAR_PATH"
    echo "   Run ./scripts/build.sh first"
    exit 1
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

# Upload to S3 first (once for all instances)
echo "☁️  Uploading JAR to S3..."
aws s3 cp "$JAR_PATH" "s3://${S3_BUCKET}/${JAR_NAME}" --region "$REGION" --no-progress
echo "   Upload complete"
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
    DEPLOY_CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters "commands=[\"cd /home/ec2-user\",\"aws s3 cp s3://${S3_BUCKET}/${JAR_NAME} . --region ${REGION}\",\"sudo mv ${JAR_NAME} /opt/nova-gateway/\",\"sudo systemctl start nova-gateway\",\"sleep 3\",\"sudo systemctl status nova-gateway --no-pager\"]" \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

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
