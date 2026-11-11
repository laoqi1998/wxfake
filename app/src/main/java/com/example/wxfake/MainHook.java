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
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WxFakeLocation — 微信小程序 位置 + WiFi 专用伪造模块（App 界面配置 + 动态开关）
 *
 * 关键设计：
 * - 腾讯定位 SDK 是微信懒加载的：启动时类不存在 → 挂 ClassLoader.loadClass 监听，
 *   首次加载 geolocation 类时立刻补挂完整 SDK hook（此前版本只试一次就放弃，
 *   导致「SDK not found」后小程序定位从未被接管 —— 本版核心修复）。
 * - 监听器替换制：hook 注册入口，把 listener 换成代理，真实回调参数在转发前被替换。
 * - WiFi 三路 + fused 四层 + 反检测常开。
 * - 开关/坐标 1 秒热加载，界面改动即时生效。
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
    static boolean FAKE_ON   = false;                      // 总开关：默认关，界面打开才注入
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
        FAKE_ON    = prefs.getString("enabled", "false").equals("true");
    }

    private static double parseD(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; }
    }

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

    /** 动态开关：每次替换前查询，1 秒节流 reload */
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
        hookJsBridge(cl);   // 终局兜底：小程序 JS 出口端拦截（wx.getLocation 等返回值改写）
    }

    // ------------------------------------------------------------------
    // 5. 小程序 JS 桥出口拦截（终局方案）
    //    不管底层定位实现走哪条路，wx.getLocation/getConnectedWifi 的结果
    //    都以 JSON 经 JS 桥流回小程序。在桥上把字段直接改掉。
    //    已知桥实现（按微信版本演进）：
    //      com.tencent.mm.appbrand.bridge.AppBrandJsBridgeBridge / WXJsApiCall
    //      com.tencent.xweb.billiard.BilliardJsBridge / APPBRAND 流量通道
    //      org.webrtc 或 webview addJavascriptInterface 层
    //    策略：对 processName 带 appbrand 的进程，hook 所有名为
    //      "nativeInvokeHandler"/"invokeHandler"/"publishHandler"/"onNativeReceive"
    //      的桥方法，扫参数文本，命中经纬度/WiFi 特征即替换。
    // ------------------------------------------------------------------
    private void hookJsBridge(final ClassLoader cl) {
        // 类名随微信版本混淆漂移 → 不猜名字，延迟到类加载时按「特征」抓：
        // 挂 ClassLoader.loadClass，凡是类里声明了 nativeInvokeHandler/publishHandler
        // 等桥方法的（AppBrand 桥家族特征），在首个实例方法被调用前挂上改写。
        XposedBridge.log("[WxFake] js bridge watching classloader (name-agnostic)");
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            final java.util.Set<String> doneClasses = new java.util.HashSet<>();

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String name = (String) param.args[0];
                    if (name == null || name.length() < 5 || name.contains(".")) return; // 只要简单名，跳过全限定名请求
                    Class<?> c = (Class<?>) param.getResult();
                    if (c == null || c.getClassLoader() != cl) return;

                    // 已处理过就跳过
                    if (!doneClasses.add(c.getName())) return;

                    // 找桥特征方法（声明了就算）
                    boolean isBridge = false;
                    try {
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            String mn = m.getName();
                            if (mn.equals("nativeInvokeHandler") || mn.equals("publishHandler")
                                    || mn.equals("invokeHandler") || mn.equals("onNativeReceive")) {
                                isBridge = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        return;
                    }
                    if (!isBridge) return;

                    int n = hookBridgeMethods(c);
                    if (n > 0) {
                        XposedBridge.log("[WxFake] js bridge caught: " + c.getName()
                                + " (methods=" + n + ")");
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        // 同时保留静态候选（老版本可能启动就加载了）
        String[] bridgeClasses = {
                "com.tencent.mm.appbrand.bridge.AppBrandJsBridgeBridge",
                "com.tencent.mm.appbrand.e",
                "com.tencent.mm.plugin.appbrand.jsapi.JsApiBridge",
                "org.chromium.base.JavaBridge",
                "com.tencent.mm.appbrand.common.c"
        };
        int hooked = 0;
        for (String name : bridgeClasses) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) continue;
            hooked += hookBridgeMethods(c);
        }
        XposedBridge.log("[WxFake] js bridge static candidates hooked (classes=" + hooked + ")");
    }

    private int hookBridgeMethods(final Class<?> c) {
        final int[] n = {0};
        try {
            XposedBridge.hookAllMethods(c, "nativeInvokeHandler", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rewriteJsonArgs(param);
                }
            });
            n[0]++;
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(c, "invokeHandler", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rewriteJsonArgs(param);
                }
            });
            n[0]++;
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(c, "publishHandler", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rewriteJsonArgs(param);
                }
            });
            n[0]++;
        } catch (Throwable ignored) {
        }
        return n[0];
    }

    /** 扫描参数中所有 String，命中坐标/WiFi JSON 特征即改写（含无引号键与转义变体） */
    private static void rewriteJsonArgs(XC_MethodHook.MethodHookParam param) {
        if (!fakeOn()) return;
        try {
            for (int i = 0; i < param.args.length; i++) {
                if (param.args[i] instanceof String) {
                    String s = (String) param.args[i];
                    if (s.length() < 15) continue;

                    String ns = null;
                    // 定位结果特征：latitude/longitude 键（引号可有可无，兼容转义变体）
                    if (s.contains("latitude") && s.contains("longitude")) {
                        ns = s
                                .replaceAll("(\\\\?\"latitude\\\\?\"|latitude)\\s*[:=]\\s*\\\"?-?[0-9.]+\\\"?",
                                        "latitude:" + FAKE_LAT)
                                .replaceAll("(\\\\?\"longitude\\\\?\"|longitude)\\s*[:=]\\s*\\\"?-?[0-9.]+\\\"?",
                                        "longitude:" + FAKE_LNG)
                                .replaceAll("(\\\\?\"accuracy\\\\?\"|accuracy)\\s*[:=]\\s*\\\"?-?[0-9.]+\\\"?",
                                        "accuracy:" + FAKE_ACC)
                                .replaceAll("(\\\\?\"address\\\\?\"|address)\\s*[:=]\\s*\\\"[^\\\"]*\\\"?",
                                        "address:" + FAKE_ADDR);
                        // 上面宽松替换可能产生非法 JSON（键没引号）——为了稳妥同时产出规范版
                        String strict = s
                                .replaceAll("\"latitude\"\\s*:\\s*\\\"?-?[0-9.]+\\\"?", "\"latitude\":" + FAKE_LAT)
                                .replaceAll("\"longitude\"\\s*:\\s*\\\"?-?[0-9.]+\\\"?", "\"longitude\":" + FAKE_LNG)
                                .replaceAll("\"accuracy\"\\s*:\\s*\\\"?-?[0-9.]+\\\"?", "\"accuracy\":" + FAKE_ACC)
                                .replaceAll("\"address\"\\s*:\\s*\"[^\"]*\"", "\"address\":\"" + FAKE_ADDR + "\"");
                        if (!strict.equals(s)) ns = strict;
                    }
                    // WiFi 结果特征
                    else if (s.contains("BSSID") || s.contains("bssid")) {
                        String strict = s
                                .replaceAll("\"SSID\"\\s*:\\s*\"[^\"]*\"", "\"SSID\":\"" + FAKE_SSID + "\"")
                                .replaceAll("\"BSSID\"\\s*:\\s*\"[^\"]*\"", "\"BSSID\":\"" + FAKE_BSSID + "\"")
                                .replaceAll("\"signal_strength\"\\s*:\\s*-?[0-9]+", "\"signal_strength\":" + FAKE_RSSI);
                        if (!strict.equals(s)) ns = strict;
                    }

                    if (ns != null && !ns.equals(s)) {
                        param.args[i] = ns;
                        XposedBridge.log("[WxFake] js bridge rewrote arg[" + i + "] len " + s.length());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
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

            // 1b. getLastKnownLocation
            XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (fakeOn()) param.setResult(buildFake((String) param.args[0]));
                        }
                    });

            // 1c. getCurrentLocation 的 Consumer 回调（Android 12+）
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

            // 1e. 谷歌融合定位（有 GMS 的环境走这条；无则自动跳过）
            Class<?> resultCls = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.location.LocationResult", cl);
            if (resultCls != null) {
                XposedBridge.hookAllMethods(resultCls, "getLocations", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!fakeOn()) return;
                        List<?> orig = (List<?>) param.getResult();
                        List<Location> fake = new ArrayList<>();
                        if (orig != null && !orig.isEmpty()) {
                            fake.add(buildFake(((Location) orig.get(0)).getProvider()));
                        } else {
                            fake.add(buildFake("fused"));
                        }
                        param.setResult(fake);
                    }
                });
                XposedBridge.hookAllMethods(resultCls, "getLastLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (fakeOn()) param.setResult(buildFake("fused"));
                    }
                });
                Class<?> cbCls = XposedHelpers.findClassIfExists(
                        "com.google.android.gms.location.LocationCallback", cl);
                if (cbCls != null) {
                    XposedBridge.hookAllMethods(cbCls, "onLocationResult", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!fakeOn()) return;
                            if (param.args.length > 0 && param.args[0] != null) {
                                param.args[0] = XposedHelpers.callStaticMethod(resultCls,
                                        "create", java.util.Collections.singletonList(buildFake("fused")));
                            }
                        }
                    });
                }
            }
            XposedBridge.log("[WxFake] system location hooked (fused=" + (resultCls != null) + ")");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

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
    // 2. 腾讯定位 SDK —— 核心修复：延迟捕获
    //    微信懒加载 SDK：启动瞬间类不存在，此前 hook 只试一次就放弃。
    //    现在挂住 ClassLoader.loadClass，首次加载 geolocation 类时立即补挂。
    // ------------------------------------------------------------------
    private void hookTencentSdk(final ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocationManager", cl);
            Class<?> listenerCls = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocationListener", cl);
            Class<?> locCls = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocation", cl);

            if (mgrCls != null && listenerCls != null && locCls != null) {
                hookTencentSdkClass(mgrCls, listenerCls, locCls, cl);
                return;
            }

            // 启动时类还没加载（懒加载）→ 挂 loadClass 监听，等「TencentLocationManager」
            // 这个确切类出现再补挂。旧版 bug：任何 geolocation.* 类都置 done=true，
            // 若先加载的是别的类（如内部类/异常类），loadClass 目标失败后彻底放弃。
            XposedBridge.log("[WxFake] TencentLocation SDK not loaded yet — watching classloader");
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                boolean done = false;

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (done) return;
                    try {
                        String name = (String) param.args[0];
                        if (name == null
                                || !name.equals("com.tencent.map.geolocation.TencentLocationManager")) {
                            return;
                        }
                        Class<?> first = (Class<?>) param.getResult();
                        if (first == null) return;
                        // 目标类真实出现，才锁定并挂 hook
                        done = true;

                        ClassLoader tcl = first.getClassLoader();
                        Class<?> mgr = first;
                        Class<?> lis = tcl.loadClass("com.tencent.map.geolocation.TencentLocationListener");
                        Class<?> loc = tcl.loadClass("com.tencent.map.geolocation.TencentLocation");
                        hookTencentSdkClass(mgr, lis, loc, cl);
                        XposedBridge.log("[WxFake] TencentLocation SDK hooked (deferred catch) ✓");
                    } catch (Throwable t) {
                        // 加载失败不置 done，继续等下一次触发
                        XposedBridge.log("[WxFake] deferred sdk hook retrying: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** SDK 三类齐全后真正挂 hook（启动命中与延迟捕获共用） */
    private void hookTencentSdkClass(final Class<?> mgrCls, final Class<?> listenerCls,
                                     final Class<?> locCls, final ClassLoader cl) {
        try {
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

            // 单次定位变体（部分小程序走这个）
            XposedBridge.hookAllMethods(mgrCls, "getCurrentLocation", new XC_MethodHook() {
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
                                            return null;
                                        }
                                    });
                        }
                    }
                }
            });

            XposedBridge.log("[WxFake] TencentLocation SDK hooked ✓");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------
    // 3. WiFi 三路
    // ------------------------------------------------------------------
    private void hookWifi(ClassLoader cl) {
        try {
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

            XposedBridge.hookAllMethods(NetworkCapabilities.class, "getSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (fakeOn()) param.setResult("\"" + FAKE_SSID + "\"");
                }
            });

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
                            sr.frequency = 2437;
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
    // 4. 反检测（常开）
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
