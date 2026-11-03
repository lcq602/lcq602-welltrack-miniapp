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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 首页 (对应小程序 pages/index): 未配置时是建立健康起点表单,
 * 已配置时是仪表盘 (状态 Hero / BMI 与打卡 / 小米活力 / 今日待办 / 7 天趋势 / 探索卡片)。
 * 数据: GET /api/v1/dashboard; 建档: POST /api/v1/profile; 同步: POST /api/v1/integrations/mihealth/sync。
 */
public class IndexPage extends BasePage {
    private static final String[] GOAL_KEYS = {"weight_loss", "fat_loss", "maintenance", "healthy_habits"};
    private static final String[] GOAL_LABELS = {"减重", "减脂", "维持", "健康习惯"};
    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};

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
    private int stepProgress = 0, calProgress = 0;
    private JSONObject mihealth, miToday;
    private final List<String> todos = new ArrayList<>();

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
        List<Double> values = new ArrayList<>();
        if (records != null) {
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null) continue;
                String d = r.optString("record_date", "");
                Double w = num(r, "weight");
                recordByDate.put(d, Boolean.TRUE);
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
        for (int i = 0; i < 7; i++) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -(6 - i));
            String ds = String.format(Locale.US, "%04d-%02d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
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
        // 小米活力
        mihealth = data.optJSONObject("mihealth");
        if (mihealth == null) mihealth = new JSONObject();
        miToday = mihealth.optJSONObject("today");
        if (miToday == null) miToday = new JSONObject();
        int steps = miToday.optInt("steps", 0);
        double cals = miToday.optDouble("calories", 0);
        stepProgress = Math.min(100, (int) Math.round(steps / 8000.0 * 100));
        calProgress = Math.min(100, (int) Math.round(cals / 400.0 * 100));
        // 待办
        todos.clear();
        JSONArray td = data.optJSONArray("todos");
        if (td != null) for (int i = 0; i < td.length(); i++) todos.add(td.optString(i, ""));
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
        if (!configured) renderSetupForm();
        else renderDashboard();
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

    /* ---- 已配置: 仪表盘 ---- */

    private void renderDashboard() {
        renderHero();
        renderBento();
        renderVitality();
        renderActions();
        renderTrendCard();
        renderJourney();
        TextView dis = Ui.text(ctx(), "BMI 与数据记录用于健康自我管理，不构成医疗诊断或特定减重速度承诺。", 11, Ui.MUTED, false);
        body.addView(dis, Ui.matchWrap(14, ctx()));
    }

    private void renderHero() {
        LinearLayout card = Ui.card(ctx());
        LinearLayout top = Ui.row(ctx());
        LinearLayout left = Ui.column(ctx());
        left.addView(Ui.label(ctx(), "当前状态"));
        TextView goalPill = pill("✳ " + goalLabelText, Color.parseColor("#d1fae5"), Ui.PRIMARY);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = Ui.dp(ctx(), 6);
        left.addView(goalPill, gp);
        top.addView(left, Ui.weight1());
        String hint = "目标 " + fmt(num(data, "target_weight")) + " kg"
                + (remainingWeight.isEmpty() ? "" : " · " + remainingWeight);
        TextView hintTv = Ui.text(ctx(), hint, 12, Ui.SLATE, false);
        hintTv.setGravity(Gravity.RIGHT);
        top.addView(hintTv);
        card.addView(top);

        LinearLayout main = Ui.row(ctx());
        LinearLayout weightCol = Ui.column(ctx());
        LinearLayout wRow = Ui.row(ctx());
        wRow.addView(Ui.text(ctx(), fmt(num(data, "current_weight")), 34, Ui.INK, true));
        TextView unit = Ui.text(ctx(), " kg", 16, Ui.SLATE, false);
        wRow.addView(unit);
        if (!diffLabel.isEmpty()) {
            TextView dp2 = pill(diffLabel, Ui.BLUE_SOFT, Color.parseColor("#2563eb"));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.leftMargin = Ui.dp(ctx(), 8);
            wRow.addView(dp2, dl);
        }
        weightCol.addView(wRow);
        boolean fromMi = "mihealth".equals(data.optString("current_weight_source", ""));
        weightCol.addView(Ui.text(ctx(), fromMi ? "数据源自小米运动健康同步" : "持续记录，见证身体点滴变化",
                12, Ui.MUTED, false), Ui.matchWrap(4, ctx()));
        main.addView(weightCol, Ui.weight1());
        if (showGoalProgress) {
            LinearLayout gauge = Ui.column(ctx());
            gauge.setGravity(Gravity.CENTER);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.WHITE);
            ring.setCornerRadius(Ui.dp(ctx(), 40));
            ring.setStroke(Ui.dp(ctx(), 3), Ui.PRIMARY);
            gauge.setBackground(ring);
            TextView pct = Ui.text(ctx(), goalProgressVal + "%", 17, Ui.PRIMARY, true);
            pct.setGravity(Gravity.CENTER);
            gauge.addView(pct);
            TextView gl = Ui.text(ctx(), "达成", 10, Ui.MUTED, false);
            gl.setGravity(Gravity.CENTER);
            gauge.addView(gl);
            int sz = Ui.dp(ctx(), 76);
            main.addView(gauge, new LinearLayout.LayoutParams(sz, sz));
        }
        card.addView(main, Ui.matchWrap(12, ctx()));

        if (showGoalProgress) {
            card.addView(progressTrack(goalProgressVal, Ui.PRIMARY, 8), Ui.matchWrap(12, ctx()));
        }
        LinearLayout foot = Ui.row(ctx());
        TextView fl = Ui.text(ctx(), progressLabel, 12, Ui.MUTED, false);
        foot.addView(fl, Ui.weight1());
        foot.addView(Ui.text(ctx(), goalProgressVal + "%", 12, Ui.PRIMARY, true));
        card.addView(foot, Ui.matchWrap(8, ctx()));
        body.addView(card, Ui.matchWrap(14, ctx()));
    }

    private void renderBento() {
        LinearLayout grid = Ui.row(ctx());
        // BMI 磁贴
        LinearLayout bmiTile = Ui.card(ctx());
        LinearLayout bh = Ui.row(ctx());
        TextView bt = Ui.text(ctx(), "BMI 指数", 13, Ui.INK, true);
        bh.addView(bt, Ui.weight1());
        bh.addView(pill(bmiStatus, pillBg(bmiPillKind), pillFg(bmiPillKind)));
        bmiTile.addView(bh);
        bmiTile.addView(Ui.text(ctx(), fmt(num(data, "bmi")), 24, Ui.INK, true), Ui.matchWrap(8, ctx()));
        bmiTile.addView(Ui.text(ctx(), "标准区间 18.5 ~ 23.9", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        LinearLayout spectrum = Ui.row(ctx());
        spectrum.addView(specSeg(Color.parseColor("#93c5fd"), 1f));
        spectrum.addView(specSeg(Color.parseColor("#34d399"), 1.4f));
        spectrum.addView(specSeg(Color.parseColor("#fbbf24"), 1f));
        bmiTile.addView(spectrum, Ui.matchWrap(10, ctx()));
        LinearLayout specLabels = Ui.row(ctx());
        specLabels.addView(specLabel("偏瘦", Gravity.LEFT));
        specLabels.addView(specLabel("● 正常", Gravity.CENTER));
        specLabels.addView(specLabel("偏重", Gravity.RIGHT));
        bmiTile.addView(specLabels, Ui.matchWrap(4, ctx()));
        LinearLayout.LayoutParams lpA = Ui.weight1(); lpA.rightMargin = Ui.dp(ctx(), 6);
        grid.addView(bmiTile, lpA);

        // 打卡磁贴
        LinearLayout streakTile = Ui.card(ctx());
        LinearLayout sh = Ui.row(ctx());
        TextView st = Ui.text(ctx(), "7天记录打卡", 13, Ui.INK, true);
        sh.addView(st, Ui.weight1());
        sh.addView(Ui.text(ctx(), "🔥", 14, Ui.INK, false));
        streakTile.addView(sh);
        LinearLayout valRow = Ui.row(ctx());
        valRow.addView(Ui.text(ctx(), String.valueOf(data.optInt("record_days_7d", 0)), 24, Ui.INK, true));
        valRow.addView(Ui.text(ctx(), "/7 天", 12, Ui.MUTED, false));
        streakTile.addView(valRow, Ui.matchWrap(8, ctx()));
        streakTile.addView(Ui.text(ctx(), "近7天有记录天数", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        LinearLayout dots = Ui.row(ctx());
        for (TrendDay t : trend) {
            TextView dotTv = Ui.text(ctx(), t.hasRecord ? "✓" : "", 10, Color.WHITE, true);
            dotTv.setGravity(Gravity.CENTER);
            GradientDrawable circ = new GradientDrawable();
            circ.setShape(GradientDrawable.OVAL);
            circ.setColor(t.hasRecord ? Ui.PRIMARY : Ui.SOFT);
            if (t.isToday) circ.setStroke(Ui.dp(ctx(), 2), Ui.PRIMARY_DARK);
            dotTv.setBackground(circ);
            int sz = Ui.dp(ctx(), 20);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(sz, sz);
            dl.rightMargin = Ui.dp(ctx(), 4);
            dots.addView(dotTv, dl);
        }
        streakTile.addView(dots, Ui.matchWrap(10, ctx()));
        LinearLayout.LayoutParams lpB = Ui.weight1(); lpB.leftMargin = Ui.dp(ctx(), 6);
        grid.addView(streakTile, lpB);
        body.addView(grid, Ui.matchWrap(12, ctx()));

        // 运动活力宽磁贴
        LinearLayout act = Ui.card(ctx());
        LinearLayout row = Ui.row(ctx());
        TextView iconBox = Ui.text(ctx(), "🏃", 20, Ui.INK, false);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(Ui.roundRect(Ui.BLUE_SOFT, 12, ctx()));
        int isz = Ui.dp(ctx(), 44);
        row.addView(iconBox, new LinearLayout.LayoutParams(isz, isz));
        LinearLayout info = Ui.column(ctx());
        LinearLayout titleRow = Ui.row(ctx());
        titleRow.addView(Ui.text(ctx(), "运动活力", 14, Ui.INK, true));
        TextView wk = pill("本周达标", Color.parseColor("#dbeafe"), Color.parseColor("#2563eb"));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.leftMargin = Ui.dp(ctx(), 6);
        titleRow.addView(wk, wl);
        info.addView(titleRow);
        int exCount = data.optInt("exercise_count_7d", 0);
        info.addView(Ui.text(ctx(), "本周 " + exCount + " 次运动打卡", 12, Ui.SLATE, false), Ui.matchWrap(3, ctx()));
        info.addView(Ui.text(ctx(), "坚持活动，提升日常心肺与代谢", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        LinearLayout.LayoutParams il = Ui.weight1(); il.leftMargin = Ui.dp(ctx(), 10);
        row.addView(info, il);
        LinearLayout badge = Ui.column(ctx());
        badge.setGravity(Gravity.CENTER);
        TextView bn = Ui.text(ctx(), String.valueOf(exCount), 22, Ui.PRIMARY, true);
        bn.setGravity(Gravity.CENTER);
        badge.addView(bn);
        TextView bu = Ui.text(ctx(), "次", 10, Ui.MUTED, false);
        bu.setGravity(Gravity.CENTER);
        badge.addView(bu);
        row.addView(badge);
        act.addView(row);
        body.addView(act, Ui.matchWrap(12, ctx()));
    }

    private void renderVitality() {
        LinearLayout card = Ui.card(ctx());
        boolean connected = mihealth.optBoolean("connected", false);
        LinearLayout head = Ui.row(ctx());
        View dot = new View(ctx());
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(connected ? Ui.PRIMARY : Ui.BORDER);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(ctx(), 9), Ui.dp(ctx(), 9));
        dl.rightMargin = Ui.dp(ctx(), 8);
        head.addView(dot, dl);
        LinearLayout titles = Ui.column(ctx());
        titles.addView(Ui.sectionTitle(ctx(), "小米运动健康 · 活力看板"));
        String sub;
        if (connected) {
            String synced = mihealth.optString("last_synced_at", "");
            sub = (synced != null && !synced.isEmpty() && !"null".equals(synced))
                    ? "数据已同步于 " + synced : "今日活力数据已就绪";
        } else {
            sub = "连接小米手环/手表，自动同步全天活力";
        }
        titles.addView(Ui.text(ctx(), sub, 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        head.addView(titles, Ui.weight1());
        if (connected) {
            android.widget.Button refresh = Ui.ghostButton(ctx(), syncing ? "同步中…" : "⚡ 刷新");
            refresh.setOnClickListener(v -> syncMiHealth());
            head.addView(refresh);
        } else {
            TextView go = pill("去连接 ›", Color.parseColor("#dbeafe"), Color.parseColor("#2563eb"));
            go.setOnClickListener(v -> activity().switchTab(4));
            head.addView(go);
        }
        card.addView(head);

        LinearLayout row1 = Ui.row(ctx());
        row1.addView(vitalityTile("今日步数", "👟", fmt((double) miToday.optInt("steps", 0)), "步",
                stepProgress, "目标 8,000 步 · " + stepProgress + "%", Color.parseColor("#ecfdf5")), tileLp(true));
        row1.addView(vitalityTile("消耗能量", "🔥", fmt(miToday.optDouble("calories", 0)), "kcal",
                calProgress, "日常动态代谢消耗", Color.parseColor("#fffbeb")), tileLp(false));
        card.addView(row1, Ui.matchWrap(12, ctx()));

        Double sleep = num(miToday, "sleep_hours");
        String sleepFoot = sleep != null && sleep >= 7 ? "✨ 作息充足" : (sleep != null ? "需注意补觉" : "手环自动监测");
        double workout = miToday.optDouble("workout_minutes", 0);
        String workFoot = workout >= 30 ? "🔥 今日达标" : "建议每日 30 分钟";
        LinearLayout row2 = Ui.row(ctx());
        row2.addView(vitalityTile("昨夜睡眠", "🌙", fmt(sleep), "小时",
                -1, sleepFoot, Color.parseColor("#eef2ff")), tileLp(true));
        row2.addView(vitalityTile("有效运动", "⏱️", fmt(workout), "分钟",
                -1, workFoot, Color.parseColor("#f5f3ff")), tileLp(false));
        card.addView(row2, Ui.matchWrap(8, ctx()));

        if (!connected) {
            LinearLayout hint = Ui.row(ctx());
            hint.setBackground(Ui.roundRect(Ui.BLUE_SOFT, 10, ctx()));
            int p = Ui.dp(ctx(), 10);
            hint.setPadding(p, p, p, p);
            TextView ht = Ui.text(ctx(), "💡 支持小米手环、手表与体脂秤数据无感同步", 12, Ui.SLATE, false);
            hint.addView(ht, Ui.weight1());
            hint.addView(Ui.text(ctx(), "去绑定 ›", 12, Color.parseColor("#2563eb"), true));
            hint.setOnClickListener(v -> activity().switchTab(4));
            card.addView(hint, Ui.matchWrap(10, ctx()));
        }
        body.addView(card, Ui.matchWrap(12, ctx()));
    }

    private LinearLayout.LayoutParams tileLp(boolean left) {
        LinearLayout.LayoutParams lp = Ui.weight1();
        if (left) lp.rightMargin = Ui.dp(ctx(), 5); else lp.leftMargin = Ui.dp(ctx(), 5);
        return lp;
    }

    private LinearLayout vitalityTile(String label, String icon, String value, String unit,
                                      int progress, String foot, int bg) {
        LinearLayout tile = Ui.column(ctx());
        tile.setBackground(Ui.roundRect(bg, 12, ctx()));
        int p = Ui.dp(ctx(), 12);
        tile.setPadding(p, p, p, p);
        LinearLayout top = Ui.row(ctx());
        TextView l = Ui.text(ctx(), label, 12, Ui.SLATE, false);
        top.addView(l, Ui.weight1());
        top.addView(Ui.text(ctx(), icon, 13, Ui.INK, false));
        tile.addView(top);
        LinearLayout valRow = Ui.row(ctx());
        valRow.addView(Ui.text(ctx(), value, 20, Ui.INK, true));
        valRow.addView(Ui.text(ctx(), " " + unit, 11, Ui.MUTED, false));
        tile.addView(valRow, Ui.matchWrap(4, ctx()));
        if (progress >= 0) tile.addView(progressTrack(progress, Ui.PRIMARY, 5), Ui.matchWrap(8, ctx()));
        tile.addView(Ui.text(ctx(), foot, 11, Ui.MUTED, false), Ui.matchWrap(6, ctx()));
        return tile;
    }

    private void renderActions() {
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
        body.addView(card, Ui.matchWrap(12, ctx()));
    }

    private void renderTrendCard() {
        LinearLayout card = Ui.card(ctx());
        LinearLayout head = Ui.row(ctx());
        LinearLayout titles = Ui.column(ctx());
        titles.addView(Ui.sectionTitle(ctx(), "7天体重波动"));
        titles.addView(Ui.text(ctx(), "平稳渐进 · 关注整体走势", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        head.addView(titles, Ui.weight1());
        LinearLayout right = Ui.column(ctx());
        right.setGravity(Gravity.RIGHT);
        right.addView(pill("目标 " + fmt(num(data, "target_weight")) + " kg", Ui.SOFT, Ui.SLATE));
        TextView link = Ui.text(ctx(), "看分析 ›", 12, Ui.PRIMARY, true);
        link.setGravity(Gravity.RIGHT);
        link.setOnClickListener(v -> activity().switchTab(3));
        right.addView(link, Ui.matchWrap(6, ctx()));
        head.addView(right);
        card.addView(head);

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
        body.addView(card, Ui.matchWrap(12, ctx()));
    }

    private void renderJourney() {
        LinearLayout head = Ui.row(ctx());
        TextView h = Ui.text(ctx(), "开启你的健康旅程", 15, Ui.INK, true);
        head.addView(h, Ui.weight1());
        head.addView(Ui.text(ctx(), "滑动查看", 11, Ui.MUTED, false));
        body.addView(head, Ui.matchWrap(16, ctx()));

        HorizontalScrollView hsv = new HorizontalScrollView(ctx());
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout track = Ui.row(ctx());
        track.addView(journeyCard("📏", "身体基线", "用体重、腰围和 BMI，建立自己的观察基准。", "开始记录 ›",
                Color.parseColor("#ecfdf5"), () -> activity().switchTab(2)));
        track.addView(journeyCard("📅", "周训练计划", "为这一周安排力量、有氧与放松恢复。", "查看周计划 ›",
                Color.parseColor("#eff6ff"), () -> activity().switchTab(1)));
        track.addView(journeyCard("📈", "每一步看得见", "复盘生理波动、睡眠时长与小习惯坚持度。", "查看趋势复盘 ›",
                Color.parseColor("#f5f3ff"), () -> activity().switchTab(3)));
        hsv.addView(track);
        body.addView(hsv, Ui.matchWrap(8, ctx()));
    }

    private LinearLayout journeyCard(String icon, String title, String desc, String link, int bg, Runnable action) {
        LinearLayout card = Ui.column(ctx());
        card.setBackground(Ui.roundRect(bg, 16, ctx()));
        int p = Ui.dp(ctx(), 14);
        card.setPadding(p, p, p, p);
        card.addView(Ui.text(ctx(), icon, 22, Ui.INK, false));
        card.addView(Ui.text(ctx(), title, 14, Ui.INK, true), Ui.matchWrap(8, ctx()));
        card.addView(Ui.text(ctx(), desc, 12, Ui.SLATE, false), Ui.matchWrap(4, ctx()));
        card.addView(Ui.text(ctx(), link, 12, Ui.PRIMARY, true), Ui.matchWrap(8, ctx()));
        card.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 218), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(ctx(), 10);
        card.setLayoutParams(lp);
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

    private View specSeg(int color, float weight) {
        View v = new View(ctx());
        v.setBackground(Ui.roundRect(color, 3, ctx()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(ctx(), 6), weight);
        lp.rightMargin = Ui.dp(ctx(), 3);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView specLabel(String s, int gravity) {
        TextView t = Ui.text(ctx(), s, 10, Ui.MUTED, false);
        t.setGravity(gravity);
        LinearLayout.LayoutParams lp = Ui.weight1();
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout progressTrack(int progress, int fillColor, int heightDp) {
        LinearLayout track = Ui.row(ctx());
        track.setBackground(Ui.roundRect(Ui.BORDER, heightDp / 2f, ctx()));
        if (progress > 0) {
            View fill = new View(ctx());
            fill.setBackground(Ui.roundRect(fillColor, heightDp / 2f, ctx()));
            track.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(ctx(), heightDp), progress));
        }
        if (progress < 100) {
            View rest = new View(ctx());
            track.addView(rest, new LinearLayout.LayoutParams(0, Ui.dp(ctx(), heightDp), 100 - progress));
        }
        return track;
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
