package dev.canxin.homescreenlayoutstudio.layout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.canxin.homescreenlayoutstudio.rules.LayoutSpec;
import dev.canxin.homescreenlayoutstudio.rules.RuleItem;
import dev.canxin.homescreenlayoutstudio.rules.RuleSet;

public final class LauncherLayoutOrganizer {
    private static final int DESKTOP = -100;
    private static final int HOTSEAT = -101;
    private static final int SCREEN_BASE_ID = 1000;
    private static final int FOLDER_COLUMNS = 3;
    private static final int FOLDER_ROWS = 4;

    private LauncherLayoutOrganizer() {
    }

    public static OrganizeResult organize(String sourceJson, RuleSet rules) throws JSONException {
        return organize(sourceJson, rules, new DesignOverrides());
    }

    public static OrganizeResult organize(String sourceJson, RuleSet rules, DesignOverrides overrides) throws JSONException {
        JSONObject root = new JSONObject(sourceJson);
        LinkedHashMap<String, ModeReport> reports = new LinkedHashMap<>();
        for (String modeName : new String[]{"LAYOUT", "LAYOUT_DRAW", "LAYOUT_SIMPLE"}) {
            JSONObject mode = root.optJSONObject(modeName);
            if (mode == null) {
                continue;
            }
            reports.put(modeName, organizeMode(mode, modeName, rules, overrides));
        }
        return new OrganizeResult(root.toString(2) + "\n", reports, reportText(reports, root));
    }

    public static LayoutPlan plan(String sourceJson, RuleSet rules, DesignOverrides overrides) throws JSONException {
        JSONObject root = new JSONObject(sourceJson);
        JSONObject mode = root.optJSONObject("LAYOUT");
        if (mode == null) {
            return new LayoutPlan(4, 6, 1);
        }
        JSONObject working = new JSONObject(mode.toString());
        arrangeDesktopWidgets(working, "LAYOUT");
        int[] grid = gridSize(working);
        applyWidgetOverrides(working, overrides, grid[0]);
        LinkedHashMap<String, ArrayList<ItemRef>> categorized = categorizeItems(working, rules);
        ArrayList<FolderGroup> groups = folderGroups(categorized, "LAYOUT", rules, grid[0], grid[1]);
        Map<Integer, Set<String>> occupied = occupiedCells(working);
        groups = rebalanceFolderSizes(groups, occupied, "LAYOUT", grid[0], grid[1]);
        sortGroups(groups);
        ArrayList<FolderPlacement> placements = placeFolderGroups(groups, occupied, grid[0], grid[1]);
        LayoutPlan plan = new LayoutPlan(grid[0], grid[1], Math.max(1, Math.max(maxScreen(occupiedCells(working)), maxPlacementScreen(placements)) + 2));
        addWidgetPlanItems(plan, working);
        for (FolderPlacement placement : placements) {
            FolderGroup group = placement.group;
            plan.items.add(new PlanItem(
                    "group:" + group.category,
                    group.category,
                    "group",
                    false,
                    placement.screen,
                    placement.cellX,
                    placement.cellY,
                    group.spec.spanX,
                    group.spec.spanY,
                    group.items.size()));
        }
        return plan;
    }

    public static LayoutSummary summarize(String sourceJson) throws JSONException {
        JSONObject root = new JSONObject(sourceJson);
        LayoutSummary summary = new LayoutSummary();
        for (String modeName : new String[]{"LAYOUT", "LAYOUT_DRAW", "LAYOUT_SIMPLE"}) {
            JSONObject mode = root.optJSONObject(modeName);
            if (mode != null) {
                summary.modes.put(modeName, summarizeMode(mode));
            }
        }
        return summary;
    }

