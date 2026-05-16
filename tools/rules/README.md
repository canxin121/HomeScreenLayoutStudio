# 规则文件

这里放可以复用的规则数据。规则不写进具体脚本，脚本只负责加载规则并执行操作。

## 当前规则

- `launcher_app_categories.json`：桌面应用分类规则，用于把桌面布局里的应用和快捷方式整理进分类文件夹。

## 格式

规则文件是 JSON：

```json
{
  "name": "launcher_app_categories",
  "fallback": "待确认",
  "field_aliases": {
    "title": ["app_title", "label", "name"],
    "package": ["packageName", "app_packageName", "package_name", "id"]
  },
  "rules": [
    {
      "name": "AI 助手",
      "layout": {
        "span": [2, 2],
        "priority": 980,
        "preferred_screen": 0
      },
      "contains": {
        "title": ["ChatGPT"],
        "package": ["com.openai."]
      }
    }
  ]
}
```

## 匹配逻辑

- 规则按 `rules` 中的顺序匹配，命中第一条后停止。
- 一个规则内支持 `equals`、`prefix`、`contains` 三种匹配方式。
- `field_aliases` 用来把不同数据源的字段统一起来。例如规则写 `package`，实际数据可以是 `packageName`、`app_packageName` 或 `id`。
- 没命中的项目会进入 `fallback`，当前是“待确认”。

## 版式字段

每个规则可以带 `layout`，用于主桌面 `LAYOUT` 的美化编排：

- `span`：文件夹在桌面网格中占据的大小，例如 `[2, 2]` 是大文件夹，`[1, 1]` 是小文件夹。
- `priority`：放置优先级，数值越大越靠前，也越倾向保留为大文件夹。
- `preferred_screen`：页面倾向，第一页是 `0`。这是软提示；脚本会优先填满前面的页面，不会为了它跳过前面的空位。

整理脚本会先保留并上移已有小组件/卡片，再按 `priority` 从前往后放置文件夹，同时检查组件和文件夹的矩形占位，避免重叠。若大文件夹过多导致最后一页太空，脚本会自动把低优先级的大文件夹降为 `1x1`，尽量让前面的页面被填满。

## 使用

默认使用内置规则：

```bash
python3 tools/launcher_layout_editor.py organize 'temp/桌面备份 05-17 00-02.json' \
  -o 'temp/桌面布局 分类整理版 05-17.json' \
  -r 'temp/桌面布局 分类整理报告 05-17.md'
```

指定另一套规则：

```bash
python3 tools/launcher_layout_editor.py organize input.json \
  -o output.json \
  -r report.md \
  --rules tools/rules/launcher_app_categories.json
```
