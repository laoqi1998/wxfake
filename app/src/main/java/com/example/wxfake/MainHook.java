package com.example.wxfake;

import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WxFakeLocation — 微信小程序 位置 + WiFi 专用伪造模块（带 App 配置界面 + 动态开关）
 *
 * 配置由界面 App 写入 shared_prefs/config.xml，本类通过 XSharedPreferences 读取；
 * 开关/坐标/WiFi 改动后 1 秒内生效（节流 reload），微信无需重启。
 * 开关关闭时所有 hook 透传原始数据，反检测层保持常开。
 */
public class MainHook implements IXposedHookLoadPackage {

    // ==================== 配置默认值（App 界面可改，存 shared_prefs） ====================
    static double FAKE_LAT   = 31.2304;                    // 纬度
    static double FAKE_LNG   = 121.4737;                   // 经度
    static float  FAKE_ACC   = 15.0f;                      // 精度（米）
    static String FAKE_ADDR  = "上海市黄浦区人民广场";        // 地址描述
    static String FAKE_SSID  = "MyHomeWiFi";               // WiFi 名称（不带引号）
    static String FAKE_BSSID = "aa:bb:cc:dd:ee:ff";        // AP 的 MAC
    static int    FAKE_RSSI  = -45;                        // 信号强度 dBm
    static String FAKE_CAPS  = "[WPA2-PSK-CCMP][ESS]";     // 加密能力串
    static boolean FAKE_ON   = true;                       // 总开关
    // ==========================================================================

    static XSharedPreferences prefs;
    static long lastReload = 0;

    private static void refreshFields() {
        FAKE_LAT   = parseD(prefs.getString("lat",   "31.2304"), FAKE_LAT);
        FAKE_LNG   = parseD(prefs.getString("lng",   "121.4737"), FAKE_LNG);
        FAKE_ACC   = (float) parseD(prefs.getString("acc",   "15"), FAKE_ACC);
        FAKE_ADDR  = prefs.getString("addr",  FAKE_ADDR);
        FAKE_SSID  = prefs.getString("ssid",  FAKE_SSID);
        FAKE_BSSID = prefs.getString("bssid", FAKE_BSSID);
        FAKE_RSSI  = (int) parseD(prefs.getString("rssi",  "-45"), FAKE_RSSI);
        FAKE_CAPS  = prefs.getString("caps",  FAKE_CAPS);
        FAKE_ON    = Boolean.parseBoolean(prefs.getString("enabled", "true"));
    }

