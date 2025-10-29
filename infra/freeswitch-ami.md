# FreeSWITCH AMI Instance Documentation

## Instance Details

- **Instance ID**: `i-06fcbe4efc776029b`
- **Public IP**: `44.237.82.96`
- **Private IP**: `10.0.1.121`
- **SSH Key**: `/Users/yasser/freeswitch.pem`
- **Instance Type**: (check with AWS console)
- **AMI**: Solve DevOps FreeSWITCH Marketplace Image
- **Documentation**: https://solvedevops.com/docs/freeswitch/how-to-set-up-your-first-freeswitch-system-a-complete-beginners-guide/
- **Status**: Running

## Access

### SSH Access
```bash
ssh -i /Users/yasser/freeswitch.pem admin@44.237.82.96
```

**Note**: This AMI uses the `admin` user (not `ubuntu` or `ec2-user`).

### SSM Session Manager (if configured)
```bash
aws ssm start-session --target i-06fcbe4efc776029b
```

## FreeSWITCH Configuration

### Service Management
```bash
# Check status
sudo systemctl status freeswitch

# Start/stop/restart
sudo systemctl start freeswitch
sudo systemctl stop freeswitch
sudo systemctl restart freeswitch

# View logs
sudo tail -f /var/log/freeswitch/freeswitch.log
```

### FreeSWITCH CLI
```bash
# Connect to FreeSWITCH CLI
fs_cli

# Common commands
fs_cli> status
fs_cli> version
fs_cli> show channels
fs_cli> sofia status
fs_cli> sofia status profile internal
fs_cli> load mod_nova_sonic
fs_cli> module_exists mod_nova_sonic
fs_cli> reloadxml

# Execute commands directly (without entering CLI)
fs_cli -x "version"
fs_cli -x "reloadxml"
fs_cli -x "show channels"
fs_cli -x "sofia status profile internal"
```

## Configuration Files

### Important Paths (Solve DevOps AMI)
- **Main FreeSWITCH Directory**: `/usr/local/freeswitch`
- **Config Directory**: `/etc/freeswitch/` (primary) and `/usr/local/freeswitch/conf/`
- **Extension Configurations**: `/usr/local/freeswitch/conf/directory/default/`
- **SIP Profiles**: `/etc/freeswitch/sip_profiles/`
- **Dialplan**: `/etc/freeswitch/dialplan/`
- **Modules Directory**: `/usr/local/freeswitch/mod/`
- **Logs**: `/var/log/freeswitch/` or `/usr/local/freeswitch/log/`
- **Development Headers**: `/usr/local/freeswitch/include/freeswitch/`
- **Variables**: `/usr/local/freeswitch/conf/vars.xml`
- **Event Socket Config**: `/usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml`

### Dialplan
Edit `/etc/freeswitch/dialplan/default.xml` or create new file in `/etc/freeswitch/dialplan/`:

```xml
<!-- Nova Sonic AI Assistant -->
<extension name="nova_sonic_demo">
  <condition field="destination_number" expression="^(8888)$">
    <action application="answer"/>
    <action application="sleep" data="500"/>
    <action application="set" data="bypass_media=false"/>
    <action application="nova_sonic" data=""/>
    <action application="hangup"/>
  </condition>
</extension>
```

## Module Installation

### Building mod_nova_sonic
```bash
# On FreeSWITCH instance
cd /tmp
git clone <repository-url>
cd freeswitch-module

# Build the module
make

# Install
sudo make install

# Verify installation
ls -l /usr/lib/freeswitch/mod/mod_nova_sonic.so
# or
ls -l /usr/local/freeswitch/mod/mod_nova_sonic.so
```

### Module Configuration
Create `/usr/local/freeswitch/conf/autoload_configs/nova_sonic.conf.xml`:

```xml
<configuration name="nova_sonic.conf" description="Nova Sonic Audio Proxy">
  <settings>
    <!-- Java Gateway Instance -->
    <param name="gateway-host" value="34.208.83.171"/>
    <param name="gateway-port" value="8085"/>
  </settings>
</configuration>
```

Or set via environment variables in `/etc/default/freeswitch`:
```bash
NOVA_GATEWAY_HOST=34.208.83.171
NOVA_GATEWAY_PORT=8085
```

