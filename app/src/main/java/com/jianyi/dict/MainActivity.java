package com.jianyi.dict;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private TextView dictStatus;                                   // 搜索框上方那行词典准备提示
    private final android.os.Handler dictStatusHandler = new android.os.Handler();
    private final ExecutorService dbPool = Executors.newSingleThreadExecutor();
    private final ExecutorService netPool = Executors.newSingleThreadExecutor(); // 网络请求独立线程，不阻塞词典查询
    private final ExecutorService packPool = Executors.newSingleThreadExecutor(); // 素材/清单下载，不和翻译排队互相等
    private final AtomicInteger searchSeq = new AtomicInteger();
    // 联想防抖：中文反查是无索引全表扫（实测 ~130ms 一次），原先每敲一个字符
    // 就往单线程 dbPool 塞一个任务，打 "translation" 要串行扫 22 次才出结果。
    private final Handler suggestHandler = new Handler(Looper.getMainLooper());
    private Runnable suggestPending;
    private static final long SUGGEST_DEBOUNCE_MS = 160;

    /** 一条还没落地的答题/复习落库记录 */
    private static final class StudyWrite {
        static final int CORRECT = 0, WRONG = 1, RELEARN = 2, FAV = 3, UNFAV = 4, RESET = 5;
        final String word;
        final int kind;
        /** 落库完成后要在界面线程补做的刷新（比如重新渲染单词本），可为 null */
        final Runnable after;
        StudyWrite(String w, int k) { this(w, k, null); }
        StudyWrite(String w, int k, Runnable r) { word = w; kind = k; after = r; }
    }

    // 排在 dbPool 里还没跑完的答题落库（见 queueStudyWrite）
    private final ArrayList<StudyWrite> pendingStudyWrites = new ArrayList<>();

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
    private final android.os.Handler transWatchdogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable transWatchdog;          // 离线翻译 30 秒超时看门狗，成功/失败/退出都要撤掉
    private android.content.SharedPreferences prefs;

    private EditText ocrText;
    private Uri pendingPhotoUri;
    private TextRecognizer recognizer;       // 中文模型（中英混排的主力）
    private TextRecognizer latinRecognizer;  // 拉丁模型（纯英文更准，v3.3 起双跑取优）

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

    private View topBar, tabBar, topDivider, pageStudy, pageReading, pageWordBook, pageWrongBook, fabWordBook, fabWrongBook, fabStudyStats;
    private View pageGame, fabGame;
    private MatchGameView gameView;
    private TextView gameScore, gameTip;
    private GameWords gameWords;
    private View contentHost;               // 顶栏/标签栏下面的内容容器（几何补偿用）
    private DragHint studyHint, readingHint, wbHint, wrongHint;
    private TextView pullHint, studyProgress, readingTitle;
    private LinearLayout studyBody, readingBody, wbBody, wrongBody;
    private TextView wbCount, wrongCount;
    private StudyDb studyDb;
    private AlertDialog studyPanelDialog;      // 背词进度面板
    // 面板的进度快照 {高频, 中频, 低频, 全部}，null=还没读过。四个 int，留着不占地方，
    // 换来的是一进面板不用在界面线程上数库，下次打开还能先画出旧数字再刷新。
    private int[] studySeenSnapshot;
    // 面板的刷新入口，随弹窗 dismiss 置空——它捕获了整棵面板视图树，留着就是留着几十个 View
    private Runnable studyPanelRefresh;
    private boolean studySummaryCounted;       // 本组是否已经计入「今日进度」
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
        ArrayList<DictDb.Phrase> phrases;    // 结果卡片下方的常用搭配（v3.10 词组训练）
    }

    /** 外观模式来自用户设置，必须在取资源之前改写 uiMode，否则深色不生效 */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(ThemeHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupCrashHandler();
        // 崩溃提示不在这里弹：onCreate 提前 return 会让 Activity 变成空壳窗口，
        // 对话框挂上去很容易被焦点/窗口变化收走（v3.0 的"窗口一闪就没"就是这么来的）。
        // 改到 onCreate 末尾、界面完全建好之后再弹（见 maybeShowPastCrash）。
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
        loadReadingManifest();   // 阅读清单也要在这里就绪：下载素材包要用，别等用户先点开阅读页
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

        // 单词本 / 错题集（v3.0）：两个右下角圆形按钮，进各自独立沉浸页
        pageWordBook = findViewById(R.id.pageWordBook);
        wbBody = findViewById(R.id.wbBody);
        wbCount = findViewById(R.id.wbCount);
        findViewById(R.id.wbBack).setOnClickListener(v -> closeWordBook());
        pageWrongBook = findViewById(R.id.pageWrongBook);
        wrongBody = findViewById(R.id.wrongBody);
        wrongCount = findViewById(R.id.wrongCount);
        findViewById(R.id.wrongBack).setOnClickListener(v -> closeWrongBook());
        fabWordBook = findViewById(R.id.fabWordBook);
        fabWrongBook = findViewById(R.id.fabWrongBook);
        fabWordBook.setOnClickListener(v -> openWordBook());
        fabWrongBook.setOnClickListener(v -> openWrongBook());
        fabStudyStats = findViewById(R.id.fabStudyStats);
        fabStudyStats.setOnClickListener(v -> showStudyPanel());
        // 配对游戏（v3.6）：首页左下角 FAB，进独立全屏页
        pageGame = findViewById(R.id.pageGame);
        gameView = findViewById(R.id.gameView);
        gameScore = findViewById(R.id.gameScore);
        gameTip = findViewById(R.id.gameTip);
        findViewById(R.id.gameBack).setOnClickListener(v -> closeGame());
        dictStatus = findViewById(R.id.dictStatus);
        gameView.setOnWaveChanged(this::refreshGameTip);
        gameView.setListener((sc, cb, bs) -> {
            StringBuilder sb = new StringBuilder("得分 ").append(sc);
            if (cb >= 2) sb.append("　连击 ×").append(cb);
            sb.append("　最高 ").append(bs);
            if (gameScore != null) gameScore.setText(sb.toString());
            if (prefs != null) {
                prefs.edit().putInt("game_best", bs).apply();   // 分数不存档，只留最高分
            }
        });
        fabGame = findViewById(R.id.fabGame);
        fabGame.setOnClickListener(v -> openGame());
        gameView.setOnMilestone(this::showGameMilestone);
        gameView.setOnExhausted(() -> {
            if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this)
                    .setTitle("词都出完了")
                    .setMessage("这本词典里能出的词已经全过了一遍。\n\n重新开始一轮？")
                    .setNegativeButton("先不玩", null)
                    .setPositiveButton("重新开始", (d, w) -> {
                        if (gameWords != null && gameView != null) {
                            gameWords.reset();
                            gameView.setSource(gameWords);
                        }
                    })
                    .show();
        });
        wbHint = setupExitSwipe(R.id.wbScroll, R.id.wbHint,
                pageWordBook, true, () -> { applyImmersive(false); updateFabs(); });   // 下滑退出后恢复顶栏/标签
        wrongHint = setupExitSwipe(R.id.wrongScroll, R.id.wrongHint,
                pageWrongBook, true, () -> updateFabs());   // 下滑退出后回到记背页（保持沉浸）
        findViewById(R.id.cropBack).setOnClickListener(v -> closeCrop());
        findViewById(R.id.btnCropAll).setOnClickListener(v -> {
            Bitmap b = takePendingOcr();        // 图交到我手上，回收责任也跟着过来
            closeCrop();                        // 字段已空，这里不会再回收一次
            if (b == null) return;
            ocrText.setText("识别中…");
            runOcr(b);
        });
        findViewById(R.id.btnCropOk).setOnClickListener(v -> {
            // cropRect() 认 CropView 里的图，必须在把图摘掉之前问
            Rect r = cropView == null ? null : cropView.cropRect();
            Bitmap b = takePendingOcr();
            closeCrop();
            if (b == null) return;
            ocrText.setText("识别中…");
            if (r == null || r.width() < 4 || r.height() < 4) {
                runOcr(b);
                return;
            }
            try {
                Bitmap cut = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
                if (cut != b) {                   // 裁出来的是新图，整张原图当场放手
                    runOcr(cut);                  // 先排任务再回收：runOcr 只碰 cut
                    b.recycle();
                    return;
                }
                runOcr(b);      // 裁剪没动刀（极少数情况下返回原图），回收交给 runOcr
            } catch (Throwable t) {
                // 能走到这里原图必然还活着：回收只发生在 runOcr(cut) 排好任务之后
                JLog.w("MainActivity 框选裁剪失败，退回整张识别", t);
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
        CloudTrans.init(prefs);        // 云端翻译（百度/腾讯云可切换）共用这份偏好
        refreshManifests();   // 启动静默检查素材目录更新（必须在 prefs 初始化之后，否则首屏 NPE）
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
        // 词典准备提示：不摆卡片（那东西太抢眼），只在慢设备上延迟显示一行小字。
        // ① 全新安装：文件不存在，要解压 50MB
        // ② 覆盖升级：老库没有 phrases 表，也要重新解压一次
        // 两种情况都先安静地等，超过 2.5 秒还没好才提示（见 showDictStatusDelayed）。
        final boolean needExtract = !dbFileReady();
        if (needExtract) {
            showDictStatusDelayed("正在准备词典（约 50MB，仅此一次）…");
        } else if (dictNeedsUpgrade()) {
            showDictStatusDelayed("正在更新词典（新增常用搭配，仅此一次）…");
        }

        // 词典在后台线程初始化，界面绝不卡顿
        submitDb("dictInit", () -> {
            try {
                DictDb d = new DictDb(MainActivity.this);
                // 顺手把词数分母数出来（实测桌面 71ms、355240 行全扫带 LIKE）。
                // 不在这儿预热的话，谁先碰到谁付这笔钱——最惨的是用户一进来就点背词面板，
                // 那就是一下子卡住界面线程七十多毫秒起步。
                d.cet6Count();
                runOnUiThread(() -> {
                    dict = d;
                    dictReady = true;
                    dictStatusHandler.removeCallbacksAndMessages(null);
                    hideDictStatus();
                    if (needExtract) {
                        adapter.items.clear();
                        adapter.notifyDataSetChanged();
                    }
                });
            } catch (Throwable t) {
                dictError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnUiThread(() -> showLoading("词典初始化失败：" + dictError));
            }
        });

        tabDict.setOnClickListener(v -> switchTab(PAGE_DICT));
        tabTranslate.setOnClickListener(v -> switchTab(PAGE_TRANS));
        tabCamera.setOnClickListener(v -> switchTab(PAGE_CAM));
        // 历史按钮改成「切换」：开着就收起、关着就打开（#1）。
        // 原来写死 showHistory(true)，导致点第二次毫无反应、历史页一旦打开就关不掉。
        btnHistory.setOnClickListener(v -> showHistory(pageHistory.getVisibility() != View.VISIBLE));
        findViewById(R.id.titleText).setOnClickListener(v -> showHistory(false));
        // 历史页顶部新加的「返回」：打开历史后也能一键回到查词页，不必再按系统返回
        findViewById(R.id.historyBack).setOnClickListener(v -> showHistory(false));
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

        // 界面已完全就绪，这时候弹崩溃记录才不会被闪掉
        maybeShowPastCrash();
    }

    // ================= 后台检索：联想 =================

    private void scheduleSuggest(String raw) {
        scheduleSuggest(raw, false);
    }

    /**
     * @param immediate 用户已经明确表达"就要这次查询"（按回车 / 点联想项）时走即时通道。
     *                  防抖只该挡"正在打字"的中间态，不该让回车比不回车还慢一拍。
     */
    private void scheduleSuggest(String raw, boolean immediate) {
        final int seq = searchSeq.incrementAndGet();
        final String q = raw == null ? "" : raw.trim();
        // 作废上一次击键排队中的那次查询。doLookup 同样会推 searchSeq，
        // 所以按回车之后迟到的联想结果会在 runSuggest 的守卫处被丢掉，不会盖掉结果卡片。
        if (suggestPending != null) suggestHandler.removeCallbacks(suggestPending);
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
        // 清空和"词典未就绪"是即时反馈，不防抖；只有真正要扫库的查询才延后一拍
        if (immediate) {
            runSuggest(q, seq);
        } else {
            suggestPending = () -> runSuggest(q, seq);
            suggestHandler.postDelayed(suggestPending, SUGGEST_DEBOUNCE_MS);
        }
    }

    private void runSuggest(final String q, final int seq) {
        submitDb("runSuggest", () -> {
            // 防抖窗口里用户又改了输入、或已经按回车查词：这一趟扫库根本没意义
            if (searchSeq.get() != seq) return;
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
                    // 一次扫描就够：以前是拿 15 个词再逐个 lookup()，每敲一个字符
                    // 多发 15 条查询、还把 definition 整块读进内存
                    for (DictDb.Entry e : dict.suggestEntries(q, 15)) {
                        Item it = new Item();
                        it.type = 1;
                        it.text = e.word;
                        it.entry = e;   // 带上词条，联想列表才能显示六级/高频标记
                        it.sub = firstLine(e.translation);
                        items.add(it);
                    }
                }
            } catch (Throwable ignored) { JLog.w("MainActivity.runSuggest", ignored);
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
            // 回车是显式动作，不能让它比不打字还多等一个防抖窗口
            scheduleSuggest(q, true);
            return;
        }
        final int seq = searchSeq.incrementAndGet();
        submitDb("searchJump", () -> {
            DictDb.Entry e = null;
            try {
                e = dict.lookup(q);
                // 词组在 stardict 里没有词条，释义只在搭配表：不兜这一下就报"未收录"
                if (e == null) e = dict.lookupPhrase(q);
            } catch (Throwable ignored) { JLog.w("MainActivity.doLookup", ignored);
             }
            final DictDb.Entry found = e;
            ArrayList<DictDb.Example> exs = new ArrayList<>();
            ArrayList<DictDb.Phrase> phs = new ArrayList<>();
            if (found != null) {
                try {
                    exs = dict.examples(found.word);
                } catch (Throwable ignored) { JLog.w("MainActivity.doLookup", ignored);
                 }
                // 常用搭配（v3.10 词组训练 A）：查这个词时带出它的固定搭配
                try {
                    phs = dict.phrases(found.word, 6);
                } catch (Throwable ignored) { JLog.w("MainActivity.doLookup", ignored);
                 }
            }
            final ArrayList<DictDb.Example> examples = exs;
            final ArrayList<DictDb.Phrase> phrases = phs;
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
                    it.phrases = phrases;
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

    /** 词典文件是不是已经解压好了（决定要不要显示"解压中"那张卡） */
    private boolean dbFileReady() {
        try {
            File f = new File(getFilesDir(), "dict.db");
            return f.exists() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 手机上的词典是不是旧版（缺 phrases 表）？旧版会在初始化时重新解压一次新词典。
     * 探测不出来就返回 false——只是少显示一句提示，不影响功能。
     */
    private boolean dictNeedsUpgrade() {
        try {
            File f = new File(getFilesDir(), "dict.db");
            if (!f.exists() || f.length() == 0) return false;
            android.database.sqlite.SQLiteDatabase d =
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                            f.getPath(), null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = d.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='phrases'", null);
            boolean has = c.moveToFirst();
            c.close();
            d.close();
            return !has;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showLoading(String msg) {
        // 卡片提示和顶部小字别同时出现，否则同一件事说了两遍
        hideDictStatus();
        adapter.items.clear();
        Item it = new Item();
        it.type = 2;
        it.text = msg;
        it.sub = "";
        adapter.items.add(it);
        adapter.notifyDataSetChanged();
    }

    // ================= 词典准备提示（不打扰式）=====================

    /**
     * 词典解压/更新时的一行提示，不占用列表区。
     *
     * 为什么要「延迟显示」：解 50MB 词典在快机器上只要一两秒，
     * 一上来就摆提示等于闪一下，反而像出了问题。改成先安静地等，
     * 超过 {@link #DICT_STATUS_DELAY_MS} 还没好才显示这行小字 ——
     * 快机器几乎察觉不到，慢机器还能看出 App 在忙。
     */
    private void showDictStatus(String msg) {
        if (dictStatus == null) return;
        dictStatus.setText(msg);
        dictStatus.setVisibility(View.VISIBLE);
    }

    private void hideDictStatus() {
        if (dictStatus != null) dictStatus.setVisibility(View.GONE);
    }

    /** 超过这个时间还没解压完，才显示那行提示（毫秒） */
    private static final long DICT_STATUS_DELAY_MS = 2500;

    /** 延迟显示词典提示；已就绪时调用它什么也不做 */
    private void showDictStatusDelayed(final String msg) {
        dictStatusHandler.postDelayed(() -> {
            if (!dictReady) showDictStatus(msg);
        }, DICT_STATUS_DELAY_MS);
    }

    // ================= 崩溃自捕获：下次启动可查看并复制 =================

    private void setupCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String stamp = "---- " + System.currentTimeMillis() + " ----\n" + sw.toString();
                writeCrashFile(new File(getFilesDir(), "crash.log"), stamp);
                File ext = getExternalFilesDir(null);     // 外部存储：USB 直接拉，不依赖对话框
                if (ext != null) writeCrashFile(new File(ext, "crash.log"), stamp);
            } catch (Throwable ignored) { JLog.w("MainActivity.setupCrashHandler", ignored);
             }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /**
     * 上一次运行崩过就把栈弹出来（界面上已有内容，框不会闪）。
     * 必须在 setContentView 与全部 findViewById 之后调用。
     */
    private void maybeShowPastCrash() {
        File f = new File(getFilesDir(), "crash.log");
        if (!f.exists() || f.length() == 0) return;
        String full = readCrashFile(f);
        f.delete();                       // 读失败也删，避免每次启动都卡在这里
        if (full == null || full.trim().isEmpty()) return;
        // 再等一次窗口挂载，双保险
        getWindow().getDecorView().post(() -> showCrashDialog(full));
    }

    private void writeCrashFile(File f, String content) {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(f, true));
            pw.println(content);
            pw.close();
        } catch (Throwable ignored) { JLog.w("MainActivity.writeCrashFile", ignored);
         }
    }

    private String readCrashFile(File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable e) {
            return "(读取崩溃记录失败)";
        }
    }

    private void showCrashDialog(String text) {
        if (isFinishing() || isDestroyed()) return;   // 界面已经收了就别弹，免得 leak window
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("上次运行出错了（复制发联创即可定位）")
                .setView(sv)
                .setCancelable(false)                 // 点外面/返回键都不关，先看清
                .setPositiveButton("复制并关闭", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                    Toast.makeText(this, "已复制，发给联创即可定位", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("关闭", null)
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
        updateFabs();                                    // 查词页亮单词本按钮，其余收起
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
        t.setBackgroundResource(on ? R.drawable.tab_pill : 0);
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
            JSONObject root = new JSONObject(readManifest("reading"));
            String base = safePackBase(root.optString("baseUrl", null));
            if (base != null) packBase = base;
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
        } catch (Throwable ignored) { JLog.w("MainActivity.loadReadingManifest", ignored);
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

    /**
     * 读清单：下载目录里联网刷新过的那份与 APK 内置那份，取版本号较高者；
     * 这样升级后 APK 内置的新清单（删掉了旧素材）能压过手机上残留的旧 on-device 清单。
     * 旧 on-device 清单若过期就直接删掉，避免残留旧素材条目。
     */
    private String readManifest(String kind) {
        String apk = readAsset(kind + "/manifest.json");
        int apkV = manifestVersion(apk);
        File f = new File(packDir(), kind + "/manifest.json");
        String onDisk = null;
        if (f.exists() && f.length() > 0 && f.length() < 512 * 1024) {
            try {
                FileInputStream fis = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                fis.close();
                String s = bos.toString("UTF-8");
                new JSONObject(s);          // 校验是合法 JSON，坏了就回落
                onDisk = s;
            } catch (Throwable ignored) { JLog.w("MainActivity.readManifest", ignored);
             }
        }
        int onV = manifestVersion(onDisk);
        if (onV > apkV) return onDisk != null ? onDisk : apk;
        if (onDisk != null && apkV > onV) f.delete();   // 旧清单过期，清掉避免残留
        return apk;
    }

    private static int manifestVersion(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            return new JSONObject(json).optInt("version", 0);
        } catch (Throwable t) {
            return 0;
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

    /** 阅读页点词：查词典并弹小卡片；查不到就试着去掉复数/所有格，最后兜词组 */
    private void lookupWord(String raw) {
        // 只删标点，空格必须留着：阅读页点到的可能是整条词组（"give up"），
        // 老写法把空格一并删掉就成了 "giveup"，注定查不到。
        final String key = raw.toLowerCase(Locale.US)
                .replaceAll("[^a-z' ]", " ").replaceAll(" {2,}", " ").trim();
        if (key.isEmpty()) return;
        if (!dictReady || dict == null) {                       // 词典没就绪就提示，不抛 NPE
            Toast.makeText(this, "词典还在加载，稍等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        submitDb("lookupWord", () -> {
            DictDb.Entry e;
            try {
                e = dict.lookup(key);
                // 去复数只针对单个词：词组结尾的 s 属于短语本身，砍掉只会查得更偏
                if (e == null && key.indexOf(' ') < 0 && key.endsWith("s"))
                    e = dict.lookup(key.substring(0, key.length() - 1));
                if (e == null && key.indexOf(' ') < 0 && key.endsWith("es"))
                    e = dict.lookup(key.substring(0, key.length() - 2));
                if (e == null) e = dict.lookupPhrase(key);      // 词组释义在搭配表里
            } catch (Throwable t) {
                // 词典句柄可能在退出时被关掉，这里必须接住：后台线程抛出去就是崩溃
                JLog.e("MainActivity.lookupWord", t);
                return;
            }
            final DictDb.Entry fe = e;
            runOnUiThread(() -> showWordCard(key, fe));
        });
    }

    private void showWordCard(String word, DictDb.Entry e) {
        if (isFinishing() || isDestroyed()) return;        // 弹窗晚到就是 BadTokenException + window leak
        String body = (e == null || e.translation == null || e.translation.isEmpty())
                ? "词典里没找到这个词" : firstMeaning(e.translation);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(word)
                .setMessage(body)
                .setNegativeButton("关闭", null);
        if (e != null) {
            b.setNeutralButton("朗读", (d, w) -> onSpeakerClick(word));
            b.setPositiveButton("加入复习", (d, w) -> {
                // 别用 studyDb.mark()：它是 INSERT OR IGNORE，词只要已经在复习表里
                // （哪怕 correct() 早把 due 推到十几天后）就是条空语句，
                // 提示写着"已加入复习"其实什么都没发生。relearn 才等于"现在就要复习它"。
                recordRelearn(word);
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
    private String packBase = "";          // 清单里给的根地址（只接受 https + 白名单主机，见 safePackBase）
    private static final String[] PACK_HOSTS = {
            "cdn.jsdelivr.net", "raw.githubusercontent.com", "github.com"};
    private static final long MAX_PACK_BYTES = 32L * 1024 * 1024;   // 单个素材文件上限，防远端把存储灌满
    private volatile int packDone, packTotal;   // 后台线程写、UI 线程读进度
    private volatile boolean packBusy;

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
            } catch (Throwable ignored) { JLog.w("MainActivity.readMaterial", ignored);
             }
        }
        return readAsset(kind + "/" + name);
    }

    /** 开启素材包：把清单里没内置的素材逐个拉到手机 */
    private void downloadPack(final Runnable onDone) {
        if (packBusy) return;
        // 兜底：清单还没加载就点了按钮（v3.0.2 及之前的闪退就出在这里）
        if (readingItems == null) loadReadingManifest();
        if (listenItems == null) loadListenManifest();
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
        if (packPool.isShutdown()) return;
        packPool.execute(() -> {
            int fail = 0;
            for (String item : rels) {
                int sep = item.indexOf(':');
                if (sep <= 0 || sep >= item.length() - 1) {      // 清单里的坏条目，跳过而不是抛异常
                    JLog.w("MainActivity.downloadPack", "条目格式不对: " + item);
                    fail++;
                    packDone++;
                    continue;
                }
                String kind = item.substring(0, sep);
                String rel = item.substring(sep + 1);
                File dir = new File(packDir(), kind);
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, relName(rel));
                if (out.exists() && out.length() > 0) {          // 下过了就跳过，增量更新只拉新的
                    packDone++;
                    continue;
                }
                File part = new File(dir, relName(rel) + ".part");
                // 先写 .part 再 rename：任何一步失败都不会留下半截的正式文件
                if (downloadToFile(packBase + "/" + rel, part) && part.renameTo(out)) {
                    // 成功
                } else {
                    part.delete();
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
        });
    }

    /**
     * 下载单个素材文件到 dst。
     * 非 2xx、超过 MAX_PACK_BYTES、中途断掉都算失败——返回 false 让调用方清掉临时文件。
     * 老代码连响应码都不看，错误页会被当成素材正文存下来。
     */
    private boolean downloadToFile(String url, File dst) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                JLog.w("MainActivity.downloadToFile", "HTTP " + code + " · " + url);
                return false;
            }
            if (c.getContentLengthLong() > MAX_PACK_BYTES) {
                JLog.w("MainActivity.downloadToFile", "文件过大，拒绝：" + url);
                return false;
            }
            try (InputStream in = c.getInputStream();
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_PACK_BYTES) {
                        JLog.w("MainActivity.downloadToFile", "超出大小上限，中断：" + url);
                        return false;
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
                return total > 0;
            }
        } catch (Throwable t) {
            JLog.w("MainActivity.downloadToFile", t);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 清单里的 baseUrl 由远端决定，必须收窄：只认 https 且主机在白名单内。
     * 否则一份被改过的清单就能把后续所有素材下载指向任意地址。
     */
    private static String safePackBase(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return null;
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
            for (String allow : PACK_HOSTS) {
                if (host.equals(allow) || host.endsWith("." + allow)) return url;
            }
        } catch (Throwable t) {
            JLog.w("MainActivity.safePackBase", t);
        }
        JLog.w("MainActivity.safePackBase", "非白名单地址已忽略: " + url);
        return null;
    }

    /** 关闭素材包：删掉下载目录，只留 APK 内置的那点内容 */
    private void deletePack() {
        deleteRecursive(packDir());
        prefs.edit().putBoolean(PREF_PACK, false).apply();
    }

    // ================= 素材目录热更新 =================

    /**
     * 联网拉一遍远程素材清单（listening.json / reading.json）。
     * 版本号比本地（APK 内置 / 已下载）高就落进下载目录，列表随即刷新——
     * 以后往仓库里加新素材，不用发新版本 App 也能看到。
     * 一小时最多拉一次；没网或版本没变就静默跳过。
     */
    private void refreshManifests() {
        if (prefs == null) return;     // 防御：还没初始化就调用也不崩
        if (packBusy) return;
        if (packBase == null || packBase.isEmpty()) return;     // 还没读过本地清单
        long last = prefs.getLong("manifest_refresh_ts", 0);
        if (System.currentTimeMillis() - last < 3600_000L) return;
        if (packPool.isShutdown()) return;
        packPool.execute(() -> {
            boolean changed = false;
            boolean anyOk = false;   // 至少一份清单真的拿到了才记时间戳，断网别把一小时额度耗掉
            for (String kind : new String[]{"listening", "reading"}) {
                String json = fetchText(packBase + "/" + kind + ".json", 512 * 1024);
                if (json == null) continue;
                try {
                    int remoteV = new JSONObject(json).optInt("version", 0);
                    anyOk = true;
                    // 本地基线：下载目录里那份优先，没有就用 APK 内置的
                    int localV = 0;
                    File cur = new File(packDir(), kind + "/manifest.json");
                    if (cur.exists() && cur.length() > 0) {
                        try {
                            localV = new JSONObject(readManifest(kind)).optInt("version", 0);
                        } catch (Throwable ignored) { JLog.w("MainActivity.refreshManifests", ignored);
                         }
                    }
                    if (localV == 0) {
                        try {
                            localV = new JSONObject(readAsset(kind + "/manifest.json")).optInt("version", 0);
                        } catch (Throwable ignored) { JLog.w("MainActivity.refreshManifests", ignored);
                         }
                    }
                    if (remoteV > localV && remoteV > 0) {
                        File dir = new File(packDir(), kind);
                        if (!dir.exists()) dir.mkdirs();
                        File out = new File(dir, "manifest.json");
                        File part = new File(dir, "manifest.json.part");
                        // 先写 .part 再 rename：就地覆盖时进程被杀会留下截断的清单，
                        // 之后每次读它都只报"素材还没准备好"，而且看不出为什么
                        try (java.io.OutputStream os = new java.io.FileOutputStream(part)) {
                            os.write(json.getBytes("UTF-8"));
                        }
                        if (part.renameTo(out)) changed = true;
                        else {
                            part.delete();
                            JLog.w("MainActivity.refreshManifests", "清单落盘失败: " + kind);
                        }
                    }
                } catch (Throwable t) {
                    JLog.w("MainActivity.refreshManifests", t);
                }
            }
            if (anyOk) prefs.edit().putLong("manifest_refresh_ts", System.currentTimeMillis()).apply();
            if (changed) {
                runOnUiThread(() -> {
                    loadReadingManifest();
                    loadListenManifest();
                    // 正在看详情就不重画列表，退出详情时自然会刷
                    if (readingId == null) renderReadingList();
                    if (listenId == null) renderListenList();
                    Toast.makeText(this, "素材目录已更新，有新内容可以看了", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 取一个小文本（清单这类几百 KB 的东西）；非 2xx、超限、异常一律返回 null */
    private String fetchText(String url, int maxBytes) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                JLog.w("MainActivity.fetchText", "HTTP " + code + " · " + url);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        JLog.w("MainActivity.fetchText", "超出大小上限: " + url);
                        return null;
                    }
                    bos.write(buf, 0, n);
                }
                return total > 0 ? bos.toString("UTF-8") : null;
            }
        } catch (Throwable t) {
            JLog.w("MainActivity.fetchText", t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 清单里还没下载的素材条数（用于设置页提示） */
    private int countMissingMaterial() {
        int n = 0;
        if (readingItems != null) {
            for (ReadingItem it : readingItems) {
                if (!it.builtin && !materialReady("reading", it.text)) n++;
            }
        }
        if (listenItems != null) {
            for (ListenItem it : listenItems) {
                if (!it.builtin && !materialReady("listening", it.audio)) n++;
            }
        }
        return n;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /** 素材包现在占多少空间（清单文件 manifest.json 不算占用） */
    private long packSize() {
        return dirSize(packDir(), true);
    }

    private static long dirSize(File f, boolean skipManifest) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) {
            if (skipManifest && "manifest.json".equals(f.getName())) return 0;
            return f.length();
        }
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += dirSize(k, skipManifest);
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
            JSONObject root = new JSONObject(readManifest("listening"));
            String base = safePackBase(root.optString("baseUrl", null));
            if (base != null) packBase = base;
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
        } catch (Throwable ignored) { JLog.w("MainActivity.loadListenManifest", ignored);
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
            } catch (Throwable ignored) { JLog.w("MainActivity.releaseListen", ignored);
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
        updateFabs();                                    // 记背页亮错题集按钮
    }

    private void closeStudy() {
        if (pageStudy == null || pageStudy.getVisibility() != View.VISIBLE) return;
        slideOut(pageStudy, true, () -> applyImmersive(false));   // 上滑退出 → 往上移出
    }

    /**
     * 出一组十题：先复习到期的陌生词（最多 4 个），其余按「六级高频 → 中频 → 低频」补新词。
     * 顺序走 ECDICT 词频（frq 越小越常用），不再随机抽签——
     * 先把 state / system / program 这类高频词啃下来，收益比抽到 any 个生僻词大得多。
     * 选择题和拼写题交替；干扰项取高频段的词，避免一眼看出。
     * 整组在后台线程生成好再一次性渲染。
     */
    private void buildGroup() {
        submitDb("buildGroup", () -> {
            ArrayList<Quiz> list = new ArrayList<>();
            try {
                Random r = new Random();
                int[] bounds = bandBounds();
                // 一次 GROUP BY 拿齐三档进度 + 总数。原来 currentBand 逐档 COUNT，
                // 再加一次当前档 COUNT，一组题要扫 learn 四遍。
                int[] seenBands = studyDb == null ? new int[4] : studyDb.seenSummary();
                int band = currentBand(bounds, seenBands);
                HashSet<String> seen = studyDb == null ? new HashSet<>() : studyDb.seenSet();
                ArrayList<String> words = new ArrayList<>();
                if (studyDb != null) {
                    for (String w : studyDb.dueWords(4)) {
                        if (!words.contains(w)) words.add(w);
                    }
                }
                // 当前档里从"已背到的位置"往后取：已背数当游标，天然跳过学过的词
                int bandStart = bounds[band], bandEnd = bounds[band + 1];
                int done = seenBands[band];
                int offset = bandStart + Math.max(0, Math.min(done, bandEnd - bandStart) - 8);
                ArrayList<String> cand = new ArrayList<>(dict.cet6Page(offset, 80));
                if (cand.size() < 16) cand.addAll(dict.cet6Page(bandStart, 80));
                for (String w : cand) {
                    if (words.size() >= 18) break;
                    if (seen.contains(w) || words.contains(w)) continue;
                    words.add(w);
                }
                ArrayList<String> all = dict.cet6Page(0, 800);     // 干扰项池：最高频的 800 个
                ArrayList<String> fresh = new ArrayList<>();
                HashSet<String> usedMeaning = new HashSet<>();  // 本组已用过的释义（#7）
                for (String w : words) {
                    if (list.size() >= 8) break;          // 10 题里留 2 个位置给短语题（v3.10）
                    DictDb.Entry e = dict.lookup(w);
                    if (e == null || e.translation == null || e.translation.isEmpty()) continue;
                    ArrayList<String> ms = Quiz.meanings(e.translation, 10);
                    if (ms.isEmpty()) continue;          // 释义太碎，这个词不适合出题
                    String meaning = ms.get(0);
                    // 同一组里两个词撞成同一个释义，会让选择题出现重复选项、拼写题答案混淆，
                    // 用户看着就像「同一个单词反复出现」。这里按释义再挡一道。
                    if (!usedMeaning.add(meaning)) continue;
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
                            if (usedMeaning.contains(om) || ds.contains(om)) continue;  // 排除和本组其它题撞释义的干扰项
                            ds.add(om);
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
                    if (!seen.contains(w)) fresh.add(w);   // 本组新词：出题即算背过
                }
                if (studyDb != null) {
                    for (String w : fresh) studyDb.seen(w, band);
                }

                // ===== 追加 2 道短语题（v3.10 词组训练 B）=====
                // 插到第 2、5 位：题型交替着来，不全挤在末尾。
                ArrayList<Quiz> phraseQs = buildPhraseQuestions(r, 2);
                for (int i = phraseQs.size() - 1; i >= 0; i--) {
                    int at = (i == 0) ? Math.min(2, list.size()) : Math.min(5, list.size());
                    list.add(at, phraseQs.get(i));
                }
            } catch (Throwable ignored) { JLog.w("MainActivity.buildGroup", ignored);
             }
            quizGroup = list;
            quizIndex = 0;
            quizRight = 0;
            quizWrongWords = new ArrayList<>();
            studySummaryCounted = false;
            runOnUiThread(this::renderQuiz);
        });
    }

    // ================= 背词进度与计划 =================

    /** 三档边界：高频 [0,n/3) 中频 [n/3,2n/3) 低频 [2n/3,n) */
    private int[] bandBounds() {
        int n = dict == null ? 0 : dict.cet6Count();
        return new int[]{0, n / 3, n * 2 / 3, n};
    }

    private static String bandName(int b) {
        return b == 0 ? "高频" : b == 1 ? "中频" : "低频";
    }

    /** 某档的目标词数（可在面板里调整，默认该档全部） */
    private int bandTarget(int b, int[] bounds) {
        int def = Math.max(0, bounds[b + 1] - bounds[b]);
        int v = prefs.getInt("study_target_" + b, def);
        return Math.max(0, Math.min(v, def));
    }

    /**
     * 现在该背哪一档：高频没背完就高频，依次往后；三档都达标就回高频复习。
     * 进度数字由调用方给（seen = StudyDb.seenSummary()），这里不碰库——
     * 面板要在界面线程上算「当前档」，顺手查库就会把 3 条 COUNT 甩到主线程。
     */
    private int currentBand(int[] bounds, int[] seen) {
        for (int b = 0; b < 3; b++) {
            int target = bandTarget(b, bounds);
            if (target <= 0) continue;
            if (seen[b] < target) return b;
        }
        return 0;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    /** 今天背了几组（每组 10 词） */
    private int studyDoneToday() {
        return today().equals(prefs.getString("study_day", "")) ? prefs.getInt("study_done", 0) : 0;
    }

    /** 一组做完记一次 */
    private void bumpStudyDone() {
        int n = studyDoneToday() + 1;
        prefs.edit().putString("study_day", today()).putInt("study_done", n).apply();
    }

    /** 一条细进度条：底槽 + 按百分比填充 */
    private View studyProgressBar(int percent) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundResource(R.drawable.progress_track);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        track.setLayoutParams(lp);
        barFill(track, percent);
        return track;
    }

    /**
     * 填充进度条。条上已经有填充 View 就只改它的权重，没有才 new 一个——
     * 面板每次刷新都 removeAllViews + new View 的话，三根条一轮点按就能扔几十个 View，
     * 每个 View 都带着自己的 background/measure 缓存，纯属白忙。
     */
    private void barFill(View track, int percent) {
        if (!(track instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) track;
        int p = Math.max(0, Math.min(100, percent));
        View fill = bar.getChildAt(0);
        if (p == 0) {
            if (fill != null) bar.removeAllViews();
            return;
        }
        if (fill == null) {
            fill = new View(this);
            fill.setBackgroundResource(R.drawable.progress_fill);
            bar.addView(fill);
        }
        fill.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, p / 100f));
    }

    /**
     * 面板的进度读取：一条 GROUP BY 出齐三档 + 总数，放到 dbPool 上跑。
     * 读回来只把数字交给面板重画，视图树不动；面板已经关掉的话快照照样留着，
     * 下次打开先拿它画一遍，省掉「统计中…」闪一下。
     */
    private void readStudyStats() {
        submitDb("readStudyStats", () -> {
            final int[] seen = studyDb == null ? new int[4] : studyDb.seenSummary();
            runOnUiThread(() -> {
                studySeenSnapshot = seen;
                Runnable refresh = studyPanelRefresh;
                if (refresh != null && !isFinishing() && !isDestroyed()) refresh.run();
            });
        });
    }

    /** 左下角 FAB：背词进度 + 计划调整 */
    private void showStudyPanel() {
        if (dict == null) {
            Toast.makeText(this, "词典还在加载，稍等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] bounds = bandBounds();       // 分母已缓存，这里不再扫全表
        final int total = bounds[3];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), dp(4));

        box.addView(sectionTitle("背词进度"));
        final TextView summary = new TextView(this);
        summary.setTextSize(15);
        summary.setTextColor(getColor(R.color.text_primary));
        summary.setPadding(0, dp(8), 0, 0);
        box.addView(summary);

        final TextView[] bandRows = new TextView[3];
        final View[] bars = new View[3];
        for (int b = 0; b < 3; b++) {
            TextView row = new TextView(this);
            row.setTextSize(13);
            row.setTextColor(getColor(R.color.text_secondary));
            row.setPadding(0, dp(10), 0, 0);
            bandRows[b] = row;
            box.addView(row);
            View bar = studyProgressBar(0);
            bars[b] = bar;
            box.addView(bar);
        }

        box.addView(sectionTitle("背词计划"));
        final int[] dailyOpts = {1, 2, 3, 5};
        final String[] dailyLabels = {"每天 1 组（10 词）", "每天 2 组（20 词）",
                "每天 3 组（30 词）", "每天 5 组（50 词）"};
        final TextView[] dailyRows = new TextView[4];
        final TextView[] targetVals = new TextView[3];

        /*
         * 面板重画：数字全部来自 studySeenSnapshot（后台读回来的进度快照），
         * 这里一条查询都不发。
         *
         * 原来每点一次 −／＋ 都会 dismiss 旧面板 + 新造一棵（ScrollView、十几号
         * TextView、三根进度条，外加它们各自的 LayoutParams），一次好几十对象白扔，
         * 旧的还得等 GC；顺便每次都在界面线程上跑四条 COUNT。
         * 现在视图只建一次，按钮改完 prefs 就重画一遍，库里那份进度不点「重新开始背」根本不会变。
         */
        final Runnable refresh = () -> {
            int[] seen = studySeenSnapshot;
            int daily = Math.max(1, prefs.getInt("study_daily_groups", 3));
            if (seen == null) {
                summary.setText("统计中…");
                for (int b = 0; b < 3; b++) {
                    bandRows[b].setText("");
                    targetVals[b].setText("");
                    barFill(bars[b], 0);
                }
            } else {
                summary.setText("已背 " + seen[3] + " / " + total + " 词"
                        + "　·　今天 " + studyDoneToday() + " / " + daily + " 组");
                int cur = currentBand(bounds, seen);
                for (int b = 0; b < 3; b++) {
                    int size = bounds[b + 1] - bounds[b];
                    int d = seen[b];
                    int target = bandTarget(b, bounds);
                    String mark = b == cur ? "　← 当前" : (target == 0 ? "　（跳过）" : "");
                    bandRows[b].setText(bandName(b) + "频　已背 " + d + " / " + target
                            + "　（本档 " + size + " 词）" + mark);
                    bandRows[b].setTextColor(getColor(b == cur ? R.color.accent : R.color.text_secondary));
                    // 进度条按本档目标算
                    barFill(bars[b], target <= 0 ? 0 : Math.min(100, d * 100 / target));
                    targetVals[b].setText(String.valueOf(target));
                }
            }
            int idx = 0;
            for (int i = 0; i < dailyOpts.length; i++) if (dailyOpts[i] == daily) idx = i;
            refreshOptions(dailyRows, dailyLabels, idx);
        };

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            dailyRows[i] = optionRow(dailyLabels[i], () -> {
                prefs.edit().putInt("study_daily_groups", dailyOpts[idx]).apply();
                refresh.run();            // 数字就在面板上，改完重画即可
            });
            box.addView(dailyRows[i]);
        }

        // 三档目标词数：− 数字 ＋
        for (int b = 0; b < 3; b++) {
            final int band = b;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(6), 0, dp(6));

            TextView name = new TextView(this);
            name.setText(bandName(b) + "频目标");
            name.setTextSize(15);
            name.setTextColor(getColor(R.color.text_primary));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nlp);
            line.addView(name);

            TextView minus = miniButton("−");
            minus.setOnClickListener(v -> {
                int cur = bandTarget(band, bounds);
                int step = Math.max(50, (bounds[band + 1] - bounds[band]) / 20);
                prefs.edit().putInt("study_target_" + band,
                        Math.max(0, cur - step)).apply();
                refresh.run();
            });
            line.addView(minus);

            TextView val = new TextView(this);
            val.setTextSize(15);
            val.setTextColor(getColor(R.color.text_primary));
            val.setGravity(Gravity.CENTER);
            val.setMinWidth(dp(64));
            targetVals[b] = val;
            line.addView(val);

            TextView plus = miniButton("＋");
            plus.setOnClickListener(v -> {
                int cur = bandTarget(band, bounds);
                int max = bounds[band + 1] - bounds[band];
                int step = Math.max(50, max / 20);
                prefs.edit().putInt("study_target_" + band,
                        Math.min(max, cur + step)).apply();
                refresh.run();
            });
            line.addView(plus);

            box.addView(line);
        }

        TextView note = new TextView(this);
        note.setText("词表按六级词频三等分：先高频、再中频、最后低频。"
                + "目标设为 0 表示跳过该档；改动下一组立即生效。");
        note.setTextSize(11);
        note.setTextColor(getColor(R.color.text_secondary));
        note.setPadding(0, dp(10), 0, dp(4));
        box.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        studyPanelDialog = new AlertDialog.Builder(this)
                .setTitle("背单词")
                .setView(sv)
                .setNegativeButton("重新开始背（清除进度）", (d, w) -> {
                    // 全表 DELETE 可能上百毫秒，别在界面线程上等它。
                    // 清完要重读一遍进度，快照留着旧数字的话面板会一直显示清零前。
                    queueStudyWrite(new StudyWrite(null, StudyWrite.RESET, this::readStudyStats));
                    Toast.makeText(this, "背词进度已清零，收藏和错题不受影响",
                            Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("关闭", null)
                .create();
        // 刷新入口只在面板开着的时候交出去：它捕获了整棵面板视图树，
        // 弹窗一关不撒手的话，三十来个 View 就得跟着 Activity 活到下次开面板。
        studyPanelRefresh = refresh;
        studyPanelDialog.setOnDismissListener(d -> {
            if (studyPanelRefresh == refresh) studyPanelRefresh = null;
        });
        refresh.run();          // 先拿上次的快照画一遍，面板不至于空着等查询
        studyPanelDialog.show();
        readStudyStats();       // 再后台读最新的进度，回来重画数字
    }

    /** 面板里的小圆角按钮（− ＋） */
    private TextView miniButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(getColor(R.color.text_primary));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(40));
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setBackgroundResource(R.drawable.btn_soft);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    // ================= 无尽配对游戏（v3.6）=================

    /** 小游戏只出已经背过的词；太少铺不满一屏，先劝他去背两组 */
    /**
     * 进小游戏。词源是整本词典（v3.8），不再要求先背过多少词：
     * 出词顺序 六级高频 → 中频 → 低频 → 词典其他常用词，每个词只出一次；
     * 错题集和单词本的词不排队，优先插进来。
     */
    private void openGame() {
        if (dict == null) {
            Toast.makeText(this, "词典还在加载，稍等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        // 进游戏前把查词框的焦点交出去、键盘收掉（#6）：
        // 不这么做的话，从查词页直接点游戏按钮时 searchBox 还留着焦点，
        // 之后从桌面切回前台（尤其小屏机 adjustResize）系统会自动把输入法弹出来。
        hideKeyboard();
        // 进游戏前把音效开关状态同步给 gameView（放在 openGame 而不是 onCreate，
        // 因为 onCreate 里 prefs 还没初始化完，早读会 NPE）
        if (gameView != null && prefs != null) {
            gameView.setSfxEnabled(prefs.getBoolean("game_sfx", true));
            gameView.setVibeEnabled(prefs.getBoolean("game_vibe", true));
        }
        applyImmersive(true);
        slideIn(pageGame, false);
        updateFabs();
        gameScore.setText("准备中…");
        submitDb("gamePrepare", () -> {
            final GameWords src = new GameWords(dict, studyDb);
            src.prepare();                       // 读个人词 + 算分档边界，可能要几百毫秒
            runOnUiThread(() -> {
                if (!isPageVisible(pageGame)) return;      // 准备期间已经退出去了
                gameWords = src;
                gameView.setBest(prefs == null ? 0 : prefs.getInt("game_best", 0));
                gameView.setSource(src);
                refreshGameTip();
            });
        });
    }

    /** 顶部小字：有错词压着就报个数字，让"存档"看得见 */
    private void refreshGameTip() {
        if (gameTip == null) return;
        int n = gameWords == null ? 0 : gameWords.pendingWrongCount();
        gameTip.setText(n > 0
                ? "点英文，再点它对应的中文　·　待回炉 " + n + " 个"
                : "点英文，再点它对应的中文；整屏消干净自动换一批新词");
    }

    /** 六级高频 + 中频都刷过一遍：让玩家决定重来还是往后走 */
    private void showGameMilestone() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("六级核心词刷完了")
                .setMessage("六级高频和中频的词都出现过一遍了。\n\n"
                        + "继续：往下练低频词和词典里的其他常用词\n"
                        + "重新开始：回到六级高频，把这一轮从头再刷（分数清零）")
                .setNegativeButton("继续往后", null)
                .setPositiveButton("重新开始", (d, w) -> {
                    if (gameWords != null && gameView != null) {
                        gameWords.reset();
                        gameView.setSource(gameWords);
                    }
                })
                .show();
    }

    private void closeGame() {
        if (pageGame == null || pageGame.getVisibility() != View.VISIBLE) return;
        if (gameView != null) {
            gameView.persistProgress();      // 先存档再关，下次进来接着玩
            gameView.stop();
        }
        slideOut(pageGame, false, () -> {
            applyImmersive(false);
            updateFabs();
        });
    }

    /** 取释义首行第一个合格义项；实在没有就退回原始首行 */
    private static String firstMeaning(String translation) {
        ArrayList<String> ms = Quiz.meanings(translation, 10);
        if (!ms.isEmpty()) return ms.get(0);
        return firstLine(translation).trim();
    }

    /** 题干提示语：不同题型说不同的话（v3.10 加了短语题） */
    private static String quizHeadText(Quiz q) {
        if (q.type == Quiz.TYPE_MEANING) return "选出正确的释义";
        if (q.type == Quiz.TYPE_PHRASE) {
            return q.phraseAskChinese ? "这个意思的常用搭配是？" : "这个搭配是什么意思？";
        }
        return "拼出这个单词";
    }

    /** 题干主体：选择题给英文，拼写题给中文，短语题看方向 */
    private static String quizTitleText(Quiz q) {
        if (q.type == Quiz.TYPE_MEANING) return q.word;
        if (q.type == Quiz.TYPE_PHRASE) {
            return q.phraseAskChinese ? q.meaning : q.word;
        }
        return q.meaning;
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
        head.setText(quizHeadText(q));
        head.setTextColor(getColor(R.color.text_secondary));
        head.setTextSize(13);
        studyBody.addView(head);

        TextView title = new TextView(this);
        title.setText(quizTitleText(q));
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(6));
        studyBody.addView(title);

        // 按"有没有选项"分支，而不是按题型：TYPE_PHRASE 也带 options，
        // 只有 Quiz.spell() 造出来的题 options 为 null。用 type 判会漏掉短语题。
        if (q.options != null && !q.options.isEmpty()) {
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

            // 光标自动落到这条输入框：老写法只把 EditText 加进布局，焦点还在原来的控件上，
            // 用户必须先去点一下那条空行才能打字——键盘明明常驻也没用。
            // post 是必需的：视图刚 addView 还没走完 measure/layout，
            // 这时 showSoftInput 一定失败（也是这条路径以前弹不出键盘的原因）。
            input.setFocusableInTouchMode(true);
            if (isPageVisible(pageStudy)) {
                input.requestFocus();
                studyBody.post(() -> {
                    if (isPageVisible(pageStudy)) showKeyboard(input);
                });
            }
        }

        TextView forgot = new TextView(this);
        forgot.setText("不认识，直接标记为陌生词");
        forgot.setTextColor(getColor(R.color.text_secondary));
        forgot.setTextSize(13);
        forgot.setPadding(dp(4), dp(26), dp(4), dp(10));
        forgot.setOnClickListener(v -> {
            // "不认识"等同于答错：既进复习，也计入错题集
            recordAnswer(q.word, false);
            if (!quizWrongWords.contains(q.word)) quizWrongWords.add(q.word);
            Toast.makeText(this, "已加入复习和错题集", Toast.LENGTH_SHORT).show();
            nextQuiz();
        });
        studyBody.addView(forgot);
    }

    /**
     * 出短语题（v3.10 词组训练 B）。
     *
     * 一半「给中文选英文短语」、一半「给英文短语选中文」，两种方向轮着来。
     * 干扰项从搭配库里再取，长度和结构要接近才迷惑——
     * 拿 "give up" 去和 "read book" 放一起，一眼就能排除，等于送分。
     */
    private ArrayList<Quiz> buildPhraseQuestions(Random r, int want) {
        ArrayList<Quiz> out = new ArrayList<>();
        if (want <= 0 || dict == null) return out;
        ArrayList<DictDb.Phrase> pool;
        try {
            pool = dict.phrasePool(60);
        } catch (Throwable t) {
            return out;
        }
        if (pool.size() < 4) return out;

        // 答案只从"常用搭配"里挑：score 太低的是生僻搭配，当答案用户根本不认识，
        // 当干扰项才合适。（core 小品词类 score 在 170+，普通介词类 150+）
        final int ANSWER_MIN_SCORE = 150;

        ArrayList<DictDb.Phrase> used = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            DictDb.Phrase target = null;
            for (DictDb.Phrase p : pool) {
                if (p.score < ANSWER_MIN_SCORE) continue;
                if (used.contains(p)) continue;
                target = p;
                break;
            }
            if (target == null) break;
            used.add(target);

            boolean askChinese = (i % 2 == 0);
            // 干扰项：同一方向（askChinese 决定比英文短语还是比中文释义）
            ArrayList<String> ds = new ArrayList<>();
            for (int k = 0; k < 80 && ds.size() < 3; k++) {
                DictDb.Phrase cand = pool.get(r.nextInt(pool.size()));
                if (cand == null || used.contains(cand)) continue;
                String text = askChinese ? cand.phrase : cand.zh;
                if (text == null || text.isEmpty()) continue;
                if (askChinese) {
                    if (!text.contains(" ")) continue;                 // 必须是短语，不能是单词
                    if (!phraseSimilar(target.phrase, text)) continue; // 长度差太多一眼排除
                } else {
                    if (!phraseZhSimilar(target.zh, text)) continue;
                }
                if (!ds.contains(text)) ds.add(text);
            }
            if (ds.size() < 2) continue;      // 凑不够就不出这道题，宁缺毋滥
            Quiz q = Quiz.phrase(target.phrase, target.zh, ds, askChinese, r);
            if (q != null) out.add(q);
        }
        return out;
    }

    /** 两个英文短语像不像：单词数相同、字符数差不超过 5 */
    private static boolean phraseSimilar(String a, String b) {
        if (a == null || b == null) return false;
        if (a.split(" ").length != b.split(" ").length) return false;
        return Math.abs(a.length() - b.length()) <= 5;
    }

    /** 两个中文释义像不像：字数差不超过 3 */
    private static boolean phraseZhSimilar(String a, String b) {
        if (a == null || b == null) return false;
        return Math.abs(a.length() - b.length()) <= 3;
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

    /**
     * 答题落库挪到 dbPool。
     * 原先每点一个选项都在主线程同步开一个写事务（SELECT box → UPDATE → 提交），
     * 是出题流程里唯一卡在指尖下的磁盘 IO，低端机上就是"点下去卡顿一下"。
     *
     * 顺序是安全的：dbPool 单线程 FIFO，下一题触发的 buildGroup 一定排在这次落库之后，
     * 读到的必然是新状态。唯一的坑是 onDestroy 用 shutdownNow() 会把还没跑到的任务丢掉
     * = 丢进度，所以留一份待写清单，销毁前补做完。
     */
    private void recordAnswer(String word, boolean ok) {
        queueStudyWrite(new StudyWrite(word, ok ? StudyWrite.CORRECT : StudyWrite.WRONG));
    }

    /**
     * 「其实没记住」也走同一条队列：它必须排在本次 correct() 之后，
     * 否则两步会互相抵消（升格和退格的先后一旦乱掉，词就还留在高格子里）。
     */
    private void recordRelearn(String word) {
        queueStudyWrite(new StudyWrite(word, StudyWrite.RELEARN));
    }

    private void queueStudyWrite(final StudyWrite job) {
        if (studyDb == null) return;
        // RESET 不针对某个词，其余种类没词就没意义（别往表里写空行）
        if (job.word == null && job.kind != StudyWrite.RESET) return;
        synchronized (pendingStudyWrites) { pendingStudyWrites.add(job); }
        submitDb("queueStudyWrite", () -> {
            try {
                // 动手之前先从待写清单里把这条认领回来。认领不到就说明
                // flushPendingStudyWrites()（退出补做）已经替它落过库了，
                // 再跑一次等于同一个答案记两遍、step() 的读改写互相覆盖。
                if (claimStudyWrite(job)) applyStudyWrite(job);
            } finally {
                // 列表要在数据真正落库之后再刷，否则读到的还是旧内容
                if (job.after != null) runOnUiThread(job.after);
            }
        });
    }

    /** 从待写清单里认领一条。返回 false = 退出时已经补做过了，这活儿不用再干 */
    private boolean claimStudyWrite(StudyWrite job) {
        synchronized (pendingStudyWrites) {
            return pendingStudyWrites.remove(job);
        }
    }

    private void applyStudyWrite(StudyWrite j) {
        switch (j.kind) {
            case StudyWrite.CORRECT: studyDb.correct(j.word); break;
            case StudyWrite.WRONG:   studyDb.wrong(j.word); break;
            case StudyWrite.FAV:     studyDb.favorite(j.word); break;
            case StudyWrite.UNFAV:   studyDb.unfavorite(j.word); break;
            case StudyWrite.RESET:   studyDb.resetProgress(); break;
            default:                 studyDb.relearn(j.word); break;
        }
    }

    /** 退出前把还排在队列里的落库补做完，别丢进度 */
    private void flushPendingStudyWrites() {
        ArrayList<StudyWrite> left;
        synchronized (pendingStudyWrites) {
            left = new ArrayList<>(pendingStudyWrites);
            pendingStudyWrites.clear();
        }
        for (StudyWrite j : left) {
            try {
                applyStudyWrite(j);
            } catch (Throwable t) {
                JLog.w("MainActivity.flushPendingStudyWrites", t);
            }
        }
    }

    /**
     * 往 dbPool 提交工作的唯一入口（别再用裸 dbPool.execute）。
     *
     * onDestroy 是 shutdownNow() 收摊，之后任何一次迟到的点击、看门狗、后台回调再提交，
     * 抛的都是主线程上的 RejectedExecutionException = 直接闪退。兜住它，
     * 「这活儿不干了」交回调用方处理（识别用的位图就靠这一步回收）。
     *
     * 顺手把 body 里的异常也接住：库被关掉之后跑到的读任务会抛
     * "database has been closed"，那同样是不该炸给用户看的。
     */
    private void submitDb(String tag, Runnable body) {
        submitDb(tag, body, null);
    }

    private void submitDb(String tag, Runnable body, Runnable onRejected) {
        if (isFinishing() || isDestroyed()) {
            if (onRejected != null) onRejected.run();
            return;
        }
        try {
            dbPool.execute(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    JLog.w("MainActivity." + tag, t);
                }
            });
        } catch (Throwable t) {
            JLog.w("MainActivity." + tag + " 提交失败（线程池已收摊）", t);
            if (onRejected != null) onRejected.run();
        }
    }

    private void answer(Quiz q, boolean ok) {
        if (ok) {
            quizRight++;
            recordAnswer(q.word, true);
        } else {
            if (!quizWrongWords.contains(q.word)) quizWrongWords.add(q.word);
            recordAnswer(q.word, false);   // 答错自动进复习
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
                // 必须用 relearn 不能用 mark：上面 correct() 已把这个词升了一格、
                // due 推到十几天后，而 mark() 是 INSERT OR IGNORE，对已存在的词是空操作，
                // 点了按钮等于什么都没发生。
                recordRelearn(q.word);
                Toast.makeText(this, "已退回第一格，下组再来", Toast.LENGTH_SHORT).show();
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
        if (!studySummaryCounted) {          // 一组只计一次，防止旋屏/重绘重复累加
            studySummaryCounted = true;
            bumpStudyDone();
        }

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

    // ================= 单词本 / 错题集（v3.0）=================

    /** 右下角圆形按钮显隐：单词本按钮只在查词页、错题集按钮只在记背页，且不压在已打开的新页上 */
    private void updateFabs() {
        if (fabWordBook == null || fabWrongBook == null) return;
        boolean bookOpen = isPageVisible(pageWordBook) || isPageVisible(pageWrongBook);
        boolean anyImmersive = bookOpen || isPageVisible(pageStudy)
                || isPageVisible(pageReading) || isPageVisible(pageCrop)
                || isPageVisible(pageGame);
        boolean onDict = swipeHost != null && swipeHost.getPage() == PAGE_DICT;
        boolean onStudy = isPageVisible(pageStudy);
        fabWordBook.setVisibility(onDict && !anyImmersive ? View.VISIBLE : View.GONE);
        if (fabGame != null) {
            fabGame.setVisibility(onDict && !anyImmersive ? View.VISIBLE : View.GONE);
        }
        fabWrongBook.setVisibility(onStudy && !bookOpen ? View.VISIBLE : View.GONE);
        if (fabStudyStats != null) {
            fabStudyStats.setVisibility(onStudy && !bookOpen ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isPageVisible(View p) {
        return p != null && p.getVisibility() == View.VISIBLE;
    }

    private void openWordBook() {
        applyImmersive(true);
        slideIn(pageWordBook, false);            // 从底部滑入
        if (wbHint != null) wbHint.showHint();
        renderWordBook();
        updateFabs();
    }

    private void closeWordBook() {
        if (pageWordBook == null || pageWordBook.getVisibility() != View.VISIBLE) return;
        slideOut(pageWordBook, false, () -> {    // 下滑退出
            applyImmersive(false);               // 回到查词页，恢复顶栏/标签
            updateFabs();
        });
    }

    private void openWrongBook() {
        // 从记背页进，记背页本身已是沉浸态，保持沉浸（不再 applyImmersive）
        slideIn(pageWrongBook, false);
        if (wrongHint != null) wrongHint.showHint();
        renderWrongBook();
        updateFabs();
    }

    private void closeWrongBook() {
        if (pageWrongBook == null || pageWrongBook.getVisibility() != View.VISIBLE) return;
        slideOut(pageWrongBook, false, () -> updateFabs());   // 下滑退出，回到记背页（保持沉浸）
    }

    private void renderWordBook() {
        // 词表本身也要到后台线程去取：收藏几百个词时这条 SELECT 不是零成本
        renderBook(wbBody, wbCount,
                () -> studyDb == null ? new ArrayList<String>() : studyDb.favoriteWords(),
                "单词本", true);
    }

    private void renderWrongBook() {
        renderBook(wrongBody, wrongCount,
                () -> studyDb == null ? new ArrayList<String>() : studyDb.wrongWords(),
                "错题集", false);
    }

    /** 通用渲染：词表 + 释义都在后台取，回来一次性上屏；点开看详情，收藏本可取消收藏 */
    private void renderBook(final LinearLayout body, final TextView count,
                            final Callable<ArrayList<String>> fetch,
                            final String title, final boolean canUnfav) {
        if (body == null) return;
        body.removeAllViews();
        if (count != null) count.setText("");
        TextView loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextColor(getColor(R.color.text_secondary));
        loading.setTextSize(15);
        body.addView(loading);
        submitDb("renderBook", () -> {
            final ArrayList<String> words;
            ArrayList<String> got;
            try {
                got = fetch.call();
            } catch (Throwable t) {
                JLog.w("MainActivity.renderBook 取词表失败", t);
                got = new ArrayList<>();
            }
            words = got;
            final ArrayList<DictDb.Entry> entries = new ArrayList<>();
            for (String w : words) {
                DictDb.Entry e = null;
                try {
                    e = dict.lookup(w);
                    // 错题集里的词组在 stardict 没有词条，释义只存在搭配表里；
                    // 不兜这一下，列表里就是一排"（未收录）"。
                    if (e == null) e = dict.lookupPhrase(w);
                } catch (Throwable ignore) { JLog.w("MainActivity.renderBook", ignore); }
                entries.add(e);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                body.removeAllViews();
                if (count != null) count.setText(words.isEmpty() ? "" : words.size() + " 个");
                if (words.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText("单词本".equals(title)
                            ? "还没有收藏的词。查词时点词条上的星标即可收进这里。"
                            : "还没有错题。记背答题答错的词会自动收进这里。");
                    empty.setTextColor(getColor(R.color.text_secondary));
                    empty.setTextSize(15);
                    empty.setPadding(dp(4), dp(24), dp(4), dp(4));
                    body.addView(empty);
                    return;
                }
                for (int i = 0; i < words.size(); i++) {
                    body.addView(buildBookRow(words.get(i), entries.get(i), title, canUnfav));
                }
            });
        });
    }

    private View buildBookRow(final String word, DictDb.Entry e,
                              final String title, final boolean canUnfav) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        card.setPadding(pad, dp(14), pad, dp(14));
        card.setBackgroundResource(R.drawable.btn_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);

        TextView w = new TextView(this);
        w.setText(word);
        w.setTextColor(getColor(R.color.text_primary));
        w.setTextSize(18);
        w.setTypeface(null, Typeface.BOLD);
        card.addView(w);

        TextView m = new TextView(this);
        m.setText(e != null ? firstMeaning(e.translation) : "（未收录）");
        m.setTextColor(getColor(R.color.text_secondary));
        m.setTextSize(14);
        m.setPadding(0, dp(4), 0, 0);
        card.addView(m);

        card.setOnClickListener(v -> showBookDetail(word, e, title, canUnfav));
        return card;
    }

    private void showBookDetail(final String word, DictDb.Entry e,
                                final String title, final boolean canUnfav) {
        String body = (e == null || e.translation == null || e.translation.isEmpty())
                ? "词典里没找到这个词" : e.translation.trim();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(word)
                .setMessage(body)
                .setNegativeButton("关闭", null);
        if (e != null) b.setNeutralButton("朗读", (d, w) -> onSpeakerClick(word));
        if (canUnfav) {
            b.setPositiveButton("取消收藏", (d, w) -> {
                // 写库挪到 dbPool，落库完成后再刷新列表
                queueStudyWrite(new StudyWrite(word, StudyWrite.UNFAV, this::renderWordBook));
                Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }

    /** 词条卡上的收藏星标：点亮=已收进单词本，点一下切换 */
    private View buildFavorite(final String word) {
        final ImageView star = new ImageView(this);
        int pad = dp(8);
        star.setPadding(pad, pad, pad, pad);
        // 这里不能同步查 isFavorite()：它在列表行的 getView() 里，每滑出一行就是
        // 一次界面线程 I/O。先按未收藏画，后台查完再点亮。
        star.setImageResource(R.drawable.ic_fav_border);
        final boolean[] marked = {false};
        final boolean[] touched = {false};   // 用户点过就以用户为准，别让迟到的查询结果盖回去
        star.setOnClickListener(v -> {
            if (studyDb == null) return;
            touched[0] = true;
            marked[0] = !marked[0];
            star.setImageResource(marked[0] ? R.drawable.ic_fav : R.drawable.ic_fav_border);
            Toast.makeText(this, marked[0] ? "已收进单词本" : "已取消收藏",
                    Toast.LENGTH_SHORT).show();
            queueStudyWrite(new StudyWrite(word,
                    marked[0] ? StudyWrite.FAV : StudyWrite.UNFAV, null));
        });
        if (studyDb != null) {
            submitDb("buildFavorite", () -> {
                final boolean fav;
                try {
                    fav = studyDb.isFavorite(word);
                } catch (Throwable t) {
                    JLog.w("MainActivity.buildFavorite", t);
                    return;
                }
                runOnUiThread(() -> {
                    if (touched[0]) return;
                    marked[0] = fav;
                    star.setImageResource(fav ? R.drawable.ic_fav : R.drawable.ic_fav_border);
                });
            });
        }
        return star;
    }

    @Override
    public void onBackPressed() {
        if (pageGame != null && pageGame.getVisibility() == View.VISIBLE) {
            closeGame();
            return;
        }
        if (pageWrongBook != null && pageWrongBook.getVisibility() == View.VISIBLE) {
            closeWrongBook();      // 错题集盖在记背页上，先退它，回到记背页
            return;
        }
        if (pageWordBook != null && pageWordBook.getVisibility() == View.VISIBLE) {
            closeWordBook();
            return;
        }
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
                submitDb("saveSkin", () -> {
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
        submitDb("loadImage", () -> {
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
        if (isFinishing() || isDestroyed()) {
            // 后台解完图才切回界面，中间用户已经退出了：onDestroy 的 releasePendingOcr()
            // 早跑完了，这张图挂上去就没人收，当场放手。
            recycleOcrPair(bmp, null);
            return;
        }
        releasePendingOcr();        // 上一张还没放手的话先收掉，不然连着两次选图就是 24MB
        pendingOcrBmp = bmp;
        if (cropView != null) cropView.setBitmap(bmp);
        if (pageCrop != null) pageCrop.setVisibility(View.VISIBLE);
        ocrText.setText("");
    }

    private void closeCrop() {
        if (pageCrop != null) pageCrop.setVisibility(View.GONE);
        releasePendingOcr();
    }

    /**
     * 把待识别的图交给调用方，同时摘掉字段——此后谁拿走谁回收。
     * 拿走之后调 closeCrop() 不会重复回收（字段已经空了）。
     */
    private Bitmap takePendingOcr() {
        Bitmap b = pendingOcrBmp;
        pendingOcrBmp = null;
        if (cropView != null) cropView.setBitmap(null);
        return b;
    }

    /** 框选页一收就把图还给系统：最长边 2000px 的相机图是 12MB，等 GC 不划算 */
    private void releasePendingOcr() {
        Bitmap b = takePendingOcr();
        if (b != null && !b.isRecycled()) b.recycle();
    }

    /** 读取图片并缩到最长边约 2000px，防低配手机内存爆炸；顺带按 EXIF 摆正方向 */
    private Bitmap loadBitmap(Uri uri) throws Exception {
        int orientation = 0;
        try (InputStream meta = getContentResolver().openInputStream(uri)) {
            orientation = exifOrientation(meta);
        } catch (Throwable ignored) { JLog.w("MainActivity.loadBitmap", ignored);
         }
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        try (InputStream s = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(s, null, probe);
        }
        int sample = 1;
        while (probe.outWidth / sample > 2000 || probe.outHeight / sample > 2000) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try (InputStream s2 = getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(s2, null, opts);
        }
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
            // createBitmap 会新建一张；原图不回收就在后台线程里堆出两份全屏位图
            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (rotated != b) b.recycle();
            b = rotated;
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

    /**
     * 识别一张图。v3.3 起两件事变了：
     * 1) 小图先放大——框选一小块菜单时字高常常只有十几像素，ML Kit 要求 ≥32px 才稳；
     * 2) 拉丁模型和中文模型同时跑，按置信度择优——中文模型认英文天生比拉丁模型差一截。
     *
     * bmp 的所属权归这个方法：图是调用方交出来的（takePendingOcr / 裁剪），
     * 两个模型都跑完就当场 recycle，不留给 GC 挑时候。放大版是新图，两张都要收。
     */
    private void runOcr(Bitmap bmp) {
        // 提交不进去（退出时线程池已收摊）就把图收掉：这张图的所属权在我手上，
        // onDestroy 的 releasePendingOcr() 已经拿不到它了，不收就是 12~24MB 陪着进程走完。
        submitDb("runOcr", () -> {
            final Bitmap img;
            try {
                img = upscaleForOcr(bmp);
            } catch (Throwable t) {
                recycleOcrPair(bmp, null);
                runOnUiThread(() -> ocrText.setText("图片预处理失败：" + t.getMessage()));
                return;
            }
            final List<com.google.android.gms.tasks.Task<Text>> tasks = new ArrayList<>();
            final List<String> tags = new ArrayList<>();
            try {
                tasks.add(getLatinRecognizer().process(InputImage.fromBitmap(img, 0)));
                tags.add("英文模型");
            } catch (Throwable ignored) { JLog.w("MainActivity.runOcr", ignored);
             }
            try {
                tasks.add(getRecognizer().process(InputImage.fromBitmap(img, 0)));
                tags.add("中文模型");
            } catch (Throwable ignored) { JLog.w("MainActivity.runOcr", ignored);
             }
            if (tasks.isEmpty()) {
                recycleOcrPair(bmp, img);
                runOnUiThread(() -> ocrText.setText("识别服务未就绪，请重启 App 再试"));
                return;
            }
            // 完成回调也走同一道门：直接写 dbPool 的话，线程池收摊那一刻
            // addOnCompleteListener 会把提交失败抛回调用线程，图就再没人收了。
            java.util.concurrent.Executor ocrDone = body ->
                    submitDb("ocrDone", body, () -> recycleOcrPair(bmp, img));
            com.google.android.gms.tasks.Tasks.whenAllComplete(tasks)
                    .addOnCompleteListener(ocrDone, task -> {
                        // 两个模型都到此为止，图没人再引用了，先放手再算结果
                        recycleOcrPair(bmp, img);
                        Text best = null;
                        String which = "";
                        double bestScore = -1;
                        for (int i = 0; i < tasks.size(); i++) {
                            com.google.android.gms.tasks.Task<Text> t = tasks.get(i);
                            if (!t.isSuccessful()) continue;
                            double sc = ocrScore(t.getResult(), i == 0);
                            if (sc > bestScore) {
                                bestScore = sc;
                                best = t.getResult();
                                which = tags.get(i);
                            }
                        }
                        if (best == null) {
                            StringBuilder msg = new StringBuilder();
                            for (com.google.android.gms.tasks.Task<Text> t : tasks) {
                                if (t.getException() != null) msg.append(t.getException().getMessage()).append(' ');
                            }
                            if (msg.toString().trim().isEmpty()) msg.append("识别服务未就绪");
                            final String m = msg.toString();
                            runOnUiThread(() -> ocrText.setText("文字识别失败：" + m));
                            return;
                        }
                        // 后处理永远不许把识别结果吞掉：处理后为空就退回原始输出
                        String text = polishOcr(renderOcr(best));
                        if (text.trim().isEmpty()) {
                            String raw = best.getText();
                            text = raw == null ? "" : raw.trim();
                        }
                        final String model = which;
                        final int conf = (int) Math.max(0, Math.min(99, bestScore));
                        final String result = text;
                        runOnUiThread(() -> {
                            if (result.isEmpty()) {
                                ocrText.setText("未识别到文字，请保证字迹清晰、光线充足后重试");
                            } else {
                                ocrText.setText(result);
                                Toast.makeText(this, model + " · 置信度 " + conf + "% · v"
                                                + BuildConfig.VERSION_NAME,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
        }, () -> recycleOcrPair(bmp, null));
    }

    /** 小图放大到长边约 1600px（最多 3 倍），字太小是识别不准的头号原因 */
    private Bitmap upscaleForOcr(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= 0 || longSide >= 1600) return src;
        float f = Math.min(3f, 1600f / longSide);
        if (f <= 1.15f) return src;
        Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * f)),
                Math.max(1, Math.round(h * f)), true);
        return out == null ? src : out;
    }

    /**
     * 回收识别用的图。放大常常就 multiply 出第二张全屏位图（1600px 见方 = 10MB），
     * 原图 + 放大图一起压着，低配机上足够把堆顶穿，所以用完立刻收。
     * createScaledBitmap 尺寸没变时会把原图原样返回，判一下别 recycle 两遍。
     */
    private static void recycleOcrPair(Bitmap src, Bitmap scaled) {
        if (scaled != null && scaled != src && !scaled.isRecycled()) scaled.recycle();
        if (src != null && !src.isRecycled()) src.recycle();
    }

    /** 给一份识别结果打分：平均置信度为主，乱码扣分，纯拉丁文本给拉丁模型加分 */
    private double ocrScore(Text t, boolean latinModel) {
        if (t == null) return -1;
        double wsum = 0;
        int len = 0, latinChars = 0, cjkChars = 0, junk = 0;
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line ln : b.getLines()) {
                for (Text.Element el : ln.getElements()) {
                    String s = el.getText();
                    if (s == null || s.isEmpty()) continue;
                    Float c = el.getConfidence();
                    double conf = c == null ? 0.6 : c;
                    wsum += conf * s.length();
                    len += s.length();
                    for (int i = 0; i < s.length(); i++) {
                        char ch = s.charAt(i);
                        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) latinChars++;
                        else if ((ch >= 0x4E00 && ch <= 0x9FFF)) cjkChars++;
                        else if (ch == '|' || ch == '~' || ch == '^' || ch == '`' || ch == '\\') junk++;
                    }
                }
            }
        }
        if (len == 0) return -1;
        double conf = wsum / len;
        double score = conf * 100;
        score -= junk * 100.0 / len * 0.8;                       // 乱码符号扣分
        if (latinModel && latinChars > cjkChars * 3) score += 8;  // 纯英文场景拉丁模型本就该赢
        if (!latinModel && cjkChars > latinChars) score += 6;     // 中文场景中文模型本该赢
        return score;
    }

    /**
     * 字符级清洗。这一步专治「单词后面挂一串乱码」：
     * OCR 会把单词之间的竖线、装饰符号、零宽字符一起读进同一行，
     * 中文模型还会把拉丁字母认成形近汉字（l→丨、0→〇），不清洗就会粘在词尾。
     */
    private static String cleanOcrLine(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u00A0' || ch == '\u3000') { b.append(' '); continue; }
            // 零宽字符 / 软连字符 / 方向标记 / 替换符：看不见但会跟着单词跑
            if ((ch >= '\u200B' && ch <= '\u200F') || ch == '\uFEFF'
                    || ch == '\u00AD' || ch == '\u2060' || ch == '\uFFFD') continue;
            // 全角转半角（中文模型爱输出全角英数）
            if (ch >= '\uFF01' && ch <= '\uFF5E') { b.append((char) (ch - 0xFEE0)); continue; }
            b.append(ch);
        }
        // 形近竖线：夹在两个字母中间基本就是 l / I，直接删掉会把整个词毁掉
        StringBuilder c = new StringBuilder(b.length());
        String out = b.toString();
        for (int i = 0; i < out.length(); i++) {
            char ch = out.charAt(i);
            if ("|丨¦│ǀ⌐".indexOf(ch) >= 0) {
                int p = i - 1;
                while (p >= 0 && out.charAt(p) == ' ') p--;
                int q = i + 1;
                while (q < out.length() && out.charAt(q) == ' ') q++;
                boolean leftLetter = p >= 0 && Character.isLetter(out.charAt(p));
                boolean rightLetter = q < out.length() && Character.isLetter(out.charAt(q));
                if (leftLetter && rightLetter) {
                    if (c.length() > 0 && c.charAt(c.length() - 1) == ' ') c.setLength(c.length() - 1);
                    c.append(Character.isUpperCase(out.charAt(p)) ? 'I' : 'l');
                    i = q - 1;
                    continue;
                }
                c.append(' ');           // 孤立的竖线当分隔符，别留在词尾
                continue;
            }
            c.append(ch);
        }
        // 其余装饰性符号（波浪线、脱字符、重音符、圆点、箭头）：一律变空格
        String cleaned = c.toString().replaceAll("[~^`´¨¸•·●○◆◇★☆※→←↑↓▪▫■□▲▼]+", " ");
        cleaned = cleaned.replaceAll("[ \\t]{2,}", " ").trim();
        return cleaned;
    }

    /** 这一行还有没有实质内容（纯符号行直接丢掉，别让乱码单独占一行） */
    private static boolean hasAlnum(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) return true;
        }
        return false;
    }

    /**
     * 行级重排：按（栏 → 行顶 → 左缘）排序，双栏时先左栏后右栏。
     * v3.4 做减法：不再从词块重建版面——收益不稳定，却让结果变脆。
     */
    private String renderOcr(Text t) {
        if (t == null) return "";
        ArrayList<String> lines = new ArrayList<>();
        ArrayList<int[]> pos = new ArrayList<>();      // {top, left, right}
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line ln : b.getLines()) {
                String s = cleanOcrLine(ln.getText());
                if (s.isEmpty() || !hasAlnum(s)) continue;
                android.graphics.Rect bb = ln.getBoundingBox();
                lines.add(s);
                pos.add(new int[]{bb == null ? 0 : bb.top, bb == null ? 0 : bb.left,
                        bb == null ? 0 : bb.right});
            }
        }
        int n = lines.size();
        if (n == 0) return "";
        int minLeft = Integer.MAX_VALUE, maxRight = 0;
        for (int[] q : pos) {
            if (q[1] < minLeft) minLeft = q[1];
            if (q[2] > maxRight) maxRight = q[2];
        }
        final int mid = (minLeft + maxRight) / 2;
        boolean twoColTmp = false;
        if (n >= 6) {
            int l = 0, r = 0, cross = 0;
            for (int[] q : pos) {
                if (q[1] < mid) l++;
                if (q[2] > mid) r++;
                if (q[1] < mid - 8 && q[2] > mid + 8) cross++;
            }
            twoColTmp = l >= 3 && r >= 3 && cross <= 1;
        }
        final boolean twoCol = twoColTmp;
        Integer[] order = new Integer[n];
        for (int i2 = 0; i2 < n; i2++) order[i2] = i2;
        java.util.Arrays.sort(order, (x, y) -> {
            int cx = twoCol ? (pos.get(x)[1] < mid ? 0 : 1) : 0;
            int cy = twoCol ? (pos.get(y)[1] < mid ? 0 : 1) : 0;
            if (cx != cy) return cx - cy;
            int d = pos.get(x)[0] - pos.get(y)[0];
            if (d != 0) return d;
            return pos.get(x)[1] - pos.get(y)[1];
        });
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            String s2 = lines.get(order[k]);
            if (sb.length() > 0) {
                int m = sb.length();
                if (m > 1 && sb.charAt(m - 1) == '-' && !s2.isEmpty()
                        && Character.isLowerCase(s2.charAt(0))) {
                    sb.setLength(m - 1);
                    sb.append(s2);
                    continue;
                }
                sb.append('\n');
            }
            sb.append(s2);
        }
        return sb.toString();
    }

    /**
     * 用自家 35.5 万词条给 OCR 结果做兜底纠错：
     * 只处理"词典里查不到"的英文单词，且候选与原文编辑距离 ≤1 才替换——
     * 宁可少纠，不能把对的改错。
     */
    private String polishOcr(String text) {
        if (text == null || text.isEmpty()) return "";
        if (!dictReady || dict == null) return text;
        StringBuilder out = new StringBuilder(text.length());
        Matcher m = Pattern.compile("[A-Za-z]{5,}").matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String w = m.group();
            last = m.end();
            String fixed = w;
            try {
                if (dict.lookup(w) == null) {
                    String lower = w.toLowerCase(Locale.US);
                    ArrayList<String> cands = dict.suggest(lower.substring(0, 3), 16);
                    String best = null;
                    int bestD = 9;
                    for (String c : cands) {
                        if (c == null || Math.abs(c.length() - lower.length()) > 1) continue;
                        int d = editDistance(lower, c.toLowerCase(Locale.US), 2);
                        if (d < bestD) { bestD = d; best = c; }
                    }
                    if (best != null && bestD == 1) {
                        fixed = Character.isUpperCase(w.charAt(0))
                                ? Character.toUpperCase(best.charAt(0)) + best.substring(1) : best;
                    }
                }
            } catch (Throwable ignored) { JLog.w("MainActivity.polishOcr", ignored);
             }
            out.append(fixed);
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** 带上限的编辑距离：超过 cap 立刻返回 cap+1，省得白算 */
    private static int editDistance(String a, String b, int cap) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > cap) return cap + 1;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > cap) return cap + 1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return Math.min(prev[lb], cap + 1);
    }

    private TextRecognizer getLatinRecognizer() {
        if (latinRecognizer == null) {
            latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        return latinRecognizer;
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
        translating = true;
        if (CloudTrans.isConfigured()) {
            // 云端优先：当前选中的厂商（百度 / 腾讯云）；失败自动回退离线引擎，但错误原因必须显示出来
            translateOutput.setText(zh2en ? "（中 → 英 · 云端，翻译中…）" : "（英 → 中 · 云端，翻译中…）");
            // onDestroy 把三个线程池都 shutdownNow 了，往收摊的池子提交是直接抛
            // RejectedExecutionException = 主线程闪退。这里和素材下载那两处保持同一道检查。
            if (netPool.isShutdown()) { translating = false; return; }
            netPool.execute(() -> {
                final String result = CloudTrans.translate(input, zh2en);
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
        cancelTranslateWatchdog();
        // 30 秒无响应 → 判定卡住，给出自救指引并重建引擎
        transWatchdog = () -> {
            if (!finished[0]) {
                finished[0] = true;      // 之后迟到的回调就不再覆盖这段自救指引了
                translating = false;
                resetTranslators();
                translateOutput.setText("引擎响应超时（30 秒）。\n\n可能原因与对策：\n"
                        + "1) 首次使用需联网下载语言包，请确认网络可用后重试；\n"
                        + "2) 手机管家/安全软件可能拦截了语言包下载，请放行简译；\n"
                        + "3) 切换 Wi-Fi/流量后再试；\n"
                        + "4) 仍不行：设置 → 应用 → 简译 → 清除数据，重启 App（词典会自动重新解压）。");
            }
        };
        // 挂在字段 handler 上：成功/失败/onDestroy 都要撤掉，
        // 否则 lambda 会连着 Activity 和视图多存活 30 秒
        transWatchdogHandler.postDelayed(transWatchdog, 30000);
        t.downloadModelIfNeeded()
                .addOnSuccessListener(v -> t.translate(input)
                        .addOnSuccessListener(res -> {
                            if (finished[0]) return;
                            finished[0] = true;
                            cancelTranslateWatchdog();
                            translating = false;
                            translateOutput.setText((zh2en ? "（中 → 英）\n" : "（英 → 中）\n") + res);
                        })
                        .addOnFailureListener(e -> {
                            if (finished[0]) return;
                            finished[0] = true;
                            cancelTranslateWatchdog();
                            translating = false;
                            translateOutput.setText("翻译失败：" + friendly(e));
                        }))
                .addOnFailureListener(e -> {
                    if (finished[0]) return;
                    finished[0] = true;
                    cancelTranslateWatchdog();
                    translating = false;
                    resetTranslators();
                    translateOutput.setText("语言包下载失败：" + friendly(e)
                            + "\n\n首次使用需联网下载一次语言包（约 30MB），请检查网络后重试；下载成功后即可永远离线使用。");
                });
    }

    private void cancelTranslateWatchdog() {
        if (transWatchdog != null) {
            transWatchdogHandler.removeCallbacks(transWatchdog);
            transWatchdog = null;
        }
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

    /**
     * 云端增强设置弹窗：可选百度 / 腾讯云，两家凭据分开存、互不影响。
     * 切换厂商只改「用哪家」，之前填的另一边还留着，随时能切回来。
     */
    private void showCloudDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);

        // 厂商选择：两枚可点的标签，选中哪个就填哪个的凭据
        LinearLayout provRow = new LinearLayout(this);
        provRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] provBtns = new TextView[2];
        final String[] provIds = {CloudTrans.PROVIDER_BAIDU, CloudTrans.PROVIDER_TENCENT};
        final String[] provNames = {"百度翻译", "腾讯云"};

        // 凭据输入区：随厂商切换换内容
        final LinearLayout credBox = new LinearLayout(this);
        credBox.setOrientation(LinearLayout.VERTICAL);
        final EditText etId = new EditText(this);
        final EditText etKey = new EditText(this);
        final TextView tip = new TextView(this);
        tip.setTextColor(getColor(R.color.text_secondary));
        tip.setTextSize(13);
        tip.setPadding(0, dp(8), 0, 0);
        credBox.addView(etId);
        credBox.addView(etKey);
        credBox.addView(tip);

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            String prov = prefs.getString(CloudTrans.PREF_PROVIDER, CloudTrans.PROVIDER_BAIDU);
            if (CloudTrans.PROVIDER_TENCENT.equals(prov)) {
                etId.setHint("SecretId");
                etId.setText(prefs.getString("tencent_secret_id", ""));
                etKey.setHint("SecretKey");
                etKey.setText(prefs.getString("tencent_secret_key", ""));
                tip.setText("腾讯云机器翻译：控制台 → 访问管理 → API 密钥管理，新建一个密钥，"
                        + "把 SecretId / SecretKey 填进来。需先开通「机器翻译」服务。"
                        + "免费额度约每月 500 万字符，国内直连稳定。");
            } else {
                etId.setHint("APP ID");
                etId.setText(prefs.getString("baidu_appid", ""));
                etKey.setHint("密钥");
                etKey.setText(prefs.getString("baidu_secret", ""));
                tip.setText("免费获取：fanyi-api.baidu.com → 注册 → 开通「通用翻译」。"
                        + "填入后联网翻译走云端（更通顺），断网自动切回离线引擎；每月免费额度约 5 万字符。");
            }
            for (int i = 0; i < provBtns.length; i++) {
                boolean sel = provIds[i].equals(prov);
                provBtns[i].setTextColor(getColor(sel ? R.color.text_primary : R.color.text_secondary));
                provBtns[i].setBackgroundResource(sel ? R.drawable.btn_soft : R.drawable.input_bg);
            }
        };

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView b = new TextView(this);
            b.setText(provNames[i]);
            b.setTextSize(15);
            b.setPadding(dp(14), dp(9), dp(14), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                prefs.edit().putString(CloudTrans.PREF_PROVIDER, provIds[idx]).apply();
                fill[0].run();
            });
            provBtns[i] = b;
            provRow.addView(b);
        }
        box.addView(provRow);
        box.addView(credBox);
        fill[0].run();      // 用当前厂商填初值

        new AlertDialog.Builder(this)
                .setTitle("云端增强")
                .setView(box)
                .setPositiveButton("保存并测试", (d, w) -> {
                    final String prov = prefs.getString(CloudTrans.PREF_PROVIDER, CloudTrans.PROVIDER_BAIDU);
                    final String id = etId.getText().toString().trim();
                    final String key = etKey.getText().toString().trim();
                    if (CloudTrans.PROVIDER_TENCENT.equals(prov)) {
                        prefs.edit().putString("tencent_secret_id", id)
                                .putString("tencent_secret_key", key).apply();
                    } else {
                        prefs.edit().putString("baidu_appid", id)
                                .putString("baidu_secret", key).apply();
                    }
                    refreshCloudLabel();
                    if (id.isEmpty() || key.isEmpty()) {
                        Toast.makeText(this, "已停用云端（信息为空）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "已保存，正在测试连通…", Toast.LENGTH_SHORT).show();
                    if (netPool.isShutdown()) return;
                    netPool.execute(() -> {
                        final String r = CloudTrans.translate("你好，世界", true);
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
                    // 只停用当前厂商的凭据，另一家留着
                    String prov = prefs.getString(CloudTrans.PREF_PROVIDER, CloudTrans.PROVIDER_BAIDU);
                    if (CloudTrans.PROVIDER_TENCENT.equals(prov)) {
                        prefs.edit().remove("tencent_secret_id").remove("tencent_secret_key").apply();
                    } else {
                        prefs.edit().remove("baidu_appid").remove("baidu_secret").apply();
                    }
                    refreshCloudLabel();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCloudLabel() {
        boolean on = CloudTrans.isConfigured();
        cloudToggle.setText(on
                ? "云端增强：" + CloudTrans.providerLabel() + "已开启"
                : "云端增强：未开启");
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

    /**
     * 旋转 / 分屏：manifest 里用 configChanges 接管了，Activity 不重建、词典不重载（#11）。
     * 系统仍会重新测量布局，但有几件事必须手动做：
     *  ① 按尺寸自绘的页面（游戏）要能重新铺开并恢复重绘；
     *  ② 清掉查词框的焦点，否则旋转/恢复瞬间系统会自作主张把输入法弹出来（#6）。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 布局尺寸变了，让游戏页按新尺寸重铺一屏；非游戏页也会重走测量，这里只兜底游戏
        if (gameView != null && isPageVisible(pageGame)) {
            gameView.requestLayout();
            gameView.invalidate();
        }
        // 关键：交出查词框焦点 + 收键盘，避免配置一变就自动呼出输入法
        hideKeyboard();
    }

    /**
     * 从桌面切回前台时触发（#6）。
     * 之前完全没有这个回调，导致「游戏时切后台再回来，小屏机会自动呼出键盘」——
     * 因为 searchBox 可能还留着焦点，系统一看有可获焦点的输入框就把 IME 拉起来。
     * 这里只要不在查词页，就把焦点和键盘都收干净。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 只有停在游戏页（或其它非查词页）才收；停在查词页时不动，
        // 否则会打断用户正在输入的词。
        if (pageGame != null && pageGame.getVisibility() == View.VISIBLE) {
            hideKeyboard();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pron != null) {
            pron.stop();
            pronWord = null;
            pronState = -1;
        }
        if (gameView != null) {
            if (isPageVisible(pageGame)) gameView.persistProgress();   // 切后台/锁屏也存档
            gameView.stop();   // 退到后台就别再逐帧重绘了
        }
    }

    @Override
    protected void onDestroy() {
        if (skinBmp != null) {
            skinBmp.recycle();
            skinBmp = null;
        }
        releasePendingOcr();        // 停在框选页直接退出的话，那张相机图还压在字段上
        if (pron != null) pron.release();
        releaseListen();
        if (translatorZhEn != null) translatorZhEn.close();
        if (translatorEnZh != null) translatorEnZh.close();
        if (recognizer != null) recognizer.close();
        if (latinRecognizer != null) latinRecognizer.close();
        suggestHandler.removeCallbacksAndMessages(null);       // 撤掉还在防抖窗口里等着的联想查询
        flushPendingStudyWrites();                            // 排在 dbPool 里的答题落库先补做完，下面的 shutdownNow 会整队丢掉它们
        // shutdownNow：shutdown() 会让排队的任务继续跑完，那些任务都会 runOnUiThread/Toast，
        // Activity 都没了还往它投递工作 = 泄漏 + 销毁后访问视图
        dbPool.shutdownNow();
        netPool.shutdownNow();
        packPool.shutdownNow();
        // studyDb 得等 dbPool 收摊再关：那条线上可能还压着一个没跑完的写库，
        // 先关就是 "database has been closed"。给一点点耐心，别无限等。
        try {
            dbPool.awaitTermination(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (studyDb != null) {
            studyDb.close();
            studyDb = null;
        }
        dictStatusHandler.removeCallbacksAndMessages(null);   // 撤掉没触发的延迟提示，防泄漏
        cancelTranslateWatchdog();                            // 撤掉翻译超时看门狗（它持有本 Activity 和视图）
        if (studyPanelDialog != null) {                       // Dialog 不关就是 window leak
            studyPanelDialog.dismiss();
            studyPanelDialog = null;
        }
        if (dict != null) {                                   // 只读词典句柄也得还，进程活着就一直占着 fd
            dict.close();
            dict = null;
        }
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
        if (searchBox == null) return;      // onResume/旋屏可能早于 onCreate 完成赋值
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
        } else if (state == Pron.STATE_NO_ENGINE && message != null) {
            // 本机没有任何可用的英文语音（#10 旧手机离线朗读用不了的真正原因）。
            // 静默失败只会让人以为 App 坏了，给一个能照着做的引导。
            showNoTtsEngineDialog(message);
        }
    };

    /** 无英文语音引擎时的引导（#10）：告诉用户两条可走的路 */
    private void showNoTtsEngineDialog(String detail) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("这台手机没法离线朗读")
                .setMessage(detail
                        + "\n\n两条解决办法：\n"
                        + "① 联网时打开「设置 → 发音 → 预缓存常用词」，把发音存到本地，之后断网也能听；\n"
                        + "② 或者在系统「文字转语音」里安装/启用英文语音包。")
                .setPositiveButton("去预缓存", (d, w) -> showSettings())
                .setNegativeButton("知道了", null)
                .show();
    }

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

    /**
     * 常用搭配一行（v3.10 词组训练 A）：
     * 上面是英文短语（可点、点了就查这个短语），右边一个喇叭能听发音；
     * 下面是中文释义。整块包一层可点击背景。
     */
    private View buildPhraseRow(final DictDb.Phrase x) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(8), 0, 0);
        wrap.setBackgroundResource(R.drawable.input_bg);   // 浅底块，和其它内容区分开
        int pad = dp(10);
        wrap.setPadding(pad, pad, pad, pad);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView en = new TextView(this);
        en.setText(x.phrase);
        en.setTextColor(getColor(R.color.text_primary));
        en.setTextSize(15);
        en.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        en.setLineSpacing(dp(2), 1);
        en.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(en);
        row.addView(buildSpeakerFor(x.phrase, dp(34)));
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

        // 点英文短语 → 直接查这个短语（短语本身在词典里也有词条）
        en.setOnClickListener(v -> {
            searchBox.setText(x.phrase);
            searchBox.setSelection(x.phrase.length());
            doLookup(x.phrase);
        });
        wrap.setOnClickListener(v -> {
            searchBox.setText(x.phrase);
            searchBox.setSelection(x.phrase.length());
            doLookup(x.phrase);
        });
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
        bg.setCornerRadius(dp(12));
        t.setBackground(bg);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
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
                R.id.pageReading, R.id.pageCrop, R.id.pageWordBook, R.id.pageWrongBook};
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

        ScrollView skinSv = new ScrollView(this);
        LinearLayout.LayoutParams skinBoxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(skinBoxLp);
        skinSv.addView(box);
        skinDialog = new AlertDialog.Builder(this)
                .setTitle("皮肤")
                .setView(skinSv)
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
        Bitmap cropped = null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream s = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(s, null, probe);
            }

            int vw = getResources().getDisplayMetrics().widthPixels;
            int vh = getResources().getDisplayMetrics().heightPixels;
            int sample = 1;
            while (probe.outWidth / sample > vw * 2 || probe.outHeight / sample > vh * 2) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src;
            try (InputStream s2 = getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(s2, null, opts);
            }
            if (src == null) return false;

            // 居中裁剪成屏幕比例，铺满时才不变形
            float screenRatio = (float) vw / vh;
            float imgRatio = (float) src.getWidth() / src.getHeight();
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
            // 先写临时文件，成功后再替换，避免压缩中途失败把老皮肤写成半截文件
            File tmp = new File(getFilesDir(), "skin.jpg.tmp");
            boolean compressed;
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                compressed = cropped.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            }
            if (!compressed) {
                tmp.delete();
                return false;
            }
            File old = new File(getFilesDir(), "skin.jpg");
            if (old.exists() && !old.delete()) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                return false;
            }

            // 旧缓存直接放手，别在这儿 recycle：这个方法是后台线程跑的，
            // 而 skinBmp 还挂在屏幕背景的 BitmapDrawable 上，界面正画到一半，
            // 回收中的位图被画布碰到就是 "Canvas: trying to use a recycled bitmap"。
            // API 26 起位图像素本来就在 Java 堆里，交给 GC 收就够了，
            // 调用方紧接着会 applySkin() 解出新图，老的这张再没人引用自然会被回收。
            skinBmp = null;
            return true;
        } catch (Throwable t) {
            JLog.w("MainActivity.saveSkinBitmap", t);
            return false;
        } finally {
            if (cropped != null) cropped.recycle();
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
        // 皮肤是按屏幕比例存的，但存的时候只限到「每边不超过 2 倍屏幕」，
        // 1080×2400 的机器上文件就可能还是 2160×4800。默认参数解出来是 ARGB_8888，
        // 那张图会一直压在 skinBmp 字段里当全 App 背景 —— 10.4M 像素 × 4 字节 ≈ 41MB。
        // 背景最终就是铺满屏幕，解到屏幕尺寸 + RGB_565 完全够，看着没差别，留 5MB 左右。
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
        int vw = getResources().getDisplayMetrics().widthPixels;
        int vh = getResources().getDisplayMetrics().heightPixels;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        int sample = 1;
        while (probe.outWidth / sample > vw || probe.outHeight / sample > vh) sample *= 2;
        opts.inSampleSize = sample;
        skinBmp = BitmapFactory.decodeFile(f.getPath(), opts);
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

        // ===== 词组训练（v3.10）=====
        box.addView(sectionTitle("词组训练"));
        TextView phraseInfo = new TextView(this);
        phraseInfo.setTextColor(getColor(R.color.text_secondary));
        phraseInfo.setTextSize(13);
        final int pc = (dictReady && dict != null) ? dict.phraseCount() : 0;
        phraseInfo.setText(pc > 0
                ? "已收录 " + pc + " 条常用搭配（give up / take over / work out…）"
                : "搭配库正在加载，或本机词典还是旧版（升级后会自动更新一次）");
        phraseInfo.setPadding(0, dp(4), 0, dp(4));
        box.addView(phraseInfo);

        TextView phraseNote = new TextView(this);
        phraseNote.setText("查词时，词条下方会列出这个字的常用搭配；"
                + "记背页每组也会出 2 道短语题（给中文选英文搭配 / 给英文搭配选中文）。");
        phraseNote.setTextColor(getColor(R.color.text_secondary));
        phraseNote.setTextSize(11);
        phraseNote.setPadding(0, dp(4), 0, 0);
        box.addView(phraseNote);

        // ===== 错词复现（#8）：把两条复现路径讲清楚，别让人以为错词丢了 =====
        box.addView(sectionTitle("错词复现"));
        TextView wrongInfo = new TextView(this);
        wrongInfo.setText("错过的词会自己回来，走两条路：");
        wrongInfo.setTextColor(getColor(R.color.text_secondary));
        wrongInfo.setTextSize(13);
        wrongInfo.setPadding(0, dp(4), 0, dp(2));
        box.addView(wrongInfo);

        TextView wrongNote = new TextView(this);
        wrongNote.setText("① 记背页：答错的词按「1 天 → 2 天 → 4 天 → 8 天 → 16 天」阶梯安排复习，"
                + "答对一次就往后挪一格，所以当天再练一组看不到它——要等到期。\n"
                + "② 配对游戏：配错的词过 3 屏（约 15 对）后会回到牌桌上，帮你再认一遍。\n"
                + "③ 两条路都算「答错过」，所以游戏里配错的词也会进「错题集」。");
        wrongNote.setTextColor(getColor(R.color.text_secondary));
        wrongNote.setTextSize(11);
        wrongNote.setLineSpacing(dp(3), 1);
        wrongNote.setPadding(0, dp(2), 0, 0);
        box.addView(wrongNote);

        // ===== 消除音效（v3.12）=====
        box.addView(sectionTitle("音效"));
        final TextView sfxRow = optionRow("", () -> { });
        final Runnable refreshSfx = () -> {
            boolean on = prefs.getBoolean("game_sfx", true);
            sfxRow.setText("配对消除音效：" + (on ? "开" : "关"));
            sfxRow.setTextColor(getColor(on ? R.color.text_primary : R.color.text_secondary));
        };
        sfxRow.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("game_sfx", true);
            prefs.edit().putBoolean("game_sfx", on).apply();
            if (gameView != null) gameView.setSfxEnabled(on);
            refreshSfx.run();
            // 开着的话当场播一记，让你立刻听到效果
            if (on && gameView != null) gameView.previewSfx();
        });
        refreshSfx.run();
        box.addView(sfxRow);

        TextView sfxNote = new TextView(this);
        sfxNote.setText("游戏里配对消除时会「啵」一声；连着消多个时音高会微变，不容易听腻。");
        sfxNote.setTextColor(getColor(R.color.text_secondary));
        sfxNote.setTextSize(11);
        sfxNote.setPadding(0, dp(4), 0, 0);
        box.addView(sfxNote);

        final TextView vibeRow = optionRow("", () -> { });
        final Runnable refreshVibe = () -> {
            boolean on = prefs.getBoolean("game_vibe", true);
            vibeRow.setText("选错震动提醒：" + (on ? "开" : "关"));
            vibeRow.setTextColor(getColor(on ? R.color.text_primary : R.color.text_secondary));
        };
        vibeRow.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("game_vibe", true);
            prefs.edit().putBoolean("game_vibe", on).apply();
            if (gameView != null) gameView.setVibeEnabled(on);
            refreshVibe.run();
            if (on && gameView != null) gameView.previewBuzz();
        });
        refreshVibe.run();
        box.addView(vibeRow);

        TextView vibeNote = new TextView(this);
        vibeNote.setText("配对选错时短促震一下，手机没有马达则自动略过。");
        vibeNote.setTextColor(getColor(R.color.text_secondary));
        vibeNote.setTextSize(11);
        vibeNote.setPadding(0, dp(4), 0, 0);
        box.addView(vibeNote);

        box.addView(sectionTitle("素材包"));
        final TextView packRow = optionRow("", () -> { });
        final Runnable refreshPack = () -> {
            if (packBusy) {
                packRow.setText("素材包：下载中 " + packDone + "/" + packTotal + "…");
            } else if (prefs.getBoolean(PREF_PACK, false)) {
                int miss = countMissingMaterial();
                if (miss > 0) {
                    packRow.setText("素材包：有 " + miss + " 个新素材可下载（点此更新）");
                } else {
                    packRow.setText("素材包：已下载 " + (packSize() / 1048576) + " MB（点此删除）");
                }
            } else if (countMissingMaterial() == 0) {
                // v3.2.2 起现有素材全部内置，这一行只剩「看看有没有新的」
                packRow.setText("素材包：素材已全部内置（点此检查新内容）");
            } else {
                packRow.setText("素材包：有 " + countMissingMaterial() + " 个素材可下载");
            }
        };
        packRow.setOnClickListener(v -> {
            if (packBusy) return;
            boolean hasPack = prefs.getBoolean(PREF_PACK, false);
            if (hasPack && countMissingMaterial() == 0) {
                deletePack();
                Toast.makeText(this, "素材包已删除，只保留内置的", Toast.LENGTH_SHORT).show();
            } else if (!hasPack && countMissingMaterial() == 0) {
                // 内置就齐了：手动点一下等于「立刻查一次目录」，绕过一小时的节流
                prefs.edit().putLong("manifest_refresh_ts", 0).commit();
                Toast.makeText(this, "正在检查有没有新素材…", Toast.LENGTH_SHORT).show();
                refreshManifests();
                return;
            } else {
                if (packBase == null || packBase.isEmpty()) {
                    Toast.makeText(this, "清单里没配下载地址，稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, hasPack ? "开始更新，只拉新增的" : "开始下载，可在后台慢慢下",
                        Toast.LENGTH_SHORT).show();
                downloadPack(null);
            }
            refreshPack.run();
        });
        refreshPack.run();
        box.addView(packRow);
        TextView packNote = new TextView(this);
        packNote.setText("现有素材（5 篇阅读 + 5 条听力）已全部内置在安装包里，开箱即用，不联网也能看能听。"
                + "打开 App 会自动检查素材目录更新；以后加了新内容，上面会提示「有新素材可下载」，"
                + "点了才占用手机空间。素材来自公有领域文本与 VOA 慢速英语存量。");
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

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(boxLp);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sv)
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
            box.setBackgroundResource(R.drawable.btn_card);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = dp(pos == 0 ? 8 : 6);
            cardLp.bottomMargin = dp(6);
            box.setLayoutParams(cardLp);

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
            head.addView(buildFavorite(e.word));   // 收藏星标：点亮=已收进单词本
            head.addView(buildSpeaker(e));
            box.addView(head);

            // ===== 词级标记：六级重点词（红）+ 高频词（金星）+ 其他标签（灰）=====
            LinearLayout marks = new LinearLayout(MainActivity.this);
            marks.setOrientation(LinearLayout.HORIZONTAL);
            marks.setGravity(Gravity.CENTER_VERTICAL);
            int markCount = 0;
            if (e.phrase) {
                // 词组释义来自搭配表，挂个标签说明出处，免得看着像缺音标缺词性的残条目
                marks.addView(chip("词组 · 常见搭配", R.color.text_secondary));
                markCount++;
            }
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

            // ===== 常用搭配（v3.10 词组训练 A）：紧跟释义，讲"这个词怎么用" =====
            if (it.phrases != null && !it.phrases.isEmpty()) {
                TextView ph = new TextView(MainActivity.this);
                ph.setText("常用搭配");
                ph.setTextColor(getColor(R.color.text_secondary));
                ph.setTextSize(13);
                ph.setPadding(0, dp(18), 0, 0);
                box.addView(ph);
                for (DictDb.Phrase x : it.phrases) box.addView(buildPhraseRow(x));
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
