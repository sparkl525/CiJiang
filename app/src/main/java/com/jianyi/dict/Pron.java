package com.jianyi.dict;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单词发音管理器。
 *
 * 三条原则：
 * 1. 合成很慢（约 1~3 秒），所以查词成功后就在后台悄悄把发音取回来存好；
 *    用户点喇叭时多数情况下直接播缓存，感觉是"秒出"。
 * 2. 一个词只合成一次，按「音色 + 单词」存成 mp3 落在应用私有缓存目录。
 * 3. 合成走单线程队列，绝不并发，避免低配手机被拖垮。
 */
public class Pron {

    public static final int STATE_LOADING = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_ERROR = 3;
    public static final int STATE_OFFLINE = 4;   // 没缓存又没网：改用手机自带语音

    /** 播放状态回调（主线程） */
    public interface Listener {
        void onState(String word, int state, String message);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService batchPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private String playingWord;
    private Listener lastListener;   // 供系统语音的播放结束回调用

    // 批量预缓存
    private volatile boolean batchRunning;
    private volatile int batchDone;
    private volatile int batchTotal;

    // 离线兜底：手机自带的英文语音
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile boolean ttsFailed;

    // 预取去重：始终只保留最新待取词
    private final Object prefetchLock = new Object();
    private String wantPrefetch;
    private boolean prefetchRunning;

    public Pron(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ================= 设置 =================

    public int getVoice() {
        int v = prefs.getInt("pron_voice", 3); // 默认美式女声，最通用
        return (v >= 0 && v < EdgeTts.VOICE_IDS.length) ? v : 3;
    }

    public void setVoice(int index) {
        prefs.edit().putInt("pron_voice", index).apply();
        stop();
    }

    // ================= 缓存 =================

    private File cacheFile(String word, int voice) {
        // 存在应用私有目录（不是缓存目录）：系统清理空间时不会把它删掉，离线才靠得住
        File dir = new File(ctx.getFilesDir(), "pron/" + voice);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, md5(word.toLowerCase()) + ".mp3");
    }

    /** 缓存里是否已有该词发音 */
    public boolean isCached(String word) {
        File f = cacheFile(word, getVoice());
        return f.exists() && f.length() > 512;
    }

    /** 清空全部发音缓存，返回清掉的词条数 */
    public int clearCache() {
        File dir = new File(ctx.getFilesDir(), "pron");
        int n = countFiles(dir);
        deleteTree(dir);
        return n;
    }

    public int cacheCount() {
        return countFiles(new File(ctx.getFilesDir(), "pron"));
    }

