# Address Validation Lambda

AWS Lambda function that validates US addresses using the SmartyStreets US Street API with enhanced matching mode. Provides conversational responses optimized for Nova Sonic voice interactions.

## Features

- **Enhanced Address Validation**: Uses SmartyStreets US Street API with enhanced matching
- **Conversational Responses**: Returns voice-optimized responses for Nova
- **Multiple Validation Scenarios**:
  - Valid addresses with confirmation
  - Missing secondary address (apartment/suite) detection
  - Multiple address suggestions
  - Address correction suggestions
  - Invalid address handling
- **Function URL**: Direct HTTP access without API Gateway
- **CloudWatch Logging**: Detailed request/response logging

## Architecture

```
Voice Gateway (Java)
    ↓
AddressValidationTool
    ↓ HTTP POST
Lambda Function URL
    ↓
SmartyStreets US Street API
    ↓
Enhanced Address Validation
```

## Deployment

### Prerequisites

1. **SmartyStreets Credentials**:
   ```bash
   export SMARTY_AUTH_ID="666a32c0-96c8-6da4-4ff1-fc13f750a553"
   export SMARTY_AUTH_TOKEN="TT6S1KlLrjmVz9eSLoE3"
   ```

2. **AWS Credentials**: Configure for us-west-2 region
   ```bash
   aws configure
   ```

### Deploy with CDK

```bash
# Install dependencies
npm install

# Bootstrap CDK (first time only)
cdk bootstrap aws://ACCOUNT-NUMBER/us-west-2

# Deploy stack
cdk deploy

# Note the Function URL output - you'll need this for AddressValidationTool
```

### Outputs

After deployment, you'll receive:
- **FunctionUrl**: Use this in `AddressValidationTool` (environment variable: `ADDRESS_VALIDATION_LAMBDA_URL`)
- **FunctionArn**: Lambda function ARN
- **LogGroupName**: CloudWatch log group for monitoring

## API

### Request Format

```json
POST {FunctionUrl}
Content-Type: application/json

{
  "street": "123 Main Street",
  "suite": "Apt 4B",
  "city": "Baltimore",
  "state": "MD",
  "zipcode": "21201",
  "candidates": 5
}
```

**Fields:**
- `street` (required): Street address
- `city` (required): City name
- `state` (required): State abbreviation (e.g., "MD", "CA")
- `suite` (optional): Apartment, suite, or unit number
- `zipcode` (optional): ZIP code
- `candidates` (optional): Max number of suggestions (default: 5)

### Response Format

#### Valid Address
```json
{
  "status": "valid",
  "message": "Address validated successfully",
  "conversationalResponse": "I've validated the address: 1-2-3 Main Street, Apt 4-B, Baltimore, Maryland, 2-1-2-0-1. That's M-A-I-N Street. This address is confirmed.",
  "standardizedAddress": {
    "street": "123 Main St",
    "suite": "Apt 4B",
    "city": "Baltimore",
    "state": "MD",
    "zipcode": "21201-1234"
  },
  "originalAddress": { ... },
  "metadata": {
    "recordType": "S",
    "countyName": "Baltimore City",
    "precision": "Zip9",
    "timeZone": "Eastern",
    "utcOffset": -5,
    "dstObserved": true
  }
}
```

#### Missing Secondary (Apartment/Suite)
```json
{
  "status": "missing_secondary",
  "message": "Address found but missing apartment/suite number",
  "conversationalResponse": "I found the street address at 123 Main St in Baltimore, MD, but it appears this is a multi-unit building. Could you please provide the apartment or suite number?",
  "standardizedAddress": { ... },
  "suggestedAction": "request_suite"
}
```

#### Multiple Suggestions
```json
{
  "status": "ambiguous",
  "message": "Multiple matching addresses found",
  "conversationalResponse": "I found multiple possible matches. Did you mean 123 Main Street, Baltimore, Maryland? Or would you like to hear the other options?",
  "suggestions": [
    { "street": "123 Main St", ... },
    { "street": "123 Main Ave", ... }
  ]
}
```

