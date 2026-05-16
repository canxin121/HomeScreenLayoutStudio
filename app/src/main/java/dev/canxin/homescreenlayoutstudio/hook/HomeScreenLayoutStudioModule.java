package dev.canxin.homescreenlayoutstudio.hook;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public class HomeScreenLayoutStudioModule extends XposedModule {
    private static final String TAG = "HomeScreenLayoutStudio";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.launcher".equals(param.getPackageName())) {
            return;
        }
        try {
            ClassLoader classLoader = param.getClassLoader();
            Class<?> appClass = Class.forName("com.android.common.LauncherApplication", false, classLoader);
            Method onCreate = appClass.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object thisObject = chain.getThisObject();
                if (thisObject instanceof Application) {
                    LauncherCommandReceiver.install((Application) thisObject, classLoader, this);
                }
                return result;
            });
            moduleLog(Log.INFO, "hooked LauncherApplication.onCreate");
        } catch (Throwable t) {
            moduleLog(Log.ERROR, "failed to hook launcher application", t);
        }
    }

    void moduleLog(int priority, String message) {
        log(priority, TAG, message);
    }

    void moduleLog(int priority, String message, Throwable throwable) {
        log(priority, TAG, message, throwable);
    }

    static boolean canUseReceiverFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }
}
