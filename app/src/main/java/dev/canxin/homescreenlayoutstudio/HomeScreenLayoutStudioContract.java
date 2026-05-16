package dev.canxin.homescreenlayoutstudio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

public final class HomeScreenLayoutStudioContract {
    public static final String MODULE_PACKAGE = "dev.canxin.homescreenlayoutstudio";
    public static final String LAUNCHER_PACKAGE = "com.android.launcher";
    public static final String AUTHORITY = MODULE_PACKAGE + ".store";
    public static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY);

    public static final String ACTION_EXPORT = MODULE_PACKAGE + ".action.EXPORT_LAYOUT";
    public static final String ACTION_DRY_RUN = MODULE_PACKAGE + ".action.DRY_RUN_LAYOUT";
    public static final String ACTION_APPLY = MODULE_PACKAGE + ".action.APPLY_LAYOUT";
    public static final String ACTION_PING = MODULE_PACKAGE + ".action.PING";
    public static final String ACTION_STATUS_CHANGED = MODULE_PACKAGE + ".action.STATUS_CHANGED";

    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_COMMAND_ID = "command_id";

    public static final String METHOD_GET_TOKEN = "get_token";
    public static final String METHOD_RECORD_EVENT = "record_event";
    public static final String METHOD_GET_STATUS = "get_status";

    public static final String KEY_ACTION = "action";
    public static final String KEY_SUCCESS = "success";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_NAME = "name";
    public static final String KEY_TIME = "time";

    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_FILE_NAME = "file_name";
    public static final String COLUMN_SIZE = "size";
    public static final String COLUMN_MODIFIED = "modified";
    public static final String COLUMN_URI = "uri";

    private HomeScreenLayoutStudioContract() {
    }

    public static Uri layoutsUri() {
        return BASE_URI.buildUpon().appendPath("layouts").build();
    }

    public static Uri layoutUri(String name) {
        return BASE_URI.buildUpon().appendPath("layouts").appendPath(name).build();
    }

    public static String getToken(Context context) {
        Bundle result = context.getContentResolver().call(BASE_URI, METHOD_GET_TOKEN, null, null);
        return result == null ? null : result.getString(EXTRA_TOKEN);
    }

    public static Bundle getStatus(Context context) {
        Bundle result = context.getContentResolver().call(BASE_URI, METHOD_GET_STATUS, null, null);
        return result == null ? Bundle.EMPTY : result;
    }

    public static void recordEvent(ContentResolver resolver, String action, boolean success, String name, String message) {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_ACTION, action);
        bundle.putBoolean(KEY_SUCCESS, success);
        bundle.putString(KEY_NAME, name);
        bundle.putString(KEY_MESSAGE, message);
        bundle.putLong(KEY_TIME, System.currentTimeMillis());
        resolver.call(BASE_URI, METHOD_RECORD_EVENT, null, bundle);
    }
}
