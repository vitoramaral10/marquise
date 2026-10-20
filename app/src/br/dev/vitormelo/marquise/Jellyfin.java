package br.dev.vitormelo.marquise;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Talking to a Jellyfin server while setting Marquise up: finding it on the network, checking an address, and signing
 * in with Quick Connect (a code approved on the phone) or a user name and password. Blocking calls: run them off the
 * UI thread.
 */
final class Jellyfin {
    private Jellyfin() {}

    static final class Server {
        final String url, name, version, id;

        Server(String url, String name, String version, String id) {
            this.url = url;
            this.name = name;
            this.version = version;
            this.id = id;
        }
    }

    static final class Login {
        final String token, userId, userName;

        Login(String token, String userId, String userName) {
            this.token = token;
            this.userId = userId;
            this.userName = userName;
        }
    }

    interface Found { void onServer(Server s); }

    // MARK: - Finding servers

    /**
     * Servers on this network, each reported as soon as it answers: Jellyfin's own discovery (a UDP broadcast on 7359,
     * which some servers have turned off) and, in parallel, a quick look for its usual port (8096) on every address of
     * the TV's own /24. A server answering on two addresses (two network cards) is listed once, by its id. Returns
     * when both are done (a few seconds).
     */
    static void discover(Found found) {
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        Thread udp = new Thread(() -> broadcast(found, seen), "marquise-discover");
        udp.start();
        scan(found, seen);
        try {
            udp.join(4000);
        } catch (InterruptedException ignored) {
        }
    }

    private static void broadcast(Found found, Set<String> seen) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(2500);
            byte[] ask = "who is JellyfinServer?".getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(ask, ask.length, InetAddress.getByName("255.255.255.255"), 7359));
            byte[] buffer = new byte[2048];
            while (true) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                socket.receive(reply);
                JSONObject o = new JSONObject(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
                String address = o.optString("Address").replaceAll("/+$", "");
                String id = o.optString("Id", address);
                if (!address.isEmpty() && seen.add(id)) found.onServer(new Server(address, o.optString("Name"), "", id));
            }
        } catch (SocketTimeoutException done) {
            // no more answers
        } catch (Exception e) {
            AppLog.w("discovery broadcast: " + e.getMessage());
        }
    }

    private static void scan(Found found, Set<String> seen) {
        byte[] base = lanAddress();
        if (base == null) return;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String ip = (base[0] & 255) + "." + (base[1] & 255) + "." + (base[2] & 255) + "." + i;
            pool.execute(() -> {
                if (!portOpen(ip, 8096)) return;
                String url = "http://" + ip + ":8096";
                try {
                    Server s = info(url);
                    if (seen.add(s.id)) found.onServer(s);
                } catch (IOException ignored) {
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** The TV's private IPv4 address (Wi-Fi or Ethernet), or null. */
    private static byte[] lanAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses()) {
                    InetAddress ip = a.getAddress();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean portOpen(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // MARK: - Checking an address

    /** "192.168.1.10" → "http://192.168.1.10:8096"; keeps a scheme or a port that was typed. */
    static String normalize(String typed) {
        String s = typed.trim().replaceAll("/+$", "");
        if (s.isEmpty()) return s;
        if (!s.matches("^[a-zA-Z]+://.*")) s = "http://" + s;
        String rest = s.replaceFirst("^[a-zA-Z]+://", "");
        if (s.startsWith("http://") && !rest.contains(":") && !rest.contains("/")) s += ":8096";
        return s;
    }

    /** The server's public info (no sign-in needed); throws if it isn't a Jellyfin server. */
    static Server info(String url) throws IOException {
        try {
            JSONObject o = new JSONObject(request(url + "/System/Info/Public", "GET", null, null, null, 2500));
            if (o.optString("Id").isEmpty()) throw new IOException("not Jellyfin");
            return new Server(url, o.optString("ServerName"), o.optString("Version"), o.optString("Id"));
        } catch (org.json.JSONException e) {
            throw new IOException("not Jellyfin");
        }
    }

    // MARK: - Signing in

    static boolean quickConnectEnabled(String url, String deviceId) {
        try {
            return "true".equals(request(url + "/QuickConnect/Enabled", "GET", null, null, deviceId, 4000).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** Starts Quick Connect: {secret, the 6-digit code to show}. */
    static String[] quickConnectStart(String url, String deviceId) throws IOException {
        try {
            JSONObject o = new JSONObject(request(url + "/QuickConnect/Initiate", "POST", null, null, deviceId, 6000));
            return new String[] {o.getString("Secret"), o.getString("Code")};
        } catch (org.json.JSONException e) {
            throw new IOException("Quick Connect: " + e.getMessage());
        }
    }

    /** Whether the code was approved on another device yet. */
    static boolean quickConnectApproved(String url, String secret, String deviceId) throws IOException {
        try {
            String q = "/QuickConnect/Connect?secret=" + java.net.URLEncoder.encode(secret, "UTF-8");
            return new JSONObject(request(url + q, "GET", null, null, deviceId, 6000)).optBoolean("Authenticated");
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    static Login quickConnectLogin(String url, String secret, String deviceId) throws IOException {
        return login(url + "/Users/AuthenticateWithQuickConnect", "{\"Secret\":" + JSONObject.quote(secret) + "}", deviceId);
    }

    static Login passwordLogin(String url, String user, String password, String deviceId) throws IOException {
        String body = "{\"Username\":" + JSONObject.quote(user) + ",\"Pw\":" + JSONObject.quote(password) + "}";
        return login(url + "/Users/AuthenticateByName", body, deviceId);
    }

    private static Login login(String endpoint, String body, String deviceId) throws IOException {
        try {
            JSONObject o = new JSONObject(request(endpoint, "POST", body, null, deviceId, 8000));
            JSONObject user = o.getJSONObject("User");
            return new Login(o.getString("AccessToken"), user.getString("Id"), user.optString("Name"));
        } catch (org.json.JSONException e) {
            throw new IOException("sign-in: " + e.getMessage());
        }
    }

    // MARK: - HTTP

    /** Jellyfin's client header; with a token once signed in. */
    static String authorization(String deviceId, String token) {
        return "MediaBrowser Client=\"Marquise\", Device=\"TV\", DeviceId=\"" + (deviceId == null ? "marquise" : deviceId)
                + "\", Version=\"" + Config.VERSION + "\"" + (token == null || token.isEmpty() ? "" : ", Token=\"" + token + "\"");
    }

    private static String request(String url, String method, String body, String token, String deviceId, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", authorization(deviceId, token));
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } else if (method.equals("POST")) {
            c.setFixedLengthStreamingMode(0);
        }
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(Ui.tr("usuário ou senha incorretos", "wrong user name or password"));
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
