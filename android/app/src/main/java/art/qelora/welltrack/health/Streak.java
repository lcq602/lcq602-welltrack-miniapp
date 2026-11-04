package art.qelora.welltrack.health;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import art.qelora.welltrack.api.ApiClient;

/**
 * 连续打卡与成就计算 (纯本地逻辑, 不依赖后端新接口)。
 *
 * 数据源: GET /api/v1/records?limit=366 (双后端均已提供, 含手工记录/小米同步日/饮水日)。
 * 打卡日定义: 当天在记录列表里出现即算一天 (有体重/饮食/饮水/运动任一数据),
 * 含小米同步日 —— 首页与我的页脚注会如实说明该口径, 不声称为纯手动打卡。
 * 连续天数: 以今天为锚点, 今天未记则从昨天起算 (昨天也没记则为 0, 不把断档算进去)。
 */
public class Streak {

    public static class Badge {
        public final String icon;
        public final String name;
        public final String desc;
        public final boolean earned;
        public Badge(String icon, String name, String desc, boolean earned) {
            this.icon = icon; this.name = name; this.desc = desc; this.earned = earned;
        }
    }

    public static class Result {
        public int current;          // 当前连续天数
        public int longest;          // 窗口内最长连续天数
        public int weekRecordDays;   // 本周 (周一~周日) 已记录天数
        public int weekWorkoutDays;  // 本周运动天数 (workout_minutes > 0)
        public List<String> weekDates = new ArrayList<>();
        public Set<String> recordDates = new HashSet<>();
        public List<Badge> badges = new ArrayList<>();
        public String nextHint = "";
    }

    public interface Listener {
        void onResult(Result result);
        void onError(String message);
    }

    /** 拉取记录并算出打卡结果; 失败时回调 onError, 调用方静默处理 (不打扰用户)。 */
    public static void load(Context ctx, final Listener listener) {
        ApiClient.get(ctx).requestText("/api/v1/records?limit=366", new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                try {
                    JSONArray arr = new JSONArray(text == null ? "[]" : text);
                    Map<String, Boolean> workoutByDate = new HashMap<>();
                    Set<String> dates = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.optJSONObject(i);
                        if (r == null) continue;
                        String d = r.optString("record_date", "");
                        if (d.length() < 10) continue;
                        d = d.substring(0, 10);
                        dates.add(d);
                        if (r.optDouble("workout_minutes", 0) > 0) workoutByDate.put(d, Boolean.TRUE);
                    }
                    listener.onResult(compute(dates, workoutByDate, todayString()));
                } catch (Exception e) {
                    listener.onError("打卡数据解析失败");
                }
            }
            @Override public void onError(String message) {
                listener.onError(message);
            }
        });
    }

    public static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 本周一~周日日期 (与 PlanStore.weekDates 口径一致)。 */
    public static List<String> weekDatesOf(String today) {
        Calendar c = parse(today);
        int dow = c.get(Calendar.DAY_OF_WEEK); // 1=周日
        int shift = (dow + 5) % 7;             // 到周一的天数
        c.add(Calendar.DAY_OF_YEAR, -shift);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            out.add(format(c));
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    public static Result compute(Set<String> dates, Map<String, Boolean> workoutByDate, String today) {
        Result r = new Result();
        r.recordDates.addAll(dates);
        r.weekDates = weekDatesOf(today);
        for (String d : r.weekDates) {
            if (dates.contains(d)) r.weekRecordDays++;
            if (workoutByDate.containsKey(d)) r.weekWorkoutDays++;
        }
        // 当前连续: 今天有则从今天起, 否则昨天有则从昨天起, 否则 0
        Set<Long> days = new HashSet<>();
        for (String d : dates) days.add(dayNumber(d));
        long anchor = dayNumber(today);
        if (!days.contains(anchor)) anchor -= 1;
        int cur = 0;
        while (days.contains(anchor - cur)) cur++;
        r.current = cur;
        // 最长连续: 排序后数连续段
        List<Long> sorted = new ArrayList<>(new TreeSet<>(days));
        int best = 0, run = 0;
        long prev = Long.MIN_VALUE;
        for (long d : sorted) {
            run = (d == prev + 1) ? run + 1 : 1;
            prev = d;
            if (run > best) best = run;
        }
        r.longest = best;
        // 徽章: 连续 3/7/14/30 天 + 本周运动达人 (本周运动 >= 3 天)
        r.badges.add(new Badge("\uD83C\uDF31", "初露锋芒", "连续记录 3 天", r.current >= 3));
        r.badges.add(new Badge("\uD83D\uDD25", "坚持一周", "连续记录 7 天", r.current >= 7));
        r.badges.add(new Badge("\uD83D\uDCAA", "半月恒心", "连续记录 14 天", r.current >= 14));
        r.badges.add(new Badge("\uD83D\uDC51", "月度自律", "连续记录 30 天", r.current >= 30));
        r.badges.add(new Badge("\uD83C\uDFC3", "本周运动达人", "本周运动满 3 天", r.weekWorkoutDays >= 3));
        // 下一个目标提示
        int[][] streakBadges = {{3, 0}, {7, 1}, {14, 2}, {30, 3}};
        String[] streakNames = {"初露锋芒", "坚持一周", "半月恒心", "月度自律"};
        r.nextHint = "全部成就已解锁 \uD83C\uDF89";
        for (int i = 0; i < streakBadges.length; i++) {
            if (r.current < streakBadges[i][0]) {
                int left = streakBadges[i][0] - r.current;
                r.nextHint = "再连续记录 " + left + " 天，解锁「" + streakNames[i] + "」";
                break;
            }
        }
        if (r.nextHint.startsWith("全部成就")) {
            if (r.weekWorkoutDays < 3) {
                r.nextHint = "本周再运动 " + (3 - r.weekWorkoutDays) + " 天，解锁「本周运动达人」";
            }
        }
        return r;
    }

    private static Calendar parse(String ds) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try {
            c.set(Calendar.YEAR, Integer.parseInt(ds.substring(0, 4)));
            c.set(Calendar.MONTH, Integer.parseInt(ds.substring(5, 7)) - 1);
            c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(ds.substring(8, 10)));
        } catch (Exception ignored) {}
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static String format(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static long dayNumber(String ds) {
        return parse(ds).getTimeInMillis() / 86400000L;
    }
}
