package com.subtitlepause.translator;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.AudioManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class SubtitleOverlayService extends Service {
    public static final String ACTION_START = "com.subtitlepause.translator.START";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "subtitle_translator_running";
    private static final int NOTIFICATION_ID = 1001;
    private static final int BACKTRACK_BUFFER_MAX = 8;
    private static final long BACKTRACK_CAPTURE_INTERVAL_MS = 250L;


    private WindowManager windowManager;
    private FrameLayout overlayView;
    private WindowManager.LayoutParams overlayParams;
    private TextView bubbleView;
    private TextView statusText;
    private TextView resultText;
    private TextView backtrackLabel;
    private SeekBar backtrackSeekBar;
    private ScrollView resultScroll;
    private LinearLayout resultContainer;
    private View infoCard;

    private FrameLayout visualOverlayView;
    private WindowManager.LayoutParams visualOverlayParams;
    private LinearLayout backtrackPreviewPanel;
    private ImageView backtrackPreviewImage;
    private TextView backtrackPreviewLabel;
    private TextView backtrackPreviewInfo;
    private Bitmap backtrackPreviewBitmap;
    private View areaGuideRect;
    private TextView areaGuideLabel;

    private View btnTranslate;
    private View btnLearn;
    private View btnFavorite;
    private View btnAreaMenu;
    private View btnAreaPlus;
    private View btnAreaMinus;
    private View btnAreaUp;
    private View btnAreaDown;
    private View btnClose;

    private String lastLearningSubtitle;
    private String lastLearningRawContent;
    private String lastLearningDisplayText;
    private String lastLearningScreenshotTempPath;
    private String pendingLearningFullScreenshotTempPath;

    private final Object subtitleBufferLock = new Object();
    private final ArrayList<BufferedSubtitleFrame> subtitleBacktrackBuffer = new ArrayList<>();
    private boolean subtitleBacktrackRunning = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextRecognizer latinRecognizer;
    private TextRecognizer japaneseRecognizer;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private float subtitleAreaPercent = 0.26f;
    private float subtitleAreaBottomOffsetPercent = 0.08f;
    private int manualBacktrackMs = 800;
    private long selectedBacktrackFrameTimestampMs = -1L;
    private long backtrackReferenceTimestampMs = -1L;
    private boolean backtrackControlsVisible = false;
    private boolean areaGuideVisible = false;
    private boolean areaSubmenuExpanded = false;
    private boolean busy = false;
    private boolean expanded = false;
    private boolean hasResult = false;

    private final Runnable dimRunnable = () -> {
        if (!expanded && bubbleView != null && !busy) {
            bubbleView.animate().alpha(0.22f).setDuration(280).start();
        }
    };

    private final Runnable autoCollapseRunnable = () -> {
        if (expanded && !busy && !hasResult) {
            playPlaybackIfPossible();
            collapsePanel();
        }
    };

    private final Runnable hideAreaGuideRunnable = this::hideAreaGuideOverlay;

    private final Runnable subtitleBacktrackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!subtitleBacktrackRunning || captureHandler == null) return;
            captureSubtitleBufferFrame();
            if (subtitleBacktrackRunning && captureHandler != null) {
                captureHandler.postDelayed(this, BACKTRACK_CAPTURE_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captureThread = new HandlerThread("subtitle_capture_thread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        japaneseRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startProjection(resultCode, resultData);
            showOverlay();
        }
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            showToast("未获得屏幕录制授权，请在系统弹窗中点击允许");
            stopSelf();
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            showToast("系统没有返回有效的屏幕录制会话，请重新点击启动悬浮翻译器");
            stopSelf();
            return;
        }
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                releaseProjection();
                showToast("屏幕录制已停止");
            }
        }, captureHandler);

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "SubtitleTranslatorCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
        startSubtitleBacktrackBuffer();
    }

    private void showOverlay() {
        if (overlayView != null) return;

        overlayView = new FrameLayout(this);

        infoCard = createInfoCard();
        infoCard.setVisibility(View.GONE);
        overlayView.addView(infoCard);

        btnTranslate = createMenuButton("译", true);
        btnLearn = createMenuButton("学", true);
        btnFavorite = createMenuButton("藏", false);
        btnAreaMenu = createMenuButton("区", false);
        btnAreaPlus = createMenuButton("+", false);
        btnAreaMinus = createMenuButton("-", false);
        btnAreaUp = createMenuButton("上", false);
        btnAreaDown = createMenuButton("下", false);
        btnClose = createMenuButton("关", false);

        placeFanButton(btnTranslate, 54, 8);
        placeFanButton(btnLearn, 14, 48);
        placeFanButton(btnFavorite, 10, 90);
        placeFanButton(btnAreaMenu, 58, 78);
        placeFanButton(btnClose, 126, 8);

        placeAreaSubButton(btnAreaPlus, 104, 100);
        placeAreaSubButton(btnAreaMinus, 142, 82);
        placeAreaSubButton(btnAreaUp, 90, 138);
        placeAreaSubButton(btnAreaDown, 52, 126);

        attachTranslateLearnHoldDrag(btnTranslate, false);
        attachTranslateLearnHoldDrag(btnLearn, true);
        btnFavorite.setOnClickListener(v -> {
            pingOverlay();
            hideBacktrackControls();
            saveCurrentLearningFavorite();
        });
        btnAreaMenu.setOnClickListener(v -> {
            pingOverlay();
            hideBacktrackControls();
            setAreaSubmenuVisibility(!areaSubmenuExpanded);
            if (areaSubmenuExpanded) {
                setStatus("OCR 区域：+/- 调高度，上/下移动位置");
                showAreaGuideOverlay();
                if (expanded && infoCard != null) infoCard.setVisibility(View.VISIBLE);
            }
        });
        btnAreaPlus.setOnClickListener(v -> {
            pingOverlay();
            hideBacktrackControls();
            adjustArea(0.04f);
        });
        btnAreaMinus.setOnClickListener(v -> {
            pingOverlay();
            hideBacktrackControls();
            adjustArea(-0.04f);
        });
        btnAreaUp.setOnClickListener(v -> {
            pingOverlay();
            hideBacktrackControls();
            moveSubtitleArea(0.04f);
        });
        btnAreaDown.setOnClickListener(v -> {
            pingOverlay();
            hideBacktrackControls();
            moveSubtitleArea(-0.04f);
        });
        btnClose.setOnClickListener(v -> {
            hideBacktrackControls();
            stopSelf();
        });

        overlayView.addView(btnTranslate);
        overlayView.addView(btnLearn);
        overlayView.addView(btnFavorite);
        overlayView.addView(btnAreaMenu);
        overlayView.addView(btnAreaPlus);
        overlayView.addView(btnAreaMinus);
        overlayView.addView(btnAreaUp);
        overlayView.addView(btnAreaDown);
        overlayView.addView(btnClose);
        setMenuVisibility(false);

        bubbleView = createBubbleView();
        overlayView.addView(bubbleView);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.END | Gravity.BOTTOM;
        overlayParams.x = dp(18);
        overlayParams.y = dp(136);

        windowManager.addView(overlayView, overlayParams);
        scheduleDim();
    }

    private TextView createBubbleView() {
        TextView bubble = new TextView(this);
        bubble.setText("幕");
        bubble.setTextSize(13f);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextColor(Color.argb(225, 233, 219, 191));
        bubble.setGravity(Gravity.CENTER);
        bubble.setAlpha(0.88f);
        bubble.setShadowLayer(8f, 0f, 0f, Color.argb(110, 88, 164, 244));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(14, 5, 12, 20));
        bg.setStroke(dp(1), Color.argb(165, 136, 193, 255));
        bubble.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(42), dp(42));
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        bubble.setLayoutParams(lp);

        attachDragAndClick(bubble, () -> {
            if (expanded) {
                hideBacktrackControls();
                playPlaybackIfPossible();
                collapsePanel();
            } else {
                pausePlaybackIfPossible();
                freezeBacktrackReferenceTime();
                expandPanel();
            }
        });
        return bubble;
    }

    private View createInfoCard() {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Color.argb(232, 10, 18, 31));
        bg.setStroke(dp(1), Color.argb(155, 106, 182, 255));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(adaptiveInfoCardWidth(), FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = adaptiveSideMargin();
        lp.bottomMargin = adaptiveInfoCardBottomMargin();
        card.setLayoutParams(lp);

        FrameLayout inner = new FrameLayout(this);
        card.addView(inner);

        statusText = new TextView(this);
        statusText.setText("点“幕”暂停/播放｜译/学：当前或回溯");
        statusText.setTextColor(Color.argb(236, 232, 240, 249));
        statusText.setTextSize(13f);
        statusText.setLineSpacing(dp(2), 1f);
        inner.addView(statusText);

        backtrackLabel = new TextView(this);
        backtrackLabel.setTextColor(Color.argb(220, 195, 214, 236));
        backtrackLabel.setTextSize(12f);
        backtrackLabel.setSingleLine(true);
        updateBacktrackLabel();
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(-1, dp(22));
        labelLp.topMargin = dp(24);
        inner.addView(backtrackLabel, labelLp);
        backtrackLabel.setVisibility(View.GONE);

        backtrackSeekBar = new SeekBar(this);
        backtrackSeekBar.setMax(8);
        backtrackSeekBar.setProgress(Math.max(0, Math.min(8, manualBacktrackMs / 250)));
        backtrackSeekBar.setScaleX(-1f); // 镜像显示：左边是更早的缓存，右边是当前
        backtrackSeekBar.setPadding(0, 0, 0, 0);
        backtrackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                manualBacktrackMs = Math.max(0, Math.min(8, progress)) * 250;
                updateBacktrackLabel();
                updateBacktrackPreviewImage();
                if (fromUser) pingOverlay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pingOverlay();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                pingOverlay();
            }
        });
        FrameLayout.LayoutParams seekLp = new FrameLayout.LayoutParams(-1, dp(36));
        seekLp.topMargin = dp(44);
        inner.addView(backtrackSeekBar, seekLp);
        backtrackSeekBar.setVisibility(View.GONE);

        resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(false);
        resultScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        resultScroll.setVerticalScrollBarEnabled(true);
        resultScroll.setScrollbarFadingEnabled(false);

        resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setPadding(0, 0, dp(8), dp(10));

        resultText = createResultText();
        resultContainer.addView(resultText, new LinearLayout.LayoutParams(-1, -2));

        resultScroll.addView(resultContainer, new ScrollView.LayoutParams(-1, -2));

        FrameLayout.LayoutParams resultLp = new FrameLayout.LayoutParams(-1, adaptiveResultHeight(false));
        resultLp.topMargin = dp(26);
        inner.addView(resultScroll, resultLp);
        resultText.setText("等待字幕…");
        return card;
    }

    private void showBacktrackControls(String message) {
        freezeBacktrackReferenceTime();
        backtrackControlsVisible = true;
        if (!expanded) expandPanel();
        if (infoCard != null) infoCard.setVisibility(View.VISIBLE);
        if (backtrackLabel != null) backtrackLabel.setVisibility(View.VISIBLE);
        if (backtrackSeekBar != null) backtrackSeekBar.setVisibility(View.VISIBLE);
        showBacktrackPreviewOverlay();
        updateBacktrackLabel();
        updateBacktrackPreviewImage();
        updateResultLayoutForBacktrackControls();
        setStatus("回溯模式已开启");
        setResult(message + "\n\n预览只在长按期间显示；左滑到哪一秒，就切换到那一秒对应缓存帧，松手立即消失并执行。");
    }

    private void hideBacktrackControls() {
        backtrackControlsVisible = false;
        selectedBacktrackFrameTimestampMs = -1L;
        if (backtrackLabel != null) backtrackLabel.setVisibility(View.GONE);
        if (backtrackSeekBar != null) backtrackSeekBar.setVisibility(View.GONE);
        hideBacktrackPreviewOverlay();
        updateResultLayoutForBacktrackControls();
    }

    private void ensureVisualOverlay() {
        if (visualOverlayView != null) return;

        visualOverlayView = new FrameLayout(this);
        visualOverlayView.setVisibility(View.GONE);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        visualOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        visualOverlayParams.gravity = Gravity.START | Gravity.TOP;

        areaGuideRect = new View(this);
        areaGuideRect.setVisibility(View.GONE);
        visualOverlayView.addView(areaGuideRect);

        areaGuideLabel = new TextView(this);
        areaGuideLabel.setTextColor(Color.WHITE);
        areaGuideLabel.setTextSize(13f);
        areaGuideLabel.setTypeface(Typeface.DEFAULT_BOLD);
        areaGuideLabel.setGravity(Gravity.CENTER);
        areaGuideLabel.setSingleLine(false);
        areaGuideLabel.setMaxLines(2);
        areaGuideLabel.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable labelBg = new GradientDrawable();
        labelBg.setColor(Color.argb(210, 7, 13, 22));
        labelBg.setCornerRadius(dp(10));
        areaGuideLabel.setBackground(labelBg);
        areaGuideLabel.setVisibility(View.GONE);
        visualOverlayView.addView(areaGuideLabel);

        backtrackPreviewPanel = new LinearLayout(this);
        backtrackPreviewPanel.setOrientation(LinearLayout.VERTICAL);
        backtrackPreviewPanel.setPadding(dp(10), dp(8), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(5, 10, 18)); // 缓存预览窗口完全不透明
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(210, 255, 198, 92));
        backtrackPreviewPanel.setBackground(bg);
        backtrackPreviewPanel.setVisibility(View.GONE);

        backtrackPreviewLabel = new TextView(this);
        backtrackPreviewLabel.setTextColor(Color.rgb(255, 226, 166));
        backtrackPreviewLabel.setTextSize(14f);
        backtrackPreviewLabel.setTypeface(Typeface.DEFAULT_BOLD);
        backtrackPreviewLabel.setGravity(Gravity.CENTER);
        backtrackPreviewLabel.setBackgroundColor(Color.rgb(5, 10, 18));
        backtrackPreviewPanel.addView(backtrackPreviewLabel, new LinearLayout.LayoutParams(-1, dp(26)));

        backtrackPreviewInfo = new TextView(this);
        backtrackPreviewInfo.setTextColor(Color.argb(255, 218, 231, 248));
        backtrackPreviewInfo.setTextSize(12f);
        backtrackPreviewInfo.setGravity(Gravity.CENTER);
        backtrackPreviewInfo.setSingleLine(false);
        backtrackPreviewInfo.setBackgroundColor(Color.rgb(5, 10, 18));
        backtrackPreviewPanel.addView(backtrackPreviewInfo, new LinearLayout.LayoutParams(-1, dp(42)));

        backtrackPreviewImage = new ImageView(this);
        backtrackPreviewImage.setAdjustViewBounds(false);
        backtrackPreviewImage.setScaleType(ImageView.ScaleType.FIT_XY); // 不裁切完整帧，避免平板横屏时裁掉底部字幕
        backtrackPreviewImage.setBackgroundColor(Color.BLACK); // 预览图底色不透明
        backtrackPreviewPanel.addView(backtrackPreviewImage, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams previewLp = new FrameLayout.LayoutParams(
                adaptivePreviewWidth(),
                adaptivePreviewHeight()
        );
        previewLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        previewLp.topMargin = adaptivePreviewTopMargin();
        visualOverlayView.addView(backtrackPreviewPanel, previewLp);

        try {
            windowManager.addView(visualOverlayView, visualOverlayParams);
        } catch (Exception ignored) {
        }
    }

    private void showBacktrackPreviewOverlay() {
        ensureVisualOverlay();
        if (visualOverlayView != null) visualOverlayView.setVisibility(View.VISIBLE);
        if (backtrackPreviewPanel != null) {
            updatePreviewPanelPosition();
            backtrackPreviewPanel.setVisibility(View.VISIBLE);
        }
    }

    private void hideBacktrackPreviewOverlay() {
        if (backtrackPreviewPanel != null) backtrackPreviewPanel.setVisibility(View.GONE);
        if (backtrackPreviewImage != null) backtrackPreviewImage.setImageDrawable(null);
        recycleBacktrackPreviewBitmap();
        hideVisualOverlayIfIdle();
    }

    private void updatePreviewPanelPosition() {
        if (backtrackPreviewPanel == null) return;
        ViewGroup.LayoutParams params = backtrackPreviewPanel.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
            lp.width = adaptivePreviewWidth();
            lp.height = adaptivePreviewHeight();
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = adaptivePreviewTopMargin();
            lp.bottomMargin = 0;
            backtrackPreviewPanel.setLayoutParams(lp);
        }
    }

    private void updateBacktrackPreviewImage() {
        if (!backtrackControlsVisible || backtrackPreviewPanel == null) return;
        int targetMs = Math.max(0, Math.min(2000, manualBacktrackMs));

        BacktrackPreviewFrame previewFrame = copyBestBufferedFrameInfoByTargetMs(targetMs);
        int bufferCount = getBacktrackBufferCount();

        if (previewFrame == null || previewFrame.bitmap == null) {
            selectedBacktrackFrameTimestampMs = -1L;
            if (backtrackPreviewImage != null) backtrackPreviewImage.setImageDrawable(null);
            recycleBacktrackPreviewBitmap();
            if (backtrackPreviewLabel != null) {
                backtrackPreviewLabel.setText(String.format(Locale.US, "缓存回放预览 · 目标 %.2f 秒前", targetMs / 1000f));
            }
            if (backtrackPreviewInfo != null) {
                backtrackPreviewInfo.setText(String.format(Locale.CHINA,
                        "暂无可显示缓存帧｜已缓存 %d/%d 帧\n菜单打开前会每秒缓存 4 帧；若这里为空，请先收起菜单播放 1–2 秒再打开",
                        bufferCount, BACKTRACK_BUFFER_MAX));
            }
            return;
        }

        selectedBacktrackFrameTimestampMs = previewFrame.timestampMs;
        if (backtrackPreviewLabel != null) {
            backtrackPreviewLabel.setText(String.format(Locale.US,
                    "缓存回放预览 · 目标 %.2f 秒前（非真实视频）",
                    targetMs / 1000f));
        }
        if (backtrackPreviewInfo != null) {
            backtrackPreviewInfo.setText(String.format(Locale.US,
                    "命中缓存帧：%.2f 秒前｜偏差 %.2f 秒｜缓存 %d/%d 帧\n完整缓存帧｜手机增强同帧OCR｜窗口高 %.0f%%｜离底 %.0f%%",
                    previewFrame.ageMs / 1000f,
                    previewFrame.deltaMs / 1000f,
                    bufferCount,
                    BACKTRACK_BUFFER_MAX,
                    subtitleAreaPercent * 100f,
                    subtitleAreaBottomOffsetPercent * 100f));
        }

        if (backtrackPreviewImage != null) {
            Bitmap old = backtrackPreviewBitmap;
            backtrackPreviewBitmap = previewFrame.bitmap;
            backtrackPreviewImage.setImageBitmap(backtrackPreviewBitmap);
            if (old != null && old != backtrackPreviewBitmap && !old.isRecycled()) old.recycle();
        } else {
            previewFrame.bitmap.recycle();
        }
    }

    private void recycleBacktrackPreviewBitmap() {
        if (backtrackPreviewBitmap != null && !backtrackPreviewBitmap.isRecycled()) {
            backtrackPreviewBitmap.recycle();
        }
        backtrackPreviewBitmap = null;
    }

    private void showAreaGuideOverlay() {
        ensureVisualOverlay();
        areaGuideVisible = true;
        if (visualOverlayView != null) visualOverlayView.setVisibility(View.VISIBLE);
        updateAreaGuideLayout();
        if (areaGuideRect != null) areaGuideRect.setVisibility(View.VISIBLE);
        if (areaGuideLabel != null) areaGuideLabel.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideAreaGuideRunnable);
        uiHandler.postDelayed(hideAreaGuideRunnable, 1800);
    }

    private void hideAreaGuideOverlay() {
        areaGuideVisible = false;
        uiHandler.removeCallbacks(hideAreaGuideRunnable);
        if (areaGuideRect != null) areaGuideRect.setVisibility(View.GONE);
        if (areaGuideLabel != null) areaGuideLabel.setVisibility(View.GONE);
        hideVisualOverlayIfIdle();
    }

    private void updateAreaGuideLayout() {
        if (areaGuideRect == null || areaGuideLabel == null) return;

        int areaHeight = Math.max(dp(80), (int) (screenHeight * subtitleAreaPercent));
        int bottomOffset = Math.max(0, (int) (screenHeight * subtitleAreaBottomOffsetPercent));
        FrameLayout.LayoutParams rectLp = new FrameLayout.LayoutParams(-1, areaHeight);
        rectLp.gravity = Gravity.BOTTOM;
        rectLp.bottomMargin = bottomOffset;
        areaGuideRect.setLayoutParams(rectLp);

        GradientDrawable rectBg = new GradientDrawable();
        rectBg.setColor(Color.argb(38, 63, 184, 255));
        rectBg.setStroke(dp(2), Color.argb(230, 126, 211, 255));
        areaGuideRect.setBackground(rectBg);

        areaGuideLabel.setText(String.format(Locale.CHINA, "OCR 字幕窗口：高度 %.0f%%｜离底 %.0f%%（缓存保留）", subtitleAreaPercent * 100, subtitleAreaBottomOffsetPercent * 100));
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(adaptiveGuideLabelWidth(), FrameLayout.LayoutParams.WRAP_CONTENT);
        labelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        labelLp.bottomMargin = clampPx(bottomOffset + areaHeight - dp(42), dp(8), Math.max(dp(8), safeScreenHeight() - dp(64)));
        areaGuideLabel.setLayoutParams(labelLp);
    }

    private void hideVisualOverlayIfIdle() {
        if (visualOverlayView == null) return;
        boolean previewVisible = backtrackPreviewPanel != null && backtrackPreviewPanel.getVisibility() == View.VISIBLE;
        boolean guideVisible = areaGuideRect != null && areaGuideRect.getVisibility() == View.VISIBLE;
        if (!previewVisible && !guideVisible) {
            visualOverlayView.setVisibility(View.GONE);
        }
    }

    private void hideOverlayForCapture() {
        runOnMain(() -> {
            if (overlayView != null) overlayView.setAlpha(0f);
            if (visualOverlayView != null) visualOverlayView.setAlpha(0f);
        });
    }

    private void restoreOverlayAfterCapture() {
        runOnMain(() -> {
            if (overlayView != null) overlayView.setAlpha(1f);
            if (visualOverlayView != null) visualOverlayView.setAlpha(1f);
        });
    }

    private void updateResultLayoutForBacktrackControls() {
        if (resultScroll == null) return;
        android.view.ViewGroup.LayoutParams params = resultScroll.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
            if (backtrackControlsVisible) {
                lp.topMargin = dp(82);
                lp.height = adaptiveResultHeight(true);
            } else {
                lp.topMargin = dp(26);
                lp.height = adaptiveResultHeight(false);
            }
            resultScroll.setLayoutParams(lp);
        }
    }

    private void updateBacktrackLabel() {
        if (backtrackLabel == null) return;
        int ms = Math.max(0, Math.min(2000, manualBacktrackMs));
        if (ms <= 80) {
            backtrackLabel.setText("回溯：0.00 秒（右=当前，左=2秒前）");
        } else {
            backtrackLabel.setText(String.format(Locale.US, "回溯：%.2f 秒前（左滑更早）", ms / 1000f));
        }
    }

    private String backtrackTextForStatus(int ms) {
        if (ms <= 80) return "当前画面";
        return String.format(Locale.US, "约 %.1f 秒前", ms / 1000f);
    }

    private View createMenuButton(String text, boolean primary) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(primary ? 14f : 12f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(primary ? Color.argb(255, 244, 236, 220) : Color.argb(238, 232, 239, 248));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (primary) {
            bg.setColor(Color.argb(230, 28, 58, 95));
            bg.setStroke(dp(1), Color.argb(220, 218, 186, 120));
        } else {
            bg.setColor(Color.argb(220, 16, 26, 39));
            bg.setStroke(dp(1), Color.argb(150, 129, 186, 246));
        }
        btn.setBackground(bg);
        btn.setAlpha(0f);
        return btn;
    }

    private void placeFanButton(View button, int rightDp, int bottomDp) {
        int size = dp(38);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = clampPx(dp(rightDp), dp(4), Math.max(dp(4), safeScreenWidth() - size - dp(4)));
        lp.bottomMargin = clampPx(dp(bottomDp), dp(4), Math.max(dp(4), safeScreenHeight() - size - dp(4)));
        button.setLayoutParams(lp);
    }

    private void placeAreaSubButton(View button, int rightDp, int bottomDp) {
        int size = dp(34);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = clampPx(dp(rightDp), dp(4), Math.max(dp(4), safeScreenWidth() - size - dp(4)));
        lp.bottomMargin = clampPx(dp(bottomDp), dp(4), Math.max(dp(4), safeScreenHeight() - size - dp(4)));
        button.setLayoutParams(lp);
    }

    private void expandPanel() {
        expanded = true;
        cancelDim();
        if (bubbleView != null) bubbleView.animate().alpha(0.96f).setDuration(160).start();
        if (infoCard != null) infoCard.setVisibility(View.VISIBLE);
        setMenuVisibility(true);
        scheduleAutoCollapse();
    }

    private void collapsePanel() {
        expanded = false;
        setMenuVisibility(false);
        if (infoCard != null) infoCard.setVisibility(hasResult ? View.GONE : View.GONE);
        scheduleDim();
    }

    private void attachTranslateLearnHoldDrag(View button, boolean learningMode) {
        if (button == null) return;

        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final boolean[] longMode = new boolean[1];
        final boolean[] cancelled = new boolean[1];

        final Runnable[] longPressRunnable = new Runnable[1];
        longPressRunnable[0] = () -> {
            if (cancelled[0] || busy) return;
            longMode[0] = true;
            freezeBacktrackReferenceTime();
            manualBacktrackMs = 0;
            if (backtrackSeekBar != null) backtrackSeekBar.setProgress(0);
            showBacktrackControls((learningMode ? "按住“学”向左滑：越左越早；看预览图，松手生成学习卡片" : "按住“译”向左滑：越左越早；看预览图，松手翻译"));
            updateBacktrackDragFromX(downX[0], downX[0]);
        };

        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pingOverlay();
                    cancelled[0] = false;
                    longMode[0] = false;
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    uiHandler.postDelayed(longPressRunnable[0], ViewConfiguration.getLongPressTimeout());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (longMode[0]) {
                        updateBacktrackDragFromX(downX[0], event.getRawX());
                        return true;
                    }
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    if (Math.abs(dx) > dp(18) || Math.abs(dy) > dp(18)) {
                        cancelled[0] = true;
                        uiHandler.removeCallbacks(longPressRunnable[0]);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    uiHandler.removeCallbacks(longPressRunnable[0]);
                    if (longMode[0]) {
                        updateBacktrackDragFromX(downX[0], event.getRawX());
                        long chosenTimestamp = selectedBacktrackFrameTimestampMs;
                        hideBacktrackControls();
                        selectedBacktrackFrameTimestampMs = chosenTimestamp;
                        if (learningMode) {
                            captureLearnWithMode(true);
                        } else {
                            captureTranslateWithMode(true);
                        }
                    } else if (!cancelled[0]) {
                        hideBacktrackControls();
                        if (learningMode) {
                            captureLearnWithMode(false);
                        } else {
                            captureTranslateWithMode(false);
                        }
                    }
                    longMode[0] = false;
                    cancelled[0] = true;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    uiHandler.removeCallbacks(longPressRunnable[0]);
                    if (longMode[0]) {
                        hideBacktrackControls();
                    }
                    longMode[0] = false;
                    cancelled[0] = true;
                    return true;
            }
            return true;
        });
    }

    private void updateBacktrackDragFromX(float startRawX, float currentRawX) {
        float leftDrag = Math.max(0f, startRawX - currentRawX);
        float maxDrag = Math.max(dp(140), Math.min(screenWidth * 0.55f, dp(360)));
        float ratio = Math.max(0f, Math.min(1f, leftDrag / maxDrag));
        int step = Math.max(0, Math.min(8, Math.round(ratio * 8f)));
        manualBacktrackMs = step * 250;
        if (backtrackSeekBar != null && backtrackSeekBar.getProgress() != step) {
            backtrackSeekBar.setProgress(step);
        } else {
            updateBacktrackLabel();
            updateBacktrackPreviewImage();
        }
    }

    private void setMenuVisibility(boolean visible) {
        View[] buttons = {btnTranslate, btnLearn, btnFavorite, btnAreaMenu, btnClose};
        for (View button : buttons) {
            if (button == null) continue;
            if (visible) {
                button.setVisibility(View.VISIBLE);
                button.animate().alpha(1f).setDuration(180).start();
            } else {
                button.animate().alpha(0f).setDuration(150).withEndAction(() -> button.setVisibility(View.GONE)).start();
            }
        }
        if (!visible) {
            setAreaSubmenuVisibility(false);
        }
    }

    private void setAreaSubmenuVisibility(boolean visible) {
        areaSubmenuExpanded = visible;
        View[] buttons = {btnAreaPlus, btnAreaMinus, btnAreaUp, btnAreaDown};
        for (View button : buttons) {
            if (button == null) continue;
            if (visible && expanded) {
                button.setVisibility(View.VISIBLE);
                button.animate().alpha(1f).setDuration(160).start();
            } else {
                button.animate().alpha(0f).setDuration(120).withEndAction(() -> button.setVisibility(View.GONE)).start();
            }
        }
    }

    private void attachDragAndClick(View view, Runnable clickAction) {
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final boolean[] moved = new boolean[1];
        view.setOnTouchListener((v, event) -> {
            if (overlayParams == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pingOverlay();
                    startX[0] = overlayParams.x;
                    startY[0] = overlayParams.y;
                    touchX[0] = event.getRawX();
                    touchY[0] = event.getRawY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - touchX[0]);
                    int dy = (int) (event.getRawY() - touchY[0]);
                    if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) moved[0] = true;
                    overlayParams.x = Math.max(0, startX[0] - dx);
                    overlayParams.y = Math.max(0, startY[0] - dy);
                    updateOverlayPosition();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0] && clickAction != null) clickAction.run();
                    return true;
            }
            return false;
        });
    }

    private void pingOverlay() {
        cancelDim();
        cancelAutoCollapse();
        if (bubbleView != null) bubbleView.animate().alpha(0.98f).setDuration(120).start();
        if (expanded) scheduleAutoCollapse();
        else scheduleDim();
    }

    private void scheduleDim() {
        cancelDim();
        uiHandler.postDelayed(dimRunnable, 3500);
    }

    private void cancelDim() {
        uiHandler.removeCallbacks(dimRunnable);
    }

    private void scheduleAutoCollapse() {
        cancelAutoCollapse();
        uiHandler.postDelayed(autoCollapseRunnable, 5500);
    }

    private void cancelAutoCollapse() {
        uiHandler.removeCallbacks(autoCollapseRunnable);
    }

    private void updateOverlayPosition() {
        try {
            if (overlayView != null) windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception ignored) {
        }
    }

    private void adjustArea(float delta) {
        subtitleAreaPercent += delta;
        normalizeSubtitleAreaWindow();
        setStatus(String.format(Locale.CHINA, "字幕窗口高度：%.0f%%，离底：%.0f%%（缓存保留）", subtitleAreaPercent * 100, subtitleAreaBottomOffsetPercent * 100));
        showAreaGuideOverlay();
        updatePreviewPanelPosition();
        if (expanded && infoCard != null) infoCard.setVisibility(View.VISIBLE);
    }

    private void moveSubtitleArea(float delta) {
        subtitleAreaBottomOffsetPercent += delta;
        normalizeSubtitleAreaWindow();
        setStatus(String.format(Locale.CHINA, "字幕窗口位置：离底 %.0f%%，高度 %.0f%%（缓存保留）", subtitleAreaBottomOffsetPercent * 100, subtitleAreaPercent * 100));
        showAreaGuideOverlay();
        updatePreviewPanelPosition();
        if (expanded && infoCard != null) infoCard.setVisibility(View.VISIBLE);
    }

    private void normalizeSubtitleAreaWindow() {
        if (subtitleAreaPercent < 0.16f) subtitleAreaPercent = 0.16f;
        if (subtitleAreaPercent > 0.46f) subtitleAreaPercent = 0.46f;
        if (subtitleAreaBottomOffsetPercent < 0f) subtitleAreaBottomOffsetPercent = 0f;
        if (subtitleAreaBottomOffsetPercent > 0.42f) subtitleAreaBottomOffsetPercent = 0.42f;
        if (subtitleAreaPercent + subtitleAreaBottomOffsetPercent > 0.72f) {
            subtitleAreaBottomOffsetPercent = Math.max(0f, 0.72f - subtitleAreaPercent);
        }
    }

    private void clearLastLearningCard() {
        if (!TextUtils.isEmpty(lastLearningScreenshotTempPath)) {
            try {
                File old = new File(lastLearningScreenshotTempPath);
                if (old.exists()) old.delete();
            } catch (Exception ignored) {
            }
        }
        clearPendingLearningFullScreenshot();
        lastLearningSubtitle = null;
        lastLearningRawContent = null;
        lastLearningDisplayText = null;
        lastLearningScreenshotTempPath = null;
    }

    private void clearPendingLearningFullScreenshot() {
        if (!TextUtils.isEmpty(pendingLearningFullScreenshotTempPath)) {
            try {
                File old = new File(pendingLearningFullScreenshotTempPath);
                if (old.exists()) old.delete();
            } catch (Exception ignored) {
            }
        }
        pendingLearningFullScreenshotTempPath = null;
    }

    private void captureTranslateWithMode(boolean useBacktrack) {
        if (!hasMiniMaxKey()) {
            showMiniMaxKeyMissing("翻译");
            return;
        }
        clearLastLearningCard();
        captureSubtitle(false, useBacktrack);
    }

    private void captureLearnWithMode(boolean useBacktrack) {
        if (!hasMiniMaxKey()) {
            showMiniMaxKeyMissing("学习");
            return;
        }
        clearLastLearningCard();
        captureSubtitle(true, useBacktrack);
    }

    private void captureAndTranslate() {
        captureTranslateWithMode(false);
    }

    private void captureAndLearn() {
        captureLearnWithMode(false);
    }

    private boolean hasMiniMaxKey() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String apiKey = prefs.getString(MainActivity.PREF_MINIMAX_API_KEY, "");
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    private void showMiniMaxKeyMissing(String modeName) {
        setStatus("请先回到 App 首页填写并保存 MiniMax API Key");
        setResult(modeName + "模式需要 MiniMax API Key。\n打开 App 首页 → MiniMax 设置 → 填写 API Key → 保存 → 重新启动悬浮翻译器。 ");
        expandPanel();
    }

    private void pausePlaybackIfPossible() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            long eventTime = System.currentTimeMillis();
            KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
            KeyEvent up = new KeyEvent(eventTime, eventTime + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
            audioManager.dispatchMediaKeyEvent(down);
            audioManager.dispatchMediaKeyEvent(up);
        } catch (Exception ignored) {
        }
    }

    private void playPlaybackIfPossible() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            long eventTime = System.currentTimeMillis();
            KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
            KeyEvent up = new KeyEvent(eventTime, eventTime + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
            audioManager.dispatchMediaKeyEvent(down);
            audioManager.dispatchMediaKeyEvent(up);
        } catch (Exception ignored) {
        }
    }

    private void captureSubtitle(boolean learningMode, boolean useBacktrack) {
        if (busy) return;
        if (imageReader == null || mediaProjection == null) {
            setStatus("未启动屏幕录制，请回到应用重新启动");
            return;
        }
        busy = true;
        pausePlaybackIfPossible();
        final int targetBacktrackMs = useBacktrack ? Math.max(0, Math.min(2000, manualBacktrackMs)) : 0;
        String backtrackText = useBacktrack ? backtrackTextForStatus(targetBacktrackMs) : "当前画面";
        setStatus(useBacktrack ? "正在读取预览选中的" + backtrackText + "缓存帧…" : "正在暂停并识别当前字幕…");
        setResult(useBacktrack ? "将严格识别当前预览图对应的同一张缓存帧；不会换附近帧，但会在这张帧内多区域扫描字幕。" : "已尝试暂停播放，正在识别当前画面字幕…");
        if (!expanded) expandPanel();

        captureHandler.postDelayed(() -> {
            ArrayList<Bitmap> candidates = new ArrayList<>();

            if (useBacktrack) {
                Bitmap selectedFrame = copyBufferedFrameByTimestamp(selectedBacktrackFrameTimestampMs);
                if (selectedFrame == null) {
                    selectedFrame = copyBestBufferedFrameByTargetMs(targetBacktrackMs);
                }
                if (selectedFrame != null) {
                    if (learningMode) {
                        // 收藏截图在手机和平板都保存完整缓存帧，而不是 OCR 裁剪区域。
                        pendingLearningFullScreenshotTempPath = saveTempLearningScreenshot(selectedFrame);
                    }
                    // 严格回溯仍然只使用“当前预览图对应的同一张缓存帧”。
                    // 但在这一张帧内部，尤其手机端，会尝试更多字幕可能出现的位置。
                    addBacktrackFrameOcrCrops(candidates, selectedFrame);
                    selectedFrame.recycle();
                }
            } else {
                hideOverlayForCapture();
                Bitmap current = acquireLatestBitmap();
                restoreOverlayAfterCapture();
                if (current != null) {
                    if (learningMode) {
                        // 收藏截图在手机和平板都保存隐藏悬浮层后的完整当前屏幕帧。
                        pendingLearningFullScreenshotTempPath = saveTempLearningScreenshot(current);
                    }
                    addCurrentFrameCrops(candidates, current, true);
                    current.recycle();
                }
            }

            if (candidates.isEmpty()) {
                clearPendingLearningFullScreenshot();
                runOnMain(() -> {
                    busy = false;
                    setStatus(useBacktrack ? "没有可用的预缓存帧" : "没有截到当前画面");
                    setResult(useBacktrack
                            ? "没有可用的预缓存帧。\n请先收起菜单播放 1–2 秒，让后台缓存最近 8 帧；再点“幕”暂停并长按“译 / 学”回溯。\n"
                            : "没有截到当前画面；如果视频平台受 DRM 保护，系统可能返回黑屏。\n");
                });
                return;
            }

            runOcrCandidates(candidates, learningMode, 0, useBacktrack, targetBacktrackMs);
        }, useBacktrack ? 40 : 220);
    }

    private void startSubtitleBacktrackBuffer() {
        if (captureHandler == null) return;
        subtitleBacktrackRunning = true;
        captureHandler.removeCallbacks(subtitleBacktrackRunnable);
        captureHandler.postDelayed(subtitleBacktrackRunnable, BACKTRACK_CAPTURE_INTERVAL_MS);
    }

    private void stopSubtitleBacktrackBuffer() {
        subtitleBacktrackRunning = false;
        if (captureHandler != null) {
            captureHandler.removeCallbacks(subtitleBacktrackRunnable);
        }
        clearSubtitleBacktrackBuffer();
    }

    private void captureSubtitleBufferFrame() {
        // 只在主菜单未展开时后台预缓存，避免缓存到本软件 UI。
        // 采样频率由 BACKTRACK_CAPTURE_INTERVAL_MS 控制：250ms = 每秒 4 帧。
        // 注意：这里缓存完整屏幕帧，OCR 时再按当前字幕窗口裁剪；因此调整 OCR 区域不会清空缓存。
        if (imageReader == null || mediaProjection == null || busy || expanded || backtrackControlsVisible || areaGuideVisible) return;

        Bitmap bitmap = acquireLatestBitmap();
        if (bitmap == null) return;

        try {
            addSubtitleBufferFrame(bitmap);
            bitmap = null; // ownership transferred to the buffer
        } catch (Exception ignored) {
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private void addSubtitleBufferFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        synchronized (subtitleBufferLock) {
            subtitleBacktrackBuffer.add(new BufferedSubtitleFrame(frame, System.currentTimeMillis()));
            while (subtitleBacktrackBuffer.size() > BACKTRACK_BUFFER_MAX) {
                BufferedSubtitleFrame old = subtitleBacktrackBuffer.remove(0);
                old.recycle();
            }
        }
    }

    private ArrayList<Bitmap> copyBufferedSubtitleFramesByTargetMs(int targetMs) {
        ArrayList<Bitmap> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (subtitleBufferLock) {
            ArrayList<BufferedSubtitleFrame> frames = new ArrayList<>(subtitleBacktrackBuffer);
            Collections.sort(frames, (a, b) -> {
                long da = Math.abs((now - a.timestampMs) - targetMs);
                long db = Math.abs((now - b.timestampMs) - targetMs);
                return Long.compare(da, db);
            });
            for (BufferedSubtitleFrame frame : frames) {
                Bitmap copy = frame.copyBitmap();
                if (copy != null) result.add(copy);
            }
        }
        return result;
    }

    private Bitmap copyBestBufferedFrameByTargetMs(int targetMs) {
        BacktrackPreviewFrame frame = copyBestBufferedFrameInfoByTargetMs(targetMs);
        return frame == null ? null : frame.bitmap;
    }

    private BacktrackPreviewFrame copyBestBufferedFrameInfoByTargetMs(int targetMs) {
        long referenceTime = getBacktrackReferenceTime();
        if (referenceTime <= 0L) return null;

        BufferedSubtitleFrame best = null;
        long bestDelta = Long.MAX_VALUE;
        long bestAge = 0L;
        synchronized (subtitleBufferLock) {
            for (BufferedSubtitleFrame frame : subtitleBacktrackBuffer) {
                long age = Math.max(0L, referenceTime - frame.timestampMs);
                long delta = Math.abs(age - targetMs);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestAge = age;
                    best = frame;
                }
            }
            if (best == null) return null;
            Bitmap copy = best.copyBitmap();
            return copy == null ? null : new BacktrackPreviewFrame(copy, best.timestampMs, bestAge, bestDelta);
        }
    }

    private long getBacktrackReferenceTime() {
        synchronized (subtitleBufferLock) {
            if (backtrackReferenceTimestampMs > 0L) return backtrackReferenceTimestampMs;
            return latestBacktrackTimestampLocked();
        }
    }

    private void freezeBacktrackReferenceTime() {
        synchronized (subtitleBufferLock) {
            backtrackReferenceTimestampMs = latestBacktrackTimestampLocked();
        }
    }

    private long latestBacktrackTimestampLocked() {
        long latest = -1L;
        for (BufferedSubtitleFrame frame : subtitleBacktrackBuffer) {
            if (frame.timestampMs > latest) latest = frame.timestampMs;
        }
        return latest;
    }

    private int getBacktrackBufferCount() {
        synchronized (subtitleBufferLock) {
            return subtitleBacktrackBuffer.size();
        }
    }

    private Bitmap copyBufferedFrameByTimestamp(long timestampMs) {
        if (timestampMs <= 0L) return null;
        synchronized (subtitleBufferLock) {
            for (BufferedSubtitleFrame frame : subtitleBacktrackBuffer) {
                if (frame.timestampMs == timestampMs) {
                    return frame.copyBitmap();
                }
            }
        }
        return null;
    }

    private void clearSubtitleBacktrackBuffer() {
        synchronized (subtitleBufferLock) {
            for (BufferedSubtitleFrame frame : subtitleBacktrackBuffer) {
                frame.recycle();
            }
            subtitleBacktrackBuffer.clear();
        }
    }

    private void recycleBitmapList(List<Bitmap> bitmaps) {
        if (bitmaps == null) return;
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        bitmaps.clear();
    }

    private Bitmap acquireLatestBitmap() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return null;
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap padded = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
            padded.recycle();
            return bitmap;
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap cropSubtitleArea(Bitmap source) {
        return cropSubtitleArea(source, subtitleAreaPercent, subtitleAreaBottomOffsetPercent);
    }

    private Bitmap cropSubtitleArea(Bitmap source, float heightPercent, float bottomOffsetPercent) {
        int sourceHeight = source.getHeight();
        int sourceWidth = source.getWidth();

        float safeHeightPercent = Math.max(0.12f, Math.min(0.60f, heightPercent));
        float safeBottomOffsetPercent = Math.max(0f, Math.min(0.55f, bottomOffsetPercent));
        if (safeHeightPercent + safeBottomOffsetPercent > 0.82f) {
            safeBottomOffsetPercent = Math.max(0f, 0.82f - safeHeightPercent);
        }

        int bottom = Math.max(1, Math.min(sourceHeight, (int) (sourceHeight * (1f - safeBottomOffsetPercent))));
        int cropHeight = Math.max(1, Math.min(sourceHeight, (int) (sourceHeight * safeHeightPercent)));
        int top = Math.max(0, bottom - cropHeight);
        if (top + cropHeight > sourceHeight) {
            cropHeight = sourceHeight - top;
        }
        return Bitmap.createBitmap(source, 0, top, sourceWidth, Math.max(1, cropHeight));
    }

    private void addCurrentFrameCrops(ArrayList<Bitmap> candidates, Bitmap current, boolean includeSmartFallbacks) {
        if (current == null || current.isRecycled()) return;

        try {
            candidates.add(cropSubtitleArea(current));
        } catch (Exception ignored) {
        }

        if (!includeSmartFallbacks) return;

        // 当前画面兜底扫描：用较窄字幕窗口在下半屏多段尝试，避免必须把区域放大到 60%。
        float smartHeight = Math.max(0.18f, Math.min(0.28f, subtitleAreaPercent));
        float[] offsets = new float[]{0.00f, 0.06f, 0.12f, 0.18f, 0.24f, 0.30f, 0.36f};
        for (float offset : offsets) {
            if (Math.abs(offset - subtitleAreaBottomOffsetPercent) < 0.015f
                    && Math.abs(smartHeight - subtitleAreaPercent) < 0.015f) {
                continue;
            }
            try {
                candidates.add(cropSubtitleArea(current, smartHeight, offset));
            } catch (Exception ignored) {
            }
        }
    }

    private void addBacktrackFrameOcrCrops(ArrayList<Bitmap> candidates, Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;

        // 1. 用户当前 OCR 窗口，优先级最高。
        try {
            candidates.add(cropSubtitleArea(frame));
        } catch (Exception ignored) {
        }

        // 2. 同一张帧内的字幕区域扫描，不换时间帧。
        // 手机上视频/字幕位置变化更大，所以手机端扫更多区域。
        float[] heights = isTabletLayout()
                ? new float[]{0.18f, 0.24f, 0.32f, 0.45f}
                : new float[]{0.16f, 0.22f, 0.30f, 0.40f, 0.55f};
        float[] offsets = isTabletLayout()
                ? new float[]{0.00f, 0.06f, 0.12f, 0.18f, 0.26f, 0.34f}
                : new float[]{0.00f, 0.04f, 0.08f, 0.12f, 0.18f, 0.24f, 0.32f, 0.42f};

        for (float height : heights) {
            for (float offset : offsets) {
                if (height + offset > 0.82f) continue;
                if (Math.abs(offset - subtitleAreaBottomOffsetPercent) < 0.015f
                        && Math.abs(height - subtitleAreaPercent) < 0.015f) {
                    continue;
                }
                try {
                    candidates.add(cropSubtitleArea(frame, height, offset));
                } catch (Exception ignored) {
                }
            }
        }

        // 3. 最后兜底：整帧 OCR。仍然是同一张预览帧，不会切到其他时间。
        // 放最后，避免优先识别到播放器 UI 字样。
        try {
            Bitmap whole = frame.copy(Bitmap.Config.ARGB_8888, false);
            if (whole != null) candidates.add(whole);
        } catch (Exception ignored) {
        }
    }

    private void runOcrCandidates(ArrayList<Bitmap> candidates, boolean learningMode, int index, boolean strictBacktrack, int targetBacktrackMs) {
        if (index >= candidates.size()) {
            recycleBitmapList(candidates);
            clearPendingLearningFullScreenshot();
            busy = false;
            if (strictBacktrack) {
                setStatus("当前预览缓存帧未识别到字幕");
                setResult("当前预览缓存帧没有识别到字幕。\n本次没有切换到其他时间的缓存帧；已在这张预览帧内尝试手机/平板多区域 OCR 和整帧兜底。\n如果仍失败，通常是字幕太小、背景太花、视频平台截图黑屏，或字幕在缓存前已经消失。\n");
            } else {
                setStatus("没有识别到字幕");
                setResult("没有识别到字幕。\n点“区”展开 OCR 控制后，可用“+ / -”调窗口高度，用“上 / 下”移动字幕窗口；也可长按“译 / 学”回溯。\n");
            }
            return;
        }

        Bitmap crop = candidates.get(index);
        InputImage image = InputImage.fromBitmap(crop, 0);
        Task<Text> latinTask = latinRecognizer.process(image);
        Task<Text> japaneseTask = japaneseRecognizer.process(image);

        Tasks.whenAllComplete(latinTask, japaneseTask)
                .addOnSuccessListener(tasks -> {
                    String latinText = safeTaskText(latinTask);
                    String japaneseText = safeTaskText(japaneseTask);
                    String selected = chooseBestText(latinText, japaneseText);

                    if (selected.trim().isEmpty()) {
                        if (crop != null && !crop.isRecycled()) crop.recycle();
                        candidates.set(index, null);
                        // strictBacktrack 表示“不换到其他时间帧”，不是“不尝试同帧的其他 OCR 区域”。
                        runOcrCandidates(candidates, learningMode, index + 1, strictBacktrack, targetBacktrackMs);
                    } else {
                        String screenshotPath = null;
                        if (learningMode) {
                            screenshotPath = pendingLearningFullScreenshotTempPath;
                            pendingLearningFullScreenshotTempPath = null;
                            if (TextUtils.isEmpty(screenshotPath)) {
                                // 极端情况下没有拿到完整帧，才退回保存 OCR 裁剪图。
                                screenshotPath = saveTempLearningScreenshot(crop);
                            }
                        }
                        recycleBitmapList(candidates);

                        if (learningMode) {
                            lastLearningSubtitle = selected.trim();
                            lastLearningScreenshotTempPath = screenshotPath;
                            lastLearningRawContent = "";
                            lastLearningDisplayText = "";
                            setStatus(strictBacktrack ? "已识别预览缓存帧，正在生成学习卡片…" : (index == 0 ? "识别到字幕，正在生成学习卡片…" : "已回看找到字幕，正在生成学习卡片…"));
                            requestMiniMaxResult(selected.trim(), true);
                        } else {
                            setStatus(strictBacktrack ? "已识别预览缓存帧，MiniMax 正在翻译…" : (index == 0 ? "识别到字幕，MiniMax 正在快速翻译…" : "已回看找到字幕，MiniMax 正在快速翻译…"));
                            requestMiniMaxResult(selected.trim(), false);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    if (crop != null && !crop.isRecycled()) crop.recycle();
                    candidates.set(index, null);
                    runOcrCandidates(candidates, learningMode, index + 1, strictBacktrack, targetBacktrackMs);
                });
    }

    private static class BacktrackPreviewFrame {
        final Bitmap bitmap;
        final long timestampMs;
        final long ageMs;
        final long deltaMs;

        BacktrackPreviewFrame(Bitmap bitmap, long timestampMs, long ageMs, long deltaMs) {
            this.bitmap = bitmap;
            this.timestampMs = timestampMs;
            this.ageMs = ageMs;
            this.deltaMs = deltaMs;
        }
    }

    private static class BufferedSubtitleFrame {
        final Bitmap bitmap;
        final long timestampMs;

        BufferedSubtitleFrame(Bitmap bitmap, long timestampMs) {
            this.bitmap = bitmap;
            this.timestampMs = timestampMs;
        }

        Bitmap copyBitmap() {
            if (bitmap == null || bitmap.isRecycled()) return null;
            try {
                return bitmap.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Exception e) {
                return null;
            }
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private String safeTaskText(Task<Text> task) {
        if (task != null && task.isSuccessful() && task.getResult() != null) {
            return task.getResult().getText();
        }
        return "";
    }

    private String chooseBestText(String latinText, String japaneseText) {
        String cleanLatin = cleanOcrText(latinText);
        String cleanJapanese = cleanOcrText(japaneseText);
        boolean japaneseChars = containsJapaneseChars(cleanJapanese) || containsJapaneseChars(cleanLatin);
        if (japaneseChars && cleanJapanese.length() >= Math.max(3, cleanLatin.length() / 2)) {
            return cleanJapanese;
        }
        return cleanLatin.length() >= cleanJapanese.length() ? cleanLatin : cleanJapanese;
    }

    private String cleanOcrText(String text) {
        if (text == null) return "";
        return text.replace("\r", "\n")
                .replaceAll("[\\u0000-\\u001F&&[^\\n]]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private boolean containsJapaneseChars(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\u31f0' && c <= '\u31ff')) {
                return true;
            }
        }
        return false;
    }

    private void requestMiniMaxResult(String text, boolean learningMode) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String apiKey = prefs.getString(MainActivity.PREF_MINIMAX_API_KEY, "");
        String model = prefs.getString(MainActivity.PREF_MINIMAX_MODEL, MainActivity.DEFAULT_MINIMAX_MODEL);
        String endpoint = prefs.getString(MainActivity.PREF_MINIMAX_ENDPOINT, MainActivity.DEFAULT_MINIMAX_ENDPOINT);
        if (model == null || model.trim().isEmpty()) model = MainActivity.DEFAULT_MINIMAX_MODEL;
        if (endpoint == null || endpoint.trim().isEmpty()) endpoint = MainActivity.DEFAULT_MINIMAX_ENDPOINT;

        final String finalApiKey = apiKey.trim();
        final String finalModel = normalizeMiniMaxModel(model.trim());
        final String finalEndpoint = endpoint.trim();

        setResult(learningMode
                ? buildOriginalFirstLearningFallbackText(text, "正在生成：中文 / 重点词 / 语法…")
                : buildOriginalFirstTranslationText(text, "正在翻译…"));

        new Thread(() -> {
            try {
                String result = callMiniMax(finalEndpoint, finalApiKey, finalModel, text, learningMode);
                final String finalResult = result;
                runOnMain(() -> {
                    busy = false;
                    hasResult = true;
                    cancelAutoCollapse();
                    if (infoCard != null) infoCard.setVisibility(View.VISIBLE);
                    setStatus(learningMode ? "学习卡片已生成" : "翻译完成");
                    hideBacktrackControls();
                    hideAreaGuideOverlay();
                    if (learningMode) {
                        lastLearningRawContent = finalResult;
                        lastLearningDisplayText = buildLearningPlainText(finalResult);
                        renderLearningCard(finalResult, text);
                    } else {
                        setResult(buildOriginalFirstTranslationText(text, finalResult));
                    }
                });
            } catch (Exception e) {
                runOnMain(() -> {
                    busy = false;
                    cancelAutoCollapse();
                    setStatus(learningMode ? "MiniMax 学习解析失败" : "MiniMax 翻译失败");
                    hideBacktrackControls();
                    hideAreaGuideOverlay();
                    setResult("识别原文：\n" + text + "\n\n错误：" + e.getMessage() + "\n\n请检查 API Key、网络、模型名和接口地址。当前接口：" + finalEndpoint);
                });
            }
        }).start();
    }

    private String normalizeMiniMaxModel(String model) {
        if (model == null || model.trim().isEmpty()) return MainActivity.DEFAULT_MINIMAX_MODEL;
        String m = model.trim();
        if (m.equalsIgnoreCase("minimax-m3")) return "MiniMax-M3";
        if (m.equalsIgnoreCase("minimax-m2.7-highspeed")) return "MiniMax-M2.7-highspeed";
        if (m.equalsIgnoreCase("minimax-m2.7")) return "MiniMax-M2.7";
        return m;
    }

    private String saveTempLearningScreenshot(Bitmap bitmap) {
        if (bitmap == null) return null;
        FileOutputStream outputStream = null;
        try {
            File dir = new File(getCacheDir(), "learning_screenshots");
            if (!dir.exists() && !dir.mkdirs()) return null;

            File file = new File(dir, "learning_latest_" + System.currentTimeMillis() + ".png");
            outputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 92, outputStream);
            outputStream.flush();
            return file.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                if (outputStream != null) outputStream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasUsableLearningCard() {
        String raw = lastLearningRawContent == null ? "" : lastLearningRawContent.trim();
        String display = lastLearningDisplayText == null ? "" : lastLearningDisplayText.trim();

        if (raw.isEmpty() && display.isEmpty()) return false;
        if (raw.equals("{}") || raw.equals("[]")) return false;

        try {
            JSONObject json = new JSONObject(extractJsonObject(raw));
            String chinese = json.optString("中文", "").trim();
            JSONArray words = json.optJSONArray("重点词");
            JSONArray grammar = json.optJSONArray("语法");

            boolean hasWords = words != null && words.length() > 0;
            boolean hasGrammar = grammar != null && grammar.length() > 0;
            return !chinese.isEmpty() || hasWords || hasGrammar;
        } catch (Exception ignored) {
            String normalized = display.replace("未返回重点词", "")
                    .replace("未返回语法点", "")
                    .replace("未保存中文翻译", "")
                    .trim();
            return normalized.length() >= 3;
        }
    }

    private void saveCurrentLearningFavorite() {
        if (!hasUsableLearningCard()) {
            setStatus("学习卡片为空，无法收藏");
            showToast("学习卡片为空，请先点“学”生成内容");
            return;
        }

        setStatus("正在保存收藏…");
        new Thread(() -> {
            try {
                FavoriteSaveResult result = saveFavoriteFiles();
                runOnMain(() -> {
                    setStatus("已收藏学习卡片和截图");
                    showToast("已收藏：" + result.folderPath);
                });
            } catch (Exception e) {
                runOnMain(() -> {
                    setStatus("收藏失败：" + e.getMessage());
                    showToast("收藏失败：" + e.getMessage());
                });
            }
        }).start();
    }

    private FavoriteSaveResult saveFavoriteFiles() throws Exception {
        if (!hasUsableLearningCard()) {
            throw new Exception("学习卡片为空");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = getExternalFilesDir("SubtitleFavorites");
        if (dir == null) dir = new File(getFilesDir(), "SubtitleFavorites");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建收藏目录");
        }

        File imageFile = new File(dir, "favorite_" + timestamp + ".png");
        boolean hasScreenshot = false;
        if (!TextUtils.isEmpty(lastLearningScreenshotTempPath)) {
            File temp = new File(lastLearningScreenshotTempPath);
            if (temp.exists()) {
                copyFile(temp, imageFile);
                hasScreenshot = true;
            }
        }

        if (!hasScreenshot && imageReader != null) {
            Bitmap current = acquireLatestBitmap();
            if (current != null) {
                FileOutputStream output = null;
                try {
                    output = new FileOutputStream(imageFile);
                    current.compress(Bitmap.CompressFormat.PNG, 92, output);
                    output.flush();
                    hasScreenshot = true;
                } finally {
                    try {
                        if (output != null) output.close();
                    } catch (Exception ignored) {
                    }
                    current.recycle();
                }
            }
        }

        File textFile = new File(dir, "favorite_" + timestamp + ".txt");
        File jsonFile = new File(dir, "favorite_" + timestamp + ".json");
        StringBuilder content = new StringBuilder();
        content.append("字幕学习收藏\n");
        content.append("保存时间：").append(timestamp).append("\n\n");
        if (!TextUtils.isEmpty(lastLearningSubtitle)) {
            content.append("识别原文：\n").append(lastLearningSubtitle).append("\n\n");
        }
        content.append("学习卡片：\n");
        if (!TextUtils.isEmpty(lastLearningDisplayText)) {
            content.append(lastLearningDisplayText).append("\n\n");
        } else {
            content.append(lastLearningRawContent).append("\n\n");
        }
        if (hasScreenshot) {
            content.append("截图文件：").append(imageFile.getName()).append("\n");
            content.append("截图说明：手机和平板都会优先保存识别来源的完整屏幕帧，不再只保存 OCR 裁剪区域。\n");
        } else {
            content.append("截图文件：未保存成功\n");
        }
        content.append("收藏目录：").append(dir.getAbsolutePath()).append("\n");

        FileOutputStream textOutput = null;
        try {
            textOutput = new FileOutputStream(textFile);
            textOutput.write(content.toString().getBytes("UTF-8"));
            textOutput.flush();
        } finally {
            if (textOutput != null) textOutput.close();
        }

        if (!TextUtils.isEmpty(lastLearningRawContent)) {
            FileOutputStream jsonOutput = null;
            try {
                jsonOutput = new FileOutputStream(jsonFile);
                jsonOutput.write(lastLearningRawContent.getBytes("UTF-8"));
                jsonOutput.flush();
            } finally {
                if (jsonOutput != null) jsonOutput.close();
            }
        }

        FavoriteSaveResult result = new FavoriteSaveResult();
        result.folderPath = dir.getAbsolutePath();
        result.textPath = textFile.getAbsolutePath();
        result.imagePath = hasScreenshot ? imageFile.getAbsolutePath() : "";
        return result;
    }

    private void copyFile(File source, File target) throws Exception {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            if (input != null) input.close();
            if (output != null) output.close();
        }
    }

    private static class FavoriteSaveResult {
        String folderPath;
        String textPath;
        String imagePath;
    }

    private String callMiniMax(String endpoint, String apiKey, String model, String subtitle, boolean learningMode) throws Exception {
        JSONObject body = buildMiniMaxRequestBody(model, subtitle, learningMode, false);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(learningMode ? 22000 : 12000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] payload = body.toString().getBytes("UTF-8");
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(payload);
            outputStream.flush();
            outputStream.close();

            int code = connection.getResponseCode();
            InputStream inputStream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readStream(inputStream);
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + "：" + compact(response));
            }
            return cleanMiniMaxOutput(extractMiniMaxContent(response), learningMode);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String callMiniMaxStreaming(String endpoint, String apiKey, String model, String subtitle) throws Exception {
        JSONObject body = buildMiniMaxRequestBody(model, subtitle, false, true);
        StringBuilder streamed = new StringBuilder();
        StringBuilder rawFallback = new StringBuilder();

        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(18000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream, application/json");

            byte[] payload = body.toString().getBytes("UTF-8");
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(payload);
            outputStream.flush();
            outputStream.close();

            int code = connection.getResponseCode();
            InputStream inputStream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            if (code < 200 || code >= 300) {
                String error = readStream(inputStream);
                throw new Exception("HTTP " + code + "：" + compact(error));
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            String line;
            boolean sawSse = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.startsWith("data:")) {
                    sawSse = true;
                    String data = trimmed.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;

                    String delta = extractMiniMaxDelta(data);
                    if (!delta.isEmpty()) {
                        mergeStreamingText(streamed, delta);
                        String partial = cleanMiniMaxOutput(streamed.toString(), false);
                        runOnMain(() -> {
                            hasResult = true;
                            if (infoCard != null) infoCard.setVisibility(View.VISIBLE);
                            setStatus("正在翻译…");
                            setResult(partial.isEmpty() ? "…" : partial);
                        });
                    }
                } else {
                    rawFallback.append(line).append('\n');
                }
            }
            reader.close();

            String result = cleanMiniMaxOutput(streamed.toString(), false);
            if (!result.isEmpty()) return result;

            String fallback = rawFallback.toString().trim();
            if (!fallback.isEmpty()) {
                return cleanMiniMaxOutput(extractMiniMaxContent(fallback), false);
            }
            if (!sawSse) {
                throw new Exception("MiniMax 没有返回流式内容");
            }
            throw new Exception("MiniMax 流式返回为空，已自动尝试非流式快速模式");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void mergeStreamingText(StringBuilder builder, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        String current = builder.toString();
        String next;

        // Some providers return true incremental chunks: "你" -> "好".
        // Some return cumulative chunks: "你" -> "你好" -> "你好啊".
        // If we blindly append cumulative chunks, the UI shows duplicate translations.
        if (current.isEmpty()) {
            next = chunk;
        } else if (chunk.startsWith(current)) {
            next = chunk;
        } else if (current.endsWith(chunk)) {
            next = current;
        } else if (current.contains(chunk)) {
            next = current;
        } else {
            next = current + chunk;
        }

        builder.setLength(0);
        builder.append(next);
    }

    private JSONObject buildMiniMaxRequestBody(String model, String subtitle, boolean learningMode, boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.01);
        body.put("top_p", 0.7);
        body.put("stream", stream);
        body.put("max_completion_tokens", learningMode ? 520 : 220);
        body.put("service_tier", "standard");

        if (model != null && model.toLowerCase(Locale.ROOT).contains("m3")) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
        }

        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("name", "MiniMax AI");
        if (learningMode) {
            system.put("content", "你是英语/日语字幕学习助手。只输出严格 JSON，不要 markdown，不要代码块，不要解释。JSON 格式必须是：{\"中文\":\"自然中文翻译\",\"重点词\":[{\"词\":\"原词或短语\",\"中文\":\"中文释义\",\"词性\":\"名词/动词/副词/短语/助词/句型等\",\"说明\":\"非常短的用法说明\"}],\"语法\":[{\"语法\":\"语法点\",\"中文解释\":\"中文解释\",\"说明\":\"结合本句的简短说明\"}]}。重点词 2-5 个，每个必须有中文释义；语法 1-3 个。");
        } else {
            system.put("content", "你是字幕翻译器。只输出最短自然中文译文，不解释，不带标签，不输出原文。");
        }
        messages.put(system);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("name", "用户");
        user.put("content", learningMode ? "请解析这句字幕并返回 JSON：\n" + subtitle : subtitle);
        messages.put(user);
        body.put("messages", messages);
        return body;
    }

    private String extractMiniMaxDelta(String data) throws Exception {
        JSONObject root = new JSONObject(data);

        if (root.has("base_resp")) {
            JSONObject baseResp = root.optJSONObject("base_resp");
            if (baseResp != null) {
                int statusCode = baseResp.optInt("status_code", 0);
                String statusMsg = baseResp.optString("status_msg", "").trim();
                if (statusCode != 0) {
                    throw new Exception("MiniMax 返回错误 " + statusCode + (statusMsg.isEmpty() ? "" : "：" + statusMsg));
                }
            }
        }

        JSONArray choices = root.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            if (choice != null) {
                JSONObject delta = choice.optJSONObject("delta");
                if (delta != null) {
                    String content = delta.optString("content", "");
                    if (!content.isEmpty()) return content;
                }
                JSONObject message = choice.optJSONObject("message");
                if (message != null) {
                    String content = message.optString("content", "");
                    if (!content.isEmpty()) return content;
                }
                String text = choice.optString("text", "");
                if (!text.isEmpty()) return text;
            }
        }

        String reply = root.optString("reply", "");
        if (!reply.isEmpty()) return reply;
        return "";
    }

    private String cleanMiniMaxOutput(String content, boolean learningMode) {
        if (content == null) return "";
        String result = content.trim();
        result = result.replaceAll("(?i)^\\s*原文[:：].*(\\n|$)", "");
        result = result.replaceAll("(?i)^\\s*译文[:：]\\s*", "");
        result = result.replaceAll("(?m)^\\s*字幕[:：].*$", "");
        result = result.replaceAll("\\n{3,}", "\n\n").trim();
        if (!learningMode) {
            result = result.replaceAll("^中文[:：]\\s*", "").trim();
            result = result.replaceAll("^[「『\"']+", "").replaceAll("[」』\"']+$", "").trim();
        }
        return result;
    }

    private String extractMiniMaxContent(String response) throws Exception {
        JSONObject root = new JSONObject(response);

        if (root.has("base_resp")) {
            JSONObject baseResp = root.optJSONObject("base_resp");
            if (baseResp != null) {
                int statusCode = baseResp.optInt("status_code", 0);
                String statusMsg = baseResp.optString("status_msg", "").trim();
                if (statusCode != 0) {
                    throw new Exception("MiniMax 返回错误 " + statusCode + (statusMsg.isEmpty() ? "" : "：" + statusMsg));
                }
            }
        }

        if (root.optBoolean("input_sensitive", false)) {
            throw new Exception("MiniMax 拒绝输入内容：input_sensitive=true");
        }
        if (root.optBoolean("output_sensitive", false)) {
            throw new Exception("MiniMax 拒绝输出内容：output_sensitive=true");
        }

        if (root.has("choices")) {
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice = choices.optJSONObject(0);
                if (choice != null) {
                    JSONObject message = choice.optJSONObject("message");
                    if (message != null) {
                        String content = message.optString("content", "").trim();
                        if (!content.isEmpty()) return content;

                        String audioContent = message.optString("audio_content", "").trim();
                        if (!audioContent.isEmpty()) return audioContent;

                        String reasoning = message.optString("reasoning_content", "").trim();
                        String finishReason = choice.optString("finish_reason", "").trim();
                        if (!reasoning.isEmpty() && "length".equals(finishReason)) {
                            throw new Exception("MiniMax 返回被截断：finish_reason=length。请更新到修复版或增大 max_completion_tokens。");
                        }
                    }

                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta != null) {
                        String content = delta.optString("content", "").trim();
                        if (!content.isEmpty()) return content;
                    }

                    String text = choice.optString("text", "").trim();
                    if (!text.isEmpty()) return text;

                    String finishReason = choice.optString("finish_reason", "").trim();
                    if (!finishReason.isEmpty()) {
                        throw new Exception("MiniMax 没有返回正文，finish_reason=" + finishReason + "，原始返回：" + compact(response));
                    }
                }
            }
        }

        if (root.has("reply")) {
            String reply = root.optString("reply", "").trim();
            if (!reply.isEmpty()) return reply;
        }
        if (root.has("message")) {
            String message = root.optString("message", "").trim();
            if (!message.isEmpty()) throw new Exception("MiniMax 返回消息：" + message);
        }
        if (root.has("error")) {
            throw new Exception("MiniMax 返回错误：" + compact(root.opt("error").toString()));
        }

        throw new Exception("无法解析 MiniMax 返回内容：" + compact(response));
    }

    private String readStream(InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        reader.close();
        return builder.toString().trim();
    }

    private String compact(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }

    private void setStatus(String text) {
        runOnMain(() -> {
            if (statusText != null) {
                statusText.setText(text);
                if (expanded && infoCard != null) infoCard.setVisibility(View.VISIBLE);
            }
        });
    }

    private TextView createResultText() {
        TextView view = new TextView(this);
        view.setTextColor(Color.argb(252, 246, 249, 253));
        view.setTextSize(14f);
        view.setLineSpacing(dp(3), 1f);
        view.setMaxLines(Integer.MAX_VALUE);
        view.setEllipsize(null);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private String buildOriginalFirstTranslationText(String originalText, String translatedText) {
        String original = originalText == null ? "" : originalText.trim();
        String translated = translatedText == null ? "" : translatedText.trim();

        StringBuilder out = new StringBuilder();
        if (!original.isEmpty()) {
            out.append("原文：\n").append(original).append("\n\n");
        }
        out.append("中文译文：\n");
        out.append(translated.isEmpty() ? "未返回译文" : translated);
        return out.toString().trim();
    }

    private String buildOriginalFirstLearningFallbackText(String originalText, String rawContent) {
        String original = originalText == null ? "" : originalText.trim();
        String raw = rawContent == null ? "" : rawContent.trim();

        StringBuilder out = new StringBuilder();
        if (!original.isEmpty()) {
            out.append("原文：\n").append(original).append("\n\n");
        }
        out.append("学习卡片：\n");
        out.append(raw.isEmpty() ? "未返回学习内容" : raw);
        return out.toString().trim();
    }

    private String buildLearningPlainText(String rawContent) {
        try {
            JSONObject json = new JSONObject(extractJsonObject(rawContent));
            StringBuilder out = new StringBuilder();

            String chinese = json.optString("中文", "").trim();
            if (!chinese.isEmpty()) {
                out.append("中文：\n").append(chinese).append("\n\n");
            }

            JSONArray words = json.optJSONArray("重点词");
            out.append("重点词：\n");
            if (words != null && words.length() > 0) {
                for (int i = 0; i < words.length(); i++) {
                    JSONObject item = words.optJSONObject(i);
                    if (item == null) continue;
                    out.append(i + 1).append(". ");
                    out.append(item.optString("词", "").trim());
                    String meaning = item.optString("中文", "").trim();
                    String pos = item.optString("词性", "").trim();
                    String note = item.optString("说明", "").trim();
                    if (!meaning.isEmpty()) out.append("｜中文：").append(meaning);
                    if (!pos.isEmpty()) out.append("｜词性：").append(pos);
                    if (!note.isEmpty()) out.append("｜说明：").append(note);
                    out.append("\n");
                }
            } else {
                out.append("未返回重点词\n");
            }

            JSONArray grammar = json.optJSONArray("语法");
            out.append("\n语法：\n");
            if (grammar != null && grammar.length() > 0) {
                for (int i = 0; i < grammar.length(); i++) {
                    JSONObject item = grammar.optJSONObject(i);
                    if (item == null) continue;
                    out.append(i + 1).append(". ");
                    out.append(item.optString("语法", "").trim());
                    String meaning = item.optString("中文解释", "").trim();
                    String note = item.optString("说明", "").trim();
                    if (!meaning.isEmpty()) out.append("｜解释：").append(meaning);
                    if (!note.isEmpty()) out.append("｜本句：").append(note);
                    out.append("\n");
                }
            } else {
                out.append("未返回语法点\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            return rawContent == null ? "" : rawContent.trim();
        }
    }

    private void renderLearningCard(String rawContent, String originalText) {
        runOnMain(() -> {
            hasResult = !TextUtils.isEmpty(rawContent);
            if (resultContainer == null) {
                setResult(buildOriginalFirstLearningFallbackText(originalText, rawContent));
                return;
            }

            resultContainer.removeAllViews();
            try {
                String original = originalText == null ? "" : originalText.trim();
                if (!original.isEmpty()) {
                    addSectionTitle("原文");
                    addSimpleCard(original, true);
                }

                JSONObject json = new JSONObject(extractJsonObject(rawContent));

                String chinese = json.optString("中文", "").trim();
                addSectionTitle("中文");
                addSimpleCard(chinese.isEmpty() ? "未返回中文翻译" : chinese, true);

                addSectionTitle("重点词");
                JSONArray words = json.optJSONArray("重点词");
                if (words == null || words.length() == 0) {
                    addSimpleCard("未返回重点词", false);
                } else {
                    for (int i = 0; i < words.length(); i++) {
                        JSONObject item = words.optJSONObject(i);
                        if (item == null) continue;
                        String word = item.optString("词", "").trim();
                        String meaning = item.optString("中文", "").trim();
                        String pos = item.optString("词性", "").trim();
                        String note = item.optString("说明", "").trim();

                        LinearLayout row = createCardLayout();
                        TextView title = createCardTitle(word.isEmpty() ? "词语" : word);
                        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

                        StringBuilder line = new StringBuilder();
                        if (!meaning.isEmpty()) line.append("中文：").append(meaning);
                        if (!pos.isEmpty()) {
                            if (line.length() > 0) line.append("　");
                            line.append("词性：").append(pos);
                        }
                        if (line.length() > 0) row.addView(createCardBody(line.toString()), new LinearLayout.LayoutParams(-1, -2));
                        if (!note.isEmpty()) row.addView(createCardBody("说明：" + note), new LinearLayout.LayoutParams(-1, -2));
                        resultContainer.addView(row, cardLp());
                    }
                }

                addSectionTitle("语法");
                JSONArray grammar = json.optJSONArray("语法");
                if (grammar == null || grammar.length() == 0) {
                    addSimpleCard("未返回语法点", false);
                } else {
                    for (int i = 0; i < grammar.length(); i++) {
                        JSONObject item = grammar.optJSONObject(i);
                        if (item == null) continue;
                        String point = item.optString("语法", "").trim();
                        String meaning = item.optString("中文解释", "").trim();
                        String note = item.optString("说明", "").trim();

                        LinearLayout row = createCardLayout();
                        row.addView(createCardTitle(point.isEmpty() ? "语法点" : point), new LinearLayout.LayoutParams(-1, -2));
                        if (!meaning.isEmpty()) row.addView(createCardBody("解释：" + meaning), new LinearLayout.LayoutParams(-1, -2));
                        if (!note.isEmpty()) row.addView(createCardBody("本句：" + note), new LinearLayout.LayoutParams(-1, -2));
                        resultContainer.addView(row, cardLp());
                    }
                }
            } catch (Exception parseError) {
                addSectionTitle("学习卡片");
                addSimpleCard(rawContent, false);
                addSimpleCard("提示：MiniMax 没有返回标准 JSON，已按普通文本显示。", false);
            }

            if (resultScroll != null) {
                resultScroll.post(() -> resultScroll.scrollTo(0, 0));
            }
            if (infoCard != null) infoCard.setVisibility(View.VISIBLE);
        });
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

    private void addSectionTitle(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(Color.argb(255, 154, 205, 255));
        view.setTextSize(13f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(10), 0, dp(4));
        resultContainer.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSimpleCard(String text, boolean primary) {
        TextView view = createCardBody(text);
        view.setTextSize(primary ? 15f : 14f);
        LinearLayout card = createCardLayout();
        card.addView(view, new LinearLayout.LayoutParams(-1, -2));
        resultContainer.addView(card, cardLp());
    }

    private LinearLayout createCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(112, 18, 30, 46));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(95, 92, 156, 220));
        card.setBackground(bg);
        return card;
    }

    private TextView createCardTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.argb(255, 244, 229, 191));
        view.setTextSize(14f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView createCardBody(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextColor(Color.argb(245, 236, 242, 250));
        view.setTextSize(13f);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(2), 0, 0);
        return view;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(5));
        return lp;
    }

    private void setResult(String text) {
        runOnMain(() -> {
            hasResult = !TextUtils.isEmpty(text);
            if (resultContainer != null) {
                resultContainer.removeAllViews();
                if (resultText == null) resultText = createResultText();
                resultText.setText(text);
                resultContainer.addView(resultText, new LinearLayout.LayoutParams(-1, -2));
            } else if (resultText != null) {
                resultText.setText(text);
            }
            if (resultScroll != null) {
                resultScroll.post(() -> resultScroll.scrollTo(0, 0));
            }
            if (infoCard != null) infoCard.setVisibility(View.VISIBLE);
        });
    }

    private void showToast(String text) {
        runOnMain(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private void runOnMain(Runnable runnable) {
        uiHandler.post(runnable);
    }

    private Notification createNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("拂月译屏正在运行")
                .setContentText("轻触“幕”悬浮珠展开/收起字幕翻译菜单")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "字幕翻译器运行状态",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private int safeScreenWidth() {
        if (screenWidth > 0) return screenWidth;
        return Math.max(1, getResources().getDisplayMetrics().widthPixels);
    }

    private int safeScreenHeight() {
        if (screenHeight > 0) return screenHeight;
        return Math.max(1, getResources().getDisplayMetrics().heightPixels);
    }

    private float shortestWidthDp() {
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        return Math.min(safeScreenWidth(), safeScreenHeight()) / density;
    }

    private boolean isTabletLayout() {
        return shortestWidthDp() >= 600f;
    }

    private int clampPx(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private int adaptiveSideMargin() {
        return isTabletLayout() ? dp(12) : dp(6);
    }

    private int adaptiveInfoCardWidth() {
        int w = safeScreenWidth();
        if (isTabletLayout()) {
            int max = Math.min(dp(420), w - adaptiveSideMargin() * 2);
            return clampPx(max, Math.min(dp(320), Math.max(1, w - dp(24))), Math.max(1, w - dp(24)));
        } else {
            int max = w - adaptiveSideMargin() * 2;
            int desired = Math.min(dp(330), max);
            return clampPx(desired, Math.min(dp(250), Math.max(1, max)), Math.max(1, max));
        }
    }

    private int adaptiveInfoCardBottomMargin() {
        int h = safeScreenHeight();
        if (isTabletLayout()) {
            return h < dp(720) ? dp(72) : dp(92);
        }
        if (h < dp(560)) return dp(46);
        if (h < dp(700)) return dp(56);
        return dp(68);
    }

    private int adaptiveResultHeight(boolean backtrackVisible) {
        int h = safeScreenHeight();
        int available = Math.max(dp(90), h - adaptiveInfoCardBottomMargin() - dp(backtrackVisible ? 150 : 100));

        if (isTabletLayout()) {
            int desired = backtrackVisible ? (int) (h * 0.24f) : (int) (h * 0.30f);
            int max = backtrackVisible ? dp(230) : dp(270);
            int min = backtrackVisible ? dp(120) : dp(150);
            return clampPx(Math.min(desired, available), Math.min(min, available), Math.min(max, available));
        } else {
            int desired = backtrackVisible ? (int) (h * 0.18f) : (int) (h * 0.22f);
            int max = backtrackVisible ? dp(145) : dp(170);
            int min = backtrackVisible ? dp(82) : dp(105);
            return clampPx(Math.min(desired, available), Math.min(min, available), Math.min(max, available));
        }
    }

    private int adaptivePreviewTopMargin() {
        int h = safeScreenHeight();
        if (isTabletLayout()) {
            return h < dp(720) ? dp(24) : dp(36);
        }
        return h < dp(600) ? dp(8) : dp(12);
    }

    private int adaptivePreviewWidth() {
        int w = safeScreenWidth();
        if (isTabletLayout()) {
            int margin = dp(24);
            int maxWidth = w - margin * 2;
            return clampPx(maxWidth, Math.min(dp(420), Math.max(1, maxWidth)), Math.max(1, maxWidth));
        } else {
            int margin = dp(8);
            int maxWidth = w - margin * 2;
            return clampPx(maxWidth, Math.min(dp(260), Math.max(1, maxWidth)), Math.max(1, maxWidth));
        }
    }

    private int adaptivePreviewHeight() {
        int h = safeScreenHeight();
        int top = adaptivePreviewTopMargin();

        if (isTabletLayout()) {
            int bottomSafe = dp(72);
            int available = Math.max(dp(220), h - top - bottomSafe);
            int desired = (int) (h * 0.68f);
            return clampPx(desired, Math.min(dp(220), available), available);
        } else {
            // 手机上预览窗必须更保守，避免压住菜单/信息卡或超出屏幕。
            int bottomSafe = h < dp(620) ? dp(132) : dp(150);
            int available = Math.max(dp(150), h - top - bottomSafe);
            int desired = h < dp(620) ? (int) (h * 0.42f) : (int) (h * 0.48f);
            return clampPx(desired, Math.min(dp(145), available), available);
        }
    }

    private int adaptiveGuideLabelWidth() {
        int w = safeScreenWidth();
        int margin = isTabletLayout() ? dp(32) : dp(16);
        int max = Math.max(1, w - margin * 2);
        return clampPx(max, Math.min(dp(220), max), max);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void releaseProjection() {
        cancelDim();
        cancelAutoCollapse();
        hideAreaGuideOverlay();
        hideBacktrackControls();
        stopSubtitleBacktrackBuffer();
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }
    }

    @Override
    public void onDestroy() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        if (visualOverlayView != null) {
            try {
                windowManager.removeView(visualOverlayView);
            } catch (Exception ignored) {
            }
            visualOverlayView = null;
        }
        recycleBacktrackPreviewBitmap();
        releaseProjection();
        if (latinRecognizer != null) latinRecognizer.close();
        if (japaneseRecognizer != null) japaneseRecognizer.close();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