    private static ModeReport organizeMode(JSONObject mode, String modeName, RuleSet rules, DesignOverrides overrides) throws JSONException {
        arrangeDesktopWidgets(mode, modeName);
        int[] grid = gridSize(mode);
        int gridWidth = grid[0];
        int gridHeight = grid[1];
        applyWidgetOverrides(mode, overrides, gridWidth);

        LinkedHashMap<String, ArrayList<ItemRef>> categorized = categorizeItems(mode, rules);
        ArrayList<FolderGroup> groups = folderGroups(categorized, modeName, rules, gridWidth, gridHeight);

        Map<Integer, Set<String>> occupied = occupiedCells(mode);
        groups = rebalanceFolderSizes(groups, occupied, modeName, gridWidth, gridHeight);
        sortGroups(groups);

        int nextId = maxItemId(mode) + 1;
        JSONArray newFolders = new JSONArray();
        ArrayList<FolderPlacement> placements = placeFolderGroups(groups, occupied, gridWidth, gridHeight);

        for (FolderPlacement placement : placements) {
            FolderGroup group = placement.group;
            int folderId = nextId++;
            if (group.createsFolder()) {
                JSONObject folder = folderTemplate(mode, group.spec.spanX, group.spec.spanY);
                folder.put("_id", folderId);
                folder.put("title", group.category);
                desktopPosition(folder, placement.screen, placement.cellX, placement.cellY, gridWidth);
                newFolders.put(folder);

                for (int rank = 0; rank < group.items.size(); rank++) {
                    folderChildPosition(group.items.get(rank).item, folderId, rank);
                }
            } else if (!group.items.isEmpty()) {
                standalonePosition(group.items.get(0).item, placement.screen, placement.cellX, placement.cellY, gridWidth);
            }
        }

        mode.put("FOLDERS", newFolders);
        for (String section : modeSections(mode)) {
            JSONArray array = mode.optJSONArray(section);
            if (array != null) {
                mode.put(section, sortedItems(array));
            }
        }
        ensureScreens(mode, maxScreen(occupiedCells(mode)));

        ModeReport report = new ModeReport(modeName);
        report.categories.putAll(toCategoryLabels(categorized));
        report.summary = summarizeMode(mode);
        return report;
    }

    private static List<String> modeSections(JSONObject mode) {
        ArrayList<String> sections = new ArrayList<>();
        if (mode.optJSONArray("APPLICATIONS") != null) {
            sections.add("APPLICATIONS");
        }
        if (mode.optJSONArray("SHORTCUTS") != null) {
            sections.add("SHORTCUTS");
        }
        return sections;
    }

