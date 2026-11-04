package art.qelora.welltrack.health;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/**
 * 记录提醒偏好 (v2.12, 纯本机保存, 不新增后端接口、不上传服务器)。
 *
 * 载体是首页提醒条, 而非系统通知: 不申请闹钟/通知权限, 不受后台限制影响,
 * 打开 App 当天未记录体重时即能看到, 点「今日不再提醒」后当天不再出现。
 * 默认开启、默认 08:00 (晨起称重), 用户可在计划页修改时间或关闭。
 */
public final class Reminders {
    private static final String PREFS = "welltrack_reminders";
    private static final String KEY_ENABLED = "reminder_enabled";
    private static final String KEY_HOUR = "reminder_hour";
    private static final String KEY_MINUTE = "reminder_minute";
    private static final String KEY_DISMISSED_DATE = "dismissed_date";

    private Reminders() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int hour(Context ctx) {
        return prefs(ctx).getInt(KEY_HOUR, 8);
    }

    public static int minute(Context ctx) {
        return prefs(ctx).getInt(KEY_MINUTE, 0);
    }

    public static void setTime(Context ctx, int hour, int minute) {
        prefs(ctx).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply();
    }

    public static String timeText(Context ctx) {
        return String.format(Locale.US, "%02d:%02d", hour(ctx), minute(ctx));
    }

    public static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 当天是否已点过「今日不再提醒」。 */
    public static boolean dismissedToday(Context ctx) {
        return todayString().equals(prefs(ctx).getString(KEY_DISMISSED_DATE, ""));
    }

    public static void dismissToday(Context ctx) {
        prefs(ctx).edit().putString(KEY_DISMISSED_DATE, todayString()).apply();
    }
}
