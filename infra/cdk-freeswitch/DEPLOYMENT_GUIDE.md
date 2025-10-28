# FreeSWITCH Phase 1 - Quick Deployment Guide

## Pre-Deployment Checklist

- [ ] AWS CLI configured (`aws sts get-caller-identity`)
- [ ] Node.js 18+ installed (`node --version`)
- [ ] CDK installed (`npm install -g aws-cdk`)
- [ ] EC2 key pair created in target region
- [ ] Account ID and region confirmed

## Step-by-Step Deployment

### 1. Navigate to Stack Directory

```bash
cd infra/cdk-freeswitch
```

### 2. Install Dependencies

```bash
npm install
```

### 3. Edit Configuration

Edit `bin/cdk.ts`:

```typescript
new FreeSwitchStack(app, 'FreeSwitchStack', {
  env: {
    account: 'YOUR-ACCOUNT-ID',    // ← Update this
    region: 'us-west-2'             // ← Update if needed
  },
  keyPairName: 'your-key-pair',    // ← Update this
});
```

### 4. Bootstrap CDK (First Time Only)

```bash
cdk bootstrap aws://YOUR-ACCOUNT-ID/us-west-2
```

### 5. Review Changes

```bash
cdk diff
```

### 6. Deploy

```bash
cdk deploy
```

**Answer "y" when prompted to approve changes.**

Deployment takes **15-20 minutes** (FreeSWITCH compiles from source).

### 7. Save Outputs

When complete, you'll see:

```
✅  FreeSwitchStack

Outputs:
FreeSwitchStack.InstancePublicIP = 54.XXX.XXX.XXX
FreeSwitchStack.SipUri = sip:54.XXX.XXX.XXX:5060
FreeSwitchStack.SSMSessionCommand = aws ssm start-session --target i-XXXXXXXXX
```

**Save these values!**

## Post-Deployment Verification

### 1. Connect to Instance

```bash
aws ssm start-session --target i-XXXXXXXXX
```

### 2. Check FreeSWITCH Status

```bash
sudo systemctl status freeswitch
```

Expected: **active (running)**

### 3. Verify SIP Listener

```bash
sudo ss -tulpn | grep 5060
```

Expected: `udp ... 0.0.0.0:5060`

### 4. Check FreeSWITCH Console

```bash
sudo /usr/local/freeswitch/bin/fs_cli
```

Commands to try:
```
sofia status
sofia status profile external
show channels
```

Type `exit` to quit.

### 5. Monitor Logs

```bash
sudo tail -f /usr/local/freeswitch/log/freeswitch.log
```

## Configure Voice Connector

### AWS Console Steps

1. **Navigate**: Chime SDK → Voice Connectors → Your Connector
2. **Click**: Origination tab
3. **Add Route**:
   - Host: `54.XXX.XXX.XXX` (your FreeSWITCH IP)
   - Port: `5060`
   - Protocol: `UDP`
   - Priority: `1`
   - Weight: `1`
4. **Save**

### CLI Method

```bash
aws chime-sdk-voice put-voice-connector-origination \
  --voice-connector-id YOUR-CONNECTOR-ID \
  --origination '{
    "Routes": [{
      "Host": "54.XXX.XXX.XXX",
      "Port": 5060,
      "Protocol": "UDP",
      "Priority": 1,
      "Weight": 1
    }],
    "Disabled": false
  }'
```

## Test the Setup

### Make a Test Call

1. Dial your Voice Connector phone number
2. Listen for greeting: "Thank you for calling. This is FreeSWITCH Phase One test. Goodbye."
3. Call hangs up automatically

**Hear the greeting?** ✅ Phase 1 complete!

### Troubleshooting

**No audio?**
```bash
# Check RTP ports
sudo ss -tulpn | grep -E "1[0-9]{4}"

# Check security group in AWS Console
# Ensure UDP 10000-20000 is open
```

**Call doesn't connect?**
```bash
# Check FreeSWITCH logs during call attempt
sudo tail -f /usr/local/freeswitch/log/freeswitch.log | grep -i invite
```

**Service not running?**
```bash
# Check installation logs
sudo cat /var/log/freeswitch-install.log

# Try manual start
sudo /usr/local/freeswitch/bin/freeswitch -nc -nonat -nf
```

## Modifying Configuration

Configuration is in Parameter Store. To update:

### 1. Update Local Files

Edit files in `configs/`:
- `vars.xml` - Global variables
- `sip_profiles/external.xml` - SIP profile
- `dialplan/default.xml` - Call routing

### 2. Redeploy

```bash
cdk deploy
```

This updates Parameter Store but **doesn't restart FreeSWITCH**.

### 3. Apply Changes on Instance

```bash
# Connect to instance
aws ssm start-session --target i-XXXXXXXXX

# Re-fetch configuration
sudo /tmp/install-freeswitch.sh

# Or manually:
aws ssm get-parameter --name /freeswitch/config/dialplan \
  --query 'Parameter.Value' --output text | \
  sudo tee /usr/local/freeswitch/conf/dialplan/public.xml

# Restart FreeSWITCH
sudo systemctl restart freeswitch
```

## Cleanup

To tear down everything:

```bash
cdk destroy
```

To also remove Parameter Store entries:

```bash
aws ssm delete-parameter --name /freeswitch/config/vars
aws ssm delete-parameter --name /freeswitch/config/sip-profile
aws ssm delete-parameter --name /freeswitch/config/dialplan
```

## Next Steps

Once Phase 1 is validated:
1. Document Voice Connector → FreeSWITCH call flow
2. Begin Phase 2: Event Socket integration
3. Plan audio streaming to Java service
4. Design Nova Sonic integration

## Common Commands Reference

```bash
# System Management
sudo systemctl status freeswitch
sudo systemctl restart freeswitch
sudo systemctl stop freeswitch

# FreeSWITCH CLI
sudo /usr/local/freeswitch/bin/fs_cli

# Logs
sudo tail -f /usr/local/freeswitch/log/freeswitch.log
sudo tail -f /var/log/freeswitch-install.log

# Network
sudo ss -tulpn | grep 5060              # SIP
sudo ss -tulpn | grep -E "1[0-9]{4}"    # RTP

# Configuration
ls /usr/local/freeswitch/conf/
cat /usr/local/freeswitch/conf/vars.xml

# CloudWatch Logs (AWS Console)
# Log Group: /aws/freeswitch/system
```

## Support

For issues:
1. Check logs: `/usr/local/freeswitch/log/freeswitch.log`
2. Check installation: `/var/log/freeswitch-install.log`
3. Review CloudWatch logs in AWS Console
4. Check CDK deployment events in CloudFormation

## Cost

- **EC2 t3.small**: ~$15/month
- **Data transfer**: ~$5-10/month
- **CloudWatch**: ~$1-2/month
- **Total**: ~$20-30/month
