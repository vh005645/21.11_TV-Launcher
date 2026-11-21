package vn.herotran.launcher;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;

/**
 * Client ADB tối giản (giao thức ADB over TCP, xác thực RSA) - không cần thư viện ngoài.
 * Khóa RSA lưu trong bộ nhớ trong của app nên chỉ phải bấm "Always allow" một lần.
 */
public class AdbClient {
    private static final String TAG = "AdbClient";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000001;
    private static final int MAXDATA = 256 * 1024;

    // DigestInfo của SHA-1 (ADB ký thẳng lên token 20 byte như thể đã băm sẵn)
    private static final byte[] SHA1_PREFIX = new byte[] {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static final class Msg {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }

    private final Context ctx;
    private final int port;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private volatile boolean connected;
    private int nextLocalId = 1;

    public AdbClient(Context context, int port) {
        this.ctx = context.getApplicationContext();
        this.port = port;
    }

    public boolean isConnected() {
        return connected;
    }

    // ------------------------------------------------------------------ khóa RSA

    private void loadOrCreateKeys() throws Exception {
        if (privateKey != null && publicKey != null) {
            return;
        }
        File priv = new File(ctx.getFilesDir(), "adbkey.priv");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (priv.exists() && pub.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(readFile(priv)));
                publicKey = kf.generatePublic(new X509EncodedKeySpec(readFile(pub)));
                return;
            } catch (Exception e) {
                Log.w(TAG, "Khóa cũ hỏng, tạo khóa mới", e);
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        privateKey = kp.getPrivate();
        publicKey = kp.getPublic();
        writeFile(priv, privateKey.getEncoded());
        writeFile(pub, publicKey.getEncoded());
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    /** Khóa công khai theo định dạng Android (struct RSAPublicKey 2048-bit) + base64 + tên. */
    private byte[] publicKeyForAdb() {
        RSAPublicKey pk = (RSAPublicKey) publicKey;
        BigInteger n = pk.getModulus();
        final int words = 64;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);

        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger inv = n.mod(r32).modInverse(r32);
        b.putInt(r32.subtract(inv).intValue()); // n0inv = -1/n[0] mod 2^32

        for (int i = 0; i < words; i++) {
            b.putInt(n.shiftRight(32 * i).intValue());
        }
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n); // R^2 mod n, R = 2^2048
        for (int i = 0; i < words; i++) {
            b.putInt(rr.shiftRight(32 * i).intValue());
        }
        b.putInt(pk.getPublicExponent().intValue());

        String b64 = Base64.encodeToString(b.array(), Base64.NO_WRAP);
        String text = b64 + " tvlauncher@hero\u0000";
        try {
            return text.getBytes("UTF-8");
        } catch (Exception e) {
            return text.getBytes();
        }
    }

