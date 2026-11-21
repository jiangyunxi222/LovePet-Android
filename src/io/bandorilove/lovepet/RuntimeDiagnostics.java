package io.bandorilove.lovepet;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.webkit.WebView;

import java.io.File;
import java.nio.charset.StandardCharsets;

final class RuntimeDiagnostics {
    enum Area {
        PANEL_RENDER, PET_RENDER, WALLPAPER_RENDER, WALLPAPER_PREVIEW,
        MODEL_LIBRARY, WALLPAPER_CHANGE, PLAYBACK_ERROR, SCENE_ERROR, QUICK_SETTINGS, PET_SERVICE
    }

    private static final String SUMMARY_PREFIX = "BandoriLovePet:";

    static void event(Context context, Area area, String message) {
        DesktopRuntime.write(context, key(area), date(System.currentTimeMillis()) + "  " + message);
    }

    static void petServiceEvent(Context context, String message) {
        event(context, Area.PET_SERVICE, message);
        processEvent(context, message);
    }

    static void processEvent(Context context, String message) {
        try {
            context.getSystemService(ActivityManager.class)
                    .setProcessStateSummary((SUMMARY_PREFIX + message).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            // Exit history is optional and must not affect playback or services.
        }
    }

    static String report(MainActivity activity) {
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        boolean petRunning = DesktopRuntime.isPetRunning(activity);
        StringBuilder result = new StringBuilder("应用与设备");
        row(result, "应用", version(activity));
        row(result, "设备", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        row(result, "WebView", webViewVersion());
        row(result, "后台限制", manager.isBackgroundRestricted() ? "已限制" : "Android 未报告限制");
        row(result, "可用存储", activity.getFilesDir().getUsableSpace() / (1024 * 1024) + " MB");

        result.append("\n\n权限配置");
        row(result, "悬浮窗", enabled(Settings.canDrawOverlays(activity)));
        row(result, "使用情况访问", enabled(HomeVisibility.hasUsageAccess(activity)));
        switch (activity.wallpaperPermissionState()) {
            case "enabled": row(result, "动态壁纸服务", "已开启"); break;
            case "disabled": row(result, "动态壁纸服务", "未开启"); break;
            case "unsupported": row(result, "动态壁纸服务", "系统不支持"); break;
            default: row(result, "动态壁纸服务", "在系统中查看");
        }

        result.append("\n\n模型与播放");
        ModelStore store = new ModelStore(activity);
        File model = store.active();
        String format = model == null ? "" : store.archiveExtension(model);
        row(result, "当前模型", model == null ? "暂无模型" : format.isEmpty() ? "模型记录存在" : format + " 模型记录存在");
        last(result, activity, "模型导入与记录", Area.MODEL_LIBRARY);
        last(result, activity, "应用内渲染与纯享", Area.PANEL_RENDER);
        last(result, activity, "最近播放异常", Area.PLAYBACK_ERROR);

        result.append("\n\n桌宠");
        row(result, "服务", petRunning ? "运行中" : "未运行");
        row(result, "显示范围", PetService.homeOnly(activity) ? "仅在桌面" : "在应用上层");
        last(result, activity, "最近服务事件", Area.PET_SERVICE);
        last(result, activity, "最近桌宠渲染", Area.PET_RENDER);

        result.append("\n\n壁纸与素材特效");
        row(result, "主屏幕动态壁纸", WallpaperRecovery.isApplied(activity, WallpaperManager.FLAG_SYSTEM) ? "已应用" : "未应用");
        row(result, "锁屏动态壁纸", Build.VERSION.SDK_INT < 34 ? "系统无法单独查询"
                : WallpaperRecovery.isApplied(activity, WallpaperManager.FLAG_LOCK) ? "已应用" : "未应用");
        last(result, activity, "壁纸应用与退出", Area.WALLPAPER_CHANGE);
        last(result, activity, "最近壁纸渲染", Area.WALLPAPER_RENDER);
        last(result, activity, "最近系统壁纸预览渲染", Area.WALLPAPER_PREVIEW);
        last(result, activity, "最近素材特效异常", Area.SCENE_ERROR);
        last(result, activity, "最近控制中心操作", Area.QUICK_SETTINGS);

        appendExitHistory(result, manager, activity.getPackageName());
        return result.toString();
    }

    private static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "无法读取版本";
        }
    }

    private static String webViewVersion() {
        try {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            return info == null ? "未检测到 WebView" : info.packageName + " " + info.versionName;
        } catch (RuntimeException | LinkageError ignored) {
            return "无法读取 WebView 版本";
        }
    }

    private static String enabled(boolean value) { return value ? "已开启" : "未开启"; }

    private static String key(Area area) {
        // Preserve the service event already present in older installations.
        return area == Area.PET_SERVICE ? "pet-service-event" : "diagnostic-" + area.name();
    }

    private static void row(StringBuilder result, String label, String value) {
        result.append('\n').append(label).append("  ").append(value);
    }

    private static void last(StringBuilder result, Context context, String label, Area area) {
        String event = DesktopRuntime.read(context, key(area));
        row(result, label, event == null ? "暂无记录" : event);
    }

    private static void appendExitHistory(StringBuilder result, ActivityManager manager, String appProcess) {
        result.append("\n\n系统进程退出记录");
        try {
            int panel = 0, desktop = 0;
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(appProcess, 0, 32)) {
                boolean isDesktop = (appProcess + ":desktop").equals(exit.getProcessName());
                if ((!isDesktop && !appProcess.equals(exit.getProcessName()))
                        || exit.getReason() == ApplicationExitInfo.REASON_PACKAGE_UPDATED || (isDesktop ? desktop : panel) >= 2) continue;
                if (isDesktop) desktop++; else panel++;
                row(result, isDesktop ? "桌宠与壁纸进程" : "应用主进程", date(exit.getTimestamp()));
                result.append('\n').append(reason(exit.getReason()));
                String description = exit.getDescription();
                if (description != null && !description.trim().isEmpty()) result.append(" · ").append(description.replace('\n', ' '));
                byte[] bytes = exit.getProcessStateSummary();
                if (bytes == null) continue;
                String summary = new String(bytes, StandardCharsets.UTF_8);
                if (summary.startsWith(SUMMARY_PREFIX)) summary = summary.substring(SUMMARY_PREFIX.length());
                else if (!summary.equals("收到最近任务移除回调") && !summary.equals("桌宠渲染进程退出")) continue;
                row(result, "退出前状态", summary);
            }
            if (panel + desktop == 0) result.append("\n暂无记录");
        } catch (RuntimeException ignored) {
            result.append("\n无法读取系统记录");
        }
    }

    private static String date(long timestamp) { return DateFormat.format("MM-dd HH:mm:ss", timestamp).toString(); }

    private static String reason(int code) {
        switch (code) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "系统回收内存";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "系统记录为用户请求结束";
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "进程崩溃";
            case ApplicationExitInfo.REASON_ANR: return "应用无响应";
            case ApplicationExitInfo.REASON_SIGNALED: return "进程收到终止信号";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "系统因资源占用终止进程";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "权限变更";
            default: return "Android 退出原因 " + code;
        }
    }
}