#### Address Not Found
```json
{
  "status": "invalid",
  "message": "Address not found",
  "conversationalResponse": "I couldn't find that address in the postal database. The address \"123 Fake St, Springfield, XX\" doesn't appear to be valid. Could you please verify the address and try again?"
}
```

## Validation Status Codes

| Status | Description | Action |
|--------|-------------|--------|
| `valid` | Address validated and deliverable | Use standardized address |
| `missing_secondary` | Multi-unit building, needs apt/suite | Request secondary info |
| `ambiguous` | Multiple matches found | Present suggestions |
| `suggestion` | Similar address found | Confirm correction |
| `invalid` | Address not found | Request re-entry |
| `unknown` | Unexpected validation result | Request re-entry |

## SmartyStreets Enhanced Matching

Enhanced matching mode provides:
- **Aggressive matching**: More permissive than strict mode
- **Fuzzy matching**: Handles typos and variations
- **Candidate suggestions**: Returns multiple possible matches
- **Standardization**: Corrects abbreviations and formatting

### DPV Match Codes

- `Y`: Address is valid and deliverable
- `S`: Secondary address (apt/suite) is valid
- `D`: Missing secondary address information
- `N`: Address not found in USPS database

## Testing

### Test Valid Address
```bash
curl -X POST {FunctionUrl} \
  -H "Content-Type: application/json" \
  -d '{
    "street": "1 Santa Claus Ln",
    "city": "North Pole",
    "state": "AK",
    "zipcode": "99705"
  }'
```

### Test Missing Secondary
```bash
curl -X POST {FunctionUrl} \
  -H "Content-Type: application/json" \
  -d '{
    "street": "123 Main St",
    "city": "Baltimore",
    "state": "MD"
  }'
```

### Test Invalid Address
```bash
curl -X POST {FunctionUrl} \
  -H "Content-Type: application/json" \
  -d '{
    "street": "123 Fake Street",
    "city": "Springfield",
    "state": "XX"
  }'
```

## Monitoring

### View Logs
```bash
# Get log group name from CDK output
aws logs tail /aws/lambda/AddressValidationLambdaStack-AddressValidationFunction --follow
```

### CloudWatch Insights Query
```sql
fields @timestamp, @message
| filter @message like /Address validation request/
| sort @timestamp desc
| limit 20
```

## Cost Considerations

- **Lambda**: Pay per request (~$0.20 per 1M requests + compute time)
- **CloudWatch Logs**: 1-week retention
- **SmartyStreets**: Check your SmartyStreets plan for API limits
- **Function URL**: No additional cost (vs API Gateway)

## Integration with Voice Gateway

The Lambda is called by `AddressValidationTool` in the voice gateway:

1. User provides address to Nova
2. CollectAddressTool collects all components
3. AddressValidationTool sends to Lambda Function URL
4. Lambda validates via SmartyStreets
5. Conversational response returned to Nova
6. Nova asks follow-up based on validation status

See `/voice-gateway/src/main/java/com/example/s2s/voipgateway/nova/tools/AddressValidationTool.java`

## Troubleshooting

**Lambda returns 500 error:**
- Check CloudWatch logs for details
- Verify SmartyStreets credentials in environment variables
- Ensure SmartyStreets API is accessible from Lambda

**"Address not found" for valid addresses:**
- Verify address format matches USPS standards
- Check state abbreviation is correct
- Try with enhanced matching mode (already enabled)

**Timeout errors:**
- Increase Lambda timeout (currently 30s)
- Check SmartyStreets API response time

## Security

- **Function URL**: Currently set to NONE auth (public access)
- **CORS**: Enabled for all origins
- **Credentials**: Stored in Lambda environment variables (consider using Secrets Manager for production)

For production:
- Consider adding API key authentication
- Use AWS Secrets Manager for SmartyStreets credentials
- Restrict CORS origins
- Enable AWS WAF for DDoS protection

## Cleanup

```bash
cdk destroy
```

## References

- [SmartyStreets US Street API Docs](https://www.smarty.com/docs/cloud/us-street-api)
- [AWS Lambda Function URLs](https://docs.aws.amazon.com/lambda/latest/dg/lambda-urls.html)
- [CDK Lambda Construct](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_lambda-readme.html)
