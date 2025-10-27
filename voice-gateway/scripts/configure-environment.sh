#!/bin/bash
# Configure environment variables for nova-gateway service

set -e

REGION="${AWS_REGION:-us-west-2}"
INSTANCE_TAG="${INSTANCE_TAG:-NovaSonicVoIPGatewayInstance}"

echo "⚙️  Configuring environment for nova-gateway..."
echo ""

# Ask if registering with SIP server
read -p "Register with SIP server? (y/N): " DO_REGISTER
echo ""

if [ "$DO_REGISTER" == "y" ] || [ "$DO_REGISTER" == "Y" ]; then
    # Full SIP registration mode
    read -p "SIP Server (e.g., sip.example.com): " SIP_SERVER
    read -p "SIP User: " SIP_USER
    read -p "Auth User (press enter to use SIP_USER): " AUTH_USER
    AUTH_USER="${AUTH_USER:-$SIP_USER}"
    read -sp "Auth Password: " AUTH_PASSWORD
    echo ""
    read -p "Auth Realm (press enter to use SIP_SERVER): " AUTH_REALM
    AUTH_REALM="${AUTH_REALM:-$SIP_SERVER}"
    read -p "Display Name: " DISPLAY_NAME

    echo ""
    echo "📝 Environment configuration:"
    echo "   SIP_SERVER=$SIP_SERVER"
    echo "   SIP_USER=$SIP_USER"
    echo "   AUTH_USER=$AUTH_USER"
    echo "   AUTH_REALM=$AUTH_REALM"
    echo "   DISPLAY_NAME=$DISPLAY_NAME"
    echo "   PINPOINT_APPLICATION_ID=f87ebc6cb0924d51a0e0de7dfa5ddc3f"
else
    # Inbound trunk mode (no registration)
    echo "📝 Environment configuration (inbound trunk mode):"
    echo "   PINPOINT_APPLICATION_ID=f87ebc6cb0924d51a0e0de7dfa5ddc3f"
fi
echo ""

read -p "Apply this configuration? (y/N): " CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
    echo "Cancelled"
    exit 0
fi

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

echo ""
echo "Found $INSTANCE_COUNT instance(s)"
echo ""

# Configure each instance
for INSTANCE_ID in "${INSTANCE_ARRAY[@]}"; do
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "⚙️  Configuring: $INSTANCE_ID"
    echo ""

    # Build environment file content based on mode
    if [ "$DO_REGISTER" == "y" ] || [ "$DO_REGISTER" == "Y" ]; then
        ENV_CONTENT="SIP_SERVER=${SIP_SERVER}
SIP_USER=${SIP_USER}
AUTH_USER=${AUTH_USER}
AUTH_PASSWORD=${AUTH_PASSWORD}
AUTH_REALM=${AUTH_REALM}
DISPLAY_NAME=${DISPLAY_NAME}
PINPOINT_APPLICATION_ID=f87ebc6cb0924d51a0e0de7dfa5ddc3f
CALL_RECORDING_BUCKET=voice-gateway-recordings-322081704783-us-west-2
ADDRESS_VALIDATION_LAMBDA_URL=https://yo3qim52ngs3euzwkdxidl3isa0zomgt.lambda-url.us-west-2.on.aws/"
    else
        ENV_CONTENT="PINPOINT_APPLICATION_ID=f87ebc6cb0924d51a0e0de7dfa5ddc3f
CALL_RECORDING_BUCKET=voice-gateway-recordings-322081704783-us-west-2
ADDRESS_VALIDATION_LAMBDA_URL=https://yo3qim52ngs3euzwkdxidl3isa0zomgt.lambda-url.us-west-2.on.aws/"
    fi

    CMD_ID=$(aws ssm send-command \
        --document-name "AWS-RunShellScript" \
        --instance-ids "$INSTANCE_ID" \
        --parameters "commands=[
            \"# Create environment file for nova-gateway service\",
            \"sudo tee /etc/nova-gateway.env > /dev/null <<'EOF'\",
            \"${ENV_CONTENT}\",
            \"EOF\",
            \"\",
            \"sudo chmod 600 /etc/nova-gateway.env\",
            \"echo 'Environment file created at /etc/nova-gateway.env'\"
        ]" \
        --region "$REGION" \
        --output text \
        --query "Command.CommandId")

    sleep 3

    STATUS=$(aws ssm get-command-invocation \
        --command-id "$CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query "Status" \
        --output text)

    if [ "$STATUS" == "Success" ]; then
        echo "   ✅ Environment configured"
    else
        echo "   ❌ Configuration failed (status: $STATUS)"
    fi

    echo ""
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Environment configuration complete!"
echo ""
echo "Next steps:"
echo "1. Run ./scripts/setup-service.sh to create/update the systemd service"
echo "2. Run ./scripts/deploy-to-ec2.sh to deploy the JAR"
