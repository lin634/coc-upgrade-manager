#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
部落冲突升级倒计时管理工具
- 多账号管理
- 截图导入 + AI 识别剩余升级时间,自动创建倒计时
- 多服务商多模型(OpenAI 兼容协议)
- 倒计时结束弹窗 + 声音提醒
纯标准库实现,无需安装第三方依赖。
"""

import base64
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

try:
    import winsound
except ImportError:
    winsound = None

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
DATA_PATH = os.path.join(BASE_DIR, "data.json")

# 默认预置的服务商(不含密钥,用户在设置里填)
DEFAULT_PROVIDERS = [
    {"name": "智谱AI", "base_url": "https://open.bigmodel.cn/api/paas/v4",
     "api_key": "", "models": ["glm-4.5v", "glm-4v-plus"]},
    {"name": "DeepSeek", "base_url": "https://api.deepseek.com/v1",
     "api_key": "", "models": ["deepseek-chat"]},
    {"name": "通义千问", "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
     "api_key": "", "models": ["qwen-vl-max", "qwen-vl-plus"]},
    {"name": "Kimi", "base_url": "https://api.moonshot.cn/v1",
     "api_key": "", "models": ["moonshot-v1-8k-vision-preview"]},
    {"name": "OpenAI", "base_url": "https://api.openai.com/v1",
     "api_key": "", "models": ["gpt-4o", "gpt-4o-mini"]},
]

VISION_PROMPT = """你是游戏截图识别助手。这是一张手游《部落冲突》(Clash of Clans)的截图,里面显示了正在升级的建筑/兵种/法术及其剩余时间。

请识别截图中所有正在升级的项目及其剩余时间,以 JSON 数组返回,不要输出任何其他文字、解释或代码块标记:

[{"item":"建筑或兵种名称","days":1,"hours":3,"minutes":0}]

