import org.conscrypt.Conscrypt;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1b spike, Android side. Conscrypt-over-BoringSSL is the TLS stack Android ships, and
 * {@code android.net.ssl.SSLSockets.exportKeyingMaterial} (public SDK, API 31+) is a thin
 * delegation to the same Conscrypt entry point this harness calls directly. Running it on the
 * JVM lets the exporter question be answered on a laptop instead of only on a phone.
 *
 * <p>Answers, empirically:
 * <ol>
 *   <li>Does Conscrypt expose a working TLS 1.3 keying-material exporter?</li>
 *   <li>Under TLS 1.3, does a {@code null} context produce the same bytes as a zero-length one?
 *       (RFC 8446 §7.5 says it must; RFC 5705 / TLS 1.2 says it must not.)</li>
 *   <li>Do both endpoints of one handshake export identical bytes?</li>
 *   <li>Is a certificate produced by RideLink's own DER encoder accepted, and does its
 *       self-signature verify under an independent X.509 implementation?</li>
 * </ol>
 *
 * <p>Modes: {@code pair} runs both endpoints in-process; {@code server PORT} runs only the server
 * so an Apple Network.framework client can connect to it (the cross-stack experiment).
 */
public final class ConscryptExporterSpike {
    private static final String LABEL = "EXPORTER-RideLink-SAS-v1"; // PROTOCOL §4.5.1
    private static final int LENGTH = 32;                           // PROTOCOL §4.5.1

    private ConscryptExporterSpike() {
    }

    private static String hex(byte[] b) {
        if (b == null) {
            return "<null>";
        }
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }

    /** RideLink does not use PKI validation: trust is the SPKI pin applied above the transport. */
    private static TrustManager[] acceptAnyCertificate() {
        return new TrustManager[]{new X509ExtendedTrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }

            public void checkServerTrusted(X509Certificate[] c, String a) { }

            public void checkClientTrusted(X509Certificate[] c, String a, Socket s) { }

            public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { }

            public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }

            public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
    }

    private static SSLContext context(Provider provider, String p12, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(p12)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3", provider);
        ctx.init(kmf.getKeyManagers(), acceptAnyCertificate(), SecureRandom.getInstanceStrong());
        return ctx;
    }

    private static void report(String side, SSLSocket s) throws Exception {
        X509Certificate own = (X509Certificate) s.getSession().getLocalCertificates()[0];
        byte[] nullContext = Conscrypt.exportKeyingMaterial(s, LABEL, null, LENGTH);
        byte[] emptyContext = Conscrypt.exportKeyingMaterial(s, LABEL, new byte[0], LENGTH);
        byte[] someContext = Conscrypt.exportKeyingMaterial(s, LABEL, new byte[]{1, 2, 3}, LENGTH);
        X509Certificate peer = (X509Certificate) s.getSession().getPeerCertificates()[0];
        byte[] spki = peer.getPublicKey().getEncoded(); // PublicKey.getEncoded() IS the DER SPKI
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        System.out.println(side + "_ENABLED=" + String.join(",", s.getEnabledProtocols()));
        System.out.println(side + "_SESSION_GETPROTOCOL=" + s.getSession().getProtocol());
        System.out.println(side + "_CIPHER=" + s.getSession().getCipherSuite());
        System.out.println(side + "_EXPORTER_NULLCTX=" + hex(nullContext));
        System.out.println(side + "_EXPORTER_EMPTYCTX=" + hex(emptyContext));
        System.out.println(side + "_EXPORTER_CTX010203=" + hex(someContext));
        System.out.println(side + "_PEER_SPKI_DER=" + hex(spki));
        System.out.println(side + "_PEER_SPKI_SHA256=" + hex(sha256.digest(spki)));
        System.out.println(side + "_PEER_CERT_DER_BYTES=" + peer.getEncoded().length);
        System.out.println(side + "_PEER_CERT_SUBJECT=" + peer.getSubjectX500Principal());
        System.out.println(side + "_PEER_CERT_SIGALG=" + peer.getSigAlgName());
        try {
            peer.verify(peer.getPublicKey());
            System.out.println(side + "_PEER_SELFSIG_VERIFIES=true");
        } catch (Exception e) {
            System.out.println(side + "_PEER_SELFSIG_VERIFIES=false (" + e + ")");
        }
        try {
            peer.checkValidity();
            System.out.println(side + "_PEER_VALIDITY_OK=true");
        } catch (Exception e) {
            System.out.println(side + "_PEER_VALIDITY_OK=false (" + e + ")");
        }
        System.out.println(side + "_OWN_SPKI_SHA256=" + hex(sha256.digest(own.getPublicKey().getEncoded())));
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        Provider provider = Conscrypt.newProvider();
        System.out.println("PROVIDER=" + provider.getName() + " " + provider.getVersionStr());
        String mode = args.length > 0 ? args[0] : "pair";

        if ("server".equals(mode)) {
            runServerOnly(provider, Integer.parseInt(args[1]));
        } else {
            runPair(provider);
        }
    }

    /** Cross-stack experiment: this process is the TLS server, an Apple client connects to it. */
    private static void runServerOnly(Provider provider, int port) throws Exception {
        SSLContext ctx = context(provider, "server.p12", "spike".toCharArray());
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory()
                .createServerSocket(port, 1, InetAddress.getLoopbackAddress());
        ss.setEnabledProtocols(new String[]{"TLSv1.3"});
        ss.setNeedClientAuth(true);
        System.out.println("JVM_SERVER_READY port=" + ss.getLocalPort());
        System.out.flush();
        try (SSLSocket s = (SSLSocket) ss.accept()) {
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.startHandshake();
            report("JVM_SERVER", s);
            Thread.sleep(500); // let the Apple client finish its own export before FIN
        } finally {
            ss.close();
        }
    }

    /** Both endpoints in one process: settles the null-vs-empty-context question. */
    private static void runPair(Provider provider) throws Exception {
        SSLContext serverCtx = context(provider, "server.p12", "spike".toCharArray());
        SSLContext clientCtx = context(provider, "client.p12", "spike".toCharArray());

        SSLServerSocket ss = (SSLServerSocket) serverCtx.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ss.setEnabledProtocols(new String[]{"TLSv1.3"});
        ss.setNeedClientAuth(true);
        int port = ss.getLocalPort();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> server = pool.submit(() -> {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.setEnabledProtocols(new String[]{"TLSv1.3"});
                s.startHandshake();
                report("JVM_SERVER", s);
                s.getOutputStream().write(1);
                s.getOutputStream().flush();
                s.getInputStream().read();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        Future<?> client = pool.submit(() -> {
            try (SSLSocket s = (SSLSocket) clientCtx.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                s.setEnabledProtocols(new String[]{"TLSv1.3"});
                s.startHandshake();
                report("JVM_CLIENT", s);
                s.getInputStream().read();
                s.getOutputStream().write(1);
                s.getOutputStream().flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        server.get(60, TimeUnit.SECONDS);
        client.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        ss.close();
    }
}
