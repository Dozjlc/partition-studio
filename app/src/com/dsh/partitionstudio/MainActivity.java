package com.dsh.partitionstudio;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.animation.ValueAnimator;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_FLASH_IMAGE = 4101;
    private static final int REQ_VIEW_IMAGE = 4102;
    private static final String BACKUP_DIR = "/sdcard/Download/PartitionImages";
    private static final String BUNDLE_PREFIX = "bs_gs_";
    private static final long MIN_FREE_FOR_BUNDLE = 64L * 1024 * 1024;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Partition> partitions = new ArrayList<>();
    private String query = "";

    private LinearLayout listContainer;
    private EditText search;
    private TextView rootChip;
    private TextView statusHint;
    private TextView countBadge;
    private TextView heroCount;
    private TextView heroTotal;
    private TextView heroModel;
    private TextView heroMeta;
    private ImageButton refreshButton;

    private volatile boolean busy = false;
    private boolean rootGranted = false;
    private String deviceModel = "";
    private String androidVersion = "";
    private Partition pendingFlashTarget;
    private Dialog activeDialog;

    private static final int TAB_HOME = 0;
    private static final int TAB_BACKUP = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_SPONSOR = 3;
    private static final int[] ACCENT_CHOICES = {
            0,
            0xFF4A78D0, 0xFF7A68C9, 0xFFC04A78, 0xFFC15B4E, 0xFFB8782D,
            0xFF8C8A2E, 0xFF3E8A5B, 0xFF3C8A86, 0xFF5B7385, 0xFF7A7A7A
    };
    private int currentTab = TAB_HOME;
    private int restoredTab = TAB_HOME;
    private FrameLayout pageHost;
    private View homePage;
    private View backupPage;
    private View settingsPage;
    private View sponsorPage;
    private LinearLayout bottomNav;
    private final List<LinearLayout> navItems = new ArrayList<>();
    private final List<ImageView> navIcons = new ArrayList<>();
    private final List<TextView> navLabels = new ArrayList<>();
    private TextView slotChip;
    private String currentSlotText = "";
    private boolean tabsInitialized = false;
    private boolean listEntrancePlayed = false;

    private interface ByteProgress {
        void onProgress(long done, long total);
    }

    private interface BackupDone {
        void done(BackupOutcome outcome);
    }

    private interface BundleProgress {
        void onProgress(int percent, String stage, String detail);
    }

    private static final class BackupOutcome {
        final boolean ok;
        final String path;
        final String message;
        final long size;

        BackupOutcome(boolean ok, String path, String message, long size) {
            this.ok = ok;
            this.path = path;
            this.message = message;
            this.size = size;
        }
    }

    private static final class FlashOutcome {
        final boolean ok;
        final String message;

        FlashOutcome(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static final class BundleOutcome {
        final boolean ok;
        final String path;
        final long zipSize;
        final int count;
        final String message;

        BundleOutcome(boolean ok, String path, long zipSize, int count, String message) {
            this.ok = ok;
            this.path = path;
            this.zipSize = zipSize;
            this.count = count;
            this.message = message;
        }
    }

    private static final class ImageInfo {
        final Uri uri;
        final String name;
        final long size;
        final byte[] header;

        ImageInfo(Uri uri, String name, long size, byte[] header) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.header = header;
        }

        String type() {
            return Ui.guessType(header);
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = newBase.getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
        int mode = prefs.getInt(Ui.KEY_THEME, 0);
        if (mode == 1 || mode == 2) {
            Configuration config = new Configuration(newBase.getResources().getConfiguration());
            config.uiMode &= ~Configuration.UI_MODE_NIGHT_MASK;
            config.uiMode |= mode == 2 ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            newBase = newBase.createConfigurationContext(config);
        }
        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        restoredTab = savedInstanceState == null ? TAB_HOME
                : savedInstanceState.getInt("current_tab", TAB_HOME);
        if (!Integrity.verify(this)) {
            setContentView(buildIntegrityError());
            return;
        }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        int systemUi = 0;
        if (!Ui.DARK) {
            systemUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        setContentView(buildUi());
        loadPartitions();
        main.postDelayed(() -> {
            if (!isFinishing() && !Integrity.verify(this)) {
                setContentView(buildIntegrityError());
            }
        }, 1800);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("current_tab", currentTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            Ui.toast(this, "操作进行中，请等待完成");
            return;
        }
        if (currentTab != TAB_HOME) {
            setTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    private View buildIntegrityError() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        Ui.pad(box, 28, 28, 28, 28, this);
        TextView mark = Ui.text(this, "!", 22f, Ui.RED, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.solid(Ui.CARD_2, 50, this));
        box.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView title = Ui.text(this, "无法验证应用完整性", 22f, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        addTop(box, title, 18);
        TextView body = Ui.text(this,
                "安装包签名与官方版本不一致。为防止被篡改，已停止运行。\n\n请从作者处重新获取官方安装包。\n作者 QQ：2895150520",
                12f, Ui.TEXT_DIM, false);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(Ui.dp(this, 2), 1.15f);
        addTop(box, body, 10);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        lp.leftMargin = Ui.dp(this, 24);
        lp.rightMargin = Ui.dp(this, 24);
        root.addView(box, lp);
        return root;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        pageHost = new FrameLayout(this);
        root.addView(pageHost, new FrameLayout.LayoutParams(MATCH, MATCH));

        homePage = buildHomePage();
        backupPage = buildBackupPage();
        settingsPage = buildSettingsPage();
        sponsorPage = buildSponsorPage();
        pageHost.addView(homePage, new FrameLayout.LayoutParams(MATCH, MATCH));
        pageHost.addView(backupPage, new FrameLayout.LayoutParams(MATCH, MATCH));
        pageHost.addView(settingsPage, new FrameLayout.LayoutParams(MATCH, MATCH));
        pageHost.addView(sponsorPage, new FrameLayout.LayoutParams(MATCH, MATCH));

        bottomNav = buildBottomNav();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        if (prefs().getBoolean(Ui.KEY_FLOATING, true)) {
            navLp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12));
        }
        root.addView(bottomNav, navLp);
        for (View page : new View[]{homePage, backupPage, settingsPage, sponsorPage}) {
            if (page instanceof ScrollView) {
                ((ScrollView) page).setOnScrollChangeListener((v, sx, sy, oldSx, oldSy) -> {
                    if (bottomNav instanceof GlassBar) ((GlassBar) bottomNav).requestCapture();
                });
            }
        }
        setTab(restoredTab);
        return root;
    }

    private View buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(content, 20, 18, 20, 116, this);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "分区镜像", 27f, Ui.TEXT, true);
        TextView sub = Ui.text(this, "读取、备份与写入手机分区", 11.5f, Ui.TEXT_DIM, false);
        titles.addView(title);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        subLp.topMargin = Ui.dp(this, 4);
        titles.addView(sub, subLp);
        header.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageButton viewBtn = Ui.iconButton(this, R.drawable.ic_folder, "查看镜像文件", v -> pickImage(REQ_VIEW_IMAGE));
        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        vbLp.leftMargin = Ui.dp(this, 8);
        header.addView(viewBtn, vbLp);

        refreshButton = Ui.iconButton(this, R.drawable.ic_refresh, "刷新分区", v -> loadPartitions());
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        rbLp.leftMargin = Ui.dp(this, 8);
        header.addView(refreshButton, rbLp);
        addTop(content, header, 0);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        rootChip = Ui.pill(this, "Root 检测中", Ui.TEXT_DIM, Ui.CARD, Ui.STROKE);
        rootChip.setClickable(true);
        rootChip.setFocusable(true);
        rootChip.setOnClickListener(v -> loadPartitions());
        statusRow.addView(rootChip);
        statusHint = Ui.text(this, "正在请求超级用户权限…", 11.5f, Ui.TEXT_FAINT, false);
        statusHint.setSingleLine(true);
        statusHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        hintLp.leftMargin = Ui.dp(this, 10);
        statusRow.addView(statusHint, hintLp);
        addTop(content, statusRow, 18);

        LinearLayout overview = new LinearLayout(this);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setBackground(Ui.outline(this, Ui.CARD, 22, Ui.STROKE, 1));
        Ui.pad(overview, 18, 17, 18, 17, this);
        LinearLayout overviewTop = new LinearLayout(this);
        overviewTop.setOrientation(LinearLayout.HORIZONTAL);
        overviewTop.setGravity(Gravity.CENTER_VERTICAL);
        overviewTop.addView(Ui.label(this, "设备概览", Ui.TEXT_FAINT));
        overview.addView(overviewTop, new LinearLayout.LayoutParams(MATCH, WRAP));
        heroModel = Ui.text(this, "正在读取设备信息", 17f, Ui.TEXT, true);
        heroModel.setSingleLine(true);
        heroModel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addTop(overview, heroModel, 7);
        heroMeta = Ui.text(this, "正在读取分区表", 10.5f, Ui.TEXT_FAINT, false);
        heroMeta.setTypeface(Ui.mono());
        heroMeta.setSingleLine(true);
        heroMeta.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        addTop(overview, heroMeta, 5);

        LinearLayout slotRow = new LinearLayout(this);
        slotRow.setOrientation(LinearLayout.HORIZONTAL);
        slotRow.setGravity(Gravity.CENTER_VERTICAL);
        slotRow.setBackground(Ui.solid(Ui.CARD_2, 12, this));
        Ui.pad(slotRow, 12, 8, 10, 8, this);
        TextView slotLabel = Ui.text(this, "当前槽位", 11.5f, Ui.TEXT_DIM, true);
        slotRow.addView(slotLabel, new LinearLayout.LayoutParams(0, WRAP, 1f));
        slotChip = Ui.pill(this, "读取中…", Ui.TEXT_FAINT, 0x00000000, 0);
        slotChip.setTextSize(12f);
        slotRow.addView(slotChip);
        addTop(overview, slotRow, 12);

        View overviewDivider = new View(this);
        overviewDivider.setBackgroundColor(Ui.STROKE_SOFT);
        LinearLayout.LayoutParams odLp = new LinearLayout.LayoutParams(MATCH, Ui.dp(this, 1));
        odLp.topMargin = Ui.dp(this, 14);
        overview.addView(overviewDivider, odLp);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout c1 = new LinearLayout(this);
        c1.setOrientation(LinearLayout.VERTICAL);
        c1.addView(Ui.label(this, "分区数量", Ui.TEXT_FAINT));
        heroCount = Ui.text(this, "—", 26f, Ui.TEXT, true);
        addTop(c1, heroCount, 4);
        metrics.addView(c1, new LinearLayout.LayoutParams(0, WRAP, 1f));

        View vDiv = new View(this);
        vDiv.setBackgroundColor(Ui.STROKE_SOFT);
        LinearLayout.LayoutParams vdLp = new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 32));
        vdLp.leftMargin = Ui.dp(this, 14);
        vdLp.rightMargin = Ui.dp(this, 20);
        metrics.addView(vDiv, vdLp);

        LinearLayout c2 = new LinearLayout(this);
        c2.setOrientation(LinearLayout.VERTICAL);
        c2.addView(Ui.label(this, "总容量", Ui.TEXT_FAINT));
        heroTotal = Ui.text(this, "—", 18f, Ui.TEXT, true);
        addTop(c2, heroTotal, 8);
        metrics.addView(c2, new LinearLayout.LayoutParams(0, WRAP, 1.1f));
        addTop(overview, metrics, 13);
        addTop(content, overview, 14);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.solid(Ui.CARD_2, 14, this));
        Ui.pad(searchBox, 14, 10, 14, 10, this);
        android.widget.ImageView searchIcon = new android.widget.ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setImageTintList(ColorStateList.valueOf(Ui.TEXT_DIM));
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        search = new EditText(this);
        search.setBackground(null);
        search.setHint("搜索分区或路径");
        search.setHintTextColor(Ui.TEXT_FAINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14f);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        searchLp.leftMargin = Ui.dp(this, 10);
        searchBox.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                renderFiltered();
            }
        });
        addTop(content, searchBox, 16);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = Ui.text(this, "分区", 16f, Ui.TEXT, true);
        section.addView(sectionTitle);
        countBadge = Ui.text(this, "0 个", 11.5f, Ui.TEXT_FAINT, false);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        cbLp.leftMargin = Ui.dp(this, 8);
        section.addView(countBadge, cbLp);
        addTop(content, section, 24);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        addTop(content, listContainer, 8);

        LinearLayout warning = new LinearLayout(this);
        warning.setOrientation(LinearLayout.HORIZONTAL);
        warning.setGravity(Gravity.TOP);
        Ui.pad(warning, 2, 0, 2, 0, this);
        TextView mark = Ui.text(this, "!", 10f, Ui.AMBER, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.outline(this, Ui.AMBER_SOFT, 50, 0, 1));
        warning.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19)));
        TextView warnText = Ui.text(this,
                "写入分区可能导致设备无法开机。请确认镜像与机型、分区完全匹配，并务必先备份。",
                11f, Ui.TEXT_DIM, false);
        warnText.setLineSpacing(Ui.dp(this, 2), 1.1f);
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        wtLp.leftMargin = Ui.dp(this, 9);
        warning.addView(warnText, wtLp);
        addTop(content, warning, 22);

        TextView version = Ui.text(this, "v1.8  ·  本地构建", 10f, Ui.TEXT_FAINT, false);
        version.setGravity(Gravity.CENTER);
        version.setLetterSpacing(0.06f);
        addTop(content, version, 22);

        return scroll;
    }

    private void addTop(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp;
        ViewGroup.LayoutParams existing = child.getLayoutParams();
        if (existing instanceof LinearLayout.LayoutParams) {
            lp = (LinearLayout.LayoutParams) existing;
        } else {
            lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        }
        lp.topMargin = Ui.dp(this, topDp);
        parent.addView(child, lp);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Ui.PREFS, MODE_PRIVATE);
    }

    private LinearLayout buildBottomNav() {
        boolean floating = prefs().getBoolean(Ui.KEY_FLOATING, true);
        boolean glass = prefs().getBoolean(Ui.KEY_GLASS, true);
        GlassBar nav = new GlassBar(this, floating && glass, pageHost);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        if (floating && glass) {
            nav.setBackground(null);
        } else if (floating) {
            nav.setBackground(Ui.outline(this, Ui.CARD, 26, Ui.STROKE, 1));
        } else {
            nav.setBackground(Ui.solid(Ui.CARD, 0, this));
        }
        nav.setElevation(Ui.dp(this, floating ? 18 : 8));
        Ui.pad(nav, 7, 7, 7, 7, this);

        String[] labels = {"分区", "备份", "设置", "赞助"};
        int[] icons = {R.drawable.ic_partitions, R.drawable.ic_backup,
                R.drawable.ic_settings, R.drawable.ic_heart};
        navItems.clear();
        navIcons.clear();
        navLabels.clear();
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setPadding(Ui.dp(this, 6), Ui.dp(this, 6),
                    Ui.dp(this, 6), Ui.dp(this, 6));

            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(Ui.TEXT_FAINT));
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21)));

            TextView label = Ui.text(this, labels[i], 9.5f, Ui.TEXT_FAINT, true);
            label.setGravity(Gravity.CENTER);
            label.setLetterSpacing(0.02f);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(WRAP, WRAP);
            labelLp.topMargin = Ui.dp(this, 3);
            item.addView(label, labelLp);

            item.setOnClickListener(v -> setTab(tab));
            nav.addView(item, new LinearLayout.LayoutParams(0,
                    Ui.dp(this, 54), 1f));
            navItems.add(item);
            navIcons.add(icon);
            navLabels.add(label);
        }
        return nav;
    }

    private void setTab(int tab) {
        if (tab < TAB_HOME || tab > TAB_SPONSOR) tab = TAB_HOME;
        boolean first = !tabsInitialized;
        if (!first && tab == currentTab) return;
        currentTab = tab;
        tabsInitialized = true;
        View[] pages = {homePage, backupPage, settingsPage, sponsorPage};
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null && i != tab) pages[i].setVisibility(View.GONE);
        }
        View next = pages[tab];
        if (next != null) {
            next.animate().cancel();
            next.setVisibility(View.VISIBLE);
            next.setAlpha(0f);
            next.setTranslationY(Ui.dp(this, first ? 18 : 12));
            next.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(first ? 360 : 260)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            next.postDelayed(() -> {
                if (next.getAlpha() < 0.99f) {
                    next.setAlpha(1f);
                    next.setTranslationY(0f);
                }
            }, first ? 420 : 320);
        }
        boolean glassNav = bottomNav instanceof GlassBar;
        for (int i = 0; i < navItems.size(); i++) {
            boolean selected = i == tab;
            LinearLayout item = navItems.get(i);
            android.graphics.drawable.Drawable bg;
            if (glassNav) {
                bg = Ui.solid(0x00000000, 16, this);
            } else {
                bg = selected
                        ? Ui.outline(this, Ui.ACCENT_SOFT, 16, Ui.withAlpha(Ui.ACCENT, 0x55), 1)
                        : Ui.solid(0x00000000, 16, this);
            }
            item.setBackground(Ui.ripple(this, bg, Ui.DARK ? 0x26FFFFFF : 0x12000000));
            navIcons.get(i).setImageTintList(ColorStateList.valueOf(
                    selected ? Ui.ACCENT : Ui.TEXT_FAINT));
            navLabels.get(i).setTextColor(selected ? Ui.TEXT : Ui.TEXT_FAINT);

            ImageView icon = navIcons.get(i);
            icon.animate().cancel();
            icon.animate()
                    .scaleX(selected ? 1.14f : 1f)
                    .scaleY(selected ? 1.14f : 1f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.6f))
                    .start();
            item.animate().cancel();
            item.animate()
                    .scaleX(selected ? 1.02f : 1f)
                    .scaleY(selected ? 1.02f : 1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        if (bottomNav instanceof GlassBar) {
            GlassBar glassBar = (GlassBar) bottomNav;
            glassBar.setSelectedTab(tab, !first);
            glassBar.requestCapture();
            glassBar.postDelayed(glassBar::requestCapture, 380);
        }
    }

    private LinearLayout pageHeader(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, title, 27f, Ui.TEXT, true));
        TextView sub = Ui.text(this, subtitle, 11.5f, Ui.TEXT_DIM, false);
        addTop(box, sub, 4);
        return box;
    }

    private LinearLayout pageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.outline(this, Ui.CARD, 22, Ui.STROKE, 1));
        Ui.pad(card, 18, 18, 18, 17, this);
        return card;
    }

    private View buildBackupPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(content, 20, 18, 20, 116, this);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        content.addView(pageHeader("备份", "ZIP 归档与单分区镜像"));

        LinearLayout main = pageCard();
        main.addView(Ui.label(this, "一键备份", Ui.TEXT_FAINT));
        TextView title = Ui.text(this, "打包为 ZIP", 19f, Ui.TEXT, true);
        addTop(main, title, 7);
        TextView body = Ui.text(this,
                "把当前分区打包为一个 ZIP 压缩包，文件名以 bs_gs_ 开头。默认不包含数据分区，可在弹层中勾选。",
                12f, Ui.TEXT_DIM, false);
        body.setLineSpacing(Ui.dp(this, 2), 1.1f);
        addTop(main, body, 8);
        TextView start = Ui.button(this, "开始打包", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        start.setOnClickListener(v -> openBundleBackup());
        addTop(main, start, 15);
        addTop(content, main, 20);

        LinearLayout custom = pageCard();
        custom.addView(Ui.label(this, "自定义备份", Ui.TEXT_FAINT));
        TextView customTitle = Ui.text(this, "自选分区打包", 17f, Ui.TEXT, true);
        addTop(custom, customTitle, 7);
        TextView customBody = Ui.text(this,
                "只勾选需要的分区，打包成一个 ZIP。适合只备份 boot、系统分区等场景。",
                11.5f, Ui.TEXT_DIM, false);
        customBody.setLineSpacing(Ui.dp(this, 2), 1.1f);
        addTop(custom, customBody, 7);
        TextView pick = Ui.button(this, "选择分区", Ui.CARD_2, Ui.TEXT, Ui.STROKE);
        pick.setOnClickListener(v -> openCustomBackup());
        addTop(custom, pick, 13);
        addTop(content, custom, 14);

        LinearLayout where = pageCard();
        where.addView(Ui.label(this, "保存位置", Ui.TEXT_FAINT));
        TextView path = codeBlock(BACKUP_DIR);
        addTop(where, path, 8);
        TextView pathNote = Ui.text(this,
                "ZIP 与单分区 .img 都会保存在这里。备份期间请保持应用在前台。",
                10.5f, Ui.TEXT_FAINT, false);
        pathNote.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(where, pathNote, 9);
        addTop(content, where, 14);

        LinearLayout single = pageCard();
        single.addView(Ui.label(this, "单分区备份", Ui.TEXT_FAINT));
        TextView singleBody = Ui.text(this,
                "在分区列表中点任意分区，可只备份该分区，也可以直接替换它的镜像。",
                12f, Ui.TEXT_DIM, false);
        singleBody.setLineSpacing(Ui.dp(this, 2), 1.1f);
        addTop(single, singleBody, 8);
        TextView go = Ui.button(this, "返回分区列表", Ui.CARD_2, Ui.TEXT, Ui.STROKE);
        go.setOnClickListener(v -> setTab(TAB_HOME));
        addTop(single, go, 14);
        addTop(content, single, 14);

        return scroll;
    }

    private void recreateWithFade() {
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        recreate();
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(content, 20, 18, 20, 116, this);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        content.addView(pageHeader("设置", "外观、主题与行为"));

        int mode = prefs().getInt(Ui.KEY_THEME, 0);
        LinearLayout display = pageCard();
        display.addView(Ui.text(this, "显示模式", 16f, Ui.TEXT, true));
        TextView displayDesc = Ui.text(this, "选择应用外观，切换后立即生效。", 11f, Ui.TEXT_DIM, false);
        addTop(display, displayDesc, 5);
        String[] modeLabels = {"跟随系统", "浅色", "深色"};
        for (int i = 0; i < modeLabels.length; i++) {
            addTop(display, themeOption(modeLabels[i], i, mode), 8);
        }
        addTop(content, display, 20);

        LinearLayout accentCard = pageCard();
        LinearLayout accentTitle = new LinearLayout(this);
        accentTitle.setOrientation(LinearLayout.HORIZONTAL);
        accentTitle.setGravity(Gravity.CENTER_VERTICAL);
        accentTitle.addView(Ui.text(this, "强调色", 16f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView reset = Ui.text(this, "恢复默认", 10.5f, Ui.TEXT_FAINT, true);
        reset.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        reset.setOnClickListener(v -> {
            prefs().edit().putInt(Ui.KEY_ACCENT, 0).putBoolean(Ui.KEY_MONET, false).apply();
            recreateWithFade();
        });
        accentTitle.addView(reset);
        accentCard.addView(accentTitle);
        TextView accentDesc = Ui.text(this,
                "用于主要按钮、选中状态和图标。选择自定义颜色会自动关闭 Monet。",
                11f, Ui.TEXT_DIM, false);
        accentDesc.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(accentCard, accentDesc, 5);

        boolean monet = prefs().getBoolean(Ui.KEY_MONET, false);
        boolean monetSupported = android.os.Build.VERSION.SDK_INT >= 31;
        addTop(accentCard, toggleRow("启用 Monet",
                monetSupported ? "从系统壁纸取色，让按钮和图标融入主题。"
                        : "需要 Android 12 及以上系统。",
                monet, monetSupported, (buttonView, isChecked) -> {
                    prefs().edit().putBoolean(Ui.KEY_MONET, isChecked).apply();
                    recreateWithFade();
                }), 14);

        accentCard.addView(Ui.label(this, "自选颜色", Ui.TEXT_FAINT));
        int selectedAccent = prefs().getInt(Ui.KEY_ACCENT, 0);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < ACCENT_CHOICES.length; i++) {
            if (i % 6 == 0) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                grid.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            LinearLayout row = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
            int color = ACCENT_CHOICES[i];
            boolean selected = !monet && selectedAccent == color;
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            cell.setBackground(Ui.outline(this, 0x00000000, 50,
                    selected ? Ui.TEXT : Ui.STROKE, selected ? 1.5f : 1f));
            View dot = new View(this);
            dot.setBackground(Ui.solid(color == 0 ? Ui.TEXT : color, 50, this));
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 28), Ui.dp(this, 28), Gravity.CENTER);
            cell.addView(dot, dotLp);
            cell.setOnClickListener(v -> {
                prefs().edit().putInt(Ui.KEY_ACCENT, color).putBoolean(Ui.KEY_MONET, false).apply();
                recreateWithFade();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        }
        if (grid.getChildCount() > 0) {
            LinearLayout lastRow = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
            while (lastRow.getChildCount() < 6) {
                View blank = new View(this);
                lastRow.addView(blank, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
            }
        }
        addTop(accentCard, grid, 8);
        TextView accentNote = Ui.text(this,
                "第一个圆点为默认黑白配色。点按色点立即应用。",
                10f, Ui.TEXT_FAINT, false);
        addTop(accentCard, accentNote, 2);
        addTop(content, accentCard, 14);

        boolean floating = prefs().getBoolean(Ui.KEY_FLOATING, true);
        boolean glass = prefs().getBoolean(Ui.KEY_GLASS, true);
        LinearLayout effects = pageCard();
        effects.addView(Ui.text(this, "界面与动效", 16f, Ui.TEXT, true));
        TextView effectsDesc = Ui.text(this, "调整底部导航的呈现方式。", 11f, Ui.TEXT_DIM, false);
        addTop(effects, effectsDesc, 5);
        addTop(effects, toggleRow("悬浮底栏",
                "导航栏悬浮在内容上方，保留圆角、边距和阴影。",
                floating, true, (buttonView, isChecked) -> {
                    prefs().edit().putBoolean(Ui.KEY_FLOATING, isChecked).apply();
                    recreateWithFade();
                }), 14);
        addTop(effects, toggleRow("液态玻璃",
                "使用半透明材质和高光，让底栏有玻璃质感。",
                glass, floating, (buttonView, isChecked) -> {
                    prefs().edit().putBoolean(Ui.KEY_GLASS, isChecked).apply();
                    recreateWithFade();
                }), 10);
        addTop(content, effects, 14);

        LinearLayout about = pageCard();
        about.addView(Ui.text(this, "关于", 16f, Ui.TEXT, true));
        addTop(about, settingRow("版本", "v1.8", false, null), 8);
        about.addView(hairline());
        addTop(about, settingRow("作者", "Dozjlc.", false, null), 0);
        about.addView(hairline());
        addTop(about, settingRow("作者 QQ", "2895150520", false, null), 0);
        about.addView(hairline());
        addTop(about, settingRow("赞助作者", "", true, v -> setTab(TAB_SPONSOR)), 0);
        TextView aboutNote = Ui.text(this,
                "所有分区操作均在本机通过 Root 完成，不上传任何数据。",
                10f, Ui.TEXT_FAINT, false);
        aboutNote.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(about, aboutNote, 10);
        addTop(content, about, 14);

        return scroll;
    }

    private TextView themeOption(String label, int option, int current) {
        boolean selected = option == current;
        TextView tv = Ui.text(this, label, 13.5f, selected ? Ui.ACCENT_DARK : Ui.TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        tv.setBackground(selected
                ? Ui.ripple(this, Ui.solid(Ui.ACCENT, 18, this),
                        Ui.DARK ? 0x33FFFFFF : 0x22000000)
                : Ui.ripple(this, Ui.solid(Ui.CARD_2, 18, this),
                        Ui.DARK ? 0x1FFFFFFF : 0x10000000));
        tv.setClickable(true);
        tv.setFocusable(true);
        if (!selected) {
            tv.setOnClickListener(v -> {
                prefs().edit().putInt(Ui.KEY_THEME, option).apply();
                recreateWithFade();
            });
        }
        return tv;
    }

    private LinearLayout toggleRow(String title, String desc, boolean checked,
                                   boolean enabled, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, title, 13.5f, Ui.TEXT, true));
        TextView d = Ui.text(this, desc, 10.5f, Ui.TEXT_FAINT, false);
        d.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(col, d, 4);
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setEnabled(enabled);
        sw.setShowText(false);
        sw.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        sw.setTrackTintList(ColorStateList.valueOf(Ui.withAlpha(Ui.ACCENT, 0x66)));
        row.addView(sw, new LinearLayout.LayoutParams(WRAP, WRAP));
        if (!enabled) row.setAlpha(0.45f);
        sw.setOnCheckedChangeListener(listener);
        return row;
    }

    private LinearLayout settingRow(String label, String value, boolean chevron, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 0, 12, 0, 12, this);
        row.addView(Ui.text(this, label, 13f, Ui.TEXT, false),
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (value != null && !value.isEmpty()) {
            row.addView(Ui.text(this, value, 11.5f, Ui.TEXT_FAINT, false));
        }
        if (chevron) {
            TextView arrow = Ui.text(this, "›", 18f, Ui.TEXT_FAINT, false);
            arrow.setPadding(Ui.dp(this, 8), 0, 0, 0);
            row.addView(arrow);
        }
        if (listener != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(Ui.ripple(this, Ui.solid(0x00000000, 12, this),
                    Ui.DARK ? 0x1FFFFFFF : 0x10000000));
            row.setOnClickListener(listener);
        }
        return row;
    }

    private Bitmap loadSponsorQr() {
        try (java.io.InputStream in = getAssets().open("d.bin");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            byte[] key = {0x5D, 0x13, (byte) 0xA7, 0x3C, (byte) 0xE1, 0x44,
                    (byte) 0x9B, 0x2F, 0x6A, (byte) 0xD5, 0x08, (byte) 0xBC,
                    0x71, 0x23, (byte) 0x8E, (byte) 0xF0};
            for (int i = 0; i < data.length; i++) data[i] ^= key[i % key.length];
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return null;
        }
    }

    private View buildSponsorPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(content, 20, 18, 20, 116, this);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        content.addView(pageHeader("赞助作者", "如果这个工具帮到了你"));

        LinearLayout card = pageCard();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout qrBox = new FrameLayout(this);
        int qrSize = Math.min(Ui.dp(this, 300),
                getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 96));
        qrBox.setBackground(Ui.solid(0xFFFFFFFF, 22, this));
        qrBox.setClipToOutline(true);
        Ui.pad(qrBox, 8, 8, 8, 8, this);
        ImageView qr = new ImageView(this);
        Bitmap qrBitmap = loadSponsorQr();
        if (qrBitmap != null) qr.setImageBitmap(qrBitmap);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrBox.addView(qr, new FrameLayout.LayoutParams(MATCH, qrSize));
        card.addView(qrBox, new LinearLayout.LayoutParams(qrSize + Ui.dp(this, 16),
                qrSize + Ui.dp(this, 16)));
        TextView caption = Ui.text(this, "Dozjlc. 的赞赏码", 17f, Ui.TEXT, true);
        caption.setGravity(Gravity.CENTER);
        addTop(card, caption, 14);
        TextView captionSub = Ui.text(this, "微信扫码 · 赞助完全自愿", 11f, Ui.TEXT_DIM, false);
        captionSub.setGravity(Gravity.CENTER);
        addTop(card, captionSub, 5);
        addTop(content, card, 20);

        LinearLayout note = pageCard();
        TextView thanks = Ui.text(this,
                "感谢你支持独立开发。赞助不会解锁额外功能，也不会影响分区工具的任何行为。",
                12f, Ui.TEXT_DIM, false);
        thanks.setLineSpacing(Ui.dp(this, 2), 1.1f);
        note.addView(thanks);
        addTop(content, note, 14);

        return scroll;
    }

    private boolean guardIntegrity() {
        if (Integrity.verify(this)) return true;
        showError("完整性校验失败",
                "应用代码或资源与官方版本不一致，已停止操作。\n\n请从作者处重新获取安装包。");
        return false;
    }

    private void loadPartitions() {
        if (busy) return;
        if (!guardIntegrity()) return;
        busy = true;
        rootGranted = false;
        setRootChip("Root 检测中", Ui.TEXT_DIM);
        if (slotChip != null && (currentSlotText == null || currentSlotText.isEmpty())) {
            setSlotChip("读取中…", false);
        }
        loadSlotFromSystem();
        statusHint.setText("正在请求超级用户权限…");
        heroModel.setText("正在读取设备信息");
        heroMeta.setText("/dev/block/by-name");
        heroCount.setText("—");
        heroTotal.setText("—");
        listContainer.removeAllViews();
        showLoadingCard();

        io.execute(() -> {
            boolean rooted = false;
            String uid = "";
            List<Partition> found = new ArrayList<>();
            String model = "";
            String version = "";
            String slotText = "";
            String suffix = "";
            String slotProp = "";
            String error = null;
            String raw = "";
            try {
                RootShell.Result r = RootShell.run(partitionListScript());
                raw = r.output;
                Matcher uidMatcher = Pattern.compile("__UID__:(\\d+)").matcher(raw);
                if (uidMatcher.find()) uid = uidMatcher.group(1);
                rooted = "0".equals(uid);
                for (String line : raw.split("\n")) {
                    if (line.startsWith("__P__|")) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length >= 4) {
                            String name = parts[1].trim();
                            String path = parts[2].trim();
                            if (name.isEmpty() || !path.startsWith("/dev/block/")) continue;
                            long size = 0;
                            try { size = Long.parseLong(parts[3].trim()); } catch (Exception ignored) {}
                            found.add(new Partition(name, path, size));
                        }
                    } else if (line.startsWith("__SLOT__|")) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length >= 3) {
                            suffix = parts[1].trim();
                            slotProp = parts[2].trim();
                        }
                    } else if (line.startsWith("__META__|")) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length >= 4) {
                            model = parts[1].trim();
                            version = parts[2].trim();
                            String device = parts[3].trim();
                            if (model.isEmpty()) model = device;
                            if (!device.isEmpty() && !device.equals(model)) {
                                model = model + "  ·  " + device;
                            }
                        }
                    }
                }
                found.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.US)));
                String rawSlot = suffix.replace("_", "").trim();
                if (rawSlot.isEmpty()) rawSlot = slotProp.trim();
                if (!rawSlot.isEmpty()) {
                    slotText = rawSlot.toUpperCase(Locale.US);
                } else {
                    boolean ab = false;
                    for (Partition part : found) {
                        String n = part.name.toLowerCase(Locale.US);
                        if (n.endsWith("_a") || n.endsWith("_b")) { ab = true; break; }
                    }
                    slotText = ab ? "A/B 未知" : "单槽位";
                }
                if (!rooted) {
                    error = "未获得 Root 权限";
                } else if (found.isEmpty()) {
                    error = "没有找到 /dev/block/by-name 下的分区";
                }
                if (r.exit != 0 && rooted && found.isEmpty()) {
                    error = "读取分区表失败";
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (error != null && error.toLowerCase(Locale.US).contains("su")) {
                    error = "未找到 su，请确认设备已 Root";
                }
            }
            final boolean root = rooted;
            final List<Partition> result = found;
            final String modelF = model;
            final String versionF = version;
            final String errorF = error;
            final String rawF = raw;
            final String slotF = slotText;
            main.post(() -> {
                busy = false;
                rootGranted = root;
                partitions = result;
                deviceModel = modelF;
                androidVersion = versionF;
                refreshButton.setRotation(0f);
                currentSlotText = slotF == null ? "" : slotF;
                if (!root) {
                    renderNoRoot(errorF == null ? "未获得 Root 权限" : errorF, rawF);
                } else if (result.isEmpty()) {
                    renderRootError(errorF == null ? "分区表为空" : errorF, rawF);
                } else {
                    String resolvedSlot = slotF;
                    if (!isKnownSlot(resolvedSlot) && isKnownSlot(currentSlotText)) {
                        resolvedSlot = currentSlotText;
                    }
                    currentSlotText = resolvedSlot == null ? "" : resolvedSlot;
                    setSlotChip(currentSlotText.isEmpty() ? "—" : currentSlotText,
                            isKnownSlot(currentSlotText));
                    setRootChip("Root 已授权", Ui.SUCCESS);
                    statusHint.setText((deviceModel.isEmpty() ? "设备" : deviceModel)
                            + (androidVersion.isEmpty() ? "" : "  ·  Android " + androidVersion));
                    heroModel.setText(modelF.isEmpty() ? "已连接设备" : modelF);
                    heroMeta.setText("/dev/block/by-name  ·  " + result.size() + " 个块设备");
                    heroCount.setText(String.valueOf(result.size()));
                    long total = 0;
                    for (Partition p : result) if (p.size > 0) total += p.size;
                    heroTotal.setText(Ui.size(total));
                    renderFiltered();
                }
            });
        });
    }

    private String partitionListScript() {
        return "echo \"__UID__:$(id -u)\"\n"
                + "if [ \"$(id -u)\" != \"0\" ]; then echo __NO_ROOT__; exit 9; fi\n"
                + "for p in /dev/block/by-name/*; do\n"
                + "  [ -e \"$p\" ] || continue\n"
                + "  n=${p##*/}\n"
                + "  t=$(readlink -f \"$p\")\n"
                + "  s=$(blockdev --getsize64 \"$t\" 2>/dev/null)\n"
                + "  if [ -z \"$s\" ] || [ \"$s\" = \"0\" ]; then\n"
                + "    b=${t##*/}\n"
                + "    sec=$(cat /sys/class/block/$b/size 2>/dev/null)\n"
                + "    [ -n \"$sec\" ] && s=$((sec * 512))\n"
                + "  fi\n"
                + "  echo \"__P__|$n|$t|${s:-0}\"\n"
                + "done\n"
                + "echo \"__SLOT__|$(getprop ro.boot.slot_suffix)|$(getprop ro.boot.slot)\"\n"
                + "echo \"__META__|$(getprop ro.product.model)|$(getprop ro.build.version.release)|$(getprop ro.product.device)\"\n"
                + "echo __END__\n";
    }

    private void showLoadingCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.outline(this, Ui.CARD, 18, Ui.STROKE, 1));
        Ui.pad(card, 16, 17, 16, 17, this);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.TEXT));
        card.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView tv = Ui.text(this, "正在读取分区表…", 13f, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        card.addView(tv, lp);
        listContainer.addView(card);
    }

    private void setRootChip(String text, int color) {
        rootChip.setText(text);
        rootChip.setTextColor(color);
        rootChip.setBackground(Ui.outline(this, Ui.CARD, 20, Ui.STROKE, 1));
    }

    private boolean isKnownSlot(String text) {
        return text != null && !text.isEmpty() && !text.contains("未知")
                && !text.equals("单槽位") && !text.equals("—");
    }

    private void setSlotChip(String text, boolean known) {
        if (slotChip == null) return;
        slotChip.setText(text == null || text.isEmpty() ? "—" : text);
        slotChip.setTextColor(known ? Ui.ACCENT : Ui.TEXT_FAINT);
        slotChip.setBackground(Ui.solid(known ? Ui.ACCENT_SOFT : Ui.CARD_2, 20, this));
    }

    private void loadSlotFromSystem() {
        io.execute(() -> {
            String slot = readSystemSlot();
            if (slot.isEmpty()) return;
            main.post(() -> {
                if (slotChip == null) return;
                if (!isKnownSlot(currentSlotText)) {
                    currentSlotText = slot;
                    setSlotChip(slot, true);
                }
            });
        });
    }

    private String readSystemSlot() {
        String suffix = readProp("ro.boot.slot_suffix");
        String slot = readProp("ro.boot.slot");
        String raw = suffix.trim().isEmpty() ? slot : suffix;
        raw = raw.replace("_", "").trim();
        if (!raw.isEmpty()) return raw.toUpperCase(Locale.US);
        String cmdline = readSmallFile("/proc/cmdline");
        Matcher m = Pattern.compile("androidboot\\.slot_suffix=(_?[A-Za-z0-9]+)").matcher(cmdline);
        if (m.find()) {
            String value = m.group(1).replace("_", "").trim();
            if (!value.isEmpty()) return value.toUpperCase(Locale.US);
        }
        return "";
    }

    private String readProp(String key) {
        try {
            Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            String value = RootShell.readText(p.getInputStream(), 128).trim();
            p.waitFor();
            return value;
        } catch (Exception e) {
            return "";
        }
    }

    private String readSmallFile(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            return RootShell.readText(in, 4096);
        } catch (Exception e) {
            return "";
        }
    }

    private void renderNoRoot(String message, String raw) {
        setRootChip("Root 未授权", Ui.AMBER);
        setSlotChip(currentSlotText == null || currentSlotText.isEmpty() ? "—" : currentSlotText,
                isKnownSlot(currentSlotText));
        statusHint.setText("点击状态标签重新检测");
        heroModel.setText("等待 Root 授权");
        heroMeta.setText("需要超级用户权限才能读取块设备");
        heroCount.setText("—");
        heroTotal.setText("—");
        countBadge.setText("0 个");
        listContainer.removeAllViews();
        listContainer.addView(infoCard("!", "需要 Root 权限",
                "请打开 Magisk / KernelSU / APatch，在超级用户列表中允许「分区镜像」。部分设备还需要先开启 Root 或安装授权管理器。\n\n" + message,
                "重新检测", v -> loadPartitions(), false));
    }

    private void renderRootError(String message, String raw) {
        setRootChip("Root 已授权", Ui.SUCCESS);
        setSlotChip(currentSlotText == null || currentSlotText.isEmpty() ? "—" : currentSlotText,
                isKnownSlot(currentSlotText));
        statusHint.setText("已获得 Root，但分区表读取失败");
        heroModel.setText(deviceModel.isEmpty() ? "已获得 Root" : deviceModel);
        heroMeta.setText("分区表读取失败");
        heroCount.setText("0");
        heroTotal.setText("—");
        countBadge.setText("0 个");
        listContainer.removeAllViews();
        String detail = message;
        String diagnostic = Ui.oneLine(raw, 220);
        if (!diagnostic.isEmpty()) detail += "\n\n" + diagnostic;
        listContainer.addView(infoCard("!", "没有读取到分区",
                detail, "重试", v -> loadPartitions(), false));
    }

    private View infoCard(String icon, String title, String message, String action, View.OnClickListener listener, boolean danger) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.outline(this, Ui.CARD, 20, Ui.STROKE, 1));
        Ui.pad(card, 18, 18, 18, 17, this);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.text(this, icon, 12f, danger ? Ui.RED : Ui.TEXT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.solid(danger ? Ui.CARD_2 : Ui.CARD_2, 50, this));
        top.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView heading = Ui.text(this, title, 15f, Ui.TEXT, true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        hLp.leftMargin = Ui.dp(this, 11);
        top.addView(heading, hLp);
        card.addView(top);
        TextView body = Ui.text(this, message, 12f, Ui.TEXT_DIM, false);
        body.setLineSpacing(Ui.dp(this, 2), 1.1f);
        body.setTextIsSelectable(true);
        addTop(card, body, 12);
        if (action != null) {
            TextView btn = Ui.button(this, action, Ui.ACCENT, Ui.ACCENT_DARK, 0);
            btn.setOnClickListener(listener);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.topMargin = Ui.dp(this, 14);
            card.addView(btn, lp);
        }
        return card;
    }

    private void renderFiltered() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        if (partitions.isEmpty()) {
            countBadge.setText("0 个");
            return;
        }
        List<Partition> filtered = new ArrayList<>();
        for (Partition p : partitions) {
            if (query.isEmpty()
                    || p.name.toLowerCase(Locale.US).contains(query)
                    || p.path.toLowerCase(Locale.US).contains(query)) {
                filtered.add(p);
            }
        }
        countBadge.setText(query.isEmpty() ? partitions.size() + " 个"
                : filtered.size() + " / " + partitions.size());
        if (filtered.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setBackground(Ui.outline(this, Ui.CARD, 20, Ui.STROKE, 1));
            Ui.pad(emptyCard, 18, 28, 18, 28, this);
            TextView empty = Ui.text(this, "没有匹配的分区", 12.5f, Ui.TEXT_DIM, false);
            empty.setGravity(Gravity.CENTER);
            emptyCard.addView(empty);
            listContainer.addView(emptyCard);
            return;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.outline(this, Ui.CARD, 20, Ui.STROKE, 1));
        card.setClipToOutline(true);
        Ui.pad(card, 0, 4, 0, 4, this);
        List<View> rowViews = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            View row = partitionRow(filtered.get(i));
            rowViews.add(row);
            card.addView(row);
            if (i < filtered.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Ui.STROKE_SOFT);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(MATCH, Ui.dp(this, 1));
                dlp.leftMargin = Ui.dp(this, 16);
                card.addView(divider, dlp);
            }
        }
        listContainer.addView(card);
        if (bottomNav instanceof GlassBar) ((GlassBar) bottomNav).requestCapture();
        if (!listEntrancePlayed) {
            listEntrancePlayed = true;
            int delay = 0;
            for (View row : rowViews) {
                row.setAlpha(0f);
                row.setTranslationY(Ui.dp(this, 10));
                row.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(delay)
                        .setDuration(260)
                        .setInterpolator(new DecelerateInterpolator(1.4f))
                        .start();
                delay = Math.min(delay + 18, 260);
            }
            card.postDelayed(() -> {
                for (View row : rowViews) {
                    if (row.getAlpha() < 0.99f) {
                        row.setAlpha(1f);
                        row.setTranslationY(0f);
                    }
                }
            }, 620);
        }
    }

    private View partitionRow(Partition p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.ripple(this, Ui.solid(0x00000000, 0, this),
                Ui.DARK ? 0x22FFFFFF : 0x12000000));
        Ui.pad(row, 16, 14, 13, 14, this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, p.name, 15f, Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        TextView path = Ui.text(this, p.path, 10f, Ui.TEXT_FAINT, false);
        path.setTypeface(Ui.mono());
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        addTop(col, path, 4);
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView size = Ui.text(this, Ui.size(p.size), 12f, Ui.TEXT_DIM, false);
        size.setSingleLine(true);
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        sizeLp.leftMargin = Ui.dp(this, 12);
        row.addView(size, sizeLp);
        TextView chevron = Ui.text(this, "›", 18f, Ui.TEXT_FAINT, false);
        chevron.setPadding(Ui.dp(this, 8), 0, 0, 0);
        row.addView(chevron);
        row.setOnClickListener(v -> openPartition(p));
        return row;
    }

    private void openPartition(Partition p) {
        CappedScrollView sv = new CappedScrollView(this, 0.88f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout heading = dialogHeading(card, "分区详情", p.name);
        TextView close = closeButton();
        heading.addView(close);

        LinearLayout pathHead = new LinearLayout(this);
        pathHead.setOrientation(LinearLayout.HORIZONTAL);
        pathHead.setGravity(Gravity.CENTER_VERTICAL);
        pathHead.addView(Ui.label(this, "块设备路径", Ui.TEXT_FAINT));
        View grow = new View(this);
        pathHead.addView(grow, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView copy = Ui.text(this, "复制", 10.5f, Ui.ACCENT, true);
        copy.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), 0, Ui.dp(this, 2));
        copy.setOnClickListener(v -> Ui.copy(this, "partition path", p.path));
        pathHead.addView(copy);
        addTop(card, pathHead, 20);

        TextView path = codeBlock(p.path);
        addTop(card, path, 8);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.TOP);
        LinearLayout sizeCol = infoColumn("分区大小", Ui.size(p.size));
        info.addView(sizeCol, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView formatValue = Ui.text(this, "识别中…", 14f, Ui.TEXT, true);
        LinearLayout typeCol = infoColumnView("内容格式", formatValue);
        LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(0, WRAP, 1.25f);
        typeLp.leftMargin = Ui.dp(this, 12);
        info.addView(typeCol, typeLp);
        addTop(card, info, 16);

        addTop(card, hairline(), 17);
        TextView actionsLabel = Ui.label(this, "操作", Ui.TEXT_FAINT);
        addTop(card, actionsLabel, 15);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView hexBtn = Ui.button(this, "查看头部", Ui.CARD_2, Ui.TEXT, Ui.STROKE);
        TextView backupBtn = Ui.button(this, "备份分区", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        bLp.leftMargin = Ui.dp(this, 9);
        actions.addView(hexBtn, hLp);
        actions.addView(backupBtn, bLp);
        addTop(card, actions, 10);

        TextView replaceBtn = Ui.button(this, "选择镜像并替换此分区", Ui.CARD_2, Ui.RED, Ui.STROKE);
        addTop(card, replaceBtn, 9);

        TextView note = Ui.text(this,
                "替换会直接写入块设备。请确认镜像与当前机型、分区完全匹配，并建议先备份原分区。",
                10.5f, Ui.TEXT_FAINT, false);
        note.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, note, 12);

        Dialog d = Ui.dialog(this, sv, true);
        activeDialog = d;
        close.setOnClickListener(v -> d.dismiss());
        backupBtn.setOnClickListener(v -> {
            d.dismiss();
            confirmBackup(p);
        });
        hexBtn.setOnClickListener(v -> {
            loadAndShowHeader(p);
        });
        replaceBtn.setOnClickListener(v -> {
            d.dismiss();
            pendingFlashTarget = p;
            pickImage(REQ_FLASH_IMAGE);
        });
        d.setOnDismissListener(di -> {
            if (activeDialog == d) activeDialog = null;
        });
        d.show();

        io.execute(() -> {
            byte[] header = readHeader(p.path, 2048);
            String type = header == null ? "读取失败" : Ui.guessType(header);
            main.post(() -> {
                if (d.isShowing() && !isFinishing()) formatValue.setText(type);
            });
        });
    }

    private LinearLayout dialogHeading(LinearLayout card, String label, String title) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.label(this, label, Ui.TEXT_FAINT));
        TextView t = Ui.text(this, title, 23f, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addTop(col, t, 8);
        top.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        card.addView(top);
        return top;
    }

    private TextView closeButton() {
        TextView close = Ui.text(this, "✕", 15f, Ui.TEXT_DIM, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.ripple(this, Ui.outline(this, Ui.CARD_2, 20, Ui.STROKE, 1), Ui.DARK ? 0x33FFFFFF : 0x14000000));
        close.setClickable(true);
        close.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        lp.leftMargin = Ui.dp(this, 10);
        close.setLayoutParams(lp);
        return close;
    }

    private LinearLayout dialogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.outline(this, Ui.CARD, 24, Ui.STROKE, 1));
        Ui.pad(card, 22, 22, 22, 21, this);
        return card;
    }

    private View hairline() {
        View line = new View(this);
        line.setBackgroundColor(Ui.STROKE_SOFT);
        line.setLayoutParams(new LinearLayout.LayoutParams(MATCH, Ui.dp(this, 1)));
        return line;
    }

    private LinearLayout infoColumn(String label, String value) {
        return infoColumnView(label, Ui.text(this, value, 14f, Ui.TEXT, true));
    }

    private LinearLayout infoColumnView(String label, TextView value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.label(this, label, Ui.TEXT_FAINT));
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addTop(col, value, 6);
        return col;
    }

    private TextView codeBlock(String value) {
        TextView tv = Ui.text(this, value, 10.5f, Ui.TEXT_DIM, false);
        tv.setTypeface(Ui.mono());
        tv.setTextIsSelectable(true);
        tv.setBackground(Ui.outline(this, Ui.BG_SOFT, 12, Ui.STROKE_SOFT, 1));
        Ui.pad(tv, 12, 11, 12, 11, this);
        return tv;
    }

    private void confirmBackup(Partition p) {
        CappedScrollView sv = new CappedScrollView(this, 0.86f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout heading = dialogHeading(card, "备份", p.name);
        TextView close = closeButton();
        heading.addView(close);

        TextView intro = Ui.text(this,
                "将完整读取该分区，并以原始镜像（.img）保存到手机 Download 目录。",
                12f, Ui.TEXT_DIM, false);
        intro.setLineSpacing(Ui.dp(this, 2), 1.1f);
        addTop(card, intro, 16);

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setBackground(Ui.outline(this, Ui.BG_SOFT, 14, Ui.STROKE_SOFT, 1));
        Ui.pad(detail, 14, 13, 14, 13, this);
        detail.addView(detailLine("分区容量", Ui.size(p.size)));
        detail.addView(detailLine("输出目录", "Download/PartitionImages"));
        detail.addView(detailLine("文件格式", "原始镜像 · .img"));
        addTop(card, detail, 14);

        TextView note = Ui.text(this,
                "备份期间请保持应用在前台，不要退出或断电。大分区可能需要几分钟。",
                10.5f, Ui.TEXT_FAINT, false);
        addTop(card, note, 12);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = Ui.button(this, "取消", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        TextView start = Ui.button(this, "开始备份", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, WRAP, 1.35f);
        sLp.leftMargin = Ui.dp(this, 9);
        buttons.addView(cancel, cLp);
        buttons.addView(start, sLp);
        addTop(card, buttons, 18);

        Dialog d = Ui.dialog(this, sv, true);
        close.setOnClickListener(v -> d.dismiss());
        cancel.setOnClickListener(v -> d.dismiss());
        start.setOnClickListener(v -> {
            d.dismiss();
            startBackup(p);
        });
        d.show();
    }

    private View detailLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Ui.text(this, label, 11f, Ui.TEXT_FAINT, false);
        row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 0.8f));
        TextView v = Ui.text(this, value, 11.5f, Ui.TEXT_DIM, true);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(v, new LinearLayout.LayoutParams(0, WRAP, 1.2f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private void startBackup(Partition p) {
        if (busy) return;
        if (!guardIntegrity()) return;
        busy = true;
        Ui.keepScreenOn(this, true);
        ProgressPanel panel = showProgress("正在备份", "正在读取 " + p.name + "…");
        io.execute(() -> {
            BackupOutcome outcome = backupToFile(p, "", (done, total) -> {
                int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                String detail = Ui.size(done) + (total > 0 ? " / " + Ui.size(total) : "");
                main.post(() -> panel.update(percent, "正在读取 " + p.name, detail));
            });
            main.post(() -> {
                panel.dismiss();
                busy = false;
                Ui.keepScreenOn(this, false);
                if (outcome.ok) {
                    scanFile(outcome.path);
                    showBackupResult(p, outcome);
                } else {
                    showError("备份失败", outcome.message);
                }
            });
        });
    }

    private void showBackupResult(Partition p, BackupOutcome outcome) {
        CappedScrollView sv = new CappedScrollView(this, 0.86f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        addTop(card, statusBadge("✓", Ui.SUCCESS, Ui.CARD_2, Ui.STROKE), 0);
        TextView title = Ui.text(this, "备份完成", 24f, Ui.TEXT, true);
        addTop(card, title, 14);
        TextView msg = Ui.text(this, p.name + " 已保存为原始镜像，大小 " + Ui.size(outcome.size) + "。", 12f, Ui.TEXT_DIM, false);
        addTop(card, msg, 7);
        TextView path = codeBlock(outcome.path);
        addTop(card, path, 16);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = Ui.button(this, "复制路径", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        TextView done = Ui.button(this, "完成", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, WRAP, 1.3f);
        dLp.leftMargin = Ui.dp(this, 9);
        buttons.addView(copy, cLp);
        buttons.addView(done, dLp);
        addTop(card, buttons, 18);

        Dialog d = Ui.dialog(this, sv, true);
        copy.setOnClickListener(v -> Ui.copy(this, "backup path", outcome.path));
        done.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private TextView statusBadge(String glyph, int color, int fill, int stroke) {
        TextView badge = Ui.text(this, glyph, 22f, color, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.outline(this, fill, 50, stroke, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        badge.setLayoutParams(lp);
        return badge;
    }

    private BackupOutcome backupToFile(Partition p, String prefix, ByteProgress callback) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safe = p.name.replaceAll("[^A-Za-z0-9._-]", "_");
        String out = BACKUP_DIR + "/" + prefix + safe + "_" + stamp + ".img";
        String part = out + ".part";
        String cmd = "mkdir -p " + RootShell.q(BACKUP_DIR) + "\n"
                + "rm -f " + RootShell.q(out) + " " + RootShell.q(part) + "\n"
                + "donefile=/data/local/tmp/.pstudio_$$_ddrc\n"
                + "rm -f \"$donefile\"\n"
                + "( dd if=" + RootShell.q(p.path) + " of=" + RootShell.q(part)
                + " bs=4M conv=fsync 2>&1; echo $? > \"$donefile\" ) &\n"
                + "pid=$!\n"
                + "while [ ! -f \"$donefile\" ]; do\n"
                + "  sz=$(stat -c %s " + RootShell.q(part) + " 2>/dev/null)\n"
                + "  [ -n \"$sz\" ] && echo \"__PROG__:$sz\"\n"
                + "  sleep 0.5\n"
                + "done\n"
                + "wait \"$pid\"\n"
                + "rc=$(cat \"$donefile\" 2>/dev/null)\n"
                + "rm -f \"$donefile\"\n"
                + "[ -n \"$rc\" ] || rc=1\n"
                + "if [ $rc -ne 0 ]; then rm -f " + RootShell.q(part) + "; echo __FAIL__:$rc; exit $rc; fi\n"
                + "sync\n"
                + "mv -f " + RootShell.q(part) + " " + RootShell.q(out) + " || { rm -f " + RootShell.q(part) + "; echo __MOVE_FAIL__; exit 1; }\n"
                + "echo __SIZE__:$(stat -c %s " + RootShell.q(out) + ")\n";
        try {
            RootShell.Result result = RootShell.runWithProgress(cmd, p.size,
                    (done, total) -> { if (callback != null) callback.onProgress(done, total); });
            Matcher matcher = Pattern.compile("__SIZE__:(\\d+)").matcher(result.output);
            if (result.exit != 0 || !matcher.find()) {
                String reason = Ui.oneLine(result.output, 320);
                if (reason.isEmpty()) reason = "退出码 " + result.exit;
                return new BackupOutcome(false, out, "无法读取分区或写入存储。\n" + reason, 0);
            }
            long size;
            try { size = Long.parseLong(matcher.group(1)); } catch (Exception e) { size = 0; }
            if (size <= 0) {
                return new BackupOutcome(false, out, "备份文件为空，请检查存储空间。", 0);
            }
            if (p.size > 0 && size != p.size) {
                return new BackupOutcome(false, out,
                        "备份大小与分区不一致（" + Ui.size(size) + " / " + Ui.size(p.size) + "）。", size);
            }
            return new BackupOutcome(true, out, "", size);
        } catch (Exception e) {
            return new BackupOutcome(false, out, friendlyError(e), 0);
        }
    }

    private void openBundleBackup() {
        if (busy) {
            Ui.toast(this, "操作进行中，请稍候");
            return;
        }
        if (partitions.isEmpty()) {
            Ui.toast(this, "还没有读取到分区");
            return;
        }
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final String fileName = BUNDLE_PREFIX + stamp + ".zip";
        final long free = availableBytes();

        CappedScrollView sv = new CappedScrollView(this, 0.9f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout heading = dialogHeading(card, "一键备份", "打包为 ZIP");
        TextView close = closeButton();
        heading.addView(close);

        TextView intro = Ui.text(this,
                "将当前分区打包成一个 ZIP 压缩包，保存到 Download/PartitionImages。",
                12f, Ui.TEXT_DIM, false);
        intro.setLineSpacing(Ui.dp(this, 2), 1.1f);
        addTop(card, intro, 14);

        TextView nameValue = Ui.text(this, fileName, 11f, Ui.TEXT, true);
        nameValue.setTypeface(Ui.mono());
        nameValue.setSingleLine(true);
        nameValue.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView countValue = Ui.text(this, "", 11.5f, Ui.TEXT, true);
        TextView totalValue = Ui.text(this, "", 11.5f, Ui.TEXT, true);
        TextView freeValue = Ui.text(this, free > 0 ? Ui.size(free) : "未知", 11.5f, Ui.TEXT, true);

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setBackground(Ui.outline(this, Ui.BG_SOFT, 14, Ui.STROKE_SOFT, 1));
        Ui.pad(detail, 14, 5, 14, 5, this);
        detail.addView(bundleDetailRow("输出文件", nameValue));
        detail.addView(bundleDetailRow("分区数量", countValue));
        detail.addView(bundleDetailRow("原始大小", totalValue));
        detail.addView(bundleDetailRow("可用空间", freeValue));
        addTop(card, detail, 14);

        CheckBox dataBox = new CheckBox(this);
        dataBox.setText("包含数据分区");
        dataBox.setTextColor(Ui.TEXT);
        dataBox.setTextSize(13f);
        dataBox.setButtonTintList(ColorStateList.valueOf(Ui.TEXT));
        dataBox.setPadding(0, 0, 0, 0);
        addTop(card, dataBox, 12);
        TextView dataHint = Ui.text(this, "默认排除 userdata、cache、metadata，避免 ZIP 过大。",
                10.5f, Ui.TEXT_FAINT, false);
        dataHint.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, dataHint, 1);

        TextView spaceWarn = Ui.text(this, "", 10.5f, Ui.AMBER, false);
        spaceWarn.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, spaceWarn, 8);

        TextView note = Ui.text(this,
                "备份期间请保持应用在前台。ZIP 内为各分区单独的 .img，解压后可分别写入。",
                10f, Ui.TEXT_FAINT, false);
        note.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, note, 10);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = Ui.button(this, "取消", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        TextView start = Ui.button(this, "开始备份", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, WRAP, 1.35f);
        sLp.leftMargin = Ui.dp(this, 9);
        buttons.addView(cancel, cLp);
        buttons.addView(start, sLp);
        addTop(card, buttons, 16);

        final boolean canStart = free < 0 || free >= MIN_FREE_FOR_BUNDLE;
        if (!canStart) {
            start.setEnabled(false);
            start.setAlpha(0.5f);
        }

        Runnable refresh = () -> {
            boolean includeData = dataBox.isChecked();
            List<Partition> selected = bundleSelection(includeData);
            long total = 0;
            for (Partition p : selected) if (p.size > 0) total += p.size;
            countValue.setText(selected.size() + " 个");
            totalValue.setText(Ui.size(total));
            dataHint.setText(includeData
                    ? "已包含数据分区：userdata、cache、metadata，ZIP 可能非常大。"
                    : "默认排除 userdata、cache、metadata，避免 ZIP 过大。");
            dataHint.setTextColor(includeData ? Ui.AMBER : Ui.TEXT_FAINT);
            if (!canStart) {
                spaceWarn.setVisibility(View.VISIBLE);
                spaceWarn.setText("可用空间不足，无法创建备份文件。请先清理存储空间。");
            } else if (free > 0 && total > free) {
                spaceWarn.setVisibility(View.VISIBLE);
                spaceWarn.setText("可用空间少于原始分区大小。ZIP 通常会压缩变小，但仍可能中途空间不足。");
            } else {
                spaceWarn.setVisibility(View.GONE);
            }
        };
        refresh.run();
        dataBox.setOnCheckedChangeListener((buttonView, isChecked) -> refresh.run());

        Dialog d = Ui.dialog(this, sv, true);
        close.setOnClickListener(v -> d.dismiss());
        cancel.setOnClickListener(v -> d.dismiss());
        start.setOnClickListener(v -> {
            if (!canStart) {
                Ui.toast(this, "存储空间不足");
                return;
            }
            List<Partition> selected = bundleSelection(dataBox.isChecked());
            if (selected.isEmpty()) {
                Ui.toast(this, "没有可备份的分区");
                return;
            }
            d.dismiss();
            startBundleBackup(selected, fileName);
        });
        d.show();
    }

    private void openCustomBackup() {
        if (busy) {
            Ui.toast(this, "操作进行中，请稍候");
            return;
        }
        if (partitions.isEmpty()) {
            Ui.toast(this, "还没有读取到分区");
            return;
        }
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final String fileName = BUNDLE_PREFIX + stamp + ".zip";
        final long free = availableBytes();
        final List<Partition> all = new ArrayList<>(partitions);
        final List<CheckBox> boxes = new ArrayList<>();

        CappedScrollView sv = new CappedScrollView(this, 0.92f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout heading = dialogHeading(card, "自定义备份", "选择分区");
        TextView close = closeButton();
        heading.addView(close);

        TextView intro = Ui.text(this,
                "勾选需要打包的分区。所有内容会写入同一个 ZIP，文件名 " + fileName + "。",
                11.5f, Ui.TEXT_DIM, false);
        intro.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, intro, 12);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        TextView selectAll = compactButton("全选");
        TextView clearAll = compactButton("清空");
        TextView defaults = compactButton("仅系统");
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        t2.leftMargin = Ui.dp(this, 7);
        t3.leftMargin = Ui.dp(this, 7);
        tools.addView(selectAll, t1);
        tools.addView(clearAll, t2);
        tools.addView(defaults, t3);
        addTop(card, tools, 12);

        TextView summary = Ui.text(this, "", 11.5f, Ui.TEXT_DIM, true);
        addTop(card, summary, 10);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setBackground(Ui.outline(this, Ui.BG_SOFT, 14, Ui.STROKE_SOFT, 1));
        Ui.pad(listScroll, 4, 4, 4, 4, this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Partition p : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(row, 8, 7, 10, 7, this);
            CheckBox cb = new CheckBox(this);
            cb.setText(p.name);
            cb.setTextColor(Ui.TEXT);
            cb.setTextSize(12f);
            cb.setSingleLine(true);
            cb.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
            cb.setChecked(!isDataPartition(p.name));
            cb.setPadding(0, 0, 0, 0);
            row.addView(cb, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView sz = Ui.text(this, Ui.size(p.size), 10f, Ui.TEXT_FAINT, false);
            sz.setSingleLine(true);
            row.addView(sz);
            list.addView(row);
            boxes.add(cb);
        }
        listScroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(MATCH, Ui.dp(this, 270));
        listLp.topMargin = Ui.dp(this, 8);
        card.addView(listScroll, listLp);

        TextView spaceWarn = Ui.text(this, "", 10.5f, Ui.AMBER, false);
        spaceWarn.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, spaceWarn, 8);
        TextView note = Ui.text(this,
                "默认不勾选 userdata / cache / metadata。数据分区可能非常大，请确认剩余空间。",
                10f, Ui.TEXT_FAINT, false);
        note.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, note, 6);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = Ui.button(this, "取消", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        TextView start = Ui.button(this, "开始备份", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, WRAP, 1.35f);
        sLp.leftMargin = Ui.dp(this, 9);
        buttons.addView(cancel, cLp);
        buttons.addView(start, sLp);
        addTop(card, buttons, 14);

        final boolean canCreate = free < 0 || free >= MIN_FREE_FOR_BUNDLE;
        final Runnable refresh = () -> {
            int count = 0;
            long total = 0;
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isChecked()) {
                    count++;
                    total += all.get(i).size;
                }
            }
            summary.setText("已选 " + count + " 个 · " + Ui.size(total));
            start.setEnabled(count > 0 && canCreate);
            start.setAlpha(count > 0 && canCreate ? 1f : 0.5f);
            if (!canCreate) {
                spaceWarn.setVisibility(View.VISIBLE);
                spaceWarn.setText("可用空间不足，无法创建备份文件。请先清理存储空间。");
            } else if (free > 0 && total > free) {
                spaceWarn.setVisibility(View.VISIBLE);
                spaceWarn.setText("原始大小超过可用空间。ZIP 通常会压缩变小，但仍可能中途失败。");
            } else {
                spaceWarn.setVisibility(View.GONE);
            }
        };
        for (CheckBox cb : boxes) {
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> refresh.run());
        }
        selectAll.setOnClickListener(v -> {
            for (CheckBox cb : boxes) cb.setChecked(true);
            refresh.run();
        });
        clearAll.setOnClickListener(v -> {
            for (CheckBox cb : boxes) cb.setChecked(false);
            refresh.run();
        });
        defaults.setOnClickListener(v -> {
            for (int i = 0; i < boxes.size(); i++) {
                boxes.get(i).setChecked(!isDataPartition(all.get(i).name));
            }
            refresh.run();
        });
        refresh.run();

        Dialog d = Ui.dialog(this, sv, true);
        close.setOnClickListener(v -> d.dismiss());
        cancel.setOnClickListener(v -> d.dismiss());
        start.setOnClickListener(v -> {
            List<Partition> selected = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isChecked()) selected.add(all.get(i));
            }
            if (selected.isEmpty()) {
                Ui.toast(this, "请至少选择一个分区");
                return;
            }
            if (!canCreate) {
                Ui.toast(this, "存储空间不足");
                return;
            }
            d.dismiss();
            startBundleBackup(selected, fileName);
        });
        d.show();
    }

    private TextView compactButton(String label) {
        TextView tv = Ui.text(this, label, 11f, Ui.TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(Ui.ripple(this, Ui.outline(this, Ui.CARD_2, 12, Ui.STROKE, 1),
                Ui.DARK ? 0x22FFFFFF : 0x12000000));
        tv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private LinearLayout bundleDetailRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Ui.text(this, label, 11f, Ui.TEXT_FAINT, false);
        row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 0.8f));
        value.setGravity(Gravity.END);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(value, new LinearLayout.LayoutParams(0, WRAP, 1.2f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = Ui.dp(this, 8);
        lp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private List<Partition> bundleSelection(boolean includeData) {
        List<Partition> selected = new ArrayList<>();
        for (Partition p : partitions) {
            if (includeData || !isDataPartition(p.name)) selected.add(p);
        }
        return selected;
    }

    private boolean isDataPartition(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.equals("userdata") || n.startsWith("userdata")
                || n.equals("cache") || n.startsWith("cache")
                || n.equals("metadata") || n.startsWith("metadata");
    }

    private long availableBytes() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        try {
            if (!dir.exists()) dir.mkdirs();
            StatFs stat = new StatFs(dir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    private void startBundleBackup(final List<Partition> selected, final String fileName) {
        if (busy || selected.isEmpty()) return;
        if (!guardIntegrity()) return;
        busy = true;
        Ui.keepScreenOn(this, true);
        ProgressPanel panel = showProgress("一键备份", "正在准备 ZIP…");
        io.execute(() -> {
            BundleOutcome outcome = createBundle(selected, fileName, (percent, stage, detailText) ->
                    main.post(() -> panel.update(percent, stage, detailText)));
            main.post(() -> {
                panel.dismiss();
                busy = false;
                Ui.keepScreenOn(this, false);
                if (outcome.ok) {
                    scanFile(outcome.path);
                    showBundleResult(outcome);
                } else {
                    showError("一键备份失败", outcome.message);
                }
            });
        });
    }

    private BundleOutcome createBundle(final List<Partition> selected, final String fileName,
                                       final BundleProgress progress) {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File stageDir = new File(base, "bundle_staging");
        if (!stageDir.exists() && !stageDir.mkdirs()) {
            return new BundleOutcome(false, "", 0, selected.size(), "无法创建临时目录。");
        }
        File[] stale = stageDir.listFiles();
        if (stale != null) {
            for (File old : stale) {
                if (old.getName().endsWith(".part")) old.delete();
            }
        }
        File part = new File(stageDir, fileName + ".part");
        File partLocal = new File(stageDir, fileName);
        if (partLocal.exists()) partLocal.delete();

        long total = 0;
        for (Partition p : selected) if (p.size > 0) total += p.size;
        StringBuilder manifest = new StringBuilder();
        manifest.append("分区镜像 一键备份\n");
        manifest.append("备份时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        manifest.append("设备: ").append(deviceModel).append("\n");
        manifest.append("Android: ").append(androidVersion).append("\n");
        manifest.append("分区数量: ").append(selected.size()).append("\n");
        manifest.append("原始总大小: ").append(Ui.size(total)).append("\n");
        manifest.append("压缩格式: ZIP / DEFLATE\n\n");
        manifest.append("文件列表:\n");
        for (Partition p : selected) {
            manifest.append(String.format(Locale.US, "%-24s %s\n", p.name + ".img", Ui.size(p.size)));
        }
        manifest.append("\n说明: ZIP 内是块设备原始镜像，恢复时必须对应相同机型和分区。\n");

        long done = 0;
        int index = 0;
        long lastNotify = 0;
        try (FileOutputStream fos = new FileOutputStream(part);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1024 * 1024);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_SPEED);
            ZipEntry infoEntry = new ZipEntry("backup_info.txt");
            zos.putNextEntry(infoEntry);
            zos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            for (Partition p : selected) {
                index++;
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (index == 1 || now - lastNotify > 250) {
                        lastNotify = now;
                        int pct = total > 0 ? (int) Math.min(99, done * 100 / total) : -1;
                        progress.onProgress(pct, "正在备份 " + p.name,
                                index + "/" + selected.size() + " · " + Ui.size(done) + " / " + Ui.size(total));
                    }
                }
                ZipEntry entry = new ZipEntry(p.name.replace('/', '_') + ".img");
                zos.putNextEntry(entry);
                Process process = null;
                long entryBytes = 0;
                try {
                    process = RootShell.startBinary("dd if=" + RootShell.q(p.path) + " bs=1M");
                    try (InputStream in = process.getInputStream()) {
                        byte[] buf = new byte[1024 * 1024];
                        int n;
                        long lastUi = 0;
                        while ((n = in.read(buf)) != -1) {
                            if (n == 0) continue;
                            zos.write(buf, 0, n);
                            entryBytes += n;
                            done += n;
                            long now = System.currentTimeMillis();
                            if (progress != null && now - lastUi > 300) {
                                lastUi = now;
                                lastNotify = now;
                                int pct = total > 0 ? (int) Math.min(99, done * 100 / total) : -1;
                                progress.onProgress(pct, "正在备份 " + p.name,
                                        index + "/" + selected.size() + " · " + Ui.size(done) + " / " + Ui.size(total));
                            }
                        }
                    }
                    int code = process.waitFor();
                    if (code != 0) {
                        throw new IOException("读取 " + p.name + " 失败（dd 退出码 " + code + "）");
                    }
                } finally {
                    if (process != null) process.destroy();
                }
                if (p.size > 0 && entryBytes != p.size) {
                    throw new IOException("分区 " + p.name + " 读取不完整（"
                            + entryBytes + " / " + p.size + " 字节）");
                }
                zos.closeEntry();
            }
            zos.finish();
        } catch (Exception e) {
            part.delete();
            return new BundleOutcome(false, "", 0, selected.size(),
                    "打包失败：" + friendlyError(e) + "\n\n请确认存储空间充足且分区可读。");
        }

        if (part.length() <= 0) {
            part.delete();
            return new BundleOutcome(false, "", 0, selected.size(), "生成的 ZIP 为空。");
        }
        long zipSize = part.length();
        String dest = BACKUP_DIR + "/" + fileName;
        String moveCmd = "mkdir -p " + RootShell.q(BACKUP_DIR) + "\n"
                + "rm -f " + RootShell.q(dest) + "\n"
                + "mv -f " + RootShell.q(part.getAbsolutePath()) + " " + RootShell.q(dest)
                + " || { echo __ZIP_MOVE_FAIL__; exit 1; }\n"
                + "chmod 644 " + RootShell.q(dest) + "\n"
                + "sync\n"
                + "echo __ZIP_OK__\n";
        try {
            RootShell.Result move = RootShell.run(moveCmd);
            if (move.exit == 0 && move.output.contains("__ZIP_OK__")) {
                return new BundleOutcome(true, dest, zipSize, selected.size(), "");
            }
        } catch (Exception ignored) {}
        if (part.renameTo(partLocal)) {
            return new BundleOutcome(true, partLocal.getAbsolutePath(), zipSize, selected.size(),
                    "打包已完成，但移动到 Download 失败。文件保存在应用专属目录，请用文件管理器手动移出。");
        }
        return new BundleOutcome(false, part.getAbsolutePath(), zipSize, selected.size(),
                "打包已完成，但无法移动到 Download，也无法重命名临时文件。");
    }

    private void showBundleResult(BundleOutcome outcome) {
        CappedScrollView sv = new CappedScrollView(this, 0.88f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        addTop(card, statusBadge("✓", Ui.SUCCESS, Ui.CARD_2, Ui.STROKE), 0);
        TextView title = Ui.text(this, "备份完成", 22f, Ui.TEXT, true);
        addTop(card, title, 14);
        TextView msg = Ui.text(this,
                "已打包 " + outcome.count + " 个分区，ZIP 大小 " + Ui.size(outcome.zipSize) + "。",
                12f, Ui.TEXT_DIM, false);
        addTop(card, msg, 7);
        if (outcome.message != null && !outcome.message.isEmpty()) {
            TextView warn = Ui.text(this, outcome.message, 10.5f, Ui.AMBER, false);
            warn.setLineSpacing(Ui.dp(this, 2), 1.05f);
            addTop(card, warn, 9);
        }
        TextView path = codeBlock(outcome.path);
        addTop(card, path, 14);
        TextView note = Ui.text(this,
                "ZIP 内每个分区都是独立 .img 文件，解压后可单独写入。",
                10f, Ui.TEXT_FAINT, false);
        addTop(card, note, 8);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = Ui.button(this, "复制路径", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        TextView done = Ui.button(this, "完成", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, WRAP, 1.3f);
        dLp.leftMargin = Ui.dp(this, 9);
        buttons.addView(copy, cLp);
        buttons.addView(done, dLp);
        addTop(card, buttons, 16);
        Dialog d = Ui.dialog(this, sv, true);
        copy.setOnClickListener(v -> Ui.copy(this, "zip backup path", outcome.path));
        done.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void prepareFlash(Uri uri) {
        final Partition target = pendingFlashTarget;
        if (target == null) {
            Ui.toast(this, "没有选择目标分区");
            return;
        }
        Ui.toast(this, "正在读取镜像…");
        io.execute(() -> {
            ImageInfo info = readImageInfo(uri);
            main.post(() -> {
                pendingFlashTarget = null;
                if (info == null || info.header == null) {
                    showError("无法读取镜像", "文件无法打开，或读取权限已失效。请重新选择镜像。");
                    return;
                }
                if (info.size <= 0) {
                    showError("无法读取镜像大小", "文件管理器没有提供镜像大小，无法安全写入。请将镜像复制到本机 Download 后重试。");
                    return;
                }
                if (Ui.isCompressedOrSparse(info.header)) {
                    showError("镜像格式不受支持",
                            "检测到该文件是稀疏镜像或压缩文件。\n\n请使用原始 .img（raw image），不要选择 .gz、.zip 或 Android sparse 格式。");
                    return;
                }
                if (target.size <= 0) {
                    showError("无法确认分区大小", "目标分区大小未知，为避免越界写入已阻止操作。");
                    return;
                }
                if (info.size > target.size) {
                    showError("镜像大于分区", "镜像 " + Ui.size(info.size) + " 大于目标分区 "
                            + Ui.size(target.size) + "，已阻止写入。");
                    return;
                }
                showFlashConfirm(target, info);
            });
        });
    }

    private ImageInfo readImageInfo(Uri uri) {
        ContentResolver cr = getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {}
        if (size < 0) {
            try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r")) {
                if (afd != null) size = afd.getLength();
            } catch (Exception ignored) {}
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "image.img";
        }
        byte[] header = new byte[2048];
        int read = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                while (read < header.length) {
                    int n = in.read(header, read, header.length - read);
                    if (n <= 0) break;
                    read += n;
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (read <= 0) return null;
        byte[] actual = new byte[read];
        System.arraycopy(header, 0, actual, 0, read);
        return new ImageInfo(uri, name, size, actual);
    }

    private void showFlashConfirm(Partition target, ImageInfo info) {
        CappedScrollView sv = new CappedScrollView(this, 0.9f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout heading = dialogHeading(card, "危险操作", "写入 " + target.name);
        TextView close = closeButton();
        heading.addView(close);

        LinearLayout warning = new LinearLayout(this);
        warning.setOrientation(LinearLayout.HORIZONTAL);
        warning.setBackground(Ui.outline(this, Ui.CARD_2, 14, Ui.STROKE, 1));
        Ui.pad(warning, 12, 11, 12, 11, this);
        TextView warnIcon = Ui.text(this, "!", 13f, Ui.AMBER, true);
        warnIcon.setGravity(Gravity.CENTER);
        warning.addView(warnIcon, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView warn = Ui.text(this, "这会直接覆盖块设备。错误镜像可能导致系统无法启动、数据丢失。", 11f, Ui.TEXT_DIM, false);
        warn.setLineSpacing(Ui.dp(this, 2), 1.05f);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        wLp.leftMargin = Ui.dp(this, 9);
        warning.addView(warn, wLp);
        addTop(card, warning, 16);

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setBackground(Ui.outline(this, Ui.BG_SOFT, 14, Ui.STROKE_SOFT, 1));
        Ui.pad(detail, 14, 12, 14, 12, this);
        detail.addView(detailLine("目标分区", target.name + "  ·  " + Ui.size(target.size)));
        detail.addView(detailLine("镜像文件", info.name));
        detail.addView(detailLine("镜像大小", Ui.size(info.size)));
        detail.addView(detailLine("识别格式", info.type()));
        addTop(card, detail, 14);

        if (info.size < target.size) {
            TextView small = Ui.text(this,
                    "镜像比分区小 " + Ui.size(target.size - info.size)
                            + "：只会覆盖分区开头，剩余内容保留，可能造成分区损坏。",
                    10.5f, Ui.AMBER, false);
            small.setLineSpacing(Ui.dp(this, 2), 1.05f);
            addTop(card, small, 10);
        }
        String lower = target.name.toLowerCase(Locale.US);
        if (lower.contains("system") || lower.contains("vendor") || lower.contains("product")
                || lower.contains("super") || lower.contains("userdata") || lower.contains("metadata")) {
            TextView live = Ui.text(this,
                    "该分区可能正在被系统挂载使用，运行中写入风险更高，可能立即导致系统异常或数据损坏。",
                    10.5f, Ui.RED, false);
            live.setLineSpacing(Ui.dp(this, 2), 1.05f);
            addTop(card, live, 8);
        }

        CheckBox backupBox = new CheckBox(this);
        backupBox.setText("写入前先备份原分区");
        backupBox.setTextColor(Ui.TEXT_DIM);
        backupBox.setTextSize(12.5f);
        backupBox.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        backupBox.setChecked(target.size > 0 && target.size <= 8L * 1024 * 1024 * 1024);
        backupBox.setPadding(0, 0, 0, 0);
        addTop(card, backupBox, 12);

        TextView backupHint = Ui.text(this,
                backupBox.isChecked()
                        ? "原分区将保存到 Download/PartitionImages"
                        : "已关闭自动备份：写入后无法恢复原分区",
                10f, Ui.TEXT_FAINT, false);
        addTop(card, backupHint, 2);
        backupBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            backupHint.setText(isChecked
                    ? "原分区将保存到 Download/PartitionImages"
                    : "已关闭自动备份：写入后无法恢复原分区");
            backupHint.setTextColor(isChecked ? Ui.TEXT_FAINT : Ui.AMBER);
        });

        TextView confirmLabel = Ui.text(this, "输入分区名 " + target.name + " 以确认", 11f, Ui.TEXT_DIM, false);
        addTop(card, confirmLabel, 16);
        EditText confirm = new EditText(this);
        confirm.setHint(target.name);
        confirm.setHintTextColor(Ui.TEXT_FAINT);
        confirm.setTextColor(Ui.TEXT);
        confirm.setTextSize(14f);
        confirm.setTypeface(Ui.mono());
        confirm.setSingleLine(true);
        confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        confirm.setBackground(Ui.outline(this, Ui.BG_SOFT, 12, Ui.STROKE, 1));
        Ui.pad(confirm, 12, 10, 12, 10, this);
        addTop(card, confirm, 8);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = Ui.button(this, "取消", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        TextView write = Ui.button(this, "确认写入", Ui.CARD_2, Ui.TEXT_FAINT, 0);
        write.setEnabled(false);
        write.setAlpha(0.55f);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams wLp2 = new LinearLayout.LayoutParams(0, WRAP, 1.35f);
        wLp2.leftMargin = Ui.dp(this, 9);
        buttons.addView(cancel, cLp);
        buttons.addView(write, wLp2);
        addTop(card, buttons, 18);

        Dialog d = Ui.dialog(this, sv, true);
        close.setOnClickListener(v -> d.dismiss());
        cancel.setOnClickListener(v -> d.dismiss());
        confirm.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                boolean match = s != null && s.toString().trim().equals(target.name);
                write.setEnabled(match);
                write.setAlpha(match ? 1f : 0.55f);
                write.setTextColor(match ? Ui.ACCENT_DARK : Ui.TEXT_FAINT);
                write.setBackground(match ? Ui.ripple(MainActivity.this, Ui.solid(Ui.ACCENT, 14, MainActivity.this), Ui.DARK ? 0x33FFFFFF : 0x22000000)
                        : Ui.solid(Ui.CARD_2, 14, MainActivity.this));
            }
        });
        write.setOnClickListener(v -> {
            if (!confirm.getText().toString().trim().equals(target.name)) return;
            d.dismiss();
            startFlash(target, info, backupBox.isChecked());
        });
        d.show();
    }

    private void startFlash(Partition target, ImageInfo info, boolean autoBackup) {
        if (busy) return;
        if (!guardIntegrity()) return;
        busy = true;
        Ui.keepScreenOn(this, true);
        ProgressPanel panel = showProgress("正在写入", "准备写入 " + target.name + "…");
        io.execute(() -> {
            String backupPath = null;
            if (autoBackup) {
                main.post(() -> panel.update(0, "1/2 · 正在备份原分区", "读取 " + target.name));
                BackupOutcome backup = backupToFile(target, "preflash_", (done, total) -> {
                    int percent = total > 0 ? (int) Math.min(38, done * 38 / total) : -1;
                    String detail = Ui.size(done) + (total > 0 ? " / " + Ui.size(total) : "");
                    main.post(() -> panel.update(percent, "1/2 · 正在备份原分区", detail));
                });
                if (!backup.ok) {
                    main.post(() -> {
                        panel.dismiss();
                        busy = false;
                        Ui.keepScreenOn(this, false);
                        showError("已中止写入：备份失败",
                                backup.message + "\n\n为保护设备，原分区未被修改。");
                    });
                    return;
                }
                backupPath = backup.path;
                scanFile(backupPath);
            }
            final String finalBackupPath = backupPath;
            int base = autoBackup ? 38 : 0;
            final String writeStage = autoBackup ? "2/2 · 正在写入分区" : "正在写入分区";
            main.post(() -> panel.update(base, writeStage, "准备写入 " + target.name));
            FlashOutcome outcome = flashStream(target, info.uri, info.size, (done, total) -> {
                int span = 100 - base;
                int percent = base + (total > 0 ? (int) Math.min(span, done * span / total) : 0);
                String detail = Ui.size(done) + " / " + Ui.size(total);
                main.post(() -> panel.update(percent, writeStage, detail));
            });
            main.post(() -> {
                panel.dismiss();
                busy = false;
                Ui.keepScreenOn(this, false);
                if (outcome.ok) {
                    showFlashResult(target, info, finalBackupPath);
                } else {
                    String extra = "\n\n分区可能已被部分写入。请勿反复尝试未知镜像；使用写入前的备份恢复，或重新写入确认无误的镜像。";
                    if (finalBackupPath != null) extra += "\n\n原分区备份：\n" + finalBackupPath;
                    showError("写入失败", outcome.message + extra);
                }
            });
        });
    }

    private FlashOutcome flashStream(Partition target, Uri uri, long imageSize, ByteProgress callback) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = RootShell.start("dd of=" + RootShell.q(target.path)
                    + " bs=4M conv=fsync 2>&1; rc=$?; echo __DD_RC__:$rc; exit $rc");
            final Process fp = process;
            Thread reader = new Thread(() -> {
                try (InputStream in = fp.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (output.length() < 16000) {
                            output.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {}
            }, "dd-output");
            reader.start();

            long written = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法打开镜像文件");
                OutputStream os = process.getOutputStream();
                byte[] buf = new byte[1024 * 1024];
                int n;
                long lastUpdate = 0;
                while ((n = in.read(buf)) != -1) {
                    if (n == 0) continue;
                    os.write(buf, 0, n);
                    written += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 120) {
                        lastUpdate = now;
                        callback.onProgress(written, imageSize);
                    }
                }
                os.flush();
                os.close();
            }
            int code = process.waitFor();
            reader.join(2500);
            callback.onProgress(written, imageSize);
            String text = output.toString();
            if (code != 0 || !text.contains("__DD_RC__:0")) {
                String reason = Ui.oneLine(text, 320);
                if (reason.isEmpty()) reason = "dd 退出码 " + code;
                return new FlashOutcome(false, "写入被系统拒绝或中断。\n" + reason);
            }
            if (written != imageSize) {
                return new FlashOutcome(false, "写入字节数与镜像大小不一致（"
                        + written + " / " + imageSize + "），分区可能不完整。");
            }
            return new FlashOutcome(true, "");
        } catch (Exception e) {
            if (process != null) process.destroy();
            return new FlashOutcome(false, friendlyError(e));
        }
    }

    private void showFlashResult(Partition target, ImageInfo info, String backupPath) {
        CappedScrollView sv = new CappedScrollView(this, 0.88f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        addTop(card, statusBadge("✓", Ui.SUCCESS, Ui.CARD_2, Ui.STROKE), 0);
        TextView title = Ui.text(this, "写入完成", 24f, Ui.TEXT, true);
        addTop(card, title, 14);
        TextView msg = Ui.text(this,
                info.name + " 已写入 " + target.name + "（" + Ui.size(info.size) + "）。",
                12f, Ui.TEXT_DIM, false);
        addTop(card, msg, 7);
        TextView note = Ui.text(this,
                "如写入的是 boot、init_boot、dtbo、vbmeta 等启动相关分区，请自行重启设备。若设备无法启动，可进入 Recovery / Fastboot 使用备份恢复。",
                10.5f, Ui.TEXT_FAINT, false);
        note.setLineSpacing(Ui.dp(this, 2), 1.05f);
        addTop(card, note, 10);
        if (backupPath != null) {
            card.addView(Ui.label(this, "写入前备份", Ui.TEXT_FAINT));
            TextView path = codeBlock(backupPath);
            addTop(card, path, 8);
        }
        TextView done = Ui.button(this, "完成", Ui.ACCENT, Ui.ACCENT_DARK, 0);
        addTop(card, done, 18);
        Dialog d = Ui.dialog(this, sv, true);
        done.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void loadAndShowHeader(Partition p) {
        Ui.toast(this, "正在读取分区头部…");
        io.execute(() -> {
            byte[] header = readHeader(p.path, 1024);
            main.post(() -> {
                if (header == null || header.length == 0) {
                    showError("读取失败", "无法读取 " + p.path + " 的头部数据。");
                } else {
                    showHexDialog(p.name, p.path, header);
                }
            });
        });
    }

    private void showHexDialog(String title, String subtitle, byte[] header) {
        CappedScrollView sv = new CappedScrollView(this, 0.86f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout heading = dialogHeading(card, "头部预览", title);
        TextView close = closeButton();
        heading.addView(close);

        TextView path = Ui.text(this, subtitle, 10f, Ui.TEXT_FAINT, false);
        path.setTypeface(Ui.mono());
        addTop(card, path, 8);
        TextView type = Ui.pill(this, Ui.guessType(header), Ui.TEXT, Ui.CARD_2, 0);
        addTop(card, type, 10);

        TextView hex = Ui.text(this, Ui.hexDump(header, 512), 9f, Ui.TEXT_DIM, false);
        hex.setTypeface(Ui.mono());
        hex.setTextIsSelectable(true);
        hex.setLineSpacing(0, 1.15f);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setBackground(Ui.outline(this, Ui.BG_SOFT, 12, Ui.STROKE_SOFT, 1));
        Ui.pad(hScroll, 12, 12, 12, 12, this);
        hScroll.addView(hex);
        addTop(card, hScroll, 12);

        TextView note = Ui.text(this, "仅显示前 512 字节 · 左侧为偏移，右侧为 ASCII", 9.5f, Ui.TEXT_FAINT, false);
        addTop(card, note, 8);
        Dialog d = Ui.dialog(this, sv, true);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private byte[] readHeader(String path, int size) {
        try {
            RootShell.Result r = RootShell.run("dd if=" + RootShell.q(path)
                    + " bs=" + size + " count=1 2>/dev/null | od -An -tx1 -v");
            if (r.exit != 0) return null;
            List<Byte> bytes = new ArrayList<>();
            Matcher m = Pattern.compile("\\b[0-9a-fA-F]{2}\\b").matcher(r.output);
            while (m.find() && bytes.size() < size) {
                bytes.add((byte) Integer.parseInt(m.group(), 16));
            }
            byte[] result = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) result[i] = bytes.get(i);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private void pickImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            showError("没有文件选择器", "系统中没有可用的文件选择器。请安装「文件」应用后重试。");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_FLASH_IMAGE) pendingFlashTarget = null;
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        if (requestCode == REQ_FLASH_IMAGE) {
            prepareFlash(uri);
        } else if (requestCode == REQ_VIEW_IMAGE) {
            Ui.toast(this, "正在读取镜像…");
            io.execute(() -> {
                ImageInfo info = readImageInfo(uri);
                main.post(() -> {
                    if (info == null) {
                        showError("无法读取镜像", "文件无法打开，或读取权限已失效。");
                    } else {
                        showHexDialog(info.name, "镜像文件预览", info.header);
                    }
                });
            });
        }
    }

    private ProgressPanel showProgress(String title, String initialStage) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.outline(this, Ui.CARD, 24, Ui.STROKE, 1));
        Ui.pad(card, 22, 22, 22, 20, this);
        TextView label = Ui.label(this, "进行中", Ui.TEXT_FAINT);
        card.addView(label);
        TextView heading = Ui.text(this, title, 22f, Ui.TEXT, true);
        addTop(card, heading, 8);
        TextView stage = Ui.text(this, initialStage, 11.5f, Ui.TEXT_DIM, false);
        addTop(card, stage, 14);

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.STROKE));
        progressRow.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 7), 1f));
        TextView percent = Ui.text(this, "…", 12f, Ui.ACCENT, true);
        percent.setGravity(Gravity.END);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(Ui.dp(this, 44), WRAP);
        pLp.leftMargin = Ui.dp(this, 12);
        progressRow.addView(percent, pLp);
        addTop(card, progressRow, 12);

        TextView detail = Ui.text(this, "", 10.5f, Ui.TEXT_FAINT, false);
        detail.setTypeface(Ui.mono());
        addTop(card, detail, 8);
        TextView note = Ui.text(this, "请保持应用在前台，不要退出、断电或强行停止。", 10f, Ui.TEXT_FAINT, false);
        addTop(card, note, 14);

        Dialog dialog = Ui.dialog(this, card, false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        return new ProgressPanel(dialog, stage, percent, detail, bar);
    }

    private final class ProgressPanel {
        final Dialog dialog;
        final TextView stage;
        final TextView percent;
        final TextView detail;
        final ProgressBar bar;

        ProgressPanel(Dialog dialog, TextView stage, TextView percent, TextView detail, ProgressBar bar) {
            this.dialog = dialog;
            this.stage = stage;
            this.percent = percent;
            this.detail = detail;
            this.bar = bar;
        }

        void update(int pct, String stageText, String detailText) {
            if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            stage.setText(stageText);
            detail.setText(detailText == null ? "" : detailText);
            if (pct < 0) {
                bar.setIndeterminate(true);
                percent.setText("…");
            } else {
                int clamped = Math.max(0, Math.min(100, pct));
                bar.setIndeterminate(false);
                bar.setProgress(clamped);
                percent.setText(clamped + "%");
            }
        }

        void dismiss() {
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    private void showError(String title, String message) {
        CappedScrollView sv = new CappedScrollView(this, 0.86f);
        LinearLayout card = dialogCard();
        sv.addView(card, new ScrollView.LayoutParams(MATCH, WRAP));
        addTop(card, statusBadge("!", Ui.RED, Ui.CARD_2, Ui.STROKE), 0);
        TextView heading = Ui.text(this, title, 22f, Ui.TEXT, true);
        addTop(card, heading, 14);
        TextView body = Ui.text(this, message == null ? "未知错误" : message, 11.5f, Ui.TEXT_DIM, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(Ui.dp(this, 2), 1.08f);
        addTop(card, body, 9);
        TextView close = Ui.button(this, "关闭", Ui.CARD_2, Ui.TEXT_DIM, Ui.STROKE);
        addTop(card, close, 18);
        Dialog d = Ui.dialog(this, sv, true);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void scanFile(String path) {
        if (path == null) return;
        try {
            MediaScannerConnection.scanFile(this, new String[]{path}, null, null);
        } catch (Exception ignored) {}
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        if (msg.contains("EPIPE")) msg = "写入管道已关闭（dd 可能提前退出）。";
        if (msg.contains("ENOSPC")) msg = "存储空间不足。";
        return msg;
    }

    private static class GlassBar extends LinearLayout {
        private final boolean glass;
        private final View source;
        private Bitmap blurred;
        private int cachedWidth;
        private int cachedHeight;
        private boolean dirty = true;
        private static Bitmap noise;
        private final Runnable captureTask = () -> {
            captureScheduled = false;
            dirty = true;
            invalidate();
        };
        private boolean captureScheduled = false;
        private int selectedTab = 0;
        private float bubbleLeft = -1f;
        private float bubbleRight = -1f;
        private ValueAnimator bubbleAnimator;

        GlassBar(Activity context, boolean glass, View source) {
            super(context);
            this.glass = glass;
            this.source = source;
            setWillNotDraw(!glass);
            if (glass) {
                setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                                view.getHeight() / 2f);
                        outline.setAlpha(0.85f);
                    }
                });
            }
        }

        void requestCapture() {
            if (!glass || captureScheduled) return;
            captureScheduled = true;
            postDelayed(captureTask, 90);
        }

        void setSelectedTab(int tab, boolean animate) {
            if (!glass) return;
            selectedTab = tab;
            if (getWidth() <= 0 || getChildCount() <= tab) {
                post(() -> setSelectedTab(tab, false));
                return;
            }
            View item = getChildAt(tab);
            if (item == null || item.getWidth() <= 0) {
                post(() -> setSelectedTab(tab, false));
                return;
            }
            float targetLeft = item.getLeft() + Ui.dp(getContext(), 5);
            float targetRight = item.getRight() - Ui.dp(getContext(), 5);
            if (bubbleLeft < 0 || !animate) {
                bubbleLeft = targetLeft;
                bubbleRight = targetRight;
                invalidate();
                return;
            }
            if (bubbleAnimator != null) bubbleAnimator.cancel();
            float startLeft = bubbleLeft;
            float startRight = bubbleRight;
            bubbleAnimator = ValueAnimator.ofFloat(0f, 1f);
            bubbleAnimator.setDuration(320);
            bubbleAnimator.setInterpolator(new DecelerateInterpolator(1.7f));
            bubbleAnimator.addUpdateListener(animation -> {
                float f = (float) animation.getAnimatedValue();
                bubbleLeft = startLeft + (targetLeft - startLeft) * f;
                bubbleRight = startRight + (targetRight - startRight) * f;
                invalidate();
            });
            bubbleAnimator.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            dirty = true;
            bubbleLeft = -1f;
            bubbleRight = -1f;
            post(() -> setSelectedTab(selectedTab, false));
        }

        private static Bitmap noiseBitmap() {
            if (noise != null) return noise;
            int size = 96;
            int[] pixels = new int[size * size];
            Random random = new Random(20261121L);
            for (int i = 0; i < pixels.length; i++) {
                int alpha = random.nextInt(13);
                int shade = random.nextBoolean() ? 255 : 0;
                pixels[i] = (alpha << 24) | (shade << 16) | (shade << 8) | shade;
            }
            noise = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            noise.setPixels(pixels, 0, size, 0, 0, size, size);
            return noise;
        }

        private void captureBackdrop() {
            if (!glass) return;
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (source == null) {
                dirty = false;
                return;
            }
            Bitmap raw = null;
            try {
                raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(raw);
                canvas.drawColor(Ui.BG);
                canvas.translate(source.getLeft() - getLeft(), source.getTop() - getTop());
                source.draw(canvas);

                int sw = Math.max(8, w / 8);
                int sh = Math.max(4, h / 8);
                Bitmap small = Bitmap.createScaledBitmap(raw, sw, sh, true);
                int[] pixels = new int[sw * sh];
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh);
                blurPixels(pixels, sw, sh, 2);
                small.setPixels(pixels, 0, sw, 0, 0, sw, sh);
                if (blurred != null) blurred.recycle();
                blurred = small;
                cachedWidth = w;
                cachedHeight = h;
            } catch (Exception ignored) {
                if (blurred != null) {
                    blurred.recycle();
                    blurred = null;
                }
            } finally {
                if (raw != null) raw.recycle();
                dirty = false;
            }
        }

        private static void blurPixels(int[] pixels, int w, int h, int radius) {
            int[] source = pixels.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r = 0, g = 0, b = 0, count = 0;
                    for (int dy = -radius; dy <= radius; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int dx = -radius; dx <= radius; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            int c = source[yy * w + xx];
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            count++;
                        }
                    }
                    pixels[y * w + x] = 0xFF000000
                            | ((r / count) << 16) | ((g / count) << 8) | (b / count);
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!glass) {
                super.onDraw(canvas);
                return;
            }
            if (dirty) captureBackdrop();
            float w = getWidth();
            float h = getHeight();
            float radius = h / 2f;
            RectF rect = new RectF(0, 0, w, h);
            Path path = new Path();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            canvas.save();
            canvas.clipPath(path);

            if (blurred != null) {
                paint.setFilterBitmap(true);
                Rect src = new Rect(0, 0, blurred.getWidth(), blurred.getHeight());
                canvas.drawBitmap(blurred, src, rect, paint);
            }

            int tintTop = Ui.DARK ? 0x9E080808 : 0xC2FFFFFF;
            int tintBottom = Ui.DARK ? 0xB80A0A0A : 0xB8FFFFFF;
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{tintTop, tintBottom}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);

            paint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{Ui.withAlpha(Ui.ACCENT, 0x18), 0x00000000},
                    new float[]{0f, 0.82f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);

            paint.setShader(new BitmapShader(noiseBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            canvas.drawRect(rect, paint);

            paint.setShader(new LinearGradient(0, 0, 0, h * 0.62f,
                    new int[]{Ui.DARK ? 0x38FFFFFF : 0x82FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.62f, paint);

            paint.setShader(new LinearGradient(0, 0, w, h * 0.9f,
                    new int[]{0x00FFFFFF, Ui.DARK ? 0x1CFFFFFF : 0x2EFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);

            paint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                    new int[]{0x00000000, Ui.DARK ? 0x33000000 : 0x0D000000},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, h * 0.55f, w, h, paint);

            if (bubbleLeft >= 0 && bubbleRight > bubbleLeft) {
                float top = Ui.dp(getContext(), 5);
                float bottom = h - Ui.dp(getContext(), 5);
                float br = (bottom - top) / 2f;
                RectF bubble = new RectF(bubbleLeft, top, bubbleRight, bottom);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Ui.DARK ? 0x40000000 : 0x1F000000);
                RectF shadow = new RectF(bubble);
                shadow.offset(0, Ui.dp(getContext(), 2));
                canvas.drawRoundRect(shadow, br, br, paint);

                paint.setShader(new LinearGradient(bubbleLeft, top, bubbleRight, bottom,
                        new int[]{
                                Ui.DARK ? 0x38FFFFFF : 0xFAFFFFFF,
                                Ui.DARK ? 0x16FFFFFF : 0xD8FFFFFF
                        }, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(bubble, br, br, paint);

                paint.setShader(new LinearGradient(bubbleLeft, top, bubbleRight, bottom,
                        new int[]{Ui.withAlpha(Ui.ACCENT, 0x18), 0x00000000},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(bubble, br, br, paint);

                Path bubbleClip = new Path();
                bubbleClip.addRoundRect(bubble, br, br, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(bubbleClip);
                paint.setShader(new android.graphics.RadialGradient(
                        bubbleLeft + (bubbleRight - bubbleLeft) * 0.34f,
                        top + (bottom - top) * 0.18f,
                        Math.max(1f, (bubbleRight - bubbleLeft) * 0.72f),
                        new int[]{Ui.DARK ? 0x38FFFFFF : 0x8AFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(bubble, paint);
                canvas.restore();

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1));
                paint.setShader(null);
                paint.setColor(Ui.DARK ? 0x45FFFFFF : 0xE8FFFFFF);
                canvas.drawRoundRect(bubble, br, br, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1));
                paint.setColor(Ui.DARK ? 0x30FFFFFF : 0x90FFFFFF);
                canvas.drawLine(bubbleLeft + br * 0.8f, top + Ui.dp(getContext(), 1.2f),
                        bubbleRight - br * 0.8f, top + Ui.dp(getContext(), 1.2f), paint);
            }
            canvas.restore();

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 1));
            paint.setColor(Ui.DARK ? 0x24FFFFFF : 0x10000000);
            RectF border = new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f);
            canvas.drawRoundRect(border, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 1));
            paint.setShader(new LinearGradient(0, 0, w, 0, new int[]{
                    Ui.withAlpha(Ui.ACCENT, 0x22),
                    Ui.DARK ? 0x90FFFFFF : 0xE0FFFFFF,
                    Ui.withAlpha(Ui.ACCENT, 0x22)
            }, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawLine(radius * 0.65f, Ui.dp(getContext(), 1.4f),
                    w - radius * 0.65f, Ui.dp(getContext(), 1.4f), paint);
            paint.setShader(null);
        }
    }

    private static class CappedScrollView extends ScrollView {
        private final float fraction;

        CappedScrollView(Activity context, float fraction) {
            super(context);
            this.fraction = fraction;
            setFillViewport(false);
            setVerticalScrollBarEnabled(false);
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int hMode = MeasureSpec.getMode(heightMeasureSpec);
            int hSize = MeasureSpec.getSize(heightMeasureSpec);
            int cap = (int) (getResources().getDisplayMetrics().heightPixels * fraction);
            int limit = hMode == MeasureSpec.UNSPECIFIED ? cap : Math.min(hSize, cap);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }
}
