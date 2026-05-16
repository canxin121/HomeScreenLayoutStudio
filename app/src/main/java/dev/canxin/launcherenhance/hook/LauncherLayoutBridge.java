package dev.canxin.launcherenhance.hook;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.SparseArray;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.canxin.launcherenhance.LauncherEnhanceContract;

final class LauncherLayoutBridge {
    private final Context context;
    private final ClassLoader cl;
    private final LauncherEnhanceModule module;

    LauncherLayoutBridge(Context context, ClassLoader classLoader, LauncherEnhanceModule module) {
        this.context = context.getApplicationContext();
        this.cl = classLoader;
        this.module = module;
    }

    void exportTo(Uri uri) throws Exception {
        String json = exportJson();
        writeString(uri, json);
        module.moduleLog(Log.INFO, "layout exported to " + uri);
    }

    String dryRun(Uri uri) throws Exception {
        String json = readString(uri);
        ParsedRestoreData parsed = parseRestoreData(json);
        return summarize("Dry-run ok", parsed.restoreData);
    }

    String apply(Uri uri) throws Exception {
        String json = readString(uri);
        String backupName = "before-apply-" + timestamp() + ".json";
        Uri backupUri = LauncherEnhanceContract.layoutUri(backupName);
        writeString(backupUri, exportJson());

        ParsedRestoreData parsed = parseRestoreData(json);
        Object helper = newInstance("com.android.launcher.backup.backrestore.restore.LayoutRestoreHelper");
        Class<?> helperClass = helper.getClass();
        helperClass.getMethod("setRestoreData", List.class).invoke(helper, parsed.restoreData);

        callRestoreState("onCloudRestoreStart");
        callRestoreState("onRecoverStart");

        boolean started = false;
        boolean success = false;
        try {
            started = (Boolean) helperClass.getMethod("onRestoreStart", Context.class, boolean.class)
                    .invoke(helper, context, parsed.onlyHasStandardData);
            if (started) {
                helperClass.getMethod("onRestoringReady", Context.class).invoke(helper, context);
                success = (Boolean) helperClass.getMethod("onRestoring", Context.class).invoke(helper, context);
                helperClass.getMethod("onRestoringComplete", Context.class).invoke(helper, context);
            }
        } finally {
            if (started) {
                helperClass.getMethod("onRestoreEnd", Context.class, boolean.class, boolean.class)
                        .invoke(helper, context, success, true);
            } else {
                callRestoreState("onRecoverEnd");
            }
        }

        if (!success) {
            throw new IllegalStateException("Restore helper returned false. Backup saved as " + backupName);
        }
        return summarize("Applied. Backup: " + backupName, parsed.restoreData);
    }

