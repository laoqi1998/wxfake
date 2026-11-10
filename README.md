# WxFakeLocation — 微信小程序 位置 + WiFi 专用 LSPosed 模块

针对微信小程序（`com.tencent.mm`）的定位与 WiFi 信息伪造模块。
区别于 Fake Location 等通用模块的失效原因与设计说明见文末。

## 工程

```
WxFakeLocation/
├── settings.gradle / build.gradle / gradle.properties      # Gradle 配置
├── .github/workflows/build.yml                             # 云编译（push 自动出 APK）
└── app/
    ├── build.gradle                                        # minSdk 33, Xposed API 82
    └── src/main/
        ├── AndroidManifest.xml                             # xposed 声明 + 作用域=微信
        ├── assets/xposed_init                              # 入口: com.example.wxfake.MainHook
        ├── java/com/example/wxfake/MainHook.java           # 全部 hook 逻辑
        └── res/values/{strings.xml, arrays.xml}            # 名称与作用域数组
```

## 改伪造值

全部常量在 `app/src/main/java/com/example/wxfake/MainHook.java` 顶部「配置区」：
`FAKE_LAT / FAKE_LNG / FAKE_ACC / FAKE_ADDR / FAKE_SSID / FAKE_BSSID / FAKE_RSSI / FAKE_CAPS`。
改完重新编译一次即可（无配置文件，改常量最稳，避免跨进程读写被微信检测）。

## 编译（二选一）

### A. 本机 Android Studio（推荐）

1. 安装 Android Studio（自带 JDK17 与 SDK，会自动补齐 gradle wrapper）
2. File → Open → 选中 `WxFakeLocation` 目录
3. 等索引完成后 Build → Build APK(s)
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

依赖仓库为 `https://api.xposed.info/`（Xposed 官方 maven）。若拉取失败：
手动下载 `XposedBridgeApi-82.jar` 放到 `app/libs/`，并把
`app/build.gradle` 的依赖改为 `compileOnly files('libs/XposedBridgeApi-82.jar')`。

### B. GitHub Actions 云编译（本机零安装）

1. 把 `WxFakeLocation` 整个文件夹推到 GitHub 仓库（注意 `.github` 是隐藏目录，别漏传）
2. push 后自动触发 `Build APK`，跑完在 Actions → 该次运行 → Artifacts 下载 `WxFakeLocation-debug-apk`
3. 也可在 Actions 页手动点 Run workflow

## 安装与启用

```
1. adb install app-debug.apk    （debug 签名即可，LSPosed 不校验签名）
2. LSPosed 管理器 → 模块 → 启用 WxFakeLocation
3. 作用域只勾「微信」
4. LSPosed 弹出「作用域已变更」→ 点立即生效（杀掉微信进程）
5. 重新打开微信
```

## 验证

```
1. 确认 hook 加载：
   LSPosed → 日志 → 过滤 [WxFake]，应看到：
   loaded into com.tencent.mm / system location hooked /
   TencentLocation SDK hooked / wifi hooked / anti-detect hooked

2. 定位：朋友圈发图 → 所在位置 → 应显示上海市黄浦区人民广场附近
   （对应 FAKE_LAT/FAKE_LNG）

3. WiFi：找调 wx.getConnectedWifi 的小程序（门店打卡/附近设备类），
   SSID 应为 MyHomeWiFi，BSSID 应为 aa:bb:cc:dd:ee:ff

4. 实时监控：adb logcat | grep -i wxfake
```

## 排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 日志无 [WxFake] | 模块未加载 | 确认 LSPosed 作用域勾了微信；确认 Zygisk 已开启 |
| TencentLocation SDK not found | 微信新版 SDK 包名/混淆变化 | 系统层 hook 仍在生效，wx.getLocation 大概率已伪造；如需 SDK 级，dump 微信 dex 找 com.tencent.map.geolocation 实际包名后改 findClassIfExists 的字符串 |
| 位置变了但小程序显示城市不对 | 模糊定位 wx.getFuzzyLocation + IP 归属地辅助 | 正常现象；把网络代理到目标城市即可 |
| WiFi SSID 仍真实 | 微信 native 层直读 binder | 加 hook `android.net.wifi.IWifiManager$Stub$Proxy.getConnectionInfo`（WifiInfo getter 已被 3a 拦截，拿到即假值） |
| 微信提示环境异常 | 反检测残留 | 已隐藏 Xposed/FakeLocation/Magisk 包名；再用 Shamiko/DenyList 隐藏 root |

## 设计说明（为什么 Fake Location 失败而这套有效）

1. **回调包装而非接口 hook**：Xposed 无法 hook 抽象/接口方法。通用模块在
   `LocationManager.requestLocationUpdates` 后「额外主动回调一次假坐标」，
   但系统真实回调随后到达会覆盖。本模块把**注册进系统的 listener 本身**
   换成包装代理，任何真实回调到达时参数已在转发前被替换。
2. **腾讯 SDK 双保险**：`wx.getLocation` 底层是腾讯定位 SDK，本模块同时包装
   `TencentLocationListener` 回调并伪造 `TencentLocation` 对象（动态代理）。
3. **WiFi 指纹源替换**：小程序定位大量场景是「客户端上报周围 AP 列表 →
   服务端按 WiFi 指纹反查坐标」，只改 GPS 坐标没用。本模块把
   `getScanResults()` 整体替换为单一假 AP，服务端反查的输入源已换掉。
4. **反检测闭环**：mock 标记三 API + Settings.Secure 开关 + 已安装应用
   列表隐藏（Fake Location 的包名本身在微信检测黑名单里，通用模块藏不住自己）。
