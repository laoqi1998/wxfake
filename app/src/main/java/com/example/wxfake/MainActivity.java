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
        sw.setChecked(sp.getString("enabled", "true").equals("true"));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putString("enabled", checked ? "true" : "false").apply();
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

        // ---------- WiFi 区 ----------
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
        foot.setText("隐藏桌面后：下拉通知点「WxFakeLocation 运行中」即可回来；LSPosed 模块状态不受影响。");
        foot.setTextSize(12);
        foot.setTextColor(0xFF999999);
        foot.setPadding(0, pad, 0, 0);
        root.addView(foot);

        refreshCurInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurInfo();
    }

    // ================= 当前生效状态卡 =================
    private void refreshCurInfo() {
        if (curInfo == null || sp == null) return;
        boolean on = sp.getString("enabled", "true").equals("true");
        curInfo.setText("【当前生效（微信读到）】\n"
                + "总开关: " + (on ? "✅ 开启" : "❌ 关闭（透传真实）") + "\n"
                + "位置: " + sp.getString("lat", "-") + ", " + sp.getString("lng", "-") + "\n"
                + "名称: " + sp.getString("addr", "-") + "\n"
                + "WiFi: " + sp.getString("ssid", "-") + "  (" + sp.getString("bssid", "-") + ")");
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
        ed.putString("enabled", "true");
        ed.apply();
        addHistory(inputs[3].getText().toString().trim(), cur(latIdx), cur(lngIdx));
        refreshCurInfo();
        Toast.makeText(this, "已保存 ✅ 已自动开启伪造，并记入历史位置", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "已填入，记得点保存", Toast.LENGTH_SHORT).show();
                refreshCurInfo();
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("历史位置（★当前生效的已高亮）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空历史", (dlg, w) -> {
                    sp.edit().remove(HIST_KEY).apply();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ================= WiFi 列表勾选（历史 + 附近） =================
    private ArrayList<String> runRoot(String cmd) {
        ArrayList<String> out = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.add(line);
            r.close();
            p.waitFor();
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String extractSsid(String line) {
        int i = line.indexOf("SSID=\"");
        if (i < 0) return null;
        int s = i + 6;
        int e = line.indexOf('"', s);
        if (e <= s) return null;
        return line.substring(s, e);
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

        // 2) 已保存/历史连接的网络（root 读取，无真实 BSSID → 用稳定生成 MAC）
        try {
            for (String line : runRoot("cmd wifi list-networks")) {
                final String ssid = extractSsid(line);
                if (ssid == null || ssid.isEmpty()) continue;
                if (seen.add("saved:" + ssid)) {
                    rows.add(new String[]{ssid, macFromSsid(ssid), "-50",
                            "[WPA2-PSK-CCMP][ESS]", "💾 已保存(历史)"});
                }
            }
        } catch (Throwable ignored) {
        }

        // 3) 弹窗列表
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad8 = (int) (8 * getResources().getDisplayMetrics().density);
        String curSsid = sp.getString("ssid", "");
        String curBssid = sp.getString("bssid", "");

        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没读到任何网络。\n· 附近网络：请开 WiFi 和定位服务\n· 历史/已保存：需要给本 App 授权 root（Magisk 弹窗点允许）");
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
                Toast.makeText(this, "已填入「" + r[0] + "」，记得点保存", Toast.LENGTH_SHORT).show();
                refreshCurInfo();
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
        Toast.makeText(this, "已填入当前 WiFi，记得点保存", Toast.LENGTH_SHORT).show();
    }

    private static String trimQuotes(String s) {
        return s == null ? "" : s.replaceAll("^\"+|\"+$", "");
    }

    // ================= 地图回填 + 自动记录 =================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null && data.hasExtra("lat")) {
            double lat = data.getDoubleExtra("lat", 0);
            double lng = data.getDoubleExtra("lng", 0);
            inputs[latIdx].setText(String.valueOf(lat));
            inputs[lngIdx].setText(String.valueOf(lng));
            addHistory(inputs[3].getText().toString().trim(), lat, lng);
            refreshCurInfo();
            Toast.makeText(this, "已填入并记入历史位置，记得点保存", Toast.LENGTH_SHORT).show();
        }
    }
}