    private String exportJson() throws Exception {
        Class<?> parserClass = Class.forName(
                "com.android.launcher.backup.backrestore.backup.LauncherDBParser", false, cl);
        Method getMap = parserClass.getMethod("getBackupDataModeMapFromDB", Context.class);
        Object modeMap = getMap.invoke(null, context);
        if (!(modeMap instanceof Map) || ((Map<?, ?>) modeMap).isEmpty()) {
            throw new IllegalStateException("No launcher layout data found");
        }

        Class<?> jsonObjectClass = Class.forName("com.google.gson.JsonObject", false, cl);
        Class<?> jsonArrayClass = Class.forName("com.google.gson.JsonArray", false, cl);
        Class<?> jsonElementClass = Class.forName("com.google.gson.JsonElement", false, cl);
        Object root = jsonObjectClass.getDeclaredConstructor().newInstance();
        Method add = jsonObjectClass.getMethod("add", String.class, jsonElementClass);
        Method addPropertyNumber = jsonObjectClass.getMethod("addProperty", String.class, Number.class);
        Method addPropertyString = jsonObjectClass.getMethod("addProperty", String.class, String.class);

        Class<?> generatorClass = Class.forName("com.android.launcher.backup.cloudsync.CloudDataGenerator", false, cl);
        Constructor<?> generatorCtor = generatorClass.getDeclaredConstructor(Context.class);
        generatorCtor.setAccessible(true);
        Object generator = generatorCtor.newInstance(context);
        Method generateMode = generatorClass.getDeclaredMethod(
                "generateBackupDataModeToJson",
                Context.class,
                Class.forName("com.android.launcher.backup.mode.BackupDataModel", false, cl));
        generateMode.setAccessible(true);
        Method generateAppList = generatorClass.getDeclaredMethod("generateThirdPartAppListToJson");
        generateAppList.setAccessible(true);

        Class<?> modelClass = Class.forName("com.android.launcher.backup.mode.BackupDataModel", false, cl);
        Class<?> launcherModeClass = Class.forName("com.android.launcher.mode.LauncherMode", false, cl);
        Method setMode = modelClass.getMethod("setMode", launcherModeClass);

        for (Map.Entry<?, ?> entry : ((Map<?, ?>) modeMap).entrySet()) {
            Object mode = entry.getKey();
            Object model = entry.getValue();
            if (mode == null || model == null) {
                continue;
            }
            setMode.invoke(model, mode);
            Object modeJson = generateMode.invoke(null, context, model);
            String tag = tagForMode(mode);
            if (tag != null && modeJson != null) {
                add.invoke(root, tag, modeJson);
            }
        }

        Object appList = generateAppList.invoke(generator);
        if (appList == null) {
            appList = jsonArrayClass.getDeclaredConstructor().newInstance();
        }
        add.invoke(root, "app_list", appList);
        addPropertyNumber.invoke(root, "itemId", Integer.valueOf(1));
        addPropertyString.invoke(root, "launcher_enhance_exported_at", timestamp());
        return prettyJson(root.toString());
    }

    private ParsedRestoreData parseRestoreData(String json) throws Exception {
        Object jsonObject = parseHostJsonObject(json);
        Class<?> parserClass = Class.forName("com.android.launcher.backup.cloudsync.CloudDataParser", false, cl);
        Class<?> jsonObjectClass = Class.forName("com.google.gson.JsonObject", false, cl);
        Constructor<?> constructor = parserClass.getDeclaredConstructor(jsonObjectClass, Context.class);
        constructor.setAccessible(true);
        Object parser = constructor.newInstance(jsonObject, context);
        Object rawList = parserClass.getMethod("getRestoreModeDataList").invoke(parser);
        if (!(rawList instanceof List)) {
            throw new IllegalStateException("Parser did not return a mode list");
        }
        List<?> original = (List<?>) rawList;
        if (original.isEmpty()) {
            throw new IllegalStateException("No valid mode data in JSON");
        }
        ArrayList<Object> restoreData = new ArrayList<>(original);
        boolean onlyHasStandard = maybeAddDrawerCopy(restoreData);
        return new ParsedRestoreData(restoreData, onlyHasStandard);
    }

    private boolean maybeAddDrawerCopy(ArrayList<Object> restoreData) throws Exception {
        if (restoreData.size() != 1) {
            return false;
        }
        Object standard = restoreData.get(0);
        Object mode = standard.getClass().getMethod("getMode").invoke(standard);
        if (mode == null || !"Standard".equals(String.valueOf(mode))) {
            return false;
        }

        Class<?> modelClass = Class.forName("com.android.launcher.backup.mode.BackupDataModel", false, cl);
        Class<?> launcherModeClass = Class.forName("com.android.launcher.mode.LauncherMode", false, cl);
        Object drawerMode = enumValue(launcherModeClass, "Drawer");
        Object drawer = modelClass.getDeclaredConstructor().newInstance();
        modelClass.getMethod("setMode", launcherModeClass).invoke(drawer, drawerMode);
        copyModelValue(modelClass, standard, drawer, "DeviceLayoutParameter");
        copyModelValue(modelClass, standard, drawer, "DrawerModeSetting");
        copyModelValue(modelClass, standard, drawer, "ModeLayoutParameter");
        copyModelValue(modelClass, standard, drawer, "LayoutMap");
        restoreData.add(drawer);
        return true;
    }

