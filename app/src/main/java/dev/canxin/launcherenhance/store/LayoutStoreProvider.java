package dev.canxin.launcherenhance.store;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import dev.canxin.launcherenhance.LauncherEnhanceContract;

public class LayoutStoreProvider extends ContentProvider {
    private static final int MATCH_LAYOUTS = 1;
    private static final int MATCH_LAYOUT = 2;
    private static final String PREFS = "layout_store";
    private static final String PREF_TOKEN = "token";
    private static final String FORBIDDEN_TITLE_CHARS = "\\/:*?<>\"|";

    private final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);

    @Override
    public boolean onCreate() {
        matcher.addURI(LauncherEnhanceContract.AUTHORITY, "layouts", MATCH_LAYOUTS);
        matcher.addURI(LauncherEnhanceContract.AUTHORITY, "layouts/*", MATCH_LAYOUT);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        enforceAllowedCaller();
        if (matcher.match(uri) != MATCH_LAYOUTS) {
            throw new IllegalArgumentException("Unsupported uri: " + uri);
        }
        String[] columns = new String[]{
                LauncherEnhanceContract.COLUMN_ID,
                LauncherEnhanceContract.COLUMN_NAME,
                LauncherEnhanceContract.COLUMN_FILE_NAME,
                LauncherEnhanceContract.COLUMN_SIZE,
                LauncherEnhanceContract.COLUMN_MODIFIED,
                LauncherEnhanceContract.COLUMN_URI
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        File[] files = layoutsDir().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return cursor;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long id = 1;
        for (File file : files) {
            cursor.addRow(new Object[]{
                    id++,
                    displayTitleFor(file.getName()),
                    file.getName(),
                    file.length(),
                    file.lastModified(),
                    LauncherEnhanceContract.layoutUri(file.getName()).toString()
            });
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        if (matcher.match(uri) == MATCH_LAYOUTS) {
            return "vnd.android.cursor.dir/vnd.dev.canxin.launcher-layout";
        }
        if (matcher.match(uri) == MATCH_LAYOUT) {
            return "application/json";
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Use openFile for writes");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceAllowedCaller();
        if (matcher.match(uri) != MATCH_LAYOUT) {
            throw new IllegalArgumentException("Unsupported uri: " + uri);
        }
        File file = fileForUri(uri);
        boolean deleted = file.exists() && file.delete();
        if (deleted) {
            prefs().edit().remove(titleKey(file.getName())).apply();
        }
        return deleted ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        enforceAllowedCaller();
        if (matcher.match(uri) != MATCH_LAYOUT) {
            throw new IllegalArgumentException("Unsupported uri: " + uri);
        }
        if (values == null || !values.containsKey(LauncherEnhanceContract.COLUMN_NAME)) {
            return 0;
        }
        String title = normalizeTitle(values.getAsString(LauncherEnhanceContract.COLUMN_NAME));
        if (TextUtils.isEmpty(title)) {
            return 0;
        }
        String fileName = uri.getLastPathSegment();
        if (!isSafeJsonName(fileName)) {
            throw new IllegalArgumentException("Unsafe layout name: " + fileName);
        }
        prefs().edit().putString(titleKey(fileName), title).apply();
        return 1;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceAllowedCaller();
        if (matcher.match(uri) != MATCH_LAYOUT) {
            throw new FileNotFoundException("Unsupported uri: " + uri);
        }
        File file = fileForUri(uri);
        boolean write = mode != null && (mode.contains("w") || mode.contains("a") || mode.contains("+"));
        if (write) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new FileNotFoundException("Cannot create " + parent);
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode == null ? "r" : mode));
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceAllowedCaller();
        if (LauncherEnhanceContract.METHOD_GET_TOKEN.equals(method)) {
            Bundle result = new Bundle();
            result.putString(LauncherEnhanceContract.EXTRA_TOKEN, getOrCreateToken());
            return result;
        }
        if (LauncherEnhanceContract.METHOD_RECORD_EVENT.equals(method)) {
            recordEvent(extras == null ? Bundle.EMPTY : extras);
            return Bundle.EMPTY;
        }
        if (LauncherEnhanceContract.METHOD_GET_STATUS.equals(method)) {
            return getStatus();
        }
        return super.call(method, arg, extras);
    }

    private File layoutsDir() {
        Context context = requireProviderContext();
        File dir = new File(context.getFilesDir(), "layouts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File fileForUri(Uri uri) {
        String name = uri.getLastPathSegment();
        if (!isSafeJsonName(name)) {
            throw new IllegalArgumentException("Unsafe layout name: " + name);
        }
        return new File(layoutsDir(), name);
    }

    private boolean isSafeJsonName(String name) {
        if (TextUtils.isEmpty(name) || name.length() > 120 || !name.endsWith(".json")) {
            return false;
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String displayTitleFor(String fileName) {
        String title = normalizeTitle(prefs().getString(titleKey(fileName), null));
        if (!TextUtils.isEmpty(title)) {
            return title;
        }
        return defaultTitleFor(fileName);
    }

    private String titleKey(String fileName) {
        return "title:" + fileName;
    }

    private String defaultTitleFor(String fileName) {
        String base = fileName;
        if (base.endsWith(".json")) {
            base = base.substring(0, base.length() - 5);
        }
        if (base.startsWith("import-") && base.length() > 23) {
            return base.substring(23);
        }
        if (base.startsWith("layout-") && base.length() > 23) {
            return base.substring(23);
        }
        if (base.startsWith("before-apply-")) {
            return "恢复前备份 " + base.substring("before-apply-".length());
        }
        return base;
    }

    private String normalizeTitle(String title) {
        String raw = title == null ? "" : title.trim();
        StringBuilder builder = new StringBuilder(raw.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (FORBIDDEN_TITLE_CHARS.indexOf(c) >= 0 || Character.isISOControl(c)) {
                c = ' ';
            }
            if (Character.isWhitespace(c)) {
                if (builder.length() > 0 && !lastWasSpace) {
                    builder.append(' ');
                    lastWasSpace = true;
                }
            } else {
                builder.append(c);
                lastWasSpace = false;
            }
        }
        String value = builder.toString().trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private void recordEvent(Bundle extras) {
        SharedPreferences.Editor editor = prefs().edit();
        editor.putString(LauncherEnhanceContract.KEY_ACTION, extras.getString(LauncherEnhanceContract.KEY_ACTION, ""));
        editor.putBoolean(LauncherEnhanceContract.KEY_SUCCESS, extras.getBoolean(LauncherEnhanceContract.KEY_SUCCESS, false));
        editor.putString(LauncherEnhanceContract.KEY_NAME, extras.getString(LauncherEnhanceContract.KEY_NAME, ""));
        editor.putString(LauncherEnhanceContract.KEY_MESSAGE, extras.getString(LauncherEnhanceContract.KEY_MESSAGE, ""));
        editor.putLong(LauncherEnhanceContract.KEY_TIME, extras.getLong(LauncherEnhanceContract.KEY_TIME, System.currentTimeMillis()));
        editor.apply();

        Context context = getContext();
        if (context != null) {
            Intent intent = new Intent(LauncherEnhanceContract.ACTION_STATUS_CHANGED);
            intent.setPackage(LauncherEnhanceContract.MODULE_PACKAGE);
            context.sendBroadcast(intent);
        }
    }

    private Bundle getStatus() {
        SharedPreferences prefs = prefs();
        Bundle result = new Bundle();
        result.putString(LauncherEnhanceContract.KEY_ACTION, prefs.getString(LauncherEnhanceContract.KEY_ACTION, ""));
        result.putBoolean(LauncherEnhanceContract.KEY_SUCCESS, prefs.getBoolean(LauncherEnhanceContract.KEY_SUCCESS, false));
        result.putString(LauncherEnhanceContract.KEY_NAME, prefs.getString(LauncherEnhanceContract.KEY_NAME, ""));
        result.putString(LauncherEnhanceContract.KEY_MESSAGE, prefs.getString(LauncherEnhanceContract.KEY_MESSAGE, ""));
        result.putLong(LauncherEnhanceContract.KEY_TIME, prefs.getLong(LauncherEnhanceContract.KEY_TIME, 0L));
        return result;
    }

    private String getOrCreateToken() {
        SharedPreferences prefs = prefs();
        String token = prefs.getString(PREF_TOKEN, null);
        if (!TextUtils.isEmpty(token)) {
            return token;
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        token = builder.toString();
        prefs.edit().putString(PREF_TOKEN, token).apply();
        return token;
    }

    private SharedPreferences prefs() {
        return requireProviderContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void enforceAllowedCaller() {
        if (!isAllowedCaller()) {
            throw new SecurityException("Caller is not allowed");
        }
    }

    private boolean isAllowedCaller() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return true;
        }
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (LauncherEnhanceContract.LAUNCHER_PACKAGE.equals(pkg)
                    || LauncherEnhanceContract.MODULE_PACKAGE.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private Context requireProviderContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider context is null");
        }
        return context;
    }
}
