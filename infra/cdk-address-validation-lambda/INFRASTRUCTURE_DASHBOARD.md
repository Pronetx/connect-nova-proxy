# Infrastructure & Microservices Monitoring Dashboard

This dashboard provides comprehensive monitoring for the entire Nova Voice Gateway infrastructure, including EC2/ECS instances, Lambda microservices, Bedrock API usage, and Chime Voice Connector integration.

## Dashboard Sections

### 1. **EC2 Voice Gateway Instance**
- CPU Utilization
- Network Traffic (RTP/SIP)
- Health Checks

### 2. **ECS Cluster** (if deployed)
- Container CPU/Memory utilization
- Running vs Desired task counts
- Network traffic

### 3. **Lambda Microservices**
- Invocations, Errors, and Throttles
- Duration metrics (avg, p99, max)
- Concurrent executions
- Error rate percentage

### 4. **Bedrock Nova API Usage**
- Total invocations
- Latency (average and p99)
- Errors and throttles

### 5. **Application KPIs**
Custom metrics published by the voice gateway:
- Active and total calls
- Call duration statistics
- RTP audio packet counts
- Application errors (Nova stream, SIP)
- Tool invocations

### 6. **VPC & Networking**
- VPC traffic (bytes in/out)
- Packets dropped

### 7. **CloudWatch Logs Insights**
- Recent gateway errors
- Recent Lambda errors
- SIP message activity

### 8. **Chime Voice Connector**
- Inbound call attempts and failures
- Round-trip time (RTT)

## Deployment

### Create Dashboard

```bash
aws cloudwatch put-dashboard \
  --dashboard-name "NovaGateway-Infrastructure" \
  --region us-west-2 \
  --dashboard-body file://infrastructure-dashboard.json
```

### View Dashboard

```bash
# Get dashboard URL
DASHBOARD_URL="https://us-west-2.console.aws.amazon.com/cloudwatch/home?region=us-west-2#dashboards:name=NovaGateway-Infrastructure"
echo "Dashboard URL: $DASHBOARD_URL"

# Or open directly (macOS)
open "$DASHBOARD_URL"
```

### Update Dashboard

```bash
aws cloudwatch put-dashboard \
  --dashboard-name "NovaGateway-Infrastructure" \
  --region us-west-2 \
  --dashboard-body file://infrastructure-dashboard.json
```

### Delete Dashboard

```bash
aws cloudwatch delete-dashboards \
  --dashboard-names "NovaGateway-Infrastructure" \
  --region us-west-2
```

## Custom Metrics Requirements

The dashboard expects the voice gateway application to publish custom metrics to the `NovaGateway/Usage` namespace. To enable these metrics, ensure your voice gateway publishes:

### Metrics to Publish

```java
// Example CloudWatch metrics publishing from Java application
PutMetricDataRequest request = PutMetricDataRequest.builder()
    .namespace("NovaGateway/Usage")
    .metricData(
        MetricDatum.builder()
            .metricName("ActiveCalls")
            .value(activeCallCount)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("TotalCalls")
            .value(1.0)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("CallDuration")
            .value(durationSeconds)
            .unit(StandardUnit.SECONDS)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("AudioPacketsReceived")
            .value(packetCount)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("AudioPacketsSent")
            .value(packetCount)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("NovaStreamErrors")
            .value(1.0)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("SIPErrors")
            .value(1.0)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build(),
        MetricDatum.builder()
            .metricName("ToolInvocations")
            .value(1.0)
            .unit(StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build()
    )
    .build();

cloudWatchClient.putMetricData(request);
```

### Maven Dependency

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cloudwatch</artifactId>
    <version>2.20.0</version>
</dependency>
```

## Monitoring Notes

### EC2 Instance Monitoring

- The EC2 metrics will automatically populate once the voice gateway EC2 instance is running
- Filter by instance ID or add instance-specific dimensions as needed
- Network traffic includes both SIP (UDP 5060) and RTP (UDP 10000-20000) traffic

### ECS Monitoring

- ECS metrics only populate if using the ECS deployment (cdk-ecs stack)
- Container Insights must be enabled (already configured in the ECS stack)

### Lambda Monitoring

- Lambda metrics aggregate across all functions in the region
- To filter specific functions, add function name dimensions
- The address validation Lambda is monitored separately in the address validation dashboard

### Bedrock Monitoring

- Bedrock metrics are in **us-east-1** region (Nova Sonic availability)
- Ensure cross-region monitoring is configured in CloudWatch console

### Chime Voice Connector

- Chime metrics require metrics to be enabled in the Chime Voice Connector settings
- Metrics may not be available immediately after enabling

## Customization

### Filter by Specific Resources

To filter metrics by specific resource IDs, update the JSON with dimension filters:

```json
{
  "metrics": [
    [ "AWS/EC2", "CPUUtilization", {
      "stat": "Average",
      "dimensions": {
        "InstanceId": "i-1234567890abcdef0"
      }
    } ]
  ]
}
```

### Adjust Time Ranges

The default period is 300 seconds (5 minutes). Adjust the `period` field in each widget:

```json
{
  "period": 60  // 1 minute
}
```

### Add Alarms

Create CloudWatch alarms for critical metrics:

```bash
# Example: High CPU alarm
aws cloudwatch put-metric-alarm \
  --alarm-name "NovaGateway-HighCPU" \
  --alarm-description "Alert when EC2 CPU exceeds 80%" \
  --metric-name CPUUtilization \
  --namespace AWS/EC2 \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2
```

## Troubleshooting

### No Data Showing

1. **EC2 Metrics**: Ensure the instance is running and has the CloudWatch agent installed
2. **Custom Metrics**: Verify the voice gateway is publishing metrics with correct namespace
3. **Bedrock Metrics**: Check that you're viewing us-east-1 region
4. **Lambda Metrics**: Ensure functions have been invoked at least once

### Missing Log Groups

If log queries fail, verify log groups exist:

```bash
aws logs describe-log-groups --region us-west-2 | grep voip-gateway
```

### Insufficient Permissions

Ensure your IAM role has these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutDashboard",
        "cloudwatch:GetDashboard",
        "cloudwatch:DeleteDashboards",
        "cloudwatch:ListDashboards"
      ],
      "Resource": "*"
    }
  ]
}
```

## Related Dashboards

- **Address Validation Dashboard**: `cloudwatch-dashboard.json` - Detailed monitoring for the address validation Lambda
- **Custom KPI Dashboard**: Create additional dashboards for business-specific metrics

## Cost Considerations

- CloudWatch dashboards: First 3 dashboards are free, then $3/month per dashboard
- Custom metrics: $0.30 per metric per month (first 10,000 metrics)
- Logs Insights queries: $0.005 per GB scanned
- Detailed EC2 monitoring: $2.10 per instance per month (basic monitoring is free)

## Next Steps

1. Deploy the dashboard with the command above
2. Instrument the voice gateway to publish custom metrics
3. Set up CloudWatch alarms for critical thresholds
4. Create SNS topics for alert notifications
5. Consider setting up X-Ray for distributed tracing
