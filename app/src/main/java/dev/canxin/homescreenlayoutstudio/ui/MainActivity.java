package dev.canxin.homescreenlayoutstudio.ui;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.canxin.homescreenlayoutstudio.HomeScreenLayoutStudioContract;
import dev.canxin.homescreenlayoutstudio.R;
import dev.canxin.homescreenlayoutstudio.apps.DeviceApp;
import dev.canxin.homescreenlayoutstudio.apps.DeviceAppScanner;
import dev.canxin.homescreenlayoutstudio.layout.LauncherLayoutOrganizer;
import dev.canxin.homescreenlayoutstudio.rules.RuleItem;
import dev.canxin.homescreenlayoutstudio.rules.RuleSet;
import dev.canxin.homescreenlayoutstudio.rules.RuleStore;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_IMPORT = 10;
    private static final int REQ_SAVE_COPY = 11;
    private static final int PAGE_LAYOUTS = 1;
    private static final int PAGE_DESIGN = 2;
    private static final int PAGE_RULES = 3;
    private static final int PAGE_SETTINGS = 4;
    private static final int PAGE_APPS = 5;
    private static final int PAGE_UNGROUPED = 6;
    private static final int PAGE_RULE_APPS = 7;
    private static final String FORBIDDEN_TITLE_CHARS = "\\/:*?<>\"|";

    private FrameLayout contentFrame;
    private BottomNavigationView navigationView;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private View errorPanel;
    private TextView errorMeta;
    private TextView errorMessage;

    private LayoutAdapter layoutAdapter;
    private RecyclerView recyclerView;
    private View emptyState;
    private TextView listTitleLabel;
    private TextView countLabel;
    private ImageButton startSelectionButton;
    private ImageButton selectAllButton;
    private ImageButton deleteSelectedButton;
    private ImageButton cancelSelectionButton;

    private TextView designSelectedTitle;
    private TextView designSelectedMeta;
    private TextView designSummaryLabel;
    private MaterialButton designOrganizeButton;
    private MaterialButton designReportButton;
    private DesignGridView designGridView;

    private RuleAdapter ruleAdapter;
    private TextView rulesCountLabel;

    private final ArrayList<LayoutEntry> layouts = new ArrayList<>();
    private final Set<String> selectedFiles = new HashSet<>();
    private final Map<String, LauncherLayoutOrganizer.DesignOverrides> designOverridesByFile = new LinkedHashMap<>();
    private RuleSet ruleSet;
    private Bundle currentStatus = Bundle.EMPTY;
    private String activeFileName;
    private LayoutEntry designEntry;
    private boolean selectionMode;
    private int currentPage = PAGE_LAYOUTS;
    private String routeRuleName;
    private String addAppTargetRuleName;
    private LayoutEntry pendingSaveCopy;
    private String lastErrorText = "";
    private String lastReportText = "";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
            refreshList();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppSettings.apply(this);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        configureWindow();
        loadRules();
        buildShell();
        refreshStatus();
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(HomeScreenLayoutStudioContract.ACTION_STATUS_CHANGED);
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
            int flags = isDarkMode() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.surface));

        root.addView(buildHeader(), lp(match(), wrap()));

        contentFrame = new FrameLayout(this);
        contentFrame.setBackgroundColor(color(R.color.surface));
        root.addView(contentFrame, new LinearLayout.LayoutParams(match(), 0, 1f));

        navigationView = new BottomNavigationView(this);
        navigationView.setBackgroundColor(color(R.color.surface_container));
        navigationView.setItemIconTintList(null);
        navigationView.getMenu().add(0, PAGE_LAYOUTS, 0, getString(R.string.nav_layouts)).setIcon(R.drawable.ic_library);
        navigationView.getMenu().add(0, PAGE_DESIGN, 1, getString(R.string.nav_design)).setIcon(R.drawable.ic_tools);
        navigationView.getMenu().add(0, PAGE_RULES, 2, getString(R.string.nav_rules)).setIcon(R.drawable.ic_edit);
        navigationView.getMenu().add(0, PAGE_SETTINGS, 3, getString(R.string.nav_settings)).setIcon(R.drawable.ic_more);
        navigationView.setSelectedItemId(PAGE_LAYOUTS);
        navigationView.setOnItemSelectedListener(item -> {
            switchPage(item.getItemId());
            return true;
        });
        root.addView(navigationView, lp(match(), wrap()));
        setContentView(root);
        switchPage(PAGE_LAYOUTS);
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
        headerTitle = text("", 28, R.color.on_surface, true);
        headerTitle.setIncludeFontPadding(false);
        titleBlock.addView(headerTitle, lp(match(), wrap()));

        headerSubtitle = text("", 13, R.color.on_surface_variant, false);
        headerSubtitle.setPadding(0, dp(5), 0, 0);
        titleBlock.addView(headerSubtitle, lp(match(), wrap()));
        titleRow.addView(titleBlock, new LinearLayout.LayoutParams(0, wrap(), 1f));

        ImageButton refresh = iconButton(R.drawable.ic_refresh, getString(R.string.action_refresh), v -> {
            refreshStatus();
            refreshList();
            loadRules();
        });
        titleRow.addView(refresh, squareLp(42));

        header.addView(titleRow, lp(match(), wrap()));

        errorPanel = buildErrorPanel();
        header.addView(errorPanel, cardLp());
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

        TextView title = text(getString(R.string.error_panel_title), 18, R.color.danger, true);
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(0, wrap(), 1f));

        errorMeta = text("", 12, R.color.danger, true);
        errorMeta.setGravity(Gravity.END);
        row.addView(errorMeta, lp(wrap(), wrap()));

        ImageButton copy = iconButton(R.drawable.ic_copy, getString(R.string.action_copy_details), v -> copyText(lastErrorText));
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

    private void switchPage(int page) {
        currentPage = page;
        renderHeaderText();
        contentFrame.removeAllViews();
        if (page == PAGE_LAYOUTS) {
            contentFrame.addView(buildLayoutsPage(), frameLp());
        } else if (page == PAGE_DESIGN) {
            contentFrame.addView(buildDesignPage(), frameLp());
        } else if (page == PAGE_RULES) {
            contentFrame.addView(buildRulesPage(), frameLp());
        } else if (page == PAGE_APPS) {
            contentFrame.addView(buildDeviceAppsPage(), frameLp());
        } else if (page == PAGE_UNGROUPED) {
            contentFrame.addView(buildUngroupedAppsPage(), frameLp());
        } else if (page == PAGE_RULE_APPS) {
            contentFrame.addView(buildRuleAppsPage(), frameLp());
        } else {
            contentFrame.addView(buildSettingsPage(), frameLp());
        }
        renderStatus();
        renderListState();
        renderDesignState();
        renderRulesState();
    }

    private void renderHeaderText() {
        if (headerTitle == null || headerSubtitle == null) {
            return;
        }
        if (currentPage == PAGE_LAYOUTS) {
            headerTitle.setText(R.string.page_layouts_title);
            headerSubtitle.setText(R.string.page_layouts_subtitle);
        } else if (currentPage == PAGE_DESIGN) {
            headerTitle.setText(R.string.page_design_title);
            headerSubtitle.setText(R.string.page_design_subtitle);
        } else if (currentPage == PAGE_RULES) {
            headerTitle.setText(R.string.page_rules_title);
            headerSubtitle.setText(R.string.page_rules_subtitle);
        } else if (currentPage == PAGE_APPS) {
            headerTitle.setText(R.string.device_apps_title);
            headerSubtitle.setText(addAppTargetRuleName == null ? R.string.device_apps_subtitle : R.string.device_apps_add_subtitle);
        } else if (currentPage == PAGE_UNGROUPED) {
            headerTitle.setText(R.string.ungrouped_apps_title);
            headerSubtitle.setText(R.string.ungrouped_apps_subtitle);
        } else if (currentPage == PAGE_RULE_APPS) {
            headerTitle.setText(routeRuleName == null ? getString(R.string.action_rule_apps) : routeRuleName);
            headerSubtitle.setText(R.string.rule_apps_subtitle);
        } else {
            headerTitle.setText(R.string.page_settings_title);
            headerSubtitle.setText(R.string.page_settings_subtitle);
        }
    }

    private View buildLayoutsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(color(R.color.surface));
        page.addView(buildQuickActions(), lp(match(), wrap()));
        page.addView(buildListHeader(), lp(match(), wrap()));
        page.addView(buildListArea(), new LinearLayout.LayoutParams(match(), 0, 1f));
        return page;
    }

    private View buildQuickActions() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(18), dp(4), dp(18), dp(8));
        row.addView(primaryButton(getString(R.string.action_export_current), R.drawable.ic_download, v -> exportCurrent()), weightedButtonLp(true));
        row.addView(secondaryButton(getString(R.string.action_import_file), R.drawable.ic_upload, v -> openImportPicker()), weightedButtonLp(false));
        return row;
    }

    private View buildListHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(4), dp(18), dp(8));
        row.setBackgroundColor(color(R.color.surface));

        listTitleLabel = text(getString(R.string.saved_layouts), 18, R.color.on_surface, true);
        listTitleLabel.setIncludeFontPadding(false);
        row.addView(listTitleLabel, new LinearLayout.LayoutParams(0, wrap(), 1f));

        countLabel = text(getString(R.string.count_items, 0), 13, R.color.on_surface_variant, false);
        countLabel.setGravity(Gravity.END);
        row.addView(countLabel, lp(wrap(), wrap()));

        startSelectionButton = iconButton(R.drawable.ic_select_all, getString(R.string.action_multi_select), v -> enterSelectionMode(null));
        addToolbarButton(row, startSelectionButton, false);

        selectAllButton = iconButton(R.drawable.ic_select_all, getString(R.string.action_select_all), v -> selectAllLayouts());
        addToolbarButton(row, selectAllButton, true);

        deleteSelectedButton = iconButton(R.drawable.ic_delete, getString(R.string.action_delete_selected), v -> confirmDeleteSelected());
        deleteSelectedButton.setImageTintList(ColorStateList.valueOf(color(R.color.danger)));
        addToolbarButton(row, deleteSelectedButton, true);

        cancelSelectionButton = iconButton(R.drawable.ic_close, getString(R.string.action_cancel_selection), v -> clearSelection());
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

        TextView title = text(getString(R.string.empty_layouts_title), 18, R.color.on_surface, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(6));
        body.addView(title, lp(match(), wrap()));

        TextView message = text(getString(R.string.empty_layouts_message), 14, R.color.on_surface_variant, false);
        message.setGravity(Gravity.CENTER);
        message.setSingleLine(false);
        body.addView(message, lp(match(), wrap()));

        body.addView(buttonRow(
                primaryButton(getString(R.string.action_export_current), R.drawable.ic_download, v -> exportCurrent()),
                secondaryButton(getString(R.string.action_import_file), R.drawable.ic_upload, v -> openImportPicker())));
        card.addView(body);
        return card;
    }

    private View buildDesignPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(4), dp(18), dp(22));
        page.setBackgroundColor(color(R.color.surface));
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));

        page.addView(buildDesignSelectionCard(), cardLp());
        page.addView(buildDesignCanvasCard(), cardLp());
        page.addView(buildDesignActionsCard(), cardLp());
        page.addView(buildDesignSummaryCard(), cardLp());
        return scroll;
    }

    private View buildDesignSelectionCard() {
        MaterialCardView card = card();
        LinearLayout body = cardBody();

        TextView label = text(getString(R.string.design_selected_layout), 13, R.color.on_surface_variant, true);
        body.addView(label, lp(match(), wrap()));

        designSelectedTitle = text("", 20, R.color.on_surface, true);
        designSelectedTitle.setPadding(0, dp(6), 0, dp(4));
        designSelectedTitle.setSingleLine(true);
        designSelectedTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        body.addView(designSelectedTitle, lp(match(), wrap()));

        designSelectedMeta = text("", 13, R.color.on_surface_variant, false);
        designSelectedMeta.setSingleLine(false);
        body.addView(designSelectedMeta, lp(match(), wrap()));

        body.addView(secondaryButton(getString(R.string.action_choose_layout), R.drawable.ic_library, v -> showChooseLayoutSheet()), fullButtonLp());
        card.addView(body);
        return card;
    }

    private View buildDesignCanvasCard() {
        MaterialCardView card = card();
        LinearLayout body = cardBody();
        TextView title = text(getString(R.string.design_canvas_title), 18, R.color.on_surface, true);
        body.addView(title, lp(match(), wrap()));

        designGridView = new DesignGridView(this);
        designGridView.setListener(new DesignGridListener() {
            @Override
            public void onMove(LauncherLayoutOrganizer.PlanItem item, int screen, int cellX, int cellY, float dragForce) {
                handlePlanItemMoved(item, screen, cellX, cellY, dragForce);
            }

            @Override
            public void onOpen(LauncherLayoutOrganizer.PlanItem item) {
                showPlanItemSheet(item);
            }
        });
        body.addView(designGridView, lp(match(), wrap()));
        card.addView(body);
        return card;
    }

    private View buildDesignActionsCard() {
        MaterialCardView card = card();
        LinearLayout body = cardBody();

        TextView title = text(getString(R.string.design_actions_title), 18, R.color.on_surface, true);
        body.addView(title, lp(match(), wrap()));

        designOrganizeButton = primaryButton(getString(R.string.action_organize_layout), R.drawable.ic_tools, v -> organizeDesignLayout());
        body.addView(designOrganizeButton, fullButtonLp());
        card.addView(body);
        return card;
    }

    private View buildDesignSummaryCard() {
        MaterialCardView card = card();
        LinearLayout body = cardBody();
        TextView title = text(getString(R.string.design_summary_title), 18, R.color.on_surface, true);
        body.addView(title, lp(match(), wrap()));
        designSummaryLabel = text("", 14, R.color.on_surface_variant, false);
        designSummaryLabel.setPadding(0, dp(8), 0, dp(8));
        designSummaryLabel.setTextIsSelectable(true);
        designSummaryLabel.setSingleLine(false);
        body.addView(designSummaryLabel, lp(match(), wrap()));
        designReportButton = secondaryButton(getString(R.string.action_copy_report), R.drawable.ic_copy, v -> copyText(lastReportText));
        body.addView(designReportButton, fullButtonLp());
        card.addView(body);
        return card;
    }

    private View buildRulesPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(color(R.color.surface));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(18), dp(4), dp(18), dp(8));
        rulesCountLabel = text("", 14, R.color.on_surface_variant, false);
        actions.addView(rulesCountLabel, new LinearLayout.LayoutParams(0, wrap(), 1f));
        actions.addView(secondaryButton(getString(R.string.action_reset_rules), R.drawable.ic_refresh, v -> confirmResetRules()), weightedButtonLp(true));
        actions.addView(primaryButton(getString(R.string.action_add_rule), R.drawable.ic_edit, v -> addRule()), weightedButtonLp(false));
        page.addView(actions, lp(match(), wrap()));

        LinearLayout appActions = new LinearLayout(this);
        appActions.setPadding(dp(18), 0, dp(18), dp(8));
        appActions.addView(secondaryButton(getString(R.string.action_device_apps), R.drawable.ic_library, v -> showDeviceAppsSheet()), weightedButtonLp(true));
        appActions.addView(secondaryButton(getString(R.string.action_ungrouped_apps), R.drawable.ic_select_all, v -> showUngroupedAppsSheet()), weightedButtonLp(false));
        page.addView(appActions, lp(match(), wrap()));

        RecyclerView rulesRecycler = new RecyclerView(this);
        rulesRecycler.setLayoutManager(new LinearLayoutManager(this));
        rulesRecycler.setClipToPadding(false);
        rulesRecycler.setPadding(dp(18), dp(4), dp(18), dp(22));
        ruleAdapter = new RuleAdapter((rule, position) -> showRuleSheet(rule, position));
        rulesRecycler.setAdapter(ruleAdapter);
        page.addView(rulesRecycler, new LinearLayout.LayoutParams(match(), 0, 1f));
        return page;
    }

    private View buildDeviceAppsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(4), dp(18), dp(22));
        page.setBackgroundColor(color(R.color.surface));
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));

        page.addView(secondaryButton(getString(R.string.action_back_to_rules), R.drawable.ic_close, v -> {
            addAppTargetRuleName = null;
            switchPage(PAGE_RULES);
        }), fullButtonLp());

        try {
            ArrayList<DeviceApp> apps = DeviceAppScanner.launchableApps(this);
            TextView meta = text(getString(R.string.device_apps_count, apps.size(), ungroupedApps(apps).size()), 14, R.color.on_surface_variant, false);
            meta.setPadding(0, dp(8), 0, dp(8));
            page.addView(meta, lp(match(), wrap()));
            for (DeviceApp app : apps) {
                String category = classifyDeviceApp(app);
                boolean targetMode = addAppTargetRuleName != null;
                String subtitle = targetMode
                        ? app.packageName
                        : app.packageName + " · " + category;
                page.addView(simpleActionRow(
                        app.label,
                        subtitle,
                        targetMode ? R.drawable.ic_upload : (isFallbackCategory(category) ? R.drawable.ic_select_all : R.drawable.ic_check),
                        v -> {
                            if (targetMode) {
                                RuleItem targetRule = ruleSet.ruleByName(addAppTargetRuleName);
                                if (targetRule != null) {
                                    addAppToRule(app, targetRule);
                                    routeRuleName = targetRule.name;
                                    addAppTargetRuleName = null;
                                    switchPage(PAGE_RULE_APPS);
                                }
                            } else if (isFallbackCategory(category)) {
                                chooseRuleForApp(app);
                            } else {
                                toast(getString(R.string.toast_app_already_grouped, category));
                            }
                        }), lp(match(), wrap()));
            }
        } catch (Throwable t) {
            page.addView(errorTextView(t), lp(match(), wrap()));
        }
        return scroll;
    }

    private View buildUngroupedAppsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(4), dp(18), dp(22));
        page.setBackgroundColor(color(R.color.surface));
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));

        page.addView(secondaryButton(getString(R.string.action_back_to_rules), R.drawable.ic_close, v -> switchPage(PAGE_RULES)), fullButtonLp());
        try {
            ArrayList<DeviceApp> apps = ungroupedApps(DeviceAppScanner.launchableApps(this));
            TextView meta = text(getString(R.string.ungrouped_apps_message, apps.size()), 14, R.color.on_surface_variant, false);
            meta.setPadding(0, dp(8), 0, dp(8));
            meta.setSingleLine(false);
            page.addView(meta, lp(match(), wrap()));
            if (apps.isEmpty()) {
                TextView empty = text(getString(R.string.rule_apps_empty), 14, R.color.on_surface_variant, false);
                empty.setPadding(0, dp(18), 0, dp(18));
                page.addView(empty, lp(match(), wrap()));
            } else {
                for (DeviceApp app : apps) {
                    page.addView(simpleActionRow(app.label, app.packageName, R.drawable.ic_file, v -> chooseRuleForApp(app)), lp(match(), wrap()));
                }
            }
        } catch (Throwable t) {
            page.addView(errorTextView(t), lp(match(), wrap()));
        }
        return scroll;
    }

    private View buildRuleAppsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(4), dp(18), dp(22));
        page.setBackgroundColor(color(R.color.surface));
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));

        RuleItem rule = routeRuleName == null || ruleSet == null ? null : ruleSet.ruleByName(routeRuleName);
        page.addView(secondaryButton(getString(R.string.action_back_to_rules), R.drawable.ic_close, v -> switchPage(PAGE_RULES)), fullButtonLp());
        if (rule == null) {
            page.addView(text(getString(R.string.toast_no_rules), 14, R.color.on_surface_variant, false), lp(match(), wrap()));
            return scroll;
        }

        page.addView(buttonRow(
                primaryButton(getString(R.string.action_add_app), R.drawable.ic_upload, v -> {
                    addAppTargetRuleName = rule.name;
                    switchPage(PAGE_APPS);
                }),
                secondaryButton(getString(R.string.action_sort_name), R.drawable.ic_select_all, v -> sortRuleAppsByName(rule))));

        try {
            ArrayList<DeviceApp> apps = appsForRule(rule, DeviceAppScanner.launchableApps(this));
            TextView meta = text(getString(R.string.rule_apps_message, apps.size(), sortLabel(rule)), 14, R.color.on_surface_variant, false);
            meta.setPadding(0, dp(8), 0, dp(8));
            page.addView(meta, lp(match(), wrap()));
            if (apps.isEmpty()) {
                TextView empty = text(getString(R.string.rule_apps_empty), 14, R.color.on_surface_variant, false);
                empty.setPadding(0, dp(18), 0, dp(18));
                page.addView(empty, lp(match(), wrap()));
            } else {
                for (int i = 0; i < apps.size(); i++) {
                    page.addView(appOrderRow(rule, ruleSet.rules.indexOf(rule), apps, i), lp(match(), wrap()));
                }
            }
        } catch (Throwable t) {
            page.addView(errorTextView(t), lp(match(), wrap()));
        }
        return scroll;
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(4), dp(18), dp(22));
        page.setBackgroundColor(color(R.color.surface));
        scroll.addView(page, new ScrollView.LayoutParams(match(), wrap()));

        page.addView(settingsCard(
                getString(R.string.settings_appearance_title),
                getString(R.string.settings_appearance_message),
                buttonRow(
                        secondaryButton(getString(R.string.settings_theme), R.drawable.ic_tools, v -> chooseTheme()),
                        secondaryButton(getString(R.string.settings_language), R.drawable.ic_file, v -> chooseLanguage()))), cardLp());

        page.addView(settingsCard(
                getString(R.string.settings_rules_title),
                getString(R.string.settings_rules_message),
                buttonRow(
                        secondaryButton(getString(R.string.action_open_rules), R.drawable.ic_edit, v -> {
                            navigationView.setSelectedItemId(PAGE_RULES);
                            switchPage(PAGE_RULES);
                        }),
                        secondaryButton(getString(R.string.action_reset_rules), R.drawable.ic_refresh, v -> confirmResetRules()))), cardLp());

        page.addView(settingsCard(
                getString(R.string.settings_diagnostics_title),
                getString(R.string.settings_diagnostics_message),
                buttonRow(
                        secondaryButton(getString(R.string.action_copy_diagnostics), R.drawable.ic_copy, v -> copyDiagnostics()),
                        secondaryButton(getString(R.string.action_about), R.drawable.ic_more, v -> showAbout()))), cardLp());
        return scroll;
    }

    private View settingsCard(String title, String message, View actionView) {
        MaterialCardView card = card();
        LinearLayout body = cardBody();
        TextView titleView = text(title, 18, R.color.on_surface, true);
        body.addView(titleView, lp(match(), wrap()));
        TextView messageView = text(message, 14, R.color.on_surface_variant, false);
        messageView.setPadding(0, dp(6), 0, dp(10));
        messageView.setSingleLine(false);
        body.addView(messageView, lp(match(), wrap()));
        body.addView(actionView, lp(match(), wrap()));
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

        TextView meta = text(layoutMeta(entry), 13, R.color.on_surface_variant, false);
        meta.setPadding(0, dp(6), 0, dp(14));
        sheet.addView(meta, lp(match(), wrap()));

        sheet.addView(primaryButton(getString(R.string.action_apply_layout), R.drawable.ic_play, v -> {
            dialog.dismiss();
            confirmApply(entry);
        }), fullButtonLp());

        sheet.addView(buttonRow(
                secondaryButton(getString(R.string.action_design_this), R.drawable.ic_tools, v -> {
                    dialog.dismiss();
                    designEntry = entry;
                    navigationView.setSelectedItemId(PAGE_DESIGN);
                    switchPage(PAGE_DESIGN);
                }),
                secondaryButton(getString(R.string.action_rename), R.drawable.ic_edit, v -> {
                    dialog.dismiss();
                    renameLayout(entry);
                })));

        sheet.addView(buttonRow(
                secondaryButton(getString(R.string.action_save_copy), R.drawable.ic_save, v -> {
                    dialog.dismiss();
                    saveCopy(entry);
                }),
                secondaryButton(getString(R.string.action_inspect_layout), R.drawable.ic_file, v -> {
                    dialog.dismiss();
                    showLayoutSummary(entry);
                })));

        sheet.addView(dangerButton(getString(R.string.action_delete), R.drawable.ic_delete, v -> {
            dialog.dismiss();
            confirmDelete(entry);
        }), fullButtonLp());

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showChooseLayoutSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));
        scroll.addView(sheet, new ScrollView.LayoutParams(match(), wrap()));

        TextView title = text(getString(R.string.choose_layout_title), 20, R.color.on_surface, true);
        sheet.addView(title, lp(match(), wrap()));
        if (layouts.isEmpty()) {
            TextView empty = text(getString(R.string.empty_layouts_message), 14, R.color.on_surface_variant, false);
            empty.setPadding(0, dp(12), 0, dp(12));
            sheet.addView(empty, lp(match(), wrap()));
        } else {
            for (LayoutEntry entry : layouts) {
                sheet.addView(simpleActionRow(entry.title, layoutMeta(entry), R.drawable.ic_file, v -> {
                    designEntry = entry;
                    renderDesignState();
                    dialog.dismiss();
                }), lp(match(), wrap()));
            }
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showRuleSheet(RuleItem rule, int position) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));

        TextView title = text(rule.name, 20, R.color.on_surface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sheet.addView(title, lp(match(), wrap()));
        TextView meta = text(ruleMeta(rule), 13, R.color.on_surface_variant, false);
        meta.setPadding(0, dp(6), 0, dp(14));
        sheet.addView(meta, lp(match(), wrap()));

        sheet.addView(primaryButton(getString(R.string.action_edit_rule), R.drawable.ic_edit, v -> {
            dialog.dismiss();
            editRule(rule, position);
        }), fullButtonLp());
        sheet.addView(buttonRow(
                secondaryButton(getString(R.string.action_rule_apps), R.drawable.ic_library, v -> {
                    dialog.dismiss();
                    showRuleAppsSheet(rule, position);
                }),
                secondaryButton(getString(R.string.action_sort_name), R.drawable.ic_select_all, v -> {
                    dialog.dismiss();
                    sortRuleAppsByName(rule);
                })));
        sheet.addView(buttonRow(
                secondaryButton(getString(R.string.action_move_up), R.drawable.ic_upload, v -> {
                    dialog.dismiss();
                    moveRule(position, -1);
                }),
                secondaryButton(getString(R.string.action_move_down), R.drawable.ic_download, v -> {
                    dialog.dismiss();
                    moveRule(position, 1);
                })));
        sheet.addView(dangerButton(getString(R.string.action_delete_rule), R.drawable.ic_delete, v -> {
            dialog.dismiss();
            confirmDeleteRule(position);
        }), fullButtonLp());
        dialog.setContentView(sheet);
        dialog.show();
    }

    private View simpleActionRow(String title, String subtitle, int icon, View.OnClickListener listener) {
        MaterialCardView card = card();
        card.setClickable(true);
        card.setOnClickListener(listener);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));

        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(color(R.color.primary)));
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setBackground(makeRoundBg(color(R.color.primary_container), dp(8)));
        row.addView(image, squareLp(42));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setPadding(dp(12), 0, 0, 0);
        TextView titleView = text(title, 15, R.color.on_surface, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textBlock.addView(titleView, lp(match(), wrap()));
        TextView subtitleView = text(subtitle, 12, R.color.on_surface_variant, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setPadding(0, dp(3), 0, 0);
        textBlock.addView(subtitleView, lp(match(), wrap()));
        row.addView(textBlock, new LinearLayout.LayoutParams(0, wrap(), 1f));

        card.addView(row);
        LinearLayout.LayoutParams params = lp(match(), wrap());
        params.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private void showDeviceAppsSheet() {
        addAppTargetRuleName = null;
        switchPage(PAGE_APPS);
    }

    private void showUngroupedAppsSheet() {
        switchPage(PAGE_UNGROUPED);
    }

    private void chooseRuleForApp(DeviceApp app) {
        if (ruleSet == null || ruleSet.rules.isEmpty()) {
            toast(getString(R.string.toast_no_rules));
            return;
        }
        String[] labels = new String[ruleSet.rules.size()];
        for (int i = 0; i < ruleSet.rules.size(); i++) {
            labels[i] = ruleSet.rules.get(i).name;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.choose_rule_for_app, app.label))
                .setItems(labels, (dialog, which) -> addAppToRule(app, ruleSet.rules.get(which)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void addAppToRule(DeviceApp app, RuleItem rule) {
        removePackageFromOtherRules(app.packageName, rule);
        addUnique(rule.mutableEquals("package"), app.packageName);
        addUnique(rule.appOrder, app.packageName);
        rule.sortMode = RuleItem.SORT_MANUAL;
        saveRules(false);
        renderDesignState();
        toast(getString(R.string.toast_app_added_to_rule, app.label, rule.name));
    }

    private void showRuleAppsSheet(RuleItem rule, int position) {
        routeRuleName = rule.name;
        switchPage(PAGE_RULE_APPS);
    }

    private View appOrderRow(RuleItem rule, int rulePosition, ArrayList<DeviceApp> apps, int appPosition) {
        DeviceApp app = apps.get(appPosition);
        MaterialCardView card = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(8), dp(10));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(app.label, 14, R.color.on_surface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textBlock.addView(title, lp(match(), wrap()));
        TextView meta = text(app.packageName, 11, R.color.on_surface_variant, false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, 0);
        textBlock.addView(meta, lp(match(), wrap()));
        row.addView(textBlock, new LinearLayout.LayoutParams(0, wrap(), 1f));

        ImageButton up = iconButton(R.drawable.ic_upload, getString(R.string.action_move_up), v -> {
            moveAppInRule(rule, apps, appPosition, -1, rulePosition);
        });
        up.setEnabled(appPosition > 0);
        up.setAlpha(appPosition > 0 ? 1f : 0.35f);
        row.addView(up, squareLp(34));

        ImageButton down = iconButton(R.drawable.ic_download, getString(R.string.action_move_down), v -> {
            moveAppInRule(rule, apps, appPosition, 1, rulePosition);
        });
        down.setEnabled(appPosition < apps.size() - 1);
        down.setAlpha(appPosition < apps.size() - 1 ? 1f : 0.35f);
        LinearLayout.LayoutParams downLp = squareLp(34);
        downLp.setMargins(dp(4), 0, 0, 0);
        row.addView(down, downLp);

        card.addView(row);
        LinearLayout.LayoutParams params = lp(match(), wrap());
        params.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private void sortRuleAppsByName(RuleItem rule) {
        try {
            ArrayList<DeviceApp> apps = appsForRule(rule, DeviceAppScanner.launchableApps(this));
            apps.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label));
            rule.sortMode = RuleItem.SORT_NAME;
            rule.appOrder.clear();
            for (DeviceApp app : apps) {
                addUnique(rule.appOrder, app.packageName);
            }
            saveRules(false);
            renderDesignState();
            toast(getString(R.string.toast_rule_sorted));
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void moveAppInRule(RuleItem rule, ArrayList<DeviceApp> apps, int appPosition, int delta, int rulePosition) {
        int target = appPosition + delta;
        if (target < 0 || target >= apps.size()) {
            return;
        }
        Collections.swap(apps, appPosition, target);
        rule.sortMode = RuleItem.SORT_MANUAL;
        rule.appOrder.clear();
        for (DeviceApp app : apps) {
            addUnique(rule.appOrder, app.packageName);
        }
        saveRules(false);
        renderDesignState();
        routeRuleName = rule.name;
        switchPage(PAGE_RULE_APPS);
    }

    private ArrayList<DeviceApp> appsForRule(RuleItem rule, ArrayList<DeviceApp> apps) throws Exception {
        ArrayList<DeviceApp> result = new ArrayList<>();
        for (DeviceApp app : apps) {
            if (rule.name.equals(classifyDeviceApp(app))) {
                result.add(app);
            }
        }
        orderApps(rule, result);
        return result;
    }

    private ArrayList<DeviceApp> ungroupedApps(ArrayList<DeviceApp> apps) throws Exception {
        ArrayList<DeviceApp> result = new ArrayList<>();
        for (DeviceApp app : apps) {
            if (isFallbackCategory(classifyDeviceApp(app))) {
                result.add(app);
            }
        }
        return result;
    }

    private void orderApps(RuleItem rule, ArrayList<DeviceApp> apps) {
        if (RuleItem.SORT_NAME.equals(rule.sortMode)) {
            apps.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label));
            return;
        }
        if (rule.appOrder.isEmpty()) {
            return;
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < rule.appOrder.size(); i++) {
            order.put(rule.appOrder.get(i), i);
        }
        apps.sort((left, right) -> {
            int leftOrder = order.containsKey(left.packageName) ? order.get(left.packageName) : Integer.MAX_VALUE;
            int rightOrder = order.containsKey(right.packageName) ? order.get(right.packageName) : Integer.MAX_VALUE;
            if (leftOrder != rightOrder) {
                return Integer.compare(leftOrder, rightOrder);
            }
            return String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label);
        });
    }

    private String classifyDeviceApp(DeviceApp app) throws Exception {
        if (ruleSet == null) {
            loadRules();
        }
        return ruleSet == null ? "" : ruleSet.classify(app.toRuleRecord());
    }

    private boolean isFallbackCategory(String category) {
        return ruleSet != null && TextUtils.equals(category, ruleSet.fallback);
    }

    private String sortLabel(RuleItem rule) {
        return RuleItem.SORT_NAME.equals(rule.sortMode) ? getString(R.string.sort_name) : getString(R.string.sort_manual);
    }

    private void addUnique(List<String> values, String value) {
        String item = value == null ? "" : value.trim();
        if (!item.isEmpty() && !values.contains(item)) {
            values.add(item);
        }
    }

    private void removePackageFromOtherRules(String packageName, RuleItem targetRule) {
        if (ruleSet == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        for (RuleItem rule : ruleSet.rules) {
            if (rule == targetRule) {
                continue;
            }
            removeValueIgnoreCase(rule.mutableEquals("package"), packageName);
            removeValueIgnoreCase(rule.mutableContains("package"), packageName);
            removeValueIgnoreCase(rule.appOrder, packageName);
        }
    }

    private void removeValueIgnoreCase(List<String> values, String value) {
        String target = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            return;
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            if (target.equals(values.get(i).trim().toLowerCase(Locale.ROOT))) {
                values.remove(i);
            }
        }
    }

    private void exportCurrent() {
        showTitleDialog(getString(R.string.dialog_export_title), getString(R.string.action_save), defaultExportTitle(), this::exportCurrentAs);
    }

    private void exportCurrentAs(String title) {
        String fileName = makeStorageName("layout", title);
        Uri uri = HomeScreenLayoutStudioContract.layoutUri(fileName);
        setLayoutTitle(uri, title);
        activeFileName = fileName;
        sendCommand(HomeScreenLayoutStudioContract.ACTION_EXPORT, uri, title);
        toast(getString(R.string.toast_export_started));
        refreshList();
    }

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/plain", "*/*"});
        startActivityForResult(intent, REQ_IMPORT);
    }

    private void autoCheck(LayoutEntry entry) {
        sendCommand(HomeScreenLayoutStudioContract.ACTION_DRY_RUN, entry.uri, entry.title);
    }

    private void confirmApply(LayoutEntry entry) {
        activeFileName = entry.fileName;
        renderListState();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_apply_title)
                .setMessage(getString(R.string.dialog_apply_message, entry.title))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, (dialog, which) -> {
                    sendCommand(HomeScreenLayoutStudioContract.ACTION_APPLY, entry.uri, entry.title);
                    toast(getString(R.string.toast_apply_started));
                })
                .show();
    }

    private void renameLayout(LayoutEntry entry) {
        showTitleDialog(getString(R.string.dialog_rename_title), getString(R.string.action_save), entry.title, title -> {
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
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getString(R.string.dialog_delete_message, entry.title))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
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
        if (currentPage != PAGE_LAYOUTS) {
            currentPage = PAGE_LAYOUTS;
            if (navigationView != null) {
                navigationView.setSelectedItemId(PAGE_LAYOUTS);
            }
            switchPage(PAGE_LAYOUTS);
        } else {
            renderListState();
        }
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
            toast(getString(R.string.toast_select_first));
            return;
        }
        int count = selectedFiles.size();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_selected_title)
                .setMessage(getString(R.string.dialog_delete_selected_message, count))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    for (String fileName : new ArrayList<>(selectedFiles)) {
                        LayoutEntry entry = findLayout(fileName);
                        if (entry != null) {
                            deleteLayout(entry);
                        } else {
                            getContentResolver().delete(HomeScreenLayoutStudioContract.layoutUri(fileName), null, null);
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
        if (designEntry != null && entry.fileName.equals(designEntry.fileName)) {
            designEntry = null;
        }
        designOverridesByFile.remove(entry.fileName);
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
                toast(getString(R.string.toast_file_saved));
            }
        } catch (Throwable t) {
            pendingSaveCopy = null;
            showError(t);
        }
    }

    private void importJson(Uri sourceUri) throws Exception {
        byte[] bytes = readAll(sourceUri);
        String json = new String(bytes, StandardCharsets.UTF_8);
        new JSONObject(json);
        String initialTitle = titleFromSourceName(displayName(sourceUri));
        showTitleDialog(getString(R.string.dialog_import_title), getString(R.string.action_save), initialTitle, title -> {
            try {
                saveImportedJson(bytes, title);
            } catch (Throwable t) {
                showError(t);
            }
        });
    }

    private void saveImportedJson(byte[] bytes, String title) throws Exception {
        String fileName = makeStorageName("import", title);
        Uri target = HomeScreenLayoutStudioContract.layoutUri(fileName);
        try (OutputStream outputStream = getContentResolver().openOutputStream(target, "wt")) {
            if (outputStream == null) {
                throw new IllegalStateException("Cannot open target layout");
            }
            outputStream.write(bytes);
        }
        setLayoutTitle(target, title);
        LayoutEntry entry = new LayoutEntry(title, fileName, target, bytes.length, System.currentTimeMillis());
        activeFileName = fileName;
        designEntry = entry;
        refreshList();
        toast(getString(R.string.toast_imported));
        autoCheck(entry);
        showLayoutSheet(entry);
    }

    private void organizeDesignLayout() {
        if (designEntry == null) {
            toast(getString(R.string.toast_choose_layout_first));
            showChooseLayoutSheet();
            return;
        }
        try {
            RuleSet rules = RuleStore.load(this);
            String json = readString(designEntry.uri);
            LauncherLayoutOrganizer.OrganizeResult result = LauncherLayoutOrganizer.organize(json, rules, currentDesignOverrides());
            String defaultTitle = getString(R.string.default_organized_title, designEntry.title);
            showTitleDialog(getString(R.string.dialog_organized_title), getString(R.string.action_save), defaultTitle, title -> {
                try {
                    saveOrganizedJson(result, title);
                } catch (Throwable t) {
                    showError(t);
                }
            });
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void saveOrganizedJson(LauncherLayoutOrganizer.OrganizeResult result, String title) throws Exception {
        byte[] bytes = result.json.getBytes(StandardCharsets.UTF_8);
        String fileName = makeStorageName("design", title);
        Uri target = HomeScreenLayoutStudioContract.layoutUri(fileName);
        try (OutputStream outputStream = getContentResolver().openOutputStream(target, "wt")) {
            if (outputStream == null) {
                throw new IllegalStateException("Cannot open target layout");
            }
            outputStream.write(bytes);
        }
        setLayoutTitle(target, title);
        LayoutEntry entry = new LayoutEntry(title, fileName, target, bytes.length, System.currentTimeMillis());
        activeFileName = fileName;
        designEntry = entry;
        lastReportText = result.reportText;
        refreshList();
        renderDesignState();
        autoCheck(entry);
        showOrganizedReportSheet(entry, result.reportText);
    }

    private void inspectDesignLayout() {
        if (designEntry == null) {
            toast(getString(R.string.toast_choose_layout_first));
            showChooseLayoutSheet();
            return;
        }
        showLayoutSummary(designEntry);
    }

    private LauncherLayoutOrganizer.DesignOverrides currentDesignOverrides() {
        if (designEntry == null) {
            return new LauncherLayoutOrganizer.DesignOverrides();
        }
        LauncherLayoutOrganizer.DesignOverrides overrides = designOverridesByFile.get(designEntry.fileName);
        if (overrides == null) {
            overrides = new LauncherLayoutOrganizer.DesignOverrides();
            designOverridesByFile.put(designEntry.fileName, overrides);
        }
        return overrides;
    }

    private void handlePlanItemMoved(LauncherLayoutOrganizer.PlanItem item, int screen, int cellX, int cellY, float dragForce) {
        if (item == null || designEntry == null) {
            return;
        }
        DragPlacementResult result = placeDraggedItem(item, screen, cellX, cellY, dragForce);
        if (!result.changed) {
            return;
        }
        if (result.rulesChanged) {
            saveRules(false);
        }
        renderDesignState();
        toast(getString(item.widget ? R.string.toast_widget_position_saved : R.string.toast_group_position_saved));
    }

    private DragPlacementResult placeDraggedItem(LauncherLayoutOrganizer.PlanItem movingItem, int screen, int cellX, int cellY, float dragForce) {
        LauncherLayoutOrganizer.LayoutPlan plan = designGridView == null ? null : designGridView.currentPlan();
        if (plan == null) {
            return DragPlacementResult.unchanged();
        }
        DesignPlacement moving = placementFor(movingItem, screen, cellX, cellY);
        if (!fitsGrid(moving, plan.gridWidth, plan.gridHeight)) {
            toast(getString(R.string.toast_cell_occupied));
            return DragPlacementResult.unchanged();
        }

        LinkedHashMap<String, DesignPlacement> placements = new LinkedHashMap<>();
        for (LauncherLayoutOrganizer.PlanItem item : plan.items) {
            placements.put(item.id, placementFor(item, item.screen, item.cellX, item.cellY));
        }
        DesignPlacement current = placements.get(movingItem.id);
        if (current == null) {
            return DragPlacementResult.unchanged();
        }
        DesignPlacement released = current.copy();
        current.screen = moving.screen;
        current.cellX = moving.cellX;
        current.cellY = moving.cellY;

        ArrayList<DesignPlacement> displaced = new ArrayList<>();
        for (DesignPlacement placement : placements.values()) {
            if (placement.id.equals(current.id)) {
                continue;
            }
            if (placementsOverlap(current, placement)) {
                displaced.add(placement);
            }
        }
        displaced.sort((left, right) -> {
            int result = Integer.compare(right.area(), left.area());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(left.screen, right.screen);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(left.cellY, right.cellY);
            return result != 0 ? result : Integer.compare(left.cellX, right.cellX);
        });
        if (!displaced.isEmpty() && dragForce < requiredPushForce(displaced)) {
            return DragPlacementResult.unchanged();
        }

        Set<String> displacedIds = new HashSet<>();
        for (DesignPlacement placement : displaced) {
            displacedIds.add(placement.id);
        }

        Map<Integer, Set<String>> occupied = new LinkedHashMap<>();
        for (DesignPlacement placement : placements.values()) {
            if (displacedIds.contains(placement.id) || placement.id.equals(current.id)) {
                continue;
            }
            occupyCells(occupied, placement);
        }
        occupyCells(occupied, current);

        for (DesignPlacement placement : displaced) {
            DesignPlacement next = findFreePlacement(placement, occupied, released, plan.gridWidth, plan.gridHeight, plan.screenCount);
            if (next == null) {
                toast(getString(R.string.toast_no_space_for_push));
                return DragPlacementResult.unchanged();
            }
            placement.screen = next.screen;
            placement.cellX = next.cellX;
            placement.cellY = next.cellY;
            occupyCells(occupied, placement);
        }

        DragPlacementResult result = new DragPlacementResult();
        result.changed = true;
        result.rulesChanged |= commitPlacement(current);
        for (DesignPlacement placement : displaced) {
            result.rulesChanged |= commitPlacement(placement);
        }
        return result;
    }

    private float requiredPushForce(ArrayList<DesignPlacement> displaced) {
        int extraItems = Math.max(0, displaced.size() - 1);
        int totalArea = 0;
        for (DesignPlacement placement : displaced) {
            totalArea += placement.area();
        }
        int areaPenalty = Math.min(3, Math.max(0, totalArea - 1));
        return dp(54) + extraItems * dp(18) + areaPenalty * dp(8);
    }

    private DesignPlacement findFreePlacement(
            DesignPlacement placement,
            Map<Integer, Set<String>> occupied,
            DesignPlacement released,
            int gridWidth,
            int gridHeight,
            int screenCount) {
        DesignPlacement preferred = placement.copy();
        preferred.screen = released.screen;
        preferred.cellX = released.cellX;
        preferred.cellY = released.cellY;
        if (fitsGrid(preferred, gridWidth, gridHeight) && canPlace(occupied, preferred)) {
            return preferred;
        }
        int maxScreens = Math.max(screenCount + 8, Math.max(screenCount, released.screen + 1));
        for (int screen = 0; screen < maxScreens; screen++) {
            for (int y = 0; y <= gridHeight - placement.spanY; y++) {
                for (int x = 0; x <= gridWidth - placement.spanX; x++) {
                    DesignPlacement candidate = placement.copy();
                    candidate.screen = screen;
                    candidate.cellX = x;
                    candidate.cellY = y;
                    if (canPlace(occupied, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private boolean commitPlacement(DesignPlacement placement) {
        if (placement.item.widget) {
            currentDesignOverrides().widgetPlacements.put(placement.id, new LauncherLayoutOrganizer.Placement(placement.screen, placement.cellX, placement.cellY));
            return false;
        }
        RuleItem rule = ruleSet == null ? null : ruleSet.ruleByName(placement.item.title);
        if (rule == null) {
            return false;
        }
        rule.layout.manualScreen = placement.screen;
        rule.layout.manualCellX = placement.cellX;
        rule.layout.manualCellY = placement.cellY;
        rule.layout.preferredScreen = placement.screen;
        return true;
    }

    private boolean targetOverlapsAny(LauncherLayoutOrganizer.PlanItem movingItem, int screen, int cellX, int cellY, int spanX, int spanY) {
        LauncherLayoutOrganizer.LayoutPlan plan = designGridView == null ? null : designGridView.currentPlan();
        if (plan == null) {
            return false;
        }
        DesignPlacement target = placementFor(movingItem, screen, cellX, cellY, spanX, spanY);
        if (!fitsGrid(target, plan.gridWidth, plan.gridHeight)) {
            return true;
        }
        for (LauncherLayoutOrganizer.PlanItem item : plan.items) {
            if (item.id.equals(movingItem.id)) {
                continue;
            }
            if (rectsOverlap(screen, cellX, cellY, spanX, spanY, item.screen, item.cellX, item.cellY, item.spanX, item.spanY)) {
                return true;
            }
        }
        return false;
    }

    private void showPlanItemSheet(LauncherLayoutOrganizer.PlanItem item) {
        if (item == null) {
            return;
        }
        if (item.widget) {
            showWidgetPlanSheet(item);
        } else {
            showGroupPlanSheet(item);
        }
    }

    private void showWidgetPlanSheet(LauncherLayoutOrganizer.PlanItem item) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));

        TextView title = text(item.title, 20, R.color.on_surface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sheet.addView(title, lp(match(), wrap()));
        TextView meta = text(getString(R.string.plan_item_meta, item.screen + 1, item.cellX, item.cellY, item.spanX, item.spanY), 13, R.color.on_surface_variant, false);
        meta.setPadding(0, dp(6), 0, dp(14));
        sheet.addView(meta, lp(match(), wrap()));
        sheet.addView(secondaryButton(getString(R.string.action_clear_position), R.drawable.ic_refresh, v -> {
            currentDesignOverrides().widgetPlacements.remove(item.id);
            dialog.dismiss();
            renderDesignState();
        }), fullButtonLp());
        dialog.setContentView(sheet);
        dialog.show();
    }

    private void showGroupPlanSheet(LauncherLayoutOrganizer.PlanItem item) {
        RuleItem rule = ruleSet == null ? null : ruleSet.ruleByName(item.title);
        if (rule == null) {
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));

        TextView title = text(rule.name, 20, R.color.on_surface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sheet.addView(title, lp(match(), wrap()));
        TextView meta = text(getString(R.string.plan_group_meta, item.childCount, item.screen + 1, item.cellX, item.cellY, rule.layout.spanX, rule.layout.spanY), 13, R.color.on_surface_variant, false);
        meta.setPadding(0, dp(6), 0, dp(14));
        sheet.addView(meta, lp(match(), wrap()));

        sheet.addView(buttonRow(
                secondaryButton("1x1", R.drawable.ic_file, v -> updateRuleSpan(rule, item, dialog, 1, 1)),
                secondaryButton("2x2", R.drawable.ic_file, v -> updateRuleSpan(rule, item, dialog, 2, 2))));
        sheet.addView(buttonRow(
                secondaryButton("2x3", R.drawable.ic_file, v -> updateRuleSpan(rule, item, dialog, 2, 3)),
                secondaryButton("3x2", R.drawable.ic_file, v -> updateRuleSpan(rule, item, dialog, 3, 2))));
        sheet.addView(buttonRow(
                secondaryButton(getString(R.string.action_rule_apps), R.drawable.ic_library, v -> {
                    dialog.dismiss();
                    showRuleAppsSheet(rule, ruleSet.rules.indexOf(rule));
                }),
                secondaryButton(getString(R.string.action_edit_rule), R.drawable.ic_edit, v -> {
                    dialog.dismiss();
                    editRule(rule, ruleSet.rules.indexOf(rule));
                })));
        sheet.addView(secondaryButton(getString(R.string.action_clear_position), R.drawable.ic_refresh, v -> {
            rule.layout.manualScreen = null;
            rule.layout.manualCellX = null;
            rule.layout.manualCellY = null;
            saveRules(false);
            dialog.dismiss();
            renderDesignState();
        }), fullButtonLp());
        dialog.setContentView(sheet);
        dialog.show();
    }

    private void updateRuleSpan(RuleItem rule, LauncherLayoutOrganizer.PlanItem item, BottomSheetDialog dialog, int spanX, int spanY) {
        if (targetOverlapsAny(item, item.screen, item.cellX, item.cellY, spanX, spanY)) {
            toast(getString(R.string.toast_cell_occupied));
            return;
        }
        rule.layout.spanX = spanX;
        rule.layout.spanY = spanY;
        rule.layout.manualScreen = item.screen;
        rule.layout.manualCellX = item.cellX;
        rule.layout.manualCellY = item.cellY;
        rule.layout.preferredScreen = item.screen;
        saveRules(false);
        dialog.dismiss();
        renderDesignState();
        toast(getString(R.string.toast_rule_size_saved));
    }

    private DesignPlacement placementFor(LauncherLayoutOrganizer.PlanItem item, int screen, int cellX, int cellY) {
        return placementFor(item, screen, cellX, cellY, item.spanX, item.spanY);
    }

    private DesignPlacement placementFor(LauncherLayoutOrganizer.PlanItem item, int screen, int cellX, int cellY, int spanX, int spanY) {
        return new DesignPlacement(item, screen, cellX, cellY, spanX, spanY);
    }

    private boolean rectsOverlap(int leftX, int topY, int leftSpanX, int leftSpanY, int rightX, int rightY, int rightSpanX, int rightSpanY) {
        return leftX < rightX + rightSpanX
                && leftX + leftSpanX > rightX
                && topY < rightY + rightSpanY
                && topY + leftSpanY > rightY;
    }

    private boolean rectsOverlap(int leftScreen, int leftX, int leftY, int leftSpanX, int leftSpanY, int rightScreen, int rightX, int rightY, int rightSpanX, int rightSpanY) {
        return leftScreen == rightScreen && rectsOverlap(leftX, leftY, leftSpanX, leftSpanY, rightX, rightY, rightSpanX, rightSpanY);
    }

    private boolean placementsOverlap(DesignPlacement left, DesignPlacement right) {
        return rectsOverlap(left.screen, left.cellX, left.cellY, left.spanX, left.spanY, right.screen, right.cellX, right.cellY, right.spanX, right.spanY);
    }

    private boolean fitsGrid(DesignPlacement placement, int gridWidth, int gridHeight) {
        return placement.cellX >= 0
                && placement.cellY >= 0
                && placement.cellX + placement.spanX <= gridWidth
                && placement.cellY + placement.spanY <= gridHeight;
    }

    private boolean canPlace(Map<Integer, Set<String>> occupied, DesignPlacement placement) {
        Set<String> cells = occupied.get(placement.screen);
        if (cells == null) {
            return true;
        }
        for (int x = placement.cellX; x < placement.cellX + placement.spanX; x++) {
            for (int y = placement.cellY; y < placement.cellY + placement.spanY; y++) {
                if (cells.contains(cellKey(x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void occupyCells(Map<Integer, Set<String>> occupied, DesignPlacement placement) {
        Set<String> cells = occupied.get(placement.screen);
        if (cells == null) {
            cells = new HashSet<>();
            occupied.put(placement.screen, cells);
        }
        for (int x = placement.cellX; x < placement.cellX + placement.spanX; x++) {
            for (int y = placement.cellY; y < placement.cellY + placement.spanY; y++) {
                cells.add(cellKey(x, y));
            }
        }
    }

    private String cellKey(int x, int y) {
        return x + ":" + y;
    }

    private void showLayoutSummary(LayoutEntry entry) {
        try {
            String summary = summaryText(LauncherLayoutOrganizer.summarize(readString(entry.uri)));
            designSummaryLabelSet(summary);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(entry.title)
                    .setMessage(summary)
                    .setNegativeButton(R.string.action_close, null)
                    .setPositiveButton(R.string.action_copy, (dialog, which) -> copyText(summary))
                    .show();
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void showOrganizedReportSheet(LayoutEntry entry, String report) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(22));
        sheet.setBackgroundColor(color(R.color.surface_container));
        scroll.addView(sheet, new ScrollView.LayoutParams(match(), wrap()));

        TextView title = text(getString(R.string.organized_done_title), 20, R.color.on_surface, true);
        sheet.addView(title, lp(match(), wrap()));
        TextView meta = text(entry.title, 14, R.color.on_surface_variant, false);
        meta.setPadding(0, dp(6), 0, dp(12));
        sheet.addView(meta, lp(match(), wrap()));

        TextView reportView = text(reportPreview(report), 13, R.color.on_surface_variant, false);
        reportView.setTextIsSelectable(true);
        reportView.setSingleLine(false);
        reportView.setPadding(0, 0, 0, dp(10));
        sheet.addView(reportView, lp(match(), wrap()));

        sheet.addView(primaryButton(getString(R.string.action_apply_layout), R.drawable.ic_play, v -> {
            dialog.dismiss();
            confirmApply(entry);
        }), fullButtonLp());
        sheet.addView(buttonRow(
                secondaryButton(getString(R.string.action_copy_report), R.drawable.ic_copy, v -> copyText(report)),
                secondaryButton(getString(R.string.action_open_library), R.drawable.ic_library, v -> {
                    dialog.dismiss();
                    navigationView.setSelectedItemId(PAGE_LAYOUTS);
                })));
        dialog.setContentView(scroll);
        dialog.show();
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
            String token = HomeScreenLayoutStudioContract.getToken(this);
            if (TextUtils.isEmpty(token)) {
                throw new IllegalStateException("No command token");
            }
            Intent intent = new Intent(action);
            intent.setPackage(HomeScreenLayoutStudioContract.LAUNCHER_PACKAGE);
            intent.putExtra(HomeScreenLayoutStudioContract.EXTRA_TOKEN, token);
            if (uri != null) {
                intent.putExtra(HomeScreenLayoutStudioContract.EXTRA_URI, uri.toString());
            }
            if (title != null) {
                intent.putExtra(HomeScreenLayoutStudioContract.EXTRA_NAME, title);
            }
            intent.putExtra(HomeScreenLayoutStudioContract.EXTRA_COMMAND_ID, timestamp());
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            sendBroadcast(intent);
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void refreshStatus() {
        Bundle status = HomeScreenLayoutStudioContract.getStatus(this);
        currentStatus = status == null ? Bundle.EMPTY : status;
        renderStatus();
    }

    private void refreshList() {
        layouts.clear();
        try (Cursor cursor = getContentResolver().query(
                HomeScreenLayoutStudioContract.layoutsUri(),
                null,
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String title = cursor.getString(cursor.getColumnIndexOrThrow(HomeScreenLayoutStudioContract.COLUMN_NAME));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(HomeScreenLayoutStudioContract.COLUMN_SIZE));
                    long modified = cursor.getLong(cursor.getColumnIndexOrThrow(HomeScreenLayoutStudioContract.COLUMN_MODIFIED));
                    String uri = cursor.getString(cursor.getColumnIndexOrThrow(HomeScreenLayoutStudioContract.COLUMN_URI));
                    String fileName = cursor.getString(cursor.getColumnIndexOrThrow(HomeScreenLayoutStudioContract.COLUMN_FILE_NAME));
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
        if (designEntry == null && !layouts.isEmpty()) {
            designEntry = layouts.get(0);
        } else if (designEntry != null) {
            LayoutEntry refreshed = findLayout(designEntry.fileName);
            if (refreshed != null) {
                designEntry = refreshed;
            }
        }
        renderListState();
        renderDesignState();
    }

    private void loadRules() {
        try {
            ruleSet = RuleStore.load(this);
            renderRulesState();
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void renderStatus() {
        if (errorPanel == null) {
            return;
        }
        String action = currentStatus.getString(HomeScreenLayoutStudioContract.KEY_ACTION, "");
        boolean success = currentStatus.getBoolean(HomeScreenLayoutStudioContract.KEY_SUCCESS, false);
        if (TextUtils.isEmpty(action) || success) {
            lastErrorText = "";
            errorPanel.setVisibility(View.GONE);
            return;
        }

        String message = currentStatus.getString(HomeScreenLayoutStudioContract.KEY_MESSAGE, "");
        String title = currentStatus.getString(HomeScreenLayoutStudioContract.KEY_NAME, "");
        long time = currentStatus.getLong(HomeScreenLayoutStudioContract.KEY_TIME, 0L);
        lastErrorText = errorDetail(action, title, message, time);
        errorMeta.setText(actionLabel(action));
        errorMessage.setText(lastErrorText);
        errorPanel.setVisibility(View.VISIBLE);
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
            listTitleLabel.setText(selectionMode ? getString(R.string.selected_count, selectedCount) : getString(R.string.saved_layouts));
        }
        if (countLabel != null) {
            countLabel.setText(selectionMode ? getString(R.string.total_count, layouts.size()) : getString(R.string.count_items, layouts.size()));
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

    private void renderDesignState() {
        if (designSelectedTitle == null) {
            return;
        }
        boolean hasSelection = designEntry != null;
        designSelectedTitle.setText(hasSelection ? designEntry.title : getString(R.string.design_no_selection));
        designSelectedMeta.setText(hasSelection ? layoutMeta(designEntry) : getString(R.string.design_no_selection_message));
        if (designOrganizeButton != null) {
            designOrganizeButton.setEnabled(hasSelection);
            designOrganizeButton.setAlpha(hasSelection ? 1f : 0.45f);
        }
        String summary = getString(R.string.design_summary_empty);
        if (hasSelection) {
            try {
                String json = readString(designEntry.uri);
                summary = summaryText(LauncherLayoutOrganizer.summarize(json));
                if (designGridView != null) {
                    designGridView.setPlan(LauncherLayoutOrganizer.plan(json, ruleSet == null ? RuleStore.load(this) : ruleSet, currentDesignOverrides()));
                }
            } catch (Throwable t) {
                summary = t.getClass().getSimpleName() + ": " + t.getMessage();
                if (designGridView != null) {
                    designGridView.setPlan(null);
                }
            }
        } else if (designGridView != null) {
            designGridView.setPlan(null);
        }
        designSummaryLabelSet(summary);
        if (designReportButton != null) {
            boolean hasReport = !TextUtils.isEmpty(lastReportText);
            designReportButton.setVisibility(hasReport ? View.VISIBLE : View.GONE);
        }
    }

    private void designSummaryLabelSet(String value) {
        if (designSummaryLabel != null) {
            designSummaryLabel.setText(value);
        }
    }

    private void renderRulesState() {
        if (ruleAdapter != null && ruleSet != null) {
            ruleAdapter.submit(ruleSet.rules);
        }
        if (rulesCountLabel != null) {
            int count = ruleSet == null ? 0 : ruleSet.rules.size();
            rulesCountLabel.setText(getString(R.string.rules_count, count));
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

    private void addRule() {
        if (ruleSet == null) {
            loadRules();
        }
        RuleItem rule = new RuleItem(getString(R.string.new_rule_default_name));
        rule.layout.spanX = 2;
        rule.layout.spanY = 2;
        rule.layout.priority = 500;
        editRule(rule, -1);
    }

    private void editRule(RuleItem original, int position) {
        RuleItem draft = original.copy();
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(2), dp(8), dp(2), 0);
        scroll.addView(body, new ScrollView.LayoutParams(match(), wrap()));

        EditText nameInput = editText(draft.name, getString(R.string.rule_name_hint), false);
        EditText titleInput = editText(joinLines(draft.mutableContains("title")), getString(R.string.rule_title_keywords_hint), true);
        EditText packageInput = editText(joinLines(draft.mutableContains("package")), getString(R.string.rule_package_keywords_hint), true);
        EditText spanXInput = editText(String.valueOf(draft.layout.spanX), getString(R.string.rule_span_x_hint), false);
        EditText spanYInput = editText(String.valueOf(draft.layout.spanY), getString(R.string.rule_span_y_hint), false);
        EditText priorityInput = editText(String.valueOf(draft.layout.priority), getString(R.string.rule_priority_hint), false);
        EditText screenInput = editText(draft.layout.preferredScreen == null ? "" : String.valueOf(draft.layout.preferredScreen), getString(R.string.rule_screen_hint), false);

        body.addView(labeledInput(getString(R.string.rule_name), nameInput));
        body.addView(labeledInput(getString(R.string.rule_title_keywords), titleInput));
        body.addView(labeledInput(getString(R.string.rule_package_keywords), packageInput));
        body.addView(labeledInput(getString(R.string.rule_span_x), spanXInput));
        body.addView(labeledInput(getString(R.string.rule_span_y), spanYInput));
        body.addView(labeledInput(getString(R.string.rule_priority), priorityInput));
        body.addView(labeledInput(getString(R.string.rule_preferred_screen), screenInput));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(position < 0 ? R.string.action_add_rule : R.string.action_edit_rule)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                draft.name = normalizeTitle(nameInput.getText() == null ? "" : nameInput.getText().toString());
                if (draft.name.isEmpty()) {
                    toast(getString(R.string.toast_rule_name_required));
                    return;
                }
                draft.contains.put("title", splitLines(titleInput.getText() == null ? "" : titleInput.getText().toString()));
                draft.contains.put("package", splitLines(packageInput.getText() == null ? "" : packageInput.getText().toString()));
                draft.layout.spanX = clamp(parseInt(spanXInput, 1), 1, 4);
                draft.layout.spanY = clamp(parseInt(spanYInput, 1), 1, 6);
                draft.layout.priority = parseInt(priorityInput, 0);
                String screenRaw = screenInput.getText() == null ? "" : screenInput.getText().toString().trim();
                draft.layout.preferredScreen = screenRaw.isEmpty() ? null : Math.max(0, Integer.parseInt(screenRaw));
                if (position < 0) {
                    ruleSet.rules.add(draft);
                } else {
                    ruleSet.rules.set(position, draft);
                }
                saveRules();
                dialog.dismiss();
            } catch (Throwable t) {
                showError(t);
            }
        }));
        dialog.show();
    }

    private EditText editText(String value, String hint, boolean multiline) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setSingleLine(!multiline);
        if (multiline) {
            input.setMinLines(3);
            input.setGravity(Gravity.TOP | Gravity.START);
        }
        return input;
    }

    private View labeledInput(String label, EditText input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(8));
        TextView labelView = text(label, 13, R.color.on_surface_variant, true);
        box.addView(labelView, lp(match(), wrap()));
        box.addView(input, lp(match(), wrap()));
        return box;
    }

    private void moveRule(int position, int delta) {
        if (ruleSet == null) {
            return;
        }
        int target = position + delta;
        if (position < 0 || position >= ruleSet.rules.size() || target < 0 || target >= ruleSet.rules.size()) {
            return;
        }
        RuleItem item = ruleSet.rules.remove(position);
        ruleSet.rules.add(target, item);
        saveRules();
    }

    private void confirmDeleteRule(int position) {
        if (ruleSet == null || position < 0 || position >= ruleSet.rules.size()) {
            return;
        }
        RuleItem rule = ruleSet.rules.get(position);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_rule_title)
                .setMessage(getString(R.string.dialog_delete_rule_message, rule.name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    ruleSet.rules.remove(position);
                    saveRules();
                })
                .show();
    }

    private void confirmResetRules() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_reset_rules_title)
                .setMessage(R.string.dialog_reset_rules_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    try {
                        ruleSet = RuleStore.reset(this);
                        renderRulesState();
                        toast(getString(R.string.toast_rules_reset));
                    } catch (Throwable t) {
                        showError(t);
                    }
                })
                .show();
    }

    private void saveRules() {
        saveRules(true);
    }

    private void saveRules(boolean showToast) {
        try {
            RuleStore.save(this, ruleSet);
            renderRulesState();
            if (showToast) {
                toast(getString(R.string.toast_rules_saved));
            }
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void chooseTheme() {
        String[] values = {AppSettings.THEME_SYSTEM, AppSettings.THEME_LIGHT, AppSettings.THEME_DARK};
        String[] labels = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)};
        int checked = indexOf(values, AppSettings.theme(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setTheme(this, values[which]);
                    dialog.dismiss();
                    recreate();
                })
                .show();
    }

    private void chooseLanguage() {
        String[] values = {AppSettings.LANGUAGE_SYSTEM, AppSettings.LANGUAGE_ZH, AppSettings.LANGUAGE_EN};
        String[] labels = {getString(R.string.language_system), getString(R.string.language_zh), getString(R.string.language_en)};
        int checked = indexOf(values, AppSettings.language(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppSettings.setLanguage(this, values[which]);
                    dialog.dismiss();
                    recreate();
                })
                .show();
    }

    private void copyDiagnostics() {
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.app_name)).append('\n');
        builder.append("package=").append(getPackageName()).append('\n');
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            builder.append("version=").append(info.versionName).append(" (").append(info.versionCode).append(")\n");
        } catch (Throwable ignored) {
        }
        builder.append("theme=").append(AppSettings.theme(this)).append('\n');
        builder.append("language=").append(AppSettings.language(this)).append('\n');
        builder.append("layouts=").append(layouts.size()).append('\n');
        builder.append("rules=").append(ruleSet == null ? 0 : ruleSet.rules.size()).append('\n');
        if (!TextUtils.isEmpty(lastErrorText)) {
            builder.append("\nlast_error=\n").append(lastErrorText).append('\n');
        }
        copyText(builder.toString());
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private String errorDetail(String action, String title, String message, long time) {
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.error_doing, actionLabel(action)));
        if (!TextUtils.isEmpty(title)) {
            builder.append('\n').append(getString(R.string.error_layout, title));
        }
        if (!TextUtils.isEmpty(message)) {
            builder.append('\n').append(getString(R.string.error_reason, message));
        }
        if (time > 0L) {
            builder.append('\n').append(getString(R.string.error_time, formatDate(time)));
        }
        return builder.toString();
    }

    private String actionLabel(String action) {
        if ("ready".equals(action)) {
            return getString(R.string.status_ready);
        }
        if ("export".equals(action)) {
            return getString(R.string.action_export_current);
        }
        if ("dry_run".equals(action)) {
            return getString(R.string.status_dry_run);
        }
        if ("apply".equals(action)) {
            return getString(R.string.action_apply_layout);
        }
        if ("ping".equals(action)) {
            return getString(R.string.status_ping);
        }
        return action.toUpperCase(Locale.US);
    }

    private String sourceLabel(String fileName) {
        if (fileName.startsWith("before-apply-")) {
            return getString(R.string.source_before_apply);
        }
        if (fileName.startsWith("import-")) {
            return getString(R.string.source_import);
        }
        if (fileName.startsWith("design-")) {
            return getString(R.string.source_design);
        }
        if (fileName.startsWith("layout-")) {
            return getString(R.string.source_saved);
        }
        return getString(R.string.source_layout_file);
    }

    private String layoutMeta(LayoutEntry entry) {
        return sourceLabel(entry.fileName) + " · " + formatSize(entry.size) + " · " + formatDate(entry.modified);
    }

    private String ruleMeta(RuleItem rule) {
        String screen = rule.layout.hasManualPosition()
                ? getString(R.string.rule_position, rule.layout.manualScreen + 1, rule.layout.manualCellX, rule.layout.manualCellY)
                : (rule.layout.preferredScreen == null ? "-" : String.valueOf(rule.layout.preferredScreen + 1));
        return getString(R.string.rule_meta, rule.layout.spanX, rule.layout.spanY, rule.layout.priority, screen, rule.keywordCount(), sortLabel(rule), rule.appOrder.size());
    }

    private String summaryText(LauncherLayoutOrganizer.LayoutSummary summary) {
        StringBuilder builder = new StringBuilder();
        for (String modeName : summary.modes.keySet()) {
            LauncherLayoutOrganizer.ModeSummary mode = summary.modes.get(modeName);
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(modeName).append('\n');
            builder.append(getString(R.string.summary_screens, mode.screens)).append('\n');
            builder.append(getString(R.string.summary_folders, mode.folders)).append('\n');
            builder.append(getString(R.string.summary_folder_sizes, mode.folderSpanSummary())).append('\n');
            builder.append(getString(R.string.summary_desktop_items, mode.desktopItems)).append('\n');
            builder.append(getString(R.string.summary_hotseat_items, mode.hotseatItems)).append('\n');
            builder.append(getString(R.string.summary_folder_children, mode.folderChildren));
        }
        return builder.length() == 0 ? getString(R.string.design_summary_empty) : builder.toString();
    }

    private String reportPreview(String report) {
        if (report == null) {
            return "";
        }
        return report.length() > 1800 ? report.substring(0, 1800) + "\n..." : report;
    }

    private void showTitleDialog(String title, String positive, String initial, TitleCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{this::filterTitleInput});
        input.setText(normalizeTitle(initial));
        input.setSelectAllOnFocus(true);
        input.setHint(getString(R.string.title_hint));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(2);
        container.setPadding(horizontal, dp(8), horizontal, 0);
        container.addView(input, lp(match(), wrap()));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(positive, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = normalizeTitle(input.getText() == null ? "" : input.getText().toString());
                if (TextUtils.isEmpty(value)) {
                    toast(getString(R.string.toast_title_required));
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
        values.put(HomeScreenLayoutStudioContract.COLUMN_NAME, normalizeTitle(title));
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
        return getString(R.string.default_export_title, DateFormat.format("MM-dd HH-mm", System.currentTimeMillis()));
    }

    private String titleFromSourceName(String sourceName) {
        String value = TextUtils.isEmpty(sourceName) ? getString(R.string.default_import_title) : sourceName.trim();
        if (value.endsWith(".json")) {
            value = value.substring(0, value.length() - 5);
        }
        return normalizeTitle(value);
    }

    private String documentNameFor(String title) {
        String value = normalizeTitle(title);
        if (TextUtils.isEmpty(value)) {
            value = getString(R.string.default_document_title);
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

    private String readString(Uri uri) throws Exception {
        return new String(readAll(uri), StandardCharsets.UTF_8);
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
        return getString(R.string.default_import_title);
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
            return getString(R.string.unknown_time);
        }
        return DateFormat.format("yyyy-MM-dd HH:mm:ss", millis).toString();
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private List<String> splitLines(String value) {
        ArrayList<String> result = new ArrayList<>();
        for (String line : value.split("\\R")) {
            String item = line.trim();
            if (!item.isEmpty() && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private String joinLines(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private int parseInt(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText() == null ? "" : input.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int indexOf(String[] values, String selected) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) {
                return i;
            }
        }
        return 0;
    }

    private boolean isDarkMode() {
        int uiMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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

    private TextView errorTextView(Throwable throwable) {
        TextView view = text(throwable.getClass().getSimpleName() + ": " + throwable.getMessage(), 14, R.color.danger, false);
        view.setPadding(0, dp(12), 0, dp(12));
        view.setTextIsSelectable(true);
        view.setSingleLine(false);
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
        params.setMargins(0, dp(8), 0, dp(8));
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
            clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
            toast(getString(R.string.toast_copied));
        }
    }

    private void showError(Throwable throwable) {
        throwable.printStackTrace();
        toast(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final class DesignGridView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private LauncherLayoutOrganizer.LayoutPlan plan;
        private DesignGridListener listener;
        private LauncherLayoutOrganizer.PlanItem downItem;
        private LauncherLayoutOrganizer.PlanItem selectedItem;
        private float downX;
        private float downY;
        private int currentScreen;
        private long lastDragPageFlipAt;
        private boolean longPressed;
        private static final long DRAG_PAGE_FLIP_COOLDOWN_MS = 650L;
        private final Runnable longPressRunnable = () -> {
            if (downItem != null) {
                selectedItem = downItem;
                longPressed = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
            }
        };

        DesignGridView(Context context) {
            super(context);
            setMinimumHeight(dp(260));
        }

        void setListener(DesignGridListener listener) {
            this.listener = listener;
        }

        void setPlan(LauncherLayoutOrganizer.LayoutPlan plan) {
            this.plan = plan;
            if (plan == null) {
                currentScreen = 0;
                selectedItem = null;
            } else {
                currentScreen = Math.max(0, Math.min(currentScreen, plan.screenCount - 1));
                if (selectedItem != null && selectedItem.screen != currentScreen) {
                    selectedItem = null;
                }
            }
            requestLayout();
            invalidate();
        }

        LauncherLayoutOrganizer.LayoutPlan currentPlan() {
            return plan;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height;
            if (plan == null) {
                height = dp(260);
            } else {
                float cell = cellSize(width);
                height = Math.round(dp(66) + cell * plan.gridHeight);
            }
            setMeasuredDimension(width, Math.max(dp(240), height));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (plan == null) {
                paint.setColor(color(R.color.surface_container_low));
                rect.set(dp(2), dp(8), getWidth() - dp(2), getHeight() - dp(8));
                canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                paint.setColor(color(R.color.on_surface_variant));
                paint.setTextSize(dp(14));
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                drawCenteredText(canvas, getString(R.string.design_no_selection), rect);
                return;
            }

            float cell = cellSize(getWidth());
            float gridWidth = cell * plan.gridWidth;
            float left = (getWidth() - gridWidth) / 2f;
            float top = dp(20);
            paint.setColor(color(R.color.on_surface_variant));
            paint.setTextSize(dp(12));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(getString(R.string.screen_label, currentScreen + 1) + " / " + plan.screenCount, left, top, paint);
            float gridTop = top + dp(12);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(R.color.surface_container_low));
            rect.set(left, gridTop, left + gridWidth, gridTop + cell * plan.gridHeight);
            canvas.drawRoundRect(rect, dp(8), dp(8), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(color(R.color.outline));
            for (int x = 0; x <= plan.gridWidth; x++) {
                float lineX = left + x * cell;
                canvas.drawLine(lineX, gridTop, lineX, gridTop + cell * plan.gridHeight, paint);
            }
            for (int y = 0; y <= plan.gridHeight; y++) {
                float lineY = gridTop + y * cell;
                canvas.drawLine(left, lineY, left + gridWidth, lineY, paint);
            }
            paint.setStyle(Paint.Style.FILL);

            for (LauncherLayoutOrganizer.PlanItem item : plan.items) {
                if (item.screen == currentScreen && item.widget) {
                    drawPlanItem(canvas, item, left, gridTop, cell);
                }
            }
            for (LauncherLayoutOrganizer.PlanItem item : plan.items) {
                if (item.screen == currentScreen && !item.widget) {
                    drawPlanItem(canvas, item, left, gridTop, cell);
                }
            }

            paint.setStyle(Paint.Style.FILL);
        }

        private void drawPlanItem(Canvas canvas, LauncherLayoutOrganizer.PlanItem item, float left, float top, float cell) {
            float gap = dp(3);
            rect.set(
                    left + item.cellX * cell + gap,
                    top + item.cellY * cell + gap,
                    left + (item.cellX + item.spanX) * cell - gap,
                    top + (item.cellY + item.spanY) * cell - gap);
            paint.setColor(color(item.widget ? R.color.surface_container : R.color.primary_container));
            canvas.drawRoundRect(rect, dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.STROKE);
            boolean selected = selectedItem != null && selectedItem.id.equals(item.id);
            paint.setStrokeWidth(dp(selected ? 3 : (item.widget ? 1 : 2)));
            paint.setColor(color(selected ? R.color.danger : (item.widget ? R.color.outline : R.color.primary)));
            canvas.drawRoundRect(rect, dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(color(item.widget ? R.color.on_surface_variant : R.color.on_surface));
            paint.setTextSize(dp(item.spanX > 1 ? 12 : 10));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            String title = item.title;
            int maxChars = Math.max(3, item.spanX * 5);
            if (title.length() > maxChars) {
                title = title.substring(0, maxChars - 1) + "…";
            }
            canvas.drawText(title, rect.left + dp(6), rect.top + dp(16), paint);
            if (!item.widget) {
                paint.setTextSize(dp(10));
                paint.setTypeface(Typeface.DEFAULT);
                canvas.drawText(item.spanX + "x" + item.spanY + " · " + item.childCount, rect.left + dp(6), rect.top + dp(31), paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (plan == null || listener == null) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                selectedItem = null;
                downX = event.getX();
                downY = event.getY();
                downItem = itemAt(downX, downY);
                longPressed = false;
                lastDragPageFlipAt = 0L;
                handler.removeCallbacks(longPressRunnable);
                if (downItem != null) {
                    handler.postDelayed(longPressRunnable, 420);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (!longPressed && (dx > dp(10) || dy > dp(10))) {
                    handler.removeCallbacks(longPressRunnable);
                } else if (longPressed) {
                    maybeFlipDragPage(event);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                float rawDx = event.getX() - downX;
                float rawDy = event.getY() - downY;
                float dx = Math.abs(rawDx);
                float dy = Math.abs(rawDy);
                LauncherLayoutOrganizer.PlanItem item = selectedItem != null ? selectedItem : downItem;
                boolean moved = dx > dp(8) || dy > dp(8);
                downItem = null;
                if (item == null && dx > dp(42) && dx > dy * 1.2f) {
                    moveScreen(rawDx < 0 ? 1 : -1);
                    return true;
                }
                if (item == null) {
                    return true;
                }
                if (!longPressed && dx > dp(42) && dx > dy * 1.2f) {
                    moveScreen(rawDx < 0 ? 1 : -1);
                    return true;
                }
                int[] cell = longPressed
                        ? cellAtForDrag(event.getX(), event.getY(), item.spanX, item.spanY)
                        : cellAt(event.getX(), event.getY(), item.spanX, item.spanY);
                if (longPressed && moved && cell != null) {
                    selectedItem = null;
                    listener.onMove(item, cell[0], cell[1], cell[2], Math.max(dx, dy));
                } else if (longPressed) {
                    selectedItem = null;
                    invalidate();
                    listener.onOpen(item);
                } else {
                    selectedItem = null;
                    invalidate();
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(longPressRunnable);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                downItem = null;
                selectedItem = null;
                invalidate();
            }
            return true;
        }

        private LauncherLayoutOrganizer.PlanItem itemAt(float x, float y) {
            ArrayList<LauncherLayoutOrganizer.PlanItem> reversed = new ArrayList<>(plan.items);
            Collections.reverse(reversed);
            for (LauncherLayoutOrganizer.PlanItem item : reversed) {
                if (item.screen != currentScreen) {
                    continue;
                }
                RectF itemRect = itemRect(item);
                if (itemRect.contains(x, y)) {
                    return item;
                }
            }
            return null;
        }

        private RectF itemRect(LauncherLayoutOrganizer.PlanItem item) {
            float cell = cellSize(getWidth());
            float gridWidth = cell * plan.gridWidth;
            float left = (getWidth() - gridWidth) / 2f;
            float top = dp(20);
            float gridTop = top + dp(12);
            return new RectF(
                    left + item.cellX * cell,
                    gridTop + item.cellY * cell,
                    left + (item.cellX + item.spanX) * cell,
                    gridTop + (item.cellY + item.spanY) * cell);
        }

        private int[] cellAt(float x, float y, int spanX, int spanY) {
            float cell = cellSize(getWidth());
            float gridWidth = cell * plan.gridWidth;
            float left = (getWidth() - gridWidth) / 2f;
            float top = dp(20);
            float gridTop = top + dp(12);
            if (x >= left && x < left + gridWidth && y >= gridTop && y < gridTop + cell * plan.gridHeight) {
                int cellX = Math.max(0, Math.min(plan.gridWidth - spanX, (int) ((x - left) / cell)));
                int cellY = Math.max(0, Math.min(plan.gridHeight - spanY, (int) ((y - gridTop) / cell)));
                return new int[]{currentScreen, cellX, cellY};
            }
            return null;
        }

        private int[] cellAtForDrag(float x, float y, int spanX, int spanY) {
            float cell = cellSize(getWidth());
            float gridWidth = cell * plan.gridWidth;
            float left = (getWidth() - gridWidth) / 2f;
            float top = dp(20);
            float gridTop = top + dp(12);
            if (y >= gridTop && y < gridTop + cell * plan.gridHeight) {
                int maxX = Math.max(0, plan.gridWidth - spanX);
                int maxY = Math.max(0, plan.gridHeight - spanY);
                int cellX = Math.max(0, Math.min(maxX, (int) ((x - left) / cell)));
                int cellY = Math.max(0, Math.min(maxY, (int) ((y - gridTop) / cell)));
                return new int[]{currentScreen, cellX, cellY};
            }
            return null;
        }

        private void maybeFlipDragPage(MotionEvent event) {
            int delta = dragPageDelta(event.getX());
            if (delta == 0 || event.getEventTime() - lastDragPageFlipAt < DRAG_PAGE_FLIP_COOLDOWN_MS) {
                return;
            }
            if (moveScreen(delta, true)) {
                lastDragPageFlipAt = event.getEventTime();
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        }

        private int dragPageDelta(float x) {
            if (plan == null) {
                return 0;
            }
            float cell = cellSize(getWidth());
            float gridWidth = cell * plan.gridWidth;
            float left = (getWidth() - gridWidth) / 2f;
            float right = left + gridWidth;
            int outside = dp(8);
            if (x < left - outside) {
                return -1;
            }
            if (x > right + outside) {
                return 1;
            }
            return 0;
        }

        private boolean moveScreen(int delta) {
            return moveScreen(delta, false);
        }

        private boolean moveScreen(int delta, boolean keepSelection) {
            if (plan == null) {
                return false;
            }
            int next = Math.max(0, Math.min(plan.screenCount - 1, currentScreen + delta));
            if (next != currentScreen) {
                currentScreen = next;
                if (!keepSelection) {
                    selectedItem = null;
                }
                invalidate();
                return true;
            }
            return false;
        }

        private float cellSize(int width) {
            if (plan == null) {
                return dp(54);
            }
            return Math.max(dp(42), Math.min(dp(68), (width - dp(32)) / (float) plan.gridWidth));
        }

        private void drawCenteredText(Canvas canvas, String value, RectF bounds) {
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float x = bounds.centerX() - paint.measureText(value) / 2f;
            float y = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(value, x, y, paint);
        }
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
                    drawSwipeText(canvas, itemView, getString(R.string.action_more), itemView.getLeft() + dp(24), color(R.color.primary));
                } else {
                    canvas.drawRect(itemView.getRight() + dX, itemView.getTop(), itemView.getRight(), itemView.getBottom(), paint);
                    drawSwipeText(canvas, itemView, getString(R.string.action_more), itemView.getRight() - dp(54), color(R.color.danger));
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

            ImageButton apply = actionIconButton(R.drawable.ic_play, getString(R.string.action_apply_layout), null);
            row.addView(apply, squareLp(34));

            ImageButton more = new ImageButton(context);
            more.setImageResource(R.drawable.ic_more);
            more.setImageTintList(ColorStateList.valueOf(color(R.color.on_surface_variant)));
            more.setBackgroundColor(0x00000000);
            more.setPadding(dp(7), dp(7), dp(7), dp(7));
            more.setContentDescription(getString(R.string.action_more));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                more.setTooltipText(getString(R.string.action_more));
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
            holder.meta.setText(layoutMeta(entry));
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

    private final class RuleAdapter extends RecyclerView.Adapter<RuleViewHolder> {
        private final RuleClickListener listener;
        private final ArrayList<RuleItem> data = new ArrayList<>();

        RuleAdapter(RuleClickListener listener) {
            this.listener = listener;
            setHasStableIds(true);
        }

        void submit(List<RuleItem> rules) {
            data.clear();
            data.addAll(rules);
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            return data.get(position).name.hashCode();
        }

        @Override
        public RuleViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = card();
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(match(), wrap());
            params.setMargins(0, 0, 0, dp(10));
            card.setLayoutParams(params);
            card.setClickable(true);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));

            LinearLayout textBlock = new LinearLayout(MainActivity.this);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            TextView title = text("", 15, R.color.on_surface, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            textBlock.addView(title, lp(match(), wrap()));
            TextView meta = text("", 12, R.color.on_surface_variant, false);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setPadding(0, dp(3), 0, 0);
            textBlock.addView(meta, lp(match(), wrap()));
            row.addView(textBlock, new LinearLayout.LayoutParams(0, wrap(), 1f));

            ImageButton more = new ImageButton(MainActivity.this);
            more.setImageResource(R.drawable.ic_more);
            more.setImageTintList(ColorStateList.valueOf(color(R.color.on_surface_variant)));
            more.setBackgroundColor(0x00000000);
            more.setContentDescription(getString(R.string.action_more));
            row.addView(more, squareLp(34));
            card.addView(row);
            return new RuleViewHolder(card, title, meta, more);
        }

        @Override
        public void onBindViewHolder(RuleViewHolder holder, int position) {
            RuleItem rule = data.get(position);
            holder.title.setText(rule.name);
            holder.meta.setText(ruleMeta(rule));
            holder.itemView.setOnClickListener(v -> listener.onRuleClick(rule, holder.getAdapterPosition()));
            holder.more.setOnClickListener(v -> listener.onRuleClick(rule, holder.getAdapterPosition()));
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

    private static final class RuleViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;
        final ImageButton more;

        RuleViewHolder(MaterialCardView card, TextView title, TextView meta, ImageButton more) {
            super(card);
            this.title = title;
            this.meta = meta;
            this.more = more;
        }
    }

    private interface LayoutActions {
        void open(LayoutEntry entry);

        void apply(LayoutEntry entry);

        void select(LayoutEntry entry);
    }

    private interface RuleClickListener {
        void onRuleClick(RuleItem rule, int position);
    }

    private interface DesignGridListener {
        void onMove(LauncherLayoutOrganizer.PlanItem item, int screen, int cellX, int cellY, float dragForce);

        void onOpen(LauncherLayoutOrganizer.PlanItem item);
    }

    private static final class DragPlacementResult {
        boolean changed;
        boolean rulesChanged;

        static DragPlacementResult unchanged() {
            return new DragPlacementResult();
        }
    }

    private static final class DesignPlacement {
        final String id;
        final LauncherLayoutOrganizer.PlanItem item;
        final int spanX;
        final int spanY;
        int screen;
        int cellX;
        int cellY;

        DesignPlacement(LauncherLayoutOrganizer.PlanItem item, int screen, int cellX, int cellY, int spanX, int spanY) {
            this.id = item.id;
            this.item = item;
            this.screen = Math.max(0, screen);
            this.cellX = Math.max(0, cellX);
            this.cellY = Math.max(0, cellY);
            this.spanX = Math.max(1, spanX);
            this.spanY = Math.max(1, spanY);
        }

        DesignPlacement copy() {
            return new DesignPlacement(item, screen, cellX, cellY, spanX, spanY);
        }

        int area() {
            return spanX * spanY;
        }
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
