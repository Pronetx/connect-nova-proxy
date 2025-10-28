# FreeSWITCH CDK Stack - Phase 1

This CDK stack deploys a FreeSWITCH server on EC2 for **Phase 1** of the mjSIP to FreeSWITCH migration.

## What This Does

Phase 1 deploys a basic FreeSWITCH installation that:
- ✅ Accepts SIP calls on UDP port 5060
- ✅ Answers incoming calls
- ✅ Plays a canned greeting message
- ✅ Hangs up after the greeting
- ✅ Logs all SIP/RTP activity to CloudWatch

**No AI integration yet** - this validates that FreeSWITCH can successfully handle calls from your Voice Connector before adding complexity.

## Architecture

```
PSTN Caller → Chime Voice Connector → FreeSWITCH (EC2)
                                          ↓
                                     Play greeting.wav
                                          ↓
                                       Hangup
```

## Prerequisites

- AWS CLI configured with credentials
- Node.js 18+ and npm
- CDK v2 installed (`npm install -g aws-cdk`)
- EC2 Key Pair created in your region (for SSH access)

## Deployment

### 1. Install Dependencies

```bash
cd infra/cdk-freeswitch
npm install
```

### 2. Configure Stack

Edit `bin/cdk.ts` and set:
- `account`: Your AWS account ID
- `region`: Same region as your Voice Connector (e.g., `us-west-2`)
- `keyPairName`: Your EC2 key pair name for SSH access

### 3. Bootstrap CDK (First Time Only)

```bash
cdk bootstrap aws://ACCOUNT-ID/REGION
```

### 4. Deploy Stack

```bash
cdk deploy
```

Deployment takes approximately **15-20 minutes** due to:
- FreeSWITCH compilation from source
- Sound file installation
- CloudWatch agent setup

### 5. Note the Outputs

After deployment completes, CDK will output:

```
FreeSwitchStack.InstancePublicIP = 54.XXX.XXX.XXX
FreeSwitchStack.SipUri = sip:54.XXX.XXX.XXX:5060
FreeSwitchStack.SSMSessionCommand = aws ssm start-session --target i-XXXXXXXXX
```

**Save these values** - you'll need them for testing.

## Configuration Files

FreeSWITCH configuration is stored in AWS Systems Manager Parameter Store:

| Parameter | Description |
|-----------|-------------|
| `/freeswitch/config/vars` | Global variables (IPs, ports, codecs) |
| `/freeswitch/config/sip-profile` | External SIP profile settings |
| `/freeswitch/config/dialplan` | Dialplan (call routing logic) |

Configuration is fetched during EC2 instance bootstrap and written to:
- `/usr/local/freeswitch/conf/vars.xml`
- `/usr/local/freeswitch/conf/sip_profiles/external.xml`
- `/usr/local/freeswitch/conf/dialplan/public.xml`

## Testing

### Test 1: Verify FreeSWITCH is Running

```bash
# Connect via SSM Session Manager (recommended)
aws ssm start-session --target i-XXXXXXXXX

# Or SSH (if key pair configured)
ssh -i ~/.ssh/your-key.pem ec2-user@54.XXX.XXX.XXX

# Check service status
sudo systemctl status freeswitch

# Verify SIP listener
sudo ss -tulpn | grep 5060
```

Expected output:
```
udp   UNCONN 0      0      0.0.0.0:5060      0.0.0.0:*    users:(("freeswitch",pid=XXXX,fd=YY))
```

### Test 2: Check FreeSWITCH Console

```bash
sudo /usr/local/freeswitch/bin/fs_cli

# In the FreeSWITCH console:
sofia status                    # Shows SIP profiles
sofia status profile external   # Shows external profile details
```

Expected output:
```
=================================================================================================
Name                   external
Profile-Name           external
...
State                  RUNNING (0)
```

Type `exit` to leave the console.

### Test 3: Monitor Logs

```bash
# FreeSWITCH logs
sudo tail -f /usr/local/freeswitch/log/freeswitch.log

# Installation logs
sudo tail -f /var/log/freeswitch-install.log

# CloudWatch Logs (via AWS Console)
# Log Group: /aws/freeswitch/system
```

### Test 4: Configure Voice Connector

Update your Chime Voice Connector **Origination** settings to point to FreeSWITCH:

