package dev.canxin.homescreenlayoutstudio.apps;

import org.json.JSONException;
import org.json.JSONObject;

public final class DeviceApp {
    public final String label;
    public final String packageName;
    public final String activityName;

    DeviceApp(String label, String packageName, String activityName) {
        this.label = label;
        this.packageName = packageName;
        this.activityName = activityName;
    }

    public JSONObject toRuleRecord() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("title", label);
        object.put("packageName", packageName);
        object.put("activityName", activityName);
        return object;
    }
}
