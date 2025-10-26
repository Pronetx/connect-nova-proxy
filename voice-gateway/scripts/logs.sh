#!/bin/bash
# View Nova VoIP Gateway logs from EC2

set -e

REGION="${AWS_REGION:-us-west-2}"
INSTANCE_TAG="${INSTANCE_TAG:-NovaSonicVoIPGatewayInstance}"
LINES="${1:-50}"

echo "📋 Fetching logs from EC2..."
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

# Get logs from each instance
for INSTANCE_ID in "${INSTANCE_ARRAY[@]}"; do
    if [ $INSTANCE_COUNT -gt 1 ]; then
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "📋 Instance: $INSTANCE_ID"
        echo ""
    fi

    CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters "commands=[\"sudo journalctl -u nova-gateway -n ${LINES} --no-pager\"]" \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

    sleep 5

    # Get command status
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
        echo "❌ Command failed:"
        aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "$INSTANCE_ID" \
            --region "$REGION" \
            --query "StandardErrorContent" \
            --output text
    else
        echo "⚠️  Command status: $STATUS"
        echo "Command ID: $CMD_ID"
    fi

    if [ $INSTANCE_COUNT -gt 1 ]; then
        echo ""
    fi
done
