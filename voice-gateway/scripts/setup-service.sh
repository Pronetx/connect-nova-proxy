#!/bin/bash
# Setup nova-gateway as a systemd service on EC2

set -e

REGION="${AWS_REGION:-us-west-2}"
INSTANCE_TAG="${INSTANCE_TAG:-NovaSonicVoIPGatewayInstance}"

echo "🔧 Setting up nova-gateway systemd service..."
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

INSTANCE_ARRAY=($INSTANCE_IDS)
INSTANCE_COUNT=${#INSTANCE_ARRAY[@]}

echo "Found $INSTANCE_COUNT instance(s)"
echo ""

# Setup service on each instance
for INSTANCE_ID in "${INSTANCE_ARRAY[@]}"; do
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🔧 Setting up service on: $INSTANCE_ID"
    echo ""

    # Create systemd service file and setup directories
    CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters 'commands=[
            "# Stop any existing java processes",
            "pkill -f \"java -jar s2s-voip-gateway\" || true",
            "sleep 2",
            "",
            "# Create directory if it doesnt exist",
            "sudo mkdir -p /opt/nova-gateway",
            "",
            "# Copy .mjsip-ua config if it exists",
            "if [ -f /home/ec2-user/.mjsip-ua ]; then",
            "  sudo cp /home/ec2-user/.mjsip-ua /opt/nova-gateway/",
            "  sudo chown root:root /opt/nova-gateway/.mjsip-ua",
            "  echo 'Copied .mjsip-ua config'",
            "fi",
            "",
            "# Create systemd service file",
            "sudo tee /etc/systemd/system/nova-gateway.service > /dev/null <<EOF",
            "[Unit]",
            "Description=Nova Sonic VoIP Gateway",
            "After=network.target",
            "",
            "[Service]",
            "Type=simple",
            "User=root",
            "WorkingDirectory=/opt/nova-gateway",
            "EnvironmentFile=/etc/nova-gateway.env",
            "# Set START_TIME for unique log stream per service restart",
            "ExecStartPre=/bin/sh -c '\''echo START_TIME=$(date +%%s) >> /etc/nova-gateway.env.runtime'\''",
            "ExecStart=/bin/sh -c '\''START_TIME=$(date +%%s) /usr/bin/java -jar /opt/nova-gateway/s2s-voip-gateway-0.6-SNAPSHOT.jar'\''",
            "Restart=always",
            "RestartSec=10",
            "StandardOutput=journal",
            "StandardError=journal",
            "SyslogIdentifier=nova-gateway",
            "",
            "[Install]",
            "WantedBy=multi-user.target",
            "EOF",
            "",
            "# Reload systemd and enable service",
            "sudo systemctl daemon-reload",
            "sudo systemctl enable nova-gateway",
            "",
            "# Start the service",
            "sudo systemctl start nova-gateway",
            "sleep 3",
            "",
            "# Show status",
            "sudo systemctl status nova-gateway --no-pager"
        ]' \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

    echo "   Waiting for setup to complete..."
    sleep 10

    # Get status
    STATUS=$(aws ssm get-command-invocation \
        --command-id "$CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query "Status" \
        --output text)

    if [ "$STATUS" == "Success" ]; then
        echo "   ✅ Service setup complete!"
        echo ""
        echo "   Service status:"
        aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "$INSTANCE_ID" \
            --region "$REGION" \
            --query "StandardOutputContent" \
            --output text | tail -20 | sed 's/^/   /'
    elif [ "$STATUS" == "Failed" ]; then
        echo "   ❌ Setup failed:"
        aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "$INSTANCE_ID" \
            --region "$REGION" \
            --query "StandardErrorContent" \
            --output text | sed 's/^/   /'
    else
        echo "   ⚠️  Setup status: $STATUS"
        echo "   Command ID: $CMD_ID"
    fi

    echo ""
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Service setup complete on all instances!"
