package art.qelora.welltrack.pages;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.health.Insights;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 趋势页 (对应小程序 pages/analysis): 周期切换 + 摘要 Bento + 日历 +
 * 四个维度的图表 (体重/腰围折线、步数柱状、能量柱状、睡眠运动对照条)。
 * 图表数据计算与 analysis.js 的 buildChart / stepBars / calBars / vitalityRows 等价。
 */
public class AnalysisPage extends BasePage {
    // v2 指标专属色: 睡眠紫 / 消耗橙 / 运动蓝 (与 Ui 指标色体系对齐)
    private static final int PURPLE = Ui.SLEEP;
    private static final int PURPLE_SOFT = Ui.SLEEP_SOFT;
    private static final int AMBER = Ui.CAL;
    private static final int AMBER_BAR = Ui.CAL;
    private static final int AMBER_SOFT = Ui.CAL_SOFT;
    private static final int BLUE = Ui.MOVE;

    private String period = "week";
    private String chartTab = "weight";
    private String calendarMonth = currentMonth();
    private JSONObject summary;
    private String loadError = "";
    private boolean loading = true;

    private LinearLayout body;
    private TextView insightPill;
    private final TextView[] periodPills = new TextView[3];
    private static final String[] PERIOD_KEYS = {"week", "month", "calendar"};
    private static final String[] PERIOD_LABELS = {"本周", "本月", "日历"};

    public AnalysisPage(Context ctx) { super(ctx); }

    @Override
    protected void build() {
        // Header
        LinearLayout head = Ui.row(ctx());
        LinearLayout headLeft = Ui.column(ctx());
        LinearLayout eyebrow = Ui.row(ctx());
        View dot = new View(ctx());
        int ds = Ui.dp(ctx(), 7);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dlp.rightMargin = Ui.dp(ctx(), 6);
        dot.setLayoutParams(dlp);
        dot.setBackground(Ui.roundRect(Ui.PRIMARY, 4, ctx()));
        eyebrow.addView(dot);
        eyebrow.addView(Ui.text(ctx(), "SMALL STEPS, CLEARER PICTURE", 10, Ui.MUTED, true));
        headLeft.addView(eyebrow);
        TextView title = Ui.title(ctx(), "数据复盘");
        LinearLayout.LayoutParams tlp = Ui.matchWrap(4, ctx());
        headLeft.addView(title, tlp);
        head.addView(headLeft, Ui.weight1());
        insightPill = pill("趋势洞察", Ui.GREEN_SOFT, Ui.PRIMARY_DARK);
        insightPill.setVisibility(GONE);
        head.addView(insightPill);
        content.addView(head);

        // 周期切换
        LinearLayout switchBar = Ui.row(ctx());
        switchBar.setBackground(Ui.roundRect(Ui.SOFT, 999, ctx()));
        switchBar.setPadding(Ui.dp(ctx(), 4), Ui.dp(ctx(), 4), Ui.dp(ctx(), 4), Ui.dp(ctx(), 4));
        for (int i = 0; i < 3; i++) {
            final String key = PERIOD_KEYS[i];
            TextView p = Ui.text(ctx(), PERIOD_LABELS[i], 13, Ui.SLATE, false);
            p.setGravity(Gravity.CENTER);
            p.setPadding(0, Ui.dp(ctx(), 7), 0, Ui.dp(ctx(), 7));
            p.setOnClickListener(v -> {
                period = key;
                stylePeriodPills();
                load();
            });
            periodPills[i] = p;
            switchBar.addView(p, Ui.weight1());
        }
        content.addView(switchBar, Ui.matchWrap(14, ctx()));
        stylePeriodPills();

        body = Ui.column(ctx());
        content.addView(body, Ui.matchWrap(14, ctx()));
        renderBody();
    }

    @Override
    public void onShow() {
        super.onShow();
        load();
    }

