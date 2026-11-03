package art.qelora.welltrack.pages;

import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;
import art.qelora.welltrack.ui.VitalityRings;

/**
 * 首页 v2 (对应小程序 pages/index, 视觉与功能重构版):
 * 产品主线 = 小米运动健康数据: 活力三环 + 今日健康分 + 消耗达标 + 健康洞察与锻炼建议 + 每日体重。
 * 结构: 问候 → 主视觉横幅 → 活力卡 (三环/健康分/达标文案) → 今日指标 2×2 → 健康洞察 →
 *       体重卡 → 今日待办 → 7 天体重波动。未连接小米时活力区换成连接引导卡。
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
        refreshHandler.removeCallbacks(refreshLoop);
        refreshHandler.postDelayed(refreshLoop, 60000);
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
                render();
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
        circle.setColor(Color.parseColor("#fef2f2"));
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

    private void renderHead() {
        LinearLayout head = Ui.row(ctx());
        LinearLayout left = Ui.column(ctx());
        LinearLayout eyebrow = Ui.row(ctx());
        View dot = new View(ctx());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Ui.PRIMARY);
        dot.setBackground(d);
        eyebrow.addView(dot, new LinearLayout.LayoutParams(Ui.dp(ctx(), 7), Ui.dp(ctx(), 7)));
        TextView brand = Ui.text(ctx(), "  WELLTRACK · 每一点都算数", 11, Ui.PRIMARY, true);
        eyebrow.addView(brand);
        left.addView(eyebrow);
        TextView title = Ui.title(ctx(), configured ? "早上好，今天也慢慢来" : "建立我的健康起点");
        left.addView(title, Ui.matchWrap(6, ctx()));
        head.addView(left, Ui.weight1());
        if (configured) {
            LinearLayout pillBox = Ui.column(ctx());
            pillBox.setGravity(Gravity.CENTER);
            pillBox.setBackground(Ui.roundRect(Ui.SOFT, 12, ctx()));
            int p = Ui.dp(ctx(), 8);
            pillBox.setPadding(p, Ui.dp(ctx(), 6), p, Ui.dp(ctx(), 6));
            pillBox.addView(Ui.text(ctx(), todayFullLabel, 11, Ui.INK, false));
            TextView badge = Ui.text(ctx(), "已同步", 10, Ui.PRIMARY, true);
            badge.setGravity(Gravity.CENTER);
            pillBox.addView(badge);
            head.addView(pillBox);
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
        // 2) 主视觉横幅 (纯图, 不叠字)
        body.addView(Ui.bannerImage(ctx(), R.drawable.hero_home, 150, 24), Ui.matchWrap(14, ctx()));

        if (miConnected) {
            // 3) 活力卡
            body.addView(renderVitalityCard(), Ui.matchWrap(18, ctx()));
            // 4) 今日指标 2×2
            body.addView(renderMetricTiles(), Ui.matchWrap(18, ctx()));
            // 5) 健康洞察
            LinearLayout insights = renderInsightsCard();
            if (insights != null) body.addView(insights, Ui.matchWrap(18, ctx()));
        } else {
            // 未连接小米: 连接引导卡替代活力区
            body.addView(renderConnectCard(), Ui.matchWrap(18, ctx()));
        }

        // 6) 体重卡
        body.addView(renderWeightCard(), Ui.matchWrap(18, ctx()));
        // 7) 今日待办
        body.addView(renderActionsCard(), Ui.matchWrap(18, ctx()));
        // 8) 7 天体重波动
        body.addView(renderTrendCard(), Ui.matchWrap(18, ctx()));

        TextView dis = Ui.text(ctx(), "BMI 与数据记录用于健康自我管理，不构成医疗诊断或特定减重速度承诺。", 11, Ui.MUTED, false);
        body.addView(dis, Ui.matchWrap(14, ctx()));
    }

    /* 活力卡: 三环 + 今日健康分 + 三项图例 + 消耗达标横条; 右上角保留小米同步按钮 */
    private LinearLayout renderVitalityCard() {
        LinearLayout card = Ui.card(ctx());
        LinearLayout head = Ui.row(ctx());
        LinearLayout titles = Ui.column(ctx());
        titles.addView(Ui.sectionTitle(ctx(), "小米运动健康 · 今日活力"));
        String synced = mihealth.optString("last_synced_at", "");
        String sub = (synced != null && !synced.isEmpty() && !"null".equals(synced))
                ? "数据已同步于 " + synced : "今日活力数据已就绪";
        titles.addView(Ui.text(ctx(), sub, 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        head.addView(titles, Ui.weight1());
        android.widget.Button refresh = Ui.ghostButton(ctx(), syncing ? "同步中…" : "⚡ 同步");
        refresh.setEnabled(!syncing);
        refresh.setOnClickListener(v -> syncMiHealth());
        head.addView(refresh);
        card.addView(head);

        LinearLayout main = Ui.row(ctx());
        VitalityRings rings = new VitalityRings(ctx());
        rings.setData(todayDay.steps / (double) Insights.STEP_GOAL,
                todayDay.calories / (double) Insights.CAL_GOAL,
                todayDay.workoutMinutes / (double) Insights.WORKOUT_GOAL);
        int rsz = Ui.dp(ctx(), 148);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rsz, rsz);
        rlp.rightMargin = Ui.dp(ctx(), 14);
        main.addView(rings, rlp);

        LinearLayout right = Ui.column(ctx());
        right.addView(Ui.label(ctx(), "今日健康分"));
        LinearLayout scoreRow = Ui.row(ctx());
        int score = Insights.score(todayDay);
        scoreRow.addView(Ui.text(ctx(), String.valueOf(score), 34, Ui.INK, true));
        TextView scoreUnit = Ui.text(ctx(), " 分", 13, Ui.SLATE, false);
        scoreRow.addView(scoreUnit);
        TextView labelChip = Ui.chip(ctx(), Insights.scoreLabel(score), Ui.DEEP, Ui.GREEN_SOFT);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(ctx(), 8);
        scoreRow.addView(labelChip, clp);
        right.addView(scoreRow, Ui.matchWrap(2, ctx()));
        right.addView(legendRow(Ui.PRIMARY, "步数",
                fmt((double) todayDay.steps) + " / " + Insights.STEP_GOAL + " 步"), Ui.matchWrap(10, ctx()));
        right.addView(legendRow(Ui.CAL, "消耗",
                todayDay.calories + " / " + Insights.CAL_GOAL + " 千卡"), Ui.matchWrap(6, ctx()));
        right.addView(legendRow(Ui.MOVE, "运动",
                todayDay.workoutMinutes + " / " + Insights.WORKOUT_GOAL + " 分钟"), Ui.matchWrap(6, ctx()));
        main.addView(right, Ui.weight1());
        card.addView(main, Ui.matchWrap(14, ctx()));

        boolean reached = todayDay.calories >= Insights.CAL_GOAL;
        LinearLayout hint = Ui.row(ctx());
        hint.setBackground(Ui.roundRect(reached ? Ui.GREEN_SOFT : Ui.CAL_SOFT, 10, ctx()));
        int p = Ui.dp(ctx(), 10);
        hint.setPadding(p, p, p, p);
        TextView ht = Ui.text(ctx(), (reached ? "🔥 " : "🎯 ") + Insights.calorieHint(todayDay.calories),
                13, reached ? Ui.DEEP : Color.parseColor("#9a3412"), true);
        hint.addView(ht, Ui.weight1());
        card.addView(hint, Ui.matchWrap(14, ctx()));
        return card;
    }

    private LinearLayout legendRow(int color, String name, String value) {
        LinearLayout row = Ui.row(ctx());
        View dot = new View(ctx());
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(color);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(ctx(), 8), Ui.dp(ctx(), 8));
        dl.rightMargin = Ui.dp(ctx(), 6);
        row.addView(dot, dl);
        TextView n = Ui.text(ctx(), name, 12, Ui.SLATE, false);
        row.addView(n, Ui.weight1());
        row.addView(Ui.text(ctx(), value, 12, Ui.INK, true));
        return row;
    }

    /* 未连接小米: 图片横幅 + 引导文案 + 连接按钮 (跳我的页) */
    private LinearLayout renderConnectCard() {
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.bannerImage(ctx(), R.drawable.img_connect, 150, 24));
        card.addView(Ui.sectionTitle(ctx(), "连接小米运动健康，解锁活力数据"), Ui.matchWrap(12, ctx()));
        card.addView(Ui.text(ctx(), "同步手环/手表的步数、消耗、睡眠与运动数据，首页会自动生成健康分、达标提醒和锻炼建议。",
                13, Ui.SLATE, false), Ui.matchWrap(6, ctx()));
        android.widget.Button go = Ui.primaryButton(ctx(), "去连接小米运动健康");
        go.setOnClickListener(v -> activity().switchTab(4));
        card.addView(go, Ui.matchWrap(12, ctx()));
        return card;
    }

    /* 今日指标 2×2: 步数/消耗/睡眠/运动, 每块回答「达标没、差多少」 */
    private LinearLayout renderMetricTiles() {
        LinearLayout col = Ui.column(ctx());
        LinearLayout row1 = Ui.row(ctx());
        String stepSub = todayDay.steps >= Insights.STEP_GOAL ? "已达标 🎉"
                : "还差 " + fmt((double) (Insights.STEP_GOAL - todayDay.steps)) + " 步";
        row1.addView(Ui.metricTile(ctx(), Ui.PRIMARY, Ui.GREEN_SOFT, "👟", "步数",
                fmt((double) todayDay.steps), stepSub,
                todayDay.steps / (double) Insights.STEP_GOAL), tileLp(true));
        String calSub = todayDay.calories >= Insights.CAL_GOAL ? "已达标 🎉"
                : "还差 " + (Insights.CAL_GOAL - todayDay.calories) + " 千卡";
        row1.addView(Ui.metricTile(ctx(), Ui.CAL, Ui.CAL_SOFT, "🔥", "活动消耗 · 千卡",
                String.valueOf(todayDay.calories), calSub,
                todayDay.calories / (double) Insights.CAL_GOAL), tileLp(false));
        col.addView(row1);

        LinearLayout row2 = Ui.row(ctx());
        Double sleep = num(miToday, "sleep_hours");
        String sleepVal = sleep != null ? fmt(sleep) + " 小时" : "--";
        String sleepSub = sleep == null ? "手环自动监测"
                : (sleep >= 7 ? "作息充足" : "建议睡满 8 小时");
        row2.addView(Ui.metricTile(ctx(), Ui.SLEEP, Ui.SLEEP_SOFT, "🌙", "昨夜睡眠",
                sleepVal, sleepSub, sleep != null ? sleep / Insights.SLEEP_GOAL : -1), tileLp(true));
        String woSub = todayDay.workoutMinutes >= Insights.WORKOUT_GOAL ? "今日达标 🎉"
                : "还差 " + (Insights.WORKOUT_GOAL - todayDay.workoutMinutes) + " 分钟";
        row2.addView(Ui.metricTile(ctx(), Ui.MOVE, Ui.MOVE_SOFT, "⏱️", "运动 · 分钟",
                String.valueOf(todayDay.workoutMinutes), woSub,
                todayDay.workoutMinutes / (double) Insights.WORKOUT_GOAL), tileLp(false));
        LinearLayout.LayoutParams r2 = Ui.matchWrap(10, ctx());
        col.addView(row2, r2);
        return col;
    }

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
    private LinearLayout renderWeightCard() {
        LinearLayout card = Ui.card(ctx());
        LinearLayout top = Ui.row(ctx());
        LinearLayout left = Ui.column(ctx());
        left.addView(Ui.label(ctx(), "当前体重 · " + goalLabelText));
        boolean fromMi = "mihealth".equals(data.optString("current_weight_source", ""));
        left.addView(Ui.text(ctx(), fromMi ? "数据源自小米运动健康同步" : "持续记录，见证身体点滴变化",
                11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        top.addView(left, Ui.weight1());
        TextView goalPill = pill("目标 " + fmt(num(data, "target_weight")) + " kg"
                + (remainingWeight.isEmpty() ? "" : " · " + remainingWeight),
                Color.parseColor("#d1fae5"), Ui.PRIMARY);
        top.addView(goalPill);
        card.addView(top);

        LinearLayout wRow = Ui.row(ctx());
        wRow.addView(Ui.text(ctx(), fmt(num(data, "current_weight")), 34, Ui.INK, true));
        wRow.addView(Ui.text(ctx(), " kg", 16, Ui.SLATE, false));
        if (!diffLabel.isEmpty()) {
            TextView dp2 = pill(diffLabel, Ui.BLUE_SOFT, Color.parseColor("#2563eb"));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.leftMargin = Ui.dp(ctx(), 8);
            wRow.addView(dp2, dl);
        }
        Double bmi = num(data, "bmi");
        if (bmi != null && bmi > 0) {
            TextView bp = pill("BMI " + fmt(bmi) + " · " + bmiStatus, pillBg(bmiPillKind), pillFg(bmiPillKind));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.leftMargin = Ui.dp(ctx(), 8);
            wRow.addView(bp, bl);
        }
        card.addView(wRow, Ui.matchWrap(10, ctx()));

        if (showGoalProgress) {
            FrameLayout bar = Ui.progressBar(ctx(), goalProgressVal / 100.0, Ui.PRIMARY, 8);
            LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            blp.topMargin = Ui.dp(ctx(), 12);
            bar.setLayoutParams(blp);
            card.addView(bar);
            LinearLayout foot = Ui.row(ctx());
            TextView fl = Ui.text(ctx(), progressLabel, 12, Ui.MUTED, false);
            foot.addView(fl, Ui.weight1());
            foot.addView(Ui.text(ctx(), goalProgressVal + "%", 12, Ui.PRIMARY, true));
            card.addView(foot, Ui.matchWrap(6, ctx()));
        }

        android.widget.Button record = Ui.primaryButton(ctx(), "记体重");
        record.setOnClickListener(v -> activity().switchTab(2));
        card.addView(record, Ui.matchWrap(14, ctx()));
        return card;
    }

    /* 今日待办 (保留原 todos 逻辑与两个入口) */
    private LinearLayout renderActionsCard() {
        LinearLayout card = Ui.card(ctx());
        LinearLayout head = Ui.row(ctx());
        LinearLayout titles = Ui.column(ctx());
        titles.addView(Ui.sectionTitle(ctx(), "今天，照顾好自己"));
        titles.addView(Ui.text(ctx(), "小行动比完美计划更重要", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        head.addView(titles, Ui.weight1());
        head.addView(pill(todos.size() + " 项行动", Color.parseColor("#d1fae5"), Ui.PRIMARY));
        card.addView(head);
        for (String todo : todos) {
            LinearLayout row = Ui.row(ctx());
            TextView check = Ui.text(ctx(), "✓", 11, Ui.PRIMARY, true);
            check.setGravity(Gravity.CENTER);
            GradientDrawable circ = new GradientDrawable();
            circ.setShape(GradientDrawable.OVAL);
            circ.setColor(Color.parseColor("#d1fae5"));
            check.setBackground(circ);
            int sz = Ui.dp(ctx(), 22);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(sz, sz);
            cl.rightMargin = Ui.dp(ctx(), 8);
            row.addView(check, cl);
            TextView tx = Ui.text(ctx(), todo, 13, Ui.INK, false);
            row.addView(tx, Ui.weight1());
            row.addView(Ui.text(ctx(), "已规划", 11, Ui.MUTED, false));
            card.addView(row, Ui.matchWrap(10, ctx()));
        }
        LinearLayout btnRow = Ui.row(ctx());
        android.widget.Button main = Ui.primaryButton(ctx(), "＋ 记录今天");
        main.setOnClickListener(v -> activity().switchTab(2));
        LinearLayout.LayoutParams bl = Ui.weight1(); bl.rightMargin = Ui.dp(ctx(), 6);
        btnRow.addView(main, bl);
        android.widget.Button sub = Ui.ghostButton(ctx(), "⚡ 记一笔运动");
        sub.setOnClickListener(v -> activity().openExercise());
        LinearLayout.LayoutParams br = Ui.weight1(); br.leftMargin = Ui.dp(ctx(), 6);
        btnRow.addView(sub, br);
        card.addView(btnRow, Ui.matchWrap(14, ctx()));
        return card;
    }

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
        TextView chip = Ui.text(ctx(), label, 13, active ? Color.WHITE : Ui.SLATE, active);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.roundRect(active ? Ui.PRIMARY : Ui.SOFT, 10, ctx()));
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

    private int pillBg(String kind) {
        switch (kind) {
            case "blue": return Color.parseColor("#dbeafe");
            case "amber": return Color.parseColor("#fef3c7");
            case "purple": return Color.parseColor("#ede9fe");
            default: return Color.parseColor("#d1fae5");
        }
    }

    private int pillFg(String kind) {
        switch (kind) {
            case "blue": return Color.parseColor("#2563eb");
            case "amber": return Color.parseColor("#d97706");
            case "purple": return Color.parseColor("#7c3aed");
            default: return Ui.PRIMARY;
        }
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