    private void copyModelValue(Class<?> modelClass, Object from, Object to, String name) throws Exception {
        Object value = modelClass.getMethod("get" + name).invoke(from);
        Method setter = null;
        for (Method method : modelClass.getMethods()) {
            if (method.getName().equals("set" + name) && method.getParameterTypes().length == 1) {
                setter = method;
                break;
            }
        }
        if (setter == null) {
            throw new NoSuchMethodException("set" + name);
        }
        setter.invoke(to, value);
    }

    private Object parseHostJsonObject(String json) throws Exception {
        Class<?> parserClass = Class.forName("com.google.gson.JsonParser", false, cl);
        Object element;
        try {
            Method parseString = parserClass.getDeclaredMethod("parseString", String.class);
            element = parseString.invoke(null, json);
        } catch (NoSuchMethodException oldGson) {
            Object parser = parserClass.getDeclaredConstructor().newInstance();
            Method parse = parserClass.getDeclaredMethod("parse", String.class);
            element = parse.invoke(parser, json);
        }
        return element.getClass().getMethod("getAsJsonObject").invoke(element);
    }

    private String summarize(String prefix, List<?> restoreData) throws Exception {
        int itemCount = 0;
        StringBuilder modes = new StringBuilder();
        for (Object model : restoreData) {
            Object mode = model.getClass().getMethod("getMode").invoke(model);
            if (modes.length() > 0) {
                modes.append(',');
            }
            modes.append(mode);
            Object layoutMap = model.getClass().getMethod("getLayoutMap").invoke(model);
            if (layoutMap instanceof SparseArray) {
                SparseArray<?> sparseArray = (SparseArray<?>) layoutMap;
                for (int i = 0; i < sparseArray.size(); i++) {
                    Object value = sparseArray.valueAt(i);
                    if (value instanceof List) {
                        itemCount += ((List<?>) value).size();
                    }
                }
            }
        }
        return prefix + ". modes=" + modes + ", records=" + itemCount;
    }

    private String prettyJson(String compactJson) {
        try {
            return new JSONObject(compactJson).toString(2);
        } catch (Throwable ignored) {
            return compactJson;
        }
    }

    private String tagForMode(Object mode) {
        String name = String.valueOf(mode);
        if ("Standard".equals(name)) {
            return "LAYOUT";
        }
        if ("Drawer".equals(name)) {
            return "LAYOUT_DRAW";
        }
        if ("Simple".equals(name)) {
            return "LAYOUT_SIMPLE";
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private Object newInstance(String className) throws Exception {
        Class<?> clazz = Class.forName(className, false, cl);
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private void callRestoreState(String methodName) {
        try {
            Class<?> stateClass = Class.forName("com.android.launcher.backup.util.RestoreStateHelper", false, cl);
            Method method = stateClass.getMethod(methodName);
            method.invoke(null);
        } catch (Throwable t) {
            module.moduleLog(Log.INFO, "restore state call skipped: " + methodName + " " + t.getClass().getSimpleName());
        }
    }

    private String readString(Uri uri) throws Exception {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalStateException("Cannot open " + uri);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void writeString(Uri uri, String value) throws Exception {
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (outputStream == null) {
                throw new IllegalStateException("Cannot open " + uri);
            }
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static final class ParsedRestoreData {
        final ArrayList<Object> restoreData;
        final boolean onlyHasStandardData;

        ParsedRestoreData(ArrayList<Object> restoreData, boolean onlyHasStandardData) {
            this.restoreData = restoreData;
            this.onlyHasStandardData = onlyHasStandardData;
        }
    }
}
