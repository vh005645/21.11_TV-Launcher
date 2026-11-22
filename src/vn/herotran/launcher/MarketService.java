package vn.herotran.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lấy Giá xăng dầu (Petrolimex, Vùng 1) và Giá vàng hôm nay từ webgia.com.
 * Dữ liệu được làm mới mỗi giờ vào đầu giờ (8h00, 9h00, 10h00...). Có dữ liệu mẫu khi hoàn toàn mất mạng.
 */
public final class MarketService {

    private static final String URL_FUEL = "https://webgia.com/gia-xang-dau/petrolimex/";
    private static final String URL_SJC = "https://webgia.com/gia-vang/sjc/";
    private static final String URL_BTMC = "https://webgia.com/gia-vang/bao-tin-minh-chau/";

    private static final String SEP = "[\\s\\u00A0]+";
    private static final String PRICE_FUEL = "(\\d{2}\\.\\d{3})";
    private static final String PRICE_GOLD = "(\\d{1,2}\\.\\d{3}\\.\\d{3})";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?s)<script.*?</script>|<style.*?</style>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s\\u00A0]+");

    // ---- Giá xăng dầu (webgia dùng tên "mức", một số widget dùng tên cũ -III/-II) ----
    private static final Pattern[] E5_PATTERNS = {
            wg("Xăng E5 RON 92 mức 2", PRICE_FUEL),
            wg("Xăng E5 RON 92-II", PRICE_FUEL)
    };
    private static final Pattern[] E10_PATTERNS = {
            wg("Xăng E10 RON 95 mức 3", PRICE_FUEL),
            wg("Xăng E10 RON 95-III", PRICE_FUEL)
    };
    private static final Pattern[] DIESEL_PATTERNS = {
            wg("Dầu DO 0,05S mức 2", PRICE_FUEL),
            wg("Dầu DO 0,05S-II", PRICE_FUEL)
    };

    // ---- Giá vàng (webgia tính đồng/chỉ -> nhân 10 thành đồng/lượng) ----
    private static final Pattern SJC_MIENG = wg("Vàng SJC 1L, 10L, 1KG", PRICE_GOLD + SEP + PRICE_GOLD);
    private static final Pattern NHAN_BTMC = wg("Nhẫn tròn trơn 999.9 (24k)", PRICE_GOLD + SEP + PRICE_GOLD);
    private static final Pattern NHAN_SJC = wg("Vàng nhẫn SJC 99,99% 1 chỉ, 2 chỉ, 5 chỉ", PRICE_GOLD + SEP + PRICE_GOLD);

    // Ô "Giá vàng thế giới $3,524.80" (có trên mọi trang webgia)
    private static final Pattern WORLD_GOLD = Pattern.compile(
            "Giá" + SEP + "vàng" + SEP + "thế" + SEP + "giới[\\s\\u00A0]*\\$[\\s\\u00A0]*([\\d,]+\\.\\d+)", FLAGS);

    // "Cập nhật lúc 07:40:05 22/11/2026"
    private static final Pattern UPDATED_DATE = Pattern.compile(
            "Cập" + SEP + "nhật" + SEP + "lúc" + SEP + "\\d{1,2}:\\d{2}(?::\\d{2})?" + SEP + "(\\d{1,2}/\\d{1,2})/\\d{4}", FLAGS);

    private static final long SAMPLE_RETRY_MS = 2 * 60 * 1000L; // 2 phút nếu đang là dữ liệu mẫu

    private static volatile Market cachedMarket;
    private static volatile long cachedHourKey = -1;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean isFetching = new AtomicBoolean(false);

    public interface Callback {
        void onMarket(Market market);
    }

    public static final class Market {
        // Cột Giá xăng dầu (đồng/lít)
        public String e5;
        public String e10;
        public String diesel;

        // Cột Giá vàng (triệu đồng/lượng & USD/oz)
        public String sjcBuy;
        public String sjcSell;
        public String nhanBuy;
        public String nhanSell;
        public String worldGold;

        // Thông tin chân thẻ chung
        public String date;      // ví dụ "22/11"
        public String source;    // "webgia.com" hoặc "dữ liệu mẫu"
        public boolean sample;   // true nếu đang là dữ liệu mẫu
    }

    private MarketService() {
    }

    /** Khóa theo giờ hiện tại (ngày + giờ) - dữ liệu coi là còn mới trong cùng một giờ. */
    private static long currentHourKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000000L + c.get(Calendar.DAY_OF_YEAR) * 100L + c.get(Calendar.HOUR_OF_DAY);
    }

    /** Gọi từ luồng chính; tải trên luồng nền (nếu cache chưa thuộc giờ hiện tại) và trả kết quả về luồng chính. */
    public static void load(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Market currentCache = cachedMarket;
        final long now = SystemClock.elapsedRealtime();

        if (currentCache != null) {
            boolean fresh;
            if (currentCache.sample) {
                fresh = (now - lastFetchTime) < SAMPLE_RETRY_MS;
            } else {
                fresh = cachedHourKey == currentHourKey();
            }
            if (fresh) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMarket(currentCache);
                    }
                });
                return;
            }
        }

        if (!isFetching.compareAndSet(false, true)) {
            if (currentCache != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMarket(currentCache);
                    }
                });
            }
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Market result;
                boolean real = true;
                try {
                    result = fetch();
                } catch (Exception e) {
                    real = false;
                    result = (cachedMarket != null && !cachedMarket.sample) ? cachedMarket : sample();
                } finally {
                    isFetching.set(false);
                }
                cachedMarket = result;
                lastFetchTime = SystemClock.elapsedRealtime();
                // Lấy được dữ liệu thật: khóa theo giờ; lỗi mạng: thử lại sau 2 phút
                cachedHourKey = (real && !result.sample) ? currentHourKey() : -1;
                if (!real && !result.sample) {
                    // giữ dữ liệu cũ nhưng thử lại sớm
                    cachedHourKey = -1;
                }
                final Market out = result;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMarket(out);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ tải + phân tích

    private static Market fetch() throws Exception {
        String fuelText = fetchText(URL_FUEL);
        String sjcText = fetchText(URL_SJC);
        String btmcText = fetchText(URL_BTMC);

        // Giá xăng dầu: ưu tiên trang Petrolimex, dự phòng bằng ô widget xăng dầu trên các trang vàng
        String e5 = firstOf(E5_PATTERNS, fuelText, sjcText, btmcText);
        String e10 = firstOf(E10_PATTERNS, fuelText, sjcText, btmcText);
        String diesel = firstOf(DIESEL_PATTERNS, fuelText, sjcText, btmcText);

        // Giá vàng
        String[] sjc = goldPair(SJC_MIENG, sjcText);
        String[] nhan = goldPair(NHAN_BTMC, btmcText);
        if (nhan == null) {
            nhan = goldPair(NHAN_SJC, sjcText);
        }
        String world = worldGold(sjcText, fuelText, btmcText);

        boolean gotFuel = e5 != null || e10 != null || diesel != null;
        boolean gotGold = sjc != null || nhan != null;
        if (!gotFuel && !gotGold) {
            throw new IllegalStateException("Không đọc được giá từ webgia.com");
        }

        Market def = (cachedMarket != null && !cachedMarket.sample) ? cachedMarket : sample();
        Market m = new Market();
        m.e5 = e5 != null ? e5 : def.e5;
        m.e10 = e10 != null ? e10 : def.e10;
        m.diesel = diesel != null ? diesel : def.diesel;
        m.sjcBuy = sjc != null ? sjc[0] : def.sjcBuy;
        m.sjcSell = sjc != null ? sjc[1] : def.sjcSell;
        m.nhanBuy = nhan != null ? nhan[0] : def.nhanBuy;
        m.nhanSell = nhan != null ? nhan[1] : def.nhanSell;
        m.worldGold = world != null ? world : def.worldGold;

        String date = firstGroup(UPDATED_DATE, sjcText);
        if (date == null) date = firstGroup(UPDATED_DATE, fuelText);
        if (date == null) date = todayDdMm();
        m.date = date;
        m.source = "webgia.com";
        m.sample = false;
        return m;
    }

    /** Dữ liệu mẫu khi hoàn toàn mất mạng (giá ngày 22/11/2026). */
    public static Market sample() {
        Market m = new Market();
        m.sample = true;
        m.date = "--/--";
        m.source = "dữ liệu mẫu";
        m.e5 = "18.570";
        m.e10 = "19.100";
        m.diesel = "26.340";
        m.sjcBuy = "128,5";
        m.sjcSell = "133,5";
        m.nhanBuy = "128,3";
        m.nhanSell = "133,3";
        m.worldGold = "3.524,8";
        return m;
    }

    // ------------------------------------------------------------------ tiện ích

    /** Tạo regex từ một cụm chữ (khoảng trắng linh hoạt) + phần giá phía sau. */
    private static Pattern wg(String phrase, String tail) {
        String[] words = phrase.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(SEP);
            sb.append(Pattern.quote(words[i]));
        }
        sb.append(SEP).append(tail);
        return Pattern.compile(sb.toString(), FLAGS);
    }

    private static String firstOf(Pattern[] patterns, String... texts) {
        for (String text : texts) {
            if (text == null) continue;
            for (Pattern p : patterns) {
                String v = firstGroup(p, text);
                if (v != null) return v;
            }
        }
        return null;
    }

    /** Đọc cặp giá mua/bán (đồng/chỉ) -> {"128,5", "133,5"} triệu đồng/lượng. */
    private static String[] goldPair(Pattern p, String text) {
        if (text == null) return null;
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        try {
            long buy = Long.parseLong(m.group(1).replace(".", "")) * 10L;
            long sell = Long.parseLong(m.group(2).replace(".", "")) * 10L;
            if (buy <= 0 || sell <= 0) return null;
            return new String[] { formatTrieu(buy), formatTrieu(sell) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 128500000 -> "128,5" */
    private static String formatTrieu(long dong) {
        long t = Math.round(dong / 100000.0);
        return (t / 10) + "," + (t % 10);
    }

    private static String worldGold(String... texts) {
        for (String text : texts) {
            if (text == null) continue;
            String raw = firstGroup(WORLD_GOLD, text);
            if (raw == null) continue;
            try {
                double v = Double.parseDouble(raw.replace(",", ""));
                long scaled = Math.round(v * 10);
                String ip = Long.toString(scaled / 10);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ip.length(); i++) {
                    if (i > 0 && (ip.length() - i) % 3 == 0) sb.append('.');
                    sb.append(ip.charAt(i));
                }
                return sb.append(',').append(scaled % 10).toString();
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String todayDdMm() {
        Calendar c = Calendar.getInstance();
        int d = c.get(Calendar.DAY_OF_MONTH);
        int mo = c.get(Calendar.MONTH) + 1;
        return (d < 10 ? "0" : "") + d + "/" + (mo < 10 ? "0" : "") + mo;
    }

    private static String fetchText(String address) {
        try {
            return stripHtml(get(address));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripHtml(String html) {
        String s = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        s = HTML_TAG_PATTERN.matcher(s).replaceAll(" ").replace("&nbsp;", " ");
        return WHITESPACE_PATTERN.matcher(s).replaceAll(" ");
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9");
        try {
            if (conn.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + conn.getResponseCode());
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            in.close();
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
