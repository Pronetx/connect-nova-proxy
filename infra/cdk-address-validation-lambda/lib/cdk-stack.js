const { Stack, Duration, CfnOutput } = require('aws-cdk-lib');
const lambda = require('aws-cdk-lib/aws-lambda');
const logs = require('aws-cdk-lib/aws-logs');
const path = require('path');

class AddressValidationLambdaStack extends Stack {
  constructor(scope, id, props) {
    super(scope, id, props);

    // Create Lambda function for address validation
    const addressValidationFunction = new lambda.Function(this, 'AddressValidationFunction', {
      runtime: lambda.Runtime.NODEJS_20_X,
      handler: 'index.handler',
      code: lambda.Code.fromAsset(path.join(__dirname, '../lambda')),
      timeout: Duration.seconds(30),
      memorySize: 256,
      description: 'Validates US addresses using SmartyStreets US Street API with enhanced matching',
      environment: {
        SMARTY_AUTH_ID: process.env.SMARTY_AUTH_ID || '',
        SMARTY_AUTH_TOKEN: process.env.SMARTY_AUTH_TOKEN || '',
        NODE_OPTIONS: '--enable-source-maps'
      },
      logRetention: logs.RetentionDays.ONE_WEEK,
    });

    // Create Function URL for HTTP access
    const functionUrl = addressValidationFunction.addFunctionUrl({
      authType: lambda.FunctionUrlAuthType.NONE, // Public access
      cors: {
        allowedOrigins: ['*'],
        allowedMethods: [lambda.HttpMethod.POST, lambda.HttpMethod.OPTIONS],
        allowedHeaders: ['Content-Type'],
      },
    });

    // Outputs
    new CfnOutput(this, 'FunctionName', {
      value: addressValidationFunction.functionName,
      description: 'Lambda function name',
    });

    new CfnOutput(this, 'FunctionArn', {
      value: addressValidationFunction.functionArn,
      description: 'Lambda function ARN',
    });

    new CfnOutput(this, 'FunctionUrl', {
      value: functionUrl.url,
      description: 'Lambda Function URL (use this in AddressValidationTool)',
    });

    new CfnOutput(this, 'LogGroupName', {
      value: addressValidationFunction.logGroup.logGroupName,
      description: 'CloudWatch log group name',
    });
  }
}

module.exports = { AddressValidationLambdaStack };
