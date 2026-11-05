package art.qelora.welltrack.health;

import java.util.ArrayList;
import java.util.List;

/**
 * 健康洞察引擎 (规则版): 全部基于小米运动健康同步的数据 + 体重记录计算,
 * 不依赖后端新接口。目标口径与小米/通用健康建议一致:
 * 步数 8000 步、活动消耗 400 千卡、运动 30 分钟、睡眠 8 小时。
 */
public class Insights {
    public static final int STEP_GOAL = 8000;
    public static final int CAL_GOAL = 400;
    public static final int WORKOUT_GOAL = 30;
    public static final double SLEEP_GOAL = 8.0;

    public static class Day {
        public int steps;
        public int calories;       // 活动消耗 kcal
        public double sleepHours;  // 0 表示无数据
        public int workoutMinutes;
    }

    public static class Week {
        public double avgSteps;
        public double avgSleep;        // 0 表示无数据
        public int workoutDays;        // 本周运动天数
        public int workoutMinutes;     // 本周运动总分钟
        public double weightChange7d;  // 观察窗口内体重变化 kg (首页为近7天, 趋势页为所选周期); NaN 表示无数据
        public boolean hasData;
    }

    public enum Level { GOOD, TIP, WARN }

    public static class Insight {
        public final Level level;
        public final String title;
        public final String body;
        public Insight(Level level, String title, String body) {
            this.level = level; this.title = title; this.body = body;
        }
    }

    /* ================= v3.0 多维评分 =================
     * 旧版只有步数/消耗/运动/睡眠四项且不透明, 用户反馈"评分不好"。
     * 新版五维分项、每项给出口径与一句话说明, 缺数据的维度不计分 (不惩罚),
     * 总分按可用维度等比折算到 100。首页 Hero 展示总分环 + 分项条 + 最弱项提示。
     */

    public static final int WATER_GOAL_ML = 2000;
    public static final int DIET_GOAL_KCAL = 2000;

    /** 今日完整输入: 在 Day 基础上补饮水与饮食 (活动/运动/睡眠沿用 Day 字段) */
    public static class DayFull extends Day {
        public int waterMl;        // 今日饮水 ml
        public int dietKcal = -1;  // 今日饮食摄入 kcal, -1 表示未记录
        public int dietCount;      // 今日饮食条数
    }

    public static class Part {
        public final String name;
        public final int score;    // 本项得分 (四舍五入)
        public final int max;      // 本项满分
        public final boolean hasData;
        public final String hint;  // 一句话口径说明
        public Part(String name, int score, int max, boolean hasData, String hint) {
            this.name = name; this.score = score; this.max = max;
            this.hasData = hasData; this.hint = hint;
        }
    }

    public static class ScoreResult {
        public int total;                       // 0-100 (按可用维度折算)
        public final List<Part> parts = new ArrayList<>();
        public String weakHint = "";            // 最弱可用维度的一句话
        public String strongHint = "";          // 最强可用维度的一句话
    }