    private void stylePeriodPills() {
        for (int i = 0; i < 3; i++) {
            boolean active = PERIOD_KEYS[i].equals(period);
            periodPills[i].setBackground(active ? Ui.roundRect(Color.parseColor("#2e362e"), 999, ctx()) : null);
            periodPills[i].setTextColor(active ? Ui.INK : Ui.SLATE);
            periodPills[i].setTypeface(active ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    /* ---------------- 数据加载 ---------------- */

    private void load() {
        loading = summary == null;
        loadError = "";
        if (loading) renderBody();
        String monthParam = period.equals("week") ? "" : "&month=" + calendarMonth;
        api().request("/api/v1/analytics?period=" + period + monthParam, "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                summary = data;
                loadError = "";
                loading = false;
                renderBody();
            }
            @Override public void onError(String message) {
                loading = false;
                loadError = (message == null || message.isEmpty()) ? "统计读取失败" : message;
                renderBody();
            }
        });
    }

    /* ---------------- 渲染 ---------------- */

    private void renderBody() {
        if (body == null) return;
        insightPill.setVisibility(summary != null ? VISIBLE : GONE);
        body.removeAllViews();

        if (summary == null) {
            if (!loading && !loadError.isEmpty()) {
                LinearLayout card = Ui.card(ctx());
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView mark = Ui.text(ctx(), "!", 22, Ui.RED, true);
                mark.setGravity(Gravity.CENTER);
                mark.setBackground(Ui.roundRect(Ui.RED_SOFT, 999, ctx()));
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 44), Ui.dp(ctx(), 44));
                card.addView(mark, mlp);
                TextView t = Ui.sectionTitle(ctx(), "暂时无法读取统计");
                card.addView(t, Ui.matchWrap(10, ctx()));
                TextView e = Ui.text(ctx(), loadError, 13, Ui.SLATE, false);
                e.setGravity(Gravity.CENTER);
                card.addView(e, Ui.matchWrap(6, ctx()));
                Button retry = Ui.primaryButton(ctx(), "重新加载");
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 140), ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = Ui.dp(ctx(), 14);
                retry.setLayoutParams(blp);
                retry.setOnClickListener(v -> load());
                card.addView(retry);
                body.addView(card);
            } else {
                LinearLayout card = Ui.card(ctx());
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView t = Ui.text(ctx(), "正在整理你的健康变化…", 14, Ui.INK, false);
                t.setGravity(Gravity.CENTER);
                card.addView(t);
                TextView s = Ui.text(ctx(), "WELLTRACK · 每一步都看得见", 10, Ui.MUTED, false);
                card.addView(s, Ui.matchWrap(6, ctx()));
                body.addView(card);
            }
            return;
        }

        // 日期范围行
        LinearLayout rangeRow = Ui.row(ctx());
        TextView range = pill("📅 " + summary.optString("start") + " — " + summary.optString("end"),
                Ui.SOFT, Ui.SLATE);
        rangeRow.addView(range);
        if (!loadError.isEmpty()) {
            TextView cache = pill("显示上次缓存", AMBER_SOFT, AMBER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Ui.dp(ctx(), 8);
            rangeRow.addView(cache, clp);
        }
        body.addView(rangeRow);

        body.addView(insightsCard(), Ui.matchWrap(14, ctx()));

        buildBento();

        if (period.equals("calendar")) {
            body.addView(calendarCard(), Ui.matchWrap(18, ctx()));
        } else {
            body.addView(trendCard(), Ui.matchWrap(18, ctx()));
        }

        TextView foot = Ui.text(ctx(),
                "数据来自手环自动同步与手工日常记录，用于长期生活节律复盘，不构成临床医疗诊断。",
                11, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        body.addView(foot, Ui.matchWrap(18, ctx()));
    }

    private TextView pill(String text, int bg, int fg) {
        TextView t = Ui.text(ctx(), text, 11, fg, true);
        t.setBackground(Ui.roundRect(bg, 999, ctx()));
        t.setPadding(Ui.dp(ctx(), 10), Ui.dp(ctx(), 5), Ui.dp(ctx(), 10), Ui.dp(ctx(), 5));
        return t;
    }

    /* ---------------- 健康分析 (Insights 规则引擎) ---------------- */

    /**
     * 健康分析主卡: 用所选周期的日均数据组装 Insights.Day 算健康分,
     * Week 由 analytics 的 days 数组计算 (运动天数、周期内体重首尾差),
     * 给出可执行的锻炼/作息建议 (最多 3 条)。todayPlan 传 null (本页无周计划上下文)。
     */
    private LinearLayout insightsCard() {
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.sectionHeader(ctx(), "健康分析", null, null));

        JSONArray days = days();
        double avgSteps = summary.optDouble("average_steps", 0);
        double avgCals = summary.optDouble("average_calories", 0);
        double avgSleep = summary.isNull("average_sleep") ? 0 : summary.optDouble("average_sleep", 0);
        long span = Math.max(1, epochDay(summary.optString("end")) - epochDay(summary.optString("start")) + 1);
        double avgWorkout = summary.optDouble("exercise_minutes", 0) / span;

        boolean hasData = days.length() > 0 || avgSteps > 0 || avgCals > 0 || avgSleep > 0
                || summary.optDouble("exercise_minutes", 0) > 0;
        if (!hasData) {
            TextView empty = Ui.text(ctx(),
                    "这一周期还没有同步到健康数据。连接小米运动健康并完成一次同步后，这里会生成你的健康分和锻炼建议。",
                    13, Ui.SLATE, false);
            card.addView(empty, Ui.matchWrap(10, ctx()));
            return card;
        }

        Insights.Day day = new Insights.Day();
        day.steps = (int) Math.round(avgSteps);
        day.calories = (int) Math.round(avgCals);
        day.sleepHours = avgSleep;
        day.workoutMinutes = (int) Math.round(avgWorkout);
        int score = Insights.score(day);

        Insights.Week week = new Insights.Week();
        week.avgSteps = avgSteps;
        week.avgSleep = avgSleep;
        int workoutDays = 0;
        Double firstWeight = null, lastWeight = null;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) continue;
            if (d.optDouble("workout_minutes", 0) > 0) workoutDays++;
            if (!d.isNull("weight")) {
                if (firstWeight == null) firstWeight = d.optDouble("weight");
                lastWeight = d.optDouble("weight");
            }
        }
        week.workoutDays = workoutDays;
        week.workoutMinutes = (int) Math.round(summary.optDouble("exercise_minutes", 0));
        week.weightChange7d = (firstWeight != null && lastWeight != null)
                ? lastWeight - firstWeight : Double.NaN;
        week.hasData = true;

        List<Insights.Insight> recs = Insights.recommendations(day, week, null);

        LinearLayout row = Ui.row(ctx());
        row.setGravity(Gravity.TOP);

        // 左侧: 大号健康分
        LinearLayout scoreBox = Ui.column(ctx());
        scoreBox.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 104), ViewGroup.LayoutParams.WRAP_CONTENT);
        scoreBox.setLayoutParams(sblp);
        String scoreName = period.equals("week") ? "本周健康分" : (period.equals("month") ? "本月健康分" : "当月健康分");
        scoreBox.addView(Ui.text(ctx(), scoreName, 11, Ui.MUTED, true));
        TextView scoreNum = Ui.text(ctx(), String.valueOf(score), 42, Ui.LIME, true);
        scoreBox.addView(scoreNum, Ui.matchWrap(2, ctx()));
        TextView scoreTag = Ui.chip(ctx(), Insights.scoreLabel(score), Ui.PRIMARY_DARK, Ui.GREEN_SOFT);
        scoreTag.setGravity(Gravity.CENTER);
        scoreBox.addView(scoreTag, Ui.matchWrap(4, ctx()));
        if (avgCals > 0) {
            TextView hint = Ui.text(ctx(), Insights.calorieHint((int) Math.round(avgCals)), 10.5f, Ui.MUTED, false);
            hint.setGravity(Gravity.CENTER);
            scoreBox.addView(hint, Ui.matchWrap(6, ctx()));
        }
        row.addView(scoreBox);

        View divider = new View(ctx());
        LinearLayout.LayoutParams dvlp = new LinearLayout.LayoutParams(1, Ui.dp(ctx(), 96));
        dvlp.leftMargin = Ui.dp(ctx(), 12);
        divider.setLayoutParams(dvlp);
        divider.setBackgroundColor(Ui.BORDER);
        row.addView(divider);

        // 右侧: 建议列表 (前 3 条)
        LinearLayout recBox = Ui.column(ctx());
        if (recs.isEmpty()) {
            recBox.addView(Ui.text(ctx(), "这一周期的数据还不多，先保持记录节奏，分析会越来越准。", 12.5f, Ui.SLATE, false));
        } else {
            int n = Math.min(3, recs.size());
            for (int i = 0; i < n; i++) {
                recBox.addView(insightRow(recs.get(i)), i == 0 ? new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : Ui.matchWrap(10, ctx()));
            }
        }
        LinearLayout.LayoutParams rblp = Ui.weight1();
        rblp.leftMargin = Ui.dp(ctx(), 12);
        row.addView(recBox, rblp);
        card.addView(row, Ui.matchWrap(12, ctx()));
        return card;
    }

    private LinearLayout insightRow(Insights.Insight ins) {
        LinearLayout r = Ui.row(ctx());
        r.setGravity(Gravity.TOP);
        String icon;
        switch (ins.level) {
            case GOOD: icon = "✅"; break;
            case WARN: icon = "⚠️"; break;
            default: icon = "💡"; break;
        }
        r.addView(Ui.text(ctx(), icon, 14, Ui.INK, false));
        LinearLayout texts = Ui.column(ctx());
        texts.addView(Ui.text(ctx(), ins.title, 13, Ui.INK, true));
        texts.addView(Ui.text(ctx(), ins.body, 12, Ui.SLATE, false), Ui.matchWrap(2, ctx()));
        LinearLayout.LayoutParams tlp = Ui.weight1();
        tlp.leftMargin = Ui.dp(ctx(), 8);
        r.addView(texts, tlp);
        return r;
    }

    /* ---------------- 摘要 Bento ---------------- */

    private void buildBento() {
        String sleep = summary.isNull("average_sleep") ? "—" : fmtNum(summary.optDouble("average_sleep"));
        LinearLayout row1 = Ui.row(ctx());
        row1.addView(bentoTile("累计步数", "👟", Ui.GREEN_SOFT,
                fmtInt(summary.optDouble("total_steps", 0)), "步",
                "日均 " + fmtInt(summary.optDouble("average_steps", 0)) + " 步"), Ui.weight1());
        row1.addView(Ui.spacer(ctx(), 0), fixedW(10));
        row1.addView(bentoTile("消耗能量", "🔥", AMBER_SOFT,
                fmtNum(summary.optDouble("total_calories", 0)), "kcal",
                "日均 " + fmtNum(summary.optDouble("average_calories", 0)) + " kcal"), Ui.weight1());
        body.addView(row1, Ui.matchWrap(12, ctx()));

        LinearLayout row2 = Ui.row(ctx());
        row2.addView(bentoTile("平均睡眠", "🌙", PURPLE_SOFT,
                sleep, "h", "作息规律与身心恢复"), Ui.weight1());
        row2.addView(Ui.spacer(ctx(), 0), fixedW(10));
        row2.addView(bentoTile("运动累计", "🏃", Ui.MOVE_SOFT,
                fmtInt(summary.optDouble("exercise_count", 0)), "次",
                fmtNum(summary.optDouble("exercise_minutes", 0)) + " 分钟累计"), Ui.weight1());
        body.addView(row2, Ui.matchWrap(10, ctx()));
    }

    private LinearLayout.LayoutParams fixedW(int dpVal) {
        return new LinearLayout.LayoutParams(Ui.dp(ctx(), dpVal), 1);
    }

    private LinearLayout bentoTile(String label, String icon, int iconBg,
                                   String value, String unit, String foot) {
        LinearLayout tile = Ui.card(ctx());
        LinearLayout head = Ui.row(ctx());
        head.addView(Ui.text(ctx(), label, 12, Ui.MUTED, false), Ui.weight1());
        TextView ic = Ui.text(ctx(), icon, 15, Ui.INK, false);
        ic.setBackground(Ui.roundRect(iconBg, 10, ctx()));
        ic.setPadding(Ui.dp(ctx(), 7), Ui.dp(ctx(), 5), Ui.dp(ctx(), 7), Ui.dp(ctx(), 5));
        head.addView(ic);
        tile.addView(head);
        LinearLayout valRow = Ui.row(ctx());
        valRow.setBaselineAligned(false);
        valRow.addView(Ui.text(ctx(), value, 20, Ui.INK, true));
        TextView u = Ui.text(ctx(), " " + unit, 11, Ui.MUTED, false);
        valRow.addView(u);
        tile.addView(valRow, Ui.matchWrap(6, ctx()));
        tile.addView(Ui.text(ctx(), foot, 11, Ui.MUTED, false), Ui.matchWrap(4, ctx()));
        return tile;
    }

    /* ---------------- 日历 ---------------- */

    private LinearLayout calendarCard() {
        LinearLayout card = Ui.card(ctx());
        LinearLayout head = Ui.row(ctx());
        String[] ym = calendarMonth.split("-");
        int year = Integer.parseInt(ym[0]);
        int month = Integer.parseInt(ym[1]);
        head.addView(Ui.sectionHeader(ctx(), year + " 年 " + month + " 月", null, null), Ui.weight1());
        head.addView(navBtn("‹", -1));
        head.addView(navBtn("›", 1));
        card.addView(head);

        String[] weekLabels = {"一", "二", "三", "四", "五", "六", "日"};
        LinearLayout weekRow = Ui.row(ctx());
        for (String w : weekLabels) {
            TextView t = Ui.text(ctx(), w, 11, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            weekRow.addView(t, Ui.weight1());
        }
        card.addView(weekRow, Ui.matchWrap(12, ctx()));

        // 计算单元格 (与 analysis.js 的日历算法一致: 周一起始)
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(year, month - 1, 1);
        int jsDow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1; // 0=周日
        int firstDay = (jsDow + 6) % 7;
        int daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        Set<String> logged = new HashSet<>();
        JSONArray days = days();
        for (int i = 0; i < days.length(); i++) {
            logged.add(days.optJSONObject(i).optString("record_date"));
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        List<String> cells = new ArrayList<>();
        for (int i = 0; i < firstDay; i++) cells.add("");
        for (int d = 1; d <= daysInMonth; d++) cells.add(String.valueOf(d));

        int idx = 0;
        while (idx < cells.size()) {
            LinearLayout row = Ui.row(ctx());
            for (int c = 0; c < 7; c++) {
                String dayStr = idx < cells.size() ? cells.get(idx) : "";
                idx++;
                LinearLayout cell = Ui.column(ctx());
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, Ui.dp(ctx(), 46), 1f);
                cell.setLayoutParams(cellLp);
                if (!dayStr.isEmpty()) {
                    int d = Integer.parseInt(dayStr);
                    String date = String.format(Locale.US, "%s-%02d", calendarMonth, d);
                    boolean hasRecord = logged.contains(date);
                    boolean isToday = date.equals(today);
                    TextView num = Ui.text(ctx(), dayStr, 13,
                            isToday ? Color.WHITE : (hasRecord ? Ui.PRIMARY : Ui.INK), isToday || hasRecord);
                    num.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 30), Ui.dp(ctx(), 30));
                    num.setLayoutParams(nlp);
                    if (isToday) num.setBackground(Ui.roundRect(Ui.PRIMARY, 999, ctx()));
                    cell.addView(num);
                    View dotv = new View(ctx());
                    LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 5), Ui.dp(ctx(), 5));
                    dotLp.topMargin = Ui.dp(ctx(), 3);
                    dotv.setLayoutParams(dotLp);
                    if (hasRecord && !isToday) dotv.setBackground(Ui.roundRect(Ui.PRIMARY, 999, ctx()));
                    cell.addView(dotv);
                }
                row.addView(cell);
            }
            card.addView(row);
        }

        LinearLayout legend = Ui.row(ctx());
        legend.addView(legendDot(Ui.PRIMARY));
        legend.addView(Ui.text(ctx(), "有记录", 11, Ui.SLATE, false));
        View sp = new View(ctx());
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(Ui.dp(ctx(), 14), 1);
        legend.addView(sp, spl);
        legend.addView(legendDot(Ui.PRIMARY));
        legend.addView(Ui.text(ctx(), "今日", 11, Ui.SLATE, false));
        legend.addView(new View(ctx()), Ui.weight1());
        legend.addView(Ui.text(ctx(), "共 " + days.length() + " 天记录", 11, Ui.MUTED, false));
        card.addView(legend, Ui.matchWrap(10, ctx()));
        return card;
    }

    private TextView navBtn(String label, final int step) {
        TextView b = Ui.text(ctx(), label, 18, Ui.INK, true);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 32), Ui.dp(ctx(), 32));
        lp.leftMargin = Ui.dp(ctx(), 6);
        b.setLayoutParams(lp);
        b.setBackground(Ui.roundRect(Ui.SOFT, 999, ctx()));
        b.setOnClickListener(v -> changeMonth(step));
        return b;
    }

    private View legendDot(int color) {
        View v = new View(ctx());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 8), Ui.dp(ctx(), 8));
        lp.rightMargin = Ui.dp(ctx(), 5);
        v.setLayoutParams(lp);
        v.setBackground(Ui.roundRect(color, 999, ctx()));
        return v;
    }

    private void changeMonth(int step) {
        String[] ym = calendarMonth.split("-");
        int year = Integer.parseInt(ym[0]);
        int month = Integer.parseInt(ym[1]) + step;
        while (month < 1) { month += 12; year--; }
        while (month > 12) { month -= 12; year++; }
        calendarMonth = String.format(Locale.US, "%04d-%02d", year, month);
        load();
    }

    /* ---------------- 趋势卡片 ---------------- */

    private LinearLayout trendCard() {
        LinearLayout card = Ui.card(ctx());
        // 维度 Tab
        LinearLayout tabBar = Ui.row(ctx());
        String[][] tabs = {
                {"weight", "⚖️ 体重走势"}, {"steps", "👟 步数波动"},
                {"calories", "🔥 能量消耗"}, {"vitality", "🌙 睡眠运动"}};
        for (String[] t : tabs) {
            final String key = t[0];
            boolean active = key.equals(chartTab);
            TextView chip = Ui.text(ctx(), t[1], 11, active ? Ui.PRIMARY_DARK : Ui.SLATE, active);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, Ui.dp(ctx(), 8), 0, Ui.dp(ctx(), 8));
            if (active) chip.setBackground(Ui.roundRect(Ui.GREEN_SOFT, 10, ctx()));
            chip.setOnClickListener(v -> { chartTab = key; renderBody(); });
            tabBar.addView(chip, Ui.weight1());
        }
        card.addView(tabBar);

        LinearLayout contentBox = Ui.column(ctx());
        card.addView(contentBox, Ui.matchWrap(14, ctx()));
        switch (chartTab) {
            case "steps": buildSteps(contentBox); break;
            case "calories": buildCalories(contentBox); break;
            case "vitality": buildVitality(contentBox); break;
            default: buildWeight(contentBox); break;
        }
        return card;
    }

    private void cardHeader(LinearLayout box, String title, String subtitle, View right) {
        LinearLayout head = Ui.row(ctx());
        LinearLayout left = Ui.column(ctx());
        left.addView(Ui.sectionHeader(ctx(), title, null, null));
        left.addView(Ui.text(ctx(), subtitle, 11, Ui.MUTED, false), Ui.matchWrap(3, ctx()));
        head.addView(left, Ui.weight1());
        if (right != null) head.addView(right);
        box.addView(head);
    }

    private LinearLayout legendChips(String[][] items) {
        LinearLayout row = Ui.row(ctx());
        for (String[] it : items) {
            int color = Color.parseColor(it[0]);
            LinearLayout chip = Ui.row(ctx());
            chip.addView(legendDot(color));
            chip.addView(Ui.text(ctx(), it[1], 11, Ui.SLATE, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(ctx(), 8);
            row.addView(chip, lp);
        }
        return row;
    }

    /* ---- 维度 1: 体重/腰围折线 ---- */

    private void buildWeight(LinearLayout box) {
        cardHeader(box, "身体基线走势", "每天单独呈现，观察整体平稳曲线",
                legendChips(new String[][]{{"#10b981", "体重"}, {"#a78bfa", "腰围"}}));
        JSONArray days = days();
        String start = summary.optString("start");
        String end = summary.optString("end");
        Series weight = buildSeries(days, "weight", start, end);
        Series waist = buildSeries(days, "waist_cm", start, end);
        if (weight != null) box.addView(seriesBlock("体重走势", weight, Ui.PRIMARY), Ui.matchWrap(14, ctx()));
        if (waist != null) box.addView(seriesBlock("腰围走势", waist, PURPLE), Ui.matchWrap(14, ctx()));
        if (weight == null && waist == null) {
            box.addView(Ui.text(ctx(), "暂无体重/腰围数据", 12, Ui.MUTED, false), Ui.matchWrap(14, ctx()));
        }

        // 每日细分条形清单
        List<Double> weights = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d != null && !d.isNull("weight")) weights.add(d.optDouble("weight"));
        }
        double min = weights.isEmpty() ? 0 : Double.MAX_VALUE;
        double maxW = weights.isEmpty() ? 0 : -Double.MAX_VALUE;
        for (double w : weights) { min = Math.min(min, w); maxW = Math.max(maxW, w); }
        double range = weights.isEmpty() ? 0 : maxW - min;

        LinearLayout list = Ui.column(ctx());
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) continue;
            LinearLayout row = Ui.row(ctx());
            String date = d.optString("record_date");
            row.addView(fixedText(date.length() >= 10 ? date.substring(5) : date, 12, Ui.MUTED, 44));
            double widthPct;
            String wLabel;
            if (d.isNull("weight")) { widthPct = 0; wLabel = "—"; }
            else {
                double w = d.optDouble("weight");
                widthPct = range > 0 ? 24 + ((w - min) / range) * 76 : 55;
                wLabel = fmtNum(w) + " kg";
            }
            row.addView(trackBar(widthPct, Ui.PRIMARY), Ui.weight1());
            TextView wv = fixedText(wLabel, 12, Ui.INK, 62);
            wv.setGravity(Gravity.END);
            row.addView(wv);
            String waistLabel = d.isNull("waist_cm") ? "腰围 —" : "腰围 " + fmtNum(d.optDouble("waist_cm")) + " cm";
            TextView av = fixedText(waistLabel, 12, Ui.MUTED, 82);
            av.setGravity(Gravity.END);
            row.addView(av);
            list.addView(row, Ui.matchWrap(i == 0 ? 0 : 8, ctx()));
        }
        if (days.length() > 0) box.addView(list, Ui.matchWrap(16, ctx()));
    }

    private LinearLayout seriesBlock(String title, Series s, int color) {
        LinearLayout box = Ui.column(ctx());
        LinearLayout head = Ui.row(ctx());
        head.addView(Ui.text(ctx(), title + " (" + s.unit + ")", 13, Ui.INK, true), Ui.weight1());
        head.addView(pill(s.minText + " ~ " + s.maxText + " " + s.unit, Ui.SOFT, Ui.SLATE));
        box.addView(head);
        LineChartView chart = new LineChartView(ctx(), s, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx(), 178));
        lp.topMargin = Ui.dp(ctx(), 8);
        chart.setLayoutParams(lp);
        box.addView(chart);
        return box;
    }

    /* ---- 维度 2/3: 柱状图 ---- */

    private void buildSteps(LinearLayout box) {
        cardHeader(box, "每日步数走势", "日常步行活动量，基准线为 6,000 步活力线",
                pill("6,000 步活力达标", Ui.GREEN_SOFT, Ui.PRIMARY_DARK));
        JSONArray days = days();
        int maxSteps = 10000;
        for (int i = 0; i < days.length(); i++) {
            maxSteps = Math.max(maxSteps, days.optJSONObject(i).optInt("steps", 0));
        }
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            int steps = d.optInt("steps", 0);
            double h = Math.max(16, Math.min(180, Math.round(steps / (double) maxSteps * 180)));
            Bar b = new Bar();
            b.frac = (float) (h / 180.0);
            b.color = steps >= 6000 ? Ui.PRIMARY : Color.parseColor("#454f45");
            b.topLabel = steps > 0 ? String.valueOf(steps) : "";
            String date = d.optString("record_date");
            b.bottomLabel = date.length() >= 10 ? date.substring(5) : date;
            bars.add(b);
        }
        BarChartView chart = new BarChartView(ctx(), bars, 6000f / maxSteps, "6,000 步");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx(), 208));
        lp.topMargin = Ui.dp(ctx(), 10);
        chart.setLayoutParams(lp);
        box.addView(chart);
    }

    private void buildCalories(LinearLayout box) {
        cardHeader(box, "能量消耗走势", "全天动态代谢与运动热量支出 (kcal)",
                pill("总消耗 " + fmtNum(summary.optDouble("total_calories", 0)) + " kcal", AMBER_SOFT, AMBER));
        JSONArray days = days();
        double maxCals = 500;
        for (int i = 0; i < days.length(); i++) {
            maxCals = Math.max(maxCals, days.optJSONObject(i).optDouble("calories", 0));
        }
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            double cals = d.optDouble("calories", 0);
            double h = Math.max(16, Math.min(180, Math.round(cals / maxCals * 180)));
            Bar b = new Bar();
            b.frac = (float) (h / 180.0);
            b.color = AMBER_BAR;
            b.topLabel = cals > 0 ? fmtNum(cals) : "";
            String date = d.optString("record_date");
            b.bottomLabel = date.length() >= 10 ? date.substring(5) : date;
            bars.add(b);
        }
        BarChartView chart = new BarChartView(ctx(), bars, Float.NaN, null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx(), 208));
        lp.topMargin = Ui.dp(ctx(), 10);
        chart.setLayoutParams(lp);
        box.addView(chart);
    }

    /* ---- 维度 4: 睡眠/运动对照 ---- */

    private void buildVitality(LinearLayout box) {
        cardHeader(box, "作息与运动复盘", "观察睡眠时长与运动安排的节律配合",
                legendChips(new String[][]{{"#a78bfa", "睡眠(h)"}, {"#38bdf8", "运动(min)"}}));
        JSONArray days = days();
        LinearLayout list = Ui.column(ctx());
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) continue;
            String date = d.optString("record_date");
            LinearLayout row = Ui.row(ctx());
            row.setGravity(Gravity.TOP);
            row.addView(fixedText(date.length() >= 10 ? date.substring(5) : date, 12, Ui.MUTED, 44));
            LinearLayout bars = Ui.column(ctx());
            Double sleep = d.isNull("sleep_hours") ? null : d.optDouble("sleep_hours");
            double workout = d.isNull("workout_minutes") ? 0 : d.optDouble("workout_minutes");
            double sleepW = sleep != null && sleep > 0 ? Math.min(100, Math.round(sleep / 10 * 100)) : 0;
            double workW = workout > 0 ? Math.min(100, Math.round(workout / 60 * 100)) : 0;
            bars.addView(compBar(sleepW, PURPLE, sleep != null && sleep > 0 ? fmtNum(sleep) + "h" : "—"));
            LinearLayout second = compBar(workW, BLUE, workout > 0 ? fmtNum(workout) + "m" : "—");
            LinearLayout.LayoutParams slp = Ui.matchWrap(6, ctx());
            bars.addView(second, slp);
            row.addView(bars, Ui.weight1());
            list.addView(row, Ui.matchWrap(i == 0 ? 0 : 10, ctx()));
        }
        box.addView(list, Ui.matchWrap(14, ctx()));
    }

    private LinearLayout compBar(double pct, int color, String numText) {
        LinearLayout row = Ui.row(ctx());
        row.addView(trackBar(pct, color), Ui.weight1());
        TextView n = fixedText(numText, 11, Ui.SLATE, 42);
        n.setGravity(Gravity.END);
        row.addView(n);
        return row;
    }

    /** 横向比例条: 轨道 + 填充 (用 LinearLayout 权重实现百分比宽度) */
    private LinearLayout trackBar(double pct, int color) {
        LinearLayout track = Ui.row(ctx());
        track.setBackground(Ui.roundRect(Color.parseColor("#242b24"), 4, ctx()));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx(), 8));
        track.setLayoutParams(tlp);
        double p = Math.max(0, Math.min(100, pct));
        if (p > 0) {
            View fill = new View(ctx());
            fill.setBackground(Ui.roundRect(color, 4, ctx()));
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) p));
        }
        if (p < 100) {
            track.addView(new View(ctx()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) (100 - p)));
        }
        return track;
    }

    private TextView fixedText(String s, float sp, int color, int widthDp) {
        TextView t = Ui.text(ctx(), s, sp, color, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(ctx(), widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setSingleLine(true);
        return t;
    }

    /* ---------------- 数据结构与计算 (与 analysis.js 等价) ---------------- */

    private JSONArray days() {
        JSONArray arr = summary == null ? null : summary.optJSONArray("days");
        return arr == null ? new JSONArray() : arr;
    }

    private static class Series {
        String unit;
        String minText, maxText;
        final List<float[]> points = new ArrayList<>(); // [fx, fy01]
        final List<int[]> segments = new ArrayList<>(); // 点索引对
        final List<String> xLabels = new ArrayList<>();
        final List<Float> xLabelFx = new ArrayList<>();
    }

    /** 与 buildChart 等价: x 按日期跨度定位, 仅连接相邻日期的点 */
    private Series buildSeries(JSONArray days, String field, String start, String end) {
        long first = epochDay(start);
        long last = epochDay(end);
        long span = Math.max(1, last - first);
        List<Long> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null || d.isNull(field)) continue;
            Object v = d.opt(field);
            if (!(v instanceof Number)) continue;
            dates.add(epochDay(d.optString("record_date")));
            values.add(((Number) v).doubleValue());
        }
        if (values.isEmpty()) return null;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        double padding = (max == min) ? Math.max(1, max * 0.03) : (max - min) * 0.15;
        min -= padding;
        max += padding;
        Series s = new Series();
        s.unit = field.equals("weight") ? "kg" : "cm";
        s.minText = fmtFixed1(min);
        s.maxText = fmtFixed1(max);
        for (int i = 0; i < values.size(); i++) {
            float fx = (float) ((dates.get(i) - first) / (double) span);
            float fy = (float) ((values.get(i) - min) / (max - min));
            s.points.add(new float[]{fx, fy});
            if (i > 0 && dates.get(i) - dates.get(i - 1) == 1) {
                s.segments.add(new int[]{i - 1, i});
            }
        }
        for (int i = 0; i < 5; i++) {
            long day = first + Math.round(span * i / 4.0);
            String ds = civilString(day);
            s.xLabels.add(ds.length() >= 10 ? ds.substring(5) : ds);
            s.xLabelFx.add(i / 4f);
        }
        return s;
    }

    /* 日期工具: yyyy-MM-dd 与 epoch 天数互转 (Howard Hinnant 算法, 不依赖 API 26 的 java.time) */
    private static long epochDay(String ymd) {
        if (ymd == null || ymd.length() < 10) return 0;
        int y = Integer.parseInt(ymd.substring(0, 4));
        int m = Integer.parseInt(ymd.substring(5, 7));
        int d = Integer.parseInt(ymd.substring(8, 10));
        int yy = (m <= 2) ? y - 1 : y;
        long era = Math.floorDiv(yy, 400);
        long yoe = yy - era * 400;
        long mp = (m + 9) % 12;
        long doy = (153 * mp + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    private static String civilString(long epochDay) {
        long z = epochDay + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) y++;
        return String.format(Locale.US, "%04d-%02d-%02d", y, m, d);
    }

    private static String currentMonth() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
    }

    /* 数字格式化: 与 JS 的 Number->String 行为对齐 (整数不带小数) */
    private static String fmtNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return new java.math.BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }

    private static String fmtInt(double v) { return String.valueOf((long) Math.round(v)); }

    private static String fmtFixed1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /* ---------------- 自定义图表 View ---------------- */

    private static class Bar {
        float frac;
        int color;
        String topLabel = "";
        String bottomLabel = "";
    }

    private static class LineChartView extends View {
        private final Series s;
        private final int color;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        LineChartView(Context c, Series s, int color) {
            super(c);
            this.s = s;
            this.color = color;
        }

        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float padL = 36 * density, padR = 10 * density, padT = 8 * density, padB = 20 * density;
            float x0 = padL, x1 = getWidth() - padR, y0 = padT, y1 = getHeight() - padB;
            grid.setColor(Ui.BORDER);
            grid.setStrokeWidth(1);
            canvas.drawLine(x0, y0, x1, y0, grid);
            canvas.drawLine(x0, (y0 + y1) / 2, x1, (y0 + y1) / 2, grid);
            canvas.drawLine(x0, y1, x1, y1, grid);
            label.setColor(Ui.MUTED);
            label.setTextSize(9 * density);
            canvas.drawText(s.maxText, 2, y0 + 9 * density, label);
            canvas.drawText(s.minText, 2, y1, label);

            line.setColor(color);
            line.setStrokeWidth(2 * density);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            Path path = new Path();
            for (int[] seg : s.segments) {
                float[] a = s.points.get(seg[0]);
                float[] b = s.points.get(seg[1]);
                path.moveTo(x0 + a[0] * (x1 - x0), y1 - a[1] * (y1 - y0));
                path.lineTo(x0 + b[0] * (x1 - x0), y1 - b[1] * (y1 - y0));
            }
            canvas.drawPath(path, line);
            dotPaint.setStyle(Paint.Style.FILL);
            for (float[] p : s.points) {
                float cx = x0 + p[0] * (x1 - x0), cy = y1 - p[1] * (y1 - y0);
                dotPaint.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, 4.2f * density, dotPaint);
                dotPaint.setColor(color);
                canvas.drawCircle(cx, cy, 3 * density, dotPaint);
            }
            label.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < s.xLabels.size(); i++) {
                float lx = x0 + s.xLabelFx.get(i) * (x1 - x0);
                lx = Math.max(lx, 16 * density);
                lx = Math.min(lx, getWidth() - 16 * density);
                canvas.drawText(s.xLabels.get(i), lx, getHeight() - 5 * density, label);
            }
            label.setTextAlign(Paint.Align.LEFT);
        }
    }

    private static class BarChartView extends View {
        private final List<Bar> bars;
        private final float targetFrac;
        private final String targetLabel;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarChartView(Context c, List<Bar> bars, float targetFrac, String targetLabel) {
            super(c);
            this.bars = bars;
            this.targetFrac = targetFrac;
            this.targetLabel = targetLabel;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (bars.isEmpty()) return;
            float density = getResources().getDisplayMetrics().density;
            float topPad = 18 * density, botPad = 18 * density;
            float plotH = getHeight() - topPad - botPad;
            float slot = getWidth() / (float) bars.size();
            float barW = Math.min(slot * 0.52f, 26 * density);

            if (!Float.isNaN(targetFrac)) {
                float ty = topPad + plotH * (1 - targetFrac);
                linePaint.setColor(Ui.MUTED);
                linePaint.setStrokeWidth(1);
                linePaint.setPathEffect(new DashPathEffect(new float[]{6, 5}, 0));
                canvas.drawLine(0, ty, getWidth(), ty, linePaint);
                linePaint.setPathEffect(null);
                if (targetLabel != null) {
                    textPaint.setColor(Ui.MUTED);
                    textPaint.setTextSize(9 * density);
                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(targetLabel, getWidth() - 2, ty - 4 * density, textPaint);
                    textPaint.setTextAlign(Paint.Align.LEFT);
                }
            }

            textPaint.setTextSize(9 * density);
            for (int i = 0; i < bars.size(); i++) {
                Bar b = bars.get(i);
                float cx = slot * i + slot / 2f;
                float barH = plotH * Math.max(0, Math.min(1, b.frac));
                float top = topPad + plotH - barH;
                float bottom = topPad + plotH;
                barPaint.setColor(b.color);
                RectF rect = new RectF(cx - barW / 2, top, cx + barW / 2, bottom);
                float r = 4 * density;
                Path path = new Path();
                path.addRoundRect(rect, new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
                canvas.drawPath(path, barPaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
                if (b.topLabel != null && !b.topLabel.isEmpty()) {
                    textPaint.setColor(b.color == Color.parseColor("#454f45") ? Ui.SLATE : b.color);
                    canvas.drawText(b.topLabel, cx, top - 4 * density, textPaint);
                }
                textPaint.setColor(Ui.MUTED);
                canvas.drawText(b.bottomLabel, cx, getHeight() - 4 * density, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
            }
        }
    }
}
