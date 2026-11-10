package com.example.wxfake;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Toast;

/**
 * 全屏地图选点页：WebView 加载 assets/map.html（高德瓦片，免 key）。
 * 原生按钮「◉ 我的位置」：GPS 定位 → 地图居中（WGS84→GCJ02 由页面内 moveTo() 转换）。
 * 独立文件（public class 必须与文件名同名）；必须是 public，否则系统实例化即崩。
 */
public class MapPickerActivity extends Activity {

    private android.webkit.WebView web;
    private boolean returned = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
            web = new android.webkit.WebView(this);
            android.webkit.WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            // file:// 页面加载 https 瓦片：放开混合内容与网络图片（灰屏根因）
            ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            ws.setBlockNetworkImage(false);
            ws.setBlockNetworkLoads(false);
            ws.setLoadsImagesAutomatically(true);
            ws.setJavaScriptCanOpenWindowsAutomatically(true);
            ws.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
            web.addJavascriptInterface(new Bridge(), "Android");
            fl.addView(web, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            android.widget.Button me = new android.widget.Button(this);
            me.setText("◉ 我的位置");
            android.widget.FrameLayout.LayoutParams mp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            mp.rightMargin = (int) (16 * getResources().getDisplayMetrics().density);
            mp.bottomMargin = (int) (84 * getResources().getDisplayMetrics().density);
            me.setLayoutParams(mp);
            me.setOnClickListener(v -> gotoMyLocation());
            fl.addView(me);

            setContentView(fl);

            double lat = getIntent().getDoubleExtra("lat", 31.2304);
            double lng = getIntent().getDoubleExtra("lng", 121.4737);
            web.loadUrl("file:///android_asset/map.html?lat=" + lat + "&lng=" + lng);
        } catch (Throwable e) {
            Toast.makeText(this, "地图初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void gotoMyLocation() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "先回主页点「读取WiFi」给定位权限，再来定位", Toast.LENGTH_LONG).show();
                return;
            }
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;

            // 先用最近已知位置秒回，再请求一次新定位精修
            Location last = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (last == null || l.getTime() > last.getTime())) last = l;
                } catch (Throwable ignored) {}
            }
            if (last != null) moveMap(last);

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        lm.getCurrentLocation(p, null, getMainExecutor(), loc -> {
                            if (loc != null) moveMap(loc);
                        });
                    } catch (Throwable ignored) {}
                }
            } else {
                lm.requestSingleUpdate(pickProvider(lm), new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        moveMap(location);
                    }
                }, null);
            }
            Toast.makeText(this, "定位中…地图会自动跳到你的位置", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "定位失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String pickProvider(LocationManager lm) {
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (lm.isProviderEnabled(p)) return p;
        }
        return LocationManager.PASSIVE_PROVIDER;
    }

    private void moveMap(Location loc) {
        if (web == null || returned) return;
        final double la = loc.getLatitude(), ln = loc.getLongitude();
        web.post(() -> web.evaluateJavascript(
                "moveTo(" + la + "," + ln + ")", null));
    }

    class Bridge {
        @android.webkit.JavascriptInterface
        public void mapPick(final String lat, final String lng) {
            runOnUiThread(() -> {
                Intent data = new Intent();
                try {
                    data.putExtra("lat", Double.parseDouble(lat));
                    data.putExtra("lng", Double.parseDouble(lng));
                    setResult(RESULT_OK, data);
                } catch (Exception ignored) {
                    setResult(RESULT_CANCELED);
                }
                returned = true;
                finish();
            });
        }
    }
}