    public static ScoreResult scoreFull(DayFull d) {
        ScoreResult r = new ScoreResult();
        // 活动 30: 步数 18 + 活动消耗 12
        double act = Math.min(1, d.steps / (double) STEP_GOAL) * 18
                + Math.min(1, d.calories / (double) CAL_GOAL) * 12;
        boolean actHas = d.steps > 0 || d.calories > 0;
        r.parts.add(new Part("活动", (int) Math.round(act), 30, actHas,
                actHas ? ("步数 " + d.steps + " · 消耗 " + d.calories + " 千卡")
                        : "暂无手环活动数据，不计分"));
        // 运动 15: 今日运动分钟 / 30
        double wo = Math.min(1, d.workoutMinutes / (double) WORKOUT_GOAL) * 15;
        r.parts.add(new Part("运动", (int) Math.round(wo), 15, d.workoutMinutes > 0,
                d.workoutMinutes > 0 ? ("今日运动 " + d.workoutMinutes + " 分钟")
                        : "今天还没运动，去打卡补上"));
        // 睡眠 20: 小时 / 8 (超过 8 按满分; 无数据不计分)
        boolean slHas = d.sleepHours > 0;
        double sl = slHas ? Math.min(1, d.sleepHours / SLEEP_GOAL) * 20 : 0;
        r.parts.add(new Part("睡眠", (int) Math.round(sl), 20, slHas,
                slHas ? ("昨晚 " + trim(d.sleepHours) + " 小时，目标 8 小时")
                        : "暂无睡眠数据，不计分"));
        // 饮水 15: ml / 2000
        double wa = Math.min(1, d.waterMl / (double) WATER_GOAL_ML) * 15;
        r.parts.add(new Part("饮水", (int) Math.round(wa), 15, d.waterMl > 0,
                d.waterMl > 0 ? ("已喝 " + d.waterMl + " / " + WATER_GOAL_ML + " ml")
                        : "今天还没记饮水"));
        // 饮食 20: 已记录才计分; 按摄入与目标带 (85%~110%) 的接近程度
        boolean diHas = d.dietKcal > 0;
        double di = 0;
        if (diHas) {
            double ratio = d.dietKcal / (double) DIET_GOAL_KCAL;
            double closeness;
            if (ratio >= 0.85 && ratio <= 1.1) closeness = 1;
            else if (ratio < 0.85) closeness = ratio / 0.85;
            else closeness = Math.max(0, 1 - (ratio - 1.1) / 0.9);
            di = closeness * 20;
        }
        r.parts.add(new Part("饮食", (int) Math.round(di), 20, diHas,
                diHas ? ("已记 " + d.dietCount + " 项 · " + d.dietKcal + " 千卡")
                        : "未记录饮食，不计分"));
        int sum = 0, avail = 0;
        Part weak = null, strong = null;
        for (Part p : r.parts) {
            if (!p.hasData) continue;
            sum += p.score; avail += p.max;
            double frac = p.max == 0 ? 0 : p.score / (double) p.max;
            if (weak == null || frac < weak.score / (double) Math.max(1, weak.max)) weak = p;
            if (strong == null || frac > strong.score / (double) Math.max(1, strong.max)) strong = p;
        }
        r.total = avail == 0 ? 0 : (int) Math.round(sum / (double) avail * 100);
        if (weak != null) r.weakHint = weak.name + "：" + weak.hint;
        if (strong != null) r.strongHint = strong.name + "：" + strong.hint;
        return r;
    }

    /** 综合健康分 0-100: 步数30 + 消耗30 + 运动20 + 睡眠20, 无睡眠数据时按其余项等比 */
    public static int score(Day d) {
        double s = Math.min(1, d.steps / (double) STEP_GOAL) * 30;
        double c = Math.min(1, d.calories / (double) CAL_GOAL) * 30;
        double w = Math.min(1, d.workoutMinutes / (double) WORKOUT_GOAL) * 20;
        if (d.sleepHours <= 0) {
            return (int) Math.round((s + c + w) / 80.0 * 100);
        }
        double sl = Math.min(1, d.sleepHours / SLEEP_GOAL) * 20;
        return (int) Math.round(s + c + w + sl);
    }

    public static String scoreLabel(int score) {
        if (score >= 85) return "元气满满";
        if (score >= 65) return "状态不错";
        if (score >= 40) return "稳步提升中";
        return "今天多关照自己";
    }

    /** 消耗达标文案: 还差多少千卡, 约等于步行多少分钟 (按 100 步/分钟、约 0.04 kcal/步估算) */
    public static String calorieHint(int calories) {
        if (calories >= CAL_GOAL) return "已达标 🎉 超出目标 " + (calories - CAL_GOAL) + " 千卡";
        int left = CAL_GOAL - calories;
        int walkMin = Math.max(5, (int) Math.round(left / 4.0));
        return "还差 " + left + " 千卡达标 · 约步行 " + walkMin + " 分钟可补足";
    }

