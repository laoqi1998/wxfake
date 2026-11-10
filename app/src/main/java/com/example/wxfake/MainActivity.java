package com.example.wxfake;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
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
 * WxFakeLocation 配置界面（桌面图标点开即是；隐藏后从拨号盘 *123456# 进）。
 * - 总开关：切换立即保存，下一次定位请求即生效（无需重启微信）
 * - 桌面隐藏：开启后桌面图标消失，拨号 *123456# 呼出
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
    private static final int REQ_PERM = 101;

    private SharedPreferences sp;
    private final EditText[] inputs = new EditText[KEYS.length];
    private final int latIdx = 0, lngIdx = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 拨号暗门：dial_code 有值 = 从拨号盘呼出 → 强制显示图标
        String dial = getIntent() == null ? null : getIntent().getStringExtra("dial_code");
        sp = getSharedPreferences("config", Context.MODE_PRIVATE);
        if (dial != null) {
            sp.edit().putString("hidden", "false").apply();
            applyIconVisible();
        }

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

        // ---------- 桌面隐藏开关 ----------
        Switch hide = new Switch(this);
        hide.setText("隐藏桌面图标（拨号 *123456# 呼出）");
        hide.setTextSize(14);
        hide.setChecked(sp.getString("hidden", "false").equals("true"));
        hide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton btn, boolean checked) {
                sp.edit().putString("hidden", checked ? "true" : "false").apply();
                if (checked) {
                    applyIconHidden();
                    Toast.makeText(MainActivity.this,
                            "已隐藏 ✅ 图标将消失；拨号盘输入 *123456# 呼出", Toast.LENGTH_LONG).show();
                    // 给 Toast 留 1.5 秒显示时间再消失
                    root.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            finishAndRemoveTask();
                        }
                    }, 1500);
                } else {
                    applyIconVisible();
                    Toast.makeText(MainActivity.this, "已恢复显示 ✅ 图标回桌面", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(hide);

        TextView tip = new TextView(this);
        tip.setText("开关和坐标改动都会在 1 秒内被微信读到，不用重启微信。\n"
                + "隐藏后模块照常工作（伪造不受影响），随时拨号 *123456# 呼出本界面。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF666666);
        tip.setPadding(0, 4, 0, pad / 2);
        root.addView(tip);

        // ---------- 定位区 ----------
        root.addView(sectionLabel("📍 位置", pad));
        for (int i = 0; i < 4; i++) addInput(root, i, pad);

        Button mapBtn = new Button(this);
        mapBtn.setText("打开地图选点");
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

        // ---------- WiFi 区 ----------
        root.addView(sectionLabel("📶 WiFi", pad));
        for (int i = 4; i < KEYS.length; i++) addInput(root, i, pad);

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
        foot.setText("验证：微信发朋友圈看定位 / 小程序触发定位功能。若没生效，划掉微信重开一次。");
        foot.setTextSize(12);
        foot.setTextColor(0xFF999999);
        foot.setPadding(0, pad, 0, 0);
        root.addView(foot);
    }

    // ---------------- 图标隐藏/显示（Alias 开关方案，主入口永不销毁） ----------------

    /** 隐藏桌面图标：停用带 LAUNCHER 的别名 activity */
    private void applyIconHidden() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, "com.example.wxfake.MainAlias");
            if (pm.getComponentEnabledSetting(alias)
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "隐藏失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 恢复桌面图标：启用别名 activity */
    private void applyIconVisible() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, "com.example.wxfake.MainAlias");
            if (pm.getComponentEnabledSetting(alias)
                    != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Throwable ignored) {
        }
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

    // ---------------- 权限：点按钮时再申请，结果回来接着读 ----------------
    private boolean ensurePerms() {
        boolean fineOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean nearbyOk = android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                == PackageManager.PERMISSION_GRANTED;

        if (fineOk && nearbyOk) return true;

        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQ_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    readCurrentWifi();
                    return;
                }
            }
            Toast.makeText(this, "定位权限被拒 → 读不了 WiFi，请手填；或去 设置→应用→WxFakeLocation→权限 里手动开", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 一键读取当前 WiFi ----------------
    private void readCurrentWifi() {
        String ssid = null, bssid = null;
        int rssi = -200;
        try {
            boolean locSvcOn;
            try {
                locSvcOn = Settings.Secure.getInt(getContentResolver(),
                        Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_OFF;
            } catch (Throwable t) {
                locSvcOn = false;
            }
            if (!locSvcOn) {
                Toast.makeText(this, "先去下拉快捷开关把「定位信息」打开，再点本按钮（系统规定）", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "还是读不到（可能连的是热点/没连WiFi）→ 请手填", Toast.LENGTH_LONG).show();
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
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null && data.hasExtra("lat")) {
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

        try {
            android.webkit.WebView web = new android.webkit.WebView(this);
            android.webkit.WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            web.setWebContentsDebuggingEnabled(false);
            web.addJavascriptInterface(new Bridge(), "Android");
            setContentView(web);

            double lat = getIntent().getDoubleExtra("lat", 31.2304);
            double lng = getIntent().getDoubleExtra("lng", 121.4737);
            web.loadUrl("file:///android_asset/map.html?lat=" + lat + "&lng=" + lng);
        } catch (Throwable e) {
            Toast.makeText(this, "地图组件初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
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
                        setResult(RESULT_OK, data);
                    } catch (Exception ignored) {
                        setResult(RESULT_CANCELED);
                    }
                    finish();
                }
            });
        }
    }
}

/**
 * 拨号暗门：监听拨号盘特殊码 *123456#。
 * Android 10+ 系统限制第三方读拨号内容，故用 DialRx 规则：
 * 通过一个「拨号后可见」的空 activity 响应 android.intent.action.DIAL，
 * HyperOS/原生都能从拨号记录点击进入 —— 不依赖 READ_PHONE_STATE。
 * 实际入口：MainActivity 里对 dial_code 的处理 + DialDoor activity。
 */
class DialDoor extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 直接转入主界面，带上暗门标记
        Intent it = new Intent(this, MainActivity.class);
        it.putExtra("dial_code", "*123456#");
        startActivity(it);
        finish();
    }
}
