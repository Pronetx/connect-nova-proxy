
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';

export interface VoipGatewayEC2StackProps extends cdk.StackProps {
  keyPairName?: string;
  vpcId?: string;
}

export class VoipGatewayEC2Stack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: VoipGatewayEC2StackProps) {
    super(scope, id, props);

    // Create S3 bucket for call recordings
    const callRecordingsBucket = new s3.Bucket(this, 'CallRecordingsBucket', {
      bucketName: `voice-gateway-recordings-${this.account}-${this.region}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      lifecycleRules: [
        {
          // Move to Glacier after 30 days for cost savings
          transitions: [
            {
              storageClass: s3.StorageClass.GLACIER,
              transitionAfter: cdk.Duration.days(30),
            },
          ],
          // Delete after 1 year (adjust as needed)
          expiration: cdk.Duration.days(365),
        },
      ],
      removalPolicy: cdk.RemovalPolicy.RETAIN, // Keep recordings even if stack is deleted
    });

    // Create a VPC with public subnets
    const vpc = props.vpcId ?
      ec2.Vpc.fromLookup(this, 'VPC', { vpcId: props.vpcId }) :
      new ec2.Vpc(this, 'VPC', {
      maxAzs: 2,
      subnetConfiguration: [
        {
          cidrMask: 24,
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
        }
      ]
    });
    // const vpc = ec2.Vpc.fromLookup(this, 'VPC', {
    //   vpcName: 'my-vpc'
    // });

    const instanceRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore')
      ],
      inlinePolicies: {
        'BedrockAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: ['bedrock:InvokeModel', 'bedrock:GetModelInvocationLoggingConfiguration', 'bedrock:InvokeModelWithResponseStream'],
              resources: ['*'],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
        'S3Access': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: ['s3:GetObject', 's3:ListBucket'],
              resources: ['arn:aws:s3:::voip-gateway-deployment-*', 'arn:aws:s3:::voip-gateway-deployment-*/*'],
              effect: iam.Effect.ALLOW
            }),
            new iam.PolicyStatement({
              actions: ['s3:PutObject', 's3:PutObjectAcl'],
              resources: [callRecordingsBucket.arnForObjects('*')],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
        'CloudWatchLogsAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: [
                'logs:CreateLogGroup',
                'logs:CreateLogStream',
                'logs:PutLogEvents',
                'logs:DescribeLogStreams'
              ],
              resources: ['arn:aws:logs:*:*:log-group:/aws/voip-gateway/*'],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
        'CloudWatchMetricsAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: [
                'cloudwatch:PutMetricData'
              ],
              resources: ['*'],
              effect: iam.Effect.ALLOW,
              conditions: {
                'StringEquals': {
                  'cloudwatch:namespace': 'NovaGateway/Usage'
                }
              }
            })
          ]
        }),
        'PinpointSMSAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: [
                'mobiletargeting:SendMessages',
                'mobiletargeting:GetApp'
              ],
              resources: ['*'],
              effect: iam.Effect.ALLOW
            })
          ]
        })
      },
    });

    // Create a security group for the EC2 instance
    const securityGroup = new ec2.SecurityGroup(this, 'VoipGatewaySG', {
      vpc,
      description: 'Allow SSH, SIP, and RTP traffic',
      allowAllOutbound: true,
    });

    // Allow SIP traffic on port 5060
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udp(5060),
      'Allow SIP traffic on port 5060'
    );

    // Allow RTP traffic on ports 10000-20000
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udpRange(10000, 20000),
      'Allow RTP traffic on ports 10000-20000'
    );

    // Allow SSH traffic for management
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(22),
      'Allow SSH traffic'
    );

    // Create an EC2 instance in a public subnet
    const instance = new ec2.Instance(this, 'VoipGatewayServer', {
      vpc,
      vpcSubnets: {
        subnetType: ec2.SubnetType.PUBLIC
      },
      instanceType: ec2.InstanceType.of(
        ec2.InstanceClass.T3,
        ec2.InstanceSize.MICRO
      ),
      keyPair: props.keyPairName ? ec2.KeyPair.fromKeyPairName(this, 'VoipGatewayKeypair', props.keyPairName) : undefined,
      machineImage: ec2.MachineImage.latestAmazonLinux2(),
      securityGroup: securityGroup,
      associatePublicIpAddress: true,
      userData: ec2.UserData.forLinux(),
      role: instanceRole,
    });

    // Add commands to the user data to install Java, SSM agent, and CloudWatch agent
    instance.userData.addCommands(
      '#!/bin/bash',
      'yum update -y',
      'yum install -y java-24-amazon-corretto-devel maven git amazon-ssm-agent amazon-cloudwatch-agent',
      'systemctl enable amazon-ssm-agent',
      'systemctl start amazon-ssm-agent',
      '',
      '# Get instance ID and set as environment variable for log stream naming',
      'INSTANCE_ID=$(ec2-metadata --instance-id | cut -d " " -f 2)',
      'echo "export INSTANCE_ID=$INSTANCE_ID" >> /etc/environment',
      '',
      '# Set call recordings S3 bucket environment variable',
      `echo "export CALL_RECORDING_BUCKET=${callRecordingsBucket.bucketName}" >> /etc/environment`,
      '',
      '# Create CloudWatch agent configuration',
      'cat > /opt/aws/amazon-cloudwatch-agent/etc/config.json << EOF',
      '{',
      '  "logs": {',
      '    "logs_collected": {',
      '      "files": {',
      '        "collect_list": [',
      '          {',
      '            "file_path": "/home/ec2-user/gateway.log",',
      '            "log_group_name": "/aws/voip-gateway/system",',
      '            "log_stream_name": "{instance_id}/{local_hostname}",',
      '            "retention_in_days": 7,',
      '            "timezone": "UTC"',
      '          }',
      '        ]',
      '      }',
      '    }',
      '  }',
      '}',
      'EOF',
      '',
      '# Start CloudWatch agent',
      '/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \\',
      '  -a fetch-config \\',
      '  -m ec2 \\',
      '  -s \\',
      '  -c file:/opt/aws/amazon-cloudwatch-agent/etc/config.json',
      '',
      'echo "Dependency installation completed"'
    );

    // Output the public IP of the instance
    new cdk.CfnOutput(this, 'InstancePublicIP', {
      value: instance.instancePublicIp,
      description: 'Public IP address of the EC2 instance',
    });

    // Output the call recordings bucket name
    new cdk.CfnOutput(this, 'CallRecordingsBucketName', {
      value: callRecordingsBucket.bucketName,
      description: 'S3 bucket for storing call recordings',
      exportName: 'VoiceGatewayRecordingsBucket',
    });
  }
}