package com.coc.upgrade;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** OpenAI 兼容接口调用(含视觉识别与返回解析) */
public class ApiClient {

    public static final String VISION_PROMPT =
            "你是游戏截图识别助手。这是一张手游《部落冲突》(Clash of Clans)的截图,里面显示了正在升级的建筑/兵种/法术及其剩余时间。\n\n"
            + "请识别截图中所有正在升级的项目及其剩余时间,以 JSON 数组返回,不要输出任何其他文字、解释或代码块标记:\n\n"
            + "[{\"item\":\"建筑或兵种名称\",\"days\":1,\"hours\":3,\"minutes\":0}]\n\n"
            + "时间规则:\n"
            + "- \"1d 2h\" 或 \"1天2小时\" => days:1, hours:2, minutes:0\n"
            + "- \"12h 30m\" => days:0, hours:12, minutes:30\n"
            + "- \"45m\" 或 \"45分钟\" => days:0, hours:0, minutes:45\n"
            + "- 如果画面上没有正在升级的项目,返回 []";

    /** 发送对话请求,content 为纯文本(String)或多模态数组(JSONArray),返回模型文本 */
    public static String chat(JSONObject provider, String model, Object content) throws Exception {
        String base = provider.optString("base_url").replaceAll("/+$", "");
        if (base.isEmpty()) throw new IOException("Base URL 未配置");

        JSONObject body = Store.put(new JSONObject(), "model", model);
        Store.put(body, "temperature", 0);
        Store.put(body, "max_tokens", 2000);
        JSONObject msg = Store.put(new JSONObject(), "role", "user");
        Store.put(msg, "content", content);
        JSONArray msgs = new JSONArray();
        msgs.put(msg);
        Store.put(body, "messages", msgs);

        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.optString("api_key", ""));
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = conn.getResponseCode();
        InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(is);
        if (code >= 400) throw new IOException("HTTP " + code + ": " + truncate(resp, 500));

        JSONObject res = new JSONObject(resp);
        Object c = res.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").opt("content");
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) c;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject seg = arr.optJSONObject(i);
                if (seg != null) sb.append(seg.optString("text"));
            }
            return sb.toString();
        }
        return String.valueOf(c);
    }

    /** 图片识别:img 为原始图片字节,ext 为扩展名(png/jpeg/webp 等) */
    public static String vision(JSONObject provider, String model, byte[] img, String ext) throws Exception {
        String b64 = Base64.encodeToString(img, Base64.NO_WRAP);
        JSONArray content = new JSONArray();
        JSONObject textPart = Store.put(new JSONObject(), "type", "text");
        Store.put(textPart, "text", VISION_PROMPT);
        content.put(textPart);
        JSONObject imgPart = Store.put(new JSONObject(), "type", "image_url");
        Store.put(imgPart, "image_url", Store.put(new JSONObject(), "url",
                "data:image/" + ext + ";base64," + b64));
        content.put(imgPart);
        return chat(provider, model, content);
    }

    /** 从模型返回文本中稳健提取升级条目,返回 [{item, seconds}] */
    public static JSONArray extractItems(String text) {
        JSONArray out = new JSONArray();
        if (text == null || text.isEmpty()) return out;
        String t = text.trim();
        if (t.contains("```")) {
            String[] parts = t.split("```");
            t = parts[0];
            for (String part : parts) if (part.length() > t.length()) t = part;
            t = t.trim();
            if (t.startsWith("json")) t = t.substring(4).trim();
        }
        int s = t.indexOf('['), e = t.lastIndexOf(']');
        if (s < 0 || e < s) {
            s = t.indexOf('{');
            e = t.lastIndexOf('}');
        }
        if (s < 0 || e < s) return out;
        Object data;
        try {
            data = new JSONTokener(t.substring(s, e + 1)).nextValue();
        } catch (Exception ex) {
            return out;
        }
        JSONArray list;
        if (data instanceof JSONArray) {
            list = (JSONArray) data;
        } else if (data instanceof JSONObject) {
            JSONObject o = (JSONObject) data;
            list = o.optJSONArray("upgrades");
            if (list == null) list = o.optJSONArray("items");
            if (list == null) {
                list = new JSONArray();
                list.put(o);
            }
        } else {
            return out;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject raw = list.optJSONObject(i);
            if (raw == null) continue;
            long seconds;
            int tm = raw.optInt("total_minutes", -1);
            if (tm >= 0) {
                seconds = tm * 60L;
            } else {
                seconds = raw.optInt("days", 0) * 86400L + raw.optInt("hours", 0) * 3600L
                        + raw.optInt("minutes", 0) * 60L + raw.optInt("seconds", 0);
            }
            String item = raw.optString("item", "").trim();
            if (item.isEmpty()) item = raw.optString("name", "").trim();
            if (seconds > 0) {
                JSONObject it = new JSONObject();
                Store.put(it, "item", item);
                Store.put(it, "seconds", seconds);
                out.put(it);
            }
        }
        return out;
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

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }
}
