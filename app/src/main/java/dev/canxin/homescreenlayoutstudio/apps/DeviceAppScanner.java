package dev.canxin.homescreenlayoutstudio.apps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DeviceAppScanner {
    private DeviceAppScanner() {
    }

    public static ArrayList<DeviceApp> launchableApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(intent, 0);
        Map<String, DeviceApp> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolveInfos) {
            if (info == null || info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            String activityName = info.activityInfo.name == null ? "" : info.activityInfo.name;
            CharSequence labelValue = info.loadLabel(packageManager);
            String label = labelValue == null ? packageName : labelValue.toString().trim();
            if (label.isEmpty()) {
                label = packageName;
            }
            unique.put(packageName + "/" + activityName, new DeviceApp(label, packageName, activityName));
        }
        ArrayList<DeviceApp> apps = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((left, right) -> {
            int result = collator.compare(left.label, right.label);
            if (result != 0) {
                return result;
            }
            return left.packageName.compareTo(right.packageName);
        });
        return apps;
    }
}
