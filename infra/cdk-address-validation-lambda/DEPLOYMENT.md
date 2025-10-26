# Quick Deployment Guide

## 1. Deploy Lambda Function

```bash
cd /Users/yasser/Documents/Code/connect-nova-proxy-1/infra/cdk-address-validation-lambda

# Set SmartyStreets credentials
export SMARTY_AUTH_ID="666a32c0-96c8-6da4-4ff1-fc13f750a553"
export SMARTY_AUTH_TOKEN="TT6S1KlLrjmVz9eSLoE3"

# Deploy
npx cdk deploy --require-approval never
```

## 2. Note the Function URL

After deployment, CDK will output:
```
AddressValidationLambdaStack.FunctionUrl = https://xxxxxxxxxxxxx.lambda-url.us-west-2.on.aws/
```

Copy this URL - you'll need it for the voice gateway.

## 3. Configure Voice Gateway

Set the Lambda URL as an environment variable on your EC2 instance(s):

```bash
# SSH into EC2 or use SSM
echo 'ADDRESS_VALIDATION_LAMBDA_URL=https://xxxxxxxxxxxxx.lambda-url.us-west-2.on.aws/' | sudo tee -a /etc/nova-gateway.env

# Restart service to pick up new environment variable
sudo systemctl restart nova-gateway
```

## 4. Test Lambda Directly

```bash
FUNCTION_URL="https://xxxxxxxxxxxxx.lambda-url.us-west-2.on.aws/"

curl -X POST $FUNCTION_URL \
  -H "Content-Type: application/json" \
  -d '{
    "street": "1 Santa Claus Ln",
    "city": "North Pole",
    "state": "AK",
    "zipcode": "99705"
  }'
```

Expected response:
```json
{
  "status": "valid",
  "message": "I've validated the address: ...",
  "standardizedAddress": {
    "street": "1 Santa Claus Ln",
    "city": "North Pole",
    "state": "AK",
    "zipcode": "99705-9901"
  }
}
```

## 5. Deploy Voice Gateway

```bash
cd /Users/yasser/Documents/Code/connect-nova-proxy-1/voice-gateway
./scripts/build-and-deploy.sh
```

## Cleanup

To remove the Lambda:
```bash
cd /Users/yasser/Documents/Code/connect-nova-proxy-1/infra/cdk-address-validation-lambda
cdk destroy
```
