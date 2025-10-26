# prepareExternalTransfer Lambda

**Owner:** Dev B

This Lambda function is invoked from an Amazon Connect contact flow immediately before executing an External Voice Transfer. It extracts contact context and prepares custom SIP headers to send to the Voice Gateway.

## Purpose

The Voice Gateway needs to know the Connect `InitialContactId` and `InstanceId` to update contact attributes during and after the call. Since Connect cannot directly set custom SIP headers in the External Transfer block, we use this Lambda to:

1. Extract contact context from the Connect event
2. Generate a correlation ID for tracking
3. Return custom SIP headers to attach to the SIP INVITE
4. Optionally store the correlation ID in contact attributes

## Deployment

### Prerequisites

- AWS CLI configured
- Node.js 18+ installed
- Lambda execution role with Connect permissions

### Steps

```bash
# Install dependencies
npm install

# Create deployment package
zip -r function.zip index.mjs package.json node_modules

# Create Lambda function (first time)
aws lambda create-function \
  --function-name prepareExternalTransfer \
  --runtime nodejs18.x \
  --role arn:aws:iam::ACCOUNT:role/lambda-connect-role \
  --handler index.handler \
  --zip-file fileb://function.zip

# Update existing function
npm run deploy
```

### IAM Permissions

The Lambda execution role needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:*:*:*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "connect:UpdateContactAttributes"
      ],
      "Resource": "arn:aws:connect:REGION:ACCOUNT:instance/INSTANCE_ID/contact/*"
    }
  ]
}
```

## Connect Flow Integration

### Flow Structure

```
1. Check if customer authenticated
   ↓
2. Invoke Lambda: prepareExternalTransfer
   ↓
3. Set contact attributes (Lambda response)
   ↓
4. Transfer to phone number (External)
   - Destination: sip:gateway@example.com:5060
   - Resume after disconnect: Yes
   - SIP headers: From Lambda response
   ↓
5. [Call returns to flow after gateway hangs up]
   ↓
6. Check contact attribute: nova_next
   ↓
7. Route accordingly (agent/survey/end)
```

### Lambda Invocation Block

**Settings:**
- Function ARN: `arn:aws:lambda:us-east-1:ACCOUNT:function:prepareExternalTransfer`
- Timeout: 8 seconds
- Response: Store in `$.External.prepareTransferResult`

### Transfer Block

**Settings:**
- Transfer type: External
- Phone number: `sip:gateway.example.com:5060`
- Resume after disconnect: **Yes** ✅
- Custom SIP headers: `$.External.prepareTransferResult.headers`

## Input

The Lambda receives a standard Connect Contact Flow event:

```json
{
  "Details": {
    "ContactData": {
      "ContactId": "abc-123",
      "InitialContactId": "abc-123",
      "InstanceARN": "arn:aws:connect:us-east-1:123456789012:instance/xyz-789",
      "Attributes": {},
      "CustomerEndpoint": {
        "Address": "+12065551234",
        "Type": "TELEPHONE_NUMBER"
      }
    },
    "Parameters": {}
  }
}
```

## Output

The Lambda returns custom SIP headers and metadata:

```json
{
  "statusCode": 200,
  "headers": {
    "X-Connect-ContactId": "abc-123",
    "X-Connect-InstanceId": "xyz-789",
    "X-Correlation-Id": "abc-123-1729872737123",
    "X-Contact-FlowId": "flow-123"
  },
  "body": {
    "message": "SIP headers prepared successfully",
    "correlationId": "abc-123-1729872737123"
  }
}
```

## SIP Header Format

These headers are sent in the SIP INVITE to the gateway:

```
INVITE sip:gateway@example.com:5060 SIP/2.0
...
X-Connect-ContactId: abc-123-def-456
X-Connect-InstanceId: xyz-789
X-Correlation-Id: abc-123-def-456-1729872737123
X-Contact-FlowId: flow-123
```

The Voice Gateway parses these headers using `SipHeaderParser.parseConnectContext()`.

## Error Handling

**Scenario 1: Lambda Timeout**
- Connect waits 8 seconds
- If timeout → transfer proceeds without custom headers
- Gateway falls back to environment variable `CONNECT_INSTANCE_ID`

**Scenario 2: Lambda Error**
- Lambda returns 500 status code
- Transfer proceeds anyway (gateway handles missing headers)
- Logged in CloudWatch for debugging

**Scenario 3: UpdateContactAttributes Failure**
- Lambda catches error and continues
- SIP headers still returned
- Transfer proceeds normally

## Testing

### Local Testing

```bash
npm test
```

### Manual Invocation

```bash
aws lambda invoke \
  --function-name prepareExternalTransfer \
  --payload file://test-event.json \
  response.json

cat response.json
```

**test-event.json:**
```json
{
  "Details": {
    "ContactData": {
      "ContactId": "test-123",
      "InitialContactId": "test-123",
      "InstanceARN": "arn:aws:connect:us-east-1:123456789012:instance/test-instance",
      "ContactFlowId": "test-flow"
    }
  }
}
```

### Connect Flow Testing

1. Call your Connect phone number
2. Navigate to external transfer block
3. Check CloudWatch logs for Lambda invocation
4. Check Gateway logs for received SIP headers
5. Verify contact attributes after call

## Monitoring

### CloudWatch Metrics

- Invocations
- Errors
- Duration
- Throttles

### CloudWatch Logs

Log groups:
- `/aws/lambda/prepareExternalTransfer` - Lambda execution logs
- Connect flow logs - Flow execution

**Key Log Patterns:**
```
"Contact context" - Shows extracted IDs
"SIP headers prepared successfully" - Success
"Failed to update contact attributes" - Non-blocking error
"Error preparing external transfer" - Critical error
```

### Alarms

Recommended CloudWatch alarms:

1. **High Error Rate**
   - Metric: Errors > 5% of invocations
   - Period: 5 minutes
   - Action: SNS notification

2. **High Duration**
   - Metric: Duration > 5000ms (p99)
   - Period: 5 minutes
   - Action: SNS notification

## Troubleshooting

**Problem:** SIP headers not appearing in gateway logs

**Solution:**
- Check Lambda logs for errors
- Verify Connect flow sets SIP headers from Lambda response
- Check Connect service quotas (custom headers limit)

**Problem:** UpdateContactAttributes fails

**Solution:**
- Verify Lambda role has `connect:UpdateContactAttributes` permission
- Check instance ID is correct
- Verify contact is still active

**Problem:** Correlation ID not tracking correctly

**Solution:**
- Ensure InitialContactId used (not ContactId)
- Check timestamp format in correlation ID
- Verify both Lambda and Gateway use same ID format

## Development

### Adding New Headers

1. Update `sipHeaders` object in handler
2. Update Gateway's `SipHeaderParser` to parse new header
3. Update `/shared/docs/call-flow-sequence.md` documentation
4. Deploy Lambda and rebuild Gateway

### Testing Changes

```bash
# Run unit tests
npm test

# Deploy to Lambda
npm run deploy

# Test with Connect flow
# (Call flow and verify in logs)
```

## References

- [Connect Lambda Integration](https://docs.aws.amazon.com/connect/latest/adminguide/connect-lambda-functions.html)
- [SIP Header Parsing](/connect-integration/src/main/java/com/example/s2s/connect/uui/SipHeaderParser.java)
- [Call Flow Sequence](/shared/docs/call-flow-sequence.md)
- [Attribute Contract](/shared/docs/attribute-contract.md)
