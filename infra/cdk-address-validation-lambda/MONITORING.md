# CloudWatch Monitoring Dashboard

## Dashboard Overview

**Name**: NovaAddressValidation-Monitoring  
**Region**: us-west-2  
**URL**: https://us-west-2.console.aws.amazon.com/cloudwatch/home?region=us-west-2#dashboards/dashboard/NovaAddressValidation-Monitoring

## Widgets

### Row 1: Core Metrics
1. **Lambda Invocations & Errors** (Time Series)
   - Total invocations
   - Error count (red threshold at 10)
   - Throttle count
   - 5-minute granularity

2. **Lambda Duration** (Time Series)
   - Average duration
   - Maximum duration
   - P99 duration
   - 3-second target threshold (important for caller experience)

### Row 2: Performance Monitoring
3. **Lambda Concurrent Executions** (Time Series)
   - Maximum concurrent executions
   - Helps identify scaling issues

4. **Lambda Success Rate** (Time Series)
   - Calculated: (Invocations - Errors) / Invocations * 100
   - 99% target threshold
   - Green line indicates healthy operation

5. **Address Validation Status Distribution** (Pie Chart)
   - Shows breakdown of validation results:
     - valid
     - invalid
     - missing_secondary
     - ambiguous
     - suggestion
     - error

### Row 3: Error Analysis
6. **Recent Lambda Errors** (Log Table)
   - Last 20 error messages
   - Sorted by timestamp (newest first)
   - Filters for ERROR/error in logs

7. **Slow Requests (>3s)** (Log Table)
   - Requests exceeding 3-second threshold
   - Critical for caller experience
   - Last 20 slow requests

### Row 4: Usage Analytics
8. **Top 10 Cities Validated** (Bar Chart)
   - Most frequently validated cities
   - Useful for understanding usage patterns

9. **Hourly Invocation Count** (Single Value)
   - Current hour's total invocations
   - Quick health check metric

10. **Average Response Time** (Single Value)
    - Current 5-minute average
    - Should stay well below 3 seconds

### Row 5: Deep Dive
11. **Duration Statistics** (Time Series)
    - 5-minute bucket aggregations
    - Average, max, min, P95, P99 durations
    - Trend analysis

12. **SmartyStreets API Errors** (Table)
    - API-specific errors from SmartyStreets
    - Count by error type
    - Helps identify subscription or API issues

## Key Metrics to Watch

### 🔴 Critical Alerts
- **Error Rate**: Should be < 1%
- **Response Time P99**: Should be < 3 seconds
- **Throttles**: Should be 0

### ⚠️ Warning Indicators
- **Success Rate**: Drops below 99%
- **Average Duration**: Exceeds 2 seconds
- **SmartyStreets API Errors**: Any occurrences

### ✅ Healthy Baseline
- Success Rate: > 99%
- Average Duration: 500-1000ms
- P99 Duration: < 2 seconds
- No throttles or errors

## Common Issues and Diagnosis

### High Error Rate
**Symptoms**: Errors widget shows red spikes  
**Check**: 
1. Recent Lambda Errors widget for details
2. SmartyStreets API Errors for subscription issues
3. Lambda logs for stack traces

### Slow Response Times
**Symptoms**: Duration P99 > 3 seconds  
**Check**:
1. Slow Requests table for affected requests
2. Duration Statistics for trends
3. SmartyStreets API response time
4. Lambda cold starts

### Status Distribution Anomalies
**Symptoms**: Unexpected status ratios  
**Possible Causes**:
- High "invalid" rate: Data quality issues
- High "error" rate: SmartyStreets API problems
- High "ambiguous": Incomplete address data

## CloudWatch Insights Queries

### Most Common Validation Statuses
```
SOURCE '/aws/lambda/NovaAddressValidationFunction'
| fields @timestamp, @message
| filter @message like /status/
| parse @message /.*"status":"(?<status>[^"]*)".*/
| stats count() by status
```

### Average Duration by Status
```
SOURCE '/aws/lambda/NovaAddressValidationFunction'
| fields @timestamp, @duration, @message
| filter @message like /Validation result/
| parse @message /.*"status":"(?<status>[^"]*)".*/
| stats avg(@duration) as avg_duration by status
```

### Failed Validations
```
SOURCE '/aws/lambda/NovaAddressValidationFunction'
| fields @timestamp, @message
| filter @message like /"status":"invalid"/ or @message like /"status":"error"/
| sort @timestamp desc
| limit 50
```

### SmartyStreets API Issues
```
SOURCE '/aws/lambda/NovaAddressValidationFunction'
| fields @timestamp, @message
| filter @message like /SmartyStreets/
| sort @timestamp desc
```

## Updating the Dashboard

To update the dashboard:

```bash
# Edit the dashboard JSON
vim infra/cdk-address-validation-lambda/cloudwatch-dashboard.json

# Update the dashboard
aws cloudwatch put-dashboard \
  --dashboard-name "NovaAddressValidation-Monitoring" \
  --dashboard-body file://cloudwatch-dashboard.json \
  --region us-west-2
```

## Alarm Recommendations

Consider creating CloudWatch Alarms for:

1. **High Error Rate**
   - Metric: AWS/Lambda Errors
   - Threshold: > 5 in 5 minutes
   - Action: SNS notification

2. **High Response Time**
   - Metric: AWS/Lambda Duration (P99)
   - Threshold: > 3000ms
   - Action: SNS notification

3. **Throttling**
   - Metric: AWS/Lambda Throttles
   - Threshold: > 0
   - Action: SNS notification

4. **Low Success Rate**
   - Metric Math: (Invocations - Errors) / Invocations * 100
   - Threshold: < 99%
   - Action: SNS notification

## Related Resources

- Lambda Function: NovaAddressValidationFunction
- Log Group: /aws/lambda/NovaAddressValidationFunction
- Stack: NovaAddressValidationStack
- Function URL: https://yo3qim52ngs3euzwkdxidl3isa0zomgt.lambda-url.us-west-2.on.aws/

---
Generated with Claude Code
