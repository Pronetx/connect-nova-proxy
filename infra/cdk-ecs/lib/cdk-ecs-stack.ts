import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr_assets from "aws-cdk-lib/aws-ecr-assets";
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import * as path from 'path';

export interface VoipGatewayContainerStackProps extends cdk.StackProps {
  sipServer:string;
  sipUsername:string;
  sipPassword:string;
  sipRealm:string;
  displayName:string;
  baseRtpPort?:number;
  rtpPortCount?:number;
}

export class VoipGatewayContainerStack extends cdk.Stack {
  public readonly cluster: ecs.Cluster;

  constructor(scope: Construct, id: string, props: VoipGatewayContainerStackProps) {
    super(scope, id, props);
    const baseRtpPort = props.baseRtpPort ?? 10000;
    const rtpPortCount = props.rtpPortCount ?? 10000;

    // Create a VPC with two AZs
    const vpc = new ec2.Vpc(this, 'VPC', {
      maxAzs: 2,
      natGateways: 0,  // We don't need NAT gateways if the instance is in a public subnet
      subnetConfiguration: [
        {
          cidrMask: 24,
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
        },
        {
          cidrMask: 24,
          name: 'private',
          subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
        }
      ]
    });

    // Create a security group for VPC endpoints
    const vpcEndpointSecurityGroup = new ec2.SecurityGroup(this, 'VpcEndpointSecurityGroup', {
      vpc,
      description: 'Security group for VPC endpoints',
      allowAllOutbound: true
    });

    // Allow inbound HTTPS connections from within the VPC
    vpcEndpointSecurityGroup.addIngressRule(
      ec2.Peer.ipv4(vpc.vpcCidrBlock),
      ec2.Port.tcp(443),
      'Allow HTTPS access from within the VPC'
    );

    // Create VPC Endpoints for ECR and related services
    // ECR API endpoint
    const ecrApiEndpoint = new ec2.InterfaceVpcEndpoint(this, 'EcrApiEndpoint', {
      vpc,
      service: ec2.InterfaceVpcEndpointAwsService.ECR,
      privateDnsEnabled: true,
      securityGroups: [vpcEndpointSecurityGroup],
      subnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED }
    });

    // ECR Docker endpoint
    const ecrDockerEndpoint = new ec2.InterfaceVpcEndpoint(this, 'EcrDockerEndpoint', {
      vpc,
      service: ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
      privateDnsEnabled: true,
      securityGroups: [vpcEndpointSecurityGroup],
      subnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED }
    });

    // Create the ECS cluster
    this.cluster = new ecs.Cluster(this, 'VoipGatewayCluster', {
      vpc: vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED
    });

    // Security group for the instance
    const instanceSecurityGroup = new ec2.SecurityGroup(this, 'InstanceSecurityGroup', {
      vpc,
      description: 'Security group for S2S VoIP Gateway',
      allowAllOutbound: true
    });

    instanceSecurityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udp(5060),
      'Allow SIP access from anywhere'
    );
    instanceSecurityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.udpRange(baseRtpPort, baseRtpPort+rtpPortCount),
      'Allow RTP access from anywhere'
    );

    // Add capacity to the ECS cluster with the EC2 instance
    const autoScalingGroup = this.cluster.addCapacity('DefaultAutoScalingGroup', {
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
      machineImage: ecs.EcsOptimizedImage.amazonLinux2(),
      allowAllOutbound: true,
      minCapacity: 1,
      maxCapacity: 1,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      associatePublicIpAddress: true,
    });
    autoScalingGroup.addSecurityGroup(instanceSecurityGroup);

    const dockerImageAsset = new ecr_assets.DockerImageAsset(this, 'VoipGatewayImage', {
      directory: path.join(__dirname, '../../docker'), // Adjust this path to point to your Dockerfile location
      platform: ecr_assets.Platform.LINUX_AMD64
    });

    // Create task execution role for ECS
    const taskExecutionRole = new iam.Role(this, 'TaskExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy')
      ]
    });

    // Create task role for the container with any permissions needed
    const taskRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      inlinePolicies: {
        'BedrockAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: ['bedrock:InvokeModel', 'bedrock:GetModelInvocationLoggingConfiguration', 'bedrock:InvokeModelWithResponseStream'],
              resources: ['*'],
              effect: iam.Effect.ALLOW
            })
          ]
        })
      },
    });

    const sipServerSecret = new secretsmanager.Secret(this, 'SipServerSecret', {
      secretName: 's2s-voip-gateway/sip-server',
      description: 'SIP server credentials for Nova Sonic VoIP Gateway',
      secretStringValue: cdk.SecretValue.unsafePlainText(JSON.stringify({
        username: props.sipUsername,
        password: props.sipPassword,
        server: props.sipServer,
        realm: props.sipRealm,
        displayName: props.displayName
      }))
    });
    sipServerSecret.grantRead(taskExecutionRole);

    // Create a Task Definition with HOST network mode for direct access to host network interfaces
    const taskDefinition = new ecs.Ec2TaskDefinition(this, 'VoipGatewayTaskDefinition', {
      networkMode: ecs.NetworkMode.HOST, // HOST network mode - container shares host's network stack
      executionRole: taskExecutionRole,
      taskRole: taskRole,
    });

    // Add the container to the task definition - with HOST network mode, port mappings
    // work differently as the container directly uses the host's network interfaces
    const container = taskDefinition.addContainer('VoipGatewayContainer', {
      image: ecs.ContainerImage.fromDockerImageAsset(dockerImageAsset),
      memoryLimitMiB: 1024, // Adjust based on your container's needs
      cpu: 1024, // Started at 256, Adjust based on your container's needs
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'voip-gateway' }),
      secrets: {
        SIP_SERVER: ecs.Secret.fromSecretsManager(sipServerSecret, 'server'),
        SIP_USER: ecs.Secret.fromSecretsManager(sipServerSecret, 'username'),
        AUTH_USER: ecs.Secret.fromSecretsManager(sipServerSecret, 'username'),
        AUTH_PASSWORD: ecs.Secret.fromSecretsManager(sipServerSecret, 'password'),
        AUTH_REALM: ecs.Secret.fromSecretsManager(sipServerSecret, 'realm'),
        DISPLAY_NAME: ecs.Secret.fromSecretsManager(sipServerSecret, 'displayName'),
      },
      environment: {
        MEDIA_PORT_BASE: baseRtpPort.toString(),
        MEDIA_PORT_COUNT: rtpPortCount.toString(),
      },
    });

    // No port mapping required since we're using host network mode.

    // Create an EC2 Service - specifically using the EC2 instance we provisioned
    const service = new ecs.Ec2Service(this, 'VoipGatewayService', {
      cluster: this.cluster,
      taskDefinition: taskDefinition,
      desiredCount: 1, // Run one copy of the service
      minHealthyPercent: 0,
      maxHealthyPercent: 100,
    });

    // Output the service name
    new cdk.CfnOutput(this, 'ServiceName', {
      value: service.serviceName,
      description: 'The name of the ECS service',
    });

    // Output the task definition ARN
    new cdk.CfnOutput(this, 'TaskDefinitionArn', {
      value: taskDefinition.taskDefinitionArn,
      description: 'The ARN of the task definition',
    });

    // Output the ECS cluster name
    new cdk.CfnOutput(this, 'ClusterName', {
      value: this.cluster.clusterName
    });

    // Output the VPC ID
    new cdk.CfnOutput(this, 'VpcId', {
      value: vpc.vpcId
    });
  }
}