package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.nova.NovaStreamerFactory;
import org.mjsip.config.OptionParser;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.pool.PortConfig;
import org.mjsip.pool.PortPool;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipKeepAlive;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.*;
import org.mjsip.ua.registration.RegistrationClient;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.LoggerFactory;
import org.zoolu.net.SocketAddress;

import java.util.Map;


/**
 * VoIP Gateway/User Agent for Nova Sonic S2S.
 */
public class NovaSonicVoipGateway extends RegisteringMultipleUAS {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NovaSonicVoipGateway.class);
    // Instance variables
    protected final NovaMediaConfig mediaConfig;
    protected final UAConfig uaConfig;
    private StreamerFactory streamerFactory;
    private RegistrationClient _rc;
    private SipKeepAlive keep_alive;

    // *************************** Public methods **************************

    /**
     * Creates a new UA.
     */
    public NovaSonicVoipGateway(SipProvider sipProvider, PortPool portPool, ServiceOptions serviceConfig,
                                UAConfig uaConfig, NovaMediaConfig mediaConfig) {
        super(sipProvider, portPool, uaConfig, serviceConfig);
        this.mediaConfig = mediaConfig;
        this.uaConfig = uaConfig;
        streamerFactory = new NovaStreamerFactory(this.mediaConfig);
        registerWithKeepAlive();
    }

    /**
     * Disable RegisteringMultipleUAS.register(), which gets called from the constructor.
     * We need _rc to schedule keep-alives, but it's private in the parent class.
     */
    @Override
    public void register() { }

    /**
     * SIP REGISTER with keep-alive packets sent on a schedule.
     */
    public void registerWithKeepAlive() {
        LOG.info("Registering with {}...", this.uaConfig.getRegistrar());
        if (this.uaConfig.isRegister()) {
            this._rc = new RegistrationClient(this.sip_provider, this.uaConfig, this);
            this._rc.loopRegister(this.uaConfig);
        }
        scheduleKeepAlive(uaConfig.getKeepAliveTime());
    }

    private void scheduleKeepAlive(long keepAliveTime) {
        if (keepAliveTime > 0L) {
            SipURI targetUri = this.sip_provider.hasOutboundProxy() ? this.sip_provider.getOutboundProxy() : _rc.getTargetAOR().getAddress().toSipURI();
            String targetHost = targetUri.getHost();
            int targetPort = targetUri.getPort();
            if (targetPort < 0) {
                targetPort = this.sip_provider.sipConfig().getDefaultPort();
            }

            SocketAddress targetSoAddr = new SocketAddress(targetHost, targetPort);
            if (this.keep_alive != null && this.keep_alive.isRunning()) {
                this.keep_alive.halt();
            }

            this.keep_alive = new SipKeepAlive(this.sip_provider, targetSoAddr, (SipMessage)null, keepAliveTime);
            LOG.info("Keep-alive started");
        }
    }

    @Override
    public void unregister() {
        LOG.info("Unregistering with {}...", this.uaConfig.getRegistrar());
        if (this._rc != null) {
            this._rc.unregister();
            this._rc.halt();
            this._rc = null;
        }
    }

    @Override
    protected UserAgentListener createCallHandler(SipMessage msg) {
        register();
        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller,
                                         MediaDesc[] media_descs) {
                LOG.info("Incomming call from: {}", caller.getAddress());

                // Extract caller phone number and store in config
                String callerPhone = extractPhoneNumber(caller.getAddress().toString());
                mediaConfig.setCallerPhoneNumber(callerPhone);
                LOG.info("Caller phone number: {}", callerPhone);

                // Set hangup callback to terminate the call when Nova requests it
                mediaConfig.setHangupCallback(() -> {
                    LOG.info("Hangup callback triggered - terminating call");
                    ua.hangup();
                });

                MediaDesc[] mediaDescs = mediaConfig.getMediaDescs();
                if (mediaDescs == null || mediaDescs.length == 0) {
                    mediaDescs = createDefaultMediaDescs();
                    LOG.info("Using default media descriptors");
                }
                LOG.info("About to accept call with SDP media descriptors: {}", java.util.Arrays.toString(mediaDescs));
                ua.accept(new MediaAgent(mediaDescs, streamerFactory));
            }
        };
    }
    /**
     * The main method.
     */
    public static void main(String[] args) {
        println("mjSIP UserAgent " + SipStack.version);
        SipConfig sipConfig = new SipConfig();
        UAConfig uaConfig = new UAConfig();
        SchedulerConfig schedulerConfig = new SchedulerConfig();
        PortConfig portConfig = new PortConfig();
        ServiceConfig serviceConfig = new ServiceConfig();
        NovaMediaConfig mediaConfig = new NovaMediaConfig();
        Map<String, String> environ = System.getenv();
        mediaConfig.setNovaVoiceId(environ.getOrDefault("NOVA_VOICE_ID","en_us_matthew"));
        if (isConfigured(environ.get("NOVA_PROMPT"))) {
            mediaConfig.setNovaPrompt(environ.get("NOVA_PROMPT"));
        }

        if (isConfigured(environ.get("SIP_SERVER"))) {
            configureFromEnvironment(environ, uaConfig, mediaConfig, portConfig, sipConfig);
        } else {
            OptionParser.parseOptions(args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, mediaConfig, portConfig, serviceConfig);
        }

        sipConfig.normalize();
        uaConfig.normalize(sipConfig);

        SipProvider sipProvider = new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig));
        NovaSonicVoipGateway gateway = new NovaSonicVoipGateway(sipProvider, portConfig.createPool(), serviceConfig, uaConfig, mediaConfig);
    }

    /**
     * Checks if a string is configured.
     * @param str The string
     * @return true if the string is not null and not empty, otherwise false
     */
    private static boolean isConfigured(String str) {
        return str != null && !str.isEmpty();
    }

    private static void configureFromEnvironment(Map<String, String> environ, UAConfig uaConfig,
                                                 NovaMediaConfig mediaConfig, PortConfig portConfig,
                                                 SipConfig sipConfig) {
        uaConfig.setRegistrar(new SipURI(environ.get("SIP_SERVER")));
        uaConfig.setSipUser(environ.get("SIP_USER"));
        uaConfig.setAuthUser(environ.get("AUTH_USER"));
        uaConfig.setAuthPasswd(environ.get("AUTH_PASSWORD"));
        uaConfig.setAuthRealm(environ.get("AUTH_REALM"));
        uaConfig.setDisplayName(environ.get("DISPLAY_NAME"));
        if (isConfigured(environ.get("MEDIA_ADDRESS"))) {
            uaConfig.setMediaAddr(environ.get("MEDIA_ADDRESS"));
        }
        uaConfig.setKeepAliveTime(Long.parseLong(environ.getOrDefault("SIP_KEEPALIVE_TIME","60000")));
        uaConfig.setNoPrompt(true);
        mediaConfig.setMediaDescs(createDefaultMediaDescs());
        if (isConfigured(environ.get("MEDIA_PORT_BASE"))) {
            portConfig.setMediaPort(Integer.parseInt(environ.get("MEDIA_PORT_BASE")));
        }
        if (isConfigured(environ.get("MEDIA_PORT_COUNT"))) {
            portConfig.setPortCount(Integer.parseInt(environ.get("MEDIA_PORT_COUNT")));
        }
        sipConfig.setLogAllPackets(environ.getOrDefault("DEBUG_SIP","true").equalsIgnoreCase("true"));
        if (isConfigured(environ.get("SIP_VIA_ADDR"))) {
            sipConfig.setViaAddrIPv4(environ.get("SIP_VIA_ADDR"));
        }
    }

    /**
     * Extracts phone number from SIP URI.
     * Converts "sip:+14155551234@host" to "+1 (415) 555-1234"
     */
    private static String extractPhoneNumber(String sipUri) {
        // Extract the number from SIP URI (between "sip:" and "@")
        String number = sipUri;
        if (sipUri.startsWith("sip:")) {
            number = sipUri.substring(4);
        }
        int atIndex = number.indexOf('@');
        if (atIndex > 0) {
            number = number.substring(0, atIndex);
        }

        // Format the number if it looks like E.164 (+1XXXXXXXXXX)
        if (number.startsWith("+1") && number.length() == 12) {
            // +14155551234 -> +1 (415) 555-1234
            return String.format("+%s (%s) %s-%s",
                number.substring(1, 2),
                number.substring(2, 5),
                number.substring(5, 8),
                number.substring(8, 12));
        }

        return number; // Return as-is if not standard format
    }

    /**
     * Prints a message to standard output.
     */
    protected static void println(String str) {
        System.out.println(str);
    }

    /**
     * Creates the default media descriptions.
     * @return
     */
    private static MediaDesc[] createDefaultMediaDescs() {
        return new MediaDesc[]{new MediaDesc("audio",
                4000,
                "RTP/AVP",
                new MediaSpec[]{
                        new MediaSpec(0,
                                "PCMU",
                                8000,
                                1,
                                160)})};
    }

}