    private static LinkedHashMap<String, ArrayList<ItemRef>> categorizeItems(JSONObject mode, RuleSet rules) {
        LinkedHashMap<String, ArrayList<ItemRef>> categorized = new LinkedHashMap<>();
        for (RuleItem rule : rules.rules) {
            categorized.put(rule.name, new ArrayList<>());
        }
        categorized.put(rules.fallback, new ArrayList<>());

        for (String section : modeSections(mode)) {
            JSONArray items = mode.optJSONArray(section);
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || intValue(item.opt("container"), DESKTOP) == HOTSEAT) {
                    continue;
                }
                String category = rules.classify(item);
                ArrayList<ItemRef> bucket = categorized.get(category);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    categorized.put(category, bucket);
                }
                bucket.add(new ItemRef(section, item));
            }
        }

        Iterator<Map.Entry<String, ArrayList<ItemRef>>> iterator = categorized.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isEmpty()) {
                iterator.remove();
            }
        }
        return categorized;
    }

    private static ArrayList<FolderGroup> folderGroups(
            LinkedHashMap<String, ArrayList<ItemRef>> categorized,
            String modeName,
            RuleSet rules,
            int gridWidth,
            int gridHeight) {
        HashMap<String, Integer> ruleOrder = new HashMap<>();
        for (int i = 0; i < rules.rules.size(); i++) {
            ruleOrder.put(rules.rules.get(i).name, i);
        }
        ArrayList<FolderGroup> groups = new ArrayList<>();
        for (Map.Entry<String, ArrayList<ItemRef>> entry : categorized.entrySet()) {
            RuleItem rule = rules.ruleByName(entry.getKey());
            sortItemRefs(entry.getValue(), rule);
            groups.add(new FolderGroup(
                    entry.getKey(),
                    entry.getValue(),
                    layoutSpecFor(entry.getKey(), entry.getValue().size(), rules, modeName, gridWidth, gridHeight),
                    ruleOrder.containsKey(entry.getKey()) ? ruleOrder.get(entry.getKey()) : ruleOrder.size()));
        }
        return groups;
    }

    private static void sortItemRefs(ArrayList<ItemRef> items, RuleItem rule) {
        if (rule == null) {
            return;
        }
        if (RuleItem.SORT_NAME.equals(rule.sortMode)) {
            items.sort(Comparator.comparing(ref -> itemLabel(ref.item), String.CASE_INSENSITIVE_ORDER));
            return;
        }
        if (rule.appOrder.isEmpty()) {
            return;
        }
        HashMap<String, Integer> order = new HashMap<>();
        for (int i = 0; i < rule.appOrder.size(); i++) {
            order.put(rule.appOrder.get(i), i);
        }
        items.sort((left, right) -> {
            int leftOrder = order.containsKey(packageName(left.item)) ? order.get(packageName(left.item)) : Integer.MAX_VALUE;
            int rightOrder = order.containsKey(packageName(right.item)) ? order.get(packageName(right.item)) : Integer.MAX_VALUE;
            if (leftOrder != rightOrder) {
                return Integer.compare(leftOrder, rightOrder);
            }
            return Integer.compare(left.item.optInt("rank", 0), right.item.optInt("rank", 0));
        });
    }

    private static void sortGroups(ArrayList<FolderGroup> groups) {
        Collections.sort(groups, (left, right) -> {
            boolean leftManual = left.spec.hasManualPosition();
            boolean rightManual = right.spec.hasManualPosition();
            if (leftManual != rightManual) {
                return leftManual ? -1 : 1;
            }
            int result = Integer.compare(right.spec.priority, left.spec.priority);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(right.spec.spanX * right.spec.spanY, left.spec.spanX * left.spec.spanY);
            if (result != 0) {
                return result;
            }
            int leftScreen = left.spec.preferredScreen == null ? 99 : left.spec.preferredScreen;
            int rightScreen = right.spec.preferredScreen == null ? 99 : right.spec.preferredScreen;
            result = Integer.compare(leftScreen, rightScreen);
            if (result != 0) {
                return result;
            }
            return Integer.compare(left.ruleOrder, right.ruleOrder);
        });
    }

    private static int[] gridSize(JSONObject mode) {
        JSONObject params = mode.optJSONObject("MODE_PARAMETERS");
        if (params == null) {
            params = mode.optJSONObject("LAYOUT_PARAMETERS");
        }
        if (params == null) {
            return new int[]{4, 6};
        }
        return new int[]{
                Math.max(1, params.optInt("cellCountX", 4)),
                Math.max(1, params.optInt("cellCountY", 6))
        };
    }

    private static LayoutSpec layoutSpecFor(
            String category,
            int itemCount,
            RuleSet rules,
            String modeName,
            int gridWidth,
            int gridHeight) {
        RuleItem rule = rules.ruleByName(category);
        LayoutSpec spec = rule == null ? new LayoutSpec() : rule.layout.copy();
        spec.spanX = clamp(spec.spanX, 1, gridWidth);
        spec.spanY = clamp(spec.spanY, 1, gridHeight);
        if ("LAYOUT".equals(modeName)) {
            if (itemCount <= 1) {
                spec.spanX = 1;
                spec.spanY = 1;
            } else {
                spec.spanX = Math.max(2, spec.spanX);
                spec.spanY = Math.max(2, spec.spanY);
            }
        } else {
            spec.spanX = 1;
            spec.spanY = 1;
            spec.preferredScreen = null;
        }
        return spec;
    }

    private static void arrangeDesktopWidgets(JSONObject mode, String modeName) throws JSONException {
        if (!"LAYOUT".equals(modeName)) {
            return;
        }
        int[] grid = gridSize(mode);
        int gridWidth = grid[0];
        int gridHeight = grid[1];
        JSONArray cards = mode.optJSONArray("CARD");
        if (cards == null) {
            return;
        }

        ArrayList<JSONObject> twoByTwo = new ArrayList<>();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject item = cards.optJSONObject(i);
            if (item == null || intValue(item.opt("container"), DESKTOP) != DESKTOP) {
                continue;
            }
            int[] span = normalizeSpan(item);
            if (item.optInt("screen", 0) == 0 && span[0] == 2 && span[1] == 2) {
                twoByTwo.add(item);
            }
        }
        Collections.sort(twoByTwo, Comparator.comparingInt(item -> item.optInt("_id", 0)));
        for (int i = 0; i < twoByTwo.size(); i++) {
            int nextY = i * 2;
            if (nextY + 2 <= gridHeight) {
                desktopPosition(twoByTwo.get(i), 0, 0, nextY, gridWidth);
            }
        }

        for (int i = 0; i < cards.length(); i++) {
            JSONObject item = cards.optJSONObject(i);
            if (item == null || intValue(item.opt("container"), DESKTOP) != DESKTOP) {
                continue;
            }
            int[] span = normalizeSpan(item);
            if (span[0] == gridWidth && span[1] <= gridHeight) {
                desktopPosition(item, item.optInt("screen", 0), 0, 0, gridWidth);
            }
        }
    }

    private static Map<Integer, Set<String>> occupiedCells(JSONObject mode) {
        HashMap<Integer, Set<String>> occupied = new HashMap<>();
        for (String section : new String[]{"WIDGETS", "CARD"}) {
            JSONArray items = mode.optJSONArray(section);
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || intValue(item.opt("container"), DESKTOP) != DESKTOP) {
                    continue;
                }
                int screen = item.optInt("screen", item.optInt("new_screen", 0));
                int startX = item.optInt("cellX", item.optInt("new_cellX", 0));
                int startY = item.optInt("cellY", item.optInt("new_cellY", 0));
                int[] span = normalizeSpan(item);
                Set<String> cells = occupied.computeIfAbsent(screen, ignored -> new LinkedHashSet<>());
                for (int x = startX; x < startX + span[0]; x++) {
                    for (int y = startY; y < startY + span[1]; y++) {
                        cells.add(cellKey(x, y));
                    }
                }
            }
        }
        return occupied;
    }

    private static void applyWidgetOverrides(JSONObject mode, DesignOverrides overrides, int gridWidth) throws JSONException {
        if (overrides == null || overrides.widgetPlacements.isEmpty()) {
            return;
        }
        for (String section : new String[]{"WIDGETS", "CARD"}) {
            JSONArray items = mode.optJSONArray(section);
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || intValue(item.opt("container"), DESKTOP) != DESKTOP) {
                    continue;
                }
                Placement placement = overrides.widgetPlacements.get(widgetId(section, item));
                if (placement != null) {
                    desktopPosition(item, placement.screen, placement.cellX, placement.cellY, gridWidth);
                }
            }
        }
    }

    private static ArrayList<FolderGroup> rebalanceFolderSizes(
            ArrayList<FolderGroup> groups,
            Map<Integer, Set<String>> occupied,
            String modeName,
            int gridWidth,
            int gridHeight) {
        if (!"LAYOUT".equals(modeName)) {
            return groups;
        }
        Set<String> selectedLargeNames = selectedLargeGroupNames(groups, occupied, gridWidth, gridHeight);
        ArrayList<FolderGroup> result = new ArrayList<>();
        for (FolderGroup group : groups) {
            int area = group.spec.spanX * group.spec.spanY;
            if (area <= 1 || group.spec.hasManualPosition() || selectedLargeNames.contains(group.category)) {
                result.add(group);
            } else {
                LayoutSpec spec = group.spec.copy();
                spec.spanX = 1;
                spec.spanY = 1;
                result.add(new FolderGroup(group.category, group.items, spec, group.ruleOrder));
            }
        }
        return result;
    }

    private static Set<String> selectedLargeGroupNames(
            ArrayList<FolderGroup> groups,
            Map<Integer, Set<String>> occupied,
            int gridWidth,
            int gridHeight) {
        ArrayList<FolderGroup> candidates = new ArrayList<>();
        for (FolderGroup group : groups) {
            if (group.spec.spanX * group.spec.spanY > 1) {
                candidates.add(group);
            }
        }
        Collections.sort(candidates, (left, right) -> {
            int result = Integer.compare(right.spec.priority, left.spec.priority);
            return result != 0 ? result : Integer.compare(left.ruleOrder, right.ruleOrder);
        });
        if (candidates.isEmpty()) {
            return Collections.emptySet();
        }

        int gridArea = gridWidth * gridHeight;
        int fixedCells = 0;
        for (Set<String> cells : occupied.values()) {
            fixedCells += cells.size();
        }
        int baseFolderCells = groups.size();
        float minLastPageFill = 0.75f;

        for (int largeCount = candidates.size(); largeCount >= 0; largeCount--) {
            int extraCells = 0;
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < largeCount; i++) {
                FolderGroup group = candidates.get(i);
                extraCells += group.spec.spanX * group.spec.spanY - 1;
                selected.add(group.category);
            }
            int totalCells = fixedCells + baseFolderCells + extraCells;
            int pageCount = Math.max(1, (totalCells + gridArea - 1) / gridArea);
            int lastPageCells = totalCells - (pageCount - 1) * gridArea;
            if ((float) lastPageCells / (float) gridArea >= minLastPageFill) {
                return selected;
            }
        }
        return Collections.singleton(candidates.get(0).category);
    }

    private static FolderPlacement placeFolderGroup(
            FolderGroup group,
            Map<Integer, Set<String>> occupied,
            int gridWidth,
            int gridHeight) {
        int screen = 0;
        while (true) {
            for (int y = 0; y < gridHeight; y++) {
                for (int x = 0; x < gridWidth; x++) {
                    if (canPlace(occupied, screen, x, y, group.spec.spanX, group.spec.spanY, gridWidth, gridHeight)) {
                        occupy(occupied, screen, x, y, group.spec.spanX, group.spec.spanY);
                        return new FolderPlacement(group, screen, x, y);
                    }
                }
            }
            screen++;
        }
    }

    private static ArrayList<FolderPlacement> placeFolderGroups(
            ArrayList<FolderGroup> groups,
            Map<Integer, Set<String>> occupied,
            int gridWidth,
            int gridHeight) {
        ArrayList<FolderPlacement> placements = new ArrayList<>();
        LinkedHashSet<FolderGroup> placed = new LinkedHashSet<>();
        for (FolderGroup group : groups) {
            if (!group.spec.hasManualPosition()) {
                continue;
            }
            int screen = Math.max(0, group.spec.manualScreen);
            int cellX = Math.max(0, group.spec.manualCellX);
            int cellY = Math.max(0, group.spec.manualCellY);
            if (canPlace(occupied, screen, cellX, cellY, group.spec.spanX, group.spec.spanY, gridWidth, gridHeight)) {
                occupy(occupied, screen, cellX, cellY, group.spec.spanX, group.spec.spanY);
                placements.add(new FolderPlacement(group, screen, cellX, cellY));
                placed.add(group);
            }
        }
        for (FolderGroup group : groups) {
            if (!placed.contains(group)) {
                placements.add(placeFolderGroup(group, occupied, gridWidth, gridHeight));
            }
        }
        return placements;
    }

    private static boolean canPlace(
            Map<Integer, Set<String>> occupied,
            int screen,
            int cellX,
            int cellY,
            int spanX,
            int spanY,
            int gridWidth,
            int gridHeight) {
        if (cellX < 0 || cellY < 0 || cellX + spanX > gridWidth || cellY + spanY > gridHeight) {
            return false;
        }
        Set<String> cells = occupied.computeIfAbsent(screen, ignored -> new LinkedHashSet<>());
        for (int x = cellX; x < cellX + spanX; x++) {
            for (int y = cellY; y < cellY + spanY; y++) {
                if (cells.contains(cellKey(x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void occupy(Map<Integer, Set<String>> occupied, int screen, int cellX, int cellY, int spanX, int spanY) {
        Set<String> cells = occupied.computeIfAbsent(screen, ignored -> new LinkedHashSet<>());
        for (int x = cellX; x < cellX + spanX; x++) {
            for (int y = cellY; y < cellY + spanY; y++) {
                cells.add(cellKey(x, y));
            }
        }
    }

    private static JSONObject folderTemplate(JSONObject mode, int spanX, int spanY) throws JSONException {
        JSONArray folders = mode.optJSONArray("FOLDERS");
        JSONObject template = null;
        boolean matchedSpan = false;
        if (folders != null && folders.length() > 0) {
            JSONObject fallback = folders.optJSONObject(0);
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folder = folders.optJSONObject(i);
                if (folder == null) {
                    continue;
                }
                int[] span = normalizeSpan(folder);
                if (span[0] == spanX && span[1] == spanY) {
                    template = new JSONObject(folder.toString());
                    matchedSpan = true;
                    break;
                }
            }
            if (template == null && fallback != null) {
                template = new JSONObject(fallback.toString());
            }
        }
        if (template == null) {
            template = new JSONObject();
            template.put("_id", 0);
            template.put("container", DESKTOP);
            template.put("screenId", SCREEN_BASE_ID);
            template.put("screen", 0);
            template.put("cellX", 0);
            template.put("cellY", 0);
            template.put("new_container", DESKTOP);
            template.put("new_screen", 0);
            template.put("new_cellX", 0);
            template.put("new_cellY", 0);
            template.put("new_rank", 0);
            template.put("user_id", 0);
            template.put("profileId", 0);
            template.put("restored", 0);
            template.put("title", "");
            template.put("recommendId", -1);
            template.put("options", 0);
        }
        template.put("curSpanX", spanX);
        template.put("curSpanY", spanY);
        template.put("spanX", spanX);
        template.put("spanY", spanY);
        template.put("container", DESKTOP);
        template.put("new_container", DESKTOP);
        template.put("recommendId", -1);
        if (spanX == 1 && spanY == 1) {
            template.put("options", 0);
        } else if (!matchedSpan) {
            template.put("options", 16);
        }
        return template;
    }

    private static void folderChildPosition(JSONObject item, int folderId, int rank) throws JSONException {
        item.put("container", folderId);
        item.put("new_container", folderId);
        item.put("screenId", SCREEN_BASE_ID);
        item.put("screen", 0);
        item.put("new_screen", 0);
        item.put("cellX", rank % FOLDER_COLUMNS);
        item.put("cellY", (rank / FOLDER_COLUMNS) % FOLDER_ROWS);
        item.put("new_cellX", item.optInt("cellX", 0));
        item.put("new_cellY", item.optInt("cellY", 0));
        item.put("rank", rank);
        item.put("new_rank", rank);
    }

    private static void desktopPosition(JSONObject item, int screen, int cellX, int cellY, int gridWidth) throws JSONException {
        item.put("screen", screen);
        item.put("screenId", SCREEN_BASE_ID + screen);
        item.put("cellX", cellX);
        item.put("cellY", cellY);
        item.put("new_screen", screen);
        item.put("new_cellX", cellX);
        item.put("new_cellY", cellY);
        item.put("new_rank", cellY * gridWidth + cellX);
    }

    private static void standalonePosition(JSONObject item, int screen, int cellX, int cellY, int gridWidth) throws JSONException {
        item.put("container", DESKTOP);
        item.put("new_container", DESKTOP);
        item.put("spanX", 1);
        item.put("spanY", 1);
        if (item.has("curSpanX")) {
            item.put("curSpanX", 1);
        }
        if (item.has("curSpanY")) {
            item.put("curSpanY", 1);
        }
        desktopPosition(item, screen, cellX, cellY, gridWidth);
        item.put("rank", item.optInt("new_rank", 0));
    }

    private static void ensureScreens(JSONObject mode, int maxScreen) throws JSONException {
        JSONArray screens = new JSONArray();
        for (int screen = 0; screen <= Math.max(0, maxScreen); screen++) {
            JSONObject item = new JSONObject();
            item.put("_id", screen + 1);
            item.put("screenId", SCREEN_BASE_ID + screen);
            item.put("screenNum", screen);
            item.put("new_id", screen);
            item.put("screenRank", screen);
            screens.put(item);
        }
        mode.put("SCREENS", screens);
    }

    private static JSONArray sortedItems(JSONArray source) {
        ArrayList<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null) {
                items.add(item);
            }
        }
        Collections.sort(items, (left, right) -> {
            int result = Integer.compare(intValue(left.opt("container"), 0), intValue(right.opt("container"), 0));
            if (result != 0) {
                return result;
            }
            result = Integer.compare(left.optInt("rank", 0), right.optInt("rank", 0));
            if (result != 0) {
                return result;
            }
            return Integer.compare(left.optInt("_id", 0), right.optInt("_id", 0));
        });
        JSONArray result = new JSONArray();
        for (JSONObject item : items) {
            result.put(item);
        }
        return result;
    }

    private static ModeSummary summarizeMode(JSONObject mode) {
        HashMap<Integer, JSONObject> folders = new HashMap<>();
        JSONArray folderArray = mode.optJSONArray("FOLDERS");
        if (folderArray != null) {
            for (int i = 0; i < folderArray.length(); i++) {
                JSONObject folder = folderArray.optJSONObject(i);
                if (folder != null) {
                    folders.put(folder.optInt("_id", 0), folder);
                }
            }
        }

        HashMap<Integer, Integer> children = new HashMap<>();
        for (Integer folderId : folders.keySet()) {
            children.put(folderId, 0);
        }
        ModeSummary summary = new ModeSummary();
        summary.screens = mode.optJSONArray("SCREENS") == null ? 0 : mode.optJSONArray("SCREENS").length();
        summary.folders = folders.size();
        for (JSONObject folder : folders.values()) {
            int[] span = normalizeSpan(folder);
            String key = span[0] + "x" + span[1];
            summary.folderSpanCounts.put(key, summary.folderSpanCounts.containsKey(key) ? summary.folderSpanCounts.get(key) + 1 : 1);
        }

        for (String section : modeSections(mode)) {
            JSONArray items = mode.optJSONArray(section);
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                int container = intValue(item.opt("container"), DESKTOP);
                if (container == DESKTOP) {
                    summary.desktopItems++;
                } else if (container == HOTSEAT) {
                    summary.hotseatItems++;
                } else if (children.containsKey(container)) {
                    children.put(container, children.get(container) + 1);
                }
            }
        }
        ArrayList<Integer> folderIds = new ArrayList<>(children.keySet());
        Collections.sort(folderIds, Comparator.comparing(id -> cleanTitle(folders.get(id).optString("title", ""))));
        for (Integer folderId : folderIds) {
            summary.folderChildCounts.put(cleanTitle(folders.get(folderId).optString("title", "")), children.get(folderId));
            summary.folderChildren += children.get(folderId);
        }
        return summary;
    }

    private static String reportText(LinkedHashMap<String, ModeReport> reports, JSONObject root) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Layout report\n\n");
        for (ModeReport report : reports.values()) {
            ModeSummary summary = report.summary;
            builder.append("## ").append(report.modeName).append("\n\n");
            builder.append("- Screens: ").append(summary.screens).append("\n");
            builder.append("- Folders: ").append(summary.folders).append("\n");
            builder.append("- Folder sizes: ").append(summary.folderSpanSummary()).append("\n");
            builder.append("- Desktop items: ").append(summary.desktopItems).append("\n");
            builder.append("- Hotseat items: ").append(summary.hotseatItems).append("\n");
            builder.append("- Folder children: ").append(summary.folderChildren).append("\n\n");
            builder.append("| Category | Items |\n");
            builder.append("| --- | ---: |\n");
            for (Map.Entry<String, Integer> entry : summary.folderChildCounts.entrySet()) {
                builder.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
            builder.append("\n");
            for (Map.Entry<String, List<String>> entry : report.categories.entrySet()) {
                builder.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
                for (String label : entry.getValue()) {
                    builder.append("- ").append(label).append("\n");
                }
                builder.append("\n");
            }
        }
        return builder.toString();
    }

    private static LinkedHashMap<String, List<String>> toCategoryLabels(LinkedHashMap<String, ArrayList<ItemRef>> categorized) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayList<ItemRef>> entry : categorized.entrySet()) {
            ArrayList<String> labels = new ArrayList<>();
            for (ItemRef ref : entry.getValue()) {
                labels.add(itemLabel(ref.item));
            }
            result.put(entry.getKey(), labels);
        }
        return result;
    }

    private static void addWidgetPlanItems(LayoutPlan plan, JSONObject mode) {
        for (String section : new String[]{"WIDGETS", "CARD"}) {
            JSONArray items = mode.optJSONArray(section);
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || intValue(item.opt("container"), DESKTOP) != DESKTOP) {
                    continue;
                }
                int[] span = normalizeSpan(item);
                String title = cleanTitle(item.optString("title", ""));
                if (title.isEmpty()) {
                    title = "CARD".equals(section) ? "Card" : "Widget";
                }
                plan.items.add(new PlanItem(
                        widgetId(section, item),
                        title,
                        section.toLowerCase(Locale.US),
                        true,
                        item.optInt("screen", item.optInt("new_screen", 0)),
                        item.optInt("cellX", item.optInt("new_cellX", 0)),
                        item.optInt("cellY", item.optInt("new_cellY", 0)),
                        span[0],
                        span[1],
                        0));
            }
        }
    }

    private static int maxPlacementScreen(ArrayList<FolderPlacement> placements) {
        int max = 0;
        for (FolderPlacement placement : placements) {
            max = Math.max(max, placement.screen);
        }
        return max;
    }

    private static int maxItemId(JSONObject mode) {
        int max = 0;
        Iterator<String> keys = mode.keys();
        while (keys.hasNext()) {
            Object value = mode.opt(keys.next());
            if (!(value instanceof JSONArray)) {
                continue;
            }
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    max = Math.max(max, item.optInt("_id", 0));
                }
            }
        }
        return max;
    }

    private static int maxScreen(Map<Integer, Set<String>> occupied) {
        int max = 0;
        for (Integer screen : occupied.keySet()) {
            max = Math.max(max, screen);
        }
        return max;
    }

    private static int[] normalizeSpan(JSONObject item) {
        return new int[]{
                Math.max(1, item.optInt("spanX", 1)),
                Math.max(1, item.optInt("spanY", 1))
        };
    }

    private static String itemLabel(JSONObject item) {
        String title = cleanTitle(item.optString("title", ""));
        String packageName = cleanTitle(item.optString("packageName", ""));
        return packageName.isEmpty() ? title : String.format(Locale.US, "%s (%s)", title, packageName);
    }

    private static String packageName(JSONObject item) {
        return cleanTitle(item.optString("packageName", ""));
    }

    private static String widgetId(String section, JSONObject item) {
        return "widget:" + section + ":" + item.optInt("_id", item.optInt("appWidgetId", 0));
    }

    private static String cleanTitle(String value) {
        return (value == null ? "" : value).replace("\u00a0", "").trim();
    }

    private static String cellKey(int x, int y) {
        return x + ":" + y;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class OrganizeResult {
        public final String json;
        public final LinkedHashMap<String, ModeReport> reports;
        public final String reportText;

        OrganizeResult(String json, LinkedHashMap<String, ModeReport> reports, String reportText) {
            this.json = json;
            this.reports = reports;
            this.reportText = reportText;
        }
    }

    public static final class LayoutSummary {
        public final LinkedHashMap<String, ModeSummary> modes = new LinkedHashMap<>();
    }

    public static final class DesignOverrides {
        public final LinkedHashMap<String, Placement> widgetPlacements = new LinkedHashMap<>();
    }

    public static final class Placement {
        public final int screen;
        public final int cellX;
        public final int cellY;

        public Placement(int screen, int cellX, int cellY) {
            this.screen = Math.max(0, screen);
            this.cellX = Math.max(0, cellX);
            this.cellY = Math.max(0, cellY);
        }
    }

    public static final class LayoutPlan {
        public final int gridWidth;
        public final int gridHeight;
        public final int screenCount;
        public final ArrayList<PlanItem> items = new ArrayList<>();

        LayoutPlan(int gridWidth, int gridHeight, int screenCount) {
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.screenCount = screenCount;
        }
    }

    public static final class PlanItem {
        public final String id;
        public final String title;
        public final String type;
        public final boolean widget;
        public final int screen;
        public final int cellX;
        public final int cellY;
        public final int spanX;
        public final int spanY;
        public final int childCount;

        PlanItem(String id, String title, String type, boolean widget, int screen, int cellX, int cellY, int spanX, int spanY, int childCount) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.widget = widget;
            this.screen = screen;
            this.cellX = cellX;
            this.cellY = cellY;
            this.spanX = Math.max(1, spanX);
            this.spanY = Math.max(1, spanY);
            this.childCount = childCount;
        }
    }

    public static final class ModeReport {
        public final String modeName;
        public final LinkedHashMap<String, List<String>> categories = new LinkedHashMap<>();
        public ModeSummary summary = new ModeSummary();

        ModeReport(String modeName) {
            this.modeName = modeName;
        }
    }

    public static final class ModeSummary {
        public int screens;
        public int folders;
        public int desktopItems;
        public int hotseatItems;
        public int folderChildren;
        public final LinkedHashMap<String, Integer> folderSpanCounts = new LinkedHashMap<>();
        public final LinkedHashMap<String, Integer> folderChildCounts = new LinkedHashMap<>();

        public String folderSpanSummary() {
            if (folderSpanCounts.isEmpty()) {
                return "-";
            }
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, Integer> entry : folderSpanCounts.entrySet()) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(entry.getKey()).append(": ").append(entry.getValue());
            }
            return builder.toString();
        }
    }

    private static final class ItemRef {
        final String section;
        final JSONObject item;

        ItemRef(String section, JSONObject item) {
            this.section = section;
            this.item = item;
        }
    }

    private static final class FolderGroup {
        final String category;
        final ArrayList<ItemRef> items;
        final LayoutSpec spec;
        final int ruleOrder;

        FolderGroup(String category, ArrayList<ItemRef> items, LayoutSpec spec, int ruleOrder) {
            this.category = category;
            this.items = items;
            this.spec = spec;
            this.ruleOrder = ruleOrder;
        }

        boolean createsFolder() {
            return items.size() > 1;
        }
    }

    private static final class FolderPlacement {
        final FolderGroup group;
        final int screen;
        final int cellX;
        final int cellY;

        FolderPlacement(FolderGroup group, int screen, int cellX, int cellY) {
            this.group = group;
            this.screen = screen;
            this.cellX = cellX;
            this.cellY = cellY;
        }
    }
}
