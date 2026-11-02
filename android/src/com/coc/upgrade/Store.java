package com.coc.upgrade;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 应用数据存储:config.json(模型服务商)+ data.json(账号与倒计时) */
public class Store {
    public static JSONObject config;
    public static JSONObject data;
    private static File cfgFile, dataFile;

    public static void init(Context ctx) {
        File dir = ctx.getFilesDir();
        cfgFile = new File(dir, "config.json");
        dataFile = new File(dir, "data.json");
        JSONObject newCfg = read(cfgFile);
        // 读取失败/文件损坏时保留内存中的数据,避免用空配置覆盖文件
        if (newCfg.optJSONArray("providers") == null
                && config != null && config.optJSONArray("providers") != null) {
            newCfg = config;
        }
        config = newCfg;
        if (config.optJSONArray("providers") == null) {
            // 不预置任何服务商,由用户在「模型设置」中自行添加
            put(config, "providers", new JSONArray());
            saveConfig();
        }
        JSONObject newData = read(dataFile);
        if (newData.optJSONArray("accounts") == null
                && data != null && data.optJSONArray("accounts") != null) {
            newData = data;
        }
        data = newData;
        if (data.optJSONArray("accounts") == null) {
            put(data, "accounts", new JSONArray());
        }
    }

    /** JSONObject.put 抛检查异常,统一包装成运行时异常方便链式书写 */
    public static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    public static JSONArray accounts() {
        return data.optJSONArray("accounts");
    }

    public static JSONObject account(String name) {
        if (name == null) return null;
        JSONArray as = accounts();
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a != null && name.equals(a.optString("name"))) return a;
        }
        return null;
    }

    public static JSONObject providerByName(String name) {
        JSONArray ps = config.optJSONArray("providers");
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && name.equals(p.optString("name"))) return p;
        }
        return null;
    }

    public static JSONObject timerById(String id) {
        JSONArray as = accounts();
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            JSONArray ts = a.optJSONArray("timers");
            if (ts == null) continue;
            for (int j = 0; j < ts.length(); j++) {
                JSONObject t = ts.optJSONObject(j);
                if (t != null && id.equals(t.optString("id"))) return t;
            }
        }
        return null;
    }

    public static JSONObject accountOfTimer(String id) {
        JSONArray as = accounts();
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            JSONArray ts = a.optJSONArray("timers");
            if (ts == null) continue;
            for (int j = 0; j < ts.length(); j++) {
                JSONObject t = ts.optJSONObject(j);
                if (t != null && id.equals(t.optString("id"))) return a;
            }
        }
        return null;
    }

    public static void saveConfig() {
        write(cfgFile, config);
    }

    public static void saveData() {
        write(dataFile, data);
    }

    private static JSONObject read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(File f, JSONObject o) {
        try {
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (IOException ignored) {
        }
    }
}
