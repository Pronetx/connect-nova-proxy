#!/bin/bash
# Check Nova VoIP Gateway service status on EC2

set -e

REGION="${AWS_REGION:-us-west-2}"
INSTANCE_TAG="${INSTANCE_TAG:-NovaSonicVoIPGatewayInstance}"

echo "🔍 Checking service status..."
echo ""

# Get all instance IDs
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

echo "Found $INSTANCE_COUNT instance(s)"
echo ""

# Get status from each instance
for INSTANCE_ID in "${INSTANCE_ARRAY[@]}"; do
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Instance: $INSTANCE_ID"
    echo ""

    CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters 'commands=["sudo systemctl status nova-gateway --no-pager"]' \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

    echo "   Waiting for command to complete..."
    sleep 5

    # Get both status and output
    STATUS=$(aws ssm get-command-invocation \
        --command-id "$CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query "Status" \
        --output text)

    if [ "$STATUS" == "Success" ]; then
        aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "$INSTANCE_ID" \
            --region "$REGION" \
            --query "StandardOutputContent" \
            --output text
    elif [ "$STATUS" == "Failed" ]; then
        echo "   ❌ Command failed:"
        aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "$INSTANCE_ID" \
            --region "$REGION" \
            --query "StandardErrorContent" \
            --output text | sed 's/^/   /'
    else
        echo "   ⚠️  Command status: $STATUS"
        echo "   Command ID: $CMD_ID"
    fi

    echo ""
done
