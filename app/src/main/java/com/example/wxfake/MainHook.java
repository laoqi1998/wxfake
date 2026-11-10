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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WxFakeLocation — 微信小程序 位置 + WiFi 专用伪造模块
 *
 * 设计要点（区别于 Fake Location 类通用模块）：
 * 1. 不 hook 抽象/接口方法（Xposed hook 接口方法无效），而是包装注册进系统的
 *    Listener/Consumer 参数 —— 系统真实回调无论何时到达都会先过我们的包装层，
 *    从根上避免「假坐标先注入、真坐标后回调覆盖」。
 * 2. 定位双路覆盖：系统 LocationManager 全部回调变体 + 微信实际使用的
 *    腾讯定位 SDK（com.tencent.map.geolocation）回调包装。
 * 3. WiFi 三路覆盖：WifiInfo getter / NetworkCapabilities.getSSID（Android 12+
 *    wx.getConnectedWifi 走这里）/ WifiManager.getScanResults（WiFi 指纹定位
 *    的数据源，服务端用 BSSID 列表反查坐标，必须换掉）。
 * 4. 反检测：mock 标记三 API 全部压 false、Settings.Secure 的 mock_location 读 0、
 *    已安装应用列表对微信隐藏模拟定位/Xposed 相关包名。
 */
public class MainHook implements IXposedHookLoadPackage {

    // ==================== 配置区：改成你要的值，重新编译即可 ====================
    static final double  FAKE_LAT   = 31.2304;                    // 纬度（示例：上海人民广场）
    static final double  FAKE_LNG   = 121.4737;                   // 经度
    static final float   FAKE_ACC   = 15.0f;                      // 精度（米）
    static final String  FAKE_ADDR  = "上海市黄浦区人民广场";        // 地址描述
    static final String  FAKE_SSID  = "MyHomeWiFi";               // WiFi 名称（不带引号）
    static final String  FAKE_BSSID = "aa:bb:cc:dd:ee:ff";        // AP 的 MAC
    static final int     FAKE_RSSI  = -45;                        // 信号强度 dBm
    static final String  FAKE_CAPS  = "[WPA2-PSK-CCMP][ESS]";     // 加密能力串
    // ==========================================================================

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
        XposedBridge.log("[WxFake] loaded into com.tencent.mm");

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
            // 1a. requestLocationUpdates 全部重载：把注册进系统的 LocationListener
            //     替换成包装代理 —— 真实回调的参数在转发前被替换
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

            // 1b. getLastKnownLocation：直接替换返回值
            XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(buildFake((String) param.args[0]));
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
                                            orig.accept(buildFake(p));
                                        }
                                    };
                                }
                            }
                        }
                    });

            // 1d. 兜底：LocationManager 内部转发类（若 1a 因系统变体漏网则此处再拦一道）
            Class<?> transport = XposedHelpers.findClassIfExists(
                    "android.location.LocationManager$ListenerTransport", cl);
            if (transport != null) {
                XposedBridge.hookAllMethods(transport, "onLocationChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        replaceLocationArgs(param.args);
                    }
                });
            }
            XposedBridge.log("[WxFake] system location hooked");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // 包装系统 LocationListener：onLocationChanged 参数替换后转发
    private Object wrapLocationListener(final Object orig, final ClassLoader cl) {
        return Proxy.newProxyInstance(cl, new Class[]{LocationListener.class},
                (proxy, method, args) -> {
                    try {
                        if ("onLocationChanged".equals(method.getName())
                                && args != null && args.length == 1) {
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
                args[i] = fake;
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. 腾讯定位 SDK（wx.getLocation 的真实来源）
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

            // 动态代理构造假 TencentLocation
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

            // requestLocationUpdates：包装传入的 TencentLocationListener
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
                                                    && args != null && args.length >= 2) {
                                                args[0] = fakeTencentLoc;
                                                if (args[1] instanceof Integer) {
                                                    args[1] = 0; // ERROR_OK
                                                }
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

            // getLastLocation 变体直接换返回值
            XposedBridge.hookAllMethods(mgrCls, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(fakeTencentLoc);
                }
            });
            XposedBridge.log("[WxFake] TencentLocation SDK hooked");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------
    // 3. WiFi 三路
    // ------------------------------------------------------------------
    private void hookWifi(ClassLoader cl) {
        try {
            // 3a. WifiInfo 实例 getter：任何途径拿到的 WifiInfo 都返回假值
            //     SSID 返回格式必须带双引号（系统约定）
            XposedBridge.hookAllMethods(WifiInfo.class, "getSSID",
                    XC_MethodReplacement.returnConstant("\"" + FAKE_SSID + "\""));
            XposedBridge.hookAllMethods(WifiInfo.class, "getBSSID",
                    XC_MethodReplacement.returnConstant(FAKE_BSSID));
            XposedBridge.hookAllMethods(WifiInfo.class, "getRssi",
                    XC_MethodReplacement.returnConstant(FAKE_RSSI));

            // 3b. Android 12+ wx.getConnectedWifi 实际走 NetworkCapabilities.getSSID()
            XposedBridge.hookAllMethods(NetworkCapabilities.class, "getSSID",
                    XC_MethodReplacement.returnConstant("\"" + FAKE_SSID + "\""));

            // 3c. WiFi 指纹定位数据源：扫描结果 AP 列表整体替换
            XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
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
    // 4. 反检测
    // ------------------------------------------------------------------
    private void hookAntiDetect(ClassLoader cl) {
        try {
            // 4a. mock 标记：新旧 API 全压 false
            XposedBridge.hookAllMethods(Location.class, "isFromMockProvider",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.hookAllMethods(Location.class, "isMock",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.hookAllMethods(Location.class, "getMockFlag",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));

            // 4b. mock_location 开关读取压 0
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

            // 4c. 已安装应用列表隐藏黑名单包
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

            // 4d. 单包查询直接抛 NameNotFoundException
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