    private static double parseD(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; }
    }

    /** 首次加载配置（微信进程启动） */
    private static void loadConfig() {
        try {
            prefs = new XSharedPreferences("com.example.wxfake", "config");
            prefs.reload();
            refreshFields();
            XposedBridge.log("[WxFake] config loaded: on=" + FAKE_ON + " " + FAKE_ADDR
                    + " (" + FAKE_LAT + "," + FAKE_LNG + ") wifi=" + FAKE_SSID + "/" + FAKE_BSSID);
        } catch (Throwable t) {
            XposedBridge.log("[WxFake] config load failed, use defaults: " + t);
        }
    }

    /** 动态开关：每次替换前查询，1 秒节流 reload —— 界面改开关/坐标即刻生效 */
    static boolean fakeOn() {
        if (prefs == null) return FAKE_ON;
        long now = SystemClock.elapsedRealtime();
        if (now - lastReload > 1000) {
            lastReload = now;
            try {
                prefs.reload();
                refreshFields();
            } catch (Throwable ignored) {
            }
        }
        return FAKE_ON;
    }

    // 微信已安装应用检测要隐藏的包名
    static final String[] HIDE_PKGS = {
            "com.lerist.fakelocation", "de.robv.android.xposed.installer",
            "org.lsposed.manager", "io.github.lsposed.manager",
            "com.tsng.hidemyapplist", "org.meowcat.edxposed.manager",
            "eu.chainfire.supersu", "com.topjohnwu.magisk"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.tencent.mm".equals(lpp.packageName)) return;
        final ClassLoader cl = lpp.classLoader;
        XposedBridge.log("[WxFake] loaded into " + lpp.processName);
        loadConfig();

        hookSystemLocation(cl);
        hookTencentSdk(cl);
        hookWifi(cl);
        hookAntiDetect(cl);
    }

    // ------------------------------------------------------------------
    // 构造假 Location
    // ------------------------------------------------------------------
    private static Location buildFake(String provider) {
        if (provider == null || provider.isEmpty()) provider = "gps";
        Location loc = new Location(provider);
        loc.setLatitude(FAKE_LAT);
        loc.setLongitude(FAKE_LNG);
        loc.setAccuracy(FAKE_ACC);
        loc.setAltitude(0);
        loc.setBearing(0);
        loc.setSpeed(0);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(System.nanoTime());
        return loc;
    }

    // ------------------------------------------------------------------
    // 1. 系统定位层
    // ------------------------------------------------------------------
    private void hookSystemLocation(final ClassLoader cl) {
        try {
            // 1a. requestLocationUpdates 全部重载：包装注册进系统的 LocationListener
            XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof LocationListener) {
                                    param.args[i] = wrapLocationListener(param.args[i], cl);
                                }
                            }
                        }
                    });

            // 1b. getLastKnownLocation：替换返回值
            XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (fakeOn()) param.setResult(buildFake((String) param.args[0]));
                        }
                    });

            // 1c. getCurrentLocation 的 Consumer 回调（Android 12+ 变体）
            XposedBridge.hookAllMethods(LocationManager.class, "getCurrentLocation",
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String provider = (param.args.length > 0 && param.args[0] instanceof String)
                                    ? (String) param.args[0] : "gps";
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Consumer) {
                                    final Consumer<Location> orig = (Consumer<Location>) param.args[i];
                                    final String p = provider;
                                    param.args[i] = new Consumer<Location>() {
                                        @Override
                                        public void accept(Location loc) {
                                            orig.accept(fakeOn() ? buildFake(p) : loc);
                                        }
                                    };
                                }
                            }
                        }
                    });

            // 1d. 兜底：LocationManager 内部转发类
            Class<?> transport = XposedHelpers.findClassIfExists(
                    "android.location.LocationManager$ListenerTransport", cl);
            if (transport != null) {
                XposedBridge.hookAllMethods(transport, "onLocationChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (fakeOn()) replaceLocationArgs(param.args);
                    }
                });
            }
            XposedBridge.log("[WxFake] system location hooked");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // 包装系统 LocationListener：转发前按开关决定是否替换
    private Object wrapLocationListener(final Object orig, final ClassLoader cl) {
        return Proxy.newProxyInstance(cl, new Class[]{LocationListener.class},
                (proxy, method, args) -> {
                    try {
                        if ("onLocationChanged".equals(method.getName())
                                && args != null && args.length == 1 && fakeOn()) {
                            replaceLocationArgs(args);
                        }
                        return method.invoke(orig, args);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                        return null;
                    }
                });
    }

    // args 里所有 Location / List<Location> 参数替换为假值
    private static void replaceLocationArgs(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Location) {
                Location l = (Location) args[i];
                args[i] = buildFake(l.getProvider());
            } else if (args[i] instanceof List) {
                List<Object> fake = new ArrayList<>();
                for (Object o : (List<?>) args[i]) {
                    if (o instanceof Location) {
                        Location l = (Location) o;
                        fake.add(buildFake(l.getProvider()));
                    }
                }
                if (!fake.isEmpty()) args[i] = fake;
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. 腾讯定位 SDK
    // ------------------------------------------------------------------
    private void hookTencentSdk(final ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocationManager", cl);
            Class<?> listenerCls = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocationListener", cl);
            Class<?> locCls = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocation", cl);
            if (mgrCls == null || listenerCls == null || locCls == null) {
                XposedBridge.log("[WxFake] TencentLocation SDK not found (system hook still active)");
                return;
            }

            final Object fakeTencentLoc = Proxy.newProxyInstance(cl, new Class[]{locCls},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getLatitude":  return FAKE_LAT;
                            case "getLongitude": return FAKE_LNG;
                            case "getAccuracy":  return FAKE_ACC;
                            case "getAddress":   return FAKE_ADDR;
                            case "getName":      return FAKE_ADDR;
                            case "getProvider":  return "wxfake";
                            case "getSpeed":     return 0f;
                            case "getBearing":   return 0f;
                            case "getAltitude":  return 0.0;
                            case "toString":     return "TencentLocation[wxfake]";
                            default:
                                Class<?> rt = method.getReturnType();
                                if (rt == boolean.class) return Boolean.FALSE;
                                if (rt == int.class)     return 0;
                                if (rt == long.class)    return 0L;
                                if (rt == float.class)   return 0f;
                                if (rt == double.class)  return 0.0;
                                return null;
                        }
                    });

            XposedBridge.hookAllMethods(mgrCls, "requestLocationUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        if (listenerCls.isInstance(param.args[i])) {
                            final Object orig = param.args[i];
                            param.args[i] = Proxy.newProxyInstance(cl,
                                    new Class[]{listenerCls},
                                    (proxy, method, args) -> {
                                        try {
                                            if ("onLocationChanged".equals(method.getName())
                                                    && args != null && args.length >= 2 && fakeOn()) {
                                                args[0] = fakeTencentLoc;
                                                if (args[1] instanceof Integer) args[1] = 0;
                                            }
                                            return method.invoke(orig, args);
                                        } catch (Throwable t) {
                                            XposedBridge.log(t);
                                            return null;
                                        }
                                    });
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(mgrCls, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (fakeOn()) param.setResult(fakeTencentLoc);
                }
            });
            XposedBridge.log("[WxFake] TencentLocation SDK hooked");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------
    // 3. WiFi 三路（全部动态：开关关闭时透传真实值）
    // ------------------------------------------------------------------
    private void hookWifi(ClassLoader cl) {
        try {
            // 3a. WifiInfo 实例 getter（SSID 返回必须带双引号）
            XposedBridge.hookAllMethods(WifiInfo.class, "getSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (fakeOn()) param.setResult("\"" + FAKE_SSID + "\"");
                }
            });
            XposedBridge.hookAllMethods(WifiInfo.class, "getBSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (fakeOn()) param.setResult(FAKE_BSSID);
                }
            });
            XposedBridge.hookAllMethods(WifiInfo.class, "getRssi", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (fakeOn()) param.setResult(FAKE_RSSI);
                }
            });

            // 3b. Android 12+ wx.getConnectedWifi 实际走 NetworkCapabilities.getSSID()
            XposedBridge.hookAllMethods(NetworkCapabilities.class, "getSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (fakeOn()) param.setResult("\"" + FAKE_SSID + "\"");
                }
            });

            // 3c. WiFi 指纹定位数据源：扫描结果 AP 列表整体替换
            XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!fakeOn()) return;
                            ScanResult sr = new ScanResult();
                            sr.SSID = FAKE_SSID;
                            sr.BSSID = FAKE_BSSID;
                            sr.level = FAKE_RSSI;
                            sr.capabilities = FAKE_CAPS;
                            sr.frequency = 2437; // 信道 6
                            sr.timestamp = System.currentTimeMillis() * 1000;
                            List<ScanResult> fake = new ArrayList<>(1);
                            fake.add(sr);
                            param.setResult(fake);
                        }
                    });
            XposedBridge.log("[WxFake] wifi hooked");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------
    // 4. 反检测（常开，不受开关影响）
    // ------------------------------------------------------------------
    private void hookAntiDetect(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Location.class, "isFromMockProvider",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.hookAllMethods(Location.class, "isMock",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.hookAllMethods(Location.class, "getMockFlag",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));

            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", cl,
                    "getString", ContentResolver.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ("mock_location".equals(param.args[1])) {
                                param.setResult("0");
                            }
                        }
                    });

            XC_MethodHook filterHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof List) {
                        List<Object> filtered = new ArrayList<>((List<Object>) result);
                        filtered.removeIf(item -> isHiddenPkg(pkgOf(item)));
                        param.setResult(filtered);
                    }
                }
            };
            Class<?> apmCls = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl);
            XposedBridge.hookAllMethods(apmCls, "getInstalledPackages", filterHook);
            XposedBridge.hookAllMethods(apmCls, "getInstalledApplications", filterHook);

            XposedBridge.hookAllMethods(apmCls, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (isHiddenPkg(pkg)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });
            XposedBridge.log("[WxFake] anti-detect hooked");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static String pkgOf(Object item) {
        if (item instanceof PackageInfo) return ((PackageInfo) item).packageName;
        if (item instanceof ApplicationInfo) return ((ApplicationInfo) item).packageName;
        if (item instanceof String) return (String) item;
        return null;
    }

    private static boolean isHiddenPkg(String pkg) {
        if (pkg == null) return false;
        for (String h : HIDE_PKGS) {
            if (pkg.startsWith(h)) return true;
        }
        return false;
    }
}
