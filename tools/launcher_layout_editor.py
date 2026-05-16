#!/usr/bin/env python3
"""Inspect and reorganize OPlus Launcher layout backup JSON files.

The module app imports launcher backups through the launcher's own restore path.
This helper keeps that JSON format intact while moving applications and
shortcuts into clearer folders.
"""

from __future__ import annotations

import argparse
import copy
import json
from collections import Counter, OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

from rule_engine import RuleSet


DESKTOP = -100
HOTSEAT = -101
SCREEN_BASE_ID = 1000
FOLDER_COLUMNS = 3
FOLDER_ROWS = 4
DEFAULT_RULES_PATH = Path(__file__).resolve().with_name("rules") / "launcher_app_categories.json"


@dataclass(frozen=True)
class FolderLayoutSpec:
    span_x: int
    span_y: int
    priority: int
    preferred_screen: int | None

    @property
    def area(self) -> int:
        return self.span_x * self.span_y


@dataclass(frozen=True)
class FolderGroup:
    category: str
    items: list[tuple[str, dict[str, Any]]]
    spec: FolderLayoutSpec
    rule_order: int

    @property
    def creates_folder(self) -> bool:
        return len(self.items) > 1


@dataclass(frozen=True)
class FolderPlacement:
    group: FolderGroup
    screen: int
    cell_x: int
    cell_y: int


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=OrderedDict)


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def load_rules(path: Path) -> RuleSet:
    return RuleSet.from_file(path)


def clean_title(value: Any) -> str:
    return str(value or "").replace("\u00a0", "").strip()


def item_label(item: dict[str, Any]) -> str:
    title = clean_title(item.get("title"))
    package = clean_title(item.get("packageName"))
    return f"{title} ({package})" if package else title


def classify_item(item: dict[str, Any], rules: RuleSet) -> str:
    return rules.classify(item)


def grid_size(mode: dict[str, Any]) -> tuple[int, int]:
    params = mode.get("MODE_PARAMETERS") or mode.get("LAYOUT_PARAMETERS") or {}
    return int(params.get("cellCountX", 4) or 4), int(params.get("cellCountY", 6) or 6)


def mode_sections(mode: dict[str, Any]) -> Iterable[str]:
    for section in ("APPLICATIONS", "SHORTCUTS"):
        if isinstance(mode.get(section), list):
            yield section


def all_mode_items(mode: dict[str, Any]) -> Iterable[dict[str, Any]]:
    for value in mode.values():
        if isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    yield item


def max_item_id(mode: dict[str, Any]) -> int:
    values = [int(item.get("_id", 0)) for item in all_mode_items(mode) if str(item.get("_id", "")).isdigit()]
    return max(values, default=0)


def normalize_span(item: Mapping[str, Any]) -> tuple[int, int]:
    return max(1, int(item.get("spanX", 1) or 1)), max(1, int(item.get("spanY", 1) or 1))


def layout_mapping(rule_metadata: Mapping[str, Any]) -> Mapping[str, Any]:
    layout = rule_metadata.get("layout", {})
    return layout if isinstance(layout, Mapping) else {}


def int_from_layout(layout: Mapping[str, Any], key: str, default: int) -> int:
    try:
        return int(layout.get(key, default))
    except (TypeError, ValueError):
        return default


def span_from_layout(layout: Mapping[str, Any], cell_x: int, cell_y: int) -> tuple[int, int]:
    value = layout.get("span", [1, 1])
    if not isinstance(value, list | tuple) or len(value) != 2:
        return 1, 1
    try:
        span_x = int(value[0])
        span_y = int(value[1])
    except (TypeError, ValueError):
        return 1, 1
    span_x = min(max(1, span_x), cell_x)
    span_y = min(max(1, span_y), cell_y)
    return span_x, span_y


