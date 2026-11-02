package com.coc.upgrade;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** WebDAV 多设备同步:下载远端 → 按条目合并 → 上传。数据小(几 KB),每次全量同步。 */
public class Sync {

    private static final AtomicBoolean running = new AtomicBoolean(false);

    public interface Callback {
        void onDone(String msg);
    }

    public static JSONObject syncConf() {
        return Store.config.optJSONObject("sync");
    }

    public static boolean enabled() {
        JSONObject s = syncConf();
        return s != null && s.optBoolean("enabled")
                && !s.optString("url").isEmpty()
                && !s.optString("user").isEmpty();
    }

    /** 异步同步(带单飞锁,避免并发互相覆盖) */
    public static void syncAsync(Callback cb) {
        if (!enabled()) {
            if (cb != null) cb.onDone("同步未启用或未填写完整");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            if (cb != null) cb.onDone("上一次同步还在进行中");
            return;
        }
        final Callback fcb = cb;
        new Thread(() -> {
            String msg;
            try {
                msg = doSync();
            } catch (Exception e) {
                msg = "同步失败:" + e.getMessage();
            } finally {
                running.set(false);
            }
            if (fcb != null) fcb.onDone(msg);
        }).start();
    }

    private static String doSync() throws Exception {
        JSONObject sc = syncConf();
        String url = sc.optString("url").trim();
        String user = sc.optString("user"), pass = sc.optString("pass");

        // 1. 下载远端(404 = 首次使用)
        byte[] remoteBytes = http("GET", url, user, pass, null);
        JSONObject remoteData = null, remoteCfg = null;
        if (remoteBytes != null) {
            JSONObject remoteRoot = new JSONObject(new String(remoteBytes, StandardCharsets.UTF_8));
            remoteData = remoteRoot.optJSONObject("data");
            remoteCfg = remoteRoot.optJSONObject("config");
        }

        // 2. 合并
        JSONObject mergedData = mergeData(Store.data, remoteData);
        JSONObject mergedCfg = mergeConfig(Store.config, remoteCfg);
        boolean cfgChanged = !mergedCfg.toString().equals(Store.config.toString());
        Store.data = mergedData;
        Store.config = mergedCfg;
        Store.saveData();
        if (cfgChanged) Store.saveConfig();

        // 3. 上传
        JSONObject root = new JSONObject();
        Store.put(root, "data", mergedData);
        Store.put(root, "config", mergedCfg);
        Store.put(root, "synced_at", System.currentTimeMillis());
        http("PUT", url, user, pass, root.toString().getBytes(StandardCharsets.UTF_8));

        Store.put(sc, "last_ts", System.currentTimeMillis());
        Store.saveConfig();

        return "同步成功 · " + countTimers(mergedData) + " 个倒计时"
                + (remoteBytes == null ? "(首次上传)" : "");
    }

    // ---------------------------------------------------------------- HTTP

