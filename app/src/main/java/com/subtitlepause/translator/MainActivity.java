package com.subtitlepause.translator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 8801;
    private static final int REQ_POST_NOTIFICATIONS = 8802;
    private static final int REQ_OVERLAY_PERMISSION = 8803;

    public static final String PREFS_NAME = "subtitle_translator_prefs";
    public static final String PREF_MINIMAX_API_KEY = "minimax_api_key";
    public static final String PREF_MINIMAX_MODEL = "minimax_model";
    public static final String PREF_MINIMAX_ENDPOINT = "minimax_endpoint";

    public static final String DEFAULT_MINIMAX_MODEL = "MiniMax-M3";
    public static final String DEFAULT_MINIMAX_ENDPOINT = "https://api.minimaxi.com/v1/text/chatcompletion_v2";

    private MediaProjectionManager projectionManager;
    private TextView statusText;
    private EditText apiKeyInput;
    private EditText modelInput;
    private EditText endpointInput;
    private LinearLayout favoritesList;
    private LinearLayout favoritePreviewContainer;
    private ImageView favoritePreviewImage;
    private final Set<String> selectedFavoritePaths = new HashSet<>();
    private String currentFavoriteImagePath;
    private boolean showingFavoritesPage = false;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean waitingForOverlayPermission = false;
    private boolean pendingStartAfterOverlayPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        showingFavoritesPage = false;
        setContentView(createContentView());
        updateStatus();
    }

    private View createContentView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(8, 12, 18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("拂月译屏 · MiniMax 版");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(235, 240, 248));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = new TextView(this);
        intro.setText("暂停视频后轻点“幕”悬浮珠，可选择：\n\n译：调用 MiniMax，只输出精简中文\n学：调用 MiniMax，只输出中文、重点词、语法\n+ / -：调整字幕识别区域\n\nMiniMax API Key 保存在本机 App 私有存储里，不会写入源码。现在“译”和“学”都走 MiniMax，请先在下面填写并保存 API Key。");
        intro.setTextSize(17);
        intro.setTextColor(Color.rgb(218, 226, 238));
        intro.setLineSpacing(5, 1.0f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.setMargins(0, dp(18), 0, dp(18));
        root.addView(intro, introLp);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTextColor(Color.rgb(170, 195, 226));
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, 0, 0, dp(16));
        root.addView(statusText, statusLp);

        root.addView(createSettingsPanel(), new LinearLayout.LayoutParams(-1, -2));

        Button favoritesButton = createButton("我的收藏");
        favoritesButton.setOnClickListener(v -> openFavoritesPage());
        root.addView(favoritesButton, buttonLp());

        Button overlayButton = createButton("1. 授权悬浮窗");
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlayButton, buttonLp());

        Button notifyButton = createButton("2. 允许通知 / 前台服务");
        notifyButton.setOnClickListener(v -> requestNotificationPermission());
        root.addView(notifyButton, buttonLp());

        Button startButton = createButton("3. 启动悬浮翻译器");
        startButton.setTextSize(20);
        startButton.setOnClickListener(v -> startCaptureFlow());
        root.addView(startButton, buttonLp());

        Button stopButton = createButton("关闭悬浮翻译器");
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, SubtitleOverlayService.class));
            Toast.makeText(this, "已请求关闭悬浮窗", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopButton, buttonLp());

        TextView tips = new TextView(this);
        tips.setText("使用方式：打开视频 → 暂停在不懂的字幕处 → 轻点“幕”悬浮珠 → 点“译”只看精简中文，点“学”看中文、重点词和语法，点“藏”保存。悬浮窗会先尝试暂停播放器，并可用 0-2 秒回溯滑条选择字幕画面，避免刚点时字幕已经错过。回到 App 后点“我的收藏”单独查看。需要网络和 MiniMax API Key。部分 DRM 流媒体可能禁止截图。 ");
        tips.setTextSize(15);
        tips.setTextColor(Color.rgb(192, 202, 216));
        tips.setLineSpacing(4, 1.0f);
        LinearLayout.LayoutParams tipsLp = new LinearLayout.LayoutParams(-1, -2);
        tipsLp.setMargins(0, dp(18), 0, 0);
        root.addView(tips, tipsLp);

        return scroll;
    }

    private void openFavoritesPage() {
        showingFavoritesPage = true;
        setContentView(createFavoritesPageView());
    }

    private View createFavoritesPageView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(8, 12, 18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("我的收藏");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(235, 240, 248));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        Button backButton = createButton("返回主界面");
        backButton.setOnClickListener(v -> returnMainPage());
        root.addView(backButton, buttonLp());

        root.addView(createFavoritesPanel(), new LinearLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private void returnMainPage() {
        showingFavoritesPage = false;
        currentFavoriteImagePath = null;
        setContentView(createContentView());
        updateStatus();
    }

    @Override
    public void onBackPressed() {
        if (showingFavoritesPage) {
            returnMainPage();
        } else {
            super.onBackPressed();
        }
    }

    private View createFavoritesPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(190, 13, 20, 31));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(120, 201, 165, 92));
        panel.setBackground(bg);

        TextView tip = new TextView(this);
        tip.setText("收藏列表按“截图缩略图 + 识别原文”显示，方便区分是哪一句。点条目查看完整学习卡片和截图，点截图可全屏放大。");
        tip.setTextSize(13);
        tip.setTextColor(Color.rgb(168, 184, 204));
        tip.setLineSpacing(3, 1f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.setMargins(0, dp(6), 0, dp(8));
        panel.addView(tip, tipLp);

        Button refreshButton = createButton("刷新收藏列表");
        refreshButton.setOnClickListener(v -> loadFavoritesList());
        panel.addView(refreshButton, buttonLp());

        LinearLayout manageRow = new LinearLayout(this);
        manageRow.setOrientation(LinearLayout.HORIZONTAL);

        Button deleteSelectedButton = createButton("删除选中");
        deleteSelectedButton.setTextSize(14);
        deleteSelectedButton.setOnClickListener(v -> confirmDeleteSelectedFavorites());
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        deleteLp.setMargins(0, dp(4), dp(5), dp(4));
        manageRow.addView(deleteSelectedButton, deleteLp);

        Button clearAllButton = createButton("清空全部");
        clearAllButton.setTextSize(14);
        clearAllButton.setOnClickListener(v -> confirmClearAllFavoritesStep1());
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        clearLp.setMargins(dp(5), dp(4), 0, dp(4));
        manageRow.addView(clearAllButton, clearLp);

        panel.addView(manageRow, new LinearLayout.LayoutParams(-1, -2));

        favoritesList = new LinearLayout(this);
        favoritesList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(favoritesList, new LinearLayout.LayoutParams(-1, -2));

        favoritePreviewContainer = new LinearLayout(this);
        favoritePreviewContainer.setOrientation(LinearLayout.VERTICAL);
        favoritePreviewContainer.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, -2);
        previewLp.setMargins(0, dp(8), 0, dp(8));
        panel.addView(favoritePreviewContainer, previewLp);
        renderEmptyFavoritePreview();

        favoritePreviewImage = new ImageView(this);
        favoritePreviewImage.setAdjustViewBounds(true);
        favoritePreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        favoritePreviewImage.setMaxHeight(dp(420));
        favoritePreviewImage.setBackgroundColor(Color.argb(70, 0, 0, 0));
        panel.addView(favoritePreviewImage, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(16));
        panel.setLayoutParams(lp);

        loadFavoritesList();
        return panel;
    }

    private void loadFavoritesList() {
        if (favoritesList == null) return;
        favoritesList.removeAllViews();

        File[] files = findFavoriteTextFiles();
        if (files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无收藏。点悬浮窗里的“学”生成学习卡片后，再点“藏”。");
            empty.setTextSize(13);
            empty.setTextColor(Color.rgb(168, 184, 204));
            empty.setPadding(0, dp(6), 0, dp(6));
            favoritesList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        HashSet<String> existingPaths = new HashSet<>();
        for (File file : files) existingPaths.add(file.getAbsolutePath());
        selectedFavoritePaths.retainAll(existingPaths);

        for (File file : files) {
            LinearLayout row = createFavoriteListRow(file);
            favoritesList.addView(row, favoriteRowLp());
        }
    }

    private LinearLayout createFavoriteListRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(110, 9, 18, 30));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.argb(80, 102, 174, 246));
        row.setBackground(bg);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText("");
        checkBox.setChecked(selectedFavoritePaths.contains(file.getAbsolutePath()));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedFavoritePaths.add(file.getAbsolutePath());
            } else {
                selectedFavoritePaths.remove(file.getAbsolutePath());
            }
        });
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(44), dp(62)));

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Color.argb(90, 0, 0, 0));
        File imageFile = guessFavoriteImageFile(file);
        if (imageFile != null && imageFile.exists()) {
            thumb.setImageBitmap(BitmapFactory.decodeFile(imageFile.getAbsolutePath()));
        }
        row.addView(thumb, new LinearLayout.LayoutParams(dp(88), dp(58)));

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(10), 0, 0, 0);

        TextView original = new TextView(this);
        original.setText(buildFavoriteOriginalTitle(file));
        original.setTextColor(Color.rgb(236, 242, 250));
        original.setTextSize(14);
        original.setTypeface(Typeface.DEFAULT_BOLD);
        original.setMaxLines(2);
        original.setEllipsize(TextUtils.TruncateAt.END);
        textBox.addView(original, new LinearLayout.LayoutParams(-1, -2));

        TextView time = new TextView(this);
        time.setText(formatFavoriteTime(file));
        time.setTextColor(Color.rgb(142, 164, 190));
        time.setTextSize(11);
        time.setMaxLines(1);
        time.setEllipsize(TextUtils.TruncateAt.END);
        textBox.addView(time, new LinearLayout.LayoutParams(-1, -2));

        row.addView(textBox, new LinearLayout.LayoutParams(0, dp(62), 1f));

        View.OnClickListener open = v -> showFavoriteFile(file);
        row.setOnClickListener(open);
        thumb.setOnClickListener(open);
        original.setOnClickListener(open);
        time.setOnClickListener(open);
        textBox.setOnClickListener(open);

        return row;
    }

    private String buildFavoriteOriginalTitle(File file) {
        try {
            String content = readTextFile(file);
            String original = extractOriginalSubtitle(content);
            if (!original.isEmpty()) return original.replace("\\n", " ");
        } catch (Exception ignored) {
        }
        return "未读取到识别原文";
    }

    private LinearLayout.LayoutParams favoriteRowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(6));
        return lp;
    }

    private File[] findFavoriteTextFiles() {
        File externalDir = getExternalFilesDir("SubtitleFavorites");
        File internalDir = new File(getFilesDir(), "SubtitleFavorites");

        File[] external = externalDir != null && externalDir.exists()
                ? externalDir.listFiles((dir, name) -> name.endsWith(".txt"))
                : null;
        File[] internal = internalDir.exists()
                ? internalDir.listFiles((dir, name) -> name.endsWith(".txt"))
                : null;

        int externalCount = external == null ? 0 : external.length;
        int internalCount = internal == null ? 0 : internal.length;
        File[] all = new File[externalCount + internalCount];
        int index = 0;
        if (external != null) {
            for (File f : external) all[index++] = f;
        }
        if (internal != null) {
            for (File f : internal) all[index++] = f;
        }

        Arrays.sort(all, (a, b) -> b.getName().compareTo(a.getName()));
        return all;
    }

    private void showFavoriteFile(File textFile) {
        try {
            String content = readTextFile(textFile);
            File jsonFile = guessFavoriteJsonFile(textFile);
            String favoriteTime = formatFavoriteTime(textFile);
            if (jsonFile != null && jsonFile.exists()) {
                renderPrettyFavorite(content, readTextFile(jsonFile), favoriteTime);
            } else {
                renderFavoriteFallback(content, favoriteTime);
            }

            File imageFile = guessFavoriteImageFile(textFile);
            if (imageFile != null && imageFile.exists()) {
                currentFavoriteImagePath = imageFile.getAbsolutePath();
                favoritePreviewImage.setImageBitmap(BitmapFactory.decodeFile(currentFavoriteImagePath));
                favoritePreviewImage.setVisibility(View.VISIBLE);
                favoritePreviewImage.setOnClickListener(v -> showFavoriteImageFullscreen());
            } else {
                currentFavoriteImagePath = null;
                favoritePreviewImage.setImageDrawable(null);
                favoritePreviewImage.setVisibility(View.GONE);
                favoritePreviewImage.setOnClickListener(null);
            }
        } catch (Exception e) {
            currentFavoriteImagePath = null;
            renderFavoriteError("读取收藏失败：" + e.getMessage());
            favoritePreviewImage.setImageDrawable(null);
            favoritePreviewImage.setVisibility(View.GONE);
            favoritePreviewImage.setOnClickListener(null);
        }
    }

    private void renderEmptyFavoritePreview() {
        if (favoritePreviewContainer == null) return;
        favoritePreviewContainer.removeAllViews();
        addFavoriteSimpleCard("还没有选择收藏。", false);
    }

    private void renderFavoriteError(String message) {
        if (favoritePreviewContainer == null) return;
        favoritePreviewContainer.removeAllViews();
        addFavoriteSectionTitle("读取失败");
        addFavoriteSimpleCard(message, false);
    }

    private void renderFavoriteFallback(String content, String favoriteTime) {
        if (favoritePreviewContainer == null) return;
        favoritePreviewContainer.removeAllViews();
        addFavoriteSectionTitle("收藏时间");
        addFavoriteSimpleCard(favoriteTime, true);
        addFavoriteSectionTitle("收藏内容");
        addFavoriteSimpleCard(content, false);
        addFavoriteSimpleCard("提示：这条可能是旧收藏，没有 JSON 数据，所以按普通卡片显示。新收藏会自动美化为“中文 / 重点词 / 语法”。", false);
    }

    private void renderPrettyFavorite(String savedText, String jsonContent, String favoriteTime) {
        if (favoritePreviewContainer == null) return;
        favoritePreviewContainer.removeAllViews();

        try {
            JSONObject json = new JSONObject(extractJsonObject(jsonContent));
            addFavoriteSectionTitle("收藏时间");
            addFavoriteSimpleCard(favoriteTime, true);

            String original = extractOriginalSubtitle(savedText);
            if (!original.isEmpty()) {
                addFavoriteSectionTitle("识别原文");
                addFavoriteSimpleCard(original, false);
            }

            String chinese = json.optString("中文", "").trim();
            addFavoriteSectionTitle("中文");
            addFavoriteSimpleCard(chinese.isEmpty() ? "未保存中文翻译" : chinese, true);

            addFavoriteSectionTitle("重点词");
            JSONArray words = json.optJSONArray("重点词");
            if (words == null || words.length() == 0) {
                addFavoriteSimpleCard("未保存重点词", false);
            } else {
                for (int i = 0; i < words.length(); i++) {
                    JSONObject item = words.optJSONObject(i);
                    if (item == null) continue;

                    String word = item.optString("词", "").trim();
                    String meaning = item.optString("中文", "").trim();
                    String pos = item.optString("词性", "").trim();
                    String note = item.optString("说明", "").trim();

                    LinearLayout card = createFavoriteCardLayout();
                    card.addView(createFavoriteCardTitle(word.isEmpty() ? "词语" : word), new LinearLayout.LayoutParams(-1, -2));

                    StringBuilder line = new StringBuilder();
                    if (!meaning.isEmpty()) line.append("中文：").append(meaning);
                    if (!pos.isEmpty()) {
                        if (line.length() > 0) line.append("　");
                        line.append("词性：").append(pos);
                    }
                    if (line.length() > 0) card.addView(createFavoriteCardBody(line.toString()), new LinearLayout.LayoutParams(-1, -2));
                    if (!note.isEmpty()) card.addView(createFavoriteCardBody("说明：" + note), new LinearLayout.LayoutParams(-1, -2));
                    favoritePreviewContainer.addView(card, favoriteCardLp());
                }
            }

            addFavoriteSectionTitle("语法");
            JSONArray grammar = json.optJSONArray("语法");
            if (grammar == null || grammar.length() == 0) {
                addFavoriteSimpleCard("未保存语法点", false);
            } else {
                for (int i = 0; i < grammar.length(); i++) {
                    JSONObject item = grammar.optJSONObject(i);
                    if (item == null) continue;

                    String point = item.optString("语法", "").trim();
                    String meaning = item.optString("中文解释", "").trim();
                    String note = item.optString("说明", "").trim();

                    LinearLayout card = createFavoriteCardLayout();
                    card.addView(createFavoriteCardTitle(point.isEmpty() ? "语法点" : point), new LinearLayout.LayoutParams(-1, -2));
                    if (!meaning.isEmpty()) card.addView(createFavoriteCardBody("解释：" + meaning), new LinearLayout.LayoutParams(-1, -2));
                    if (!note.isEmpty()) card.addView(createFavoriteCardBody("本句：" + note), new LinearLayout.LayoutParams(-1, -2));
                    favoritePreviewContainer.addView(card, favoriteCardLp());
                }
            }
        } catch (Exception e) {
            renderFavoriteFallback(savedText, favoriteTime);
        }
    }

    private String formatFavoriteTime(File textFile) {
        String parsed = parseFavoriteTimeFromName(textFile == null ? "" : textFile.getName());
        if (!parsed.isEmpty()) return parsed;
        if (textFile != null) {
            String fromModified = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date(textFile.lastModified()));
            return fromModified;
        }
        return "未知时间";
    }

    private String parseFavoriteTimeFromName(String fileName) {
        if (fileName == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("favorite_(\\d{8})_(\\d{6})")
                .matcher(fileName);
        if (!matcher.find()) return "";
        String date = matcher.group(1);
        String time = matcher.group(2);
        return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
                + " " + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6);
    }

    private String extractOriginalSubtitle(String savedText) {
        if (savedText == null) return "";
        String marker = "识别原文：";
        String next = "学习卡片：";
        int start = savedText.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = savedText.indexOf(next, start);
        if (end < 0) end = savedText.length();
        return savedText.substring(start, end).trim();
    }

    private String extractJsonObject(String content) {
        if (content == null) return "{}";
        String text = content.trim();
        text = text.replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void addFavoriteSectionTitle(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(Color.rgb(154, 205, 255));
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(10), 0, dp(4));
        favoritePreviewContainer.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addFavoriteSimpleCard(String text, boolean primary) {
        LinearLayout card = createFavoriteCardLayout();
        TextView body = createFavoriteCardBody(text);
        body.setTextSize(primary ? 15 : 13);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        favoritePreviewContainer.addView(card, favoriteCardLp());
    }

    private LinearLayout createFavoriteCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(128, 9, 18, 30));
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), Color.argb(90, 102, 174, 246));
        card.setBackground(bg);
        return card;
    }

    private TextView createFavoriteCardTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(244, 229, 191));
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView createFavoriteCardBody(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextColor(Color.rgb(232, 240, 250));
        view.setTextSize(13);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(2), 0, 0);
        return view;
    }

    private LinearLayout.LayoutParams favoriteCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(5));
        return lp;
    }

    private void showFavoriteImageFullscreen() {
        if (currentFavoriteImagePath == null || currentFavoriteImagePath.trim().isEmpty()) {
            Toast.makeText(this, "没有可预览的截图", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        imageView.setImageBitmap(BitmapFactory.decodeFile(currentFavoriteImagePath));
        imageView.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(imageView);
        dialog.show();
        Toast.makeText(this, "轻点截图退出全屏预览", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteSelectedFavorites() {
        if (selectedFavoritePaths.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的收藏", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("删除选中收藏？")
                .setMessage("将删除已勾选的 " + selectedFavoritePaths.size() + " 条收藏，包括学习内容、JSON 和截图。")
                .setPositiveButton("删除", (dialog, which) -> deleteSelectedFavorites())
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSelectedFavorites() {
        int count = 0;
        for (String path : new HashSet<>(selectedFavoritePaths)) {
            File file = new File(path);
            if (deleteFavoriteBundle(file)) count++;
        }
        selectedFavoritePaths.clear();
        currentFavoriteImagePath = null;
        if (favoritePreviewImage != null) {
            favoritePreviewImage.setImageDrawable(null);
            favoritePreviewImage.setVisibility(View.GONE);
        }
        renderEmptyFavoritePreview();
        loadFavoritesList();
        Toast.makeText(this, "已删除 " + count + " 条收藏", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearAllFavoritesStep1() {
        File[] files = findFavoriteTextFiles();
        if (files.length == 0) {
            Toast.makeText(this, "暂无收藏可清空", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("清空全部收藏？")
                .setMessage("即将删除全部 " + files.length + " 条收藏，包括学习内容、JSON 和截图。此操作不能撤销。")
                .setPositiveButton("继续", (dialog, which) -> confirmClearAllFavoritesStep2(files.length))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearAllFavoritesStep2(int count) {
        new AlertDialog.Builder(this)
                .setTitle("再次确认")
                .setMessage("真的要清空全部 " + count + " 条收藏吗？删除后无法恢复。")
                .setPositiveButton("确认清空", (dialog, which) -> clearAllFavorites())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllFavorites() {
        File[] files = findFavoriteTextFiles();
        int count = 0;
        for (File file : files) {
            if (deleteFavoriteBundle(file)) count++;
        }
        selectedFavoritePaths.clear();
        currentFavoriteImagePath = null;
        if (favoritePreviewImage != null) {
            favoritePreviewImage.setImageDrawable(null);
            favoritePreviewImage.setVisibility(View.GONE);
        }
        renderEmptyFavoritePreview();
        loadFavoritesList();
        Toast.makeText(this, "已清空 " + count + " 条收藏", Toast.LENGTH_SHORT).show();
    }

    private boolean deleteFavoriteBundle(File textFile) {
        if (textFile == null) return false;
        boolean existed = textFile.exists();

        File image = guessFavoriteImageFile(textFile);
        File json = guessFavoriteJsonFile(textFile);
        if (image != null && image.exists()) image.delete();
        if (json != null && json.exists()) json.delete();
        if (textFile.exists()) textFile.delete();

        return existed;
    }

    private String readTextFile(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        reader.close();
        return builder.toString().trim();
    }

    private File guessFavoriteImageFile(File textFile) {
        String name = textFile.getName();
        if (name.endsWith(".txt")) {
            File image = new File(textFile.getParentFile(), name.substring(0, name.length() - 4) + ".png");
            if (image.exists()) return image;
        }
        return null;
    }

    private File guessFavoriteJsonFile(File textFile) {
        String name = textFile.getName();
        if (name.endsWith(".txt")) {
            File json = new File(textFile.getParentFile(), name.substring(0, name.length() - 4) + ".json");
            if (json.exists()) return json;
        }
        return null;
    }

    private LinearLayout.LayoutParams compactButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private View createSettingsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(210, 15, 23, 36));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(140, 102, 174, 246));
        panel.setBackground(bg);

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("MiniMax 设置");
        settingsTitle.setTextColor(Color.rgb(236, 240, 248));
        settingsTitle.setTextSize(17);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(settingsTitle, new LinearLayout.LayoutParams(-1, -2));

        apiKeyInput = createInput("填写你的 MiniMax API Key", true);
        apiKeyInput.setText(prefs.getString(PREF_MINIMAX_API_KEY, ""));
        panel.addView(apiKeyInput, inputLp());

        modelInput = createInput("模型，默认 MiniMax-M3", false);
        modelInput.setText(prefs.getString(PREF_MINIMAX_MODEL, DEFAULT_MINIMAX_MODEL));
        panel.addView(modelInput, inputLp());

        endpointInput = createInput("接口地址", false);
        endpointInput.setText(prefs.getString(PREF_MINIMAX_ENDPOINT, DEFAULT_MINIMAX_ENDPOINT));
        panel.addView(endpointInput, inputLp());

        Button saveButton = createButton("保存 MiniMax 设置");
        saveButton.setOnClickListener(v -> saveMiniMaxSettings());
        panel.addView(saveButton, buttonLp());

        TextView note = new TextView(this);
        note.setText("提示：现在“译”和“学”都使用 MiniMax；OCR 识别仍在本机完成。默认 MiniMax-M3，代码会自动关闭 thinking。API Key 不建议提交到 GitHub。 ");
        note.setTextSize(13);
        note.setTextColor(Color.rgb(168, 184, 204));
        note.setLineSpacing(3, 1f);
        panel.addView(note, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(16));
        panel.setLayoutParams(lp);
        return panel;
    }

    private EditText createInput(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(Color.rgb(238, 242, 248));
        input.setHintTextColor(Color.rgb(125, 145, 170));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(180, 7, 13, 22));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(110, 84, 135, 190));
        input.setBackground(bg);
        return input;
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(10), 0, 0);
        return lp;
    }

    private Button createButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(18);
        button.setTextColor(Color.rgb(236, 242, 248));
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }

    private void saveMiniMaxSettings() {
        String apiKey = apiKeyInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        String endpoint = endpointInput.getText().toString().trim();
        if (model.isEmpty()) model = DEFAULT_MINIMAX_MODEL;
        if (endpoint.isEmpty()) endpoint = DEFAULT_MINIMAX_ENDPOINT;
        prefs.edit()
                .putString(PREF_MINIMAX_API_KEY, apiKey)
                .putString(PREF_MINIMAX_MODEL, model)
                .putString(PREF_MINIMAX_ENDPOINT, endpoint)
                .apply();
        Toast.makeText(this, apiKey.isEmpty() ? "已保存；翻译和学习都需要 API Key" : "MiniMax 设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void requestOverlayPermission() {
        if (hasOverlayPermission()) {
            waitingForOverlayPermission = false;
            Toast.makeText(this, "悬浮窗权限已允许", Toast.LENGTH_SHORT).show();
            updateStatus();
            if (pendingStartAfterOverlayPermission) {
                pendingStartAfterOverlayPermission = false;
                launchScreenCapturePermission();
            }
            return;
        }

        waitingForOverlayPermission = true;
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
        } catch (Exception e) {
            startActivity(intent);
        }
        Toast.makeText(this, "允许悬浮窗后返回 App，会自动刷新权限状态", Toast.LENGTH_LONG).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
        } else {
            Toast.makeText(this, "当前系统不需要单独授权通知", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCaptureFlow() {
        saveMiniMaxSettings();
        if (!hasOverlayPermission()) {
            pendingStartAfterOverlayPermission = true;
            Toast.makeText(this, "请先允许悬浮窗权限；返回后会自动继续启动", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        launchScreenCapturePermission();
    }

    private void launchScreenCapturePermission() {
        if (projectionManager == null) {
            projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            checkOverlayPermissionAfterReturn(true);
            return;
        }

        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, SubtitleOverlayService.class);
                serviceIntent.setAction(SubtitleOverlayService.ACTION_START);
                serviceIntent.putExtra(SubtitleOverlayService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(SubtitleOverlayService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "悬浮翻译器已启动", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "未获得屏幕录制权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (waitingForOverlayPermission || pendingStartAfterOverlayPermission) {
            scheduleOverlayPermissionRecheck();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateStatus() {
        if (statusText == null) return;
        boolean overlay = hasOverlayPermission();
        String key = prefs.getString(PREF_MINIMAX_API_KEY, "");
        String waiting = (waitingForOverlayPermission || pendingStartAfterOverlayPermission)
                ? "\n授权返回检测：等待系统刷新中"
                : "";
        statusText.setText("悬浮窗权限：" + (overlay ? "已允许" : "未允许")
                + "\nMiniMax API Key：" + (key == null || key.isEmpty() ? "未填写" : "已保存")
                + waiting
                + "\n建议：把“幕”悬浮珠拖到屏幕侧边，暂停时轻点展开扇形菜单。");
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void scheduleOverlayPermissionRecheck() {
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> checkOverlayPermissionAfterReturn(false), 150);
        mainHandler.postDelayed(() -> checkOverlayPermissionAfterReturn(false), 600);
        mainHandler.postDelayed(() -> checkOverlayPermissionAfterReturn(false), 1200);
    }

    private void checkOverlayPermissionAfterReturn(boolean fromActivityResult) {
        updateStatus();
        if (!waitingForOverlayPermission && !pendingStartAfterOverlayPermission) return;

        if (hasOverlayPermission()) {
            boolean shouldContinueStart = pendingStartAfterOverlayPermission;
            waitingForOverlayPermission = false;
            pendingStartAfterOverlayPermission = false;
            updateStatus();
            Toast.makeText(this, "悬浮窗权限已允许", Toast.LENGTH_SHORT).show();

            if (shouldContinueStart) {
                mainHandler.postDelayed(this::launchScreenCapturePermission, 180);
            }
        } else if (fromActivityResult) {
            Toast.makeText(this, "还没有检测到悬浮窗权限；如果已开启，请返回 App 或再点启动", Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
