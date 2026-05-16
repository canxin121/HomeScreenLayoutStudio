package dev.canxin.homescreenlayoutstudio.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.canxin.homescreenlayoutstudio.HomeScreenLayoutStudioContract;

final class LauncherCommandReceiver extends BroadcastReceiver {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "home-screen-layout-worker");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean installed;

    private final Context appContext;
    private final ClassLoader launcherClassLoader;
    private final HomeScreenLayoutStudioModule module;

    private LauncherCommandReceiver(Context context, ClassLoader classLoader, HomeScreenLayoutStudioModule module) {
        this.appContext = context.getApplicationContext();
        this.launcherClassLoader = classLoader;
        this.module = module;
    }

    static void install(Context context, ClassLoader classLoader, HomeScreenLayoutStudioModule module) {
        if (installed) {
            return;
        }
        synchronized (LauncherCommandReceiver.class) {
            if (installed) {
                return;
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(HomeScreenLayoutStudioContract.ACTION_EXPORT);
            filter.addAction(HomeScreenLayoutStudioContract.ACTION_DRY_RUN);
            filter.addAction(HomeScreenLayoutStudioContract.ACTION_APPLY);
            filter.addAction(HomeScreenLayoutStudioContract.ACTION_PING);
            LauncherCommandReceiver receiver = new LauncherCommandReceiver(context, classLoader, module);
            if (HomeScreenLayoutStudioModule.canUseReceiverFlags()) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            installed = true;
            module.moduleLog(Log.INFO, "command receiver registered");
            HomeScreenLayoutStudioContract.recordEvent(
                    context.getContentResolver(),
                    "ready",
                    true,
                    "",
                    "Launcher hook is ready");
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        String token = intent.getStringExtra(HomeScreenLayoutStudioContract.EXTRA_TOKEN);
        String uriString = intent.getStringExtra(HomeScreenLayoutStudioContract.EXTRA_URI);
        String name = intent.getStringExtra(HomeScreenLayoutStudioContract.EXTRA_NAME);
        WORKER.execute(() -> handle(action, token, uriString, name));
    }

    private void handle(String action, String token, String uriString, String name) {
        try {
            verifyToken(token);
            if (HomeScreenLayoutStudioContract.ACTION_PING.equals(action)) {
                record("ping", true, name, "Launcher hook is ready");
                return;
            }
            if (uriString == null || uriString.isEmpty()) {
                throw new IllegalArgumentException("Missing layout uri");
            }
            Uri uri = Uri.parse(uriString);
            LauncherLayoutBridge bridge = new LauncherLayoutBridge(appContext, launcherClassLoader, module);
            if (HomeScreenLayoutStudioContract.ACTION_EXPORT.equals(action)) {
                bridge.exportTo(uri);
                record("export", true, name, "Exported " + safeName(name));
            } else if (HomeScreenLayoutStudioContract.ACTION_DRY_RUN.equals(action)) {
                String summary = bridge.dryRun(uri);
                record("dry_run", true, name, summary);
            } else if (HomeScreenLayoutStudioContract.ACTION_APPLY.equals(action)) {
                String summary = bridge.apply(uri);
                record("apply", true, name, summary);
            } else {
                throw new IllegalArgumentException("Unknown action " + action);
            }
        } catch (Throwable t) {
            String event = actionToEvent(action);
            String message = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            module.moduleLog(Log.ERROR, "command failed: " + event + " " + message, t);
            record(event, false, name, message);
        }
    }

    private void verifyToken(String token) {
        Bundle bundle = appContext.getContentResolver().call(
                HomeScreenLayoutStudioContract.BASE_URI,
                HomeScreenLayoutStudioContract.METHOD_GET_TOKEN,
                null,
                null);
        String expected = bundle == null ? null : bundle.getString(HomeScreenLayoutStudioContract.EXTRA_TOKEN);
        if (expected == null || token == null || !expected.equals(token)) {
            throw new SecurityException("Bad command token");
        }
    }

    private void record(String action, boolean success, String name, String message) {
        try {
            HomeScreenLayoutStudioContract.recordEvent(
                    appContext.getContentResolver(),
                    action,
                    success,
                    name == null ? "" : name,
                    message == null ? "" : message);
        } catch (Throwable t) {
            module.moduleLog(Log.ERROR, "failed to record status", t);
        }
    }

    private String actionToEvent(String action) {
        if (HomeScreenLayoutStudioContract.ACTION_EXPORT.equals(action)) {
            return "export";
        }
        if (HomeScreenLayoutStudioContract.ACTION_DRY_RUN.equals(action)) {
            return "dry_run";
        }
        if (HomeScreenLayoutStudioContract.ACTION_APPLY.equals(action)) {
            return "apply";
        }
        if (HomeScreenLayoutStudioContract.ACTION_PING.equals(action)) {
            return "ping";
        }
        return "command";
    }

    private String safeName(String name) {
        return name == null ? "" : name;
    }
}
