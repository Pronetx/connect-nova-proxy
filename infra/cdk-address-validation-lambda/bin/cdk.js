#!/usr/bin/env node
const cdk = require('aws-cdk-lib');
const { AddressValidationLambdaStack } = require('../lib/cdk-stack');

const app = new cdk.App();

new AddressValidationLambdaStack(app, 'AddressValidationLambdaStack', {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION || 'us-west-2',
  },
  description: 'SmartyStreets Address Validation Lambda with Function URL',
});

app.synth();