    private static byte[] http(String method, String url, String user, String pass,
                               byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        String auth = Base64.encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        c.setRequestProperty("Authorization", "Basic " + auth);
        c.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
        }
        int code = c.getResponseCode();
        if (code == 404 && "GET".equals(method)) return null;
        if (code >= 300) {
            String detail = readAll(c.getErrorStream());
            String ex = extractTag(detail, "exception");
            String msg = extractTag(detail, "message");
            throw new IOException("HTTP " + code + "(" + method + ")"
                    + (ex.isEmpty() ? "" : " " + ex)
                    + (msg.isEmpty() ? "" : " " + msg)
                    + (ex.isEmpty() && msg.isEmpty() ? " " + snippet(detail) : ""));
        }
        return readAll(c.getInputStream()).getBytes(StandardCharsets.UTF_8);
    }

    /** 从 WebDAV 错误 XML 里提取异常类型/说明,如 <s:exception>xxx</s:exception> */
    private static String extractTag(String xml, String tag) {
        if (xml == null || xml.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[^>]*" + tag + ">([^<]{0,200})<").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    // ---------------------------------------------------------------- data 合并

    /** 账号按名合并;倒计时按 id 合并(取较晚的 end_ts,notified 取或);删除用墓碑同步 */
    static JSONObject mergeData(JSONObject local, JSONObject remote) {
        if (remote == null || remote.length() == 0) return local;
        Set<String> tomb = new HashSet<>();
        collect(local.optJSONArray("deleted_ids"), tomb);
        collect(remote.optJSONArray("deleted_ids"), tomb);

        LinkedHashMap<String, JSONObject> accs = new LinkedHashMap<>();
        JSONArray la = local.optJSONArray("accounts");
        for (int i = 0; i < (la == null ? 0 : la.length()); i++) {
            JSONObject a = la.optJSONObject(i);
            if (a != null) accs.put(a.optString("name"), a);
        }
        JSONArray ra = remote.optJSONArray("accounts");
        for (int i = 0; i < (ra == null ? 0 : ra.length()); i++) {
            JSONObject b = ra.optJSONObject(i);
            if (b == null) continue;
            JSONObject a = accs.get(b.optString("name"));
            if (a == null) accs.put(b.optString("name"), b);
            else mergeTimers(a, b, tomb);
        }

        JSONArray outAccs = new JSONArray();
        for (JSONObject a : accs.values()) {
            JSONArray ts = a.optJSONArray("timers");
            JSONArray keep = new JSONArray();
            for (int i = 0; i < (ts == null ? 0 : ts.length()); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null && !tomb.contains(t.optString("id"))) keep.put(t);
            }
            Store.put(a, "timers", keep);
            outAccs.put(a);
        }
        JSONObject res = new JSONObject();
        Store.put(res, "accounts", outAccs);
        if (!tomb.isEmpty()) {
            JSONArray td = new JSONArray();
            for (String id : tomb) td.put(id);
            Store.put(res, "deleted_ids", td);
        }
        return res;
    }

    private static void mergeTimers(JSONObject a, JSONObject b, Set<String> tomb) {
        JSONArray ta = a.optJSONArray("timers");
        LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<>();
        for (int i = 0; i < (ta == null ? 0 : ta.length()); i++) {
            JSONObject t = ta.optJSONObject(i);
            if (t != null && !tomb.contains(t.optString("id"))) byId.put(t.optString("id"), t);
        }
        JSONArray tb = b.optJSONArray("timers");
        for (int i = 0; i < (tb == null ? 0 : tb.length()); i++) {
            JSONObject t = tb.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("id");
            if (tomb.contains(id)) continue;
            JSONObject x = byId.get(id);
            if (x == null) {
                byId.put(id, t);
            } else {
                // 以 updated_at 较新者整条为准,这样加速药水缩短时间、改名、标记已提醒
                // 等修改都能正确同步(不会把刚缩短的时间被云端旧值覆盖回去)
                if (t.optLong("updated_at", 0) > x.optLong("updated_at", 0)) {
                    byId.put(id, t);
                }
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject t : byId.values()) out.put(t);
        Store.put(a, "timers", out);
    }

    private static void collect(JSONArray arr, Set<String> out) {
        for (int i = 0; i < (arr == null ? 0 : arr.length()); i++) {
            String id = arr.optString(i);
            if (!id.isEmpty()) out.add(id);
        }
    }

    // ---------------------------------------------------------------- config 合并

    /** 服务商按名合并(重复时本地优先,字段取非空,模型取并集);sync 配置始终保留本地 */
    static JSONObject mergeConfig(JSONObject local, JSONObject remote) {
        if (remote == null) return local;
        JSONArray lp = local.optJSONArray("providers");
        JSONArray rp = remote.optJSONArray("providers");
        if (rp != null && rp.length() > 0) {
            if (lp == null) {
                lp = new JSONArray();
                Store.put(local, "providers", lp);
            }
            LinkedHashMap<String, JSONObject> byName = new LinkedHashMap<>();
            for (int i = 0; i < lp.length(); i++) {
                JSONObject p = lp.optJSONObject(i);
                if (p != null) byName.put(p.optString("name"), p);
            }
            for (int i = 0; i < rp.length(); i++) {
                JSONObject r = rp.optJSONObject(i);
                if (r == null) continue;
                JSONObject x = byName.get(r.optString("name"));
                if (x == null) {
                    byName.put(r.optString("name"), r);
                } else {
                    if (x.optString("base_url").isEmpty())
                        Store.put(x, "base_url", r.optString("base_url"));
                    if (x.optString("api_key").isEmpty())
                        Store.put(x, "api_key", r.optString("api_key"));
                    LinkedHashSet<String> models = new LinkedHashSet<>();
                    JSONArray xm = x.optJSONArray("models");
                    for (int j = 0; j < (xm == null ? 0 : xm.length()); j++)
                        if (!xm.optString(j).isEmpty()) models.add(xm.optString(j));
                    JSONArray rm = r.optJSONArray("models");
                    for (int j = 0; j < (rm == null ? 0 : rm.length()); j++)
                        if (!rm.optString(j).isEmpty()) models.add(rm.optString(j));
                    JSONArray mo = new JSONArray();
                    for (String m : models) mo.put(m);
                    Store.put(x, "models", mo);
                }
            }
            JSONArray out = new JSONArray();
            for (JSONObject p : byName.values()) out.put(p);
            Store.put(local, "providers", out);
        }
        if (local.optString("default_model").isEmpty())
            Store.put(local, "default_model", remote.optString("default_model"));
        return local;
    }

    // ---------------------------------------------------------------- 工具

    private static int countTimers(JSONObject data) {
        int n = 0;
        JSONArray as = data.optJSONArray("accounts");
        for (int i = 0; i < (as == null ? 0 : as.length()); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            n += a.optJSONArray("timers") == null ? 0 : a.optJSONArray("timers").length();
        }
        return n;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
