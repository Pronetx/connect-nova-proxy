#!/bin/bash

# Deploy Infrastructure & Microservices Monitoring Dashboard
# This script creates a CloudWatch dashboard for monitoring the entire Nova Voice Gateway infrastructure

set -e

DASHBOARD_NAME="NovaGateway-Infrastructure"
REGION="${AWS_REGION:-us-west-2}"
DASHBOARD_FILE="infrastructure-dashboard.json"

echo "========================================="
echo "Infrastructure Dashboard Deployment"
echo "========================================="
echo ""
echo "Dashboard Name: $DASHBOARD_NAME"
echo "Region: $REGION"
echo "Dashboard File: $DASHBOARD_FILE"
echo ""

# Check if dashboard file exists
if [ ! -f "$DASHBOARD_FILE" ]; then
    echo "Error: Dashboard file '$DASHBOARD_FILE' not found!"
    exit 1
fi

# Validate JSON
echo "Validating dashboard JSON..."
if command -v jq &> /dev/null; then
    jq empty "$DASHBOARD_FILE" || {
        echo "Error: Invalid JSON in dashboard file!"
        exit 1
    }
    echo "✓ JSON is valid"
else
    echo "⚠ jq not found - skipping JSON validation"
fi

echo ""
echo "Creating/updating CloudWatch dashboard..."

# Create or update dashboard
aws cloudwatch put-dashboard \
  --dashboard-name "$DASHBOARD_NAME" \
  --region "$REGION" \
  --dashboard-body "file://$DASHBOARD_FILE"

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "✓ Dashboard deployed successfully!"
    echo "========================================="
    echo ""
    echo "Dashboard URL:"
    echo "https://${REGION}.console.aws.amazon.com/cloudwatch/home?region=${REGION}#dashboards:name=${DASHBOARD_NAME}"
    echo ""
    echo "To open in browser (macOS):"
    echo "open \"https://${REGION}.console.aws.amazon.com/cloudwatch/home?region=${REGION}#dashboards:name=${DASHBOARD_NAME}\""
    echo ""
    echo "To delete this dashboard:"
    echo "aws cloudwatch delete-dashboards --dashboard-names \"$DASHBOARD_NAME\" --region $REGION"
    echo ""
    echo "Note: Custom application metrics require instrumentation in the voice gateway."
    echo "See INFRASTRUCTURE_DASHBOARD.md for details."
else
    echo ""
    echo "Error: Failed to deploy dashboard!"
    exit 1
fi
