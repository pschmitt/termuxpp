package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.terminal.PtyProcess;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxSharedProperties;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

/** Opt-in, mutually authenticated loopback PTY endpoint for adb forward. */
public final class AdbShellService extends Service {

    public static final String ACTION_START = "dev.pschmitt.termux.action.ADB_SHELL_START";
    public static final String ACTION_STOP = "dev.pschmitt.termux.action.ADB_SHELL_STOP";
    public static final String ACTION_AUTHORIZATIONS_CHANGED = "dev.pschmitt.termux.action.ADB_SHELL_AUTHORIZATIONS_CHANGED";
    public static final String PREFS_NAME = "adb_shell";
    public static final String PREF_ENABLED = "enabled";
    public static final String PREF_TOKEN = "token";
    public static final int PORT = 5522;
    private static final String TLS_PREFACE = "TERMUXPP/TLS/1";

    public static final String CHANNEL_ID = "adb_shell";
    private static final int NOTIFICATION_ID = 8022;
    private static final int MAX_PEM_BYTES = 16 * 1024;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String LOG_TAG = "AdbShellService";
    private static final int BIND_ATTEMPTS = 5;
    private volatile boolean stopping;
    /** Identifies the current listener run; threads of an older run must not touch shared state. */
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
    private volatile ServerSocket serverSocket;
    private final java.util.Set<Socket> connections = java.util.Collections.newSetFromMap(
        new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());
    private final java.util.concurrent.atomic.AtomicBoolean enrollmentInProgress =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Set<SSLSocket> tlsClients = java.util.Collections.newSetFromMap(
        new java.util.concurrent.ConcurrentHashMap<SSLSocket, Boolean>());
    private final java.util.concurrent.ConcurrentMap<SSLSocket, String> clientIdentities =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService clients = new java.util.concurrent.ThreadPoolExecutor(
        0, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
    private Thread serverThread;
    /* Only with an SSID allow-list: Android may redact the SSID without a broadcast (permission
     * changes), so re-check now and then. The receiver covers Wi-Fi and location-mode changes. */
    private static final long NETWORK_RECHECK_MS = 60_000;
    private final Handler networkHandler = new Handler(Looper.getMainLooper());
    private final Runnable networkCheck = this::updateNetworkAccess;
    private boolean waitingForNetwork;
    private boolean destroyed;
    private final BroadcastReceiver wifiChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNetworkAccess();
        }
    };

    public static String createAccessToken(android.content.Context context) {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_TOKEN, token).apply();
        return token;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel(this);
        waitingForNetwork = !networkAllowsAccess();
        startForeground(NOTIFICATION_ID, buildNotification());
        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        // Turning location off makes Android withhold the SSID.
        filter.addAction(android.location.LocationManager.MODE_CHANGED_ACTION);
        ContextCompat.registerReceiver(this, wifiChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            disableAccess();
            stopServer();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_ENABLED, false)) {
            stopServer();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_AUTHORIZATIONS_CHANGED.equals(intent.getAction())) {
            closeTlsClients();
        }
        updateNetworkAccess();
        return START_STICKY;
    }

    private void updateNetworkAccess() {
        networkHandler.removeCallbacks(networkCheck);
        if (destroyed || !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_ENABLED, false)) return;

        boolean allowed = networkAllowsAccess();
        if (allowed) {
            ensureServer();
        } else if (!stopping) {
            stopServer();
        }
        boolean waiting = !allowed;
        if (waitingForNetwork != waiting) {
            waitingForNetwork = waiting;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        }
        // Permission changes may redact the SSID without a broadcast. Without an allow-list
        // access does not depend on the network, so there is nothing to re-check.
        if (!allowedSsids().trim().isEmpty()) networkHandler.postDelayed(networkCheck, NETWORK_RECHECK_MS);
    }

    /** The configured SSID allow-list, empty for any network. */
    @NonNull
    private static String allowedSsids() {
        TermuxSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties == null) return "";
        return properties.getPropertyValue(TermuxPropertyConstants.KEY_ADB_SHELL_ALLOWED_SSIDS, "", true);
    }

    private boolean networkAllowsAccess() {
        TermuxSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties == null) return false;
        String configured = allowedSsids();
        if (configured.trim().isEmpty()) return true;
        try {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi == null || !wifi.isWifiEnabled()) return false;
            WifiInfo current = wifi.getConnectionInfo();
            if (current == null || current.getNetworkId() < 0
                || current.getSupplicantState() != SupplicantState.COMPLETED) return false;
            return permitsSsid(configured, current.getSSID());
        } catch (SecurityException e) {
            return false;
        }
    }

    /** The connected network's name, or null when not on Wi-Fi or Android withholds it. */
    @Nullable
    public static String currentSsid(Context context) {
        try {
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wifi == null || !wifi.isWifiEnabled()) return null;
            WifiInfo current = wifi.getConnectionInfo();
            if (current == null || current.getNetworkId() < 0
                || current.getSupplicantState() != SupplicantState.COMPLETED) return null;
            String reported = current.getSSID();
            if (reported == null || reported.length() < 2 || WifiManager.UNKNOWN_SSID.equals(reported)
                || reported.charAt(0) != '"' || reported.charAt(reported.length() - 1) != '"') return null;
            return reported.substring(1, reported.length() - 1);
        } catch (SecurityException e) {
            return null;
        }
    }

    public static boolean permitsSsid(String configured, @Nullable String reported) {
        if (configured.trim().isEmpty()) return true;
        if (reported == null || reported.isEmpty() || WifiManager.UNKNOWN_SSID.equals(reported)) return false;

        // Android wraps a UTF-8 SSID in double quotes and may return a hex string otherwise.
        if (reported.length() < 2 || reported.charAt(0) != '"' || reported.charAt(reported.length() - 1) != '"')
            return false;
        String name = reported.substring(1, reported.length() - 1);
        for (String allowed : configured.split(",")) {
            if (!allowed.trim().isEmpty() && name.equals(allowed.trim())) return true;
        }
        return false;
    }

    private synchronized void ensureServer() {
        if (!stopping && serverThread != null && serverThread.isAlive()) return;
        stopServer();
        stopping = false;
        final int run = generation.incrementAndGet();
        serverThread = new Thread(() -> serve(run), "TermuxAdbShell");
        serverThread.start();
    }

    private boolean isCurrent(int run) {
        return !stopping && generation.get() == run;
    }

    private synchronized void failServer(int run, Exception error) {
        if (!isCurrent(run)) return;
        Logger.logError(LOG_TAG, "Unable to listen for the ADB shell: " +
            error.getClass().getSimpleName());
        stopServer();
        stopSelf();
    }

    private ServerSocket bindServer(int run) throws IOException {
        if (!networkAllowsAccess()) return null;
        IOException last = null;
        for (int attempt = 0; attempt < BIND_ATTEMPTS && isCurrent(run); attempt++) {
            try {
                return new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
            } catch (IOException error) {
                last = error;
                sleepBriefly();
            }
        }
        if (last != null) throw last;
        return null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        networkHandler.removeCallbacks(networkCheck);
        unregisterReceiver(wifiChanges);
        stopServer();
        clients.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    private void serve(int run) {
        ServerSocket listener = null;
        try {
            listener = bindServer(run);
            synchronized (this) {
                if (listener == null || !isCurrent(run)) return;
                serverSocket = listener;
            }
            while (isCurrent(run)) {
                Socket socket = listener.accept();
                synchronized (this) {
                    if (!isCurrent(run)) {
                        closeQuietly(socket);
                        break;
                    }
                    if (!networkAllowsAccess()) {
                        closeQuietly(socket);
                        networkHandler.post(networkCheck);
                        continue;
                    }
                    connections.add(socket);
                }
                try {
                    clients.execute(() -> handleConnection(socket, run));
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    connections.remove(socket);
                    closeQuietly(socket);
                }
            }
        } catch (IOException | RuntimeException error) {
            failServer(run, error);
        } finally {
            closeQuietly(listener);
            synchronized (this) {
                if (serverSocket == listener) serverSocket = null;
            }
        }
    }

    private void handleConnection(Socket socket, int run) {
        try {
            if (!isCurrent(run) || !networkAllowsAccess()) return;
            socket.setSoTimeout(10_000);
            byte[] line = readLine(socket.getInputStream(), 512);
            if (line == null) return;
            String preface = new String(line, StandardCharsets.US_ASCII);
            if (TLS_PREFACE.equals(preface)) {
                if (!hasTlsMaterial()) return;
                try (SSLSocket tls = wrapTls(socket)) {
                    synchronized (this) {
                        if (!isCurrent(run)) return;
                        tlsClients.add(tls);
                    }
                    handleTlsClient(tls);
                }
            } else if (preface.startsWith("TERMUXPP/BOOT/3 PROVISION ")
                    && enrollmentInProgress.compareAndSet(false, true)) {
                try {
                    if (isCurrent(run)) handleBootstrap(socket, preface, run);
                } finally {
                    enrollmentInProgress.set(false);
                }
            }
        } catch (Exception ignored) {
            // Never log credentials or peer-controlled input.
        } finally {
            connections.remove(socket);
            closeQuietly(socket);
        }
    }

    private void handleBootstrap(Socket socket, String preface, int run) throws Exception {
        String[] fields = preface.split(" ", 3);
        if (fields.length != 3 || !"TERMUXPP/BOOT/3".equals(fields[0]) ||
            !"PROVISION".equals(fields[1])) return;
        byte[] clientNonce = Base64.decode(fields[2], Base64.NO_WRAP);
        if (clientNonce.length != 32) return;
        String token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_TOKEN, null);
        if (token == null || token.isEmpty()) return;
        byte[] publicKey = getProvisioningPublicKey();
        byte[] serverNonce = new byte[32];
        SECURE_RANDOM.nextBytes(serverNonce);
        byte[] existingCertificate = hasTlsMaterial()
            ? readFileBytes(getTlsFile("adb-shell-server.crt")) : new byte[]{0};
        byte[] baseTranscript = bootstrapTranscript(fields[1], clientNonce, serverNonce, publicKey);
        ByteArrayOutputStream boundTranscript = new ByteArrayOutputStream();
        boundTranscript.write(baseTranscript);
        boundTranscript.write(MessageDigest.getInstance("SHA-256").digest(existingCertificate));
        byte[] transcript = boundTranscript.toByteArray();
        writeFrame(socket, publicKey);
        writeFrame(socket, serverNonce);
        writeFrame(socket, existingCertificate);
        writeFrame(socket, bootstrapMac(token, "server", transcript));
        byte[] proof = readFrame(socket.getInputStream());
        if (proof == null || !MessageDigest.isEqual(proof, bootstrapMac(token, "client", transcript))) return;

        if ("PROVISION".equals(fields[1])) {
            byte[] encryptedServerKey = readFrame(socket.getInputStream());
            byte[] serverCert = readFrame(socket.getInputStream());
            byte[] clientCa = readFrame(socket.getInputStream());
            byte[] clientCert = readFrame(socket.getInputStream());
            byte[] clientName = readFrame(socket.getInputStream());
            byte[] materialProof = readFrame(socket.getInputStream());
            if (encryptedServerKey == null || serverCert == null || clientCa == null || clientCert == null ||
                clientName == null || materialProof == null) return;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] material : new byte[][]{encryptedServerKey, serverCert, clientCa, clientCert, clientName}) {
                digest.update(new byte[]{(byte)(material.length >>> 24), (byte)(material.length >>> 16),
                    (byte)(material.length >>> 8), (byte)material.length});
                digest.update(material);
            }
            ByteArrayOutputStream authenticatedMaterial = new ByteArrayOutputStream();
            authenticatedMaterial.write(transcript);
            authenticatedMaterial.write(digest.digest());
            if (!MessageDigest.isEqual(materialProof, bootstrapMac(token, "material", authenticatedMaterial.toByteArray()))) return;
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(clientCert));
            validateClientCertificate(certificate);
            String name = new String(clientName, StandardCharsets.UTF_8);
            AdbShellAuthorizations.validateName(name);
            if (!isCurrent(run) || !AdbShellApprovalActivity.request(this, certificate, name) || !isCurrent(run) ||
                !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_ENABLED, false) ||
                !isValidToken(token)) {
                writeLine(socket, "ERR_APPROVAL");
                return;
            }
            synchronized (AdbShellService.class) {
                if (!isCurrent(run) || !isValidToken(token) ||
                    !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_ENABLED, false)) return;
                if (!hasTlsMaterial()) {
                    byte[] serverKey = decryptProvisionedServerKey(encryptedServerKey);
                    if (serverKey == null || !storeTlsMaterial(serverKey, serverCert, clientCa)) {
                        writeLine(socket, "ERR_CERT");
                        return;
                    }
                }
                if (!new AdbShellAuthorizations(this).authorize(certificate, name)) {
                    writeLine(socket, "ERR");
                    return;
                }
            }
            writeLine(socket, "OK");
            return;
        }

        writeLine(socket, "ERR");
    }

    private byte[] bootstrapTranscript(String operation, byte[] clientNonce, byte[] serverNonce, byte[] publicKey) throws Exception {
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        transcript.write(("TERMUXPP/BOOT/3\0" + operation + "\0").getBytes(StandardCharsets.US_ASCII));
        transcript.write(clientNonce);
        transcript.write(serverNonce);
        transcript.write(MessageDigest.getInstance("SHA-256").digest(publicKey));
        return transcript.toByteArray();
    }

    private byte[] bootstrapMac(String token, String role, byte[] transcript) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        mac.update((role + "\0").getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(transcript);
    }

    private void handleTlsClient(SSLSocket socket) {
        try {
            if (stopping) return;
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            validateClientIdentity(socket);
            clientIdentities.put(socket, AdbShellAuthorizations.fingerprint(
                (X509Certificate) socket.getSession().getPeerCertificates()[0]));
            if (!authenticate(socket)) return;
            byte[] command = readLine(socket.getInputStream(), 4096);
            if (command == null) return;
            String operation = new String(command, StandardCharsets.US_ASCII);
            validateClientIdentity(socket);
            if ("LIST".equals(operation)) {
                org.json.JSONArray result = new org.json.JSONArray();
                for (AdbShellAuthorizations.Entry entry : new AdbShellAuthorizations(this).list()) {
                    result.put(new org.json.JSONObject().put("name", entry.name).put("fingerprint", entry.fingerprint));
                }
                writeFrame(socket, result.toString().getBytes(StandardCharsets.UTF_8));
            } else if (operation.startsWith("REVOKE ")) {
                String fingerprint = operation.substring(7);
                boolean saved = new AdbShellAuthorizations(this).revoke(fingerprint);
                writeLine(socket, saved ? "OK" : "ERR");
                if (saved) {
                    for (Map.Entry<SSLSocket, String> active : clientIdentities.entrySet()) {
                        if (active.getKey() != socket && fingerprint.equals(active.getValue())) {
                            closeQuietly(active.getKey());
                        }
                    }
                }
            } else if ("SHELL".equals(operation) || operation.startsWith("SHELL ")) {
                String shell;
                try {
                    shell = AdbShellCommand.resolveShell(new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                        new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
                        "SHELL".equals(operation) ? null : operation.substring(6));
                } catch (IOException error) {
                    writeLine(socket, "ERR");
                    return;
                }
                writeLine(socket, "OK");
                socket.setSoTimeout(0);
                runShell(socket, shell);
            } else if ("EXEC".equals(operation)) {
                handleExec(socket);
            }
        } catch (Exception ignored) {
            // Never log credentials or peer-controlled input.
        } finally {
            clientIdentities.remove(socket);
            tlsClients.remove(socket);
            closeQuietly(socket);
        }
    }

    /** Authenticated EXEC: request is fully validated before anything is spawned or acknowledged. */
    private void handleExec(SSLSocket socket) throws Exception {
        AdbShellExec.Request request;
        String shell;
        try {
            request = AdbShellExec.parseRequest(AdbShellExec.readRequestFrame(socket.getInputStream()));
            shell = AdbShellCommand.resolveShell(new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), request.shell);
        } catch (IOException invalid) {
            writeLine(socket, "ERR");
            return;
        }
        // Revocation may have happened while the request was read.
        validateClientIdentity(socket);

        Process process;
        try {
            process = AdbShellExec.start(AdbShellExec.buildCommand(shell, request.argv),
                new File(TermuxConstants.TERMUX_HOME_DIR_PATH), shellEnvironment(shell));
        } catch (IOException error) {
            writeLine(socket, "ERR");
            return;
        }
        try {
            writeLine(socket, "OK");
            socket.setSoTimeout(0);
        } catch (IOException | RuntimeException error) {
            process.destroy();
            throw error;
        }
        AdbShellExec.stream(process, socket.getInputStream(), socket.getOutputStream());
    }

    private Map<String, String> shellEnvironment(String shell) {
        Map<String, String> environment = new TermuxShellEnvironment().getEnvironment(this, false);
        environment.put("SHELL", shell);
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        return environment;
    }

    private void closeTlsClients() {
        for (SSLSocket client : tlsClients) closeQuietly(client);
    }

    private byte[] getProvisioningPublicKey() throws Exception {
        File privateKeyFile = getTlsFile("adb-shell-provisioning.key");
        File publicKeyFile = getTlsFile("adb-shell-provisioning.pub");
        if (!privateKeyFile.isFile() || !publicKeyFile.isFile()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, SECURE_RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            writePrivateFile("adb-shell-provisioning.key", keyPair.getPrivate().getEncoded());
            String encoded = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
            String publicPem = "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
            writePrivateFile("adb-shell-provisioning.pub", publicPem.getBytes(StandardCharsets.US_ASCII));
        }
        return new String(readFileBytes(publicKeyFile), StandardCharsets.US_ASCII)
            .replace("\\n", "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] decryptProvisionedServerKey(byte[] encryptedKey) {
        try {
            byte[] privateKeyBytes = readFileBytes(getTlsFile("adb-shell-provisioning.key"));
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec parameters = new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, parameters);
            return cipher.doFinal(encryptedKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private SSLSocket wrapTls(Socket socket) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate serverCertificate;
        X509Certificate clientCaCertificate;
        try (FileInputStream input = new FileInputStream(getTlsFile("adb-shell-server.crt"))) {
            serverCertificate = (X509Certificate) certificateFactory.generateCertificate(input);
        }
        try (FileInputStream input = new FileInputStream(getTlsFile("adb-shell-client-ca.crt"))) {
            clientCaCertificate = (X509Certificate) certificateFactory.generateCertificate(input);
        }
        serverCertificate.checkValidity();
        clientCaCertificate.checkValidity();
        if (!isServerCertificate(serverCertificate) || !isClientCaCertificate(clientCaCertificate)) {
            throw new IOException("Invalid mTLS certificates");
        }

        byte[] privateKeyBytes = readFileBytes(getTlsFile("adb-shell-server.pk8"));
        PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("adb-shell-server", privateKey, new char[0], new Certificate[]{serverCertificate});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, new char[0]);

        javax.net.ssl.X509TrustManager clients = new javax.net.ssl.X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Client-only trust manager");
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new IOException("Missing client certificate");
                    validateClientCertificate(chain[0]);
                    // Exact leaf pins, not CA membership, are the authorization boundary.
                    if (!new AdbShellAuthorizations(AdbShellService.this).contains(chain[0])) {
                        throw new IOException("Client not authorized");
                    }
                } catch (Exception error) {
                    throw new java.security.cert.CertificateException("Client not authorized", error);
                }
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), new javax.net.ssl.TrustManager[]{clients}, SECURE_RANDOM);
        SSLSocket tls = (SSLSocket) sslContext.getSocketFactory()
            .createSocket(socket, "127.0.0.1", socket.getPort(), true);
        try {
            tls.setUseClientMode(false);
            tls.setNeedClientAuth(true);
            List<String> protocols = new ArrayList<>();
            for (String protocol : tls.getSupportedProtocols()) {
                if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
            }
            if (protocols.isEmpty()) throw new IOException("No supported TLS 1.2+ protocol");
            tls.setEnabledProtocols(protocols.toArray(new String[0]));
            return tls;
        } catch (IOException | RuntimeException error) {
            closeQuietly(tls);
            throw error;
        }
    }

    private void validateClientIdentity(SSLSocket socket) throws Exception {
        Certificate[] chain = socket.getSession().getPeerCertificates();
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            throw new SSLPeerUnverifiedException("Missing client certificate");
        }
        X509Certificate clientCertificate = (X509Certificate) chain[0];
        validateClientCertificate(clientCertificate);
        if (!new AdbShellAuthorizations(this).contains(clientCertificate)) {
            throw new SSLPeerUnverifiedException("Client not authorized");
        }
    }

    private void validateClientCertificate(X509Certificate clientCertificate) throws Exception {
        clientCertificate.checkValidity();
        java.security.PublicKey key = clientCertificate.getPublicKey();
        boolean strongKey = (key instanceof java.security.interfaces.RSAPublicKey &&
            ((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() >= 2048) ||
            (key instanceof java.security.interfaces.ECPublicKey &&
            ((java.security.interfaces.ECPublicKey) key).getParams().getOrder().bitLength() >= 256);
        if (!strongKey) throw new SSLPeerUnverifiedException("Unsupported client public key");
        boolean[] usage = clientCertificate.getKeyUsage();
        if (clientCertificate.getBasicConstraints() >= 0 || usage == null || !usage[0]) {
            throw new SSLPeerUnverifiedException("Invalid client certificate usage");
        }
        List<String> extendedKeyUsage = clientCertificate.getExtendedKeyUsage();
        if (extendedKeyUsage == null || !extendedKeyUsage.contains("1.3.6.1.5.5.7.3.2")) {
            throw new SSLPeerUnverifiedException("Client certificate lacks clientAuth usage");
        }
    }

    private boolean storeTlsMaterial(byte[] serverKey, byte[] serverCert, byte[] clientCa) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate serverCertificate = (X509Certificate) certificateFactory.generateCertificate(
                new java.io.ByteArrayInputStream(serverCert));
            X509Certificate clientCaCertificate = (X509Certificate) certificateFactory.generateCertificate(
                new java.io.ByteArrayInputStream(clientCa));
            serverCertificate.checkValidity();
            clientCaCertificate.checkValidity();
            if (!isServerCertificate(serverCertificate) || !isClientCaCertificate(clientCaCertificate)) return false;
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(serverKey));
            byte[] challenge = new byte[32];
            SECURE_RANDOM.nextBytes(challenge);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(challenge);
            byte[] signature = signer.sign();
            signer.initVerify(serverCertificate.getPublicKey());
            signer.update(challenge);
            if (!signer.verify(signature)) return false;

            writePrivateFile("adb-shell-server.pk8", serverKey);
            writePrivateFile("adb-shell-server.crt", serverCert);
            writePrivateFile("adb-shell-client-ca.crt", clientCa);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean authenticate(Socket socket) throws IOException {
        byte[] line = readLine(socket.getInputStream(), 512);
        if (line == null) return false;
        String supplied = new String(line, StandardCharsets.US_ASCII);
        String tokenPrefix = "TERMUXPP/1 ";
        if (!"TERMUXPP/2".equals(supplied)
            && (!supplied.startsWith(tokenPrefix) || !isValidToken(supplied.substring(tokenPrefix.length())))) {
            writeLine(socket, "ERR");
            return false;
        }
        writeLine(socket, "OK");
        return true;
    }

    private boolean isValidToken(String supplied) {
        String storedToken = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_TOKEN, null);
        if (storedToken == null) return false;
        byte[] expected = storedToken.getBytes(StandardCharsets.US_ASCII);
        byte[] provided = supplied.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] readFrame(InputStream input) throws IOException {
        DataInputStream dataInput = new DataInputStream(input);
        int length;
        try {
            length = dataInput.readInt();
        } catch (IOException ignored) {
            return null;
        }
        if (length < 1 || length > MAX_PEM_BYTES) return null;
        byte[] contents = new byte[length];
        dataInput.readFully(contents);
        return contents;
    }

    private byte[] readLine(InputStream input, int maxLength) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int count = 0; count <= maxLength; count++) {
            int value = input.read();
            if (value < 0) return null;
            if (value == '\n') return line.toByteArray();
            if (value != '\r') line.write(value);
        }
        return null;
    }

    private void writeLine(Socket socket, String text) throws IOException {
        socket.getOutputStream().write((text + "\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream contents = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) contents.write(buffer, 0, count);
            return contents.toByteArray();
        }
    }

    private void writeFrame(Socket socket, byte[] contents) throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(socket.getOutputStream());
        dataOutput.writeInt(contents.length);
        dataOutput.write(contents);
        dataOutput.flush();
    }

    private boolean isServerCertificate(X509Certificate certificate) throws Exception {
        List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
        if (extendedKeyUsage == null || !extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1")) return false;
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) return false;
        for (List<?> name : names) {
            if (name.size() >= 2 && Integer.valueOf(7).equals(name.get(0)) && "127.0.0.1".equals(name.get(1))) {
                return true;
            }
        }
        return false;
    }

    private boolean isClientCaCertificate(X509Certificate certificate) throws Exception {
        if (certificate.getBasicConstraints() < 0) return false;
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage == null || keyUsage.length <= 5 || !keyUsage[5]) return false;
        certificate.verify(certificate.getPublicKey());
        return true;
    }

    private boolean hasTlsMaterial() {
        return getTlsFile("adb-shell-server.pk8").isFile() && getTlsFile("adb-shell-server.crt").isFile() &&
            getTlsFile("adb-shell-client-ca.crt").isFile();
    }

    private File getTlsDirectory() {
        File directory = new File(getFilesDir(), "adb-shell");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create ADB shell directory");
        return directory;
    }

    private File getTlsFile(String name) {
        return new File(getTlsDirectory(), name);
    }

    private void writePrivateFile(String name, byte[] contents) throws IOException {
        File target = getTlsFile(name);
        File temporary = File.createTempFile("adb-shell-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(contents);
                output.getFD().sync();
            }
            temporary.setReadable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(false, false);
            temporary.setWritable(true, true);
            if (!temporary.renameTo(target)) throw new IOException("Could not replace TLS material");
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private void deleteTlsFile(String name) {
        File file = getTlsFile(name);
        if (file.exists()) file.delete();
    }

    private void disableAccess() {
        synchronized (AdbShellService.class) {
            new AdbShellAuthorizations(this).clear();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false).remove(PREF_TOKEN).apply();
            deleteTlsFile("adb-shell-server.pk8");
            deleteTlsFile("adb-shell-server.crt");
            deleteTlsFile("adb-shell-client-ca.crt");
            deleteTlsFile("adb-shell-provisioning.key");
            deleteTlsFile("adb-shell-provisioning.pub");
        }
    }

    private void runShell(Socket socket, String shell) throws IOException {
        Map<String, String> environment = shellEnvironment(shell);
        List<String> environmentEntries = new ArrayList<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            environmentEntries.add(entry.getKey() + "=" + entry.getValue());
        }
        Collections.sort(environmentEntries);

        String name = new File(shell).getName();
        PtyProcess pty = PtyProcess.start(shell, TermuxConstants.TERMUX_HOME_DIR_PATH,
            new String[]{"-" + name}, environmentEntries.toArray(new String[0]), 24, 80);

        BufferedInputStream clientInput = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream clientOutput = new BufferedOutputStream(socket.getOutputStream());
        Thread toPty = new Thread(() -> copy(clientInput, pty), "TermuxAdbShellInput");
        Thread fromPty = new Thread(() -> copy(pty, clientOutput), "TermuxAdbShellOutput");
        toPty.start();
        fromPty.start();

        try {
            pty.waitFor();
            toPty.interrupt();
            fromPty.interrupt();
            socket.close();
        } finally {
            pty.close();
        }
    }

    private void copy(BufferedInputStream input, PtyProcess pty) {
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                pty.getOutputStream().write(buffer, 0, count);
                pty.getOutputStream().flush();
            }
        } catch (IOException ignored) {
        } finally {
            terminatePty(pty);
        }
    }

    private void copy(PtyProcess pty, BufferedOutputStream output) {
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = pty.getInputStream().read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
            terminatePty(pty);
        }
    }

    private void terminatePty(PtyProcess pty) {
        // Interactive shells may ignore SIGTERM; a revoked connection must not retain its PTY.
        android.os.Process.killProcess(pty.getProcessId());
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void stopServer() {
        stopping = true;
        generation.incrementAndGet();
        AdbShellApprovalActivity.cancelPending();
        closeQuietly(serverSocket);
        for (Socket socket : connections) closeQuietly(socket);
        closeTlsClients();
    }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.adb_shell_notification_channel), NotificationManager.IMPORTANCE_LOW);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, AdbShellService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(getString(R.string.adb_shell_notification_title))
            .setContentText(waitingForNetwork
                ? getString(R.string.adb_shell_waiting_for_network)
                : getString(R.string.adb_shell_notification_text, PORT))
            .setOngoing(true)
            .addAction(R.drawable.ic_service_notification, getString(R.string.adb_shell_stop), stopPendingIntent)
            .build();
    }
}
