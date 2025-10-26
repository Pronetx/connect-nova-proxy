#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import {VoipGatewayEC2Stack} from '../lib/cdk-stack';

const app = new cdk.App();
new VoipGatewayEC2Stack(app, 'VoipGatewayEC2Stack', {
  /* If you don't specify 'env', this stack will be environment-agnostic.
   * Account/Region-dependent features and context lookups will not work,
   * but a single synthesized template can be deployed anywhere. */

  /* Uncomment the next line to specialize this stack for the AWS Account
   * and Region that are implied by the current CLI configuration. */
  // Deploy to us-west-2 for Chime Voice Connector
  env: { account: '322081704783', region: 'us-west-2' },

  /* For more information, see https://docs.aws.amazon.com/cdk/latest/guide/environments.html */

  keyPairName: 'pronetx_sbc',
  // vpcId: 'vpc-########',  // Uncomment to use existing VPC
});