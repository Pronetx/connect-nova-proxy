#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { FreeSwitchStack } from '../lib/freeswitch-stack';

const app = new cdk.App();

new FreeSwitchStack(app, 'FreeSwitchStack', {
  env: {
    account: '322081704783',
    region: 'us-west-2' // Same region as Chime Voice Connector
  },

  // Optional: specify key pair for SSH access
  keyPairName: 'pronetx_sbc',

  // Use existing Elastic IP
  elasticIpAllocationId: 'eipalloc-06944d8244ca8ad59',

  // Use existing VPC (same as Java gateway)
  vpcId: 'vpc-06db1c6fd1522eb5b',

  // Instance size (default: t3.small)
  // instanceSize: ec2.InstanceSize.MEDIUM,
});
