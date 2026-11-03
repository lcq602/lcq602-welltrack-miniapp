package art.qelora.welltrack.health;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import art.qelora.welltrack.api.ApiClient;

/**
 * 本周计划的统一数据源 (首页训练卡 / 周条与计划页共用, 避免重复请求):
 *  1) 本地缓存 (SharedPreferences, 按本周一日期命中) 直接回调;
 *  2) 未命中先取 Worker 侧缓存 (GET /api/v1/agent/plan), 404/失败再组装数据上下文调 AI 生成;
 *  3) AI 全失败时用本地规则 (Insights 口径 + 活动模板) 兜底生成同结构计划, source="local"。
 * 结果结构与接口一致: {plan:{week_goal, targets, days[7], tips}, week_start, source}。
 * 同一时间的并发请求合并为一次拉取。
 */
public class PlanStore {
    private static final String KEY_PLAN = "plan_cache_json";
    private static final String KEY_WEEK = "plan_cache_week";
    public static final String[] DAY_KEYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onPlan(JSONObject result);
        void onError(String message);
    }

    /* 并发合并: 一次拉取在途时, 后续请求排队等同一结果 */
    private static boolean fetching = false;
    private static final List<Listener> pending = new ArrayList<>();

    public static void get(Context ctx, boolean force, Listener listener) {
        Context app = ctx.getApplicationContext();
        if (!force) {
            JSONObject cached = readCache(app);
            if (cached != null) {
                MAIN.post(() -> listener.onPlan(cached));
                return;
            }
        }
        synchronized (PlanStore.class) {
            pending.add(listener);
            if (fetching) return;
            fetching = true;
        }
        startFetch(app, force);
    }

    /* ---------------- 拉取链路 ---------------- */

    private static void startFetch(Context app, boolean force) {
        ApiClient api = ApiClient.get(app);
        if (force) {
            buildContextThenGenerate(app, api, true, null, null);
            return;
        }
        api.agentGetPlan(new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                finish(app, data);
            }

            @Override public void onError(String message) {
                // Worker 无缓存 (404) 或网络失败: 组装上下文后生成
                buildContextThenGenerate(app, api, false, null, null);
            }
        });
    }

    /** 先拉 dashboard + 本周 analytics 组装上下文 (组装口径与 AgentPage 一致), 再调 AI 生成; AI 失败走本地兜底 */
    private static void buildContextThenGenerate(Context app, ApiClient api, boolean force,
                                                 JSONObject dashIn, JSONObject weekIn) {
        final JSONObject[] dash = {dashIn};
        final JSONObject[] week = {weekIn};
        final boolean[] done = {dashIn != null, weekIn != null};
        final Runnable[] proceed = new Runnable[1];
        proceed[0] = () -> {
            if (!done[0] || !done[1]) return;
            String context = buildContext(dash[0], week[0]);
            api.agentPlan(context, force, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    finish(app, data);
                }

                @Override public void onError(String message) {
                    try {
                        JSONObject local = new JSONObject();
                        local.put("plan", buildLocalPlan(dash[0], week[0]));
                        local.put("week_start", weekStartString());
                        local.put("source", "local");
                        finish(app, local);
                    } catch (Exception e) {
                        fail(message);
                    }
                }
            });
        };
        if (dashIn == null) {
            api.request("/api/v1/dashboard", "GET", null, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    dash[0] = data;
                    done[0] = true;
                    proceed[0].run();
                }

                @Override public void onError(String message) {
                    done[0] = true;
                    proceed[0].run();
                }
            });
        }
        if (weekIn == null) {
            api.request("/api/v1/analytics?period=week", "GET", null, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    week[0] = data;
                    done[1] = true;
                    proceed[0].run();
                }

                @Override public void onError(String message) {
                    done[1] = true;
                    proceed[0].run();
                }
            });
        }
        proceed[0].run();
    }

    private static void finish(Context app, JSONObject result) {
        try {
            if (!result.has("week_start")) result.put("week_start", weekStartString());
            if (!result.has("source")) result.put("source", "ai");
        } catch (Exception ignored) {}
        SharedPreferences prefs = app.getSharedPreferences("welltrack", Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PLAN, result.toString())
                .putString(KEY_WEEK, result.optString("week_start", weekStartString()))
                .apply();
        List<Listener> listeners;
        synchronized (PlanStore.class) {
            listeners = new ArrayList<>(pending);
            pending.clear();
            fetching = false;
        }
        for (Listener l : listeners) l.onPlan(result);
    }

    private static void fail(String message) {
        List<Listener> listeners;
        synchronized (PlanStore.class) {
            listeners = new ArrayList<>(pending);
            pending.clear();
            fetching = false;
        }
        String m = (message == null || message.isEmpty()) ? "计划生成失败" : message;
        for (Listener l : listeners) l.onError(m);
    }

    private static JSONObject readCache(Context app) {
        SharedPreferences prefs = app.getSharedPreferences("welltrack", Context.MODE_PRIVATE);
        String week = prefs.getString(KEY_WEEK, "");
        if (week == null || !week.equals(weekStartString())) return null;
        String json = prefs.getString(KEY_PLAN, "");
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject result = new JSONObject(json);
            return result.has("plan") ? result : null;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 数据上下文 (与 AgentPage 组装口径一致) ---------------- */

    static String buildContext(JSONObject dashData, JSONObject weekData) {
        List<String> parts = new ArrayList<>();
        if (dashData != null) {
            JSONObject profile = dashData.optJSONObject("profile");
            if (profile != null) {
                StringBuilder sb = new StringBuilder("用户目标: ");
                sb.append(profile.optString("goal", ""));
                sb.append(" | 身高 ").append(fmt(profile.optDouble("height_cm", 0))).append("cm");
                Double cw = num(dashData, "current_weight");
                sb.append(" | 当前体重 ").append(cw != null ? fmt(cw) : "--").append("kg");
                Double tw = num(dashData, "target_weight");
                if (tw == null) tw = num(profile, "target_weight");
                sb.append(" | 目标体重 ").append(tw != null ? fmt(tw) : "--").append("kg");
                sb.append(" | 目标进度 ").append(dashData.optInt("progress", 0)).append("%");
                parts.add(sb.toString());
            }
            JSONObject mi = dashData.optJSONObject("mihealth");
            boolean connected = mi != null && mi.optBoolean("connected", false);
            JSONObject today = mi != null ? mi.optJSONObject("today") : null;
            if (connected && today != null) {
                StringBuilder sb = new StringBuilder("今日小米数据: 步数 ");
                sb.append(today.optInt("steps", 0));
                sb.append(", 活动消耗 ").append(fmt(today.optDouble("calories", 0))).append(" 千卡");
                Double sleep = num(today, "sleep_hours");
                sb.append(", 睡眠 ").append(sleep != null ? fmt(sleep) : "--").append(" 小时");
                sb.append(", 运动 ").append(fmt(today.optDouble("workout_minutes", 0))).append(" 分钟 (已连接)");
                parts.add(sb.toString());
            } else {
                parts.add("今日小米数据: 未连接小米运动健康");
            }
        }
        if (weekData != null) {
            StringBuilder sb = new StringBuilder("本周: 累计步数 ");
            sb.append(fmt(weekData.optDouble("total_steps", 0)));
            sb.append(" (日均 ").append(fmt(weekData.optDouble("average_steps", 0))).append(")");
            sb.append(", 累计消耗 ").append(fmt(weekData.optDouble("total_calories", 0))).append(" 千卡");
            Double avgSleep = num(weekData, "average_sleep");
            sb.append(", 平均睡眠 ").append(avgSleep != null ? fmt(avgSleep) : "--").append(" 小时");
            sb.append(", 运动 ").append(weekData.optInt("exercise_count", 0)).append(" 次");
            parts.add(sb.toString());
        }
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) all.append(" | ");
            all.append(parts.get(i));
        }
        return all.toString();
    }

    /* ---------------- 本地规则兜底 ---------------- */

    private static JSONObject buildLocalPlan(JSONObject dash, JSONObject week) throws Exception {
        String goal = "weight_loss";
        if (dash != null) {
            JSONObject p = dash.optJSONObject("profile");
            if (p != null) goal = p.optString("goal", goal);
        }
        double avgSteps = week != null ? week.optDouble("average_steps", 0) : 0;
        JSONArray weekDays = week != null ? week.optJSONArray("days") : null;
        int dayCount = weekDays != null && weekDays.length() > 0 ? weekDays.length() : 7;
        double avgCal = week != null ? week.optDouble("total_calories", 0) / dayCount : 0;
        Double avgSleep = week != null ? num(week, "average_sleep") : null;

        int stepsT = converge(avgSteps, Insights.STEP_GOAL, 3000, 12000);
        int calT = converge(avgCal, Insights.CAL_GOAL, 150, 600);
        double sleepT = (avgSleep != null && avgSleep > 0)
                ? Math.min(Insights.SLEEP_GOAL, Math.round((avgSleep + 0.5) * 2) / 2.0)
                : Insights.SLEEP_GOAL;

        JSONObject targets = new JSONObject();
        targets.put("steps", stepsT);
        targets.put("calories_kcal", calT);
        targets.put("exercise_minutes", Insights.WORKOUT_GOAL);
        targets.put("sleep_hours", sleepT);

        String[] template = templateFor(goal);
        JSONArray days = new JSONArray();
        for (int i = 0; i < 7; i++) {
            String activity = template[i];
            boolean rest = "休息".equals(activity);
            JSONObject d = new JSONObject();
            d.put("key", DAY_KEYS[i]);
            d.put("focus", activity);
            d.put("actions", actionsFor(activity));
            d.put("steps_target", rest ? round100(stepsT * 0.7) : stepsT);
            d.put("calories_target", rest ? (int) Math.round(calT * 0.7) : calT);
            days.put(d);
        }

        JSONObject plan = new JSONObject();
        plan.put("week_goal", "以「" + goalLabel(goal) + "」为方向, 本周把日均步数稳步提到 "
                + stepsT + " 步, 消耗向 " + calT + " 千卡靠拢");
        plan.put("targets", targets);
        plan.put("days", days);
        plan.put("tips", new JSONArray(localTips(dash, week)));
        return plan;
    }

    /** 近 7 天日均上浮约 15% 后, 与标准目标取中间值并收敛到合理区间 */
    private static int converge(double avg, int standard, int lo, int hi) {
        if (avg <= 0) return standard;
        double v = (avg * 1.15 + standard) / 2.0;
        long rounded = Math.round(v / 10.0) * 10;
        return (int) Math.max(lo, Math.min(hi, rounded));
    }

    private static int round100(double v) {
        return (int) (Math.round(v / 100.0) * 100);
    }

    private static String goalLabel(String goal) {
        if ("fat_loss".equals(goal)) return "减脂";
        if ("maintenance".equals(goal)) return "维持";
        if ("healthy_habits".equals(goal)) return "健康习惯";
        return "减重";
    }

    static String[] templateFor(String goal) {
        if ("fat_loss".equals(goal)) return new String[]{"力量训练", "有氧运动", "休息", "力量训练", "散步", "有氧运动", "休息"};
        if ("maintenance".equals(goal)) return new String[]{"散步", "力量训练", "休息", "有氧运动", "休息", "自由活动", "休息"};
        if ("healthy_habits".equals(goal)) return new String[]{"散步", "拉伸", "休息", "有氧运动", "拉伸", "自由活动", "休息"};
        return new String[]{"散步", "休息", "有氧运动", "休息", "散步", "自由活动", "休息"}; // weight_loss
    }

    private static JSONArray actionsFor(String activity) throws Exception {
        JSONArray arr = new JSONArray();
        switch (activity) {
            case "力量训练":
                arr.put(action("深蹲", "徒手深蹲 3 组 × 12 次, 组间休息 60 秒", 15));
                arr.put(action("俯卧撑", "俯卧撑 3 组 × 8-10 次, 吃力时可改跪姿", 10));
                arr.put(action("拉伸放松", "全身拉伸 5 分钟, 帮助肌肉恢复", 5));
                break;
            case "有氧运动":
                arr.put(action("有氧快走", "快走或慢跑, 保持能说短句、微微出汗的强度", 30));
                arr.put(action("运动后拉伸", "腿部与肩颈各拉伸 1 分钟, 缓解紧绷", 10));
                break;
            case "拉伸":
                arr.put(action("全身拉伸", "颈肩、腰背、腿后侧逐个拉伸, 每个动作保持 30 秒", 15));
                arr.put(action("轻松散步", "慢速散步, 以放松呼吸为主", 20));
                break;
            case "休息":
                arr.put(action("充分休息", "今天不安排训练, 保证睡眠, 让身体恢复", -1));
                break;
            case "自由活动":
                arr.put(action("户外活动", "骑行、散步或家务都可以, 累计活动约 30 分钟", 30));
                break;
            default: // 散步
                arr.put(action("快走散步", "晚饭后快走, 保持能说话、微微出汗的强度", 30));
                arr.put(action("碎片步行", "久坐每小时起身走 3-5 分钟, 慢慢攒够步数", 10));
                break;
        }
        return arr;
    }

    private static JSONObject action(String title, String detail, int minutes) throws Exception {
        JSONObject a = new JSONObject();
        a.put("title", title);
        a.put("detail", detail);
        if (minutes > 0) a.put("minutes", minutes);
        return a;
    }

    /** 建议文案优先取 Insights 规则洞察, 不足时补默认贴士 */
    private static List<String> localTips(JSONObject dash, JSONObject week) {
        List<String> tips = new ArrayList<>();
        Insights.Day today = new Insights.Day();
        if (dash != null) {
            JSONObject mi = dash.optJSONObject("mihealth");
            JSONObject t = mi != null ? mi.optJSONObject("today") : null;
            if (t != null) {
                today.steps = t.optInt("steps", 0);
                today.calories = (int) Math.round(t.optDouble("calories", 0));
                Double s = num(t, "sleep_hours");
                today.sleepHours = s != null ? s : 0;
                today.workoutMinutes = (int) Math.round(t.optDouble("workout_minutes", 0));
            }
        }
        Insights.Week w = new Insights.Week();
        if (week != null) {
            w.hasData = true;
            w.avgSteps = week.optDouble("average_steps", 0);
            Double s = num(week, "average_sleep");
            w.avgSleep = s != null ? s : 0;
            w.workoutDays = week.optInt("exercise_count", 0);
            JSONArray days = week.optJSONArray("days");
            int minutes = 0;
            if (days != null) {
                for (int i = 0; i < days.length(); i++) {
                    JSONObject d = days.optJSONObject(i);
                    if (d != null) minutes += (int) Math.round(d.optDouble("workout_minutes", 0));
                }
            }
            w.workoutMinutes = minutes;
            w.weightChange7d = Double.NaN;
        }
        for (Insights.Insight ins : Insights.recommendations(today, w, null)) {
            tips.add(ins.body);
            if (tips.size() >= 3) break;
        }
        String[] defaults = {
                "晚饭后步行 20-30 分钟, 是补齐步数最稳的办法",
                "睡前一小时放下手机, 争取睡满 7-8 小时",
                "每天分次喝水 1500-2000ml, 运动后及时补充",
        };
        for (String d : defaults) {
            if (tips.size() >= 3) break;
            if (!tips.contains(d)) tips.add(d);
        }
        return tips;
    }

    /* ---------------- 日期工具 ---------------- */

    /** 本周一 (本地日期), 与设备时区一致 */
    public static Calendar mondayCalendar() {
        Calendar c = Calendar.getInstance();
        int dow = c.get(Calendar.DAY_OF_WEEK); // 1=周日 .. 7=周六
        int sinceMonday = (dow + 5) % 7;
        c.add(Calendar.DAY_OF_YEAR, -sinceMonday);
        return c;
    }

    public static String weekStartString() {
        return dateString(mondayCalendar());
    }

    /** 本周一到周日的 7 个日期字符串 (yyyy-MM-dd) */
    public static List<String> weekDates() {
        List<String> out = new ArrayList<>();
        Calendar c = mondayCalendar();
        for (int i = 0; i < 7; i++) {
            out.add(dateString(c));
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    /** 今天是本周第几天 (0=周一 .. 6=周日) */
    public static int todayIndex() {
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return (dow + 5) % 7;
    }

    public static String dateString(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 取计划里第 idx 天 (0=周一) 的安排, 越界/缺失返回 null */
    public static JSONObject dayAt(JSONObject plan, int idx) {
        if (plan == null) return null;
        JSONArray days = plan.optJSONArray("days");
        if (days == null || idx < 0 || idx >= days.length()) return null;
        return days.optJSONObject(idx);
    }

    /* ---------------- 工具 ---------------- */

    private static Double num(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        double d = o.optDouble(key, Double.NaN);
        return Double.isNaN(d) ? null : d;
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String fmt(Double d) {
        return d == null ? "--" : fmt(d.doubleValue());
    }
}