After creating/modifying config, reload XML:
```bash
fs_cli -x "reloadxml"
```

### Loading the Module
```bash
# Load manually in fs_cli
fs_cli> load mod_nova_sonic

# Or add to /usr/local/freeswitch/conf/autoload_configs/modules.conf.xml
<load module="mod_nova_sonic"/>

# Then reload
fs_cli -x "reloadxml"
```

## Integration with Java Gateway

### Java Gateway Details
- **Instance ID**: `i-0fa82e4df8fcad08e`
- **Public IP**: `34.208.83.171`
- **Private IP**: `10.0.0.68`
- **Listening Port**: TCP 8085

### Network Requirements
Ensure FreeSWITCH can reach Java Gateway:

```bash
# Test connectivity
telnet 34.208.83.171 8085

# Or use nc
nc -zv 34.208.83.171 8085
```

### Security Group Configuration
Java Gateway security group must allow:
- **Protocol**: TCP
- **Port**: 8085
- **Source**: 44.237.82.96/32 (FreeSWITCH public IP)

## Troubleshooting

### Check FreeSWITCH Status
```bash
sudo systemctl status freeswitch
fs_cli -x "status"
```

### Check Module Loading
```bash
fs_cli -x "module_exists mod_nova_sonic"
fs_cli -x "show modules" | grep nova
```

### Check Connectivity to Java Gateway
```bash
telnet 34.208.83.171 8085
curl -v telnet://34.208.83.171:8085
```

### View Logs
```bash
# FreeSWITCH main log
sudo tail -f /var/log/freeswitch/freeswitch.log

# Enable debug logging in fs_cli
fs_cli> console loglevel 7

# Check for mod_nova_sonic messages
sudo grep -i "nova" /var/log/freeswitch/freeswitch.log
```

### Common Issues

**Module won't load**:
```bash
# Check if .so file exists
ls -l /usr/local/freeswitch/mod/mod_nova_sonic.so

# Check dependencies
ldd /usr/local/freeswitch/mod/mod_nova_sonic.so

# Check FreeSWITCH error logs
sudo tail -f /var/log/freeswitch/freeswitch.log | grep -i error
# or
sudo tail -f /usr/local/freeswitch/log/freeswitch.log | grep -i error
```

**Cannot connect to Java Gateway**:
```bash
# Check network connectivity
ping 34.208.83.171
telnet 34.208.83.171 8085

# Check security groups
aws ec2 describe-security-groups --group-ids <java-gateway-sg-id>

# Check Java Gateway is running
aws ssm start-session --target i-0fa82e4df8fcad08e
# Then: netstat -tlnp | grep 8085
```

## Security Notes

This Solve DevOps AMI has the following security defaults:
- ESL (Event Socket Layer) locked to localhost by default
- Default passwords should be changed in production
- Recommended firewall ports:
  - 22/tcp (SSH)
  - 5060/tcp, 5060/udp (SIP)
  - 5061/tcp (SIP TLS, if used)
  - 16384-32768/udp (RTP media)
  - 8085/tcp (for mod_nova_sonic connection to Java Gateway)

## Next Steps

1. ✅ FreeSWITCH installation paths determined (Solve DevOps AMI)
2. ⏳ Build and install mod_nova_sonic to `/usr/local/freeswitch/mod/`
3. ⏳ Create config at `/usr/local/freeswitch/conf/autoload_configs/nova_sonic.conf.xml`
4. ⏳ Configure module to connect to Java Gateway (34.208.83.171:8085)
5. ⏳ Load module and verify connectivity
6. ⏳ Test end-to-end call flow
7. ⏳ Configure dialplan for production use

## References

- **AMI Documentation**: https://solvedevops.com/docs/freeswitch/how-to-set-up-your-first-freeswitch-system-a-complete-beginners-guide/
- **Main Integration Guide**: `INTEGRATION_GUIDE.md`
- **Module Source**: `freeswitch-module/src/mod_nova_sonic.c`
- **Java Gateway**: `voice-gateway/src/main/java/com/example/s2s/voipgateway/freeswitch/`