1. Go to AWS Console → Chime SDK → Voice Connectors → Your Connector
2. Click **Origination**
3. Add a new route:
   - **Host**: Your FreeSWITCH public IP (from CDK output)
   - **Port**: 5060
   - **Protocol**: UDP
   - **Priority**: 1
   - **Weight**: 1

### Test 5: Make a Test Call

1. Dial your Voice Connector phone number
2. You should hear: "Thank you for calling. This is FreeSWITCH Phase One test. Goodbye."
3. Call automatically hangs up after the greeting

**If you hear the greeting**: Phase 1 is complete! FreeSWITCH is successfully handling SIP/RTP.

## Troubleshooting

### Issue: Call connects but no audio

**Check RTP ports**:
```bash
sudo ss -tulpn | grep -E ":(1[0-9]{4}|20000)"
```

**Check security group**: Ensure UDP ports 10000-20000 are open in AWS Console.

**Check FreeSWITCH logs**:
```bash
sudo tail -f /usr/local/freeswitch/log/freeswitch.log | grep -i rtp
```

### Issue: Call doesn't connect (timeout/busy)

**Verify SIP profile is running**:
```bash
sudo /usr/local/freeswitch/bin/fs_cli -x "sofia status"
```

**Check SIP trace**:
```bash
sudo tail -f /usr/local/freeswitch/log/freeswitch.log | grep -i invite
```

**Verify Voice Connector can reach FreeSWITCH**:
- Check security group allows UDP 5060 from Voice Connector CIDR
- Verify public IP in CDK output matches EC2 instance

### Issue: FreeSWITCH service won't start

**Check installation logs**:
```bash
sudo cat /var/log/freeswitch-install.log
```

**Try manual start with debug**:
```bash
sudo /usr/local/freeswitch/bin/freeswitch -nc -nonat -nf
# (ctrl+C to stop)
```

**Check for port conflicts**:
```bash
sudo ss -tulpn | grep 5060
```

### Issue: Greeting file not playing

**Verify greeting file exists**:
```bash
ls -lh /usr/local/freeswitch/sounds/greeting.wav
```

**Test playback manually** (in fs_cli):
```
originate user/1000 &playback(/usr/local/freeswitch/sounds/greeting.wav)
```

## Stack Outputs

After successful deployment, these CloudFormation outputs are available:

| Output | Description |
|--------|-------------|
| `InstancePublicIP` | Public IP of FreeSWITCH server |
| `InstanceId` | EC2 instance ID (for SSM) |
| `SipUri` | SIP URI for Voice Connector |
| `SSMSessionCommand` | Command to connect via Session Manager |

## Cost Estimate

**EC2 Instance** (t3.small): ~$15/month
**Data Transfer**: ~$5-10/month (varies by call volume)
**CloudWatch Logs**: ~$1-2/month
**Parameter Store**: Free (standard tier)

**Total**: ~$20-30/month

## Cleanup

To destroy the stack and stop charges:

```bash
cdk destroy
```

**Note**: Parameter Store parameters are retained by default. To delete them:

```bash
aws ssm delete-parameter --name /freeswitch/config/vars
aws ssm delete-parameter --name /freeswitch/config/sip-profile
aws ssm delete-parameter --name /freeswitch/config/dialplan
```

## Next Steps (Phase 2)

Once Phase 1 is validated:
- [ ] Configure FreeSWITCH Event Socket (ESL)
- [ ] Create external media handler to pipe audio to Java service
- [ ] Integrate Nova Sonic streaming
- [ ] Add barge-in detection
- [ ] Implement Connect attribute updates

See `/docs/freeswitch-migration-plan.md` for full roadmap.

## Files in This Stack

```
cdk-freeswitch/
├── bin/cdk.ts                      # CDK app entry point
├── lib/freeswitch-stack.ts         # CDK stack definition
├── configs/
│   ├── vars.xml                    # FreeSWITCH variables
│   ├── sip_profiles/external.xml   # SIP profile config
│   └── dialplan/default.xml        # Dialplan (call routing)
├── scripts/
│   └── install-freeswitch.sh       # Installation script
├── package.json
├── tsconfig.json
├── cdk.json
└── README.md                       # This file
```

## Support

For issues:
1. Check troubleshooting section above
2. Review CloudWatch logs
3. Check `/var/log/freeswitch-install.log` on EC2
4. Review FreeSWITCH logs in `/usr/local/freeswitch/log/`

## License

MIT-0 (same as parent project)
