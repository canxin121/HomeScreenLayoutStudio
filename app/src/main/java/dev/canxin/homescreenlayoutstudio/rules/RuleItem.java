package dev.canxin.homescreenlayoutstudio.rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuleItem {
    public static final String SORT_MANUAL = "manual";
    public static final String SORT_NAME = "name";

    public String name;
    public String sortMode = SORT_MANUAL;
    public final LayoutSpec layout = new LayoutSpec();
    public final List<String> appOrder = new ArrayList<>();
    public final Map<String, List<String>> equals = new LinkedHashMap<>();
    public final Map<String, List<String>> prefix = new LinkedHashMap<>();
    public final Map<String, List<String>> contains = new LinkedHashMap<>();

    public RuleItem(String name) {
        this.name = name;
    }

    public RuleItem copy() {
        RuleItem copy = new RuleItem(name);
        copy.layout.spanX = layout.spanX;
        copy.layout.spanY = layout.spanY;
        copy.layout.priority = layout.priority;
        copy.layout.preferredScreen = layout.preferredScreen;
        copy.layout.manualScreen = layout.manualScreen;
        copy.layout.manualCellX = layout.manualCellX;
        copy.layout.manualCellY = layout.manualCellY;
        copy.sortMode = sortMode;
        copy.appOrder.addAll(appOrder);
        copyMap(equals, copy.equals);
        copyMap(prefix, copy.prefix);
        copyMap(contains, copy.contains);
        return copy;
    }

    public int keywordCount() {
        return countKeywords(equals) + countKeywords(prefix) + countKeywords(contains);
    }

    public List<String> mutableContains(String field) {
        List<String> values = contains.get(field);
        if (values == null) {
            values = new ArrayList<>();
            contains.put(field, values);
        }
        return values;
    }

    public List<String> mutableEquals(String field) {
        List<String> values = equals.get(field);
        if (values == null) {
            values = new ArrayList<>();
            equals.put(field, values);
        }
        return values;
    }

    static RuleItem fromJson(JSONObject object) throws JSONException {
        RuleItem rule = new RuleItem(object.optString("name", "").trim());
        JSONObject layoutObject = object.optJSONObject("layout");
        if (layoutObject != null) {
            JSONArray span = layoutObject.optJSONArray("span");
            if (span != null && span.length() >= 2) {
                rule.layout.spanX = clamp(span.optInt(0, 1), 1, 8);
                rule.layout.spanY = clamp(span.optInt(1, 1), 1, 8);
            }
            rule.layout.priority = layoutObject.optInt("priority", 0);
            if (layoutObject.has("preferred_screen") && !layoutObject.isNull("preferred_screen")) {
                rule.layout.preferredScreen = layoutObject.optInt("preferred_screen");
            }
            if (layoutObject.has("screen") && !layoutObject.isNull("screen")
                    && layoutObject.has("cell_x") && !layoutObject.isNull("cell_x")
                    && layoutObject.has("cell_y") && !layoutObject.isNull("cell_y")) {
                rule.layout.manualScreen = layoutObject.optInt("screen");
                rule.layout.manualCellX = layoutObject.optInt("cell_x");
                rule.layout.manualCellY = layoutObject.optInt("cell_y");
            }
        }
        rule.sortMode = SORT_NAME.equals(object.optString("sort", SORT_MANUAL)) ? SORT_NAME : SORT_MANUAL;
        rule.appOrder.addAll(readStringList(object.opt("app_order")));
        readMatchMap(object.optJSONObject("equals"), rule.equals);
        readMatchMap(object.optJSONObject("prefix"), rule.prefix);
        readMatchMap(object.optJSONObject("contains"), rule.contains);
        return rule;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", name);

        JSONObject layoutObject = new JSONObject();
        JSONArray span = new JSONArray();
        span.put(layout.spanX);
        span.put(layout.spanY);
        layoutObject.put("span", span);
        layoutObject.put("priority", layout.priority);
        if (layout.preferredScreen != null) {
            layoutObject.put("preferred_screen", layout.preferredScreen);
        }
        if (layout.hasManualPosition()) {
            layoutObject.put("screen", layout.manualScreen);
            layoutObject.put("cell_x", layout.manualCellX);
            layoutObject.put("cell_y", layout.manualCellY);
        }
        object.put("layout", layoutObject);
        object.put("sort", SORT_NAME.equals(sortMode) ? SORT_NAME : SORT_MANUAL);
        if (!appOrder.isEmpty()) {
            JSONArray order = new JSONArray();
            for (String packageName : appOrder) {
                String item = packageName == null ? "" : packageName.trim();
                if (!item.isEmpty()) {
                    order.put(item);
                }
            }
            object.put("app_order", order);
        }

        putMatchMap(object, "equals", equals);
        putMatchMap(object, "prefix", prefix);
        putMatchMap(object, "contains", contains);
        return object;
    }

    private static void readMatchMap(JSONObject object, Map<String, List<String>> target) throws JSONException {
        if (object == null) {
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String field = keys.next();
            target.put(field, readStringList(object.opt(field)));
        }
    }

    private static List<String> readStringList(Object value) throws JSONException {
        ArrayList<String> result = new ArrayList<>();
        if (value == null || value == JSONObject.NULL) {
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String item = array.optString(i, "").trim();
                if (!item.isEmpty()) {
                    result.add(item);
                }
            }
            return result;
        }
        String item = String.valueOf(value).trim();
        if (!item.isEmpty()) {
            result.add(item);
        }
        return result;
    }

    private static void putMatchMap(JSONObject object, String name, Map<String, List<String>> values) throws JSONException {
        JSONObject map = new JSONObject();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            JSONArray array = new JSONArray();
            for (String value : entry.getValue()) {
                String item = value == null ? "" : value.trim();
                if (!item.isEmpty()) {
                    array.put(item);
                }
            }
            if (array.length() > 0) {
                map.put(entry.getKey(), array);
            }
        }
        if (map.length() > 0) {
            object.put(name, map);
        }
    }

    private static int countKeywords(Map<String, List<String>> values) {
        int count = 0;
        for (List<String> list : values.values()) {
            count += list.size();
        }
        return count;
    }

    private static void copyMap(Map<String, List<String>> source, Map<String, List<String>> target) {
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            target.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