def layout_spec_for(category: str, item_count: int, rules: RuleSet, mode_name: str, cell_x: int, cell_y: int) -> FolderLayoutSpec:
    rule = rules.rule_by_name(category)
    layout = layout_mapping(rule.metadata) if rule is not None else {}
    priority = int_from_layout(layout, "priority", item_count)

    if mode_name == "LAYOUT":
        if item_count <= 1:
            span_x, span_y = 1, 1
        else:
            requested_x, requested_y = span_from_layout(layout, cell_x, cell_y)
            span_x = max(2, requested_x)
            span_y = max(2, requested_y)
        preferred_raw = layout.get("preferred_screen")
        try:
            preferred_screen = int(preferred_raw) if preferred_raw is not None else None
        except (TypeError, ValueError):
            preferred_screen = None
    else:
        span_x, span_y = 1, 1
        preferred_screen = None

    return FolderLayoutSpec(span_x, span_y, priority, preferred_screen)


def folder_template(mode: dict[str, Any], span_x: int, span_y: int) -> OrderedDict[str, Any]:
    folders = mode.get("FOLDERS")
    matched_span = False
    if isinstance(folders, list) and folders:
        template_source = folders[0]
        for folder in folders:
            if normalize_span(folder) == (span_x, span_y):
                template_source = folder
                matched_span = True
                break
        template = copy.deepcopy(template_source)
    else:
        template = OrderedDict()
        template.update({
            "_id": 0,
            "container": DESKTOP,
            "screenId": SCREEN_BASE_ID,
            "screen": 0,
            "cellX": 0,
            "cellY": 0,
            "new_container": DESKTOP,
            "new_screen": 0,
            "new_cellX": 0,
            "new_cellY": 0,
            "new_rank": 0,
            "user_id": 0,
            "profileId": 0,
            "restored": 0,
            "title": "",
            "curSpanX": 1,
            "curSpanY": 1,
            "spanX": 1,
            "spanY": 1,
            "recommendId": -1,
            "options": 0,
        })
    for key in ("curSpanX", "curSpanY", "spanX", "spanY"):
        template[key] = span_x if key.endswith("X") else span_y
    template["container"] = DESKTOP
    template["new_container"] = DESKTOP
    template["recommendId"] = -1
    if span_x == 1 and span_y == 1:
        template["options"] = 0
    elif not matched_span:
        template["options"] = 16
    return template


