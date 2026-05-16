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
- Manage saved layouts in the module app: select, delete, save copy.

## Layout Editing Tools

This repository also includes helper scripts for inspecting and reorganizing
exported launcher layout JSON files:

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
