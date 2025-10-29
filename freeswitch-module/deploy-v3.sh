#!/bin/bash
set -e

FREESWITCH_HOST="44.237.82.96"
SSH_KEY="/Users/yasser/freeswitch.pem"
MODULE_NAME="mod_nova_sonic"

echo "🔨 Building $MODULE_NAME (v3)..."
./build-v3.sh

echo ""
echo "📤 Uploading to FreeSWITCH..."
scp -i "$SSH_KEY" mod_nova_sonic_v3.tar.gz admin@$FREESWITCH_HOST:/tmp/

echo ""
echo "🔄 Deploying on FreeSWITCH..."
ssh -i "$SSH_KEY" admin@$FREESWITCH_HOST << 'EOF'
cd /tmp
tar -xzf mod_nova_sonic_v3.tar.gz
sudo mv mod_nova_sonic.so /usr/local/freeswitch/mod/
sudo chown freeswitch:freeswitch /usr/local/freeswitch/mod/mod_nova_sonic.so
sudo chmod 755 /usr/local/freeswitch/mod/mod_nova_sonic.so
echo "Module deployed"
EOF

echo ""
echo "♻️  Reloading module in FreeSWITCH..."
ssh -i "$SSH_KEY" admin@$FREESWITCH_HOST 'sudo fs_cli -x "reload mod_nova_sonic"'

echo ""
echo "✅ Deployment complete!"
echo ""
echo "📝 Update your dialplan to use: nova_ai_session"
echo ""
echo "Example dialplan:"
echo '  <action application="nova_ai_session"/>'