def folder_child_position(item: dict[str, Any], folder_id: int, rank: int) -> None:
    item["container"] = folder_id
    item["new_container"] = folder_id
    item["screenId"] = SCREEN_BASE_ID
    item["screen"] = 0
    item["new_screen"] = 0
    item["cellX"] = rank % FOLDER_COLUMNS
    item["cellY"] = (rank // FOLDER_COLUMNS) % FOLDER_ROWS
    item["new_cellX"] = item["cellX"]
    item["new_cellY"] = item["cellY"]
    item["rank"] = rank
    item["new_rank"] = rank


def desktop_position(item: dict[str, Any], screen: int, cell_x: int, cell_y: int, grid_width: int) -> None:
    item["screen"] = screen
    item["screenId"] = SCREEN_BASE_ID + screen
    item["cellX"] = cell_x
    item["cellY"] = cell_y
    item["new_screen"] = screen
    item["new_cellX"] = cell_x
    item["new_cellY"] = cell_y
    item["new_rank"] = cell_y * grid_width + cell_x


def standalone_position(item: dict[str, Any], screen: int, cell_x: int, cell_y: int, grid_width: int) -> None:
    item["container"] = DESKTOP
    item["new_container"] = DESKTOP
    item["spanX"] = 1
    item["spanY"] = 1
    if "curSpanX" in item:
        item["curSpanX"] = 1
    if "curSpanY" in item:
        item["curSpanY"] = 1
    desktop_position(item, screen, cell_x, cell_y, grid_width)
    item["rank"] = item["new_rank"]


def arrange_desktop_widgets(mode: dict[str, Any], mode_name: str) -> None:
    if mode_name != "LAYOUT":
        return

    cell_x, cell_y = grid_size(mode)
    cards = [item for item in mode.get("CARD", []) or [] if item.get("container") == DESKTOP]

    two_by_two_cards = [
        item for item in cards
        if int(item.get("screen", 0)) == 0 and normalize_span(item) == (2, 2)
    ]
    for index, item in enumerate(sorted(two_by_two_cards, key=lambda value: int(value.get("_id", 0)))):
        next_y = index * 2
        if next_y + 2 <= cell_y:
            desktop_position(item, 0, 0, next_y, cell_x)

    for item in cards:
        span_x, span_y = normalize_span(item)
        if span_x == cell_x and span_y <= cell_y:
            desktop_position(item, int(item.get("screen", 0)), 0, 0, cell_x)


def occupied_cells(mode: dict[str, Any]) -> dict[int, set[tuple[int, int]]]:
    occupied: dict[int, set[tuple[int, int]]] = {}
    for section in ("WIDGETS", "CARD"):
        for item in mode.get(section, []) or []:
            if item.get("container") != DESKTOP:
                continue
            screen = int(item.get("screen", item.get("new_screen", 0)))
            start_x = int(item.get("cellX", item.get("new_cellX", 0)))
            start_y = int(item.get("cellY", item.get("new_cellY", 0)))
            span_x = max(1, int(item.get("spanX", 1)))
            span_y = max(1, int(item.get("spanY", 1)))
            cells = occupied.setdefault(screen, set())
            for x in range(start_x, start_x + span_x):
                for y in range(start_y, start_y + span_y):
                    cells.add((x, y))
    return occupied


def can_place(
    occupied: dict[int, set[tuple[int, int]]],
    *,
    screen: int,
    cell_x: int,
    cell_y: int,
    span_x: int,
    span_y: int,
    grid_width: int,
    grid_height: int,
) -> bool:
    if cell_x < 0 or cell_y < 0 or cell_x + span_x > grid_width or cell_y + span_y > grid_height:
        return False
    screen_cells = occupied.setdefault(screen, set())
    for x in range(cell_x, cell_x + span_x):
        for y in range(cell_y, cell_y + span_y):
            if (x, y) in screen_cells:
                return False
    return True


def occupy(
    occupied: dict[int, set[tuple[int, int]]],
    *,
    screen: int,
    cell_x: int,
    cell_y: int,
    span_x: int,
    span_y: int,
) -> None:
    screen_cells = occupied.setdefault(screen, set())
    for x in range(cell_x, cell_x + span_x):
        for y in range(cell_y, cell_y + span_y):
            screen_cells.add((x, y))


def placement_screens(preferred_screen: int | None) -> Iterable[int]:
    if preferred_screen is not None:
        screen = preferred_screen
        while True:
            yield screen
            screen += 1
    screen = 0
    while True:
        yield screen
        screen += 1


def place_folder_group(
    group: FolderGroup,
    occupied: dict[int, set[tuple[int, int]]],
    *,
    grid_width: int,
    grid_height: int,
) -> FolderPlacement:
    for screen in placement_screens(None):
        for y in range(grid_height):
            for x in range(grid_width):
                if can_place(
                    occupied,
                    screen=screen,
                    cell_x=x,
                    cell_y=y,
                    span_x=group.spec.span_x,
                    span_y=group.spec.span_y,
                    grid_width=grid_width,
                    grid_height=grid_height,
                ):
                    occupy(
                        occupied,
                        screen=screen,
                        cell_x=x,
                        cell_y=y,
                        span_x=group.spec.span_x,
                        span_y=group.spec.span_y,
                    )
                    return FolderPlacement(group, screen, x, y)
    raise RuntimeError(f"No desktop slot found for {group.category}.")


def selected_large_group_names(
    groups: list[FolderGroup],
    occupied: Mapping[int, set[tuple[int, int]]],
    *,
    grid_width: int,
    grid_height: int,
) -> set[str]:
    large_candidates = sorted(
        [group for group in groups if group.spec.area > 1],
        key=lambda group: (-group.spec.priority, group.rule_order),
    )
    if not large_candidates:
        return set()

    grid_area = grid_width * grid_height
    fixed_cells = sum(len(cells) for cells in occupied.values())
    base_folder_cells = len(groups)
    min_last_page_fill = 0.75

    for large_count in range(len(large_candidates), -1, -1):
        selected = large_candidates[:large_count]
        extra_cells = sum(group.spec.area - 1 for group in selected)
        total_cells = fixed_cells + base_folder_cells + extra_cells
        page_count = max(1, (total_cells + grid_area - 1) // grid_area)
        last_page_cells = total_cells - (page_count - 1) * grid_area
        if last_page_cells / grid_area >= min_last_page_fill:
            return {group.category for group in selected}

    return {large_candidates[0].category}


def rebalance_folder_sizes(
    groups: list[FolderGroup],
    occupied: Mapping[int, set[tuple[int, int]]],
    *,
    mode_name: str,
    grid_width: int,
    grid_height: int,
) -> list[FolderGroup]:
    if mode_name != "LAYOUT":
        return groups

    selected_large_names = selected_large_group_names(
        groups,
        occupied,
        grid_width=grid_width,
        grid_height=grid_height,
    )
    balanced: list[FolderGroup] = []
    for group in groups:
        if group.spec.area <= 1 or group.category in selected_large_names:
            balanced.append(group)
            continue
        balanced.append(FolderGroup(
            category=group.category,
            items=group.items,
            spec=FolderLayoutSpec(1, 1, group.spec.priority, group.spec.preferred_screen),
            rule_order=group.rule_order,
        ))
    return balanced


def ensure_screens(mode: dict[str, Any], max_screen: int) -> None:
    screens = []
    for screen in range(max_screen + 1):
        screens.append(OrderedDict({
            "_id": screen + 1,
            "screenId": SCREEN_BASE_ID + screen,
            "screenNum": screen,
            "new_id": screen,
            "screenRank": screen,
        }))
    mode["SCREENS"] = screens


def organize_mode(
    mode: dict[str, Any],
    *,
    mode_name: str,
    max_folders_per_screen: int,
    rules: RuleSet,
) -> OrderedDict[str, list[str]]:
    del max_folders_per_screen
    arrange_desktop_widgets(mode, mode_name)
    cell_x, cell_y = grid_size(mode)

    categorized: OrderedDict[str, list[tuple[str, dict[str, Any]]]] = OrderedDict()
    for category_name in rules.category_names():
        categorized[category_name] = []
    categorized[rules.fallback] = []

    for section in mode_sections(mode):
        for item in mode.get(section, []) or []:
            if item.get("container") == HOTSEAT:
                continue
            category = classify_item(item, rules)
            categorized.setdefault(category, []).append((section, item))

    categorized = OrderedDict((name, items) for name, items in categorized.items() if items)
    rule_order = {name: index for index, name in enumerate(rules.category_names())}
    groups = [
        FolderGroup(
            category=name,
            items=items,
            spec=layout_spec_for(name, len(items), rules, mode_name, cell_x, cell_y),
            rule_order=rule_order.get(name, len(rule_order)),
        )
        for name, items in categorized.items()
    ]
    occupied = occupied_cells(mode)
    groups = rebalance_folder_sizes(
        groups,
        occupied,
        mode_name=mode_name,
        grid_width=cell_x,
        grid_height=cell_y,
    )
    groups.sort(key=lambda group: (
        -group.spec.priority,
        -group.spec.area,
        group.spec.preferred_screen if group.spec.preferred_screen is not None else 99,
        group.rule_order,
    ))

    next_id = max_item_id(mode) + 1
    new_folders: list[dict[str, Any]] = []
    placements = [
        place_folder_group(group, occupied, grid_width=cell_x, grid_height=cell_y)
        for group in groups
    ]
    max_screen = max(occupied.keys(), default=0)

    for placement in placements:
        category = placement.group.category
        items = placement.group.items
        spec = placement.group.spec
        folder_id = next_id
        next_id += 1

        if placement.group.creates_folder:
            folder = folder_template(mode, spec.span_x, spec.span_y)
            folder["_id"] = folder_id
            folder["title"] = category
            desktop_position(folder, placement.screen, placement.cell_x, placement.cell_y, cell_x)
            new_folders.append(folder)

            for rank, (_section, item) in enumerate(items):
                folder_child_position(item, folder_id, rank)
        else:
            _section, item = items[0]
            standalone_position(item, placement.screen, placement.cell_x, placement.cell_y, cell_x)

    mode["FOLDERS"] = new_folders
    for section in mode_sections(mode):
        mode[section] = sorted(
            mode.get(section, []),
            key=lambda item: (int(item.get("container", 0)), int(item.get("rank", 0)), int(item.get("_id", 0))),
        )

    occupied_max_screen = max(occupied_cells(mode).keys(), default=0)
    ensure_screens(mode, max(max_screen, occupied_max_screen))
    return OrderedDict((name, [item_label(item) for _section, item in items]) for name, items in categorized.items())


def summarize_mode(mode: dict[str, Any]) -> dict[str, Any]:
    folders = {item.get("_id"): item for item in mode.get("FOLDERS", []) or []}
    children = {folder_id: 0 for folder_id in folders}
    folder_span_counts = Counter(f"{normalize_span(folder)[0]}x{normalize_span(folder)[1]}" for folder in folders.values())
    desktop = 0
    hotseat = 0
    for section in mode_sections(mode):
        for item in mode.get(section, []) or []:
            container = item.get("container")
            if container == DESKTOP:
                desktop += 1
            elif container == HOTSEAT:
                hotseat += 1
            elif container in children:
                children[container] += 1
    return {
        "screens": len(mode.get("SCREENS", []) or []),
        "folders": len(folders),
        "desktop_apps": desktop,
        "hotseat_apps": hotseat,
        "folder_children": sum(children.values()),
        "folder_span_counts": OrderedDict(sorted(folder_span_counts.items())),
        "folder_child_counts": OrderedDict(
            (clean_title(folders[folder_id].get("title")), count)
            for folder_id, count in sorted(children.items(), key=lambda pair: clean_title(folders[pair[0]].get("title")))
        ),
    }


def folder_span_summary(summary: Mapping[str, Any]) -> str:
    span_counts = summary.get("folder_span_counts", {})
    return "，".join(f"{span}：{count}" for span, count in span_counts.items())


def inspect_file(path: Path, modes: list[str]) -> None:
    data = load_json(path)
    print(f"File: {path}")
    for mode_name in modes:
        mode = data.get(mode_name)
        if not isinstance(mode, dict):
            continue
        summary = summarize_mode(mode)
        print(f"\n[{mode_name}]")
        print(f"  screens: {summary['screens']}")
        print(f"  folders: {summary['folders']}")
        print(f"  folder spans: {folder_span_summary(summary)}")
        print(f"  desktop apps/shortcuts: {summary['desktop_apps']}")
        print(f"  hotseat apps: {summary['hotseat_apps']}")
        print(f"  folder children: {summary['folder_children']}")
        for folder_name, count in summary["folder_child_counts"].items():
            print(f"  - {folder_name}: {count}")


def print_rule_summary(path: Path) -> None:
    rules = load_rules(path)
    print(f"Rules: {rules.name}")
    print(f"File: {path}")
    print(f"Fallback: {rules.fallback}")
    print(f"Categories: {len(rules.rules)}")
    for index, rule in enumerate(rules.rules, start=1):
        keyword_count = sum(len(keywords) for match_map in (rule.equals, rule.prefix, rule.contains) for keywords in match_map.values())
        layout = layout_mapping(rule.metadata)
        span = layout.get("span", [1, 1])
        priority = layout.get("priority", 0)
        preferred_screen = layout.get("preferred_screen", "-")
        print(f"  {index:02d}. {rule.name}: {keyword_count} keywords, span={span}, priority={priority}, screen={preferred_screen}")


def report_text(source: Path, output: Path, reports: dict[str, OrderedDict[str, list[str]]], data: dict[str, Any], rules: RuleSet) -> str:
    lines = [
        "# 桌面布局整理报告",
        "",
        f"- 输入：`{source}`",
        f"- 输出：`{output}`",
        f"- 规则：`{rules.path or rules.name}`",
        "",
    ]
    for mode_name, categories in reports.items():
        summary = summarize_mode(data[mode_name])
        lines.extend([
            f"## {mode_name}",
            "",
            f"- 页面数：{summary['screens']}",
            f"- 文件夹数：{summary['folders']}",
            f"- 文件夹尺寸：{folder_span_summary(summary)}",
            f"- 桌面独立项目：{summary['desktop_apps']}",
            f"- 底部常驻栏应用：{summary['hotseat_apps']}",
            f"- 已放入文件夹的项目：{summary['folder_children']}",
            "",
            "| 文件夹 | 数量 |",
            "| --- | ---: |",
        ])
        for folder_name, count in summary["folder_child_counts"].items():
            lines.append(f"| {folder_name} | {count} |")
        lines.append("")
        lines.append("<details><summary>分类明细</summary>")
        lines.append("")
        for category, items in categories.items():
            lines.append(f"### {category}（{len(items)}）")
            for item in items:
                lines.append(f"- {item}")
            lines.append("")
        lines.append("</details>")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def organize(
    input_path: Path,
    output_path: Path,
    report_path: Path,
    modes: list[str],
    max_folders_per_screen: int,
    rules_path: Path,
) -> None:
    rules = load_rules(rules_path)
    data = load_json(input_path)
    reports: dict[str, OrderedDict[str, list[str]]] = {}
    for mode_name in modes:
        mode = data.get(mode_name)
        if not isinstance(mode, dict):
            continue
        reports[mode_name] = organize_mode(
            mode,
            mode_name=mode_name,
            max_folders_per_screen=max_folders_per_screen,
            rules=rules,
        )
    write_json(output_path, data)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report_text(input_path, output_path, reports, data, rules), encoding="utf-8")


def parse_modes(value: str) -> list[str]:
    return [mode.strip() for mode in value.split(",") if mode.strip()]


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect and reorganize launcher backup JSON.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    organize_parser = subparsers.add_parser("organize", help="Create a categorized layout JSON.")
    organize_parser.add_argument("input", type=Path)
    organize_parser.add_argument("-o", "--output", type=Path, required=True)
    organize_parser.add_argument("-r", "--report", type=Path, required=True)
    organize_parser.add_argument("--rules", type=Path, default=DEFAULT_RULES_PATH)
    organize_parser.add_argument("--modes", default="LAYOUT,LAYOUT_DRAW,LAYOUT_SIMPLE")
    organize_parser.add_argument("--max-folders-per-screen", type=int, default=8)

    inspect_parser = subparsers.add_parser("inspect", help="Print a compact layout summary.")
    inspect_parser.add_argument("input", type=Path)
    inspect_parser.add_argument("--modes", default="LAYOUT,LAYOUT_DRAW,LAYOUT_SIMPLE")

    rules_parser = subparsers.add_parser("rules", help="Print a compact rule summary.")
    rules_parser.add_argument("--rules", type=Path, default=DEFAULT_RULES_PATH)

    args = parser.parse_args()
    if args.command == "organize":
        organize(
            args.input,
            args.output,
            args.report,
            parse_modes(args.modes),
            args.max_folders_per_screen,
            args.rules,
        )
    elif args.command == "inspect":
        inspect_file(args.input, parse_modes(args.modes))
    elif args.command == "rules":
        print_rule_summary(args.rules)


if __name__ == "__main__":
    main()
