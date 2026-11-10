package com.example.wxfake;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
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

/**
 * WxFakeLocation 配置界面（桌面图标点开即是）。
 * - 总开关：切换立即保存，下一次定位请求即生效（无需重启微信）
 * - 位置：手动填 或 地图选点（高德瓦片，免 key）
 * - WiFi：手动填 或 一键读取当前已连接 WiFi
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

    private SharedPreferences sp;
    private final EditText[] inputs = new EditText[KEYS.length];
    private int latIdx, lngIdx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 读 WiFi 真实信息需要定位类权限（Android 9+ 系统要求）
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
        }, 1);

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

        // ---------- 总开关（切换即时生效） ----------
        Switch sw = new Switch(this);
        sw.setText("启用伪造");
        sw.setTextSize(16);
        sw.setChecked(sp.getString("enabled", "true").equals("true"));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton btn, boolean checked) {
                getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                        .putString("enabled", checked ? "true" : "false").apply();
                Toast.makeText(MainActivity.this,
                        checked ? "已开启 ✅ 下一次定位请求生效" : "已关闭 ❌ 恢复真实数据", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(sw);

        TextView tip = new TextView(this);
        tip.setText("开关和坐标改动都会在 1 秒内被微信读到，不用重启微信。\n"
                + "地图选点得到的是国内地图坐标（GCJ-02），选哪显示哪。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF666666);
        tip.setPadding(0, 4, 0, pad / 2);
        root.addView(tip);

        // ---------- 定位区 ----------
        root.addView(sectionLabel("📍 位置", pad));
        latIdx = 0;
        lngIdx = 1;
        for (int i = 0; i < 4; i++) addInput(root, i, pad);

        Button mapBtn = new Button(this);
        mapBtn.setText("打开地图选点");
        mapBtn.setAllCaps(false);
        mapBtn.setOnClickListener(v -> {
            Intent it = new Intent(this, MapPickerActivity.class);
            it.putExtra("lat", cur(latIdx));
            it.putExtra("lng", cur(lngIdx));
            startActivityForResult(it, REQ_MAP);
        });
        root.addView(mapBtn, matchWrap());

        // ---------- WiFi 区 ----------
        root.addView(sectionLabel("📶 WiFi", pad));
        for (int i = 4; i < KEYS.length; i++) addInput(root, i, pad);

        Button wifiBtn = new Button(this);
        wifiBtn.setText("读取当前已连接的 WiFi");
        wifiBtn.setAllCaps(false);
        wifiBtn.setOnClickListener(v -> readCurrentWifi());
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
        foot.setText("验证：微信发朋友圈看定位 / 小程序触发定位功能。若没生效，划掉微信重开一次。");
        foot.setTextSize(12);
        foot.setTextColor(0xFF999999);
        foot.setPadding(0, pad, 0, 0);
        root.addView(foot);
    }

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
        Toast.makeText(this, "已保存 ✅ 已自动开启伪造", Toast.LENGTH_LONG).show();
    }

    // ---------------- 一键读取当前 WiFi ----------------
    private void readCurrentWifi() {
        String ssid = null, bssid = null;
        int rssi = 0;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                ssid = info.getSSID();
                bssid = info.getBSSID();
                rssi = info.getRssi();
            }
            // Android 12+ 备用路线：从 NetworkCapabilities 里拿 WifiInfo
            if (ssid == null || ssid.contains("unknown")) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                && nc.getTransportInfo() instanceof WifiInfo) {
                            WifiInfo w2 = (WifiInfo) nc.getTransportInfo();
                            ssid = w2.getSSID();
                            bssid = w2.getBSSID();
                            rssi = w2.getRssi();
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (ssid == null || ssid.contains("unknown") || bssid == null) {
            Toast.makeText(this, "系统限制了读取（未连WiFi或定位权限没给）→ 请手动填", Toast.LENGTH_LONG).show();
            return;
        }
        inputs[4].setText(trimQuotes(ssid));
        inputs[5].setText(bssid);
        inputs[6].setText(String.valueOf(rssi));
        Toast.makeText(this, "已填入当前 WiFi 信息，记得点保存", Toast.LENGTH_SHORT).show();
    }

    private static String trimQuotes(String s) {
        return s == null ? "" : s.replaceAll("^\"+|\"+$", "");
    }

    // ---------------- 地图选点回填 ----------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null) {
            inputs[latIdx].setText(String.valueOf(data.getDoubleExtra("lat", 0)));
            inputs[lngIdx].setText(String.valueOf(data.getDoubleExtra("lng", 0)));
            Toast.makeText(this, "已填入选点坐标，记得点保存", Toast.LENGTH_SHORT).show();
        }
    }
}

/**
 * 全屏地图选点页：WebView 加载 assets/map.html（高德瓦片，无需任何 key）。
 * 拖动地图 → 中心十字对准目标 → 点「选定」回传中心点坐标。
 */
class MapPickerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.webkit.WebView web = new android.webkit.WebView(this);
        android.webkit.WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);

        double lat = getIntent().getDoubleExtra("lat", 31.2304);
        double lng = getIntent().getDoubleExtra("lng", 121.4737);
        web.loadUrl("file:///android_asset/map.html?lat=" + lat + "&lng=" + lng);
    }

    class Bridge {
        @android.webkit.JavascriptInterface
        public void mapPick(final String lat, final String lng) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent data = new Intent();
                    try {
                        data.putExtra("lat", Double.parseDouble(lat));
                        data.putExtra("lng", Double.parseDouble(lng));
                    } catch (Exception e) {
                        return;
                    }
                    setResult(RESULT_OK, data);
                    finish();
                }
            });
        }
    }
}
