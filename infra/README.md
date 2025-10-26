# Infrastructure Module

**Owner:** Dev B

This module contains all CDK infrastructure-as-code for deploying the voice gateway.

## Structure

```
infra/
  ├── cdk-ecs/              # ECS on EC2 deployment (production-like)
  ├── cdk-ec2-instance/     # Single EC2 instance (development)
  └── README.md
```

## Deployment Options

### Option 1: ECS on EC2 (Production)

Best for production workloads with high availability and auto-scaling.

**Prerequisites:**
- Node.js and CDK installed
- Docker running (for building container image)
- Maven build completed in `/voice-gateway`

**Steps:**
```bash
cd cdk-ecs
npm install
cp cdk.context.json.template cdk.context.json
# Edit cdk.context.json with your configuration
cdk bootstrap
cdk deploy
```

**What it creates:**
- VPC with public/private subnets
- ECS cluster with EC2 auto-scaling group
- VPC endpoints for ECR
- Security groups (UDP 5060, 10000-20000)
- IAM roles (Bedrock + Connect permissions)
- Secrets Manager for SIP credentials

**Cleanup:**
```bash
cd cdk-ecs
cdk destroy
```

### Option 2: Single EC2 Instance (Development)

Best for development, testing, and quick iterations.

**Prerequisites:**
- Node.js and CDK installed
- EC2 key pair created

**Steps:**
```bash
cd cdk-ec2-instance
npm install
# Edit bin/cdk.ts to set your key pair name
cdk bootstrap
cdk deploy
```

**What it creates:**
- VPC (or uses existing if configured)
- Single EC2 instance with Amazon Linux
- Pre-installed: JDK, Maven, Git
- Security groups
- IAM instance role (Bedrock + Connect permissions)

**Running the gateway:**
```bash
# SSH to instance using output IP and key pair
ssh -i ~/.ssh/your-key.pem ec2-user@<IP>

# Clone repo and run
git clone <repo-url>
cd connect-nova-proxy-1/voice-gateway
./run.sh
```

**Cleanup:**
```bash
cd cdk-ec2-instance
cdk destroy
```

## Configuration

Both stacks support these configurations:

### SIP Settings
- `sipServer` - SIP server hostname/IP
- `sipUsername` - SIP username
- `sipPassword` - SIP password
- `sipRealm` - SIP authentication realm
- `displayName` - Caller ID display name

### Connect Settings
- `connectInstanceId` - Amazon Connect instance ID
- Region is automatically set to deployment region

### RTP Port Range
- `baseRtpPort` (default: 10000)
- `rtpPortCount` (default: 10000)

## Networking

**Required Inbound Rules:**
- UDP 5060 (SIP signaling)
- UDP 10000-20000 (RTP media)

**Required Outbound:**
- All traffic (for SIP server, AWS APIs)

## IAM Permissions

Both stacks create IAM roles with:

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "connect:UpdateContactAttributes"
      ],
      "Resource": "arn:aws:connect:*:*:instance/*/contact/*"
    }
  ]
}
```

## Monitoring

Both stacks integrate with CloudWatch:
- ECS: Container Insights enabled
- EC2: CloudWatch agent can be configured
- Logs: Application logs via stdout/stderr

## Cost Estimates

**ECS Deployment:**
- EC2 t3.medium: ~$30/month
- VPC: Free
- Data transfer: Variable
- Total: ~$35-50/month

**EC2 Deployment:**
- EC2 t3.medium: ~$30/month
- Total: ~$30-35/month

## Development Workflow

1. **Dev A** makes changes to `/voice-gateway`
2. Build and test locally or on EC2 instance
3. When ready: `cd voice-gateway && mvn package`
4. **Dev B** updates CDK if infrastructure changes needed
5. Deploy: `cd infra/cdk-ecs && cdk deploy`

## Troubleshooting

**Build fails - mjSIP dependency:**
- Ensure `~/.m2/settings.xml` configured with GitHub token
- See `/voice-gateway/README.md` for setup instructions

**Container won't start:**
- Check CloudWatch logs in ECS console
- Verify SIP credentials in Secrets Manager
- Check security group allows UDP 5060

**SIP registration fails:**
- Verify `SIP_SERVER` is reachable from VPC
- Check SIP credentials
- Enable `DEBUG_SIP=true` for packet logs

**No audio on calls:**
- Verify RTP port range open in security groups
- Check `MEDIA_ADDRESS` is set to instance public IP
- Ensure NAT not blocking UDP return traffic