    private static int countFiles(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) deleteTree(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    // ================= 预取 =================

    /** 后台静默取回发音，用户点喇叭时就能秒出 */
    public void prefetch(String word) {
        if (word == null || word.isEmpty()) return;
        if (isCached(word)) return;
        synchronized (prefetchLock) {
            wantPrefetch = word;
            if (prefetchRunning) return;
            prefetchRunning = true;
        }
        worker.execute(this::drainPrefetch);
    }

    private void drainPrefetch() {
        while (true) {
            String w;
            synchronized (prefetchLock) {
                w = wantPrefetch;
                wantPrefetch = null;
                if (w == null) {
                    prefetchRunning = false;
                    return;
                }
            }
            if (!isCached(w)) {
                try {
                    ensureFile(w);
                } catch (Throwable ignored) {
                    // 预取失败不打扰用户，等真正点击时再报错
                }
            }
        }
    }

    // ================= 播放 =================

    /** 点喇叭：有缓存立即播，没有则先合成再播 */
    public void play(final String word, final Listener listener) {
        if (word == null || word.isEmpty()) return;
        stop();

        final int voice = getVoice();
        final File f = cacheFile(word, voice);
        if (f.exists() && f.length() > 512) {
            startPlayer(word, f, listener);
            return;
        }
        if (listener != null) listener.onState(word, STATE_LOADING, null);
        playingWord = word;
        lastListener = listener;
        worker.execute(() -> {
            try {
                final File got = ensureFile(word);
                ui.post(() -> {
                    if (!word.equals(playingWord)) return; // 已经切到别的词了
                    startPlayer(word, got, listener);
                });
            } catch (final Throwable t) {
                final String reason = friendly(t);
                ui.post(() -> {
                    if (!word.equals(playingWord)) return;
                    // 合成拿不到（多数是没网）→ 改用手机自带语音，至少能出声
                    fallbackTts(word, listener, reason);
                });
            }
        });
    }

    // ================= 离线兜底：手机自带的英文语音 =================

    /** 懒加载：只有真的需要兜底时才初始化系统语音引擎 */
    private void ensureTts() {
        if (tts != null || ttsFailed) return;
        try {
            tts = new TextToSpeech(ctx, status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    ttsFailed = true;
                    return;
                }
                int r = tts.setLanguage(getVoice() <= 1 ? Locale.UK : Locale.US);
                if (r < TextToSpeech.LANG_AVAILABLE) {
                    tts.setLanguage(Locale.US);   // 没装对应口音就退回默认英文
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }

                    @Override public void onDone(String utteranceId) {
                        ui.post(() -> {
                            playingWord = null;
                            if (lastListener != null) {
                                lastListener.onState(utteranceId, STATE_DONE, null);
                            }
                        });
                    }

                    @Override public void onError(String utteranceId) {
                        ui.post(() -> {
                            playingWord = null;
                            if (lastListener != null) {
                                lastListener.onState(utteranceId, STATE_ERROR, "系统语音朗读失败");
                            }
                        });
                    }
                });
                ttsReady = true;
            });
        } catch (Throwable t) {
            ttsFailed = true;
        }
    }

    private void fallbackTts(final String word, final Listener listener, final String reason) {
        ensureTts();
        if (ttsFailed) {
            if (listener != null) listener.onState(word, STATE_ERROR, reason);
            return;
        }
        if (!ttsReady) {
            // 引擎首次初始化要一点时间，等它就绪再读
            ui.postDelayed(() -> {
                if (!word.equals(playingWord)) return;
                if (ttsReady) speakTts(word, listener);
                else if (listener != null) listener.onState(word, STATE_ERROR, reason);
            }, 1200);
            return;
        }
        speakTts(word, listener);
    }

    private void speakTts(String word, Listener listener) {
        try {
            tts.speak(word, TextToSpeech.QUEUE_FLUSH, new Bundle(), word);
            if (listener != null) listener.onState(word, STATE_OFFLINE, null);
        } catch (Throwable t) {
            if (listener != null) listener.onState(word, STATE_ERROR, "系统语音朗读失败");
        }
    }

    // ================= 批量预缓存常用词：离线朗读的底气 =================

    /** 后台逐个把常用词的发音取回来，可随时中断；断网自动停下，下次接着来 */
    public void startBatch(List<String> words) {
        if (batchRunning) return;
        final List<String> todo = new ArrayList<>(words);
        batchTotal = todo.size();
        batchDone = 0;
        batchRunning = true;
        batchPool.execute(() -> {
            for (String w : todo) {
                if (!batchRunning) break;
                try {
                    if (!isCached(w)) ensureFile(w);
                } catch (Throwable ignored) {
                    break;
                }
                batchDone++;
            }
            batchRunning = false;
        });
    }

    public void stopBatch() {
        batchRunning = false;
    }

    public boolean isBatchRunning() {
        return batchRunning;
    }

    public int batchDone() {
        return batchDone;
    }

    public int batchTotal() {
        return batchTotal;
    }

    // ================= 播放 =================

    private File ensureFile(String word) throws Exception {
        int voice = getVoice();
        File f = cacheFile(word, voice);
        if (f.exists() && f.length() > 512) return f;
        // 微软服务端偶发排队（实测约 12% 概率会卡 30 秒），超时后换一条新连接再试一次
        byte[] mp3 = null;
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                mp3 = EdgeTts.synth(EdgeTts.VOICE_IDS[voice], word);
                break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (mp3 == null) {
            throw (last != null) ? last : new IOException("发音获取失败");
        }
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(mp3);
            fo.flush();
        } finally {
            fo.close();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
        }
        return f;
    }

    private void startPlayer(final String word, File f, final Listener listener) {
        stop();
        playingWord = word;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getPath());
            mp.setOnPreparedListener(p -> {
                if (!word.equals(playingWord)) {
                    p.release();
                    return;
                }
                p.start();
                if (listener != null) listener.onState(word, STATE_PLAYING, null);
            });
            mp.setOnCompletionListener(p -> {
                if (p == player) {
                    player = null;
                    playingWord = null;
                }
                p.release();
                if (listener != null) listener.onState(word, STATE_DONE, null);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player) {
                    player = null;
                    playingWord = null;
                }
                p.release();
                // 文件可能损坏，删掉下次重新合成
                f.delete();
                if (listener != null) listener.onState(word, STATE_ERROR, "音频播放失败");
                return true;
            });
            player = mp;
            mp.prepareAsync();
        } catch (Throwable t) {
            player = null;
            playingWord = null;
            if (listener != null) listener.onState(word, STATE_ERROR, friendly(t));
        }
    }

    public void stop() {
        playingWord = null;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
        }
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    public void release() {
        stop();
        batchRunning = false;
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        worker.shutdownNow();
        batchPool.shutdownNow();
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.contains("timeout") || m.contains("Timeout") || m.contains("timed out")) {
            return "网络超时，稍后再试";
        }
        if (m.contains("UnknownHost") || m.contains("Unable to resolve host")) {
            return "域名解析失败，检查网络";
        }
        if (m.contains("连接被拒绝") || m.contains("101")) {
            return "服务暂不可用，稍后再试";
        }
        if (m.contains("SocketException") || m.contains("Connection reset")) {
            return "网络中断，请重试";
        }
        if (m.isEmpty()) return t.getClass().getSimpleName();
        return m;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
