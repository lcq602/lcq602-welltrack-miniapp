package art.qelora.welltrack.pages;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import art.qelora.welltrack.R;
import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.health.Insights;
import art.qelora.welltrack.health.PlanStore;
import art.qelora.welltrack.health.Reminders;
import art.qelora.welltrack.health.Streak;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;
import art.qelora.welltrack.ui.ScoreRing;

/**
 * 首页 v3.0 (深色荧光绿风格): 问候头 → AI 今日评分 Hero (五维分项 + AI 点评) →
 *       快捷操作 (拍照记餐/记食物/记体重/喝水) → 今日训练大卡 → 运动打卡 →
 *       全部身体数据 → 小米手环活动 → 饮水 → 本周计划 → 打卡/洞察/趋势。
 * 计划数据经 PlanStore (与计划页共用缓存)。
 * 数据: GET /api/v1/dashboard; 建档: POST /api/v1/profile; 同步: POST /api/v1/integrations/mihealth/sync。
 */
public class IndexPage extends BasePage {
    private static final String[] GOAL_KEYS = {"weight_loss", "fat_loss", "maintenance", "healthy_habits"};
    private static final String[] GOAL_LABELS = {"减重", "减脂", "维持", "健康习惯"};
    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};
    private static final String[] PLAN_KEYS_BY_DOW = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    private LinearLayout body;

    // 接口与计算状态 (字段口径与 index.js 的 setData 一致)
    private JSONObject data;
    private boolean configured = false;
    private boolean loading = true;
    private String loadError = "";
    private boolean syncing = false;

    private static class TrendDay {
        String recordDate, weekday;
        Double weight;
        boolean hasRecord, isToday;
        double barHeight;
    }

    private final List<TrendDay> trend = new ArrayList<>();
    private String diffLabel = "";
    private String bmiStatus = "健康正常";
    private String bmiPillKind = "emerald";
    private String remainingWeight = "";
    private String todayFullLabel = "";
    private String goalLabelText = "健康计划";
    private int goalProgressVal = 0;
    private boolean showGoalProgress = true;
    private String progressLabel = "目标进度";
    private JSONObject mihealth, miToday;
    private final List<String> todos = new ArrayList<>();

    // v2 洞察引擎输入 (today 取 mihealth 当日, week 由近 7 天 records 计算)
    private final Insights.Day todayDay = new Insights.Day();
    private final Insights.Week weekStats = new Insights.Week();
    private String todayPlan = null;
    private boolean miConnected = false;
    private final Map<String, JSONObject> recordByDateMap = new HashMap<>();

    // v2.6 连续打卡 (Streak): 首页展示, 我的页展示徽章墙
    private Streak.Result streakResult;
    private boolean streakLoading = false;

    // v2.2 本周计划 (PlanStore): 训练大卡 / 三瓷砖 / 周条 / 今日摘要共用
    // v3.0 首页改版: 多维评分 / AI 点评 / 今日运动打卡
    private final Insights.DayFull todayFull = new Insights.DayFull();
    private Insights.ScoreResult scoreResult;
    private String aiVerdict = "";
    private boolean aiVerdictLoading = false;
    private int todayExerciseMinutes = 0;
    private boolean exLoading = false;
    private int checkinSportIdx = 0;
    private int checkinMinutes = 30;
    private boolean checkinSaving = false;
    private static final String[] CHECKIN_SPORTS = {"跑步", "快走", "骑行", "游泳", "瑜伽", "力量训练"};
    private static final int[] CHECKIN_MINS = {15, 30, 45, 60};

    private JSONObject homePlan;         // plan 对象本身, 未就绪为 null
    private String homePlanSig = "";     // week_start+今日focus+source 签名, 防重复渲染
    private boolean homePlanRequested = false;

    // 建档表单状态
    private String fHeight = "", fCurrent = "", fTarget = "", fStartDate = "", fTargetDate = "";
    private int fGoalIndex = 0;
    private boolean saving = false;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            load();
            refreshHandler.postDelayed(this, 60000);
        }
    };

    public IndexPage(Context ctx) { super(ctx); }

    @Override
    protected void build() {
        body = Ui.column(ctx());
        content.addView(body);
        render();
    }

    @Override
    public void onShow() {
        super.onShow();
        load();
        loadStreak();
        refreshHandler.removeCallbacks(refreshLoop);
        refreshHandler.postDelayed(refreshLoop, 60000);
    }

    /* v2.6 打卡数据: 随页面显示拉取一次 (60 秒定时刷新不重复拉, 避免刷接口) */
    private void loadStreak() {
        if (streakLoading) return;
        streakLoading = true;
        Streak.load(ctx(), new Streak.Listener() {
            @Override public void onResult(Streak.Result result) {
                streakLoading = false;
                streakResult = result;
                if (data != null && configured) render();
            }
            @Override public void onError(String message) {
                streakLoading = false;
                // 打卡卡片静默缺席, 不打扰用户
            }
        });
    }

    @Override
    public void onHide() {
        refreshHandler.removeCallbacks(refreshLoop);
    }

    /* ---------------- 数据加载与计算 (对照 index.js load()) ---------------- */

    private void load() {
        loading = (data == null);
        loadError = "";
        if (data == null) render();
        api().request("/api/v1/dashboard", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject res, int status) {
                data = res;
                configured = res.optBoolean("configured", false);
                compute();
                loading = false;
                homePlanRequested = false; // 计划走 PlanStore 缓存复检, 签名不变则不重复渲染
                render();
                loadTodayExercises();
                loadAiVerdict();
            }

            @Override public void onError(String message) {
                loading = false;
                loadError = (message == null || message.isEmpty()) ? "暂时无法连接健康服务" : message;
                render();
            }
        });
    }

    private void compute() {
        // 趋势: 近 7 天按日期对齐 records
        JSONArray records = data.optJSONArray("records");
        Map<String, Double> weightByDate = new HashMap<>();
        Map<String, Boolean> recordByDate = new HashMap<>();
        Map<String, JSONObject> recordMap = new HashMap<>();
        List<Double> values = new ArrayList<>();
        if (records != null) {
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null) continue;
                String d = r.optString("record_date", "");
                Double w = num(r, "weight");
                recordByDate.put(d, Boolean.TRUE);
                recordMap.put(d, r);
                weightByDate.put(d, w);
                if (w != null) values.add(w);
            }
        }
        recordByDateMap.clear();
        recordByDateMap.putAll(recordMap);
        double min = 0, max = 1;
        if (!values.isEmpty()) {
            min = Double.MAX_VALUE; max = -Double.MAX_VALUE;
            for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        }
        Calendar now = Calendar.getInstance();
        todayFullLabel = (now.get(Calendar.MONTH) + 1) + "月" + now.get(Calendar.DAY_OF_MONTH)
                + "日 周" + WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1];
        trend.clear();
        List<String> windowDates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -(6 - i));
            String ds = String.format(Locale.US, "%04d-%02d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            windowDates.add(ds);
            TrendDay t = new TrendDay();
            t.recordDate = ds;
            t.weekday = WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
            t.hasRecord = recordByDate.containsKey(ds);
            t.weight = weightByDate.get(ds);
            t.isToday = i == 6;
            if (t.hasRecord && t.weight != null) {
                t.barHeight = Math.max(20, 32 + ((t.weight - min) / Math.max(1, max - min)) * 90);
            } else {
                t.barHeight = 8;
            }
            trend.add(t);
        }
        // 较昨日波动
        diffLabel = "";
        if (trend.get(6).weight != null && trend.get(5).weight != null) {
            double diff = Math.round((trend.get(6).weight - trend.get(5).weight) * 10.0) / 10.0;
            if (diff < 0) diffLabel = "↓ " + fmt(Math.abs(diff)) + " kg 较昨日";
            else if (diff > 0) diffLabel = "↑ " + fmt(diff) + " kg 较昨日";
            else diffLabel = "持平 较昨日";
        }
        // BMI 状态
        bmiStatus = "健康正常"; bmiPillKind = "emerald";
        Double bmi = num(data, "bmi");
        if (bmi != null && bmi > 0) {
            if (bmi < 18.5) { bmiStatus = "偏瘦"; bmiPillKind = "blue"; }
            else if (bmi <= 23.9) { bmiStatus = "健康正常"; bmiPillKind = "emerald"; }
            else if (bmi <= 27.9) { bmiStatus = "超重"; bmiPillKind = "amber"; }
            else { bmiStatus = "偏高"; bmiPillKind = "purple"; }
        }
        // 目标差值
        remainingWeight = "";
        Double cw = num(data, "current_weight");
        Double tw = num(data, "target_weight");
        if (cw != null && tw != null) {
            double rem = Math.round(Math.abs(cw - tw) * 10.0) / 10.0;
            remainingWeight = rem > 0 ? "还差 " + fmt(rem) + " kg" : "已达成目标";
        }
        // 目标与进度 (goal_progress 为 JS 现算值: 健康习惯看周覆盖, 其余看 progress)
        JSONObject profile = data.optJSONObject("profile");
        String goal = profile != null ? profile.optString("goal", "") : "";
        goalLabelText = "健康计划";
        for (int i = 0; i < GOAL_KEYS.length; i++) if (GOAL_KEYS[i].equals(goal)) goalLabelText = GOAL_LABELS[i];
        int rawProgress = "healthy_habits".equals(goal)
                ? data.optInt("weekly_completion", 0) : data.optInt("progress", 0);
        goalProgressVal = Math.min(100, Math.max(0, rawProgress));
        showGoalProgress = !"maintenance".equals(goal);
        progressLabel = "healthy_habits".equals(goal) ? "近7天记录覆盖" : "目标进度";
        // 今日周计划 (洞察建议用)
        todayPlan = null;
        if (profile != null) {
            JSONObject weekly = profile.optJSONObject("weekly_plan");
            if (weekly != null) {
                String v = weekly.optString(PLAN_KEYS_BY_DOW[now.get(Calendar.DAY_OF_WEEK) - 1], "");
                if (v != null && !v.isEmpty() && !"null".equals(v)) todayPlan = v;
            }
        }
        // 小米活力
        mihealth = data.optJSONObject("mihealth");
        if (mihealth == null) mihealth = new JSONObject();
        miToday = mihealth.optJSONObject("today");
        if (miToday == null) miToday = new JSONObject();
        miConnected = mihealth.optBoolean("connected", false);
        // 洞察引擎: 今日值
        todayDay.steps = miToday.optInt("steps", 0);
        todayDay.calories = (int) Math.round(miToday.optDouble("calories", 0));
        Double sl = num(miToday, "sleep_hours");
        todayDay.sleepHours = sl != null ? sl : 0;
        todayDay.workoutMinutes = (int) Math.round(miToday.optDouble("workout_minutes", 0));
        // 洞察引擎: 近 7 天统计 (与 trend 同一窗口与口径)
        computeWeekStats(windowDates, recordMap);
        // 待办
        todos.clear();
        JSONArray td = data.optJSONArray("todos");
        if (td != null) for (int i = 0; i < td.length(); i++) todos.add(td.optString(i, ""));
        buildScore();
    }

    /* v3.0: 组装多维评分输入 (活动/运动/睡眠取手环今日值, 运动分钟与今日打卡取大者) */
    private void buildScore() {
        todayFull.steps = todayDay.steps;
        todayFull.calories = todayDay.calories;
        todayFull.sleepHours = todayDay.sleepHours;
        todayFull.workoutMinutes = Math.max(todayDay.workoutMinutes, todayExerciseMinutes);
        todayFull.waterMl = todayWaterMl();
        JSONObject dt = data != null ? data.optJSONObject("diet_today") : null;
        if (dt != null && dt.optInt("count", 0) > 0) {
            todayFull.dietKcal = (int) Math.round(dt.optDouble("total_kcal", 0));
            todayFull.dietCount = dt.optInt("count", 0);
        } else {
            todayFull.dietKcal = -1;
            todayFull.dietCount = 0;
        }
        scoreResult = Insights.scoreFull(todayFull);
    }

    /* v3.0: 拉今日运动记录 (打卡状态与运动分钟的真实来源, 数组响应走文本通道) */
    private void loadTodayExercises() {
        if (exLoading) return;
        exLoading = true;
        final String today = todayString();
        api().requestText("/api/v1/exercises?start=" + today + "&end=" + today,
                new ApiClient.RawCallback() {
                    @Override public void onOk(String text, int status) {
                        exLoading = false;
                        try {
                            JSONArray arr = new JSONArray(text);
                            int sum = 0;
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject e = arr.optJSONObject(i);
                                if (e != null) sum += e.optInt("duration_minutes", 0);
                            }
                            if (sum != todayExerciseMinutes) {
                                todayExerciseMinutes = sum;
                                buildScore();
                                if (data != null && configured) render();
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override public void onError(String message) { exLoading = false; }
                });
    }

    /* v3.0: AI 点评 (智能体一句话点评, 按日缓存到本机; 失败静默, Hero 用短板提示兜底) */
    private void loadAiVerdict() {
        if (aiVerdictLoading || scoreResult == null || !configured) return;
        final SharedPreferences prefs = ctx().getSharedPreferences("welltrack", Context.MODE_PRIVATE);
        final String key = "ai_verdict_" + todayString();
        String cached = prefs.getString(key, "");
        if (cached != null && !cached.isEmpty()) { aiVerdict = cached; return; }
        aiVerdictLoading = true;
        StringBuilder sb = new StringBuilder();
        sb.append("今日健康分 ").append(scoreResult.total).append(" 分。");
        for (Insights.Part p : scoreResult.parts) sb.append(p.name).append("：").append(p.hint).append("；");
        sb.append("本周已运动 ").append(weekStats.workoutDays).append(" 天。");
        api().agentChat("请用一句话点评我今天的健康表现（30 字以内），再给一个今晚就能落实的小建议（30 字以内），合并成一句话回复，不要分点。",
                new JSONArray(), sb.toString(), new ApiClient.Callback() {
                    @Override public void onOk(JSONObject res, int status) {
                        aiVerdictLoading = false;
                        String reply = res.optString("reply", "").trim();
                        if (!reply.isEmpty()) {
                            if (reply.length() > 110) reply = reply.substring(0, 110);
                            aiVerdict = reply;
                            prefs.edit().putString(key, reply).apply();
                            if (data != null && configured) render();
                        }
                    }

                    @Override public void onError(String message) { aiVerdictLoading = false; }
                });
    }

    private void computeWeekStats(List<String> windowDates, Map<String, JSONObject> recordMap) {
        weekStats.avgSteps = 0;
        weekStats.avgSleep = 0;
        weekStats.workoutDays = 0;
        weekStats.workoutMinutes = 0;
        weekStats.weightChange7d = Double.NaN;
        weekStats.hasData = false;
        double stepsSum = 0; int stepsDays = 0;
        double sleepSum = 0; int sleepDays = 0;
        List<JSONObject> inWindow = new ArrayList<>();
        for (String ds : windowDates) {
            JSONObject r = recordMap.get(ds);
            if (r != null) inWindow.add(r);
        }
        Collections.sort(inWindow, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return a.optString("record_date", "").compareTo(b.optString("record_date", ""));
            }
        });
        Double firstWeight = null, lastWeight = null;
        for (JSONObject r : inWindow) {
            weekStats.hasData = true;
            Double st = num(r, "steps");
            if (st != null) { stepsSum += st; stepsDays++; }
            Double sh = num(r, "sleep_hours");
            if (sh != null && sh > 0) { sleepSum += sh; sleepDays++; }
            Double wm = num(r, "workout_minutes");
            if (wm != null && wm > 0) {
                weekStats.workoutDays++;
                weekStats.workoutMinutes += (int) Math.round(wm);
            }
            Double w = num(r, "weight");
            if (w != null) {
                if (firstWeight == null) firstWeight = w;
                lastWeight = w;
            }
        }
        if (stepsDays > 0) weekStats.avgSteps = stepsSum / stepsDays;
        if (sleepDays > 0) weekStats.avgSleep = sleepSum / sleepDays;
        if (firstWeight != null && lastWeight != null && inWindow.size() > 0
                && !firstWeight.equals(lastWeight)) {
            weekStats.weightChange7d = Math.round((lastWeight - firstWeight) * 10.0) / 10.0;
        } else if (firstWeight != null && lastWeight != null) {
            weekStats.weightChange7d = 0;
        }
    }

    private void syncMiHealth() {
        if (syncing) return;
        syncing = true;
        render();
        JSONObject bodyObj = new JSONObject();
        try { bodyObj.put("days", 14); } catch (Exception ignored) {}
        api().request("/api/v1/integrations/mihealth/sync", "POST", bodyObj, new ApiClient.Callback() {
            @Override public void onOk(JSONObject res, int status) {
                syncing = false;
                toast("手环数据已更新");
                load();
            }

            @Override public void onError(String message) {
                syncing = false;
                toast((message == null || message.isEmpty()) ? "同步遇到问题，请在“我的”页面查看连接" : message);
                render();
            }
        });
    }

    /* ---------------- 渲染 ---------------- */

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        if (data == null) {
            if (!loadError.isEmpty()) renderError();
            else renderLoading();
            return;
        }
        renderHead();
        if (!configured) {
            body.addView(Ui.bannerImage(ctx(), R.drawable.img_connect, 150, 24), Ui.matchWrap(14, ctx()));
            renderSetupForm();
        } else {
            renderDashboard();
        }
    }

    private void renderLoading() {
        LinearLayout box = Ui.column(ctx());
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(ctx(), 80), 0, 0);
        ProgressBar pb = new ProgressBar(ctx());
        box.addView(pb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(ctx(), "正在同步健康数据…", 14, Ui.SLATE, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t, Ui.matchWrap(14, ctx()));
        TextView s = Ui.text(ctx(), "WELLTRACK · 每一点都算数", 11, Ui.MUTED, false);
        s.setGravity(Gravity.CENTER);
        box.addView(s, Ui.matchWrap(6, ctx()));
        body.addView(box);
    }

    private void renderError() {
        LinearLayout box = Ui.column(ctx());
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(ctx(), 80), 0, 0);
        TextView mark = Ui.text(ctx(), "!", 28, Ui.RED, true);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.RED_SOFT);
        mark.setBackground(circle);
        int sz = Ui.dp(ctx(), 56);
        box.addView(mark, new LinearLayout.LayoutParams(sz, sz));
        TextView t = Ui.sectionTitle(ctx(), "暂时连接不上服务");
        t.setGravity(Gravity.CENTER);
        box.addView(t, Ui.matchWrap(14, ctx()));
        TextView e = Ui.text(ctx(), loadError, 13, Ui.MUTED, false);
        e.setGravity(Gravity.CENTER);
        box.addView(e, Ui.matchWrap(6, ctx()));
        android.widget.Button retry = Ui.primaryButton(ctx(), "重新连接");
        retry.setOnClickListener(v -> load());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 160), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx(), 16);
        box.addView(retry, lp);
        body.addView(box);
    }

    /* 问候头 (v2.2 参考风): 小字日期行 + 大字时段问候 + 右侧圆形头像 (去我的页) */
    private void renderHead() {
        LinearLayout head = Ui.row(ctx());
        LinearLayout left = Ui.column(ctx());
        Calendar now = Calendar.getInstance();
        String dateLine = "轻衡健康 · " + (now.get(Calendar.MONTH) + 1) + "月"
                + now.get(Calendar.DAY_OF_MONTH) + "日 周" + WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1];
        left.addView(Ui.text(ctx(), dateLine, 12, Ui.SLATE, false));
        String greet;
        if (!configured) {
            greet = "建立我的健康起点";
        } else {
            int h = now.get(Calendar.HOUR_OF_DAY);
            greet = h < 6 ? "夜深了" : h < 12 ? "早上好" : h < 13 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        }
        TextView title = Ui.text(ctx(), greet, 26, Ui.INK, true);
        left.addView(title, Ui.matchWrap(4, ctx()));
        head.addView(left, Ui.weight1());
        if (configured) {
            TextView avatar = Ui.text(ctx(), "👤", 17, Ui.INK, false);
            avatar.setGravity(Gravity.CENTER);
            GradientDrawable avBg = new GradientDrawable();
            avBg.setShape(GradientDrawable.OVAL);
            avBg.setColor(Ui.GREEN_SOFT);
            avBg.setStroke(1, Ui.BORDER);
            avatar.setBackground(avBg);
            int sz = Ui.dp(ctx(), 42);
            avatar.setOnClickListener(v -> activity().switchTab(4));
            head.addView(avatar, new LinearLayout.LayoutParams(sz, sz));
        }
        body.addView(head);
    }

    /* ---- 未配置: 建档表单 ---- */

    private void renderSetupForm() {
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.text(ctx(), "YOUR STARTING POINT · 01 / 03", 11, Ui.PRIMARY, true));
        card.addView(Ui.sectionTitle(ctx(), "从适合自己的节奏开始"), Ui.matchWrap(8, ctx()));
        card.addView(Ui.text(ctx(), "记录现在的状态，之后可以按生活节奏随时调整目标。", 13, Ui.SLATE, false), Ui.matchWrap(6, ctx()));

        card.addView(fieldLabel("身高"), Ui.matchWrap(16, ctx()));
        card.addView(inputWithUnit("例如 170", "cm", fHeight, v -> fHeight = v, true));

        LinearLayout grid = Ui.row(ctx());
        LinearLayout colA = Ui.column(ctx());
        colA.addView(fieldLabel("当前体重"));
        colA.addView(inputWithUnit("例如 65", "kg", fCurrent, v -> fCurrent = v, true), Ui.matchWrap(6, ctx()));
        LinearLayout colB = Ui.column(ctx());
        colB.addView(fieldLabel("目标体重"));
        colB.addView(inputWithUnit("例如 60", "kg", fTarget, v -> fTarget = v, true), Ui.matchWrap(6, ctx()));
        LinearLayout.LayoutParams lpA = Ui.weight1(); lpA.rightMargin = Ui.dp(ctx(), 6);
        LinearLayout.LayoutParams lpB = Ui.weight1(); lpB.leftMargin = Ui.dp(ctx(), 6);
        grid.addView(colA, lpA);
        grid.addView(colB, lpB);
        card.addView(grid, Ui.matchWrap(12, ctx()));

        card.addView(fieldLabel("计划开始日期"), Ui.matchWrap(12, ctx()));
        card.addView(dateField(fStartDate, "选择开始日期", v -> { fStartDate = v; render(); }), Ui.matchWrap(6, ctx()));
        card.addView(fieldLabel("目标日期 · 选填"), Ui.matchWrap(12, ctx()));
        card.addView(dateField(fTargetDate, "选择目标日期", v -> { fTargetDate = v; render(); }), Ui.matchWrap(6, ctx()));

        card.addView(fieldLabel("阶段重点关注"), Ui.matchWrap(12, ctx()));
        LinearLayout chips = Ui.row(ctx());
        for (int i = 0; i < GOAL_LABELS.length; i++) {
            final int idx = i;
            TextView chip = goalChip(GOAL_LABELS[i], idx == fGoalIndex);
            chip.setOnClickListener(v -> { fGoalIndex = idx; render(); });
            LinearLayout.LayoutParams lp = Ui.weight1();
            if (i > 0) lp.leftMargin = Ui.dp(ctx(), 6);
            chips.addView(chip, lp);
        }
        card.addView(chips, Ui.matchWrap(6, ctx()));

        android.widget.Button submit = Ui.primaryButton(ctx(), saving ? "正在建立计划…" : "创建我的健康计划");
        submit.setEnabled(!saving);
        submit.setOnClickListener(v -> saveProfile());
        card.addView(submit, Ui.matchWrap(18, ctx()));
        card.addView(Ui.text(ctx(), "💡 不追求极端速度，计划可以按你的生活节奏轻松调整。", 12, Ui.MUTED, false), Ui.matchWrap(10, ctx()));
        body.addView(card, Ui.matchWrap(14, ctx()));
    }

    private void saveProfile() {
        double h = parseNum(fHeight), cw = parseNum(fCurrent), tw = parseNum(fTarget);
        if (h == 0 || cw == 0 || tw == 0) { toast("请完整填写身高和体重"); return; }
        if (h <= 80 || h > 250 || cw <= 20 || cw > 500 || tw <= 20 || tw > 500) {
            toast("请检查身高和体重范围"); return;
        }
        if (saving) return;
        saving = true;
        render();
        try {
            JSONObject payload = new JSONObject();
            payload.put("height_cm", h);
            payload.put("current_weight", cw);
            payload.put("target_weight", tw);
            payload.put("start_date", fStartDate.isEmpty() ? todayString() : fStartDate);
            payload.put("target_date", fTargetDate.isEmpty() ? JSONObject.NULL : fTargetDate);
            payload.put("goal", GOAL_KEYS[fGoalIndex]);
            JSONObject plan = new JSONObject();
            plan.put("mon", "有氧或散步"); plan.put("tue", "休息"); plan.put("wed", "力量训练");
            plan.put("thu", "休息"); plan.put("fri", "有氧运动"); plan.put("sat", "自由活动"); plan.put("sun", "休息");
            payload.put("weekly_plan", plan);
            api().request("/api/v1/profile", "POST", payload, new ApiClient.Callback() {
                @Override public void onOk(JSONObject res, int status) {
                    saving = false;
                    toast("计划已建立");
                    load();
                }

                @Override public void onError(String message) {
                    saving = false;
                    toast((message == null || message.isEmpty()) ? "保存失败" : message);
                    render();
                }
            });
        } catch (Exception e) {
            saving = false;
            toast("保存失败");
            render();
        }
    }

    /* ---- 已配置: 仪表盘 v2 ---- */

    private void renderDashboard() {
        requestHomePlan();
        // v2.12 记录提醒条: 当天未记录体重且提醒开启、且今天未点过「不再提醒」时出现
        LinearLayout reminder = renderWeightReminderBanner();
        if (reminder != null) body.addView(reminder, Ui.matchWrap(14, ctx()));
        // v3.0 首页信息流: 评分 Hero → 快捷操作 → 训练 → 打卡 → 全部身体数据 → 手环 → 饮水 → 计划/打卡/洞察/趋势
        body.addView(renderScoreHero(), Ui.matchWrap(14, ctx()));
        body.addView(renderQuickActions(), Ui.matchWrap(12, ctx()));
        body.addView(renderTrainingCard(), Ui.matchWrap(12, ctx()));
        body.addView(renderCheckinCard(), Ui.matchWrap(12, ctx()));
        body.addView(renderBodyDataCard(), Ui.matchWrap(12, ctx()));
        body.addView(renderBandCard(), Ui.matchWrap(12, ctx()));
        body.addView(renderWaterCard(), Ui.matchWrap(12, ctx()));
        body.addView(renderWeekStrip(), Ui.matchWrap(18, ctx()));
        if (streakResult != null) body.addView(renderStreakCard(), Ui.matchWrap(18, ctx()));
        LinearLayout insights = renderInsightsCard();
        if (insights != null) body.addView(insights, Ui.matchWrap(18, ctx()));
        body.addView(renderTrendCard(), Ui.matchWrap(18, ctx()));

        TextView dis = Ui.text(ctx(), "BMI 与数据记录用于健康自我管理，不构成医疗诊断或特定减重速度承诺。", 11, Ui.MUTED, false);
        body.addView(dis, Ui.matchWrap(14, ctx()));
    }

    /* ---------------- v2.2: AI 计划驱动的顶部区块 ---------------- */

    /* 静默加载本周计划 (PlanStore 有缓存与并发合并); 内容变化时才重渲染一次 */
    private void requestHomePlan() {
        if (homePlanRequested) return;
        homePlanRequested = true;
        PlanStore.get(ctx(), false, new PlanStore.Listener() {
            @Override public void onPlan(JSONObject result) {
                JSONObject plan = result.optJSONObject("plan");
                if (plan == null) return;
                JSONObject today = PlanStore.dayAt(plan, PlanStore.todayIndex());
                String sig = result.optString("week_start", "") + "|"
                        + (today != null ? today.optString("focus", "") : "") + "|"
                        + result.optString("source", "");
                homePlan = plan;
                if (!sig.equals(homePlanSig)) {
                    homePlanSig = sig;
                    if (data != null && configured) render();
                }
            }

            @Override public void onError(String message) {
                // 训练卡/周条用兜底文案即可, 不打扰用户
            }
        });
    }

    /* 今日训练大卡: 训练照片铺满 + 深色渐变罩 + 今日计划标题 + 荧光绿开始按钮 */
    private View renderTrainingCard() {
        Context c = ctx();
        JSONObject todayObj = homePlan != null ? PlanStore.dayAt(homePlan, PlanStore.todayIndex()) : null;
        String focus = todayObj != null ? todayObj.optString("focus", "") : "";
        JSONArray actions = todayObj != null ? todayObj.optJSONArray("actions") : null;
        int actionCount = actions != null ? actions.length() : 0;
        String cardTitle = !focus.isEmpty() ? focus : "让 AI 安排今天";
        String cardSub = todayObj != null
                ? focus + " · " + actionCount + " 个动作"
                : "基于你的小米数据智能生成";

        FrameLayout box = new FrameLayout(c);
        box.setClipToOutline(true);
        final float radius = Ui.dp(c, 24);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 218)));

        ImageView img = new ImageView(c);
        img.setImageResource(R.drawable.hero_training);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View shade = new View(c);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#e60b0e0b"), Color.parseColor("#590b0e0b"),
                        Color.parseColor("#000b0e0b")});
        shade.setBackground(gd);
        box.addView(shade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout overlay = Ui.column(c);
        int pad = Ui.dp(c, 16);
        overlay.setPadding(pad, pad, pad, pad);
        LinearLayout top = Ui.row(c);
        top.addView(Ui.text(c, "今天的训练", 13, Ui.LIME, true), Ui.weight1());
        TextView adjust = Ui.text(c, "调整计划 ›", 12, Color.WHITE, false);
        adjust.setBackground(Ui.roundRect(Color.parseColor("#59000000"), 999, c));
        int ah = Ui.dp(c, 10), av = Ui.dp(c, 5);
        adjust.setPadding(ah, av, ah, av);
        adjust.setOnClickListener(v -> activity().switchTab(1));
        top.addView(adjust);
        overlay.addView(top);
        overlay.addView(new View(c), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        overlay.addView(Ui.text(c, cardTitle, 25, Color.WHITE, true));
        overlay.addView(Ui.text(c, cardSub, 13, Color.parseColor("#c9d2c4"), false), Ui.matchWrap(4, c));
        Button start = new Button(c);
        start.setText("开始训练  →");
        start.setTextSize(16);
        start.setTextColor(Ui.ON_LIME);
        start.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        start.setBackground(Ui.roundRect(Ui.LIME, 16, c));
        start.setAllCaps(false);
        start.setOnClickListener(v -> activity().openExercise());
        LinearLayout.LayoutParams blp = Ui.matchWrap(14, c);
        blp.height = Ui.dp(c, 50);
        overlay.addView(start, blp);
        box.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return box;
    }

    /* 三瓷砖: 饮食记录 (今日摄入热量, dashboard.diet_today; 缺失时回退今日饮水) /
       有氧运动 (今日运动分钟) / 身体数据 (最新体重, 点击去记录页) */
    /* v2.12 体重提醒条: 仅当今天记录里没有体重时出现, 温和提醒、不遮挡、不重复打扰。
       判定口径: dashboard 记录中今天那条的 weight 为空 (哪怕今天已有饮水/运动记录也算未称体重)。
       点「去记录」跳记录页; 点「今日不再提醒」后当天不再出现 (仅存本机)。 */
    private LinearLayout renderWeightReminderBanner() {
        Context c = ctx();
        if (!Reminders.isEnabled(c) || Reminders.dismissedToday(c)) return null;
        JSONObject todayRec = recordByDateMap.get(todayString());
        if (todayRec != null && num(todayRec, "weight") != null) return null;
        LinearLayout banner = Ui.column(c);
        banner.setBackground(Ui.roundRect(Ui.CAL_SOFT, 16, c));
        int p = Ui.dp(c, 14);
        banner.setPadding(p, Ui.dp(c, 12), p, Ui.dp(c, 12));
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(c, "⚖️", 20, Ui.INK, false));
        LinearLayout texts = Ui.column(c);
        LinearLayout.LayoutParams tlp = Ui.weight1();
        tlp.leftMargin = Ui.dp(c, 8);
        texts.addView(Ui.text(c, "今天还没称体重", 15, Ui.INK, true));
        texts.addView(Ui.text(c, "晨起称一次，体重趋势才准 · 提醒时间 "
                + Reminders.timeText(c) + "（可在计划页修改）", 12, Ui.SLATE, false), Ui.matchWrap(2, c));
        top.addView(texts, tlp);
        banner.addView(top);
        LinearLayout btnRow = Ui.row(c);
        Button go = Ui.primaryButton(c, "去记录 ›");
        go.setOnClickListener(v -> activity().switchTab(2));
        btnRow.addView(go, Ui.weight1());
        Button dismiss = Ui.ghostButton(c, "今日不再提醒");
        dismiss.setOnClickListener(v -> {
            Reminders.dismissToday(c);
            render();
        });
        LinearLayout.LayoutParams dlp = Ui.weight1();
        dlp.leftMargin = Ui.dp(c, 8);
        btnRow.addView(dismiss, dlp);
        banner.addView(btnRow, Ui.matchWrap(10, c));
        return banner;
    }

    /* v2.5 今日饮水卡: 进度环 (目标 2000 ml) + 一键 +250ml / 自定义毫升。
       水量优先取 dashboard.water_today, 旧后端回退今日记录的 water_ml。 */
    private int todayWaterMl() {
        JSONObject wt = data != null ? data.optJSONObject("water_today") : null;
        if (wt != null && !wt.isNull("water_ml")) return wt.optInt("water_ml", 0);
        JSONObject todayRec = recordByDateMap.get(todayString());
        if (todayRec != null && !todayRec.isNull("water_ml")) {
            return (int) Math.round(todayRec.optDouble("water_ml", 0));
        }
        return 0;
    }

    private LinearLayout renderWaterCard() {
        Context c = ctx();
        int ml = todayWaterMl();
        int pct = Math.min(100, (int) Math.round(ml / 2000.0 * 100));
        LinearLayout card = Ui.card(c);
        LinearLayout main = Ui.row(c);
        main.setGravity(Gravity.CENTER_VERTICAL);
        art.qelora.welltrack.ui.WaterRing ring = new art.qelora.welltrack.ui.WaterRing(c);
        ring.setRatio(ml / 2000.0);
        int rsz = Ui.dp(c, 64);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rsz, rsz);
        rlp.rightMargin = Ui.dp(c, 12);
        main.addView(ring, rlp);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, "💧 今日饮水", 15, Ui.INK, true));
        texts.addView(Ui.text(c, ml + " / 2000 ml · " + pct + "%"
                + (pct >= 100 ? " 🎉 已达标" : "，还差 " + Math.max(0, 2000 - ml) + " ml"),
                13, Ui.SLATE, false), Ui.matchWrap(2, c));
        main.addView(texts, Ui.weight1());
        card.addView(main);
        LinearLayout btnRow = Ui.row(c);
        Button quick = Ui.primaryButton(c, "＋ 250 ml");
        quick.setOnClickListener(v -> addWater(250));
        btnRow.addView(quick, Ui.weight1());
        Button custom = Ui.ghostButton(c, "自定义…");
        custom.setOnClickListener(v -> showWaterCustomDialog());
        LinearLayout.LayoutParams clp = Ui.weight1();
        clp.leftMargin = Ui.dp(c, 8);
        btnRow.addView(custom, clp);
        card.addView(btnRow, Ui.matchWrap(12, c));
        return card;
    }

    private void addWater(final int amountMl) {
        if (amountMl < 10 || amountMl > 2000) { toast("单次饮水量需在 10~2000 ml 之间"); return; }
        api().waterAdd(todayString(), amountMl, new ApiClient.Callback() {
            @Override public void onOk(JSONObject res, int status) {
                toast("已记 +" + amountMl + " ml 💧");
                load();
            }
            @Override public void onError(String message) {
                toast("饮水记录失败，请稍后再试");
            }
        });
    }

    private void showWaterCustomDialog() {
        Context c = ctx();
        final EditText input = new EditText(c);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("输入毫升数，如 500");
        int p = Ui.dp(c, 16);
        input.setPadding(p, p, p, p);
        new AlertDialog.Builder(c)
                .setTitle("自定义饮水量")
                .setView(input)
                .setPositiveButton("记入今日", (d, w) -> {
                    String s = input.getText().toString().trim();
                    int amount;
                    try { amount = Integer.parseInt(s); } catch (Exception e) { toast("请输入数字"); return; }
                    addWater(amount);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* v2.6 连续打卡卡: 火焰天数 + 本周记录点 + 下一徽章提示, 点击去记录页 */
    private LinearLayout renderStreakCard() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "连续打卡", "去记录 ›", v -> activity().switchTab(2)));
        LinearLayout main = Ui.row(c);
        main.setGravity(Gravity.CENTER_VERTICAL);
        main.addView(Ui.text(c, "🔥", 30, Ui.INK, false));
        LinearLayout texts = Ui.column(c);
        LinearLayout.LayoutParams tlp = Ui.weight1();
        tlp.leftMargin = Ui.dp(c, 10);
        texts.addView(Ui.text(c, streakResult.current + " 天连续记录", 18, Ui.INK, true));
        texts.addView(Ui.text(c, "最长 " + streakResult.longest + " 天 · 本周已记录 "
                + streakResult.weekRecordDays + " / 7 天 · 本周运动 " + streakResult.weekWorkoutDays + " 天",
                12, Ui.SLATE, false), Ui.matchWrap(2, c));
        main.addView(texts, tlp);
        card.addView(main, Ui.matchWrap(12, c));
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        LinearLayout dots = Ui.row(c);
        String today = Streak.todayString();
        for (int i = 0; i < 7; i++) {
            String ds = streakResult.weekDates.get(i);
            boolean done = streakResult.recordDates.contains(ds);
            boolean isToday = ds.equals(today);
            LinearLayout cell = Ui.column(c);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView wd = Ui.text(c, names[i], 11, isToday ? Ui.LIME : Ui.MUTED, isToday);
            wd.setGravity(Gravity.CENTER);
            cell.addView(wd);
            TextView dot = Ui.text(c, done ? "●" : "○", 14, done ? Ui.LIME : Ui.MUTED, false);
            dot.setGravity(Gravity.CENTER);
            cell.addView(dot, Ui.matchWrap(2, c));
            dots.addView(cell, Ui.weight1());
        }
        card.addView(dots, Ui.matchWrap(10, c));
        card.addView(Ui.text(c, "🎯 " + streakResult.nextHint, 13, Ui.PRIMARY_DARK, true),
                Ui.matchWrap(8, c));
        card.addView(Ui.text(c, "连续记录：当天有体重 / 饮食 / 饮水 / 运动任一记录即算一天（含小米同步日）。",
                11, Ui.MUTED, false), Ui.matchWrap(6, c));
        return card;
    }

    /* 本周计划: 章节头 + 周条 (今天荧光绿方块, 活动日小圆点) + 今日摘要行 */
    private LinearLayout renderWeekStrip() {
        Context c = ctx();
        LinearLayout col = Ui.column(c);
        col.addView(Ui.sectionHeader(c, "本周计划", "历史记录 ›", v -> activity().switchTab(3)));
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        List<String> dates = PlanStore.weekDates();
        int todayIdx = PlanStore.todayIndex();

        LinearLayout stripCard = Ui.card(c);
        LinearLayout strip = Ui.row(c);
        for (int i = 0; i < 7; i++) {
            String ds = dates.get(i);
            boolean isToday = i == todayIdx;
            LinearLayout cellCol = Ui.column(c);
            cellCol.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView wd = Ui.text(c, names[i], 11, isToday ? Ui.LIME : Ui.MUTED, isToday);
            wd.setGravity(Gravity.CENTER);
            cellCol.addView(wd);
            TextView num = Ui.text(c, String.valueOf(parseCal(ds).get(Calendar.DAY_OF_MONTH)),
                    15, isToday ? Ui.ON_LIME : Ui.INK, true);
            num.setGravity(Gravity.CENTER);
            if (isToday) num.setBackground(Ui.roundRect(Ui.LIME, 12, c));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
            nlp.topMargin = Ui.dp(c, 5);
            cellCol.addView(num, nlp);
            View dot = new View(c);
            if (weekDayHasActivity(ds, i)) {
                GradientDrawable dd = new GradientDrawable();
                dd.setShape(GradientDrawable.OVAL);
                dd.setColor(Ui.LIME);
                dot.setBackground(dd);
            }
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(c, 5), Ui.dp(c, 5));
            dlp.topMargin = Ui.dp(c, 5);
            cellCol.addView(dot, dlp);
            strip.addView(cellCol, Ui.weight1());
        }
        stripCard.addView(strip);
        col.addView(stripCard, Ui.matchWrap(10, c));

        JSONObject todayObj = homePlan != null ? PlanStore.dayAt(homePlan, todayIdx) : null;
        LinearLayout summary = Ui.row(c);
        summary.setBackground(Ui.roundRect(Ui.SOFT, 12, c));
        int sp = Ui.dp(c, 12);
        summary.setPadding(sp, Ui.dp(c, 10), sp, Ui.dp(c, 10));
        String sumText;
        if (todayObj != null) {
            JSONArray acts = todayObj.optJSONArray("actions");
            int n = acts != null ? acts.length() : 0;
            sumText = "今日 · " + todayObj.optString("focus", "日常活动") + " · " + n
                    + " 个动作 · 目标 " + todayObj.optInt("steps_target", 0) + " 步";
        } else {
            sumText = "让 AI 为你安排本周计划";
        }
        summary.addView(Ui.text(c, sumText, 13, Ui.INK, false), Ui.weight1());
        summary.addView(Ui.text(c, "计划 ›", 12, Ui.LIME, true));
        summary.setOnClickListener(v -> activity().switchTab(1));
        col.addView(summary, Ui.matchWrap(10, c));
        return col;
    }

    /* 周条小圆点: 当天有运动安排 (计划非休息日) 或已实际运动 */
    private boolean weekDayHasActivity(String dateStr, int idx) {
        JSONObject rec = recordByDateMap.get(dateStr);
        if (rec != null) {
            Double wm = num(rec, "workout_minutes");
            if (wm != null && wm > 0) return true;
        }
        JSONObject d = homePlan != null ? PlanStore.dayAt(homePlan, idx) : null;
        if (d != null) {
            String focus = d.optString("focus", "");
            JSONArray acts = d.optJSONArray("actions");
            if (!focus.contains("休息") && acts != null && acts.length() > 0) return true;
        }
        return false;
    }

    private static Calendar parseCal(String ds) {
        Calendar cal = Calendar.getInstance();
        try {
            cal.set(Calendar.YEAR, Integer.parseInt(ds.substring(0, 4)));
            cal.set(Calendar.MONTH, Integer.parseInt(ds.substring(5, 7)) - 1);
            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(ds.substring(8, 10)));
        } catch (Exception ignored) {}
        return cal;
    }

    /* 活力卡: 三环 + 今日健康分 + 三项图例 + 消耗达标横条; 右上角保留小米同步按钮 */
    /* 未连接小米: 图片横幅 + 引导文案 + 连接按钮 (跳我的页) */
    /* 今日指标 2×2: 步数/消耗/睡眠/运动, 每块回答「达标没、差多少」 */
    private LinearLayout.LayoutParams tileLp(boolean left) {
        LinearLayout.LayoutParams lp = Ui.weight1();
        if (left) lp.rightMargin = Ui.dp(ctx(), 5); else lp.leftMargin = Ui.dp(ctx(), 5);
        return lp;
    }

    /* 健康洞察: 取洞察引擎前 2 条, 无内容时整卡不渲染 */
    private LinearLayout renderInsightsCard() {
        List<Insights.Insight> recs = Insights.recommendations(todayDay, weekStats, todayPlan);
        if (recs.isEmpty()) return null;
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.sectionHeader(ctx(), "健康洞察", "看分析 ›", v -> activity().switchTab(3)));
        int n = Math.min(2, recs.size());
        for (int i = 0; i < n; i++) {
            Insights.Insight ins = recs.get(i);
            String icon = ins.level == Insights.Level.GOOD ? "✅"
                    : ins.level == Insights.Level.WARN ? "⚠️" : "💡";
            LinearLayout row = Ui.row(ctx());
            row.setGravity(Gravity.TOP);
            TextView ic = Ui.text(ctx(), icon, 16, Ui.INK, false);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            il.rightMargin = Ui.dp(ctx(), 8);
            il.topMargin = Ui.dp(ctx(), 1);
            row.addView(ic, il);
            LinearLayout texts = Ui.column(ctx());
            texts.addView(Ui.text(ctx(), ins.title, 14, Ui.INK, true));
            texts.addView(Ui.text(ctx(), ins.body, 12, Ui.SLATE, false), Ui.matchWrap(2, ctx()));
            row.addView(texts, Ui.weight1());
            card.addView(row, Ui.matchWrap(i == 0 ? 12 : 10, ctx()));
        }
        // 卡片底部: 健康智能体入口 (带着本页数据上下文去聊天)
        Button agentBtn = Ui.ghostButton(ctx(), "💬 问健康智能体");
        agentBtn.setOnClickListener(v -> activity().openAgent());
        card.addView(agentBtn, Ui.matchWrap(14, ctx()));
        return card;
    }

    /* 体重卡: 当前体重 + 较昨日 + BMI + 目标进度条 + 记体重入口 */
    /* 今日待办 (保留原 todos 逻辑与两个入口) */
    /* 7 天体重波动 (保留原柱状实现, 标题换 sectionHeader 样式) */
    private LinearLayout renderTrendCard() {
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.sectionHeader(ctx(), "7 天体重波动", "看分析 ›", v -> activity().switchTab(3)));
        LinearLayout subRow = Ui.row(ctx());
        TextView sub = Ui.text(ctx(), "平稳渐进 · 关注整体走势", 11, Ui.MUTED, false);
        subRow.addView(sub, Ui.weight1());
        subRow.addView(pill("目标 " + fmt(num(data, "target_weight")) + " kg", Ui.SOFT, Ui.SLATE));
        card.addView(subRow, Ui.matchWrap(4, ctx()));

        LinearLayout chart = Ui.row(ctx());
        chart.setGravity(Gravity.BOTTOM);
        for (TrendDay t : trend) {
            LinearLayout col = Ui.column(ctx());
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView tag = Ui.text(ctx(), t.weight != null ? fmt(t.weight) : "", 10,
                    t.isToday ? Ui.PRIMARY : Ui.SLATE, t.isToday);
            tag.setGravity(Gravity.CENTER);
            col.addView(tag, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx(), 16)));
            FrameLayout track = new FrameLayout(ctx());
            View bar = new View(ctx());
            GradientDrawable bd = Ui.roundRect(
                    t.hasRecord && t.weight != null ? (t.isToday ? Ui.PRIMARY_DARK : Ui.PRIMARY) : Ui.BORDER, 6, ctx());
            bar.setBackground(bd);
            int barH = Math.max(Ui.dp(ctx(), 4), (int) (Ui.dp(ctx(), (float) t.barHeight) * 0.5f));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(ctx(), 16), barH);
            blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            track.addView(bar, blp);
            col.addView(track, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx(), 64)));
            TextView wd = Ui.text(ctx(), t.weekday, 11, t.isToday ? Ui.PRIMARY : Ui.MUTED, t.isToday);
            wd.setGravity(Gravity.CENTER);
            col.addView(wd, Ui.matchWrap(4, ctx()));
            chart.addView(col, Ui.weight1());
        }
        card.addView(chart, Ui.matchWrap(12, ctx()));
        return card;
    }

    /* ================= v3.0 新增区块 ================= */

    private int partColor(String name) {
        switch (name) {
            case "活动": return Ui.LIME;
            case "运动": return Ui.MOVE;
            case "睡眠": return Ui.SLEEP;
            case "饮水": return Color.parseColor("#4db2ff");
            default: return Ui.CAL;
        }
    }

    /* AI 今日评分 Hero: 大环总分 + 状态标签 + 强弱项 + 五维分项条 + AI 点评 */
    private LinearLayout renderScoreHero() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(c, "✦ AI 今日评分", 13, Ui.LIME, true), Ui.weight1());
        head.addView(Ui.chip(c, miConnected ? "数据来自小米手环" : "基于今日记录", Ui.SLATE, Ui.SOFT));
        card.addView(head);

        int total = scoreResult != null ? scoreResult.total : 0;
        int ringColor = total >= 65 ? Ui.LIME : total >= 40 ? Ui.AMBER_TEXT : Ui.CAL;
        LinearLayout main = Ui.row(c);
        main.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout ringBox = new FrameLayout(c);
        int rsz = Ui.dp(c, 126);
        ScoreRing ring = new ScoreRing(c);
        ring.setScore(total, ringColor);
        ringBox.addView(ring, new FrameLayout.LayoutParams(rsz, rsz));
        LinearLayout center = Ui.column(c);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView numTv = Ui.text(c, String.valueOf(total), 38, Ui.INK, true);
        numTv.setGravity(Gravity.CENTER);
        center.addView(numTv);
        TextView unitTv = Ui.text(c, "今日健康分", 11, Ui.MUTED, false);
        unitTv.setGravity(Gravity.CENTER);
        center.addView(unitTv);
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(rsz, rsz);
        clp2.gravity = Gravity.CENTER;
        ringBox.addView(center, clp2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rsz, rsz);
        rlp.rightMargin = Ui.dp(c, 14);
        main.addView(ringBox, rlp);

        LinearLayout right = Ui.column(c);
        right.addView(Ui.text(c, Insights.scoreLabel(total), 20, Ui.INK, true));
        if (scoreResult != null && !scoreResult.strongHint.isEmpty()) {
            right.addView(Ui.text(c, "👍 " + scoreResult.strongHint, 12, Ui.SLATE, false), Ui.matchWrap(6, c));
        }
        if (scoreResult != null && !scoreResult.weakHint.isEmpty()) {
            right.addView(Ui.text(c, "📌 待提升 · " + scoreResult.weakHint, 12, Ui.SLATE, false), Ui.matchWrap(4, c));
        }
        Button ask = Ui.ghostButton(c, "💬 问 AI 教练");
        ask.setOnClickListener(v -> activity().openAgent());
        right.addView(ask, Ui.matchWrap(10, c));
        main.addView(right, Ui.weight1());
        card.addView(main, Ui.matchWrap(14, c));

        if (scoreResult != null) {
            for (Insights.Part p : scoreResult.parts) {
                LinearLayout row = Ui.row(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView nm = Ui.text(c, p.name, 12, Ui.SLATE, true);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(c, 34), ViewGroup.LayoutParams.WRAP_CONTENT);
                row.addView(nm, nlp);
                FrameLayout bar = Ui.progressBar(c, p.hasData ? p.score / (double) Math.max(1, p.max) : 0,
                        partColor(p.name), 6);
                row.addView(bar, Ui.weight1());
                TextView sc = Ui.text(c, p.hasData ? (p.score + "/" + p.max) : "无数据",
                        11, p.hasData ? Ui.INK : Ui.MUTED, true);
                sc.setGravity(Gravity.RIGHT);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(c, 46), ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.leftMargin = Ui.dp(c, 8);
                row.addView(sc, slp);
                card.addView(row, Ui.matchWrap(7, c));
            }
        }

        if (!aiVerdict.isEmpty()) {
            LinearLayout box = Ui.row(c);
            box.setBackground(Ui.roundRect(Ui.GREEN_SOFT, 12, c));
            int p = Ui.dp(c, 10);
            box.setPadding(p, p, p, p);
            box.addView(Ui.text(c, "🤖 " + aiVerdict, 13, Ui.INK, false), Ui.weight1());
            card.addView(box, Ui.matchWrap(12, c));
        } else if (aiVerdictLoading) {
            card.addView(Ui.text(c, "🤖 AI 正在点评今天…", 12, Ui.MUTED, false), Ui.matchWrap(12, c));
        }
        return card;
    }

    /* 快捷操作: 拍照记餐 / 记录食物 / 记体重 / 喝水 +250 */
    private LinearLayout renderQuickActions() {
        Context c = ctx();
        LinearLayout row = Ui.row(c);
        row.addView(Ui.quickAction(c, "📸", "拍照记餐", v -> activity().goRecordsDiet(true)), tile4Lp(0));
        row.addView(Ui.quickAction(c, "🍽️", "记录食物", v -> activity().goRecordsDiet(false)), tile4Lp(1));
        row.addView(Ui.quickAction(c, "⚖️", "记体重", v -> activity().switchTab(2)), tile4Lp(2));
        row.addView(Ui.quickAction(c, "💧", "喝水+250", v -> addWater(250)), tile4Lp(3));
        return row;
    }

    private LinearLayout.LayoutParams tile4Lp(int idx) {
        LinearLayout.LayoutParams lp = Ui.weight1();
        if (idx > 0) lp.leftMargin = Ui.dp(ctx(), 4);
        if (idx < 3) lp.rightMargin = Ui.dp(ctx(), 4);
        return lp;
    }

    /* 运动打卡: 选运动 + 选时长 + 一键打卡 (POST /api/v1/exercises), 状态取今日真实记录 */
    private LinearLayout renderCheckinCard() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = Ui.column(c);
        titles.addView(Ui.sectionTitle(c, "运动打卡"));
        titles.addView(Ui.text(c, todayExerciseMinutes > 0
                        ? "今天已运动 " + todayExerciseMinutes + " 分钟，继续保持"
                        : "选个运动，点一下就打卡", 12, Ui.MUTED, false), Ui.matchWrap(2, c));
        head.addView(titles, Ui.weight1());
        head.addView(todayExerciseMinutes > 0
                ? Ui.chip(c, "✓ 已打卡", Ui.ON_LIME, Ui.LIME)
                : Ui.chip(c, "未打卡", Ui.SLATE, Ui.SOFT));
        card.addView(head);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.row(c);
            for (int i = r * 3; i < r * 3 + 3; i++) {
                final int idx = i;
                TextView chipTv = Ui.toggleChip(c, CHECKIN_SPORTS[idx], idx == checkinSportIdx,
                        v -> { checkinSportIdx = idx; render(); });
                LinearLayout.LayoutParams lp = Ui.weight1();
                if (i % 3 > 0) lp.leftMargin = Ui.dp(c, 8);
                row.addView(chipTv, lp);
            }
            card.addView(row, Ui.matchWrap(r == 0 ? 12 : 8, c));
        }
        LinearLayout drow = Ui.row(c);
        for (int i = 0; i < CHECKIN_MINS.length; i++) {
            final int m = CHECKIN_MINS[i];
            TextView chipTv = Ui.toggleChip(c, m + " 分钟", m == checkinMinutes,
                    v -> { checkinMinutes = m; render(); });
            LinearLayout.LayoutParams lp = Ui.weight1();
            if (i > 0) lp.leftMargin = Ui.dp(c, 8);
            drow.addView(chipTv, lp);
        }
        card.addView(drow, Ui.matchWrap(8, c));
        Button btn = Ui.primaryButton(c, checkinSaving ? "打卡中…"
                : "✓ 打卡 " + CHECKIN_SPORTS[checkinSportIdx] + " " + checkinMinutes + " 分钟");
        btn.setEnabled(!checkinSaving);
        btn.setOnClickListener(v -> doCheckin());
        card.addView(btn, Ui.matchWrap(12, c));
        TextView more = Ui.text(c, "跟练、运动库在运动页 ›", 12, Ui.MUTED, false);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> activity().openExercise());
        card.addView(more, Ui.matchWrap(10, c));
        return card;
    }

    private void doCheckin() {
        if (checkinSaving) return;
        checkinSaving = true;
        render();
        try {
            JSONObject body = new JSONObject();
            body.put("exercise_date", todayString());
            body.put("kind", CHECKIN_SPORTS[checkinSportIdx]);
            body.put("duration_minutes", checkinMinutes);
            body.put("note", "首页快捷打卡");
            api().request("/api/v1/exercises", "POST", body, new ApiClient.Callback() {
                @Override public void onOk(JSONObject res, int status) {
                    checkinSaving = false;
                    todayExerciseMinutes += checkinMinutes;
                    toast("打卡成功 🎉 " + CHECKIN_SPORTS[checkinSportIdx] + " " + checkinMinutes + " 分钟");
                    buildScore();
                    loadStreak();
                    render();
                }

                @Override public void onError(String message) {
                    checkinSaving = false;
                    toast("打卡失败，请稍后再试");
                    render();
                }
            });
        } catch (Exception e) {
            checkinSaving = false;
            toast("打卡失败，请稍后再试");
            render();
        }
    }

    /* 全部身体数据: 8 个指标一屏看全 (手环活动 + 手工记录合并), 每项带目标进度 */
    private LinearLayout renderBodyDataCard() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "全部身体数据", "去记录 ›", v -> activity().switchTab(2)));
        LinearLayout row1 = Ui.row(c);
        String stepSub = todayDay.steps >= Insights.STEP_GOAL ? "目标 8000 · 已达标 🎉"
                : "目标 8000 · 还差 " + (Insights.STEP_GOAL - todayDay.steps) + " 步";
        row1.addView(Ui.metricTile(c, Ui.PRIMARY, Ui.GREEN_SOFT, "👟", "步数 · 手环",
                fmt((double) todayDay.steps), stepSub,
                todayDay.steps / (double) Insights.STEP_GOAL), tileLp(true));
        String calSub = todayDay.calories >= Insights.CAL_GOAL ? "目标 400 · 已达标 🎉"
                : "目标 400 · 还差 " + (Insights.CAL_GOAL - todayDay.calories) + " 千卡";
        row1.addView(Ui.metricTile(c, Ui.CAL, Ui.CAL_SOFT, "🔥", "活动消耗 · 千卡",
                String.valueOf(todayDay.calories), calSub,
                todayDay.calories / (double) Insights.CAL_GOAL), tileLp(false));
        card.addView(row1, Ui.matchWrap(12, c));

        LinearLayout row2 = Ui.row(c);
        Double sleep = num(miToday, "sleep_hours");
        if (sleep == null) {
            JSONObject tr = recordByDateMap.get(todayString());
            sleep = tr != null ? num(tr, "sleep_hours") : null;
        }
        row2.addView(Ui.metricTile(c, Ui.SLEEP, Ui.SLEEP_SOFT, "🌙", "睡眠",
                sleep != null ? fmt(sleep) + " 小时" : "--",
                sleep == null ? "手环监测或在记录页填写" : (sleep >= 7 ? "目标 8 · 作息充足" : "目标 8 · 建议睡满"),
                sleep != null ? sleep / Insights.SLEEP_GOAL : -1), tileLp(true));
        int woMin = Math.max(todayDay.workoutMinutes, todayExerciseMinutes);
        row2.addView(Ui.metricTile(c, Ui.MOVE, Ui.MOVE_SOFT, "⏱️", "运动 · 分钟",
                String.valueOf(woMin),
                woMin >= Insights.WORKOUT_GOAL ? "目标 30 · 已达标 🎉" : "目标 30 · 还差 " + (Insights.WORKOUT_GOAL - woMin) + " 分钟",
                woMin / (double) Insights.WORKOUT_GOAL), tileLp(false));
        card.addView(row2, Ui.matchWrap(8, c));

        LinearLayout row3 = Ui.row(c);
        boolean fromMi = "mihealth".equals(data.optString("current_weight_source", ""));
        row3.addView(Ui.metricTile(c, Ui.LIME, Ui.SOFT, "⚖️", "体重 · kg",
                fmt(num(data, "current_weight")),
                (diffLabel.isEmpty() ? (fromMi ? "来自小米同步" : "持续记录见证变化") : diffLabel), -1), tileLp(true));
        Double bmi = num(data, "bmi");
        row3.addView(Ui.metricTile(c, Ui.SLEEP, Ui.SOFT, "📏", "BMI",
                bmi != null && bmi > 0 ? fmt(bmi) : "--",
                bmi != null && bmi > 0 ? bmiStatus + " · 目标 " + fmt(num(data, "target_weight")) + " kg" : "先在记录页称一次体重", -1), tileLp(false));
        card.addView(row3, Ui.matchWrap(8, c));

        LinearLayout row4 = Ui.row(c);
        int water = todayWaterMl();
        row4.addView(Ui.metricTile(c, Color.parseColor("#4db2ff"), Ui.BLUE_SOFT, "💧", "饮水 · ml",
                String.valueOf(water), "目标 2000 · " + Math.min(100, water * 100 / 2000) + "%",
                water / 2000.0), tileLp(true));
        JSONObject dt = data.optJSONObject("diet_today");
        int dietK = dt != null ? (int) Math.round(dt.optDouble("total_kcal", 0)) : 0;
        int dietN = dt != null ? dt.optInt("count", 0) : 0;
        row4.addView(Ui.metricTile(c, Ui.CAL, Ui.SOFT, "🍽️", "饮食摄入 · 千卡",
                dietN > 0 ? String.valueOf(dietK) : "--",
                dietN > 0 ? "今日 " + dietN + " 项 · 目标约 2000" : "未记录，点上方拍照记餐",
                dietN > 0 ? dietK / 2000.0 : -1), tileLp(false));
        card.addView(row4, Ui.matchWrap(8, c));

        if (showGoalProgress) {
            FrameLayout bar = Ui.progressBar(c, goalProgressVal / 100.0, Ui.PRIMARY, 8);
            card.addView(bar, Ui.matchWrap(12, c));
            LinearLayout foot = Ui.row(c);
            foot.addView(Ui.text(c, progressLabel + " · " + goalLabelText, 12, Ui.MUTED, false), Ui.weight1());
            foot.addView(Ui.text(c, goalProgressVal + "%", 12, Ui.PRIMARY, true));
            card.addView(foot, Ui.matchWrap(6, c));
        }
        return card;
    }

    /* 小米手环活动: 手环今日数据 + 同步入口 (活动数据来源就是手环/小米运动健康) */
    private LinearLayout renderBandCard() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = Ui.column(c);
        titles.addView(Ui.sectionTitle(c, "⌚ 小米手环 · 今日活动"));
        String synced = mihealth.optString("last_synced_at", "");
        titles.addView(Ui.text(c, (synced != null && !synced.isEmpty() && !"null".equals(synced))
                ? "上次同步 " + synced : "步数、消耗、睡眠来自手环同步", 11, Ui.MUTED, false), Ui.matchWrap(2, c));
        head.addView(titles, Ui.weight1());
        Button refresh = Ui.ghostButton(c, syncing ? "同步中…" : "⚡ 同步");
        refresh.setEnabled(!syncing);
        refresh.setOnClickListener(v -> syncMiHealth());
        head.addView(refresh);
        card.addView(head);
        boolean hasBand = todayDay.steps > 0 || todayDay.calories > 0 || todayDay.sleepHours > 0;
        if (hasBand) {
            card.addView(Ui.text(c, "👟 " + todayDay.steps + " 步   🔥 " + todayDay.calories
                            + " 千卡   ⏱️ " + todayDay.workoutMinutes + " 分钟   🌙 "
                            + (todayDay.sleepHours > 0 ? fmt(todayDay.sleepHours) + " 小时" : "--"),
                    14, Ui.INK, true), Ui.matchWrap(12, c));
            card.addView(Ui.text(c, Insights.calorieHint(todayDay.calories), 12, Ui.SLATE, false),
                    Ui.matchWrap(4, c));
        } else if (miConnected) {
            card.addView(Ui.text(c, "手环已连接，今天还没有同步到数据，点右上角同步拉取。", 13, Ui.SLATE, false),
                    Ui.matchWrap(10, c));
        } else {
            card.addView(Ui.text(c, "绑定小米运动健康后，手环记录的步数、活动消耗和睡眠会自动出现在这里，并计入今日评分。", 13, Ui.SLATE, false),
                    Ui.matchWrap(10, c));
            Button go = Ui.primaryButton(c, "去绑定小米运动健康");
            go.setOnClickListener(v -> activity().switchTab(4));
            card.addView(go, Ui.matchWrap(10, c));
        }
        return card;
    }

    /* ---------------- 小部件 ---------------- */

    private TextView fieldLabel(String s) {
        return Ui.text(ctx(), s, 12, Ui.SLATE, true);
    }

    private interface StrSetter { void set(String v); }

    private LinearLayout inputWithUnit(String hint, String unit, String initial, StrSetter setter, boolean decimal) {
        LinearLayout row = Ui.row(ctx());
        row.setBackground(Ui.roundRect(Ui.SOFT, 10, ctx()));
        int p = Ui.dp(ctx(), 4);
        row.setPadding(p, 0, Ui.dp(ctx(), 10), 0);
        EditText e = Ui.input(ctx(), hint, decimal
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_NUMBER);
        e.setBackground(null);
        e.setText(initial);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { setter.set(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        row.addView(e, Ui.weight1());
        row.addView(Ui.text(ctx(), unit, 13, Ui.MUTED, false));
        return row;
    }

    private TextView dateField(String value, String placeholder, StrSetter setter) {
        TextView tv = Ui.text(ctx(), (value == null || value.isEmpty()) ? placeholder : value,
                15, (value == null || value.isEmpty()) ? Ui.MUTED : Ui.INK, false);
        tv.setBackground(Ui.roundRect(Ui.SOFT, 10, ctx()));
        int h = Ui.dp(ctx(), 12), v = Ui.dp(ctx(), 11);
        tv.setPadding(h, v, h, v);
        tv.setOnClickListener(view -> {
            Calendar c = Calendar.getInstance();
            if (value != null && value.length() == 10) {
                try {
                    c.set(Calendar.YEAR, Integer.parseInt(value.substring(0, 4)));
                    c.set(Calendar.MONTH, Integer.parseInt(value.substring(5, 7)) - 1);
                    c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(value.substring(8, 10)));
                } catch (Exception ignored) {}
            }
            new DatePickerDialog(ctx(), (dp, y, m, d) ->
                    setter.set(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });
        return tv;
    }

    private TextView goalChip(String label, boolean active) {
        TextView chip = Ui.text(ctx(), label, 13, active ? Ui.ON_LIME : Ui.SLATE, active);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.roundRect(active ? Ui.LIME : Ui.SOFT, 10, ctx()));
        int v = Ui.dp(ctx(), 9);
        chip.setPadding(0, v, 0, v);
        return chip;
    }

    private TextView pill(String text, int bg, int fg) {
        TextView t = Ui.text(ctx(), text, 11, fg, true);
        t.setBackground(Ui.roundRect(bg, 20, ctx()));
        int h = Ui.dp(ctx(), 8), v = Ui.dp(ctx(), 3);
        t.setPadding(h, v, h, v);
        return t;
    }

    /* ---------------- 工具 ---------------- */

    private static Double num(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        double d = o.optDouble(key, Double.NaN);
        return Double.isNaN(d) ? null : d;
    }

    private static String fmt(Double d) {
        if (d == null) return "--";
        return fmt(d.doubleValue());
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static double parseNum(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
