package com.jianyi.dict;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String[] TAG_KEYS = {"zk", "gk", "cet4", "cet6", "ky", "ielts", "toefl", "gre"};
    private static final String[] TAG_NAMES = {"中考", "高考", "四级", "六级", "考研", "雅思", "托福", "GRE"};

    private DictDb dict;                 // 后台线程初始化，主线程只读引用
    private HistoryDb history;
    private volatile boolean dictReady = false;
    private volatile String dictError = null;
    private final ExecutorService dbPool = Executors.newSingleThreadExecutor();
    private final ExecutorService netPool = Executors.newSingleThreadExecutor(); // 网络请求独立线程，不阻塞词典查询
    private final AtomicInteger searchSeq = new AtomicInteger();

    private View tabDict, tabTranslate, tabCamera, pageHistory;
    private SwipeHost swipeHost;
    private TextView btnHistory;
    private EditText searchBox;
    private PullListView resultList;
    private ListView historyList;
    private ItemAdapter adapter;
    private HistoryAdapter historyAdapter;

    private TextView btnTranslate, translateOutput, cloudToggle;
    private Translator translatorZhEn, translatorEnZh;
    private boolean translating = false;
    private android.content.SharedPreferences prefs;

    private EditText ocrText;
    private Uri pendingPhotoUri;
    private TextRecognizer recognizer;

    // ===== 发音 =====
    private Pron pron;
    private String pronWord;    // 当前在加载/播放的词
    private int pronState = -1;

    // ===== 皮肤 =====
    private static final String[] SKIN_MODES = {"default", "green", "cream", "photo"};
    private static final String[] SKIN_LABELS = {"默认（跟随外观）", "护眼绿", "米黄纸", "相册背景"};
    private static final String[] MASK_LABELS = {"自动（跟随外观）", "白色", "黑色"};
    private Bitmap skinBmp;     // 相册背景的缓存，避免每次切页都重新解码
    private AlertDialog skinDialog;

    /**
     * 页面序号。听力放在最左一格：首页向右滑进入，全屏沉浸式、不设标签入口；
     * 查词是首页，向左滑依次是翻译、拍照。
     */
    private static final int PAGE_LISTEN = 0;
    private static final int PAGE_DICT = 1;
    private static final int PAGE_TRANS = 2;
    private static final int PAGE_CAM = 3;

    private View topBar, tabBar, topDivider, pageStudy, pageReading;
    private View contentHost;               // 顶栏/标签栏下面的内容容器（几何补偿用）
    private DragHint studyHint, readingHint;
    private TextView pullHint, studyProgress, readingTitle;
    private LinearLayout studyBody, readingBody;
    private StudyDb studyDb;
    private ArrayList<Quiz> quizGroup;
    private int quizIndex, quizRight;
    private ArrayList<String> quizWrongWords;

    private ArrayList<ReadingItem> readingItems;
    private String readingId;      // 当前在读的那一期；null 表示停在列表

    // ===== 拍照框选 =====
    private View pageCrop;
    private CropView cropView;
    private Bitmap pendingOcrBmp;

    // ===== 听力 =====
    private LinearLayout listenBody;
    private TextView listenTitle;
    private ScrollView listenScroll;
    private ArrayList<ListenItem> listenItems;
    private String listenId;                    // null 表示停在列表
    private MediaPlayer listenPlayer;
    private ArrayList<Lrc.Line> listenLines;
    private ArrayList<TextView> listenLineViews;
    private int listenIndex = -1;
    private Handler listenHandler;
    private Runnable listenTick;
    private SeekBar listenSeek;

    /** 阅读素材索引里的一条（对应 manifest.json 的 items[]） */
    private static class ReadingItem {
        String id, title, titleZh, topic, level;
        String text, translation;   // 相对素材根目录的路径，如 articles/r-002.txt
        boolean builtin;
        int words;
    }

    /** 列表条目：type=0 结果卡片，type=1 联想词，type=2 提示文案 */
    private static class Item {
        int type;
        DictDb.Entry entry;
        String text;
        String sub;
        ArrayList<DictDb.Example> examples;  // 结果卡片下方的例句
    }

    /** 外观模式来自用户设置，必须在取资源之前改写 uiMode，否则深色不生效 */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(ThemeHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashCatcher();
        setContentView(R.layout.activity_main);
        history = new HistoryDb(this);

        btnHistory = findViewById(R.id.btnHistory);
        tabDict = findViewById(R.id.tabDict);
        tabTranslate = findViewById(R.id.tabTranslate);
        tabCamera = findViewById(R.id.tabCamera);
        swipeHost = findViewById(R.id.swipeHost);
        swipeHost.setListener(this::styleTabs);
        swipeHost.setSnapMs(PAGE_LISTEN, 620);   // 听力页是全屏沉浸页，进出翻页慢一点
        pageHistory = findViewById(R.id.pageHistory);
        searchBox = findViewById(R.id.searchBox);
        resultList = findViewById(R.id.resultList);
        topBar = findViewById(R.id.topBar);
        tabBar = findViewById(R.id.tabBar);
        topDivider = findViewById(R.id.topDivider);
        pullHint = findViewById(R.id.pullHint);
        studyDb = new StudyDb(this);
        pageStudy = findViewById(R.id.pageStudy);
        studyBody = findViewById(R.id.studyBody);
        studyProgress = findViewById(R.id.studyProgress);
        findViewById(R.id.studyBack).setOnClickListener(v -> closeStudy());
        pageReading = findViewById(R.id.pageReading);
        readingBody = findViewById(R.id.readingBody);
        readingTitle = findViewById(R.id.readingTitle);
        findViewById(R.id.readingBack).setOnClickListener(v -> closeReading());
        listenBody = findViewById(R.id.listenBody);
        listenTitle = findViewById(R.id.listenTitle);
        listenScroll = findViewById(R.id.listenScroll);
        listenHandler = new Handler(Looper.getMainLooper());
        findViewById(R.id.listenBack).setOnClickListener(v -> closeListen());
        loadListenManifest();
        renderListenList();
        pageCrop = findViewById(R.id.pageCrop);
        cropView = findViewById(R.id.cropView);
        // 退出方向＝进入方向的反向（哪来的回哪去，页面始终跟着手指走）：
        //   记背页 下拉进入 → 上滑返回；阅读页 上拉进入 → 下滑返回。
        // 两条路（拖拽把手 / 滚到边界再拖）统一由 setupExitSwipe 接好。
        final Runnable exitStudy = () -> applyImmersive(false);
        final Runnable exitReading = () -> {
            readingId = null;                 // 退出到首页，下次进来停在列表
            renderReadingList();
            applyImmersive(false);
        };
        studyHint = setupExitSwipe(R.id.studyScroll, R.id.studyHint,
                pageStudy, false, exitStudy);      // 下拉进入 → 上滑返回
        readingHint = setupExitSwipe(R.id.readingScroll, R.id.readingHint,
                pageReading, true, exitReading);   // 上拉进入 → 下滑返回
        findViewById(R.id.cropBack).setOnClickListener(v -> closeCrop());
        findViewById(R.id.btnCropAll).setOnClickListener(v -> {
            Bitmap b = pendingOcrBmp;
            closeCrop();
            if (b == null) return;
            ocrText.setText("识别中…");
            runOcr(b);
        });
        findViewById(R.id.btnCropOk).setOnClickListener(v -> {
            Bitmap b = pendingOcrBmp;
            Rect r = cropView == null ? null : cropView.cropRect();
            closeCrop();
            if (b == null) return;
            ocrText.setText("识别中…");
            if (r == null || r.width() < 4 || r.height() < 4) {
                runOcr(b);
                return;
            }
            try {
                Bitmap cut = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
                runOcr(cut);
            } catch (Throwable t) {
                runOcr(b);      // 裁剪出意外就退回整张，别让用户白拍
            }
        });
        // 布局完成后再定位到首页（onCreate 时宽度还是 0）
        swipeHost.post(() -> swipeHost.jumpTo(PAGE_DICT));
        setupPullGestures();
        historyList = findViewById(R.id.historyList);
        btnTranslate = findViewById(R.id.btnTranslate);
        translateOutput = findViewById(R.id.translateOutput);
        // 译文区支持内部滚动（长译文可滑到底）+ 显示滚动条提示
        translateOutput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        translateOutput.setVerticalScrollBarEnabled(true);
        cloudToggle = findViewById(R.id.cloudToggle);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        pron = new Pron(this);
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnSkin).setOnClickListener(v -> showSkinDialog());
        applySystemBars();
        applySkin();

        btnTranslate.setOnClickListener(v -> doTranslate());
        cloudToggle.setOnClickListener(v -> showCloudDialog());
        refreshCloudLabel();

        // 长按译文一键复制
        translateOutput.setOnLongClickListener(v -> {
            CharSequence all = translateOutput.getText();
            if (all == null || all.length() < 4) return true;
            int i = all.toString().indexOf('\n');
            String body = i > 0 ? all.toString().substring(i + 1) : all.toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("translation", body));
            Toast.makeText(this, "已复制译文", Toast.LENGTH_SHORT).show();
            return true;
        });

        // ===== 拍照翻译页 =====
        ocrText = findViewById(R.id.ocrText);
        findViewById(R.id.btnTakePhoto).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnPickImage).setOnClickListener(v -> pickImage());
        findViewById(R.id.btnOcrTranslate).setOnClickListener(v -> {
            String text = ocrText.getText() == null ? "" : ocrText.getText().toString().trim();
            if (text.isEmpty() || text.startsWith("识别中") || text.startsWith("未识别")
                    || text.startsWith("图片读取失败") || text.startsWith("文字识别失败")) {
                Toast.makeText(this, "先拍照或选图，识别出文字", Toast.LENGTH_SHORT).show();
                return;
            }
            ((EditText) findViewById(R.id.translateInput)).setText(text);
            switchTab(PAGE_TRANS);
            doTranslate();
        });

        adapter = new ItemAdapter();
        resultList.setAdapter(adapter);
        historyAdapter = new HistoryAdapter();
        historyList.setAdapter(historyAdapter);
        refreshHistory();
        showLoading("词典首次启动解压中…\n仅此一次，几秒后可用");

        // 词典在后台线程初始化，界面绝不卡顿
        dbPool.execute(() -> {
            try {
                DictDb d = new DictDb(MainActivity.this);
                runOnUiThread(() -> {
                    dict = d;
                    dictReady = true;
                    adapter.items.clear();
                    adapter.notifyDataSetChanged();
                });
            } catch (Throwable t) {
                dictError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnUiThread(() -> showLoading("词典初始化失败：" + dictError));
            }
        });

        tabDict.setOnClickListener(v -> switchTab(PAGE_DICT));
        tabTranslate.setOnClickListener(v -> switchTab(PAGE_TRANS));
        tabCamera.setOnClickListener(v -> switchTab(PAGE_CAM));
        btnHistory.setOnClickListener(v -> showHistory(true));
        findViewById(R.id.titleText).setOnClickListener(v -> showHistory(false));
        findViewById(R.id.btnClearHistory).setOnClickListener(v -> {
            history.clear();
            refreshHistory();
        });

        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doLookup(searchBox.getText().toString());
                return true;
            }
            return false;
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void afterTextChanged(Editable s) {
                findViewById(R.id.searchClear).setVisibility(
                        s.length() > 0 ? View.VISIBLE : View.GONE);
                scheduleSuggest(s.toString());
            }
        });
        // 点查词框：全选旧词（直接打字就覆盖）+ 立刻呼出键盘，省掉手动删词那一步。
        // 用 Touch 而不是 Click 监听：EditText 已有焦点时 Click 不一定回调，Touch 一定到。
        // return false = 不消费事件，光标定位等默认行为照旧。
        searchBox.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (!searchBox.hasFocus()) searchBox.requestFocus();
                searchBox.selectAll();
                showKeyboard(searchBox);
            }
            return false;
        });
        searchBox.setOnFocusChangeListener((v, has) -> {
            if (has) searchBox.post(() -> searchBox.selectAll());
        });

        // 叉号：清空 + 保持焦点并呼出键盘，点完就能直接输下一个词
        findViewById(R.id.searchClear).setOnClickListener(v -> {
            searchBox.setText("");
            searchBox.requestFocus();
            showKeyboard(searchBox);
        });

        historyList.setOnItemClickListener((parent, view, pos, id) -> {
            String w = historyAdapter.words.get(pos);
            showHistory(false);
            searchBox.setText(w);
            searchBox.setSelection(w.length());
            doLookup(w);
        });

        historyList.setOnItemLongClickListener((parent, view, pos, id) -> {
            String w = historyAdapter.words.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("删除这条记录？")
                    .setMessage(w)
                    .setPositiveButton("删除", (d, x) -> {
                        history.delete(w);
                        refreshHistory();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        // 标题长按显示版本信息（排查问题时用）
        findViewById(R.id.titleText).setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("简译")
                    .setMessage("版本 " + getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME
                            + "\n词典 35.5 万词条（ECDICT 精选）\n翻译引擎：ML Kit 离线 + 云端增强（可选）\n文字识别：ML Kit 离线 OCR")
                    .setPositiveButton("好", null)
                    .show();
            return true;
        });

        resultList.setOnItemClickListener((parent, view, pos, id) -> {
            Item it = adapter.items.get(pos);
            if (it.type == 1) {
                searchBox.setText(it.text);
                searchBox.setSelection(it.text.length());
                doLookup(it.text);
            }
        });
    }

    // ================= 后台检索：联想 =================

    private void scheduleSuggest(String raw) {
        final int seq = searchSeq.incrementAndGet();
        final String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) {
            runOnUiThread(() -> {
                if (searchSeq.get() == seq) {
                    adapter.items.clear();
                    adapter.notifyDataSetChanged();
                }
            });
            return;
        }
        if (!dictReady) {
            runOnUiThread(() -> {
                if (searchSeq.get() == seq) {
                    showLoading(dictError != null
                            ? "词典加载失败：" + dictError
                            : "词典加载中…");
                }
            });
            return;
        }
        dbPool.execute(() -> {
            ArrayList<Item> items = new ArrayList<>();
            try {
                if (isChinese(q)) {
                    // 中文反查：拿释义里的中文找英文单词
                    for (DictDb.Entry e : dict.lookupZh(q, 15)) {
                        Item it = new Item();
                        it.type = 1;
                        it.text = e.word;
                        it.entry = e;
                        it.sub = matchLine(e.translation, q);
                        items.add(it);
                    }
                    if (items.isEmpty()) {
                        Item tip = new Item();
                        tip.type = 2;
                        tip.text = "没有找到释义含「" + q + "」的单词";
                        tip.sub = "试试别的说法，如：愉快 → happy、帮助 → help";
                        items.add(tip);
                    }
                } else {
                    ArrayList<String> sug = dict.suggest(q, 15);
                    for (String w : sug) {
                        DictDb.Entry e = dict.lookup(w);
                        Item it = new Item();
                        it.type = 1;
                        it.text = w;
                        it.entry = e;   // 带上词条，联想列表才能显示六级/高频标记
                        it.sub = e != null ? firstLine(e.translation) : "";
                        items.add(it);
                    }
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                if (searchSeq.get() != seq) return; // 已有更新的输入，丢弃旧结果
                adapter.items = items;
                adapter.notifyDataSetChanged();
            });
        });
    }

    // ================= 后台检索：查词 =================

    private void doLookup(String input) {
        final String q = input == null ? "" : input.trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        if (!dictReady) {
            Toast.makeText(this, dictError != null ? "词典加载失败：" + dictError : "词典加载中，请稍候一秒", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isChinese(q)) {
            // 输入的是中文：按回车 = 确认反查，直接给出英文候选列表
            scheduleSuggest(q);
            return;
        }
        final int seq = searchSeq.incrementAndGet();
        dbPool.execute(() -> {
            DictDb.Entry e = null;
            try {
                e = dict.lookup(q);
            } catch (Throwable ignored) {
            }
            final DictDb.Entry found = e;
            ArrayList<DictDb.Example> exs = new ArrayList<>();
            if (found != null) {
                try {
                    exs = dict.examples(found.word);
                } catch (Throwable ignored) {
                }
            }
            final ArrayList<DictDb.Example> examples = exs;
            runOnUiThread(() -> {
                if (searchSeq.get() != seq) return;
                adapter.items.clear();
                if (found != null) {
                    history.add(found.word);
                    refreshHistory();
                    pron.prefetch(found.word);   // 后台悄悄取回发音，点喇叭时秒出
                    Item it = new Item();
                    it.type = 0;
                    it.entry = found;
                    it.examples = examples;
                    adapter.items.add(it);
                } else {
                    Item it = new Item();
                    it.type = 2;
                    it.text = "未收录「" + q + "」";
                    it.sub = "试试单词原型，如 going → go";
                    adapter.items.add(it);
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void showLoading(String msg) {
        adapter.items.clear();
        Item it = new Item();
        it.type = 2;
        it.text = msg;
        it.sub = "";
        adapter.items.add(it);
        adapter.notifyDataSetChanged();
    }

    // ================= 崩溃自捕获：下次启动可查看并复制 =================

    private void installCrashCatcher() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                File f = new File(getFilesDir(), "crash.log");
                PrintWriter pw = new PrintWriter(new FileWriter(f));
                pw.println("---- " + System.currentTimeMillis() + " ----");
                pw.println(sw.toString());
                pw.close();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
        File f = new File(getFilesDir(), "crash.log");
        if (f.exists() && f.length() > 0) {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                StringBuilder sb = new StringBuilder();
                String line;
                int n = 0;
                while ((line = r.readLine()) != null && n < 30) {
                    sb.append(line).append('\n');
                    n++;
                }
                r.close();
                f.delete();
                showCrashDialog(sb.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    private void showCrashDialog(String text) {
        new AlertDialog.Builder(this)
                .setTitle("上次运行出错了")
                .setMessage(text)
                .setPositiveButton("复制并关闭", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                    Toast.makeText(this, "已复制，发给联创即可定位", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("忽略", null)
                .show();
    }

    // ================= 页面切换 =================

    private void switchTab(int i) {
        showHistory(false);
        swipeHost.snapTo(i);   // 高亮由 onPageChanged 回调统一刷新
    }

    /**
     * 页面变化：普通页显示顶栏和标签，听力页走沉浸式（整条顶栏标签都隐藏）。
     * 隐藏/显示都是淡入淡出，不会瞬间跳变；高度变化也不会打断 SwipeHost 的翻页动画。
     */
    private void styleTabs(int i) {
        setChromeVisible(i != PAGE_LISTEN);
        styleTab(tabDict, i == PAGE_DICT);
        styleTab(tabTranslate, i == PAGE_TRANS);
        styleTab(tabCamera, i == PAGE_CAM);
        if (i != PAGE_LISTEN) pauseListenIfPlaying();   // 滑走就停，别让声音在后台跑
    }

    /**
     * 顶栏 / 分隔线 / 标签栏的显隐。
     *
     * 除了淡入淡出，还必须做一件事：这三条是**占布局高度**的，直接 setVisibility 会让
     * 下方内容区整体窜高（或变矮）约 90dp —— 切到听力页时用户看到的"跳一下"就是它。
     * 处理办法：在几何真正变化的那一刻给内容区一个等量反向位移顶住，再动画归零，
     * 视觉上就变成内容区平滑地长高 / 收回，一点也不跳。
     */
    private void setChromeVisible(boolean visible) {
        if (contentHost == null) contentHost = findViewById(R.id.contentHost);
        final View[] bars = {topBar, topDivider, tabBar};

        if (visible) {
            int delta = 0;
            for (View v : bars) {
                if (v != null && v.getVisibility() != View.VISIBLE) delta += chromeHeight(v);
            }
            for (final View v : bars) {
                if (v == null) continue;
                v.animate().cancel();
                if (v.getVisibility() != View.VISIBLE) {
                    v.setAlpha(0f);
                    v.setVisibility(View.VISIBLE);
                }
                if (v.getAlpha() < 1f) v.animate().alpha(1f).setDuration(180).start();
            }
            settleContent(-delta);          // 内容区会被顶下去，先往上顶回来
        } else {
            int delta = 0;
            final int[] left = {0};
            for (View v : bars) {
                if (v != null && v.getVisibility() == View.VISIBLE) {
                    delta += v.getHeight();
                    left[0]++;
                }
            }
            final int d = delta;
            for (final View v : bars) {
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                v.animate().cancel();
                v.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    v.setVisibility(View.GONE);
                    v.setAlpha(1f);
                    // 三条全部隐藏、几何真正变化的那一刻才补偿，避免漏出中间帧
                    if (--left[0] == 0) settleContent(d);
                }).start();
            }
        }
    }

    /** 已隐藏时 getHeight() 仍是上次量到的高度，够用；万一没量到给个兜底值 */
    private int chromeHeight(View v) {
        int h = v.getHeight();
        if (h > 0) return h;
        return v == topDivider ? 1 : dp(46);
    }

    /**
     * 顶住一次"内容区高度突变"：先给等量反向位移，再动画归零。
     * offset > 0 表示内容区刚被顶高 —— 先往下压回原位，再放开。
     */
    private void settleContent(int offset) {
        if (contentHost == null || offset == 0) return;
        contentHost.animate().cancel();
        contentHost.setTranslationY(offset);
        contentHost.animate().translationY(0)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void styleTab(View tab, boolean on) {
        TextView t = (TextView) tab;
        t.setTextColor(getColor(on ? R.color.text_primary : R.color.text_secondary));
        t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ================= 纵向手势：防误触版 =================

    /**
     * 列表滚到顶部继续下拉 → 记背；滚到底部继续上拉 → 阅读。
     * 只有处于两端边缘、且手指位移超过起始阈值时才接管，
     * 中间随便怎么滑都只是在滚列表——这是防误触的关键。
     */
    private void setupPullGestures() {
        final int startGap = dp(12);     // 判定方向的起始位移
        final int trigger = dp(170);     // 松手生效的位移阈值（120 太灵敏，容易误触）
        final float[] startY = {0};
        final int[] mode = {0};          // 0 未接管 / -1 下拉(记背) / 1 上拉(阅读)
        final boolean[] armed = {false};

        // 事件改从 PullListView.dispatchTouchEvent 抄送过来。
        // 之前挂 setOnTouchListener，只要列表项消费了事件就收不到，手势时灵时不灵。
        resultList.setWatcher((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startY[0] = ev.getY();
                    mode[0] = 0;
                    armed[0] = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dy = ev.getY() - startY[0];
                    if (mode[0] == 0) {
                        if (dy > startGap && !resultList.canScrollVertically(-1)) mode[0] = -1;
                        else if (dy < -startGap && !resultList.canScrollVertically(1)) mode[0] = 1;
                    }
                    if (mode[0] != 0) {
                        armed[0] = Math.abs(dy) > trigger;
                        pullHint.setText(armed[0]
                                ? (mode[0] == -1 ? "松手进入背单词" : "松手进入阅读")
                                : (mode[0] == -1 ? "继续下拉进入背单词" : "继续上拉进入阅读"));
                        resultList.setTranslationY(dy * 0.35f);
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (mode[0] != 0) {
                        resultList.setTranslationY(0);
                        pullHint.setText("");
                        if (armed[0]) {
                            if (mode[0] == -1) openStudy();          // 下拉：单词记背
                            else openReading();                      // 上拉：沉浸阅读
                        }
                    }
                    mode[0] = 0;
                    armed[0] = false;
                    break;
            }
            // 只观察、不消费：列表自身的滚动与点击照常
        });
    }

    // ================= 沉浸阅读 =================

    private void openReading() {
        applyImmersive(true);
        slideIn(pageReading, false);     // 上拉手势进入 → 从下方滑入
        if (readingHint != null) readingHint.showHint();   // 亮一下"↓ 下滑返回"，提示手势位置
        readingId = null;
        if (readingItems == null) loadReadingManifest();
        renderReadingList();
    }

    /** 返回：先退出正文回到列表，再退才关掉整个阅读页 */
    private void closeReading() {
        if (readingId != null) {
            readingId = null;
            renderReadingList();
            return;
        }
        if (pageReading == null || pageReading.getVisibility() != View.VISIBLE) return;
        slideOut(pageReading, false, () -> applyImmersive(false));  // 下滑退出 → 往下移出
    }

    private void loadReadingManifest() {
        readingItems = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(readAsset("reading/manifest.json"));
            packBase = root.optString("baseUrl", packBase);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ReadingItem it = new ReadingItem();
                it.id = o.optString("id");
                it.title = o.optString("title");
                it.titleZh = o.optString("titleZh");
                it.topic = o.optString("topic");
                it.level = o.optString("level");
                it.words = o.optInt("words");
                it.builtin = o.optBoolean("builtin", true);
                it.text = o.optString("text");
                it.translation = o.optString("translation");
                readingItems.add(it);
            }
        } catch (Throwable ignored) {
        }
    }

    private String readAsset(String name) {
        try {
            InputStream is = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private void renderReadingList() {
        readingBody.removeAllViews();
        if (readingTitle != null) readingTitle.setText("沉浸阅读");
        if (readingItems == null || readingItems.isEmpty()) {
            readingBody.addView(readingText("素材还没准备好", 15, R.color.text_secondary));
            return;
        }
        readingBody.addView(readingText(
                "直接读英文，遇到生词点一下就出释义；整段读不懂再点「翻译本段」。",
                13, R.color.text_secondary));
        for (ReadingItem it : readingItems) {
            boolean ready = materialReady("reading", it.text);
            TextView t = optionButton(it.titleZh + "\n" + it.topic + " · " + it.words + " 词"
                    + (ready ? "" : "\n（未下载，需开启素材包）"));
            t.setOnClickListener(v -> {
                if (!materialReady("reading", it.text)) {
                    Toast.makeText(this, "这篇还没下载：设置 → 素材包 → 开启", Toast.LENGTH_SHORT).show();
                    return;
                }
                openArticle(it);
            });
            readingBody.addView(t);
        }
    }

    private void openArticle(ReadingItem it) {
        readingId = it.id;
        String en = readMaterial("reading", it.text);
        if (en.isEmpty()) {
            readingBody.removeAllViews();
            readingBody.addView(readingText("这篇内容没读到", 15, R.color.text_secondary));
            return;
        }

        readingBody.removeAllViews();
        if (readingTitle != null) readingTitle.setText(it.words + " 词");

        TextView head = readingText(it.titleZh, 20, R.color.text_primary);
        head.setTypeface(null, Typeface.BOLD);
        readingBody.addView(head);

        // 中文梗概：默认收起，想看再点，避免养成依赖
        String zh = readMaterial("reading", it.translation).trim();
        if (!zh.isEmpty()) {
            TextView toggle = readingText("看中文梗概 ▾", 13, R.color.text_secondary);
            toggle.setPadding(dp(2), dp(10), dp(2), dp(6));
            TextView body = readingText(zh, 15, R.color.text_secondary);
            body.setVisibility(View.GONE);
            body.setLineSpacing(dp(2), 1.2f);
            toggle.setOnClickListener(v -> {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText(show ? "收起中文梗概 ▴" : "看中文梗概 ▾");
            });
            readingBody.addView(toggle);
            readingBody.addView(body);
        }

        // 正文：## 开头的是篇名，其余是段落
        for (String raw : en.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("## ")) {
                TextView t = readingText(line.substring(3), 16, R.color.text_primary);
                t.setTypeface(null, Typeface.BOLD);
                t.setPadding(0, dp(18), 0, dp(4));
                readingBody.addView(t);
                continue;
            }
            addParagraph(line);
        }
    }

    /** 一段正文：每个单词可点，点了弹释义；下面挂一个「翻译本段」 */
    private void addParagraph(String para) {
        TextView tv = new TextView(this);
        tv.setText(clickableWords(para));
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(17);
        tv.setLineSpacing(dp(3), 1.35f);
        tv.setHighlightColor(Color.TRANSPARENT);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        readingBody.addView(tv);

        final TextView out = readingText("", 15, R.color.text_secondary);
        out.setVisibility(View.GONE);
        out.setLineSpacing(dp(2), 1.2f);
        TextView btn = readingText("翻译本段 ▾", 13, R.color.text_secondary);
        btn.setPadding(dp(2), dp(2), dp(2), dp(14));
        btn.setOnClickListener(v -> {
            if (out.getVisibility() == View.VISIBLE) {
                out.setVisibility(View.GONE);
                btn.setText("翻译本段 ▾");
                return;
            }
            out.setVisibility(View.VISIBLE);
            btn.setText("收起译文 ▴");
            if (out.getText().length() == 0) translateParagraph(para, out);
        });
        readingBody.addView(btn);
        readingBody.addView(out);
    }

    /** 把段落里的每个英文单词做成可点区域 */
    private SpannableString clickableWords(String para) {
        SpannableString ss = new SpannableString(para);
        Matcher m = Pattern.compile("[A-Za-z’']+").matcher(para);
        while (m.find()) {
            final String word = m.group();
            if (word.length() < 2) continue;
            int start = m.start(), end = m.end();
            ss.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    lookupWord(word);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setUnderlineText(false);               // 不加下划线，保持版面干净
                    ds.setColor(getColor(R.color.text_primary));
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /** 阅读页点词：查词典并弹小卡片；查不到就试着去掉复数/所有格再查一次 */
    private void lookupWord(String raw) {
        final String key = raw.toLowerCase(Locale.US).replaceAll("[^a-z']", "");
        if (key.isEmpty()) return;
        dbPool.execute(() -> {
            DictDb.Entry e = dict.lookup(key);
            if (e == null && key.endsWith("s")) e = dict.lookup(key.substring(0, key.length() - 1));
            if (e == null && key.endsWith("es")) e = dict.lookup(key.substring(0, key.length() - 2));
            final DictDb.Entry fe = e;
            runOnUiThread(() -> showWordCard(key, fe));
        });
    }

    private void showWordCard(String word, DictDb.Entry e) {
        String body = (e == null || e.translation == null || e.translation.isEmpty())
                ? "词典里没找到这个词" : firstMeaning(e.translation);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(word)
                .setMessage(body)
                .setNegativeButton("关闭", null);
        if (e != null) {
            b.setNeutralButton("朗读", (d, w) -> onSpeakerClick(word));
            b.setPositiveButton("加入复习", (d, w) -> {
                if (studyDb != null) studyDb.mark(word);
                Toast.makeText(this, "已加入复习", Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }

    /** 整段翻译走 ML Kit 离线引擎；首次使用需联网下语言包 */
    private void translateParagraph(String text, TextView target) {
        target.setText("翻译中…");
        Translator t = getEnZh();
        t.downloadModelIfNeeded()
                .addOnSuccessListener(v -> t.translate(text)
                        .addOnSuccessListener(res -> target.setText(res))
                        .addOnFailureListener(e -> target.setText("翻译失败：" + friendly(e))))
                .addOnFailureListener(e -> target.setText("语言包下载失败：" + friendly(e)));
    }

    private TextView readingText(String text, float sizeSp, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(getColor(colorRes));
        return t;
    }

    // ================= 素材包：内置之外的素材按需下载 =================

    private static final String PREF_PACK = "asset_pack";
    private String packBase = "";          // 清单里给的根地址
    private volatile boolean packBusy;
    private int packDone, packTotal;

    private File packDir() {
        File d = new File(getFilesDir(), "pack");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String relName(String rel) {
        if (rel == null) return "";
        int i = rel.lastIndexOf('/');
        return i >= 0 ? rel.substring(i + 1) : rel;
    }

    /** 这块素材现在能不能用：下载过、或本来就内置在 APK 里 */
    private boolean materialReady(String kind, String rel) {
        String name = relName(rel);
        if (name.isEmpty()) return false;
        if (new File(packDir(), kind + "/" + name).exists()) return true;
        if (kind.equals("listening") || kind.equals("reading")) {
            try {
                getAssets().open(kind + "/" + name).close();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 读素材正文：优先用下载目录里的，其次回落到 APK 内置 */
    private String readMaterial(String kind, String rel) {
        String name = relName(rel);
        if (name.isEmpty()) return "";
        File f = new File(packDir(), kind + "/" + name);
        if (f.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                br.close();
                return sb.toString();
            } catch (Throwable ignored) {
            }
        }
        return readAsset(kind + "/" + name);
    }

    /** 开启素材包：把清单里没内置的素材逐个拉到手机 */
    private void downloadPack(final Runnable onDone) {
        if (packBusy) return;
        final ArrayList<String> rels = new ArrayList<>();
        for (ReadingItem it : readingItems) {
            if (it.builtin) continue;
            if (it.text != null && !it.text.isEmpty()) rels.add("reading:" + it.text);
            if (it.translation != null && !it.translation.isEmpty()) rels.add("reading:" + it.translation);
        }
        for (ListenItem it : listenItems) {
            if (it.builtin) continue;
            if (it.text != null && !it.text.isEmpty()) rels.add("listening:" + it.text);
            if (it.audio != null && !it.audio.isEmpty()) rels.add("listening:" + it.audio);
        }
        if (rels.isEmpty()) {
            Toast.makeText(this, "没有需要下载的素材", Toast.LENGTH_SHORT).show();
            if (onDone != null) onDone.run();
            return;
        }
        packBusy = true;
        packDone = 0;
        packTotal = rels.size();
        new Thread(() -> {
            int fail = 0;
            for (String item : rels) {
                int sep = item.indexOf(':');
                String kind = item.substring(0, sep);
                String rel = item.substring(sep + 1);
                try {
                    File dir = new File(packDir(), kind);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, relName(rel));
                    java.net.URL u = new java.net.URL(packBase + "/" + rel);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    InputStream in = c.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                    in.close();
                    c.disconnect();
                } catch (Throwable t) {
                    fail++;
                }
                packDone++;
            }
            final int f = fail;
            runOnUiThread(() -> {
                packBusy = false;
                prefs.edit().putBoolean(PREF_PACK, f == 0).apply();
                Toast.makeText(this, f == 0 ? "素材包已就绪" : ("有 " + f + " 个文件没下载成功"),
                        Toast.LENGTH_SHORT).show();
                if (onDone != null) onDone.run();
            });
        }).start();
    }

    /** 关闭素材包：删掉下载目录，只留 APK 内置的那点内容 */
    private void deletePack() {
        deleteRecursive(packDir());
        prefs.edit().putBoolean(PREF_PACK, false).apply();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /** 素材包现在占多少空间 */
    private long packSize() {
        return dirSize(packDir());
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += dirSize(k);
        return sum;
    }

    // ================= 听力练习（首页右滑，沉浸式） =================

    private static class ListenItem {
        String id, title, titleZh, topic, level;
        String text, audio;         // 相对素材根目录的路径
        boolean builtin;
        int words, sentences, durationSec;
    }

    private void loadListenManifest() {
        listenItems = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(readAsset("listening/manifest.json"));
            packBase = root.optString("baseUrl", packBase);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ListenItem it = new ListenItem();
                it.id = o.optString("id");
                it.title = o.optString("title");
                it.titleZh = o.optString("titleZh");
                it.topic = o.optString("topic");
                it.level = o.optString("level");
                it.words = o.optInt("words");
                it.sentences = o.optInt("sentences");
                it.durationSec = o.optInt("durationSec");
                it.builtin = o.optBoolean("builtin", true);
                it.text = o.optString("text");
                it.audio = o.optString("audio");
                listenItems.add(it);
            }
        } catch (Throwable ignored) {
        }
    }

    private void renderListenList() {
        listenBody.removeAllViews();
        listenId = null;
        if (listenTitle != null) listenTitle.setText("听力");
        if (listenItems == null || listenItems.isEmpty()) {
            listenBody.addView(readingText("听力素材还没准备好", 15, R.color.text_secondary));
            return;
        }
        listenBody.addView(readingText(
                "先整段听，跟不上再点字幕跳到那一句重听。", 13, R.color.text_secondary));
        for (ListenItem it : listenItems) {
            boolean ready = materialReady("listening", it.audio);
            TextView t = optionButton(it.titleZh + "\n" + it.title + "\n"
                    + fmtTime(it.durationSec * 1000L) + " · " + it.words + " 词"
                    + (ready ? "" : "\n（未下载，需开启素材包）"));
            t.setOnClickListener(v -> {
                if (!materialReady("listening", it.audio)) {
                    Toast.makeText(this, "这篇还没下载：设置 → 素材包 → 开启", Toast.LENGTH_SHORT).show();
                    return;
                }
                openListen(it);
            });
            listenBody.addView(t);
        }
    }

    private void openListen(ListenItem it) {
        releaseListen();
        listenId = it.id;
        listenLines = Lrc.parse(readMaterial("listening", it.text));
        listenBody.removeAllViews();
        if (listenTitle != null) listenTitle.setText(fmtTime(it.durationSec * 1000L));

        TextView head = readingText(it.titleZh, 19, R.color.text_primary);
        head.setTypeface(null, Typeface.BOLD);
        listenBody.addView(head);
        TextView sub = readingText(it.title, 14, R.color.text_secondary);
        sub.setPadding(0, dp(4), 0, dp(14));
        listenBody.addView(sub);

        // 播放控制
        final TextView play = optionButton("▶ 播放");
        listenBody.addView(play);
        listenSeek = new SeekBar(this);
        listenSeek.setMax(it.durationSec * 1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        listenSeek.setLayoutParams(lp);
        listenBody.addView(listenSeek);
        final TextView time = readingText("00:00 / " + fmtTime(it.durationSec * 1000L),
                13, R.color.text_secondary);
        listenBody.addView(time);
        play.setOnClickListener(v -> toggleListen(play, time));
        listenSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
                if (listenPlayer != null) {
                    listenPlayer.seekTo(sb.getProgress());
                    highlightLine(Lrc.indexAt(listenLines, sb.getProgress()));
                }
            }
        });

        // 字幕：一句一行，点一下跳到那一句
        TextView hint = readingText("字幕（点一句即可跳转）", 13, R.color.text_secondary);
        hint.setPadding(0, dp(18), 0, dp(6));
        listenBody.addView(hint);
        listenLineViews = new ArrayList<>();
        listenIndex = -1;
        for (int i = 0; i < listenLines.size(); i++) {
            final Lrc.Line ln = listenLines.get(i);
            TextView tv = new TextView(this);
            tv.setText(ln.text);
            tv.setTextColor(getColor(R.color.text_secondary));
            tv.setTextSize(16);
            tv.setLineSpacing(dp(3), 1.35f);
            tv.setPadding(0, dp(7), 0, dp(7));
            final int idx = i;
            tv.setOnClickListener(v -> {
                if (listenPlayer == null) toggleListen(play, time);
                if (listenPlayer != null) {
                    listenPlayer.seekTo((int) ln.startMs);
                    highlightLine(idx);
                    if (!listenPlayer.isPlaying()) toggleListen(play, time);
                }
            });
            listenLineViews.add(tv);
            listenBody.addView(tv);
        }
    }

    private void toggleListen(TextView play, TextView time) {
        if (listenPlayer == null) {
            try {
                listenPlayer = new MediaPlayer();
                File local = new File(packDir(), "listening/" + listenId + ".mp3");
                if (local.exists()) {
                    listenPlayer.setDataSource(local.getAbsolutePath());   // 素材包下载的
                } else {
                    AssetFileDescriptor afd = getAssets().openFd("listening/" + listenId + ".mp3");
                    listenPlayer.setDataSource(afd.getFileDescriptor(),
                            afd.getStartOffset(), afd.getLength());
                    afd.close();
                }
                listenPlayer.setOnCompletionListener(mp -> {
                    play.setText("▶ 重新播放");
                    stopTick();
                });
                listenPlayer.prepare();
                listenPlayer.start();
                play.setText("⏸ 暂停");
                startTick(play, time);
            } catch (Throwable t) {
                Toast.makeText(this, "播放失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (listenPlayer.isPlaying()) {
            listenPlayer.pause();
            play.setText("▶ 播放");
            stopTick();
        } else {
            listenPlayer.start();
            play.setText("⏸ 暂停");
            startTick(play, time);
        }
    }

    private void startTick(TextView play, TextView time) {
        stopTick();
        listenTick = new Runnable() {
            @Override
            public void run() {
                if (listenPlayer == null) return;
                int pos = listenPlayer.getCurrentPosition();
                highlightLine(Lrc.indexAt(listenLines, pos));
                if (listenSeek != null) listenSeek.setProgress(pos);
                if (time != null) {
                    time.setText(fmtTime(pos) + " / " + fmtTime(listenPlayer.getDuration()));
                }
                if (listenPlayer.isPlaying()) listenHandler.postDelayed(this, 200);
            }
        };
        listenHandler.post(listenTick);
    }

    private void stopTick() {
        if (listenTick != null) {
            listenHandler.removeCallbacks(listenTick);
            listenTick = null;
        }
    }

    /** 当前句加粗提亮，其余回到次要色，并把字幕滚到可见处 */
    private void highlightLine(int idx) {
        if (listenLineViews == null || idx == listenIndex) return;
        if (listenIndex >= 0 && listenIndex < listenLineViews.size()) {
            TextView old = listenLineViews.get(listenIndex);
            old.setTextColor(getColor(R.color.text_secondary));
            old.setTypeface(null, Typeface.NORMAL);
        }
        listenIndex = idx;
        if (idx >= 0 && idx < listenLineViews.size()) {
            TextView cur = listenLineViews.get(idx);
            cur.setTextColor(getColor(R.color.text_primary));
            cur.setTypeface(null, Typeface.BOLD);
            if (listenScroll != null) {
                final View target = cur;
                listenScroll.post(() -> {
                    int y = target.getTop() - listenScroll.getHeight() / 2 + target.getHeight() / 2;
                    listenScroll.smoothScrollTo(0, Math.max(0, y));
                });
            }
        }
    }

    private static String fmtTime(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** 离开听力页/切页时把播放器收掉，别让声音在后台跑 */
    private void releaseListen() {
        stopTick();
        if (listenPlayer != null) {
            try {
                if (listenPlayer.isPlaying()) listenPlayer.stop();
            } catch (Throwable ignored) {
            }
            listenPlayer.release();
            listenPlayer = null;
        }
        listenIndex = -1;
    }

    private void pauseListenIfPlaying() {
        if (listenPlayer != null && listenPlayer.isPlaying()) {
            listenPlayer.pause();
            stopTick();
        }
    }

    private void closeListen() {
        if (listenId != null) {
            releaseListen();
            renderListenList();
            return;
        }
        switchTab(PAGE_DICT);
    }

    // ================= 沉浸式覆盖页（记背 / 阅读） =================

    /** 进入全屏：淡出顶栏和标签栏（状态栏保留）；退出时按当前所在页恢复 */
    private void applyImmersive(boolean on) {
        if (on) {
            setChromeVisible(false);
        } else if (swipeHost != null) {
            styleTabs(swipeHost.getPage());
        }
    }

    /** 从屏幕外滑入：fromTop=true 从上方进入（配合下拉手势） */
    private void slideIn(View page, boolean fromTop) {
        if (page == null) return;
        int h = page.getHeight() > 0 ? page.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        page.setVisibility(View.VISIBLE);
        page.setTranslationY(fromTop ? -h : h);
        page.animate().cancel();
        page.animate().translationY(0)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 滑出屏幕后关闭：toTop=true 往上移出 */
    private void slideOut(View page, boolean toTop, final Runnable after) {
        if (page == null) return;
        int h = page.getHeight() > 0 ? page.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        page.animate().cancel();
        page.animate().translationY(toTop ? -h : h)
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    page.setVisibility(View.GONE);
                    page.setTranslationY(0);
                    if (after != null) after.run();
                }).start();
    }

    /**
     * 给沉浸页接上「手势退出」的两条路，并返回它的拖拽把手。
     *
     * 为什么是两条路，又是这两条：
     *  ① 拖拽把手 DragHint —— 它自己消费 ACTION_DOWN，整段手势都归它，最可靠；
     *  ② EdgeScrollView —— 已滚到出口边界还继续往出口方向拖，同样能退出。
     * 都不能用 setOnTouchListener：子 View（可点单词、选项按钮）会吃掉事件，容器收不到。
     *
     * @param scrollId 出口边界的滚动容器
     * @param hintId   常驻拖拽把手
     * @param page     整页容器（跟手位移的就是它）
     * @param down     true = 下滑退出，false = 上滑退出（＝进入方向的反向）
     * @param onExit   滑出屏幕后的收尾
     */
    private DragHint setupExitSwipe(int scrollId, int hintId, final View page,
                                    boolean down, final Runnable onExit) {
        final int sign = down ? 1 : -1;
        EdgeScrollView scroll = findViewById(scrollId);
        scroll.setExitDown(down);
        scroll.setEdgeListener(new EdgeScrollView.EdgeListener() {
            @Override
            public void onEdgeDrag(float dy) {
                page.setTranslationY(dy * 0.6f);          // 跟手位移
            }

            @Override
            public void onEdgeRelease(float dy) {
                endExitDrag(page, sign, dy, onExit);
            }
        });
        DragHint hint = findViewById(hintId);
        hint.setDirection(down);
        hint.setListener(new DragHint.Listener() {
            @Override
            public void onDrag(float dy) {
                page.setTranslationY(dy * 0.6f);
            }

            @Override
            public void onRelease(float dy) {
                endExitDrag(page, sign, dy, onExit);
            }
        });
        return hint;
    }

    /**
     * 松手后的收尾：跟手位移超过阈值就滑出屏幕，否则回弹原位。
     * sign > 0 表示下滑退出（阅读页），sign < 0 表示上滑退出（记背页）。
     */
    private void endExitDrag(View page, int sign, float dy, Runnable onExit) {
        if (page == null) return;
        boolean fired = sign > 0 ? dy > dp(90) : dy < -dp(90);
        if (fired) {
            slideOut(page, sign < 0, onExit);   // 下滑退出→往下移出；上滑退出→往上移出
        } else {
            page.animate().cancel();
            page.animate().translationY(0).setDuration(180).start();
        }
    }

    // ================= 单词记背：一组十题 =================

    private void openStudy() {
        applyImmersive(true);
        slideIn(pageStudy, true);        // 下拉手势进入 → 从上方滑入
        if (studyHint != null) studyHint.showHint();   // 亮一下"↑ 上滑返回"，提示手势位置
        studyBody.removeAllViews();
        TextView wait = new TextView(this);
        wait.setText("出题中…");
        wait.setTextColor(getColor(R.color.text_secondary));
        wait.setTextSize(15);
        studyBody.addView(wait);
        if (studyProgress != null) studyProgress.setText("");
        buildGroup();
    }

    private void closeStudy() {
        if (pageStudy == null || pageStudy.getVisibility() != View.VISIBLE) return;
        slideOut(pageStudy, true, () -> applyImmersive(false));   // 上滑退出 → 往上移出
    }

    /**
     * 出一组十题：先复习到期的陌生词（最多 4 个），不够再从六级词表补新词。
     * 选择题和拼写题交替；干扰项取同一词表里其他词的释义，避免随便蒙都对。
     * 整组在后台线程生成好再一次性渲染，避免界面卡顿。
     */
    private void buildGroup() {
        dbPool.execute(() -> {
            ArrayList<Quiz> list = new ArrayList<>();
            try {
                Random r = new Random();
                ArrayList<String> all = dict.wordsByTag("cet6", 800);
                ArrayList<String> pool = new ArrayList<>(all);
                ArrayList<String> words = new ArrayList<>();
                if (studyDb != null) {
                    for (String w : studyDb.dueWords(4)) {
                        if (!words.contains(w)) words.add(w);
                    }
                }
                // 多备几个，释义不合格的词会被跳过
                while (words.size() < 16 && !pool.isEmpty()) {
                    String w = pool.remove(r.nextInt(pool.size()));
                    if (!words.contains(w)) words.add(w);
                }
                for (String w : words) {
                    if (list.size() >= 10) break;
                    DictDb.Entry e = dict.lookup(w);
                    if (e == null || e.translation == null || e.translation.isEmpty()) continue;
                    ArrayList<String> ms = Quiz.meanings(e.translation, 10);
                    if (ms.isEmpty()) continue;          // 释义太碎，这个词不适合出题
                    String meaning = ms.get(0);
                    if (list.size() % 2 == 0) {
                        ArrayList<String> ds = new ArrayList<>();
                        for (int k = 0; k < 40 && ds.size() < 3 && !all.isEmpty(); k++) {
                            String ow = all.get(r.nextInt(all.size()));
                            if (ow.equals(w)) continue;
                            DictDb.Entry oe = dict.lookup(ow);
                            if (oe == null || oe.translation == null || oe.translation.isEmpty()) continue;
                            ArrayList<String> oms = Quiz.meanings(oe.translation, 10);
                            if (oms.isEmpty()) continue;
                            String om = oms.get(0);
                            if (!Quiz.plausible(meaning, om)) continue;   // 不够迷惑就不要
                            if (!ds.contains(om)) ds.add(om);
                        }
                        if (ds.size() < 3) {
                            // 干扰项凑不齐三个，改出拼写题，别让用户一眼看出答案
                            list.add(Quiz.spell(w, meaning));
                        } else {
                            list.add(Quiz.meaning(w, meaning, ds, r));
                        }
                    } else {
                        list.add(Quiz.spell(w, meaning));
                    }
                }
            } catch (Throwable ignored) {
            }
            quizGroup = list;
            quizIndex = 0;
            quizRight = 0;
            quizWrongWords = new ArrayList<>();
            runOnUiThread(this::renderQuiz);
        });
    }

    /** 取释义首行第一个合格义项；实在没有就退回原始首行 */
    private static String firstMeaning(String translation) {
        ArrayList<String> ms = Quiz.meanings(translation, 10);
        if (!ms.isEmpty()) return ms.get(0);
        return firstLine(translation).trim();
    }

    private void renderQuiz() {
        studyBody.removeAllViews();
        if (quizGroup == null || quizGroup.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("暂时没有可练的词");
            t.setTextColor(getColor(R.color.text_secondary));
            t.setTextSize(15);
            studyBody.addView(t);
            return;
        }
        if (quizIndex >= quizGroup.size()) {
            renderSummary();
            return;
        }

        final Quiz q = quizGroup.get(quizIndex);
        studyProgress.setText("第 " + (quizIndex + 1) + " / " + quizGroup.size() + "　答对 " + quizRight);

        TextView head = new TextView(this);
        head.setText(q.type == Quiz.TYPE_MEANING ? "选出正确的释义" : "拼出这个单词");
        head.setTextColor(getColor(R.color.text_secondary));
        head.setTextSize(13);
        studyBody.addView(head);

        TextView title = new TextView(this);
        title.setText(q.type == Quiz.TYPE_MEANING ? q.word : q.meaning);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(6));
        studyBody.addView(title);

        if (q.type == Quiz.TYPE_MEANING) {
            for (int i = 0; i < q.options.size(); i++) {
                final int idx = i;
                TextView opt = optionButton(q.options.get(i));
                opt.setOnClickListener(v -> answer(q, idx == q.answerIndex));
                studyBody.addView(opt);
            }
        } else {
            TextView hintLine = new TextView(this);
            hintLine.setText(q.spellHint());
            hintLine.setTextColor(getColor(R.color.text_secondary));
            hintLine.setTextSize(15);
            hintLine.setTypeface(Typeface.MONOSPACE);
            hintLine.setPadding(0, 0, 0, dp(10));
            studyBody.addView(hintLine);

            final EditText input = new EditText(this);
            input.setHint("输入英文单词");
            input.setSingleLine(true);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setTextColor(getColor(R.color.text_primary));
            input.setHintTextColor(getColor(R.color.text_secondary));
            input.setTextSize(17);
            input.setBackground(null);
            input.setPadding(0, dp(16), 0, dp(8));
            studyBody.addView(input);

            TextView submit = optionButton("确定");
            submit.setOnClickListener(v -> answer(q, q.checkSpelling(input.getText().toString())));
            studyBody.addView(submit);

            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    answer(q, q.checkSpelling(input.getText().toString()));
                    return true;
                }
                return false;
            });
        }

        TextView forgot = new TextView(this);
        forgot.setText("不认识，直接标记为陌生词");
        forgot.setTextColor(getColor(R.color.text_secondary));
        forgot.setTextSize(13);
        forgot.setPadding(dp(4), dp(26), dp(4), dp(10));
        forgot.setOnClickListener(v -> {
            if (studyDb != null) studyDb.mark(q.word);
            if (!quizWrongWords.contains(q.word)) quizWrongWords.add(q.word);
            Toast.makeText(this, "已加入复习", Toast.LENGTH_SHORT).show();
            nextQuiz();
        });
        studyBody.addView(forgot);
    }

    /** 统一的选项/按钮样式：柔和圆角，和翻译页按钮一致 */
    private TextView optionButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        t.setBackgroundResource(R.drawable.btn_soft);
        return t;
    }

    private void answer(Quiz q, boolean ok) {
        if (ok) {
            quizRight++;
            if (studyDb != null) studyDb.correct(q.word);
        } else {
            if (!quizWrongWords.contains(q.word)) quizWrongWords.add(q.word);
            if (studyDb != null) studyDb.wrong(q.word);   // 答错自动进复习
        }
        studyBody.removeAllViews();

        TextView res = new TextView(this);
        res.setText(ok ? "✓ 答对了" : "✗ 正确答案：" + q.word);
        res.setTextColor(getColor(ok ? R.color.text_primary : R.color.mark_cet6));
        res.setTextSize(20);
        res.setTypeface(null, Typeface.BOLD);
        studyBody.addView(res);

        TextView mean = new TextView(this);
        mean.setText(q.word + "　" + q.meaning);
        mean.setTextColor(getColor(R.color.text_primary));
        mean.setTextSize(16);
        mean.setPadding(0, dp(14), 0, 0);
        studyBody.addView(mean);

        if (ok) {
            TextView keep = optionButton("其实没记住，加入复习");
            keep.setOnClickListener(v -> {
                if (studyDb != null) studyDb.mark(q.word);
                Toast.makeText(this, "已加入复习", Toast.LENGTH_SHORT).show();
                nextQuiz();
            });
            studyBody.addView(keep);
        }

        TextView next = optionButton(quizIndex + 1 >= quizGroup.size() ? "看看本组结果" : "下一题");
        next.setOnClickListener(v -> nextQuiz());
        studyBody.addView(next);
    }

    private void nextQuiz() {
        quizIndex++;
        renderQuiz();
    }

    private void renderSummary() {
        studyBody.removeAllViews();
        if (studyProgress != null) studyProgress.setText("本组完成");

        TextView t = new TextView(this);
        t.setText("答对 " + quizRight + " / " + quizGroup.size());
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(24);
        t.setTypeface(null, Typeface.BOLD);
        studyBody.addView(t);

        if (!quizWrongWords.isEmpty()) {
            TextView h = new TextView(this);
            h.setText("错词：" + quizWrongWords.size() + " 个，已加入复习");
            h.setTextColor(getColor(R.color.text_secondary));
            h.setTextSize(13);
            h.setPadding(0, dp(26), 0, dp(8));
            studyBody.addView(h);
            for (String w : quizWrongWords) {
                TextView c = new TextView(this);
                c.setText(w);
                c.setTextColor(getColor(R.color.text_primary));
                c.setTextSize(16);
                c.setPadding(0, dp(10), 0, dp(10));
                studyBody.addView(c);
            }
        }

        TextView again = optionButton("再来一组");
        again.setOnClickListener(v -> openStudy());
        studyBody.addView(again);

        TextView finish = optionButton("结束");
        finish.setOnClickListener(v -> closeStudy());
        studyBody.addView(finish);
    }

    @Override
    public void onBackPressed() {
        if (pageStudy != null && pageStudy.getVisibility() == View.VISIBLE) {
            closeStudy();
            return;
        }
        if (pageReading != null && pageReading.getVisibility() == View.VISIBLE) {
            closeReading();
            return;
        }
        if (pageCrop != null && pageCrop.getVisibility() == View.VISIBLE) {
            closeCrop();
            return;
        }
        if (swipeHost != null && swipeHost.getPage() == PAGE_LISTEN) {
            closeListen();
            return;
        }
        if (swipeHost != null && swipeHost.getPage() != PAGE_DICT) {
            switchTab(PAGE_DICT);
            return;
        }
        super.onBackPressed();
    }

    // ================= 拍照翻译（系统相机 + ML Kit 离线文字识别） =================

    private void takePhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "jianyi_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            Intent in = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            in.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
            in.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(in, 100);
        } catch (Throwable t) {
            Toast.makeText(this, "启动相机失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void pickImage() {
        Intent in = new Intent(Intent.ACTION_GET_CONTENT);
        in.setType("image/*");
        startActivityForResult(Intent.createChooser(in, "选择图片"), 101);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 102) {
            // 皮肤：从相册选背景图（解码裁剪较重，放后台线程）
            if (res == RESULT_OK && data != null && data.getData() != null) {
                final Uri u = data.getData();
                dbPool.execute(() -> {
                    final boolean ok = saveSkinBitmap(u);
                    runOnUiThread(() -> {
                        if (ok) {
                            prefs.edit().putString("skin_mode", "photo").apply();
                            applySkin();
                            Toast.makeText(MainActivity.this, "背景已应用，可调蒙层浓度", Toast.LENGTH_SHORT).show();
                            // 重新弹出皮肤窗，这时才会显示蒙层调节区
                            if (skinDialog != null) {
                                skinDialog.dismiss();
                                skinDialog = null;
                            }
                            showSkinDialog();
                        } else {
                            Toast.makeText(MainActivity.this, "图片读取失败，换一张试试", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }
            return;
        }
        if (res != RESULT_OK) return;
        final Uri uri = req == 100 ? pendingPhotoUri : (data == null ? null : data.getData());
        if (uri == null) return;
        ocrText.setText("识别中…");
        dbPool.execute(() -> {
            final Bitmap bmp;
            try {
                bmp = loadBitmap(uri);
            } catch (Throwable t) {
                runOnUiThread(() -> ocrText.setText("图片读取失败：" + t.getMessage()));
                return;
            }
            runOnUiThread(() -> showCrop(bmp));   // 先让用户框选，再识别
        });
    }

    /** 拍照/选图后先进入框选：拖框决定识别哪一块，避免整页无关文字混进来 */
    private void showCrop(Bitmap bmp) {
        pendingOcrBmp = bmp;
        if (cropView != null) cropView.setBitmap(bmp);
        if (pageCrop != null) pageCrop.setVisibility(View.VISIBLE);
        ocrText.setText("");
    }

    private void closeCrop() {
        if (pageCrop != null) pageCrop.setVisibility(View.GONE);
    }

    /** 读取图片并缩到最长边约 2000px，防低配手机内存爆炸；顺带按 EXIF 摆正方向 */
    private Bitmap loadBitmap(Uri uri) throws Exception {
        int orientation = 0;
        try {
            InputStream meta = getContentResolver().openInputStream(uri);
            orientation = exifOrientation(meta);
            if (meta != null) meta.close();
        } catch (Throwable ignored) {
        }
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        InputStream s = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(s, null, probe);
        if (s != null) s.close();
        int sample = 1;
        while (probe.outWidth / sample > 2000 || probe.outHeight / sample > 2000) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream s2 = getContentResolver().openInputStream(uri);
        Bitmap b = BitmapFactory.decodeStream(s2, null, opts);
        if (s2 != null) s2.close();
        if (b == null) throw new Exception("图片解码失败");
        if (orientation != 0 && orientation != 1) {
            Matrix m = new Matrix();
            switch (orientation) {
                case 3: m.postRotate(180); break;
                case 6: m.postRotate(90); break;
                case 8: m.postRotate(270); break;
                case 2: m.postScale(-1, 1); break;
                case 4: m.postScale(1, -1); break;
                case 5: m.postRotate(90); m.postScale(-1, 1); break;
                case 7: m.postRotate(270); m.postScale(-1, 1); break;
                default: break;
            }
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        return b;
    }

    /** 极简 JPEG EXIF 方向读取器：0/1=正常，3/6/8=需旋转，其余罕见值已覆盖翻转 */
    private static int exifOrientation(InputStream in) throws Exception {
        if (in == null || in.read() != 0xFF || in.read() != 0xD8) return 0; // 非 JPEG
        while (true) {
            int b = in.read();
            if (b != 0xFF) return 0;
            int marker = in.read();
            if (marker == 0xD9 || marker == 0xDA) return 0;
            int len = (in.read() << 8) | in.read();
            if (len < 2) return 0;
            if (marker == 0xE1 && len >= 10) {
                byte[] exif = new byte[len - 2];
                int got = 0;
                while (got < exif.length) {
                    int r = in.read(exif, got, exif.length - got);
                    if (r < 0) return 0;
                    got += r;
                }
                if (got >= 6 && exif[0] == 'E' && exif[1] == 'x' && exif[2] == 'i' && exif[3] == 'f') {
                    int off = 6;
                    boolean little = exif[off] == 'I' && exif[off + 1] == 'I';
                    boolean big = exif[off] == 'M' && exif[off + 1] == 'M';
                    if (!little && !big) return 0;
                    int ifd = getInt(exif, off + 4, little, 4);
                    int pos = off + ifd;
                    if (pos + 2 > exif.length) return 0;
                    int count = getInt(exif, pos, little, 2);
                    for (int i = 0; i < count; i++) {
                        int e = pos + 2 + i * 12;
                        if (e + 10 > exif.length) return 0;
                        if (getInt(exif, e, little, 2) == 0x0112) {
                            return getInt(exif, e + 8, little, 2);
                        }
                    }
                }
                return 0;
            }
            long skip = len - 2;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) return 0;
                skip -= s;
            }
        }
    }

    private static int getInt(byte[] data, int pos, boolean little, int bytes) {
        int v = 0;
        if (little) {
            for (int i = bytes - 1; i >= 0; i--) v = (v << 8) | (data[pos + i] & 0xFF);
        } else {
            for (int i = 0; i < bytes; i++) v = (v << 8) | (data[pos + i] & 0xFF);
        }
        return v;
    }

    private void runOcr(Bitmap bmp) {
        getRecognizer().process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(t -> {
                    StringBuilder sb = new StringBuilder();
                    for (Text.TextBlock block : t.getTextBlocks()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(block.getText());
                    }
                    String text = sb.toString().trim();
                    ocrText.setText(text.isEmpty()
                            ? "未识别到文字，请保证字迹清晰、光线充足后重试" : text);
                })
                .addOnFailureListener(e -> ocrText.setText("文字识别失败：" + e.getMessage()));
    }

    private TextRecognizer getRecognizer() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return recognizer;
    }

    // ================= 离线句段翻译（ML Kit） =================

    /** 极简语向检测：统计中日韩字符与拉丁字母数量 */
    private boolean isChinese(String s) {
        int cjk = 0, latin = 0;
        for (char c : s.toCharArray()) {
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) cjk++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        return cjk >= latin;
    }

    private void doTranslate() {
        if (translating) return;
        final String input = translateInputSafe().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "先输入要翻译的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean zh2en = isChinese(input);
        final String appId = prefs.getString("baidu_appid", "");
        final String secret = prefs.getString("baidu_secret", "");
        translating = true;
        if (!appId.isEmpty() && !secret.isEmpty()) {
            // 云端优先：百度翻译；失败自动回退离线引擎，但错误原因必须显示出来
            translateOutput.setText(zh2en ? "（中 → 英 · 云端，翻译中…）" : "（英 → 中 · 云端，翻译中…）");
            netPool.execute(() -> {
                final String result = baiduTranslate(input, zh2en, appId, secret);
                runOnUiThread(() -> {
                    if (result == null) {
                        // 网络不通：静默回退离线（这时没有错误原因可显示）
                        translateOutput.setText("（云端不可用，已切换离线引擎…）");
                        runOffline(input, zh2en);
                    } else if (result.startsWith("!ERR:")) {
                        // 云端明确报错：显示原因，方便排查 key 问题
                        translating = false;
                        translateOutput.setText("云端报错：" + result.substring(5)
                                + "\n\n已临时使用离线引擎出结果：");
                        runOffline(input, zh2en);
                    } else {
                        translating = false;
                        translateOutput.setText((zh2en ? "（中 → 英 · 云端）\n" : "（英 → 中 · 云端）\n") + result);
                    }
                });
            });
        } else {
            translateOutput.setText(zh2en ? "（中 → 英，引擎准备中…）" : "（英 → 中，引擎准备中…）");
            runOffline(input, zh2en);
        }
    }

    /** ML Kit 离线引擎；首次使用需联网下载一次语言包（约 30MB） */
    private void runOffline(final String input, final boolean zh2en) {
        Translator t = zh2en ? getZhEn() : getEnZh();
        final boolean[] finished = {false};
        // 30 秒无响应 → 判定卡住，给出自救指引并重建引擎
        new android.os.Handler().postDelayed(() -> {
            if (!finished[0]) {
                translating = false;
                resetTranslators();
                translateOutput.setText("引擎响应超时（30 秒）。\n\n可能原因与对策：\n"
                        + "1) 首次使用需联网下载语言包，请确认网络可用后重试；\n"
                        + "2) 手机管家/安全软件可能拦截了语言包下载，请放行简译；\n"
                        + "3) 切换 Wi-Fi/流量后再试；\n"
                        + "4) 仍不行：设置 → 应用 → 简译 → 清除数据，重启 App（词典会自动重新解压）。");
            }
        }, 30000);
        t.downloadModelIfNeeded()
                .addOnSuccessListener(v -> t.translate(input)
                        .addOnSuccessListener(res -> {
                            finished[0] = true;
                            translating = false;
                            translateOutput.setText((zh2en ? "（中 → 英）\n" : "（英 → 中）\n") + res);
                        })
                        .addOnFailureListener(e -> {
                            finished[0] = true;
                            translating = false;
                            translateOutput.setText("翻译失败：" + friendly(e));
                        }))
                .addOnFailureListener(e -> {
                    finished[0] = true;
                    translating = false;
                    resetTranslators();
                    translateOutput.setText("语言包下载失败：" + friendly(e)
                            + "\n\n首次使用需联网下载一次语言包（约 30MB），请检查网络后重试；下载成功后即可永远离线使用。");
                });
    }

    /** 关闭并清空引擎实例，让下一次点击用全新引擎重试 */
    private void resetTranslators() {
        if (translatorZhEn != null) {
            translatorZhEn.close();
            translatorZhEn = null;
        }
        if (translatorEnZh != null) {
            translatorEnZh.close();
            translatorEnZh = null;
        }
    }

    /** 百度通用翻译 API；成功返回译文，失败返回 null（由调用方回退离线） */
    private String baiduTranslate(String input, boolean zh2en, String appId, String secret) {
        if (input.length() > 3000) {
            return "!ERR:云端不支持超过 3000 字的段落，请分段翻译";
        }
        HttpURLConnection conn = null;
        try {
            String salt = String.valueOf(System.currentTimeMillis());
            String sign = md5(appId + input + salt + secret);
            String q = URLEncoder.encode(input, "UTF-8");
            URL url = new URL("https://fanyi-api.baidu.com/api/trans/vip/translate"
                    + "?q=" + q
                    + "&from=" + (zh2en ? "zh" : "en")
                    + "&to=" + (zh2en ? "en" : "zh")
                    + "&appid=" + appId + "&salt=" + salt + "&sign=" + sign);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            if (o.has("error_code")) {
                return "!ERR:" + baiduErrMsg(o.optString("error_code"), o.optString("error_msg"));
            }
            JSONArray arr = o.getJSONArray("trans_result");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) out.append('\n');
                out.append(arr.getJSONObject(i).optString("dst"));
            }
            return out.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 百度错误码 → 人话 */
    private static String baiduErrMsg(String code, String raw) {
        if (code == null || code.isEmpty()) return "未知错误";
        switch (code) {
            case "52001": return "请求超时，请重试";
            case "52002": return "百度服务系统错误，请稍后再试";
            case "52003": return "APP ID 无效或未开通「通用翻译」服务";
            case "54000": return "请求参数为空";
            case "54001": return "签名错误：请检查 APP ID 和密钥是否填对（不要有多余空格）";
            case "54003": return "请求太频繁：免费版限速 1 次/秒，请稍后再试";
            case "54004": return "账户余额不足";
            case "58002": return "服务已被封禁";
            case "58003": return "本月免费额度已用完";
            case "90107": return "账号未实名认证：请到百度智能云完成实名认证";
            default: return "错误码 " + code + (raw == null || raw.isEmpty() ? "" : "（" + raw + "）");
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 云端增强设置弹窗：填入百度翻译 API 的 APP ID 和密钥 */
    private void showCloudDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        final EditText etId = new EditText(this);
        etId.setHint("APP ID");
        etId.setText(prefs.getString("baidu_appid", ""));
        box.addView(etId);
        final EditText etKey = new EditText(this);
        etKey.setHint("密钥");
        etKey.setText(prefs.getString("baidu_secret", ""));
        box.addView(etKey);
        TextView tip = new TextView(this);
        tip.setText("免费获取：fanyi-api.baidu.com → 注册 → 开通「通用翻译」。"
                + "填入后联网翻译走云端（更通顺），断网自动切回离线引擎；每月免费额度约 5 万字符。");
        tip.setTextColor(getColor(R.color.text_secondary));
        tip.setTextSize(13);
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("云端增强")
                .setView(box)
                .setPositiveButton("保存并测试", (d, w) -> {
                    final String id = etId.getText().toString().trim();
                    final String key = etKey.getText().toString().trim();
                    prefs.edit().putString("baidu_appid", id).putString("baidu_secret", key).apply();
                    refreshCloudLabel();
                    if (id.isEmpty() || key.isEmpty()) {
                        Toast.makeText(this, "已停用云端（信息为空）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "已保存，正在测试连通…", Toast.LENGTH_SHORT).show();
                    netPool.execute(() -> {
                        final String r = baiduTranslate("你好，世界", true, id, key);
                        runOnUiThread(() -> {
                            if (r != null && !r.startsWith("!ERR:")) {
                                Toast.makeText(this, "云端连通成功 ✓（返回：" + r + "）", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, "云端测试失败：" + (r == null ? "网络不通" : r.substring(5)), Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNeutralButton("停用", (d, w) -> {
                    prefs.edit().remove("baidu_appid").remove("baidu_secret").apply();
                    refreshCloudLabel();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCloudLabel() {
        boolean on = !prefs.getString("baidu_appid", "").isEmpty();
        cloudToggle.setText(on ? "云端增强：已开启" : "云端增强：未开启");
        cloudToggle.setTextColor(getColor(on ? R.color.text_primary : R.color.text_secondary));
    }

    private String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("network") || m.contains("Network") || m.contains("timeout") || m.contains("Timeout")) {
            return "网络不可用或超时";
        }
        return m;
    }

    private String translateInputSafe() {
        EditText et = findViewById(R.id.translateInput);
        return et.getText() == null ? "" : et.getText().toString();
    }

    private Translator getZhEn() {
        if (translatorZhEn == null) {
            translatorZhEn = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH).build());
        }
        return translatorZhEn;
    }

    private Translator getEnZh() {
        if (translatorEnZh == null) {
            translatorEnZh = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.CHINESE).build());
        }
        return translatorEnZh;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pron != null) {
            pron.stop();
            pronWord = null;
            pronState = -1;
        }
    }

    @Override
    protected void onDestroy() {
        if (skinBmp != null) {
            skinBmp.recycle();
            skinBmp = null;
        }
        if (pron != null) pron.release();
        releaseListen();
        if (translatorZhEn != null) translatorZhEn.close();
        if (translatorEnZh != null) translatorEnZh.close();
        if (recognizer != null) recognizer.close();
        dbPool.shutdown();
        netPool.shutdown();
        super.onDestroy();
    }

    private void showHistory(boolean show) {
        pageHistory.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) refreshHistory();
    }

    private void refreshHistory() {
        historyAdapter.words = history.recent(100);
        historyAdapter.notifyDataSetChanged();
        View empty = findViewById(R.id.historyEmpty);
        if (empty != null) empty.setVisibility(historyAdapter.words.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * 查完词收起键盘。必须连焦点一起清掉——
     * 只隐藏键盘而焦点还在输入框上时，下次再点输入框不会触发焦点变化，
     * 系统也就不会再弹键盘（这就是"查完下一个要点两下"的根因）。
     */
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        searchBox.clearFocus();
    }

    /** 强制呼出键盘并把光标放进指定输入框 */
    private void showKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        v.requestFocus();
        v.post(() -> imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT));
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    /** 中文反查用：取释义里包含关键词的那一行，没有就取第一行 */
    private static String matchLine(String translation, String keyword) {
        if (translation == null) return "";
        for (String line : translation.split("\\n")) {
            if (line.contains(keyword)) return line.trim();
        }
        return firstLine(translation);
    }

    /** 把 ECDICT 的 exchange 字段翻译成人能看懂的词形变化 */
    private static Map<String, String> parseExchange(String ex) {
        Map<String, String> m = new LinkedHashMap<>();
        if (ex == null || ex.isEmpty()) return m;
        for (String part : ex.split("/")) {
            int i = part.indexOf(':');
            if (i < 1) continue;
            String k = part.substring(0, i), v = part.substring(i + 1);
            switch (k) {
                case "p": m.put("过去式", v); break;
                case "d": m.put("过去分词", v); break;
                case "i": m.put("现在分词", v); break;
                case "3": m.put("三单", v); break;
                case "r": m.put("比较级", v); break;
                case "t": m.put("最高级", v); break;
                case "s": m.put("复数", v); break;
                case "0": m.put("原型", v); break;
            }
        }
        return m;
    }

    // ================= 发音 =================

    private final Pron.Listener pronListener = (word, state, message) -> {
        if (state == Pron.STATE_DONE) {
            pronWord = null;
            pronState = -1;
        } else {
            pronWord = word;
            pronState = state;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (state == Pron.STATE_ERROR && message != null) {
            Toast.makeText(MainActivity.this, "发音失败：" + message, Toast.LENGTH_LONG).show();
        } else if (state == Pron.STATE_OFFLINE) {
            Toast.makeText(MainActivity.this, "离线中，已用手机自带语音朗读", Toast.LENGTH_SHORT).show();
        }
    };

    /** 结果卡片右侧的喇叭：灰=可播，半透明=加载中，变深=播放中 */
    private View buildSpeaker(final DictDb.Entry e) {
        return buildSpeakerFor(e == null ? null : e.word, dp(42));
    }

    /** 通用喇叭：单词和例句共用一套状态显示 */
    private View buildSpeakerFor(final String text, int size) {
        boolean active = text != null && text.equals(pronWord);
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageResource(R.drawable.ic_speaker);
        if (active && pronState == Pron.STATE_PLAYING) {
            iv.setColorFilter(getColor(R.color.accent));
        } else {
            iv.setColorFilter(getColor(R.color.text_secondary));
            iv.setAlpha(active && pronState == Pron.STATE_LOADING ? 0.3f : 1f);
        }
        iv.setOnClickListener(v -> onSpeakerClick(text));
        // 长按喇叭直接换音色，不用进设置
        iv.setOnLongClickListener(v -> {
            showVoiceDialog();
            return true;
        });
        return iv;
    }

    /** 例句块：一行英文 + 喇叭，下面一行中文 */
    private View buildExampleRow(final DictDb.Example x) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(8), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView en = new TextView(this);
        en.setText(x.en);
        en.setTextColor(getColor(R.color.text_primary));
        en.setTextSize(15);
        en.setLineSpacing(dp(2), 1);
        en.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(en);
        row.addView(buildSpeakerFor(x.en, dp(34)));
        wrap.addView(row);

        if (x.zh != null && !x.zh.isEmpty()) {
            TextView zh = new TextView(this);
            zh.setText(x.zh);
            zh.setTextColor(getColor(R.color.text_secondary));
            zh.setTextSize(14);
            zh.setLineSpacing(dp(2), 1);
            zh.setPadding(0, dp(3), 0, 0);
            wrap.addView(zh);
        }
        return wrap;
    }

    private void onSpeakerClick(DictDb.Entry e) {
        if (e == null) return;
        onSpeakerClick(e.word);
    }

    private void onSpeakerClick(String text) {
        if (text == null || text.isEmpty()) return;
        if (text.equals(pronWord) && pronState == Pron.STATE_PLAYING) {
            pron.stop();
            pronWord = null;
            pronState = -1;
            adapter.notifyDataSetChanged();
            return;
        }
        pronWord = text;
        pronState = Pron.STATE_LOADING;
        adapter.notifyDataSetChanged();
        pron.play(text, pronListener);
    }

    // ================= 词级标记：六级重点词 / 高频词 =================

    private static boolean hasTag(DictDb.Entry e, String key) {
        if (e == null || e.tag == null) return false;
        for (String p : e.tag.trim().split("\\s+")) {
            if (key.equals(p)) return true;
        }
        return false;
    }

    /** 词频分档（数字越小越常用），返回 0~3 星 */
    private static int starLevel(int frq) {
        if (frq <= 0) return 0;
        if (frq <= 1000) return 3;
        if (frq <= 3000) return 2;
        if (frq <= 8000) return 1;
        return 0;
    }

    private static String stars(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < 5; i++) sb.append('★');
        return sb.toString();
    }

    /** 联想列表里的紧凑标记：词后面跟一个彩色小字 */
    private void addInlineMarks(LinearLayout row, DictDb.Entry e) {
        if (e == null) return;
        if (hasTag(e, "cet6")) {
            TextView t = new TextView(this);
            t.setText("六级");
            t.setTextColor(getColor(R.color.mark_cet6));
            t.setTextSize(11);
            t.setPadding(dp(8), dp(2), 0, 0);
            row.addView(t);
        }
        int star = starLevel(e.frq);
        if (star > 0) {
            TextView t = new TextView(this);
            t.setText(stars(star));
            t.setTextColor(getColor(R.color.mark_high));
            t.setTextSize(11);
            t.setPadding(dp(6), dp(2), 0, 0);
            row.addView(t);
        }
    }

    /** 圆角小标签 */
    private TextView chip(String text, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(getColor(colorRes));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.chip_bg));
        bg.setCornerRadius(dp(9));
        t.setBackground(bg);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        t.setLayoutParams(lp);
        return t;
    }

    // ================= 皮肤 =================

    /** 按设置铺背景：默认 / 护眼绿 / 米黄纸 / 相册图片 */
    private void applySkin() {
        String mode = prefs.getString("skin_mode", "default");
        // 用默认背景色的深浅判断当前外观：深色模式下护眼色、蒙层都要换暗版
        boolean dark = isDarkColor(getColor(R.color.bg));

        Drawable skin = null;
        int barColor = 0;
        if ("green".equals(mode)) {
            int c = dark ? 0xFF1E2A21 : 0xFFC7EDCC;
            skin = new ColorDrawable(c);
            barColor = c;
        } else if ("cream".equals(mode)) {
            int c = dark ? 0xFF2B2720 : 0xFFF5EFD8;
            skin = new ColorDrawable(c);
            barColor = c;
        } else if ("photo".equals(mode)) {
            Bitmap b = loadSkinBitmap();
            if (b != null) {
                BitmapDrawable d = new BitmapDrawable(getResources(), b);
                d.setGravity(Gravity.FILL);
                int mask = maskColor(dark);      // 返回 0 表示不加蒙层，直接用原图
                if (mask != 0) {
                    skin = new LayerDrawable(new Drawable[]{d, new ColorDrawable(mask)});
                    // 状态栏用「图片顶部平均色 + 蒙层」的混合色，和背景自然衔接
                    barColor = blend(topAverageColor(b), mask);
                } else {
                    skin = d;
                    barColor = topAverageColor(b);
                }
            }
        }
        if (skin == null) {
            skin = new ColorDrawable(getColor(R.color.bg));
        }

        // 根布局 + 每个全屏页/覆盖层都要各铺一份（Drawable 不共享实例，避免状态互相污染）。
        // 首页那几个老页面在 XML 里没写背景，rootLayout 的皮肤能透上来；后加的听力、记背、
        // 阅读、拍照框选、历史页都写死了 @color/bg 会把皮肤挡住，所以必须逐个覆盖，
        // 才能和首页外观一致。
        findViewById(R.id.rootLayout)
                .setBackground(skin.getConstantState().newDrawable(getResources()));
        final int[] skinned = {R.id.pageHistory, R.id.pageListen, R.id.pageStudy,
                R.id.pageReading, R.id.pageCrop};
        for (int id : skinned) {
            View v = findViewById(id);
            if (v != null) {
                v.setBackground(skin.getConstantState().newDrawable(getResources()));
            }
        }

        if (barColor != 0) {
            getWindow().setStatusBarColor(barColor);
            getWindow().setNavigationBarColor(barColor);
            // 状态栏图标的明暗跟着实际底色走（夜景图也能看清图标）
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (isDarkColor(barColor)) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private void showSkinDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), dp(4));

        final TextView[] rows = new TextView[SKIN_MODES.length];
        for (int i = 0; i < SKIN_MODES.length; i++) {
            final int idx = i;
            rows[i] = optionRow(SKIN_LABELS[i], () -> {
                if ("photo".equals(SKIN_MODES[idx])) {
                    pickSkinImage();   // 相册背景：直接打开选图，选完自动应用
                } else {
                    prefs.edit().putString("skin_mode", SKIN_MODES[idx]).apply();
                    applySkin();
                    refreshOptions(rows, SKIN_LABELS, idx);
                }
            });
            box.addView(rows[i]);
        }
        String cur = prefs.getString("skin_mode", "default");
        int curIdx = 0;
        for (int i = 0; i < SKIN_MODES.length; i++) {
            if (SKIN_MODES[i].equals(cur)) curIdx = i;
        }
        refreshOptions(rows, SKIN_LABELS, curIdx);

        TextView note = new TextView(this);
        note.setText("护眼色和深色模式可以叠加（自动切换暗色版）。"
                + "「相册背景」会打开相册挑一张图；选完可在下面的「背景蒙层」里调浓度，或者干脆关掉。");
        note.setTextColor(getColor(R.color.text_secondary));
        note.setTextSize(11);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);

        // 蒙层只对相册背景有意义，选了图才显示这一块
        if ("photo".equals(cur)) {
            box.addView(buildMaskSection());
        }

        skinDialog = new AlertDialog.Builder(this)
                .setTitle("皮肤")
                .setView(box)
                .setPositiveButton("好", null)
                .show();
    }

    /** 蒙层设置区：开关 + 浓度滑块 + 黑白配色，改动即时生效 */
    private View buildMaskSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.addView(sectionTitle("背景蒙层"));

        final boolean[] on = {prefs.getBoolean("skin_mask_on", true)};

        final TextView sw = new TextView(this);
        sw.setTextSize(16);
        sw.setTypeface(null, Typeface.BOLD);
        sw.setTextColor(getColor(R.color.text_primary));
        sw.setPadding(dp(2), dp(12), dp(2), dp(12));
        sec.addView(sw);

        final TextView label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setPadding(0, dp(4), 0, 0);
        sec.addView(label);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(prefs.getInt("skin_mask_alpha", 35));
        sec.addView(bar);

        final TextView[] colorRows = new TextView[MASK_LABELS.length];
        for (int i = 0; i < MASK_LABELS.length; i++) {
            final int idx = i;
            colorRows[i] = optionRow(MASK_LABELS[i], () -> {
                prefs.edit().putInt("skin_mask_color", idx).apply();
                applySkin();
                refreshOptions(colorRows, MASK_LABELS, idx);
            });
            sec.addView(colorRows[i]);
        }

        final Runnable refresh = () -> {
            sw.setText(on[0] ? "蒙层：开" : "蒙层：关");
            label.setText("浓度 " + bar.getProgress() + "%");
            int vis = on[0] ? View.VISIBLE : View.GONE;
            label.setVisibility(vis);
            bar.setVisibility(vis);
            for (TextView r : colorRows) r.setVisibility(vis);
            refreshOptions(colorRows, MASK_LABELS, prefs.getInt("skin_mask_color", 0));
        };

        sw.setOnClickListener(v -> {
            boolean next = !on[0];
            prefs.edit().putBoolean("skin_mask_on", next).apply();
            on[0] = next;
            applySkin();
            refresh.run();
        });

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                label.setText("浓度 " + p + "%");
                if (fromUser) {
                    prefs.edit().putInt("skin_mask_alpha", p).apply();
                    applySkin();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        refresh.run();
        return sec;
    }

    private void pickSkinImage() {
        Intent in = new Intent(Intent.ACTION_GET_CONTENT);
        in.setType("image/*");
        startActivityForResult(in, 102);
    }

    /** 把选中的图按屏幕比例居中裁剪、压到屏幕尺寸内，存成皮肤文件 */
    private boolean saveSkinBitmap(Uri uri) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            InputStream s = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(s, null, probe);
            if (s != null) s.close();

            int vw = getResources().getDisplayMetrics().widthPixels;
            int vh = getResources().getDisplayMetrics().heightPixels;
            int sample = 1;
            while (probe.outWidth / sample > vw * 2 || probe.outHeight / sample > vh * 2) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream s2 = getContentResolver().openInputStream(uri);
            Bitmap src = BitmapFactory.decodeStream(s2, null, opts);
            if (s2 != null) s2.close();
            if (src == null) return false;

            // 居中裁剪成屏幕比例，铺满时才不变形
            float screenRatio = (float) vw / vh;
            float imgRatio = (float) src.getWidth() / src.getHeight();
            Bitmap cropped;
            if (imgRatio > screenRatio) {
                int w = Math.round(src.getHeight() * screenRatio);
                int x = (src.getWidth() - w) / 2;
                cropped = Bitmap.createBitmap(src, x, 0, w, src.getHeight());
            } else {
                int h = Math.round(src.getWidth() / screenRatio);
                int y = (src.getHeight() - h) / 2;
                cropped = Bitmap.createBitmap(src, 0, y, src.getWidth(), h);
            }
            if (cropped != src) src.recycle();

            File out = new File(getFilesDir(), "skin.jpg");
            FileOutputStream fo = new FileOutputStream(out);
            cropped.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            fo.close();
            cropped.recycle();

            // 换图后旧缓存作废
            if (skinBmp != null) {
                skinBmp.recycle();
                skinBmp = null;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 相册背景上的蒙层颜色。返回 0 表示不叠加（用原图）。
     * 开关、浓度、黑白都可由用户调，默认 35% + 跟随外观配色。
     */
    private int maskColor(boolean dark) {
        if (!prefs.getBoolean("skin_mask_on", true)) return 0;
        int pct = Math.max(0, Math.min(100, prefs.getInt("skin_mask_alpha", 35)));
        if (pct == 0) return 0;
        int mode = prefs.getInt("skin_mask_color", 0);   // 0 自动 / 1 白 / 2 黑
        boolean black = mode == 2 || (mode == 0 && dark);
        int a = Math.round(pct * 255 / 100f);
        return (a << 24) | (black ? 0x000000 : 0xFFFFFF);
    }

    private Bitmap loadSkinBitmap() {
        if (skinBmp != null) return skinBmp;
        File f = new File(getFilesDir(), "skin.jpg");
        if (!f.exists() || f.length() == 0) return null;
        skinBmp = BitmapFactory.decodeFile(f.getPath());
        return skinBmp;
    }

    /** 图片顶部一小条的平均色，用作状态栏底色 */
    private static int topAverageColor(Bitmap b) {
        int w = b.getWidth();
        int h = Math.min(48, b.getHeight());
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 8) {
                int c = b.getPixel(x, y);
                r += Color.red(c);
                g += Color.green(c);
                bl += Color.blue(c);
                n++;
            }
        }
        if (n == 0) return 0xFF808080;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** 两种颜色按蒙层的透明度混合，算出状态栏实际该显示的颜色 */
    private static int blend(int base, int mask) {
        int a = Color.alpha(mask);
        int r = (Color.red(base) * (255 - a) + Color.red(mask) * a) / 255;
        int g = (Color.green(base) * (255 - a) + Color.green(mask) * a) / 255;
        int b = (Color.blue(base) * (255 - a) + Color.blue(mask) * a) / 255;
        return Color.rgb(r, g, b);
    }

    // ================= 设置 =================

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), dp(4));

        box.addView(sectionTitle("单词发音"));
        final TextView[] voiceRows = new TextView[EdgeTts.VOICE_LABELS.length];
        for (int i = 0; i < EdgeTts.VOICE_LABELS.length; i++) {
            final int idx = i;
            voiceRows[i] = optionRow(EdgeTts.VOICE_LABELS[i], () -> {
                pron.setVoice(idx);
                refreshOptions(voiceRows, EdgeTts.VOICE_LABELS, idx);
            });
            box.addView(voiceRows[i]);
        }
        refreshOptions(voiceRows, EdgeTts.VOICE_LABELS, pron.getVoice());

        TextView voiceNote = new TextView(this);
        voiceNote.setText("来源：微软 Edge 神经语音。首次合成约 1~3 秒，之后永久缓存、点击秒播。");
        voiceNote.setTextColor(getColor(R.color.text_secondary));
        voiceNote.setTextSize(11);
        voiceNote.setPadding(0, dp(6), 0, 0);
        box.addView(voiceNote);

        // 预缓存常用词：把发音提前搬到手机里，之后断网也能读
        final TextView batch = new TextView(this);
        batch.setTextColor(getColor(R.color.text_secondary));
        batch.setTextSize(13);
        batch.setPadding(0, dp(12), 0, dp(4));
        final Runnable refreshBatch = () -> {
            if (pron.isBatchRunning()) {
                batch.setText("预缓存常用词：进行中 " + pron.batchDone() + "/" + pron.batchTotal()
                        + "（点此停止）");
            } else {
                batch.setText("预缓存 500 个常用词的发音（约 5MB，点此开始）");
            }
        };
        batch.setOnClickListener(v -> {
            if (pron.isBatchRunning()) {
                pron.stopBatch();
            } else if (dictReady) {
                pron.startBatch(dict.topWords(500));
                Toast.makeText(this, "已开始后台缓存，可随时停止", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "词典还在加载，稍等一下", Toast.LENGTH_SHORT).show();
            }
            refreshBatch.run();
        });
        refreshBatch.run();
        box.addView(batch);

        TextView cacheNote = new TextView(this);
        cacheNote.setText("缓存好的单词断网也能朗读；没缓存又没网的词，会自动改用手机自带语音出声。");
        cacheNote.setTextColor(getColor(R.color.text_secondary));
        cacheNote.setTextSize(11);
        cacheNote.setPadding(0, dp(4), 0, 0);
        box.addView(cacheNote);

        box.addView(sectionTitle("素材包"));
        final TextView packRow = optionRow("", () -> { });
        final Runnable refreshPack = () -> {
            if (packBusy) {
                packRow.setText("素材包：下载中 " + packDone + "/" + packTotal + "…");
            } else if (prefs.getBoolean(PREF_PACK, false)) {
                packRow.setText("素材包：已下载 " + (packSize() / 1048576) + " MB（点此删除）");
            } else {
                packRow.setText("素材包：未下载（点此下载阅读与听力的其余素材）");
            }
        };
        packRow.setOnClickListener(v -> {
            if (packBusy) return;
            if (prefs.getBoolean(PREF_PACK, false)) {
                deletePack();
                Toast.makeText(this, "素材包已删除，只保留内置的", Toast.LENGTH_SHORT).show();
            } else {
                if (packBase == null || packBase.isEmpty()) {
                    Toast.makeText(this, "清单里没配下载地址，稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, "开始下载，可在后台慢慢下", Toast.LENGTH_SHORT).show();
                downloadPack(null);
            }
            refreshPack.run();
        });
        refreshPack.run();
        box.addView(packRow);
        TextView packNote = new TextView(this);
        packNote.setText("内置的那几篇随时能看能听；开启后会从 GitHub 把其余素材存到手机，"
                + "关闭则删掉已下载的。素材来自公有领域文本与 VOA 慢速英语存量。");
        packNote.setTextColor(getColor(R.color.text_secondary));
        packNote.setTextSize(11);
        packNote.setPadding(0, dp(4), 0, 0);
        box.addView(packNote);

        box.addView(sectionTitle("外观"));
        final TextView[] modeRows = new TextView[ThemeHelper.MODE_LABELS.length];
        for (int i = 0; i < ThemeHelper.MODE_LABELS.length; i++) {
            final int mode = i;
            modeRows[i] = optionRow(ThemeHelper.MODE_LABELS[i], () -> {
                if (ThemeHelper.getMode(this) == mode) return;
                ThemeHelper.setMode(this, mode);
                recreate();   // 重建后 attachBaseContext 会套用新的 uiMode
            });
            box.addView(modeRows[i]);
        }
        refreshOptions(modeRows, ThemeHelper.MODE_LABELS, ThemeHelper.getMode(this));

        TextView clear = new TextView(this);
        clear.setText("清空发音缓存（当前 " + pron.cacheCount() + " 条）");
        clear.setTextColor(getColor(R.color.text_secondary));
        clear.setTextSize(13);
        clear.setPadding(0, dp(16), 0, dp(4));
        clear.setOnClickListener(v -> {
            int n = pron.clearCache();
            clear.setText("清空发音缓存（当前 0 条）");
            Toast.makeText(this, "已清空 " + n + " 条发音缓存", Toast.LENGTH_SHORT).show();
        });
        box.addView(clear);

        // 缓存进行中时，让进度那一行自己刷新
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                refreshBatch.run();
                refreshPack.run();
                if (pron.isBatchRunning() || packBusy) h.postDelayed(this, 1500);
            }
        };
        if (pron.isBatchRunning() || packBusy) h.postDelayed(tick, 1500);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("好", null)
                .show();
        d.setOnDismissListener(x -> h.removeCallbacks(tick));
    }

    /** 长按喇叭时的快捷音色切换 */
    private void showVoiceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("发音音色")
                .setSingleChoiceItems(EdgeTts.VOICE_LABELS, pron.getVoice(), (d, which) -> {
                    pron.setVoice(which);
                    d.dismiss();
                    Toast.makeText(this, "已切换为 " + EdgeTts.VOICE_LABELS[which], Toast.LENGTH_SHORT).show();
                    if (adapter != null) adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setPadding(0, dp(16), 0, 0);
        return t;
    }

    private TextView optionRow(String label, Runnable onPick) {
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setPadding(dp(2), dp(12), dp(2), dp(12));
        t.setOnClickListener(v -> onPick.run());
        return t;
    }

    private void refreshOptions(TextView[] rows, String[] labels, int selected) {
        for (int i = 0; i < rows.length; i++) {
            boolean on = i == selected;
            rows[i].setText((on ? "● " : "○ ") + labels[i]);
            rows[i].setTextColor(getColor(on ? R.color.text_primary : R.color.text_secondary));
            rows[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** 状态栏/导航栏跟着背景走，图标明暗按背景亮度自动反色 */
    private void applySystemBars() {
        int bg = getColor(R.color.bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (isDarkColor(bg)) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private static boolean isDarkColor(int c) {
        double lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        return lum < 0.5;
    }

    // ================= 列表适配器 =================

    private class ItemAdapter extends BaseAdapter {
        ArrayList<Item> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Item it = items.get(pos);
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(14), dp(20), dp(14));

            if (it.type == 2) {
                TextView t = new TextView(MainActivity.this);
                t.setText(it.text);
                t.setTextColor(getColor(R.color.text_primary));
                t.setTextSize(17);
                box.addView(t);
                if (it.sub != null && !it.sub.isEmpty()) {
                    TextView s = new TextView(MainActivity.this);
                    s.setText(it.sub);
                    s.setTextColor(getColor(R.color.text_secondary));
                    s.setTextSize(14);
                    s.setPadding(0, dp(6), 0, 0);
                    box.addView(s);
                }
                return box;
            }

            if (it.type == 1) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView w = new TextView(MainActivity.this);
                w.setText(it.text);
                w.setTextColor(getColor(R.color.text_primary));
                w.setTextSize(17);
                row.addView(w);
                addInlineMarks(row, it.entry);
                box.addView(row);
                if (it.sub != null && !it.sub.isEmpty()) {
                    TextView s = new TextView(MainActivity.this);
                    s.setText(it.sub);
                    s.setTextColor(getColor(R.color.text_secondary));
                    s.setTextSize(14);
                    s.setMaxLines(1);
                    s.setPadding(0, dp(4), 0, 0);
                    box.addView(s);
                }
                return box;
            }

            // ===== 结果卡片 =====
            DictDb.Entry e = it.entry;

            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView w = new TextView(MainActivity.this);
            w.setText(e.word);
            w.setTextColor(getColor(R.color.text_primary));
            w.setTextSize(26);
            w.setTypeface(null, Typeface.BOLD);
            head.addView(w);
            if (e.phonetic != null && !e.phonetic.isEmpty()) {
                TextView ph = new TextView(MainActivity.this);
                ph.setText("  /" + e.phonetic + "/");
                ph.setTextColor(getColor(R.color.text_secondary));
                ph.setTextSize(15);
                head.addView(ph);
            }
            // 把喇叭推到最右侧
            TextView spacer = new TextView(MainActivity.this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
            head.addView(spacer);
            head.addView(buildSpeaker(e));
            box.addView(head);

            // ===== 词级标记：六级重点词（红）+ 高频词（金星）+ 其他标签（灰）=====
            LinearLayout marks = new LinearLayout(MainActivity.this);
            marks.setOrientation(LinearLayout.HORIZONTAL);
            marks.setGravity(Gravity.CENTER_VERTICAL);
            int markCount = 0;
            if (hasTag(e, "cet6")) {
                marks.addView(chip("六级重点", R.color.mark_cet6));
                markCount++;
            }
            int star = starLevel(e.frq);
            if (star > 0) {
                marks.addView(chip("高频 " + stars(star), R.color.mark_high));
                markCount++;
            }
            if (e.tag != null) {
                for (String p : e.tag.trim().split("\\s+")) {
                    if ("cet6".equals(p)) continue;
                    for (int i = 0; i < TAG_KEYS.length; i++) {
                        if (TAG_KEYS[i].equals(p)) {
                            marks.addView(chip(TAG_NAMES[i], R.color.text_secondary));
                            markCount++;
                            break;
                        }
                    }
                }
            }
            if (e.collins >= 1 && e.collins <= 5) {
                marks.addView(chip("柯林斯 " + stars(e.collins), R.color.text_secondary));
                markCount++;
            }
            if (e.oxford == 1) {
                marks.addView(chip("牛津3000", R.color.text_secondary));
                markCount++;
            }
            if (markCount > 0) {
                marks.setPadding(0, dp(10), 0, 0);
                box.addView(marks);
            }

            if (e.translation != null && !e.translation.isEmpty()) {
                TextView tr = new TextView(MainActivity.this);
                tr.setText(e.translation.trim());
                tr.setTextColor(getColor(R.color.text_primary));
                tr.setTextSize(16);
                tr.setLineSpacing(dp(4), 1);
                tr.setPadding(0, dp(14), 0, 0);
                box.addView(tr);
            }

            // ===== 例句（紧跟中文释义，便于对照记忆） =====
            if (it.examples != null && !it.examples.isEmpty()) {
                TextView eh = new TextView(MainActivity.this);
                eh.setText("例句");
                eh.setTextColor(getColor(R.color.text_secondary));
                eh.setTextSize(13);
                eh.setPadding(0, dp(18), 0, 0);
                box.addView(eh);
                for (DictDb.Example x : it.examples) box.addView(buildExampleRow(x));
            }

            if (e.definition != null && !e.definition.isEmpty()) {
                TextView de = new TextView(MainActivity.this);
                de.setText(e.definition.trim());
                de.setTextColor(getColor(R.color.text_secondary));
                de.setTextSize(15);
                de.setLineSpacing(dp(4), 1);
                de.setPadding(0, dp(12), 0, 0);
                box.addView(de);
            }

            Map<String, String> ex = parseExchange(e.exchange);
            if (!ex.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> en : ex.entrySet()) {
                    if (sb.length() > 0) sb.append("   ");
                    sb.append(en.getKey()).append(" ").append(en.getValue());
                }
                TextView exv = new TextView(MainActivity.this);
                exv.setText(sb.toString());
                exv.setTextColor(getColor(R.color.text_secondary));
                exv.setTextSize(13);
                exv.setPadding(0, dp(14), 0, 0);
                box.addView(exv);
            }
            return box;
        }
    }

    private class HistoryAdapter extends BaseAdapter {
        ArrayList<String> words = new ArrayList<>();

        @Override public int getCount() { return words.size(); }
        @Override public Object getItem(int p) { return words.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView t = new TextView(MainActivity.this);
            t.setText(words.get(pos));
            t.setTextColor(getColor(R.color.text_primary));
            t.setTextSize(17);
            t.setPadding(0, dp(14), 0, dp(14));
            return t;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
