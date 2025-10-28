import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as s3assets from 'aws-cdk-lib/aws-s3-assets';
import { Construct } from 'constructs';
import * as path from 'path';
import * as fs from 'fs';

export interface FreeSwitchStackProps extends cdk.StackProps {
  keyPairName?: string;
  vpcId?: string;
  instanceSize?: ec2.InstanceSize;
  elasticIpAllocationId?: string;
}

export class FreeSwitchStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: FreeSwitchStackProps) {
    super(scope, id, props);

    // Create VPC or use existing
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

    // Create Parameter Store entries for FreeSWITCH configuration
    const dialplanParam = new ssm.StringParameter(this, 'FreeSwitchDialplan', {
      parameterName: '/freeswitch/config/dialplan',
      stringValue: fs.readFileSync(path.join(__dirname, '../configs/dialplan/default.xml'), 'utf-8'),
      description: 'FreeSWITCH default dialplan configuration',
      tier: ssm.ParameterTier.ADVANCED,
    });

    const sipProfileParam = new ssm.StringParameter(this, 'FreeSwitchSipProfile', {
      parameterName: '/freeswitch/config/sip-profile',
      stringValue: fs.readFileSync(path.join(__dirname, '../configs/sip_profiles/external.xml'), 'utf-8'),
      description: 'FreeSWITCH external SIP profile configuration',
      tier: ssm.ParameterTier.ADVANCED,
    });

    const varsParam = new ssm.StringParameter(this, 'FreeSwitchVars', {
      parameterName: '/freeswitch/config/vars',
      stringValue: fs.readFileSync(path.join(__dirname, '../configs/vars.xml'), 'utf-8'),
      description: 'FreeSWITCH global variables configuration',
      tier: ssm.ParameterTier.ADVANCED,
    });

    // Create IAM role for EC2 instance
    const instanceRole = new iam.Role(this, 'FreeSwitchInstanceRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore')
      ],
      inlinePolicies: {
        'ParameterStoreAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: [
                'ssm:GetParameter',
                'ssm:GetParameters',
                'ssm:GetParametersByPath'
              ],
              resources: [
                dialplanParam.parameterArn,
                sipProfileParam.parameterArn,
                varsParam.parameterArn,
              ],
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
              resources: ['arn:aws:logs:*:*:log-group:/aws/freeswitch/*'],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
      },
    });

    // Create security group
    const securityGroup = new ec2.SecurityGroup(this, 'FreeSwitchSG', {
      vpc,
      description: 'Security group for FreeSWITCH server - SIP and RTP traffic',
      allowAllOutbound: true,
    });

    // SIP signaling
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udp(5060),
      'SIP UDP'
    );
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(5060),
      'SIP TCP'
    );
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udp(5080),
      'SIP UDP from Voice Connector'
    );

    // RTP media ports
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udpRange(10000, 20000),
      'RTP media'
    );

    // SSH for management
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(22),
      'SSH'
    );

    // FreeSWITCH Event Socket (for future integration)
    securityGroup.addIngressRule(
      ec2.Peer.ipv4(vpc.vpcCidrBlock),
      ec2.Port.tcp(8021),
      'FreeSWITCH Event Socket (internal only)'
    );

    // TCP Audio Server port (for Java Gateway communication)
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(8085),
      'TCP Audio Server for Java Gateway'
    );

    // Upload installation script as S3 asset
    const installScript = new s3assets.Asset(this, 'InstallScript', {
      path: path.join(__dirname, '../scripts/install-freeswitch.sh'),
    });

    // Create EC2 instance
    const instance = new ec2.Instance(this, 'FreeSwitchServer', {
      vpc,
      vpcSubnets: {
        subnetType: ec2.SubnetType.PUBLIC
      },
      instanceType: ec2.InstanceType.of(
        ec2.InstanceClass.T3,
        props.instanceSize || ec2.InstanceSize.SMALL
      ),
      keyPair: props.keyPairName ?
        ec2.KeyPair.fromKeyPairName(this, 'KeyPair', props.keyPairName) :
        undefined,
      machineImage: ec2.MachineImage.lookup({
        name: 'ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*',
        owners: ['099720109477'], // Canonical
      }),
      securityGroup: securityGroup,
      associatePublicIpAddress: true,
      role: instanceRole,
    });

    // Use existing Elastic IP or create new one
    let eipAllocationId: string;
    let eipAddress: string;

    if (props.elasticIpAllocationId) {
      // Use existing EIP
      eipAllocationId = props.elasticIpAllocationId;

      // Associate existing EIP with instance
      new ec2.CfnEIPAssociation(this, 'FreeSwitchEIPAssociation', {
        allocationId: eipAllocationId,
        instanceId: instance.instanceId,
      });

      // Hardcode the IP for this allocation ID (eipalloc-06944d8244ca8ad59 = 44.237.82.96)
      eipAddress = '44.237.82.96';
    } else {
      // Create new EIP
      const eip = new ec2.CfnEIP(this, 'FreeSwitchEIP', {
        domain: 'vpc',
        tags: [
          {
            key: 'Name',
            value: `${this.stackName}-FreeSWITCH-EIP`
          }
        ]
      });

      eipAllocationId = eip.attrAllocationId;
      eipAddress = eip.ref;

      // Associate new EIP with instance
      new ec2.CfnEIPAssociation(this, 'FreeSwitchEIPAssociation', {
        allocationId: eipAllocationId,
        instanceId: instance.instanceId,
      });
    }

    // Grant read access to the installation script
    installScript.grantRead(instanceRole);

    // User data to bootstrap FreeSWITCH
    instance.userData.addCommands(
      '#!/bin/bash',
      'set -e',
      '',
      '# Log all output',
      'exec > >(tee -a /var/log/user-data.log)',
      'exec 2>&1',
      '',
      'echo "Starting FreeSWITCH installation..."',
      'echo "Timestamp: $(date)"',
      '',
      '# Get public IP for configuration',
      'PUBLIC_IP=$(ec2-metadata --public-ipv4 | cut -d " " -f 2)',
      'echo "Public IP: $PUBLIC_IP"',
      '',
      '# Set environment variables',
      `export AWS_REGION=${this.region}`,
      'export PUBLIC_IP=$PUBLIC_IP',
      '',
      '# Download and execute installation script',
      `aws s3 cp ${installScript.s3ObjectUrl} /tmp/install-freeswitch.sh`,
      'chmod +x /tmp/install-freeswitch.sh',
      '/tmp/install-freeswitch.sh',
      '',
      'echo "FreeSWITCH installation completed"',
      'echo "Timestamp: $(date)"',
    );

    // Outputs
    new cdk.CfnOutput(this, 'ElasticIPAllocation', {
      value: eipAllocationId,
      description: 'Elastic IP allocation ID',
      exportName: `${this.stackName}-ElasticIPAllocation`,
    });

    new cdk.CfnOutput(this, 'ElasticIP', {
      value: eipAddress,
      description: 'Static Elastic IP address of FreeSWITCH server',
      exportName: `${this.stackName}-ElasticIP`,
    });

    new cdk.CfnOutput(this, 'InstanceId', {
      value: instance.instanceId,
      description: 'EC2 Instance ID',
      exportName: `${this.stackName}-InstanceId`,
    });

    new cdk.CfnOutput(this, 'SipUri', {
      value: `sip:${eipAddress}:5060`,
      description: 'SIP URI for Voice Connector origination',
    });

    new cdk.CfnOutput(this, 'SSMSessionCommand', {
      value: `aws ssm start-session --target ${instance.instanceId}`,
      description: 'Command to connect via SSM Session Manager',
    });
  }
}
