package dev.canxin.launcherenhance.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.canxin.launcherenhance.LauncherEnhanceContract;
import dev.canxin.launcherenhance.R;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 10;
    private static final int REQ_SAVE_COPY = 11;
    private static final String FORBIDDEN_TITLE_CHARS = "\\/:*?<>\"|";

    private LayoutAdapter layoutAdapter;
    private RecyclerView recyclerView;
    private View emptyState;
    private View errorPanel;
    private TextView errorMeta;
    private TextView errorMessage;
    private TextView listTitleLabel;
    private TextView countLabel;
    private ImageButton startSelectionButton;
    private ImageButton selectAllButton;
    private ImageButton deleteSelectedButton;
    private ImageButton cancelSelectionButton;

    private final ArrayList<LayoutEntry> layouts = new ArrayList<>();
    private final Set<String> selectedFiles = new HashSet<>();
    private Bundle currentStatus = Bundle.EMPTY;
    private String activeFileName;
    private boolean selectionMode;
    private LayoutEntry pendingSaveCopy;
    private String lastErrorText = "";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
            refreshList();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        configureWindow();
        buildScreen();
        refreshStatus();
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(LauncherEnhanceContract.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        refreshStatus();
        refreshList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(color(R.color.surface));
        window.setNavigationBarColor(color(R.color.surface));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.surface));

        root.addView(buildHeader(), lp(match(), wrap()));
        root.addView(buildListHeader(), lp(match(), wrap()));
        root.addView(buildListArea(), new LinearLayout.LayoutParams(match(), 0, 1f));

        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(16), dp(18), dp(10));
        header.setBackgroundColor(color(R.color.surface));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("桌面布局", 28, R.color.on_surface, true);
        title.setIncludeFontPadding(false);
        titleBlock.addView(title, lp(match(), wrap()));

        TextView subtitle = text("保存当前桌面，或恢复已保存的图标排列", 13, R.color.on_surface_variant, false);
        subtitle.setPadding(0, dp(5), 0, 0);
        titleBlock.addView(subtitle, lp(match(), wrap()));
        titleRow.addView(titleBlock, new LinearLayout.LayoutParams(0, wrap(), 1f));

        ImageButton refresh = iconButton(R.drawable.ic_refresh, "刷新", v -> {
            refreshStatus();
            refreshList();
        });
        titleRow.addView(refresh, squareLp(42));

        ImageButton more = iconButton(R.drawable.ic_more, "更多", v -> showToolsSheet());
        LinearLayout.LayoutParams moreLp = squareLp(42);
        moreLp.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(more, moreLp);
        header.addView(titleRow, lp(match(), wrap()));

        errorPanel = buildErrorPanel();
        header.addView(errorPanel, cardLp());

        header.addView(buttonRow(
                primaryButton("保存当前桌面", R.drawable.ic_download, v -> exportCurrent()),
                secondaryButton("导入布局文件", R.drawable.ic_upload, v -> openImportPicker())));
        return header;
    }

    private View buildErrorPanel() {
        MaterialCardView card = card();
        card.setCardBackgroundColor(color(R.color.danger_container));
        card.setVisibility(View.GONE);

        LinearLayout body = cardBody();
        body.setPadding(dp(15), dp(14), dp(15), dp(14));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("操作没有完成", 18, R.color.danger, true);
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(0, wrap(), 1f));

        errorMeta = text("", 12, R.color.danger, true);
        errorMeta.setGravity(Gravity.END);
        row.addView(errorMeta, lp(wrap(), wrap()));

        ImageButton copy = iconButton(R.drawable.ic_copy, "复制详情", v -> copyText(lastErrorText));
        copy.setImageTintList(ColorStateList.valueOf(color(R.color.danger)));
        copy.setBackground(makeRoundBg(color(R.color.surface_container), dp(8)));
        LinearLayout.LayoutParams copyLp = squareLp(38);
        copyLp.setMargins(dp(8), 0, 0, 0);
        row.addView(copy, copyLp);
        body.addView(row, lp(match(), wrap()));

        errorMessage = text("", 15, R.color.on_surface, false);
        errorMessage.setPadding(0, dp(10), 0, 0);
        errorMessage.setTextIsSelectable(true);
        errorMessage.setSingleLine(false);
        errorMessage.setLineSpacing(dp(2), 1f);
        body.addView(errorMessage, lp(match(), wrap()));

        card.addView(body);
        return card;
    }

    private View buildListHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(4), dp(18), dp(8));
        row.setBackgroundColor(color(R.color.surface));

        listTitleLabel = text("已保存布局", 18, R.color.on_surface, true);
        listTitleLabel.setIncludeFontPadding(false);
        row.addView(listTitleLabel, new LinearLayout.LayoutParams(0, wrap(), 1f));

        countLabel = text("0 个", 13, R.color.on_surface_variant, false);
        countLabel.setGravity(Gravity.END);
        row.addView(countLabel, lp(wrap(), wrap()));

        startSelectionButton = iconButton(R.drawable.ic_select_all, "多选", v -> enterSelectionMode(null));
        addToolbarButton(row, startSelectionButton, false);

        selectAllButton = iconButton(R.drawable.ic_select_all, "全选", v -> selectAllLayouts());
        addToolbarButton(row, selectAllButton, false);

        deleteSelectedButton = iconButton(R.drawable.ic_delete, "删除选中", v -> confirmDeleteSelected());
        deleteSelectedButton.setImageTintList(ColorStateList.valueOf(color(R.color.danger)));
        addToolbarButton(row, deleteSelectedButton, true);

        cancelSelectionButton = iconButton(R.drawable.ic_close, "退出多选", v -> clearSelection());
        addToolbarButton(row, cancelSelectionButton, true);
        return row;
    }

    private void addToolbarButton(LinearLayout row, ImageButton button, boolean hidden) {
        LinearLayout.LayoutParams params = squareLp(38);
        params.setMargins(dp(8), 0, 0, 0);
        button.setVisibility(hidden ? View.GONE : View.VISIBLE);
        row.addView(button, params);
    }

    private View buildListArea() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(color(R.color.surface));

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(dp(18), dp(4), dp(18), dp(22));
        layoutAdapter = new LayoutAdapter(this, new LayoutActions() {
            @Override
            public void open(LayoutEntry entry) {
                if (selectionMode) {
                    toggleSelection(entry);
                    return;
                }
                activeFileName = entry.fileName;
                renderListState();
                showLayoutSheet(entry);
            }

            @Override
            public void apply(LayoutEntry entry) {
                if (selectionMode) {
                    toggleSelection(entry);
                    return;
                }
                confirmApply(entry);
            }

            @Override
            public void select(LayoutEntry entry) {
                enterSelectionMode(entry);
            }
        });
        recyclerView.setAdapter(layoutAdapter);
        new ItemTouchHelper(new LayoutSwipeCallback()).attachToRecyclerView(recyclerView);
        frame.addView(recyclerView, frameLp());

        emptyState = buildEmptyState();
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(match(), wrap(), Gravity.CENTER);
        emptyLp.setMargins(dp(18), 0, dp(18), 0);
        frame.addView(emptyState, emptyLp);
        return frame;
    }

    private View buildEmptyState() {
        MaterialCardView card = card();
        LinearLayout body = cardBody();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(18), dp(24), dp(18), dp(22));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_library);
        icon.setImageTintList(ColorStateList.valueOf(color(R.color.primary)));
        icon.setPadding(dp(13), dp(13), dp(13), dp(13));
        icon.setBackground(makeRoundBg(color(R.color.primary_container), dp(10)));
        body.addView(icon, squareLp(58));

        TextView title = text("还没有保存的布局", 18, R.color.on_surface, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(6));
        body.addView(title, lp(match(), wrap()));

        TextView message = text("保存当前桌面或导入布局文件后，会显示在这里。", 14, R.color.on_surface_variant, false);
        message.setGravity(Gravity.CENTER);
        message.setSingleLine(false);
        body.addView(message, lp(match(), wrap()));

        body.addView(buttonRow(
                primaryButton("保存当前桌面", R.drawable.ic_download, v -> exportCurrent()),
                secondaryButton("导入布局文件", R.drawable.ic_upload, v -> openImportPicker())));
        card.addView(body);
        return card;
    }

    private void showLayoutSheet(LayoutEntry entry) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));
        scroll.addView(sheet, new ScrollView.LayoutParams(match(), wrap()));

        TextView title = text(entry.title, 20, R.color.on_surface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sheet.addView(title, lp(match(), wrap()));

        TextView meta = text(sourceLabel(entry.fileName) + " · " + formatSize(entry.size) + " · " + formatDate(entry.modified),
                13, R.color.on_surface_variant, false);
        meta.setPadding(0, dp(6), 0, dp(14));
        sheet.addView(meta, lp(match(), wrap()));

        sheet.addView(primaryButton("恢复到桌面", R.drawable.ic_play, v -> {
            dialog.dismiss();
            confirmApply(entry);
        }), fullButtonLp());

        sheet.addView(buttonRow(
                secondaryButton("改名", R.drawable.ic_edit, v -> {
                    dialog.dismiss();
                    renameLayout(entry);
                }),
                secondaryButton("保存到文件", R.drawable.ic_save, v -> {
                    dialog.dismiss();
                    saveCopy(entry);
                })));

        sheet.addView(dangerButton("删除", R.drawable.ic_delete, v -> {
            dialog.dismiss();
            confirmDelete(entry);
        }), fullButtonLp());

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showToolsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));

        TextView title = text("其他操作", 20, R.color.on_surface, true);
        title.setIncludeFontPadding(false);
        sheet.addView(title, lp(match(), wrap()));

        TextView detail = text("管理多个布局，或在列表没有更新时手动刷新。", 14, R.color.on_surface_variant, false);
        detail.setPadding(0, dp(8), 0, dp(14));
        sheet.addView(detail, lp(match(), wrap()));

        sheet.addView(buttonRow(
                secondaryButton("刷新列表", R.drawable.ic_refresh, v -> {
                    refreshStatus();
                    refreshList();
                    dialog.dismiss();
                }),
                secondaryButton("多选管理", R.drawable.ic_select_all, v -> {
                    enterSelectionMode(null);
                    dialog.dismiss();
                })));

        sheet.addView(buttonRow(
                secondaryButton("保存当前桌面", R.drawable.ic_download, v -> {
                    dialog.dismiss();
                    exportCurrent();
                }),
                secondaryButton("导入布局文件", R.drawable.ic_upload, v -> {
                    dialog.dismiss();
                    openImportPicker();
                })));

        if (!TextUtils.isEmpty(lastErrorText)) {
            sheet.addView(secondaryButton("复制错误详情", R.drawable.ic_copy, v -> {
                copyText(lastErrorText);
                dialog.dismiss();
            }), fullButtonLp());
        }

        dialog.setContentView(sheet);
        dialog.show();
    }

    private void exportCurrent() {
        showTitleDialog("保存当前桌面", "保存", defaultExportTitle(), this::exportCurrentAs);
    }

    private void exportCurrentAs(String title) {
        String fileName = makeStorageName("layout", title);
        Uri uri = LauncherEnhanceContract.layoutUri(fileName);
        setLayoutTitle(uri, title);
        activeFileName = fileName;
        renderListState();
        sendCommand(LauncherEnhanceContract.ACTION_EXPORT, uri, title);
        toast("已开始保存当前桌面");
    }

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/plain", "*/*"});
        startActivityForResult(intent, REQ_IMPORT);
    }

    private void autoCheck(LayoutEntry entry) {
        sendCommand(LauncherEnhanceContract.ACTION_DRY_RUN, entry.uri, entry.title);
    }

    private void confirmApply(LayoutEntry entry) {
        activeFileName = entry.fileName;
        renderListState();
        new MaterialAlertDialogBuilder(this)
                .setTitle("恢复到桌面")
                .setMessage("会把「" + entry.title + "」恢复到系统桌面。恢复前会自动备份当前桌面。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> {
                    sendCommand(LauncherEnhanceContract.ACTION_APPLY, entry.uri, entry.title);
                    toast("已开始恢复到桌面");
                })
                .show();
    }

    private void renameLayout(LayoutEntry entry) {
        showTitleDialog("修改名称", "保存", entry.title, title -> {
            setLayoutTitle(entry.uri, title);
            activeFileName = entry.fileName;
            refreshList();
        });
    }

    private void saveCopy(LayoutEntry entry) {
        pendingSaveCopy = entry;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, documentNameFor(entry.title));
        startActivityForResult(intent, REQ_SAVE_COPY);
    }

    private void confirmDelete(LayoutEntry entry) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除布局")
                .setMessage("确定删除「" + entry.title + "」？这个文件会从列表中移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    deleteLayout(entry);
                    refreshList();
                })
                .show();
    }

    private void enterSelectionMode(LayoutEntry first) {
        selectionMode = true;
        if (first != null) {
            selectedFiles.add(first.fileName);
        }
        renderListState();
    }

    private void toggleSelection(LayoutEntry entry) {
        selectionMode = true;
        if (selectedFiles.contains(entry.fileName)) {
            selectedFiles.remove(entry.fileName);
        } else {
            selectedFiles.add(entry.fileName);
        }
        renderListState();
    }

    private void selectAllLayouts() {
        selectionMode = true;
        selectedFiles.clear();
        for (LayoutEntry entry : layouts) {
            selectedFiles.add(entry.fileName);
        }
        renderListState();
    }

    private void clearSelection() {
        selectionMode = false;
        selectedFiles.clear();
        renderListState();
    }

    private void confirmDeleteSelected() {
        if (selectedFiles.isEmpty()) {
            toast("先选择要删除的布局");
            return;
        }
        int count = selectedFiles.size();
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除选中的布局")
                .setMessage("确定删除选中的 " + count + " 个布局？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    for (String fileName : new ArrayList<>(selectedFiles)) {
                        LayoutEntry entry = findLayout(fileName);
                        if (entry != null) {
                            deleteLayout(entry);
                        } else {
                            getContentResolver().delete(LauncherEnhanceContract.layoutUri(fileName), null, null);
                        }
                    }
                    clearSelection();
                    refreshList();
                })
                .show();
    }

    private void deleteLayout(LayoutEntry entry) {
        getContentResolver().delete(entry.uri, null, null);
        selectedFiles.remove(entry.fileName);
        if (entry.fileName.equals(activeFileName)) {
            activeFileName = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingSaveCopy = null;
            return;
        }
        try {
            if (requestCode == REQ_IMPORT) {
                importJson(data.getData());
            } else if (requestCode == REQ_SAVE_COPY) {
                LayoutEntry entry = pendingSaveCopy;
                pendingSaveCopy = null;
                if (entry == null) {
                    throw new IllegalStateException("No layout selected for copy");
                }
                copyUri(entry.uri, data.getData());
                toast("文件已保存");
            }
        } catch (Throwable t) {
            pendingSaveCopy = null;
            showError(t);
        }
    }

    private void importJson(Uri sourceUri) throws Exception {
        byte[] bytes = readAll(sourceUri);
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        new JSONObject(json);
        String initialTitle = titleFromSourceName(displayName(sourceUri));
        showTitleDialog("命名导入的布局", "保存", initialTitle, title -> {
            try {
                saveImportedJson(bytes, title);
            } catch (Throwable t) {
                showError(t);
            }
        });
    }

    private void saveImportedJson(byte[] bytes, String title) throws Exception {
        String fileName = makeStorageName("import", title);
        Uri target = LauncherEnhanceContract.layoutUri(fileName);
        try (OutputStream outputStream = getContentResolver().openOutputStream(target, "wt")) {
            if (outputStream == null) {
                throw new IllegalStateException("Cannot open target layout");
            }
            outputStream.write(bytes);
        }
        setLayoutTitle(target, title);
        LayoutEntry entry = new LayoutEntry(title, fileName, target, bytes.length, System.currentTimeMillis());
        activeFileName = fileName;
        refreshList();
        toast("已导入布局");
        autoCheck(entry);
        showLayoutSheet(entry);
    }

    private void copyUri(Uri from, Uri to) throws Exception {
        if (from == null || to == null) {
            throw new IllegalStateException("No layout uri");
        }
        try (InputStream inputStream = getContentResolver().openInputStream(from);
             OutputStream outputStream = getContentResolver().openOutputStream(to, "wt")) {
            if (inputStream == null || outputStream == null) {
                throw new IllegalStateException("Cannot open document");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private void sendCommand(String action, Uri uri, String title) {
        try {
            String token = LauncherEnhanceContract.getToken(this);
            if (TextUtils.isEmpty(token)) {
                throw new IllegalStateException("No command token");
            }
            Intent intent = new Intent(action);
            intent.setPackage(LauncherEnhanceContract.LAUNCHER_PACKAGE);
            intent.putExtra(LauncherEnhanceContract.EXTRA_TOKEN, token);
            if (uri != null) {
                intent.putExtra(LauncherEnhanceContract.EXTRA_URI, uri.toString());
            }
            if (title != null) {
                intent.putExtra(LauncherEnhanceContract.EXTRA_NAME, title);
            }
            intent.putExtra(LauncherEnhanceContract.EXTRA_COMMAND_ID, timestamp());
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            sendBroadcast(intent);
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void refreshStatus() {
        Bundle status = LauncherEnhanceContract.getStatus(this);
        currentStatus = status == null ? Bundle.EMPTY : status;
        renderStatus();
    }

    private void refreshList() {
        layouts.clear();
        try (Cursor cursor = getContentResolver().query(
                LauncherEnhanceContract.layoutsUri(),
                null,
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String title = cursor.getString(cursor.getColumnIndexOrThrow(LauncherEnhanceContract.COLUMN_NAME));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(LauncherEnhanceContract.COLUMN_SIZE));
                    long modified = cursor.getLong(cursor.getColumnIndexOrThrow(LauncherEnhanceContract.COLUMN_MODIFIED));
                    String uri = cursor.getString(cursor.getColumnIndexOrThrow(LauncherEnhanceContract.COLUMN_URI));
                    String fileName = cursor.getString(cursor.getColumnIndexOrThrow(LauncherEnhanceContract.COLUMN_FILE_NAME));
                    layouts.add(new LayoutEntry(title, fileName, Uri.parse(uri), size, modified));
                } while (cursor.moveToNext());
            }
        } catch (Throwable t) {
            showError(t);
        }
        pruneSelection();
        if (!TextUtils.isEmpty(activeFileName) && findLayout(activeFileName) == null) {
            activeFileName = null;
        }
        renderListState();
    }

    private void renderStatus() {
        if (errorPanel == null) {
            return;
        }
        String action = currentStatus.getString(LauncherEnhanceContract.KEY_ACTION, "");
        boolean success = currentStatus.getBoolean(LauncherEnhanceContract.KEY_SUCCESS, false);
        if (TextUtils.isEmpty(action) || success) {
            lastErrorText = "";
            errorPanel.setVisibility(View.GONE);
            return;
        }

        String message = currentStatus.getString(LauncherEnhanceContract.KEY_MESSAGE, "");
        String title = currentStatus.getString(LauncherEnhanceContract.KEY_NAME, "");
        long time = currentStatus.getLong(LauncherEnhanceContract.KEY_TIME, 0L);
        lastErrorText = errorDetail(action, title, message, time);
        errorMeta.setText(actionLabel(action));
        errorMessage.setText(lastErrorText);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private String errorDetail(String action, String title, String message, long time) {
        StringBuilder builder = new StringBuilder();
        builder.append("正在做：").append(actionLabel(action));
        if (!TextUtils.isEmpty(title)) {
            builder.append("\n布局：").append(title);
        }
        if (!TextUtils.isEmpty(message)) {
            builder.append("\n原因：").append(message);
        }
        if (time > 0L) {
            builder.append("\n时间：").append(formatDate(time));
        }
        return builder.toString();
    }

    private void renderListState() {
        if (layoutAdapter != null) {
            layoutAdapter.submit(layouts);
        }
        if (emptyState != null) {
            emptyState.setVisibility(layouts.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (recyclerView != null) {
            recyclerView.setVisibility(layouts.isEmpty() ? View.GONE : View.VISIBLE);
        }

        boolean hasLayouts = !layouts.isEmpty();
        if (!hasLayouts) {
            selectionMode = false;
            selectedFiles.clear();
        }
        int selectedCount = selectedFiles.size();
        if (listTitleLabel != null) {
            listTitleLabel.setText(selectionMode ? "已选择 " + selectedCount + " 个" : "已保存布局");
        }
        if (countLabel != null) {
            countLabel.setText(selectionMode ? "共 " + layouts.size() + " 个" : layouts.size() + " 个");
        }
        if (startSelectionButton != null) {
            startSelectionButton.setVisibility(!selectionMode && hasLayouts ? View.VISIBLE : View.GONE);
        }
        if (selectAllButton != null) {
            selectAllButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        }
        if (deleteSelectedButton != null) {
            deleteSelectedButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            deleteSelectedButton.setEnabled(selectedCount > 0);
            deleteSelectedButton.setAlpha(selectedCount > 0 ? 1f : 0.45f);
        }
        if (cancelSelectionButton != null) {
            cancelSelectionButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        }
    }

    private void pruneSelection() {
        Set<String> liveFiles = new HashSet<>();
        for (LayoutEntry entry : layouts) {
            liveFiles.add(entry.fileName);
        }
        selectedFiles.retainAll(liveFiles);
    }

    private LayoutEntry findLayout(String fileName) {
        for (LayoutEntry entry : layouts) {
            if (entry.fileName.equals(fileName)) {
                return entry;
            }
        }
        return null;
    }

    private boolean isSelected(LayoutEntry entry) {
        return selectedFiles.contains(entry.fileName);
    }

    private String actionLabel(String action) {
        if ("ready".equals(action)) {
            return "模块准备";
        }
        if ("export".equals(action)) {
            return "保存当前桌面";
        }
        if ("dry_run".equals(action)) {
            return "检查布局文件";
        }
        if ("apply".equals(action)) {
            return "恢复到桌面";
        }
        if ("ping".equals(action)) {
            return "连接桌面";
        }
        return action.toUpperCase(Locale.US);
    }

    private String sourceLabel(String fileName) {
        if (fileName.startsWith("before-apply-")) {
            return "恢复前备份";
        }
        if (fileName.startsWith("import-")) {
            return "导入文件";
        }
        if (fileName.startsWith("layout-")) {
            return "已保存";
        }
        return "布局文件";
    }

    private void showTitleDialog(String title, String positive, String initial, TitleCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{this::filterTitleInput});
        input.setText(normalizeTitle(initial));
        input.setSelectAllOnFocus(true);
        input.setHint("例如：工作桌面");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(2);
        container.setPadding(horizontal, dp(8), horizontal, 0);
        container.addView(input, lp(match(), wrap()));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton(positive, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = normalizeTitle(input.getText() == null ? "" : input.getText().toString());
                if (TextUtils.isEmpty(value)) {
                    toast("请输入布局名称");
                    return;
                }
                callback.onTitle(value);
                dialog.dismiss();
            });
            input.requestFocus();
        });
        dialog.show();
    }

    private void setLayoutTitle(Uri uri, String title) {
        ContentValues values = new ContentValues();
        values.put(LauncherEnhanceContract.COLUMN_NAME, normalizeTitle(title));
        getContentResolver().update(uri, values, null, null);
    }

    private String normalizeTitle(String title) {
        String raw = title == null ? "" : title.trim();
        StringBuilder builder = new StringBuilder(raw.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isForbiddenTitleChar(c) || Character.isISOControl(c)) {
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
        if (value.length() > 80) {
            value = value.substring(0, 80);
        }
        return value;
    }

    private String defaultExportTitle() {
        return "桌面备份 " + DateFormat.format("MM-dd HH-mm", System.currentTimeMillis());
    }

    private String titleFromSourceName(String sourceName) {
        String value = TextUtils.isEmpty(sourceName) ? "导入的布局" : sourceName.trim();
        if (value.endsWith(".json")) {
            value = value.substring(0, value.length() - 5);
        }
        return normalizeTitle(value);
    }

    private String documentNameFor(String title) {
        String value = normalizeTitle(title);
        if (TextUtils.isEmpty(value)) {
            value = "桌面布局";
        }
        return value.endsWith(".json") ? value : value + ".json";
    }

    private CharSequence filterTitleInput(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        StringBuilder builder = null;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (isForbiddenTitleChar(c) || Character.isISOControl(c)) {
                if (builder == null) {
                    builder = new StringBuilder(end - start);
                    builder.append(source, start, i);
                }
            } else if (builder != null) {
                builder.append(c);
            }
        }
        return builder == null ? null : builder.toString();
    }

    private boolean isForbiddenTitleChar(char c) {
        return FORBIDDEN_TITLE_CHARS.indexOf(c) >= 0;
    }

    private String makeStorageName(String prefix, String title) {
        String safe = sanitizeBaseName(title);
        String name = prefix + "-" + timestamp() + "-" + safe + ".json";
        if (name.length() > 110) {
            name = prefix + "-" + timestamp() + ".json";
        }
        return name;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalStateException("Cannot open " + uri);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Throwable ignored) {
        }
        return "导入的布局";
    }

    private String sanitizeBaseName(String name) {
        String base = name == null ? "" : name;
        if (base.endsWith(".json")) {
            base = base.substring(0, base.length() - 5);
        }
        StringBuilder builder = new StringBuilder();
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < base.length() && builder.length() < 40; i++) {
            char c = base.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                hasLetterOrDigit = true;
            }
            builder.append(ok ? c : '-');
        }
        String result = builder.toString();
        if (result.isEmpty() || !hasLetterOrDigit) {
            return "layout";
        }
        return result;
    }

    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", size / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", size / 1024f / 1024f);
    }

    private String formatDate(long millis) {
        if (millis <= 0) {
            return "时间未知";
        }
        return DateFormat.format("yyyy-MM-dd HH:mm:ss", millis).toString();
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color(color));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private ImageButton iconButton(int icon, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(color(R.color.primary)));
        button.setBackground(makeRoundBg(color(R.color.primary_container), dp(8)));
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(description);
        }
        button.setOnClickListener(listener);
        return button;
    }

    private ImageButton actionIconButton(int icon, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(color(R.color.on_primary)));
        button.setBackground(makeRoundBg(color(R.color.primary), dp(8)));
        button.setPadding(dp(7), dp(7), dp(7), dp(7));
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(description);
        }
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout cardBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(12));
        return body;
    }

    private LinearLayout buttonRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(left, weightedButtonLp(true));
        row.addView(right, weightedButtonLp(false));
        return row;
    }

    private MaterialButton primaryButton(String text, int icon, View.OnClickListener listener) {
        MaterialButton button = baseButton(text, icon, listener);
        button.setBackgroundTintList(ColorStateList.valueOf(color(R.color.primary)));
        button.setTextColor(color(R.color.on_primary));
        button.setIconTint(ColorStateList.valueOf(color(R.color.on_primary)));
        return button;
    }

    private MaterialButton secondaryButton(String text, int icon, View.OnClickListener listener) {
        MaterialButton button = baseButton(text, icon, listener);
        button.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_container_low)));
        button.setTextColor(color(R.color.on_surface));
        button.setIconTint(ColorStateList.valueOf(color(R.color.primary)));
        button.setStrokeColor(ColorStateList.valueOf(color(R.color.outline)));
        button.setStrokeWidth(dp(1));
        return button;
    }

    private MaterialButton dangerButton(String text, int icon, View.OnClickListener listener) {
        MaterialButton button = baseButton(text, icon, listener);
        button.setBackgroundTintList(ColorStateList.valueOf(color(R.color.danger_container)));
        button.setTextColor(color(R.color.danger));
        button.setIconTint(ColorStateList.valueOf(color(R.color.danger)));
        return button;
    }

    private MaterialButton baseButton(String text, int icon, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(8));
        button.setIconResource(icon);
        button.setIconSize(dp(18));
        button.setIconPadding(dp(6));
        button.setOnClickListener(listener);
        return button;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(8));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(color(R.color.outline));
        card.setCardBackgroundColor(color(R.color.surface_container));
        return card;
    }

    private android.graphics.drawable.GradientDrawable makeRoundBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private FrameLayout.LayoutParams frameLp() {
        return new FrameLayout.LayoutParams(match(), match());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams params = lp(match(), wrap());
        params.setMargins(0, dp(12), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams fullButtonLp() {
        LinearLayout.LayoutParams params = lp(match(), dp(48));
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonLp(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(left ? 0 : dp(5), dp(5), left ? dp(5) : 0, dp(5));
        return params;
    }

    private LinearLayout.LayoutParams squareLp(int dpSize) {
        return lp(dp(dpSize), dp(dpSize));
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private int color(int resId) {
        return getColor(resId);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void copyText(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Launcher Enhance", text));
            toast("已复制详情");
        }
    }

    private void showError(Throwable throwable) {
        throwable.printStackTrace();
        toast(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final class LayoutSwipeCallback extends ItemTouchHelper.SimpleCallback {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LayoutSwipeCallback() {
            super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
            paint.setTextSize(dp(14));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        public int getSwipeDirs(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            return selectionMode ? 0 : super.getSwipeDirs(recyclerView, viewHolder);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getAdapterPosition();
            if (position == RecyclerView.NO_POSITION || layoutAdapter == null) {
                return;
            }
            LayoutEntry entry = layoutAdapter.itemAt(position);
            if (entry == null) {
                return;
            }
            activeFileName = entry.fileName;
            resetSwipedItem(position);
            if (recyclerView != null) {
                recyclerView.post(() -> {
                    renderListState();
                    showLayoutSheet(entry);
                });
            }
        }

        private void resetSwipedItem(int position) {
            if (recyclerView != null) {
                recyclerView.post(() -> {
                    if (layoutAdapter != null) {
                        layoutAdapter.notifyItemChanged(position);
                    }
                });
            }
        }

        @Override
        public void onChildDraw(Canvas canvas, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                float dX, float dY, int actionState, boolean isCurrentlyActive) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                View itemView = viewHolder.itemView;
                boolean right = dX > 0;
                paint.setColor(color(right ? R.color.primary_container : R.color.danger_container));
                if (right) {
                    canvas.drawRect(itemView.getLeft(), itemView.getTop(), itemView.getLeft() + dX, itemView.getBottom(), paint);
                    drawSwipeText(canvas, itemView, "更多", itemView.getLeft() + dp(24), color(R.color.primary));
                } else {
                    canvas.drawRect(itemView.getRight() + dX, itemView.getTop(), itemView.getRight(), itemView.getBottom(), paint);
                    drawSwipeText(canvas, itemView, "更多", itemView.getRight() - dp(54), color(R.color.danger));
                }
            }
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }

        private void drawSwipeText(Canvas canvas, View itemView, String value, float x, int textColor) {
            paint.setColor(textColor);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float y = itemView.getTop() + (itemView.getHeight() - metrics.bottom + metrics.top) / 2f - metrics.top;
            canvas.drawText(value, x, y, paint);
        }
    }

    private final class LayoutAdapter extends RecyclerView.Adapter<LayoutViewHolder> {
        private final Context context;
        private final LayoutActions actions;
        private final ArrayList<LayoutEntry> data = new ArrayList<>();

        LayoutAdapter(Context context, LayoutActions actions) {
            this.context = context;
            this.actions = actions;
            setHasStableIds(true);
        }

        void submit(List<LayoutEntry> entries) {
            data.clear();
            data.addAll(entries);
            notifyDataSetChanged();
        }

        LayoutEntry itemAt(int position) {
            if (position < 0 || position >= data.size()) {
                return null;
            }
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return data.get(position).fileName.hashCode();
        }

        @Override
        public LayoutViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = card();
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(match(), wrap());
            params.setMargins(0, 0, 0, dp(10));
            card.setLayoutParams(params);
            card.setClickable(true);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));

            LinearLayout textBlock = new LinearLayout(context);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            textBlock.setPadding(0, 0, dp(6), 0);
            TextView title = text("", 14, R.color.on_surface, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            textBlock.addView(title, lp(match(), wrap()));
            TextView meta = text("", 11, R.color.on_surface_variant, false);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setPadding(0, dp(3), 0, 0);
            textBlock.addView(meta, lp(match(), wrap()));
            row.addView(textBlock, new LinearLayout.LayoutParams(0, wrap(), 1f));

            ImageButton apply = actionIconButton(R.drawable.ic_play, "恢复到桌面", null);
            row.addView(apply, squareLp(34));

            ImageButton more = new ImageButton(context);
            more.setImageResource(R.drawable.ic_more);
            more.setImageTintList(ColorStateList.valueOf(color(R.color.on_surface_variant)));
            more.setBackgroundColor(0x00000000);
            more.setPadding(dp(7), dp(7), dp(7), dp(7));
            more.setContentDescription("更多操作");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                more.setTooltipText("更多操作");
            }
            LinearLayout.LayoutParams moreLp = squareLp(34);
            moreLp.setMargins(dp(2), 0, 0, 0);
            row.addView(more, moreLp);

            card.addView(row);
            return new LayoutViewHolder(card, title, meta, apply, more);
        }

        @Override
        public void onBindViewHolder(LayoutViewHolder holder, int position) {
            LayoutEntry entry = data.get(position);
            boolean selected = isSelected(entry);
            boolean active = !selectionMode && entry.fileName.equals(activeFileName);
            holder.card.setStrokeWidth(dp(selected || active ? 2 : 1));
            holder.card.setStrokeColor(color(selected || active ? R.color.primary : R.color.outline));
            holder.card.setCardBackgroundColor(color(selected ? R.color.primary_container : R.color.surface_container));
            holder.title.setText(entry.title);
            holder.meta.setText(sourceLabel(entry.fileName) + " · " + formatSize(entry.size) + " · " + formatDate(entry.modified));
            holder.apply.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            holder.more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(v -> actions.open(entry));
            holder.itemView.setOnLongClickListener(v -> {
                actions.select(entry);
                return true;
            });
            holder.more.setOnClickListener(v -> actions.open(entry));
            holder.apply.setOnClickListener(v -> actions.apply(entry));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private static final class LayoutViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView title;
        final TextView meta;
        final ImageButton apply;
        final ImageButton more;

        LayoutViewHolder(MaterialCardView card, TextView title, TextView meta, ImageButton apply, ImageButton more) {
            super(card);
            this.card = card;
            this.title = title;
            this.meta = meta;
            this.apply = apply;
            this.more = more;
        }
    }

    private interface LayoutActions {
        void open(LayoutEntry entry);

        void apply(LayoutEntry entry);

        void select(LayoutEntry entry);
    }

    private interface TitleCallback {
        void onTitle(String title);
    }

    private static final class LayoutEntry {
        final String title;
        final String fileName;
        final Uri uri;
        final long size;
        final long modified;

        LayoutEntry(String title, String fileName, Uri uri, long size, long modified) {
            this.title = title;
            this.fileName = fileName;
            this.uri = uri;
            this.size = size;
            this.modified = modified;
        }
    }
}