时间规则:
- "1d 2h" 或 "1天2小时" => days:1, hours:2, minutes:0
- "12h 30m" => days:0, hours:12, minutes:30
- "45m" 或 "45分钟" => days:0, hours:0, minutes:45
- 如果画面上没有正在升级的项目,返回 []"""


# ---------------------------------------------------------------- 数据读写

def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def load_config():
    cfg = load_json(CONFIG_PATH, {})
    if not cfg.get("providers"):
        cfg["providers"] = json.loads(json.dumps(DEFAULT_PROVIDERS))
    cfg.setdefault("default_model", "")
    return cfg


# ---------------------------------------------------------------- API 调用

def call_chat_api(provider, model, content, is_vision):
    """调用 OpenAI 兼容接口。content 为文本或 [text, image_url] 多模态列表。"""
    base_url = provider["base_url"].rstrip("/")
    url = base_url + "/chat/completions"
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": content}],
        "temperature": 0,
        "max_tokens": 2000,
    }
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + provider.get("api_key", ""),
        })
    with urllib.request.urlopen(req, timeout=120) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    msg = result.get("choices", [{}])[0].get("message", {})
    content = msg.get("content", "")
    # 部分服务商返回分段的 content 列表
    if isinstance(content, list):
        content = "".join(
            seg.get("text", "") for seg in content if isinstance(seg, dict))
    return content


def call_vision(provider, model, image_path):
    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    ext = os.path.splitext(image_path)[1].lower().lstrip(".")
    if ext == "jpg":
        ext = "jpeg"
    if ext not in ("png", "jpeg", "webp", "gif"):
        ext = "png"
    content = [
        {"type": "text", "text": VISION_PROMPT},
        {"type": "image_url",
         "image_url": {"url": "data:image/%s;base64,%s" % (ext, b64)}},
    ]
    return call_chat_api(provider, model, content, is_vision=True)


def extract_items(text):
    """从模型返回文本中稳健地提取升级条目 JSON。"""
    if not text:
        return []
    t = text.strip()
    # 去掉 markdown 代码块围栏
    if "```" in t:
        parts = t.split("```")
        t = max(parts, key=len).strip()
        if t.startswith("json"):
            t = t[4:].strip()
    start = t.find("[")
    end = t.rfind("]")
    if start == -1 or end == -1 or end < start:
        start = t.find("{")
        end = t.rfind("}")
        if start == -1 or end == -1 or end < start:
            return []
    try:
        data = json.loads(t[start:end + 1])
    except ValueError:
        return []
    if isinstance(data, dict):
        data = data.get("upgrades") or data.get("items") or [data]
    items = []
    for raw in data if isinstance(data, list) else []:
        if not isinstance(raw, dict):
            continue

        def num(key, default=0):
            try:
                return int(float(str(raw.get(key, default)).strip() or default))
            except ValueError:
                return default

        minutes = num("total_minutes", -1)
        if minutes >= 0:
            seconds = minutes * 60
        else:
            seconds = (num("days") * 86400 + num("hours") * 3600
                       + num("minutes") * 60 + num("seconds"))
        item = str(raw.get("item") or raw.get("name") or "").strip()
        if seconds > 0:
            items.append({"item": item, "seconds": seconds})
    return items


# ---------------------------------------------------------------- 时间格式化

def fmt_remaining(seconds):
    if seconds <= 0:
        return "已完成"
    d, r = divmod(int(seconds), 86400)
    h, r = divmod(r, 3600)
    m, s = divmod(r, 60)
    if d > 0:
        return "%d天%02d:%02d:%02d" % (d, h, m, s)
    return "%02d:%02d:%02d" % (h, m, s)


def fmt_end(end_ts):
    return time.strftime("%m-%d %H:%M", time.localtime(end_ts))


# ---------------------------------------------------------------- 主应用

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("部落冲突升级管理")
        self.geometry("860x560")
        self.minsize(700, 420)

        self.config = load_config()
        self.data = load_json(DATA_PATH, {"accounts": []})
        self.data.setdefault("accounts", [])
        self.current_account = None
        self._popup_count = 0

        self._build_ui()
        self._load_last_account()
        self.after(500, self._tick)

    # ---------- UI 构建 ----------

    def _build_ui(self):
        style = ttk.Style(self)
        if "vista" in style.theme_names():
            style.theme_use("vista")
        style.configure("Big.TButton", font=("Microsoft YaHei UI", 10))
        style.configure("Treeview", rowheight=26, font=("Microsoft YaHei UI", 10))
        style.configure("Treeview.Heading", font=("Microsoft YaHei UI", 10, "bold"))

        top = ttk.Frame(self, padding=(8, 8, 8, 4))
        top.pack(fill="x")
        ttk.Label(top, text="当前账号:").pack(side="left")
        self.account_var = tk.StringVar()
        self.account_box = ttk.Combobox(
            top, textvariable=self.account_var, state="readonly", width=16,
            postcommand=self._refresh_account_box)
        self.account_box.pack(side="left", padx=(4, 4))
        self.account_box.bind("<<ComboboxSelected>>", self._on_account_switch)
        ttk.Button(top, text="＋ 账号", style="Big.TButton",
                   command=self._add_account).pack(side="left", padx=2)
        ttk.Button(top, text="－ 账号",
                   command=self._remove_account).pack(side="left", padx=2)

        ttk.Button(top, text="⚙ 模型设置", style="Big.TButton",
                   command=self._open_settings).pack(side="right")

        mid = ttk.Frame(self, padding=(8, 0, 8, 0))
        mid.pack(fill="x")
        ttk.Button(mid, text="📷 截图识别添加", style="Big.TButton",
                   command=self._import_screenshot).pack(side="left", padx=(0, 6))
        ttk.Button(mid, text="✍ 手动添加",
                   command=self._manual_add).pack(side="left", padx=6)
        ttk.Button(mid, text="🗑 删除选中",
                   command=self._delete_selected).pack(side="left", padx=6)
        ttk.Button(mid, text="清除已完成",
                   command=self._clear_done).pack(side="left", padx=6)

        cols = ("item", "remain", "end", "status")
        frame = ttk.Frame(self, padding=(8, 6, 8, 8))
        frame.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(frame, columns=cols, show="headings")
        headers = {"item": ("升级项目", 300, "w"),
                   "remain": ("剩余时间", 160, "center"),
                   "end": ("预计完成", 130, "center"),
                   "status": ("状态", 90, "center")}
        for c in cols:
            text, width, anchor = headers[c]
            self.tree.heading(c, text=text)
            self.tree.column(c, width=width, anchor=anchor)
        vsb = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=vsb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        vsb.pack(side="left", fill="y")
        self.tree.tag_configure("done", foreground="#188038")

        self.status_var = tk.StringVar(value="就绪")
        ttk.Label(self, textvariable=self.status_var, relief="sunken",
                  anchor="w", padding=(8, 3)).pack(fill="x", side="bottom")

    # ---------- 账号管理 ----------

    def _accounts(self):
        return self.data["accounts"]

    def _refresh_account_box(self):
        self.account_box["values"] = [a["name"] for a in self._accounts()]

    def _load_last_account(self):
        names = [a["name"] for a in self._accounts()]
        if names:
            self.current_account = names[0]
        self._refresh_account_box()
        if self.current_account:
            self.account_var.set(self.current_account)
        self._refresh_tree()

    def _on_account_switch(self, _event=None):
        self.current_account = self.account_var.get()
        self._refresh_tree()

    def _get_account(self, name=None):
        name = name or self.current_account
        for a in self._accounts():
            if a["name"] == name:
                return a
        return None

    def _add_account(self):
        name = simpledialog.askstring("添加账号", "请输入部落冲突账号名称:",
                                      parent=self)
        if not name:
            return
        name = name.strip()
        if not name:
            return
        if any(a["name"] == name for a in self._accounts()):
            messagebox.showwarning("提示", "账号「%s」已存在" % name, parent=self)
            return
        self._accounts().append({"name": name, "timers": []})
        self._save_data()
        self.current_account = name
        self._refresh_account_box()
        self.account_var.set(name)
        self._refresh_tree()

    def _remove_account(self):
        acc = self._get_account()
        if not acc:
            return
        if not messagebox.askyesno(
                "删除账号", "确定删除账号「%s」及其全部倒计时吗?" % acc["name"],
                parent=self):
            return
        self.data["accounts"].remove(acc)
        self._save_data()
        self.current_account = None
        if self._accounts():
            self.current_account = self._accounts()[0]["name"]
        self._refresh_account_box()
        if self.current_account:
            self.account_var.set(self.current_account)
        self._refresh_tree()

    # ---------- 倒计时列表 ----------

    def _timers(self):
        acc = self._get_account()
        return acc["timers"] if acc else []

    def _save_data(self):
        save_json(DATA_PATH, self.data)

    def _refresh_tree(self):
        self.tree.delete(*self.tree.get_children())
        now = time.time()
        rows = sorted(self._timers(), key=lambda t: t["end_ts"])
        for t in rows:
            remain = t["end_ts"] - now
            status = "已完成" if remain <= 0 else "倒计时中"
            tags = ("done",) if remain <= 0 else ()
            self.tree.insert("", "end", iid=t["id"], tags=tags, values=(
                t["item"], fmt_remaining(remain), fmt_end(t["end_ts"]), status))

    def _add_timers(self, account_name, items):
        acc = self._get_account(account_name)
        if not acc:
            return
        for it in items:
            acc["timers"].append({
                "id": uuid.uuid4().hex,
                "item": it["item"] or "升级项目",
                "end_ts": time.time() + it["seconds"],
                "notified": False,
            })
        self._save_data()
        self._refresh_tree()

    def _delete_selected(self):
        sel = list(self.tree.selection())
        if not sel:
            return
        timers = self._timers()
        timers[:] = [t for t in timers if t["id"] not in sel]
        self._save_data()
        self._refresh_tree()

    def _clear_done(self):
        now = time.time()
        timers = self._timers()
        timers[:] = [t for t in timers if t["end_ts"] > now]
        self._save_data()
        self._refresh_tree()

    # ---------- 添加倒计时 ----------

    def _require_account(self):
        if not self._get_account():
            messagebox.showinfo("提示", "请先添加一个账号", parent=self)
            return False
        return True

    def _import_screenshot(self):
        if not self._require_account():
            return
        provider, model, err = self._pick_model()
        if err:
            messagebox.showwarning("模型设置", err, parent=self)
            self._open_settings()
            return
        path = filedialog.askopenfilename(
            parent=self, title="选择升级剩余时间截图",
            filetypes=[("图片", "*.png;*.jpg;*.jpeg;*.webp;*.bmp"),
                       ("所有文件", "*.*")])
        if not path:
            return
        self.status_var.set("正在调用 AI 识别截图 (%s / %s)…" % (provider["name"], model))
        self.update_idletasks()

        def worker():
            try:
                text = call_vision(provider, model, path)
                items = extract_items(text)
                self.after(0, lambda: self._on_vision_done(items, text))
            except Exception as exc:  # noqa: BLE001
                self.after(0, lambda: self._on_vision_error(exc))

        threading.Thread(target=worker, daemon=True).start()

    def _pick_model(self):
        """返回 (provider, model, err)。"""
        sel = self.config.get("default_model", "")
        if "/" in sel:
            pname, model = sel.split("/", 1)
        else:
            pname, model = "", ""
        for p in self.config["providers"]:
            if p["name"] == pname and model in p.get("models", []):
                if not p.get("api_key"):
                    return None, None, "服务商「%s」还没有填写 API Key" % p["name"]
                return p, model, None
        return None, None, ("请先在「模型设置」中选择默认模型并填写 API Key\n"
                            "(需要支持图片识别的模型,如 glm-4.5v / qwen-vl / gpt-4o)")

    def _on_vision_done(self, items, raw_text):
        self.status_var.set("就绪")
        if not items:
            messagebox.showwarning(
                "识别结果为空",
                "AI 没有从截图中识别到正在升级的项目。\n\n"
                "建议:\n· 确认截图里能看到剩余时间(如 1d 2h 或 3天12小时)\n"
                "· 检查所选模型是否支持图片识别\n\n"
                "模型原始返回:\n%s" % (raw_text[:500] or "(空)"),
                parent=self)
            return
        self._show_confirm_dialog(items)

    def _on_vision_error(self, exc):
        self.status_var.set("就绪")
        detail = exc
        if isinstance(exc, urllib.error.HTTPError):
            try:
                detail = exc.read().decode("utf-8", "replace")[:800]
            except OSError:
                detail = str(exc)
        messagebox.showerror("AI 识别失败", "调用模型接口出错:\n%s" % detail,
                             parent=self)

    def _show_confirm_dialog(self, items):
        """识别结果确认窗口:每行可改名称和时间,可删行,确认后写入。"""
        win = tk.Toplevel(self)
        win.title("确认识别结果 — 账号:%s" % self.current_account)
        win.transient(self)
        win.grab_set()
        win.geometry("640x420")

        ttk.Label(win, text="AI 识别到以下升级项目,请核对剩余时间(可直接修改):",
                  padding=(10, 8)).pack(anchor="w")
        body = ttk.Frame(win, padding=(10, 0))
        body.pack(fill="both", expand=True)
        rows = []

        def add_row(item="", seconds=0):
            r = len(rows)
            d, rem = divmod(max(seconds, 0), 86400)
            h, rem = divmod(rem, 3600)
            m, _s = divmod(rem, 60)
            name_e = ttk.Entry(body, width=28)
            name_e.insert(0, item or ("升级项目 %d" % (r + 1)))
            de, he, me = (ttk.Entry(body, width=5) for _ in range(3))
            for e, v in ((de, d), (he, h), (me, m)):
                e.insert(0, str(v))
            for col, w in ((0, (8, "e")), (1, (28, "w")), (2, (5, "center")),
                           (3, (5, "center")), (4, (5, "center"))):
                body.columnconfigure(col, weight=w[1], pad=3)
                body.columnconfigure(col, minsize=w[0] if col == 0 else 0)
            ttk.Label(body, text="名称:").grid(row=r, column=0, sticky="e")
            name_e.grid(row=r, column=1, sticky="w")
            ttk.Label(body, text="天").grid(row=r, column=2)
            de.grid(row=r, column=3)
            ttk.Label(body, text="时").grid(row=r, column=4)
            he.grid(row=r, column=5)
            ttk.Label(body, text="分").grid(row=r, column=6)
            me.grid(row=r, column=7)
            btn = ttk.Button(body, text="✕", width=3,
                             command=lambda: remove_row(r))
            btn.grid(row=r, column=8, padx=(8, 0))
            rows.append((name_e, de, he, me, btn))

        def remove_row(idx):
            widgets = rows[idx]
            for w in widgets[:4]:
                w.destroy()
            widgets[4].destroy()
            rows[idx] = None
            # 网格行不会自动收缩,重建布局
            for i, rw in enumerate(rows):
                if rw is None:
                    continue
                for col, w in enumerate(rw):
                    w.grid_configure(row=i)

        for it in items:
            add_row(it["item"], it["seconds"])
        if not rows:
            add_row()

        def confirm():
            picked = []
            for rw in rows:
                if rw is None:
                    continue
                name_e, de, he, me, _btn = rw
                try:
                    secs = (int(float(de.get() or 0)) * 86400
                            + int(float(he.get() or 0)) * 3600
                            + int(float(me.get() or 0)) * 60)
                except ValueError:
                    messagebox.showwarning("提示", "时间请填写数字", parent=win)
                    return
                name = name_e.get().strip() or "升级项目"
                if secs > 0:
                    picked.append({"item": name, "seconds": secs})
            if not picked:
                messagebox.showwarning("提示", "没有可添加的项目(时间需大于 0)",
                                       parent=win)
                return
            self._add_timers(self.current_account, picked)
            win.destroy()
            self.status_var.set("已添加 %d 个倒计时" % len(picked))

        bar = ttk.Frame(win, padding=10)
        bar.pack(fill="x")
        ttk.Button(bar, text="＋ 再加一行",
                   command=lambda: add_row()).pack(side="left")
        ttk.Button(bar, text="确认添加", style="Big.TButton",
                   command=confirm).pack(side="right")
        ttk.Button(bar, text="取消", command=win.destroy).pack(
            side="right", padx=8)

    def _manual_add(self):
        if not self._require_account():
            return
        win = tk.Toplevel(self)
        win.title("手动添加倒计时 — 账号:%s" % self.current_account)
        win.transient(self)
        win.grab_set()
        frm = ttk.Frame(win, padding=14)
        frm.pack(fill="both", expand=True)
        ttk.Label(frm, text="升级项目名称:").grid(row=0, column=0, sticky="e", pady=4)
        name_e = ttk.Entry(frm, width=24)
        name_e.grid(row=0, column=1, columnspan=5, sticky="w", pady=4)
        name_e.focus_set()
        spins = []
        for i, (label, default) in enumerate((("天", 0), ("时", 0), ("分", 0))):
            ttk.Label(frm, text=label).grid(row=1, column=i * 2 + 1, sticky="w")
            sv = tk.StringVar(value=str(default))
            tk.Spinbox(frm, from_=0, to=999, textvariable=sv, width=5
                       ).grid(row=1, column=i * 2, sticky="e", padx=2, pady=4)
            spins.append(sv)

        def confirm():
            try:
                secs = (int(float(spins[0].get() or 0)) * 86400
                        + int(float(spins[1].get() or 0)) * 3600
                        + int(float(spins[2].get() or 0)) * 60)
            except ValueError:
                messagebox.showwarning("提示", "时间请填写数字", parent=win)
                return
            if secs <= 0:
                messagebox.showwarning("提示", "剩余时间必须大于 0", parent=win)
                return
            self._add_timers(self.current_account,
                             [{"item": name_e.get().strip() or "升级项目",
                               "seconds": secs}])
            win.destroy()

        ttk.Button(frm, text="添加", style="Big.TButton", command=confirm
                   ).grid(row=2, column=5, sticky="e", pady=(8, 0))
        ttk.Button(frm, text="取消", command=win.destroy
                   ).grid(row=2, column=4, sticky="e", pady=(8, 0), padx=8)

    # ---------- 到点提醒 ----------

    def _tick(self):
        now = time.time()
        changed = False
        for acc in self._accounts():
            for t in acc["timers"]:
                if t["end_ts"] <= now and not t.get("notified"):
                    t["notified"] = True
                    changed = True
                    self._popup_notify(acc["name"], t)
        if changed:
            self._save_data()
        # 仅刷新当前账号视图
        if self.tree and self.tree.winfo_exists():
            cur_ids = set(self.tree.get_children())
            now2 = time.time()
            for t in self._timers():
                if t["id"] in cur_ids:
                    remain = t["end_ts"] - now2
                    vals = (t["item"], fmt_remaining(remain),
                            fmt_end(t["end_ts"]),
                            "已完成" if remain <= 0 else "倒计时中")
                    self.tree.item(t["id"], values=vals)
        self.after(1000, self._tick)

    def _popup_notify(self, account_name, timer):
        self._popup_count += 1
        offset = (self._popup_count - 1) * 36
        win = tk.Toplevel(self)
        win.title("⏰ 升级完成")
        win.attributes("-topmost", True)
        win.geometry("+%d+%d" % (self.winfo_rootx() + 160 + offset,
                                 self.winfo_rooty() + 120 + offset))
        frm = ttk.Frame(win, padding=18)
        frm.pack()
        ttk.Label(frm, text="⏰ 升级完成!", font=("Microsoft YaHei UI", 14, "bold")
                  ).pack(pady=(0, 8))
        ttk.Label(frm, text="账号:%s" % account_name,
                  font=("Microsoft YaHei UI", 11)).pack(anchor="w")
        ttk.Label(frm, text="项目:%s" % timer["item"],
                  font=("Microsoft YaHei UI", 11)).pack(anchor="w")
        ttk.Label(frm, text="完成时间:%s" % fmt_end(timer["end_ts"]),
                  font=("Microsoft YaHei UI", 10)).pack(anchor="w", pady=(2, 10))
        ttk.Button(frm, text="知道了", style="Big.TButton",
                   command=win.destroy).pack()

        def beep():
            if winsound:
                for _ in range(3):
                    try:
                        winsound.Beep(880, 300)
                        time.sleep(0.15)
                    except RuntimeError:
                        break

        threading.Thread(target=beep, daemon=True).start()
        self.tree.tag_configure("done", foreground="#188038")  # keep style

    # ---------- 模型设置 ----------

    def _open_settings(self):
        SettingsDialog(self)


class SettingsDialog(tk.Toplevel):
    def __init__(self, master):
        super().__init__(master)
        self.app = master
        self.title("模型设置")
        self.transient(master)
        self.grab_set()
        self.geometry("720x480")
        # 工作副本
        self.providers = json.loads(json.dumps(master.config["providers"]))
        self.default_model = master.config.get("default_model", "")
        self.current_idx = 0

        main = ttk.Frame(self, padding=10)
        main.pack(fill="both", expand=True)

        left = ttk.Frame(main)
        left.pack(side="left", fill="y", padx=(0, 10))
        ttk.Label(left, text="服务商列表:").pack(anchor="w")
        self.listbox = tk.Listbox(left, width=18, exportselection=False,
                                  font=("Microsoft YaHei UI", 10))
        self.listbox.pack(fill="y", expand=True, pady=(4, 4))
        self.listbox.bind("<<ListboxSelect>>", self._on_select)
        bar = ttk.Frame(left)
        bar.pack(fill="x")
        ttk.Button(bar, text="＋", width=3, command=self._add_provider
                   ).pack(side="left")
        ttk.Button(bar, text="－", width=3, command=self._remove_provider
                   ).pack(side="left", padx=4)

        right = ttk.Frame(main)
        right.pack(side="left", fill="both", expand=True)
        self.fields = {}
        for row, (key, label) in enumerate((
                ("name", "名称"), ("base_url", "Base URL"),
                ("api_key", "API Key"), ("models", "模型(逗号分隔)"))):
            ttk.Label(right, text=label).grid(row=row, column=0, sticky="e",
                                              pady=4, padx=(0, 6))
            var = tk.StringVar()
            ent = ttk.Entry(right, textvariable=var, width=48)
            if key == "api_key":
                ent.configure(show="•")
            ent.grid(row=row, column=1, sticky="we", pady=4)
            var.trace_add("write", lambda *_a, k=key: self._save_field(k))
            self.fields[key] = var
        right.columnconfigure(1, weight=1)
        ttk.Label(right, foreground="#666", wraplength=380, justify="left",
                  text="提示:填写兼容 OpenAI 协议的地址,如\n"
                       "智谱 https://open.bigmodel.cn/api/paas/v4\n"
                       "DeepSeek https://api.deepseek.com/v1\n"
                       "通义 https://dashscope.aliyuncs.com/compatible-mode/v1"
                  ).grid(row=4, column=0, columnspan=2, sticky="w", pady=(6, 0))

        ttk.Label(main, text="截图识别默认模型(需支持图片):").pack(
            anchor="w", pady=(8, 0))
        self.default_box = ttk.Combobox(postcommand=self._refresh_default_box)
        self.default_box.pack(fill="x", pady=(2, 8))
        ttk.Button(main, text="测试连接", command=self._test).pack(anchor="w")

        bottom = ttk.Frame(self, padding=10)
        bottom.pack(fill="x")
        ttk.Button(bottom, text="保存", style="Big.TButton",
                   command=self._save).pack(side="right")
        ttk.Button(bottom, text="取消", command=self.destroy
                   ).pack(side="right", padx=8)

        self._refresh_list()
        if self.providers:
            self.listbox.selection_set(0)
            self._load_provider(0)
        self.default_box.set(self.default_model)

    # --- 服务商编辑 ---

    def _refresh_list(self):
        self.listbox.delete(0, "end")
        for p in self.providers:
            mark = "✓ " if p["name"] + "/" in self.default_model or \
                self.default_model.startswith(p["name"] + "/") else ""
            key_mark = "🔑" if p.get("api_key") else "  "
            self.listbox.insert("end", "%s%s %s" % (key_mark, mark, p["name"]))

    def _load_provider(self, idx):
        self.current_idx = idx
        p = self.providers[idx]
        self.fields["name"].set(p.get("name", ""))
        self.fields["base_url"].set(p.get("base_url", ""))
        self.fields["api_key"].set(p.get("api_key", ""))
        self.fields["models"].set(", ".join(p.get("models", [])))

    def _on_select(self, _event=None):
        sel = self.listbox.curselection()
        if sel:
            self._load_provider(sel[0])

    def _save_field(self, key):
        if not self.providers:
            return
        p = self.providers[self.current_idx]
        val = self.fields[key].get()
        if key == "models":
            p["models"] = [m.strip() for m in val.split(",") if m.strip()]
        else:
            p[key] = val.strip()
        if key == "name":
            # 同步默认模型里的名称与列表显示
            old_default = self.default_model
            self._refresh_list()
            if old_default.startswith(p["name"] + "/") or True:
                pass
        self._refresh_default_box()

    def _add_provider(self):
        self.providers.append({"name": "新服务商", "base_url": "",
                               "api_key": "", "models": []})
        self._refresh_list()
        self.listbox.selection_set(len(self.providers) - 1)
        self._load_provider(len(self.providers) - 1)

    def _remove_provider(self):
        if not self.providers:
            return
        idx = self.current_idx
        name = self.providers[idx]["name"]
        if messagebox.askyesno("删除", "删除服务商「%s」?" % name, parent=self):
            del self.providers[idx]
            if self.default_model.startswith(name + "/"):
                self.default_model = ""
                self.default_box.set("")
            self._refresh_list()
            if self.providers:
                idx = min(idx, len(self.providers) - 1)
                self.listbox.selection_set(idx)
                self._load_provider(idx)
            else:
                for var in self.fields.values():
                    var.set("")

    def _refresh_default_box(self):
        vals = ["%s/%s" % (p["name"], m)
                for p in self.providers for m in p.get("models", [])]
        self.default_box["values"] = vals

    def _test(self):
        sel = self.default_box.get()
        if "/" not in sel:
            messagebox.showwarning("提示", "请先选择默认模型", parent=self)
            return
        pname, model = sel.split("/", 1)
        provider = next((p for p in self.providers if p["name"] == pname), None)
        if not provider:
            messagebox.showwarning("提示", "找不到服务商", parent=self)
            return
        self._save()  # 先保存再测试
        self.app.status_var.set("正在测试 %s…" % sel)

        def worker():
            try:
                text = call_chat_api(provider, model, "请只回复:OK", False)
                msg = "连接成功,模型返回:%s" % (text.strip()[:100] or "(空)")
            except Exception as exc:  # noqa: BLE001
                msg = "连接失败:%s" % exc
            self.after(0, lambda: messagebox.showinfo("测试结果", msg, parent=self))
            self.after(0, lambda: self.app.status_var.set("就绪"))

        threading.Thread(target=worker, daemon=True).start()

    def _save(self):
        for p in self.providers:
            if not p.get("name"):
                p["name"] = "未命名"
        self.app.config["providers"] = self.providers
        self.app.config["default_model"] = self.default_box.get()
        save_json(CONFIG_PATH, self.app.config)
        self.app.status_var.set("模型设置已保存")
        self.destroy()


def main():
    app = App()
    # 启动时对已超时未提醒的条目补弹窗(由 _tick 首次运行触发)
    app.mainloop()


if __name__ == "__main__":
    main()