    private byte[] signToken(byte[] token) throws Exception {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(privateKey);
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (Exception first) {
            // Dự phòng: tự đệm PKCS#1 v1.5 rồi dùng RSA thô
            byte[] t = new byte[SHA1_PREFIX.length + token.length];
            System.arraycopy(SHA1_PREFIX, 0, t, 0, SHA1_PREFIX.length);
            System.arraycopy(token, 0, t, SHA1_PREFIX.length, token.length);
            byte[] block = new byte[256];
            block[0] = 0x00;
            block[1] = 0x01;
            int padEnd = 256 - t.length - 1;
            for (int i = 2; i < padEnd; i++) {
                block[i] = (byte) 0xff;
            }
            block[padEnd] = 0x00;
            System.arraycopy(t, 0, block, padEnd + 1, t.length);
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, privateKey);
            return c.doFinal(block);
        }
    }

    // ------------------------------------------------------------------ gói tin

    private void writeMsg(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += (data[i] & 0xff);
        }
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(len);
        b.putInt(sum);
        b.putInt(~cmd);
        if (len > 0) {
            b.put(data);
        }
        out.write(b.array());
        out.flush();
    }

    private Msg readMsg() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum - bỏ qua
        int magic = b.getInt();
        if (magic != ~m.cmd) {
            throw new IOException("Gói ADB sai magic");
        }
        if (len < 0 || len > MAXDATA) {
            throw new IOException("Gói ADB quá lớn: " + len);
        }
        m.data = new byte[len];
        if (len > 0) {
            in.readFully(m.data);
        }
        return m;
    }

    // ------------------------------------------------------------------ kết nối

    /**
     * Kết nối tới adbd. authWaitMs: thời gian chờ người dùng bấm "Allow" trên TV
     * (chỉ cần khi khóa chưa được tin cậy).
     */
    public synchronized void connect(int authWaitMs) throws IOException {
        if (connected) {
            return;
        }
        close();
        try {
            loadOrCreateKeys();
        } catch (Exception e) {
            throw new IOException("Không tạo được khóa RSA: " + e.getMessage());
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Cổng " + port + " chưa mở (ADB chưa bật?)");
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(10000);
        socket = s;
        in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 65536));
        out = s.getOutputStream();

        try {
            writeMsg(A_CNXN, VERSION, MAXDATA, "host::\u0000".getBytes("UTF-8"));
            boolean sentSignature = false;
            boolean sentPublicKey = false;
            while (true) {
                Msg m = readMsg();
                if (m.cmd == A_CNXN) {
                    break;
                } else if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                    if (!sentSignature) {
                        byte[] sig;
                        try {
                            sig = signToken(m.data);
                        } catch (Exception e) {
                            throw new IOException("Ký token thất bại: " + e.getMessage());
                        }
                        writeMsg(A_AUTH, AUTH_SIGNATURE, 0, sig);
                        sentSignature = true;
                    } else if (!sentPublicKey) {
                        writeMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyForAdb());
                        sentPublicKey = true;
                        s.setSoTimeout(authWaitMs); // chờ người dùng bấm Allow trên TV
                    } else {
                        throw new IOException("TV từ chối khóa ADB");
                    }
                } else {
                    throw new IOException("Gói ADB không mong đợi: 0x" + Integer.toHexString(m.cmd));
                }
            }
            s.setSoTimeout(15000);
            connected = true;
            Log.i(TAG, "ADB connected");
        } catch (IOException e) {
            close();
            if (e instanceof java.net.SocketTimeoutException) {
                throw new IOException("Hết thời gian chờ - hãy bấm Allow/Always allow trên TV");
            }
            throw e;
        }
    }

    public synchronized void close() {
        connected = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // ------------------------------------------------------------------ chạy lệnh

    /** Chạy lệnh qua dịch vụ shell, trả về output dạng chữ. */
    public synchronized String shell(String cmd, int timeoutMs) throws IOException {
        byte[] r = runService("shell:" + cmd, timeoutMs, 1024 * 1024);
        return new String(r, "UTF-8").trim();
    }

    /** Chạy lệnh qua dịch vụ exec (không qua PTY) - dùng cho dữ liệu nhị phân như screencap. */
    public synchronized byte[] exec(String cmd, int timeoutMs, int maxBytes) throws IOException {
        return runService("exec:" + cmd, timeoutMs, maxBytes);
    }

    private byte[] runService(String service, int timeoutMs, int maxBytes) throws IOException {
        if (!connected || socket == null) {
            throw new IOException("ADB chưa kết nối");
        }
        try {
            socket.setSoTimeout(timeoutMs);
            int localId = nextLocalId++;
            writeMsg(A_OPEN, localId, 0, (service + "\u0000").getBytes("UTF-8"));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int remoteId = 0;
            while (true) {
                Msg m = readMsg();
                if (m.cmd == A_OKAY) {
                    if (m.arg1 == localId) {
                        remoteId = m.arg0;
                    }
                } else if (m.cmd == A_WRTE) {
                    if (m.arg1 != localId) {
                        continue;
                    }
                    remoteId = m.arg0;
                    bos.write(m.data);
                    if (bos.size() > maxBytes) {
                        throw new IOException("Output ADB quá lớn");
                    }
                    writeMsg(A_OKAY, localId, remoteId, null);
                } else if (m.cmd == A_CLSE) {
                    if (m.arg1 == localId) {
                        break;
                    }
                }
            }
            socket.setSoTimeout(15000);
            return bos.toByteArray();
        } catch (IOException e) {
            close();
            throw e;
        }
    }
}
