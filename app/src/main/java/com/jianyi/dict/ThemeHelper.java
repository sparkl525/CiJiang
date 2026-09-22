package com.jianyi.dict;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 外观模式（跟随系统 / 浅色 / 深色）。
 *
 * 做法：在 Activity 的 attachBaseContext 阶段，把系统的 uiMode 改写成用户选的值，
 * 再交回去。这样所有 @color 引用和主题资源会自动走 values-night，
 * 无需引入 AndroidX 的 AppCompatDelegate。
 *
 * 用户选择"跟随系统"时不做任何改写，交给系统处理。
 */
public final class ThemeHelper {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    public static final String[] MODE_LABELS = {"跟随系统", "浅色", "深色"};

    private ThemeHelper() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static int getMode(Context ctx) {
        int m = prefs(ctx).getInt("theme_mode", MODE_SYSTEM);
        return (m >= MODE_SYSTEM && m <= MODE_DARK) ? m : MODE_SYSTEM;
    }

    public static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt("theme_mode", mode).apply();
    }

    /** 把用户选择翻译成实际的 uiMode 配置 */
    public static Context wrap(Context base) {
        int mode = getMode(base);
        if (mode == MODE_SYSTEM) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = (mode == MODE_DARK)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(cfg);
    }
}
