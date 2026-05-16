package dev.canxin.homescreenlayoutstudio.rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuleSet {
    public String name = "launcher_app_categories";
    public String description = "";
    public String fallback = "Unsorted";
    public final Map<String, List<String>> fieldAliases = new LinkedHashMap<>();
    public final ArrayList<RuleItem> rules = new ArrayList<>();

    public static RuleSet fromJson(String json) throws JSONException {
        return fromJson(new JSONObject(json));
    }

    public static RuleSet fromJson(JSONObject object) throws JSONException {
        RuleSet set = new RuleSet();
        set.name = object.optString("name", set.name);
        set.description = object.optString("description", "");
        set.fallback = object.optString("fallback", set.fallback);
        readAliases(object.optJSONObject("field_aliases"), set.fieldAliases);

        JSONArray rulesArray = object.optJSONArray("rules");
        if (rulesArray == null) {
            throw new JSONException("Rule set must contain a rules array.");
        }
        for (int i = 0; i < rulesArray.length(); i++) {
            JSONObject ruleObject = rulesArray.optJSONObject(i);
            if (ruleObject == null) {
                continue;
            }
            RuleItem rule = RuleItem.fromJson(ruleObject);
            if (!rule.name.isEmpty()) {
                set.rules.add(rule);
            }
        }
        return set;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", name);
        if (!description.isEmpty()) {
            object.put("description", description);
        }
        object.put("fallback", fallback);

        JSONObject aliases = new JSONObject();
        for (Map.Entry<String, List<String>> entry : fieldAliases.entrySet()) {
            JSONArray values = new JSONArray();
            for (String value : entry.getValue()) {
                values.put(value);
            }
            aliases.put(entry.getKey(), values);
        }
        object.put("field_aliases", aliases);

        JSONArray ruleArray = new JSONArray();
        for (RuleItem rule : rules) {
            ruleArray.put(rule.toJson());
        }
        object.put("rules", ruleArray);
        return object;
    }

    public String toPrettyJson() throws JSONException {
        return toJson().toString(2) + "\n";
    }

    public RuleItem ruleByName(String name) {
        for (RuleItem rule : rules) {
            if (rule.name.equals(name)) {
                return rule;
            }
        }
        return null;
    }

    public MatchHit match(JSONObject record) {
        MatchHit packageHit = matchExactPackage(record);
        if (packageHit != null) {
            return packageHit;
        }
        for (RuleItem rule : rules) {
            MatchHit hit = matchRule(record, rule, "equals", rule.equals);
            if (hit != null) {
                return hit;
            }
            hit = matchRule(record, rule, "prefix", rule.prefix);
            if (hit != null) {
                return hit;
            }
            hit = matchRule(record, rule, "contains", rule.contains);
            if (hit != null) {
                return hit;
            }
        }
        return new MatchHit(fallback, null, null, null);
    }

    public String classify(JSONObject record) {
        return match(record).category;
    }

    public RuleSet copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private MatchHit matchRule(JSONObject record, RuleItem rule, String matchType, Map<String, List<String>> values) {
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            String field = entry.getKey();
            String value = normalize(recordValue(record, field));
            for (String keyword : entry.getValue()) {
                String needle = normalize(keyword);
                if (needle.isEmpty()) {
                    continue;
                }
                if (matches(value, needle, matchType)) {
                    return new MatchHit(rule.name, field, matchType, keyword);
                }
            }
        }
        return null;
    }

    private MatchHit matchExactPackage(JSONObject record) {
        String packageName = normalize(recordValue(record, "package"));
        if (packageName.isEmpty()) {
            return null;
        }
        for (RuleItem rule : rules) {
            List<String> packages = rule.equals.get("package");
            if (packages == null) {
                continue;
            }
            for (String keyword : packages) {
                String needle = normalize(keyword);
                if (packageName.equals(needle)) {
                    return new MatchHit(rule.name, "package", "equals", keyword);
                }
            }
        }
        return null;
    }

    private String recordValue(JSONObject record, String field) {
        String direct = record.optString(field, "");
        if (!direct.isEmpty()) {
            return direct;
        }
        List<String> aliases = fieldAliases.get(field);
        if (aliases == null) {
            return "";
        }
        for (String alias : aliases) {
            String value = record.optString(alias, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static boolean matches(String value, String keyword, String matchType) {
        if ("equals".equals(matchType)) {
            return value.equals(keyword);
        }
        if ("prefix".equals(matchType)) {
            return value.startsWith(keyword);
        }
        return value.contains(keyword);
    }

    private static String normalize(String value) {
        return (value == null ? "" : value).replace("\u00a0", "").trim().toLowerCase(Locale.ROOT);
    }

    private static void readAliases(JSONObject object, Map<String, List<String>> target) {
        if (object == null) {
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String field = keys.next();
            ArrayList<String> values = new ArrayList<>();
            JSONArray array = object.optJSONArray(field);
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String value = array.optString(i, "").trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
            target.put(field, values);
        }
    }
}
