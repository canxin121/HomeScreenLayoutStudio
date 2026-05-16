# Home Screen Layout Studio / 桌面布局工坊

Modern libxposed API 101 module for importing, exporting, managing, and
editing `com.android.launcher` home screen layouts.

## Build

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

## LSPosed Metadata

The module uses modern API 101 metadata:

```text
app/src/main/resources/META-INF/xposed/java_init.list
app/src/main/resources/META-INF/xposed/module.prop
app/src/main/resources/META-INF/xposed/scope.list
```

Scope is fixed to:

```text
com.android.launcher
```

## Features

- Export current launcher layout to JSON.
- Import external JSON into the module layout store.
- Dry-run parse imported JSON inside the launcher process.
- Apply imported JSON through the launcher's own restore path.
- Automatically export a `before-apply-*.json` backup before applying.
- Manage saved layouts in the module app: select, delete, rename, inspect, save copy.
- Organize a saved layout on device with the same category, priority, page, and folder-size policy that was previously only available through helper scripts.
- Edit category rules in the app: category name, title keywords, package keywords, priority, preferred page, and folder span.
- Scan launchable apps installed on the phone, find apps that do not match any rule, and add them to a category from the app.
- Add an installed app to a category as an exact package rule, so manual grouping wins over broad title/package keywords.
- Edit app order inside a category manually, or sort the category by app name.
- Use a visual desktop-grid editor to swipe between launcher pages, long-press and release category folders/widgets to edit them, or drag after long-press to place them on cells before generating a layout.
- Dragging a folder or widget can push overlapping folders/widgets into the freed original cell or later empty cells; resizing a folder never pushes other items.
- Use a multi-page app shell: layout library, layout design, rules, and settings.
- Choose light mode, dark mode, system theme, app language, and reset bundled rules from settings.

## On-device Design Flow

The app is now the primary workflow; the Python tools are optional batch helpers.

1. Save or import a layout in **Layout Library**.
2. Open **Design**, choose the working layout, and tap **Organize and Save**.
3. The app classifies launcher items with local rules, keeps existing widgets/cards as occupied desktop space, creates large or small folders according to category metadata, places manually pinned groups first, then higher-priority groups, and writes a new layout JSON into the app layout store.
4. Review the generated report, then restore the new layout from the library.

Default rules are bundled in the APK:

```text
app/src/main/assets/rules/launcher_app_categories.json
```

User edits are saved in the app private files directory and can be reset from
settings.

Detailed app-side planning is documented in:

```text
docs/app-design-plan.md
```

## Optional CLI Layout Editing Tools

This repository still includes helper scripts for inspecting and reorganizing
exported launcher layout JSON files on a computer:

```bash
python3 tools/launcher_layout_editor.py inspect layout.json

python3 tools/launcher_layout_editor.py organize layout.json \
  -o organized-layout.json \
  -r organized-layout-report.md
```

The app classification and desktop layout policy live in:

```text
tools/rules/launcher_app_categories.json
```

## Internal Launcher APIs Used By Reflection

- `com.android.launcher.backup.backrestore.backup.LauncherDBParser`
- `com.android.launcher.backup.cloudsync.CloudDataGenerator`
- `com.android.launcher.backup.cloudsync.CloudDataParser`
- `com.android.launcher.backup.backrestore.restore.LayoutRestoreHelper`

The module does not write launcher database rows directly. Import applies data through
the launcher's backup and restore implementation.
