package io.bandorilove.lovepet;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

final class HomeVisibility implements AutoCloseable {
    private static final long POLL_INTERVAL_MS = 300;
    private static final long EVENT_OVERLAP_MS = 2_000;
    private static final long INITIAL_LOOKBACK_MS = 120_000;
    private final Context context;
    private final Consumer<Boolean> onChange;
    private final HandlerThread thread = new HandlerThread("pet-home-visibility");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<ComponentName> resumed = new HashSet<>();
    private final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final UsageStatsManager usage;
    private long lastQuery;
    private boolean shown;
    private volatile boolean closed;

    HomeVisibility(Context context, Consumer<Boolean> onChange) {
        this.context = context;
        this.onChange = onChange;
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
        usage = context.getSystemService(UsageStatsManager.class);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::poll);
    }

    static boolean hasUsageAccess(Context context) {
        return context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    private void poll() {
        if (closed) return;
        boolean show = false;
        try {
            show = isHomeVisible();
        } catch (SecurityException ignored) {
            // The user can revoke usage access while the desktop pet is running.
            resumed.clear();
            lastQuery = 0;
        }
        if (show != shown) {
            shown = show;
            boolean visible = show;
            main.post(() -> { if (!closed) onChange.accept(visible); });
        }
        worker.postDelayed(this::poll, POLL_INTERVAL_MS);
    }

    private boolean isHomeVisible() {
        if (!hasUsageAccess(context)) {
            resumed.clear();
            lastQuery = 0;
            return false;
        }
        long now = System.currentTimeMillis();
        if (lastQuery > now) {
            resumed.clear();
            lastQuery = 0;
        }
        long start = lastQuery == 0 ? now - INITIAL_LOOKBACK_MS : lastQuery - EVENT_OVERLAP_MS;
        UsageEvents events = usage.queryEvents(start, now);
        if (events == null) return false;
        lastQuery = now;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.getNextEvent(event)) {
            int type = event.getEventType();
            if (type == UsageEvents.Event.DEVICE_SHUTDOWN || type == UsageEvents.Event.DEVICE_STARTUP) {
                resumed.clear();
                continue;
            }
            boolean foreground = type == UsageEvents.Event.ACTIVITY_RESUMED;
            boolean background = type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED;
            if (!(foreground || background) || event.getPackageName() == null || event.getClassName() == null) continue;
            ComponentName activity = new ComponentName(event.getPackageName(), event.getClassName());
            if (foreground) resumed.add(activity);
            else resumed.remove(activity);
        }
        if (resumed.isEmpty() || !power.isInteractive() || keyguard.isKeyguardLocked()) return false;
        ResolveInfo launcher = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (launcher == null || launcher.activityInfo == null) return false;
        for (ComponentName activity : resumed) {
            if (!activity.getPackageName().equals(launcher.activityInfo.packageName)) return false;
        }
        return true;
    }

    @Override public void close() {
        closed = true;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }
}
