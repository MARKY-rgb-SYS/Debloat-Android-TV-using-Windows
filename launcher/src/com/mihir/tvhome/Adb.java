package com.mihir.tvhome;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client for the TV's own adbd on 127.0.0.1, so the launcher can run shell commands
 * (am force-stop) that a normal app isn't allowed to. The first connection makes the TV show
 * "Allow USB debugging?" for this app's key; after that it connects silently.
 */
final class Adb implements AutoCloseable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    // ASN.1 DigestInfo prefix for SHA-1: adbd hands us a 20-byte token to sign as if it were a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Listener { void waitingForApproval(); }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    static Adb connect(Context ctx, int port, Listener listener) throws Exception {
        KeyPair keys = keys(ctx);
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        s.setSoTimeout(10_000);
        Adb adb = new Adb(s);
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSignature = false;
            while (true) {
                Msg m = adb.read();
                if (m.cmd == CNXN) return adb;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply");
                if (!sentSignature) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    sentSignature = true;
                } else {
                    // Key not known yet: offer it, and give the person time to press Allow on the TV.
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyString((RSAPublicKey) keys.getPublic()));
                    if (listener != null) listener.waitingForApproval();
                    s.setSoTimeout(60_000);
                    Msg ok = adb.read();
                    s.setSoTimeout(10_000);
                    if (ok.cmd == CNXN) return adb;
                    throw new IOException("not allowed on the TV");
                }
            }
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    /** Run one shell command and return its output. */
    String shell(String cmd) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == OKAY) remote = m.arg0;
            else if (m.cmd == WRTE) {
                buf.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.cmd == CLSE) {
                if (remote != 0) send(CLSE, local, remote, new byte[0]);
                return buf.toString("UTF-8");
            }
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- wire format

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- keys

    /** The app's own 2048-bit RSA key, created on first use and kept in private app storage. */
    private static KeyPair keys(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }

    /** PKCS#1 v1.5 signature of the token, padded by hand so the token is used as the digest as-is. */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] em = new byte[256];
        int t = SHA1_PREFIX.length + token.length;
        em[0] = 0;
        em[1] = 1;
        for (int i = 2; i < em.length - t - 1; i++) em[i] = (byte) 0xFF;
        em[em.length - t - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, em, em.length - t, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, em.length - token.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(em);
    }

    /** The public key in adbd's format: base64 of the mincrypt RSAPublicKey struct, then a name. */
    private static byte[] publicKeyString(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv);
        b.put(littleEndian(n)).put(littleEndian(rr));
        b.putInt(key.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[256];
        for (int i = 0; i < 256 && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
