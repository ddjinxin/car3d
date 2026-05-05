package com.jingxin.car3d;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 入口 Activity — 检查权限后启动悬浮窗 Service
 */
public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 100;
    private static final int REQ_STORAGE = 101;
    private static final int REQ_NOTIFICATION = 102;
    private static final int REQ_ALL_FILES = 103;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查乐酷车机桌面是否安装
        boolean hasLecoAuto = false;
        java.util.List<android.content.pm.ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
        for (int i = 0; i < apps.size(); i++) {
            if ("com.lecoauto".equals(apps.get(i).packageName)) {
                hasLecoAuto = true;
                break;
            }
        }
        if (!hasLecoAuto) {
            Toast.makeText(this, "该应用仅供乐酷车机桌面用户使用", Toast.LENGTH_LONG).show();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() { public void run() { finish(); } }, 5000);
            return;
        }

        // 第一步：Android 13+ 动态申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                return;
            }
        }

        checkNextPermission();
    }

    private void checkNextPermission() {
        // Android 11+ 需要所有文件访问权限才能读取 .glb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请授予文件访问权限以读取3D模型", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_ALL_FILES);
                } catch (Exception e) {
                    // 某些设备不支持 package URI，回退到通用设置
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQ_ALL_FILES);
                    } catch (Exception e2) {
                        Toast.makeText(this, "无法打开权限设置页面", Toast.LENGTH_LONG).show();
                        finish();
                    }
                }
                return;
            }
        } else {
            // Android 10 及以下用传统存储权限
            if (!hasStoragePermission()) {
                requestStoragePermission();
                return;
            }
        }

        if (!hasOverlayPermission()) {
            requestOverlayPermission();
        } else {
            startFloatingService();
            finish();
        }
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
    }

    private void requestOverlayPermission() {
        Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_SHORT).show();
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } catch (Exception e) {
            try {
                Intent intent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_OVERLAY);
            } catch (Exception e2) {
                Toast.makeText(this, "无法打开权限设置", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            checkNextPermission();
            return;
        }
        if (requestCode == REQ_STORAGE) {
            // Android 10 及以下，存储权限结果
            if (hasOverlayPermission()) {
                startFloatingService();
                finish();
            } else {
                requestOverlayPermission();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ALL_FILES) {
            // Android 11+ 所有文件访问权限返回
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                if (hasOverlayPermission()) {
                    startFloatingService();
                    finish();
                } else {
                    requestOverlayPermission();
                }
            } else {
                Toast.makeText(this, "未授予文件访问权限，无法读取3D模型", Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        if (requestCode == REQ_OVERLAY) {
            if (hasOverlayPermission()) {
                startFloatingService();
                finish();
            } else {
                Toast.makeText(this, "未获得悬浮窗权限，无法启动", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
