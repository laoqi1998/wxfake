package com.example.wxfake;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * WxFakeLocation 配置界面。
 * - 总开关：切换立即保存，下一次定位请求即生效
 * - 桌面隐藏：图标消失，通知栏常驻「配置入口」可随时回来；LSPosed 模块状态不受影响
 * - 当前生效状态卡：实时显示微信此刻读到的伪造内容
 * - 位置：手填 / 地图选点（含"我的位置"一键定位）/ 历史位置勾选（自动记录）
 * - WiFi：手填 / 读取当前连接 / 列表勾选（root 读已保存网络 + 扫描附近网络）
 */
public class MainActivity extends Activity {

    private static final String[] KEYS   = {"lat", "lng", "acc", "addr", "ssid", "bssid", "rssi", "caps"};
    private static final String[] LABELS = {
            "纬度 latitude", "经度 longitude", "精度（米，填 10-50）",
            "位置名称（给小程序看的地址描述）",
            "WiFi 名称 SSID", "WiFi 的 MAC 地址 BSSID", "信号强度 dBm（负数，如 -45）",
            "加密能力串（保持默认即可）"
    };
    private static final String[] DEFS = {
            "31.2304", "121.4737", "15", "上海市黄浦区人民广场",
            "MyHomeWiFi", "aa:bb:cc:dd:ee:ff", "-45", "[WPA2-PSK-CCMP][ESS]"
    };

    private static final int REQ_MAP = 100;
    private static final int REQ_PERM = 101;
    private static final String HIST_KEY = "loc_history";   // 行格式: 名称|纬度|经度
    private static final int HIST_MAX = 30;

