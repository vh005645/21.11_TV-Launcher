package vn.herotran.launcher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Client Firebase Realtime Database qua REST (không cần SDK, không cần Gradle). */
public final class FirebaseRest {
    public interface StreamListener {
        /** Gọi cho mỗi sự kiện put/patch. path/data là JSON thô. */
        void onEvent(String eventName, String dataJson);
    }

    private final String base;
    private volatile HttpURLConnection streamConn;

    public FirebaseRest(String base) {
        this.base = base;
    }

    private HttpURLConnection open(String path, String method, boolean sse) throws IOException {
        URL u = new URL(base + "/" + path + ".json");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(sse ? 75000 : 30000);
        c.setUseCaches(false);
        if (sse) {
            c.setRequestProperty("Accept", "text/event-stream");
        }
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toString("UTF-8");
    }

    private String request(String method, String path, String body) throws IOException {
        HttpURLConnection c = open(path, method, false);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream os = c.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                return readAll(c.getInputStream());
            }
            String err = readAll(c.getErrorStream());
            throw new IOException("Firebase HTTP " + code + " " + err);
        } finally {
            c.disconnect();
        }
    }

    /** Trả về JSON thô ("null" nếu chưa có dữ liệu). */
    public String get(String path) throws IOException {
        return request("GET", path, null);
    }

    public void put(String path, String json) throws IOException {
        request("PUT", path, json);
    }

    /** Nghe thay đổi (Server-Sent Events). Chặn cho tới khi mất kết nối hoặc bị close(). */
    public void stream(String path, StreamListener l) throws IOException {
        HttpURLConnection c = open(path, "GET", true);
        streamConn = c;
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Firebase stream HTTP " + code);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"), 65536);
            String line;
            String event = null;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if ("put".equals(event) || "patch".equals(event)) {
                        l.onEvent(event, line.substring(5).trim());
                    } else if ("cancel".equals(event) || "auth_revoked".equals(event)) {
                        throw new IOException("Firebase stream " + event);
                    }
                }
            }
        } finally {
            streamConn = null;
            c.disconnect();
        }
    }

    /** Ngắt luồng đang nghe để vòng lặp kết nối lại. */
    public void closeStream() {
        HttpURLConnection c = streamConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }
}
