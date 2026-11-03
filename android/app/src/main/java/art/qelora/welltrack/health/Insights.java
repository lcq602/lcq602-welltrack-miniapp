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
