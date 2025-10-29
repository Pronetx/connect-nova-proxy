#!/bin/bash
set -e

FREESWITCH_HOST="44.237.82.96"
SSH_KEY="/Users/yasser/freeswitch.pem"
MODULE_NAME="mod_nova_sonic"

echo "📤 Uploading source to FreeSWITCH..."
scp -i "$SSH_KEY" src/mod_nova_sonic_v3.c admin@$FREESWITCH_HOST:/tmp/

echo ""
echo "🔨 Building on FreeSWITCH server..."
ssh -i "$SSH_KEY" admin@$FREESWITCH_HOST << 'EOF'
cd /tmp

echo "Compiling module..."
sudo gcc -fPIC -O3 -I/usr/local/freeswitch/include/freeswitch \
    -c mod_nova_sonic_v3.c -o mod_nova_sonic.o

echo "Linking module..."
sudo gcc -shared -o mod_nova_sonic.so mod_nova_sonic.o

echo "Installing module..."
sudo mv mod_nova_sonic.so /usr/local/freeswitch/mod/
sudo chown freeswitch:freeswitch /usr/local/freeswitch/mod/mod_nova_sonic.so
sudo chmod 755 /usr/local/freeswitch/mod/mod_nova_sonic.so

echo "Cleaning up..."
sudo rm -f /tmp/mod_nova_sonic.o /tmp/mod_nova_sonic_v3.c

echo "Module built and installed"
EOF

echo ""
echo "♻️  Reloading module in FreeSWITCH..."
ssh -i "$SSH_KEY" admin@$FREESWITCH_HOST 'sudo fs_cli -x "reload mod_nova_sonic"'

echo ""
echo "✅ Deployment complete!"
echo ""
echo "📝 Update your dialplan to use: nova_ai_session"