    /**
     * 生成今日洞察与锻炼建议 (最多 4 条, 按重要性排序)。
     * todayPlan: 今日周计划安排的活动名 (可为 null), 用于给出具体建议。
     */
    public static List<Insight> recommendations(Day today, Week week, String todayPlan) {
        List<Insight> out = new ArrayList<>();

        // 1) 消耗/步数达标情况
        if (today.calories >= CAL_GOAL && today.steps >= STEP_GOAL) {
            out.add(new Insight(Level.GOOD, "今日消耗已达标",
                    "活动消耗 " + today.calories + " 千卡、步数 " + today.steps + " 步, 双双达标。晚上做 5 分钟拉伸, 帮肌肉恢复。"));
        } else if (today.calories > 0) {
            int left = CAL_GOAL - today.calories;
            int walkMin = Math.max(5, (int) Math.round(Math.max(0, left) / 4.0));
            String planHint = todayPlan != null && !todayPlan.isEmpty()
                    ? "今天的计划是「" + todayPlan + "」, 完成后大约就能补齐。" : "晚饭后散步是个轻松的补法。";
            out.add(new Insight(Level.TIP, "消耗还差 " + Math.max(0, left) + " 千卡",
                    "目前活动消耗 " + today.calories + "/" + CAL_GOAL + " 千卡。再步行约 " + walkMin + " 分钟即可达标, " + planHint));
        }

        // 2) 睡眠
        if (today.sleepHours > 0 && today.sleepHours < 6.5) {
            out.add(new Insight(Level.WARN, "昨晚睡眠偏少",
                    "只睡了 " + trim(today.sleepHours) + " 小时。今天建议把训练强度降一档, 以散步、拉伸为主, 今晚提前 30 分钟放下手机。"));
        } else if (week != null && week.avgSleep > 0 && week.avgSleep < 7) {
            out.add(new Insight(Level.TIP, "本周平均睡眠不足 7 小时",
                    "本周平均 " + trim(week.avgSleep) + " 小时。睡眠不足会拖慢代谢, 优先把作息补回来, 比加练更划算。"));
        }

        // 3) 本周运动频次
        if (week != null && week.hasData && week.workoutDays < 3) {
            out.add(new Insight(Level.TIP, "本周运动 " + week.workoutDays + " 次, 还差 " + (3 - week.workoutDays) + " 次",
                    "建议本周再补 " + (3 - week.workoutDays) + " 次 30 分钟中等强度运动 (快走、骑行、游泳都可以), 心肺和代谢的收益主要来自规律, 不是单次强度。"));
        } else if (week != null && week.hasData && week.workoutDays >= 5) {
            out.add(new Insight(Level.GOOD, "本周运动很规律",
                    "已运动 " + week.workoutDays + " 天、累计 " + week.workoutMinutes + " 分钟。记得留 1-2 天做恢复, 避免连续高强度。"));
        }

        // 4) 体重趋势
        if (week != null && !Double.isNaN(week.weightChange7d)) {
            double ch = week.weightChange7d;
            if (ch <= -1.0) {
                out.add(new Insight(Level.WARN, "体重下降有点快",
                        "近期下降 " + trim(-ch) + " kg, 节奏偏快。快于每周 1 kg 时容易掉肌肉, 建议适当增加蛋白质和力量训练, 把节奏放稳。"));
            } else if (ch >= 1.0) {
                out.add(new Insight(Level.TIP, "体重近期上升 " + trim(ch) + " kg",
                        "先别紧张: 先看腰围和连续趋势再判断。如果伴随消耗连续未达标, 本周把每日步数目标先提到 8000 步。"));
            } else if (Math.abs(ch) >= 0.2) {
                out.add(new Insight(Level.GOOD, "体重稳步变化中",
                        "近期" + (ch < 0 ? "下降 " + trim(-ch) : "上升 " + trim(ch)) + " kg, 幅度温和, 在健康节奏内, 继续保持。"));
            }
        }

        // 5) 久坐 (步数极低且已有数据)
        if (today.steps > 0 && today.steps < 3000) {
            out.add(new Insight(Level.WARN, "今天活动量偏低",
                    "目前只有 " + today.steps + " 步。每坐 1 小时起来走 3-5 分钟, 全天多走 2000 步并不难, 对血糖和腰背都友好。"));
        }

        if (out.size() > 4) return new ArrayList<>(out.subList(0, 4));
        return out;
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(java.util.Locale.US, "%.1f", v);
    }
}