    private SharedPreferences sp;
    private final EditText[] inputs = new EditText[KEYS.length];
    private final int latIdx = 0, lngIdx = 1;
    private TextView curInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sp = getSharedPreferences("config", Context.MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF2F3F5);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("WxFakeLocation · 微信定位伪造");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, 6);
        root.addView(title);

        // ---------- 总开关 ----------
        Switch sw = new Switch(this);
        sw.setText("启用伪造");
        sw.setTextSize(16);
        sw.setChecked(sp.getString("enabled", "false").equals("true"));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putString("enabled", checked ? "true" : "false").apply();
            makeConfigReadable();   // ★ 开关状态也要让微信读到！
            Toast.makeText(this, checked ? "已开启 ✅ 下一次定位请求生效" : "已关闭 ❌ 恢复真实数据",
                    Toast.LENGTH_SHORT).show();
            refreshCurInfo();
        });
        root.addView(sw);

        // ---------- 桌面隐藏 ----------
        Switch hide = new Switch(this);
        hide.setText("隐藏桌面图标（通知栏保留配置入口）");
        hide.setTextSize(14);
        hide.setChecked(sp.getString("hidden", "false").equals("true"));
        hide.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putString("hidden", checked ? "true" : "false").apply();
            makeConfigReadable();   // 每次写配置后都放开权限
            if (checked) {
                applyIconHidden();
                postNotif();
                Toast.makeText(this, "已隐藏 ✅ 模块照常工作；下拉通知点「配置入口」回来", Toast.LENGTH_LONG).show();
                root.postDelayed(this::finishAndRemoveTask, 1500);
            } else {
                applyIconVisible();
                cancelNotif();
                Toast.makeText(this, "已恢复显示 ✅ 图标回桌面", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(hide);

        // ---------- 当前生效状态卡 ----------
        curInfo = new TextView(this);
        curInfo.setTypeface(Typeface.MONOSPACE);
        curInfo.setTextSize(13);
        curInfo.setTextColor(0xFF1B5E20);
        curInfo.setBackgroundColor(Color.WHITE);
        curInfo.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        root.addView(curInfo);

        TextView tip = new TextView(this);
        tip.setText("上方卡片 = 微信此刻实际读到的伪造内容（以「保存配置」为准）。\n"
                + "开关和坐标改动 1 秒内被微信读到，不用重启微信。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF666666);
        tip.setPadding(0, 4, 0, pad / 2);
        root.addView(tip);

        // ---------- 位置区 ----------
        root.addView(sectionLabel("📍 位置", pad));
        for (int i = 0; i < 4; i++) addInput(root, i, pad);

        Button mapBtn = new Button(this);
        mapBtn.setText("打开地图选点（含「我的位置」）");
        mapBtn.setAllCaps(false);
        mapBtn.setOnClickListener(v -> {
            try {
                Intent it = new Intent(this, MapPickerActivity.class);
                it.putExtra("lat", cur(latIdx));
                it.putExtra("lng", cur(lngIdx));
                startActivityForResult(it, REQ_MAP);
            } catch (Throwable e) {
                Toast.makeText(this, "地图打开失败：" + e, Toast.LENGTH_LONG).show();
            }
        });
        root.addView(mapBtn, matchWrap());

        Button histBtn = new Button(this);
        histBtn.setText("历史位置（自动记录，可勾选）");
        histBtn.setAllCaps(false);
        histBtn.setOnClickListener(v -> showHistoryDialog());
        root.addView(histBtn, matchWrap());

        // ---------- WiFi 区（勾选/读取后自动保存） ----------
        root.addView(sectionLabel("📶 WiFi", pad));
        for (int i = 4; i < KEYS.length; i++) addInput(root, i, pad);

        Button listBtn = new Button(this);
        listBtn.setText("从 WiFi 列表勾选（历史+附近）");
        listBtn.setAllCaps(false);
        listBtn.setOnClickListener(v -> {
            if (ensurePerms()) showWifiPicker();
        });
        root.addView(listBtn, matchWrap());

        Button wifiBtn = new Button(this);
        wifiBtn.setText("读取当前已连接的 WiFi");
        wifiBtn.setAllCaps(false);
        wifiBtn.setOnClickListener(v -> {
            if (ensurePerms()) readCurrentWifi();
        });
        root.addView(wifiBtn, matchWrap());

        // ---------- 高德 Key 二级页 ----------
        Button keyBtn = new Button(this);
        keyBtn.setText("⚙️ 高德地图设置（Key / 安全密钥）");
        keyBtn.setAllCaps(false);
        keyBtn.setOnClickListener(v -> showAmapKeyDialog());
        root.addView(keyBtn, matchWrap());

        // ---------- 保存 ----------
        Button save = new Button(this);
        save.setText("保存配置");
        save.setTextSize(16);
        save.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = pad;
        save.setLayoutParams(bp);
        save.setOnClickListener(v -> saveAll());
        root.addView(save);

        TextView foot = new TextView(this);
        foot.setText("WiFi/位置勾选后自动保存并生效；手动改输入框需点「保存配置」。\n"
                + "隐藏桌面后：下拉通知「WxFakeLocation 运行中」或 LSPosed ▶️ 拉回。");
        foot.setTextSize(12);
        foot.setTextColor(0xFF999999);
        foot.setPadding(0, pad, 0, 0);
        root.addView(foot);

        refreshCurInfo();
    }

    // ================= 高德 Key 二级弹窗 =================
    private void showAmapKeyDialog() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView l1 = new TextView(this);
        l1.setText("Key（lbs.amap.com 免费申请，服务平台选「Web端(JS API)」）");
        l1.setTextSize(12);
        l1.setTextColor(0xFF666666);
        box.addView(l1);

        final EditText keyIn = new EditText(this);
        keyIn.setText(sp.getString("amap_key", ""));
        keyIn.setSingleLine(true);
        keyIn.setHint("32位Key");
        keyIn.setTextSize(14);
        box.addView(keyIn);

        TextView l2 = new TextView(this);
        l2.setText("安全密钥 jscode（2021年后的 Key 配套，同页可见）");
        l2.setTextSize(12);
        l2.setTextColor(0xFF666666);
        l2.setPadding(0, pad / 2, 0, 0);
        box.addView(l2);

        final EditText secIn = new EditText(this);
        secIn.setText(sp.getString("amap_sec", ""));
        secIn.setSingleLine(true);
        secIn.setHint("安全密钥");
        secIn.setTextSize(14);
        box.addView(secIn);

        new AlertDialog.Builder(this)
                .setTitle("⚙️ 高德地图设置")
                .setView(box)
                .setPositiveButton("保存", (dlg, w) -> {
                    sp.edit()
                            .putString("amap_key", keyIn.getText().toString().trim())
                            .putString("amap_sec", secIn.getText().toString().trim())
                            .apply();
                    makeConfigReadable();
                    Toast.makeText(this, "已保存 ✅ 地图选点将走官方源", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurInfo();
    }

    // ================= 当前生效状态卡 =================
    private void refreshCurInfo() {
        if (curInfo == null || sp == null) return;
        boolean on = sp.getString("enabled", "false").equals("true");
        curInfo.setText("【当前生效（微信读到）】\n"
                + "总开关: " + (on ? "✅ 开启（伪造注入中）" : "⛔ 关闭（微信读到真实位置/WiFi）") + "\n"
                + "待命位置: " + sp.getString("lat", "-") + ", " + sp.getString("lng", "-") + "\n"
                + "待命名称: " + sp.getString("addr", "-") + "\n"
                + "待命WiFi: " + sp.getString("ssid", "-") + "  (" + sp.getString("bssid", "-") + ")"
                + (on ? "" : "\n\n提示：打开顶部「启用伪造」开关后，上方待命值才会注入微信。"));
    }

    // ================= 工具 =================
    private double cur(int idx) {
        try {
            return Double.parseDouble(inputs[idx].getText().toString().trim());
        } catch (Exception e) {
            return Double.parseDouble(DEFS[idx]);
        }
    }

    private TextView sectionLabel(String text, int pad) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, pad, 0, 4);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addInput(LinearLayout root, int i, int pad) {
        TextView label = new TextView(this);
        label.setText(LABELS[i]);
        label.setTextSize(13);
        label.setTextColor(0xFF333333);
        label.setPadding(0, pad / 2, 0, 4);
        root.addView(label);

        EditText et = new EditText(this);
        et.setText(sp.getString(KEYS[i], DEFS[i]));
        et.setTextSize(14);
        et.setSingleLine(true);
        if ("lat".equals(KEYS[i]) || "lng".equals(KEYS[i]) || "acc".equals(KEYS[i]) || "rssi".equals(KEYS[i])) {
            et.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        root.addView(et);
        inputs[i] = et;
    }

    private void saveAll() {
        try {
            Double.parseDouble(inputs[0].getText().toString().trim());
            Double.parseDouble(inputs[1].getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "纬度/经度必须是数字，例如 31.2304", Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences.Editor ed = sp.edit();
        for (int i = 0; i < KEYS.length; i++) {
            String val = inputs[i].getText().toString().trim();
            if (val.isEmpty()) val = DEFS[i];
            ed.putString(KEYS[i], val);
        }
        // 高德 Key / 安全密钥
        // Key/密钥在二级页保存，此处不再动
        // 只存待命值；开关状态保持用户手动拨的（不再自动开启）
        ed.apply();
        // ★ 核心修复：配置文件必须世界可读，微信的 XSharedPreferences 才能读到！
        //   MODE_PRIVATE 建出的文件权限 600，微信读到的永远是空 → 全部回退默认值。
        //   有 root：chmod 放开（最稳）；无 root：靠 LSPosed 的 xposedsharedprefs 声明。
        makeConfigReadable();
        addHistory(inputs[3].getText().toString().trim(), cur(latIdx), cur(lngIdx));
        refreshCurInfo();
        boolean on = sp.getString("enabled", "false").equals("true");
        Toast.makeText(this, on
                ? "已保存 ✅ 开关开启中，微信立刻读新值"
                : "已保存（待命）⛔ 开关是关的——要注入请拨顶部「启用伪造」", Toast.LENGTH_LONG).show();
    }

    /**
     * 把 shared_prefs/config.xml 权限放开（root chmod）。
     * 这是「微信读不到配置 → 伪造不生效」的根治手段。
     */
    private void makeConfigReadable() {
        try {
            String f = getFilesDir().getParentFile().getAbsolutePath()
                    + "/shared_prefs/config.xml";
            runRootD("chmod 664 '" + f + "' && chown " + getPackageName() + ":" + getPackageName()
                    + " '" + f + "' && chmod 671 '" + getFilesDir().getParentFile().getAbsolutePath()
                    + "/shared_prefs'");
        } catch (Throwable ignored) {
        }
    }

    // ================= 历史位置 =================
    private void addHistory(String name, double lat, double lng) {
        if (name == null || name.isEmpty()) name = "位置点";
        String key = String.format("%.5f|%.5f", lat, lng);
        ArrayList<String[]> list = getHistory();
        ArrayList<String> lines = new ArrayList<>();
        lines.add(name + "|" + String.format("%.6f", lat) + "|" + String.format("%.6f", lng));
        for (String[] e : list) {
            String ek = String.format("%.5f|%.5f", d(e[1]), d(e[2]));
            if (!ek.equals(key)) lines.add(e[0] + "|" + e[1] + "|" + e[2]);
        }
        while (lines.size() > HIST_MAX) lines.remove(lines.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        sp.edit().putString(HIST_KEY, sb.toString()).apply();
    }

    private ArrayList<String[]> getHistory() {
        ArrayList<String[]> out = new ArrayList<>();
        String raw = sp.getString(HIST_KEY, "");
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length == 3) out.add(p);
        }
        return out;
    }

    private static double d(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private void showHistoryDialog() {
        final ArrayList<String[]> list = getHistory();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad8 = (int) (8 * getResources().getDisplayMetrics().density);
        String curLat = sp.getString("lat", "");
        String curLng = sp.getString("lng", "");
        final AlertDialog[] holder = new AlertDialog[1];

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有记录。地图选定一个点、或点「保存配置」后自动记入。");
            empty.setPadding(pad8 * 2, pad8 * 2, pad8 * 2, pad8 * 2);
            box.addView(empty);
        }
        for (final String[] e : list) {
            boolean isCur = String.format("%.5f", d(e[1])).equals(String.format("%.5f", d(curLat)))
                    && String.format("%.5f", d(e[2])).equals(String.format("%.5f", d(curLng)));
            TextView row = new TextView(this);
            row.setText((isCur ? "✅ " : "○ ") + e[0] + "\n    " + e[1] + ", " + e[2]);
            row.setTextSize(14);
            row.setPadding(pad8 * 2, pad8, pad8 * 2, pad8);
            row.setBackgroundColor(isCur ? 0xFFE8F5E9 : Color.TRANSPARENT);
            row.setOnClickListener(v -> {
                inputs[latIdx].setText(e[1]);
                inputs[lngIdx].setText(e[2]);
                if (e[0].startsWith("位置点")) inputs[3].setText(e[0]);
                // 勾选即保存
                SharedPreferences.Editor ed = sp.edit();
                for (int i = 0; i < KEYS.length; i++) {
                    String val = inputs[i].getText().toString().trim();
                    if (val.isEmpty()) val = DEFS[i];
                    ed.putString(KEYS[i], val);
                }
                ed.apply();
                makeConfigReadable();
                refreshCurInfo();
                if (holder[0] != null) holder[0].dismiss();
                Toast.makeText(this, "已切到「" + e[0] + "」并保存 ✅ 开关开着即生效", Toast.LENGTH_LONG).show();
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("历史位置（✅=当前生效，点即切换）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空历史", (dlg, w) -> {
                    sp.edit().remove(HIST_KEY).apply();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ================= WiFi 列表勾选（历史 + 附近） =================
    /**
     * root 执行结果（含诊断信息）
     */
    private static class RootResult {
        ArrayList<String> out = new ArrayList<>();
        String diag = "";
        boolean suOk = false;
    }

    /**
     * root 执行命令。修复三个坑：
     * 1) stderr 不读会撑满缓冲区导致 waitFor 永久死锁 → 双流各起线程收
     * 2) su 挂起无响应 → 8 秒超时强杀
     * 3) 输出按 UTF-8 显式解码（默认字符集在部分 ROM 上不是 UTF-8 → 中文乱码）
     */
    private RootResult runRootD(String cmd) {
        RootResult rr = new RootResult();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final java.io.InputStream es = p.getErrorStream();
            final java.io.InputStream is = p.getInputStream();
            final StringBuilder errB = new StringBuilder();

            Thread te = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(es, java.nio.charset.StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        synchronized (errB) { errB.append(l).append('\n'); }
                    }
                } catch (Throwable ignored) {}
            });
            te.start();

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) rr.out.add(line);
            r.close();

            boolean done = false;
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try { p.exitValue(); done = true; break; }
                catch (IllegalThreadStateException e) { Thread.sleep(150); }
            }
            if (!done) { p.destroyForcibly(); rr.diag += "超时强杀; "; }
            te.join(500);

            rr.suOk = true;
            synchronized (errB) {
                if (errB.length() > 0) rr.diag += "stderr[" + errB.toString().trim() + "]";
            }
        } catch (Throwable t) {
            rr.diag += "EXC:" + t;
        }
        return rr;
    }

    private ArrayList<String> runRoot(String cmd) {
        return runRootD(cmd).out;
    }

    private static String extractSsid(String line) {
        int i = line.indexOf("SSID=\"");
        if (i < 0) return null;
        int s = i + 6;
        int e = line.indexOf('"', s);
        if (e <= s) return null;
        return line.substring(s, e);
    }

    /**
     * 解码 WifiConfigStore 里的 SSID 值：
     * - "名字"（带引号）→ 去引号
     * - 0xE4B8AD...（UTF-8 字节序列，中文WiFi常这样存）→ 解码回中文
     */
    private static String decodeSsidValue(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                String hex = s.substring(2).replace(" ", "");
                byte[] b = new byte[hex.length() / 2];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return new String(b, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable t) {
                return null;
            }
        }
        return s.trim();
    }

    /** 由 SSID 生成稳定假 MAC（本地管理位 02 开头，同一 SSID 永远同一 MAC，利于一致性） */
    private static String macFromSsid(String s) {
        int h = s.hashCode();
        return String.format("02:%02x:%02x:%02x:%02x:%02x",
                (h >>> 24) & 0xFF, (h >>> 16) & 0xFF, (h >>> 8) & 0xFF, h & 0xFF, (s.length() * 31) & 0xFF);
    }

    private void showWifiPicker() {
        final ArrayList<String[]> rows = new ArrayList<>();  // {ssid, bssid, rssi, caps, tag}
        final LinkedHashSet<String> seen = new LinkedHashSet<>();

        // 1) 附近扫描到的网络（真实 BSSID）
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                try { wm.startScan(); } catch (Throwable ignored) {}
                for (ScanResult sr : wm.getScanResults()) {
                    if (sr.SSID == null || sr.SSID.isEmpty()) continue;
                    String k = sr.BSSID;
                    if (seen.add(k)) {
                        rows.add(new String[]{sr.SSID, sr.BSSID, String.valueOf(sr.level),
                                sr.capabilities == null ? "[WPA2-PSK-CCMP][ESS]" : sr.capabilities, "📡 附近"});
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) 已保存/历史连接的网络（root，四路：cmd 命令 + 三个存储路径）
        //    诊断串拼进空列表提示，读不到时能看到死在哪一步
        ArrayList<String> savedSsids = new ArrayList<>();
        StringBuilder rootDiag = new StringBuilder();
        RootResult rr1 = runRootD("cmd wifi list-networks");
        rootDiag.append("cmd:").append(rr1.out.size()).append("行").append(rr1.diag.isEmpty() ? "" : "(" + rr1.diag + ")");
        for (String line : rr1.out) {
            final String ssid = extractSsid(line);
            if (ssid != null && !ssid.isEmpty()) savedSsids.add(ssid);
        }
        // WifiConfigStore.xml 可能整文件单行，不能按行读 → 读全部字节再正则
        for (String[] path : new String[][]{
                {"cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml", "apex"},
                {"cat /data/misc/apexdata/com.android.wifi/WifiConfigStore2.xml", "apex2"},
                {"cat /data/misc/wifi/WifiConfigStore.xml", "legacy"}}) {
            RootResult rr = runRootD(path[0]);
            String blob = String.join("\n", rr.out);
            int found = 0;
            // SSID 在文件里两种存法：<string name="SSID">"名字"</string>
            // 中文名可能存成 UTF-8 字节序列 0xE4B8AD... → 都处理
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("<string name=\"SSID\">(.*?)</string>")
                    .matcher(blob);
            while (mm.find()) {
                String s = decodeSsidValue(mm.group(1));
                if (s != null && !s.isEmpty()) { savedSsids.add(s); found++; }
            }
            rootDiag.append(" | ").append(path[1]).append(":").append(found).append("个")
                    .append(rr.out.isEmpty() ? "(0B" + (rr.diag.isEmpty() ? "" : "," + rr.diag) + ")" : "");
        }
        for (String ssid : savedSsids) {
            if (ssid == null || ssid.isEmpty()) continue;
            if (seen.add("saved:" + ssid)) {
                rows.add(new String[]{ssid, macFromSsid(ssid), "-50",
                        "[WPA2-PSK-CCMP][ESS]", "💾 已保存(历史)"});
            }
        }

        // 3) 弹窗列表
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad8 = (int) (8 * getResources().getDisplayMetrics().density);
        String curSsid = sp.getString("ssid", "");
        String curBssid = sp.getString("bssid", "");

        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没读到任何网络。\n· 附近网络：请开 WiFi 和定位服务\n· 历史网络 root 诊断：\n"
                    + rootDiag.toString()
                    + "\n\n若各行都是0：Magisk → 超级用户 → 确认 WxFakeLocation 已允许，\n"
                    + "且「命名空间切换」设为全局；HyperOS 还需给 root 授权记录里勾「信任该应用」。");
            empty.setTextSize(13);
            empty.setPadding(pad8 * 2, pad8 * 2, pad8 * 2, pad8 * 2);
            box.addView(empty);
        }
        for (final String[] r : rows) {
            boolean isCur = r[0].equals(curSsid) || (curBssid != null && curBssid.equalsIgnoreCase(r[1]));
            TextView row = new TextView(this);
            row.setText((isCur ? "✅ " : "") + r[4] + "  " + r[0]
                    + "\n    " + r[1] + "   " + r[2] + " dBm");
            row.setTextSize(14);
            row.setTypeface(Typeface.MONOSPACE);
            row.setPadding(pad8 * 2, pad8, pad8 * 2, pad8);
            row.setBackgroundColor(isCur ? 0xFFE8F5E9 : Color.TRANSPARENT);
            row.setOnClickListener(v -> {
                inputs[4].setText(r[0]);
                inputs[5].setText(r[1]);
                if (!"-50".equals(r[2])) inputs[6].setText(r[2]);
                inputs[7].setText(r[3]);
                // 勾选即保存（避免忘点保存导致"不生效"）
                SharedPreferences.Editor ed = sp.edit();
                for (int i = 0; i < KEYS.length; i++) {
                    String val = inputs[i].getText().toString().trim();
                    if (val.isEmpty()) val = DEFS[i];
                    ed.putString(KEYS[i], val);
                }
                ed.apply();
                makeConfigReadable();
                refreshCurInfo();
                dlg.dismiss();
                Toast.makeText(this, "已选「" + r[0] + "」并保存 ✅ 开关开着即生效", Toast.LENGTH_LONG).show();
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("选择 WiFi（✅=当前生效）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .setNeutralButton("重新扫描", (dlg, w) -> showWifiPicker())
                .show();
    }

    // ================= 图标隐藏/显示 =================
    // 主 activity 自带 LAUNCHER filter 但默认 enabled=false（不上桌面）；
    // 桌面图标由别名 .MainAlias 提供。隐藏 = 停用别名。
    // 隐藏后入口：① LSPosed 模块详情页 ▶️ 播放按钮（显式 intent 拉起主 activity）
    //           ② 通知栏常驻「配置入口」 ③ 设置→应用→打开

    /** 隐藏桌面图标：停用别名 + 启用主 activity（保证 LSPosed ▶️ 可拉起） */
    private void applyIconHidden() {
        try {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(new ComponentName(this, "com.example.wxfake.MainAlias"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(new ComponentName(this, "com.example.wxfake.MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Toast.makeText(this, "隐藏失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 恢复桌面图标：启用别名 + 停用主 activity（桌面只显示一个图标） */
    private void applyIconVisible() {
        try {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(new ComponentName(this, "com.example.wxfake.MainAlias"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(new ComponentName(this, "com.example.wxfake.MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }
    }

    // ================= 通知栏常驻入口 =================
    private void postNotif() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "entry", "配置入口", android.app.NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);

            Intent it = new Intent(this, MainActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification n = new android.app.Notification.Builder(this, "entry")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("WxFakeLocation 运行中")
                    .setContentText("点此打开配置界面（定位伪造生效中）")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            nm.notify(1001, n);
        } catch (Throwable ignored) {
        }
    }

    private void cancelNotif() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(1001);
        } catch (Throwable ignored) {
        }
    }

    // ================= 权限 =================
    private boolean ensurePerms() {
        boolean fineOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean nearbyOk = android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                == PackageManager.PERMISSION_GRANTED;
        boolean notifOk = android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        if (fineOk && nearbyOk) return true;

        ArrayList<String> want = new ArrayList<>();
        want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!notifOk) want.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(want.toArray(new String[0]), REQ_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            boolean anyGranted = false;
            for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) anyGranted = true;
            if (anyGranted) {
                showWifiPicker();
            } else {
                Toast.makeText(this, "定位权限被拒 → 读不了 WiFi；去 设置→应用→WxFakeLocation→权限 手动开", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ================= 读取当前连接 =================
    private void readCurrentWifi() {
        String ssid = null, bssid = null;
        int rssi = -200;
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    getSystemService(Context.LOCATION_SERVICE);
            boolean locSvcOn = lm != null
                    && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!locSvcOn) {
                Toast.makeText(this, "先开定位服务（下拉快捷开关「定位信息」），再点本按钮（系统规定）", Toast.LENGTH_LONG).show();
                return;
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = (wm == null) ? null : wm.getConnectionInfo();
            if (info != null) {
                ssid = info.getSSID();
                bssid = info.getBSSID();
                rssi = info.getRssi();
            }
            if (ssid == null || ssid.contains("unknown") || bssid == null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                && nc.getTransportInfo() instanceof WifiInfo) {
                            WifiInfo w2 = (WifiInfo) nc.getTransportInfo();
                            String s2 = w2.getSSID();
                            String b2 = w2.getBSSID();
                            if (s2 != null && !s2.contains("unknown") && b2 != null) {
                                ssid = s2;
                                bssid = b2;
                                rssi = w2.getRssi();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (ssid == null || ssid.contains("unknown") || bssid == null) {
            Toast.makeText(this, "读不到（可能没连WiFi/连的热点）→ 试试「从 WiFi 列表勾选」", Toast.LENGTH_LONG).show();
            return;
        }
        inputs[4].setText(trimQuotes(ssid));
        inputs[5].setText(bssid);
        inputs[6].setText(String.valueOf(rssi));
        // 勾选即保存
        SharedPreferences.Editor ed = sp.edit();
        for (int i = 0; i < KEYS.length; i++) {
            String val = inputs[i].getText().toString().trim();
            if (val.isEmpty()) val = DEFS[i];
            ed.putString(KEYS[i], val);
        }
        ed.apply();
        makeConfigReadable();
        refreshCurInfo();
        Toast.makeText(this, "已读取并保存 ✅ 开关开着即生效", Toast.LENGTH_LONG).show();
    }

    private static String trimQuotes(String s) {
        return s == null ? "" : s.replaceAll("^\"+|\"+$", "");
    }

    // ================= 地图回填 + 自动保存（选定即生效，不再要求手动保存） =================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null && data.hasExtra("lat")) {
            double lat = data.getDoubleExtra("lat", 0);
            double lng = data.getDoubleExtra("lng", 0);
            String addr = data.getStringExtra("addr");
            inputs[latIdx].setText(String.valueOf(lat));
            inputs[lngIdx].setText(String.valueOf(lng));
            if (addr != null && !addr.trim().isEmpty()) {
                inputs[3].setText(addr.trim());
            }
            // 自动保存全部待命值（WiFi 等其余字段维持当前输入框内容）
            SharedPreferences.Editor ed = sp.edit();
            for (int i = 0; i < KEYS.length; i++) {
                String val = inputs[i].getText().toString().trim();
                if (val.isEmpty()) val = DEFS[i];
                ed.putString(KEYS[i], val);
            }
            // Key/密钥在二级页保存，此处不动
            ed.apply();
            makeConfigReadable();   // ★ 同样必须放开权限，否则微信读不到
            addHistory(inputs[3].getText().toString().trim(), lat, lng);
            refreshCurInfo();
            Toast.makeText(this, "已选定并保存 ✅ 伪造开着的话微信立刻读到新位置", Toast.LENGTH_LONG).show();
        }
    }
}
