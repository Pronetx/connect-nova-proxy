#!/bin/bash
#
# FreeSWITCH Installation Script for Ubuntu 22.04
#
# This script:
# 1. Installs FreeSWITCH from official SignalWire packages
# 2. Installs development headers for module compilation
# 3. Downloads configuration from Parameter Store
# 4. Configures FreeSWITCH with public IP
# 5. Starts FreeSWITCH service
#

set -e  # Exit on error
set -x  # Print commands (for debugging)

# Configuration
LOG_FILE="/var/log/freeswitch-install.log"
AWS_REGION="${AWS_REGION:-us-west-2}"
PUBLIC_IP="${PUBLIC_IP:-$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4)}"

echo "=== FreeSWITCH Installation Starting ===" | tee -a "$LOG_FILE"
echo "Region: $AWS_REGION" | tee -a "$LOG_FILE"
echo "Public IP: $PUBLIC_IP" | tee -a "$LOG_FILE"
echo "Timestamp: $(date)" | tee -a "$LOG_FILE"

# Update system
echo "Updating system packages..." | tee -a "$LOG_FILE"
apt-get update
apt-get upgrade -y

# Install dependencies
echo "Installing dependencies..." | tee -a "$LOG_FILE"
apt-get install -y \
    wget \
    curl \
    gnupg2 \
    ca-certificates \
    lsb-release \
    apt-transport-https

# Add FreeSWITCH repository (SignalWire official)
echo "Adding FreeSWITCH repository..." | tee -a "$LOG_FILE"
wget --http-user=signalwire --http-password=pat_X95gDVvZKBb6d8ZKJL87uHje -O - https://freeswitch.signalwire.com/repo/deb/debian-release/signalwire-freeswitch-repo.gpg | apt-key add -

echo "machine freeswitch.signalwire.com login signalwire password pat_X95gDVvZKBb6d8ZKJL87uHje" > /etc/apt/auth.conf
chmod 600 /etc/apt/auth.conf

echo "deb https://freeswitch.signalwire.com/repo/deb/debian-release/ `lsb_release -sc` main" > /etc/apt/sources.list.d/freeswitch.list

# Update package lists
apt-get update

# Install FreeSWITCH with development headers
echo "Installing FreeSWITCH..." | tee -a "$LOG_FILE"
apt-get install -y \
    freeswitch-meta-all \
    freeswitch-mod-commands \
    freeswitch-mod-sofia \
    freeswitch-mod-dialplan-xml \
    freeswitch-mod-event-socket \
    freeswitch-lang-en \
    freeswitch-sounds-en-us-callie

# Install development tools for module compilation
echo "Installing development tools..." | tee -a "$LOG_FILE"
apt-get install -y \
    build-essential \
    gcc \
    make \
    pkg-config \
    libfreeswitch-dev

# Create FreeSWITCH user if not exists
echo "Setting up FreeSWITCH user..." | tee -a "$LOG_FILE"
if ! id freeswitch &>/dev/null; then
    useradd -r -g daemon -s /sbin/nologin -c "FreeSWITCH" -d /var/lib/freeswitch freeswitch
fi

# Set ownership
chown -R freeswitch:daemon /etc/freeswitch
chown -R freeswitch:daemon /var/lib/freeswitch
chown -R freeswitch:daemon /var/log/freeswitch
chown -R freeswitch:daemon /usr/share/freeswitch

# Fetch configuration from Parameter Store
echo "Fetching configuration from Parameter Store..." | tee -a "$LOG_FILE"

# Install AWS CLI if not present
if ! command -v aws &> /dev/null; then
    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
    unzip -q awscliv2.zip
    ./aws/install
    rm -rf aws awscliv2.zip
fi

# Fetch vars.xml
aws ssm get-parameter \
    --name "/freeswitch/config/vars" \
    --query 'Parameter.Value' \
    --output text \
    --region "$AWS_REGION" > /tmp/vars.xml

# Replace IP placeholder
sed -i "s/EXTERNAL_IP_PLACEHOLDER/$PUBLIC_IP/g" /tmp/vars.xml
cp /tmp/vars.xml /etc/freeswitch/vars.xml
chown freeswitch:daemon /etc/freeswitch/vars.xml

# Fetch external SIP profile
aws ssm get-parameter \
    --name "/freeswitch/config/sip-profile" \
    --query 'Parameter.Value' \
    --output text \
    --region "$AWS_REGION" > /etc/freeswitch/sip_profiles/external.xml
chown freeswitch:daemon /etc/freeswitch/sip_profiles/external.xml

# Fetch dialplan
aws ssm get-parameter \
    --name "/freeswitch/config/dialplan" \
    --query 'Parameter.Value' \
    --output text \
    --region "$AWS_REGION" > /etc/freeswitch/dialplan/public.xml
chown freeswitch:daemon /etc/freeswitch/dialplan/public.xml

# Enable and start FreeSWITCH
echo "Starting FreeSWITCH service..." | tee -a "$LOG_FILE"
systemctl enable freeswitch
systemctl start freeswitch

# Wait for service to start
sleep 5

# Check status
systemctl status freeswitch --no-pager | tee -a "$LOG_FILE"

# Verify FreeSWITCH is listening on port 5060
echo "Verifying SIP listener..." | tee -a "$LOG_FILE"
ss -tulpn | grep 5060 | tee -a "$LOG_FILE"

echo "=== FreeSWITCH Installation Complete ===" | tee -a "$LOG_FILE"
echo "Timestamp: $(date)" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "FreeSWITCH installed at: /usr/bin/freeswitch" | tee -a "$LOG_FILE"
echo "Config directory: /etc/freeswitch" | tee -a "$LOG_FILE"
echo "Module directory: /usr/lib/freeswitch/mod" | tee -a "$LOG_FILE"
echo "Development headers: /usr/include/freeswitch" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "To check FreeSWITCH status: systemctl status freeswitch" | tee -a "$LOG_FILE"
echo "To connect to console: fs_cli" | tee -a "$LOG_FILE"
echo "To compile modules: gcc -fPIC -O2 -I/usr/include/freeswitch -shared -o mod_name.so mod_name.c" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "SIP URI: sip:$PUBLIC_IP:5060" | tee -a "$LOG_FILE"
