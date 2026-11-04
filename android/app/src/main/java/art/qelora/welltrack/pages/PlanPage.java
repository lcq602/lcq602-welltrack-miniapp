package art.qelora.welltrack.pages;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import art.qelora.welltrack.R;
import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.health.PlanStore;
import art.qelora.welltrack.health.Reminders;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 计划页 v2.2: AI 推荐计划 (深色荧光绿风)。
 * 计划由 PlanStore 统一供给 (Worker AI 按档案 + 近 7 天小米数据生成, 本地规则兜底):
 * 本周目标卡 → 今日安排 (目标 vs 实际) → 本周安排 (含已过去日期的达标状态) → 教练建议;
 * 页面底部保留精简的「调整目标」表单 (方向/体重/日期), 保存时档案原 weekly_plan 不动。
 * 未配置档案时沿用原空状态引导去首页建档。
 */
public class PlanPage extends BasePage {
    private static final String[] GOAL_OPTIONS = {"减重", "减脂", "维持", "健康习惯"};
    private static final String[] GOAL_VALUES = {"weight_loss", "fat_loss", "maintenance", "healthy_habits"};
    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] DAY_SHORT = {"一", "二", "三", "四", "五", "六", "日"};

    private LinearLayout body;
    private JSONObject profile;              // null = 未配置
    private boolean profileLoaded = false;
    private JSONObject dashboard;            // 今日实际 (mihealth.today)
    private JSONObject weekAnalytics;        // 本周逐日实际 (达标状态)
    private JSONObject planResult;           // PlanStore 结果 {plan, week_start, source}
    private boolean generating = false;
    private boolean saving = false;

    // 调整目标表单状态 (首次拿到档案时初始化, 之后以字段为准避免重渲染丢编辑)
    private boolean formInit = false;
    private int goalIndex = 0;
    private String startDate = "";
    private String targetDate = "";
    private String formCur = "";
    private String formTar = "";
    private EditText curWeightInput;
    private EditText tarWeightInput;
    private TextView deltaView;
    private TextView startDateView;
    private TextView targetDateView;
    private final TextView[] goalChips = new TextView[4];
    private Button saveButton;
    private Button regenButton;

    public PlanPage(Context ctx) { super(ctx); }

    static String fmtNum(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        Object v = o.opt(key);
        if (v instanceof Number) return fmtDouble(((Number) v).doubleValue());
        return String.valueOf(v);
    }

    static String fmtDouble(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    @Override
    protected void build() {
        Context c = ctx();
        content.addView(Ui.label(c, "● AI PLAN FOR YOU"));
        TextView title = Ui.title(c, "本周计划");
        title.setTextSize(24);
        content.addView(title, Ui.matchWrap(2, c));
        content.addView(Ui.bannerImage(c, R.drawable.hero_training, 130, 24), Ui.matchWrap(12, c));

        body = Ui.column(c);
        content.addView(body, Ui.matchWrap(12, c));
        showLoading();
    }

    @Override
    public void onShow() {
        super.onShow();
        load();
    }

    private GradientDrawable roundStroke(int color, float radiusDp, int strokeColor) {
        GradientDrawable d = Ui.roundRect(color, radiusDp, ctx());
        d.setStroke(1, strokeColor);
        return d;
    }

    private void showLoading() {
        if (body == null) return;
        body.removeAllViews();
        LinearLayout card = Ui.card(ctx());
        card.setGravity(Gravity.CENTER);
        card.addView(Ui.text(ctx(), "AI 正在根据你的数据制定本周计划…", 14, Ui.SLATE, false));
        body.addView(card);
    }

    /* ---------------- 数据 ---------------- */

    private void load() {
        // 档案: 未配置时返回 JSON null, 用文本接口自行解析
        api().requestText("/api/v1/profile", new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                profileLoaded = true;
                try {
                    if (text == null || text.trim().equals("null") || text.trim().isEmpty()) {
                        profile = null;
                    } else {
                        profile = new JSONObject(text);
                    }
                } catch (Exception e) {
                    profile = null;
                }
                initFormFromProfile();
                renderBody();
            }
            @Override public void onError(String message) {
                profileLoaded = true;
                toast(message == null || message.isEmpty() ? "加载失败" : message);
                renderBody();
            }
        });
        api().request("/api/v1/dashboard", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                dashboard = data;
                renderBody();
            }
            @Override public void onError(String message) { /* 今日实际缺省即可 */ }
        });
        api().request("/api/v1/analytics?period=week", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                weekAnalytics = data;
                renderBody();
            }
            @Override public void onError(String message) { /* 达标状态缺省即可 */ }
        });
        requestPlan(false);
    }

    private void requestPlan(boolean force) {
        generating = true;
        PlanStore.get(ctx(), force, new PlanStore.Listener() {
            @Override public void onPlan(JSONObject result) {
                generating = false;
                planResult = result;
                renderBody();
            }
            @Override public void onError(String message) {
                generating = false;
                toast(message == null || message.isEmpty() ? "计划生成失败" : message);
                renderBody();
            }
        });
    }

    private void initFormFromProfile() {
        if (formInit || profile == null) return;
        formInit = true;
        goalIndex = indexOf(GOAL_VALUES, profile.optString("goal", "weight_loss"));
        if (goalIndex < 0) goalIndex = 0;
        startDate = profile.optString("start_date", "");
        targetDate = profile.isNull("target_date") ? "" : profile.optString("target_date", "");
        formCur = fmtNum(profile, "current_weight");
        formTar = fmtNum(profile, "target_weight");
    }

    /* ---------------- 渲染 ---------------- */

    private void renderBody() {
        if (body == null) return;
        if (!profileLoaded) { showLoading(); return; }
        body.removeAllViews();
        if (profile == null) {
            renderEmpty();
            return;
        }
        renderPlanSections();
        renderGoalForm();
        renderReminderCard();
    }

    /* v2.12 记录提醒卡: 提醒时间与开关仅存本机 (SharedPreferences), 不上传服务器、不发系统通知;
       开启后, 当天未记录体重的用户在首页会看到温和提醒条。 */
    private void renderReminderCard() {
        Context c = ctx();
        boolean enabled = Reminders.isEnabled(c);
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "记录提醒", null, null));
        card.addView(Ui.text(c, enabled
                ? "已开启 · 每天 " + Reminders.timeText(c) + " 提醒称体重（以首页提醒条呈现）"
                : "已关闭 · 开启后，未记录体重的当天首页会出现提醒条",
                13, Ui.SLATE, false), Ui.matchWrap(6, c));
        LinearLayout row = Ui.row(c);
        Button timeBtn = Ui.ghostButton(c, "提醒时间：" + Reminders.timeText(c) + " · 修改");
        timeBtn.setOnClickListener(v -> new TimePickerDialog(c,
                (view, hour, minute) -> {
                    Reminders.setTime(c, hour, minute);
                    renderBody();
                }, Reminders.hour(c), Reminders.minute(c), true).show());
        row.addView(timeBtn, Ui.weight1());
        Button toggle = enabled ? Ui.ghostButton(c, "关闭提醒") : Ui.primaryButton(c, "开启提醒");
        toggle.setOnClickListener(v -> {
            Reminders.setEnabled(c, !Reminders.isEnabled(c));
            renderBody();
        });
        LinearLayout.LayoutParams tlp = Ui.weight1();
        tlp.leftMargin = Ui.dp(c, 8);
        row.addView(toggle, tlp);
        card.addView(row, Ui.matchWrap(10, c));
        card.addView(Ui.text(c, "时间仅保存在本机，不发送系统通知、不上传服务器；当天点过「今日不再提醒」后不再重复出现。",
                11, Ui.MUTED, false), Ui.matchWrap(6, c));
        body.addView(card, Ui.matchWrap(12, c));
    }

    private void renderEmpty() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        TextView icon = Ui.text(c, "⚙", 26, Ui.LIME, false);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);
        TextView t = Ui.sectionTitle(c, "先建立你的健康起点");
        t.setGravity(Gravity.CENTER);
        card.addView(t, Ui.matchWrap(6, c));
        TextView desc = Ui.text(c, "完成身高、当前体重和目标设置后, AI 会结合你的小米数据生成专属本周计划。", 13, Ui.SLATE, false);
        desc.setGravity(Gravity.CENTER);
        card.addView(desc, Ui.matchWrap(6, c));
        Button go = Ui.primaryButton(c, "去首页完成设置 ›");
        go.setOnClickListener(v -> activity().switchTab(0));
        card.addView(go, Ui.matchWrap(14, c));
        body.addView(card);
    }

    private void renderPlanSections() {
        Context c = ctx();
        JSONObject plan = planResult != null ? planResult.optJSONObject("plan") : null;
        if (plan == null) {
            LinearLayout card = Ui.card(c);
            card.setGravity(Gravity.CENTER);
            if (generating) {
                card.addView(Ui.text(c, "AI 正在根据你的数据制定本周计划…", 14, Ui.SLATE, false));
            } else {
                card.addView(Ui.text(c, "本周计划还没生成好", 14, Ui.SLATE, false));
                Button retry = Ui.ghostButton(c, "重新生成");
                retry.setOnClickListener(v -> { requestPlan(true); renderBody(); });
                card.addView(retry, Ui.matchWrap(10, c));
            }
            body.addView(card);
            return;
        }
        body.addView(renderGoalCard(plan), Ui.matchWrap(2, c));
        body.addView(renderTodayCard(plan), Ui.matchWrap(12, c));
        body.addView(renderWeekCard(plan), Ui.matchWrap(12, c));
        body.addView(renderTipsCard(plan), Ui.matchWrap(12, c));
    }

    /* (a) 本周目标卡 */
    private LinearLayout renderGoalCard(JSONObject plan) {
        Context c = ctx();
        JSONObject targets = plan.optJSONObject("targets");
        if (targets == null) targets = new JSONObject();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "本周目标", null, null));
        TextView goal = Ui.text(c, plan.optString("week_goal", ""), 17, Ui.INK, true);
        card.addView(goal, Ui.matchWrap(8, c));

        LinearLayout row1 = Ui.row(c);
        row1.addView(Ui.metricTile(c, Ui.PRIMARY, Ui.GREEN_SOFT, "👟", "步数目标",
                String.valueOf(targets.optInt("steps", 8000)), "步 / 天", -1), tileLp(true));
        row1.addView(Ui.metricTile(c, Ui.CAL, Ui.CAL_SOFT, "🔥", "活动消耗",
                String.valueOf(targets.optInt("calories_kcal", 400)), "千卡 / 天", -1), tileLp(false));
        card.addView(row1, Ui.matchWrap(12, c));
        LinearLayout row2 = Ui.row(c);
        row2.addView(Ui.metricTile(c, Ui.MOVE, Ui.MOVE_SOFT, "⏱️", "运动时长",
                String.valueOf(targets.optInt("exercise_minutes", 30)), "分钟 / 天", -1), tileLp(true));
        double sleep = targets.optDouble("sleep_hours", 8);
        row2.addView(Ui.metricTile(c, Ui.SLEEP, Ui.SLEEP_SOFT, "🌙", "睡眠目标",
                fmtDouble(sleep), "小时 / 晚", -1), tileLp(false));
        card.addView(row2, Ui.matchWrap(8, c));

        String source = planResult.optString("source", "ai");
        String sourceText = "local".equals(source)
                ? "本地规则生成 · AI 暂时不可用, 可点重新生成重试"
                : "✨ AI 推荐 · 结合你的档案与近 7 天数据生成";
        card.addView(Ui.text(c, sourceText, 11, Ui.MUTED, false), Ui.matchWrap(12, c));
        regenButton = Ui.ghostButton(c, generating ? "生成中…" : "↻ 重新生成");
        regenButton.setEnabled(!generating);
        regenButton.setOnClickListener(v -> { requestPlan(true); renderBody(); });
        card.addView(regenButton, Ui.matchWrap(8, c));
        return card;
    }

    private LinearLayout.LayoutParams tileLp(boolean left) {
        LinearLayout.LayoutParams lp = Ui.weight1();
        if (left) lp.rightMargin = Ui.dp(ctx(), 5); else lp.leftMargin = Ui.dp(ctx(), 5);
        return lp;
    }

    /* (b) 今日安排卡: 荧光绿描边突出 + 动作清单 + 今日目标 vs 实际 */
    private LinearLayout renderTodayCard(JSONObject plan) {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        GradientDrawable bg = Ui.roundRect(Ui.CARD, 16, c);
        bg.setStroke(Ui.dp(c, 1), Ui.LIME);
        card.setBackground(bg);

        int todayIdx = PlanStore.todayIndex();
        JSONObject day = PlanStore.dayAt(plan, todayIdx);
        String focus = day != null ? day.optString("focus", "日常活动") : "日常活动";
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, "今日 · " + focus, 16, Ui.INK, true), Ui.weight1());
        head.addView(Ui.chip(c, DAY_NAMES[todayIdx], Ui.ON_LIME, Ui.LIME));
        card.addView(head);

        JSONArray actions = day != null ? day.optJSONArray("actions") : null;
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) continue;
                card.addView(actionRow(a), Ui.matchWrap(i == 0 ? 12 : 9, c));
            }
        }
        if (day != null) {
            int[] actual = todayActual();
            card.addView(progressRow("步数", actual[0], day.optInt("steps_target", 0), Ui.PRIMARY, "步"),
                    Ui.matchWrap(14, c));
            card.addView(progressRow("活动消耗", actual[1], day.optInt("calories_target", 0), Ui.CAL, "千卡"),
                    Ui.matchWrap(10, c));
            JSONArray dayActions = day.optJSONArray("actions");
            if (dayActions != null && dayActions.length() > 0) {
                Button workoutBtn = Ui.primaryButton(c, "▶ 开始跟练");
                workoutBtn.setOnClickListener(v -> activity().openWorkout(day));
                card.addView(workoutBtn, Ui.matchWrap(12, c));
            }
        }
        return card;
    }

    private LinearLayout actionRow(JSONObject a) {
        Context c = ctx();
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        View dot = new View(c);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(Ui.LIME);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(c, 7), Ui.dp(c, 7));
        dlp.rightMargin = Ui.dp(c, 9);
        dlp.topMargin = Ui.dp(c, 6);
        row.addView(dot, dlp);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, a.optString("title", ""), 14, Ui.INK, true));
        String detail = a.optString("detail", "");
        if (!detail.isEmpty()) {
            texts.addView(Ui.text(c, detail, 12, Ui.SLATE, false), Ui.matchWrap(2, c));
        }
        row.addView(texts, Ui.weight1());
        int minutes = a.optInt("minutes", 0);
        if (minutes > 0) {
            row.addView(Ui.chip(c, minutes + " 分钟", Ui.PRIMARY_DARK, Ui.GREEN_SOFT));
        }
        return row;
    }

    private LinearLayout progressRow(String label, int actual, int target, int color, String unit) {
        Context c = ctx();
        LinearLayout col = Ui.column(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, label, 12, Ui.SLATE, true), Ui.weight1());
        String val = target > 0 ? actual + " / " + target + " " + unit : actual + " " + unit;
        head.addView(Ui.text(c, val, 12, Ui.INK, true));
        col.addView(head);
        android.widget.FrameLayout bar = Ui.progressBar(c, target > 0 ? actual / (double) target : 0, color, 6);
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) bar.getLayoutParams();
        blp.topMargin = Ui.dp(c, 5);
        bar.setLayoutParams(blp);
        col.addView(bar);
        return col;
    }

    /** 今日实际 [步数, 消耗]: 优先 dashboard 小米今日, 其次 analytics 今日行 */
    private int[] todayActual() {
        int steps = 0, calories = 0;
        if (dashboard != null) {
            JSONObject mi = dashboard.optJSONObject("mihealth");
            JSONObject today = mi != null ? mi.optJSONObject("today") : null;
            if (today != null) {
                steps = today.optInt("steps", 0);
                calories = (int) Math.round(today.optDouble("calories", 0));
            }
        }
        if (steps == 0 && calories == 0) {
            JSONObject d = analyticsDay(PlanStore.weekDates().get(PlanStore.todayIndex()));
            if (d != null) {
                steps = d.optInt("steps", 0);
                calories = (int) Math.round(d.optDouble("calories", 0));
            }
        }
        return new int[]{steps, calories};
    }

    private JSONObject analyticsDay(String dateStr) {
        if (weekAnalytics == null || dateStr == null) return null;
        JSONArray days = weekAnalytics.optJSONArray("days");
        if (days == null) return null;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d != null && dateStr.equals(d.optString("record_date", ""))) return d;
        }
        return null;
    }

    /* (c) 本周安排卡: 7 天行 + 已过去日期的达标状态 */
    private LinearLayout renderWeekCard(JSONObject plan) {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "本周安排", null, null));
        List<String> dates = PlanStore.weekDates();
        int todayIdx = PlanStore.todayIndex();
        for (int i = 0; i < 7; i++) {
            JSONObject day = PlanStore.dayAt(plan, i);
            if (day == null) continue;
            card.addView(weekRow(day, dates.get(i), i, i == todayIdx, i < todayIdx),
                    Ui.matchWrap(i == 0 ? 10 : 6, c));
        }
        return card;
    }

    private LinearLayout weekRow(JSONObject day, String dateStr, int idx, boolean isToday, boolean isPast) {
        Context c = ctx();
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        if (isToday) {
            row.setBackground(Ui.roundRect(Ui.GREEN_SOFT, 12, c));
            int p = Ui.dp(c, 8);
            row.setPadding(p, p, p, p);
        }
        // 日期徽标: 周几 + 日期号
        Calendar cal = parseCal(dateStr);
        LinearLayout badge = Ui.column(c);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.roundRect(isToday ? Ui.LIME : Ui.SOFT, 12, c));
        int bp = Ui.dp(c, 5);
        badge.setPadding(0, bp, 0, bp);
        badge.addView(centerText(DAY_SHORT[idx], 10, isToday ? Ui.ON_LIME : Ui.MUTED, false));
        badge.addView(centerText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)), 15,
                isToday ? Ui.ON_LIME : Ui.INK, true));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(c, 42), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(badge, blp);

        LinearLayout mid = Ui.column(c);
        mid.addView(Ui.text(c, day.optString("focus", "日常活动"), 14, Ui.INK, true));
        JSONArray actions = day.optJSONArray("actions");
        if (actions != null && actions.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(a.optString("title", ""));
            }
            mid.addView(Ui.text(c, sb.toString(), 12, Ui.SLATE, false), Ui.matchWrap(2, c));
        }
        mid.addView(Ui.text(c, "目标 " + day.optInt("steps_target", 0) + " 步 · "
                + day.optInt("calories_target", 0) + " 千卡", 11, Ui.MUTED, false), Ui.matchWrap(3, c));
        LinearLayout.LayoutParams mlp = Ui.weight1();
        mlp.leftMargin = Ui.dp(c, 10);
        row.addView(mid, mlp);

        if (isPast) {
            row.addView(dayStatus(day, dateStr));
        } else if (isToday) {
            row.addView(Ui.chip(c, "今日", Ui.ON_LIME, Ui.LIME));
        }
        return row;
    }

    /** 已过去日期的达标状态: 对比 analytics 当日步数/消耗与计划目标 */
    private TextView dayStatus(JSONObject day, String dateStr) {
        Context c = ctx();
        JSONObject actual = analyticsDay(dateStr);
        if (actual == null) return Ui.text(c, "— 无数据", 11, Ui.MUTED, false);
        int steps = actual.optInt("steps", 0);
        int calories = (int) Math.round(actual.optDouble("calories", 0));
        if (steps <= 0 && calories <= 0) return Ui.text(c, "— 无数据", 11, Ui.MUTED, false);
        boolean stepsOk = steps >= day.optInt("steps_target", Integer.MAX_VALUE);
        boolean calOk = calories >= day.optInt("calories_target", Integer.MAX_VALUE);
        if (stepsOk && calOk) return Ui.text(c, "✓ 达标", 11, Ui.PRIMARY_DARK, true);
        return Ui.text(c, "◐ 部分", 11, Ui.CAL_TEXT, true);
    }

    private TextView centerText(String s, float sp, int color, boolean bold) {
        TextView t = Ui.text(ctx(), s, sp, color, bold);
        t.setGravity(Gravity.CENTER);
        return t;
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

    /* (d) 教练建议卡 */
    private LinearLayout renderTipsCard(JSONObject plan) {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "教练建议", null, null));
        JSONArray tips = plan.optJSONArray("tips");
        if (tips != null) {
            for (int i = 0; i < tips.length(); i++) {
                String tip = tips.optString(i, "");
                if (tip.isEmpty()) continue;
                LinearLayout row = Ui.row(c);
                row.setGravity(Gravity.TOP);
                View dot = new View(c);
                GradientDrawable dd = new GradientDrawable();
                dd.setShape(GradientDrawable.OVAL);
                dd.setColor(Ui.LIME);
                dot.setBackground(dd);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(c, 6), Ui.dp(c, 6));
                dlp.rightMargin = Ui.dp(c, 8);
                dlp.topMargin = Ui.dp(c, 6);
                row.addView(dot, dlp);
                row.addView(Ui.text(c, tip, 13, Ui.SLATE, false), Ui.weight1());
                card.addView(row, Ui.matchWrap(i == 0 ? 10 : 8, c));
            }
        }
        return card;
    }

    /* (e) 调整目标卡: 精简保留原表单 (方向/体重/日期), 保存时 weekly_plan 沿用档案已有值 */
    private void renderGoalForm() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "调整目标", null, null));
        card.addView(Ui.text(c, "目标会用于下一次 AI 生成计划", 12, Ui.MUTED, false), Ui.matchWrap(2, c));

        card.addView(Ui.text(c, "管理方向", 12.5f, Ui.SLATE, true), Ui.matchWrap(12, c));
        LinearLayout chipsRow = Ui.row(c);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView chipView = chip(GOAL_OPTIONS[i], i == goalIndex);
            chipView.setOnClickListener(v -> {
                goalIndex = idx;
                refreshGoalChips();
            });
            goalChips[i] = chipView;
            LinearLayout.LayoutParams lp = Ui.weight1();
            if (i > 0) lp.leftMargin = Ui.dp(c, 6);
            chipsRow.addView(chipView, lp);
        }
        card.addView(chipsRow, Ui.matchWrap(6, c));

        LinearLayout grid = Ui.row(c);
        LinearLayout tile1 = tileBox(c, "当前体重", "kg");
        curWeightInput = tileInput(tile1);
        curWeightInput.setText(formCur);
        LinearLayout tile2 = tileBox(c, "目标体重", "kg");
        tarWeightInput = tileInput(tile2);
        tarWeightInput.setText(formTar);
        deltaView = Ui.text(c, "", 11, Ui.PRIMARY_DARK, true);
        deltaView.setBackground(roundStroke(Ui.GREEN_SOFT, 999, Ui.GREEN_SOFT));
        int dp4 = Ui.dp(c, 4);
        deltaView.setPadding(Ui.dp(c, 8), dp4 / 2, Ui.dp(c, 8), dp4 / 2);
        tile2.addView(deltaView, Ui.matchWrap(4, c));
        LinearLayout.LayoutParams g1 = Ui.weight1();
        LinearLayout.LayoutParams g2 = Ui.weight1();
        g2.leftMargin = Ui.dp(c, 10);
        grid.addView(tile1, g1);
        grid.addView(tile2, g2);
        card.addView(grid, Ui.matchWrap(12, c));
        updateDelta();
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {
                formCur = curWeightInput != null && curWeightInput.getText() != null
                        ? curWeightInput.getText().toString() : formCur;
                formTar = tarWeightInput != null && tarWeightInput.getText() != null
                        ? tarWeightInput.getText().toString() : formTar;
                updateDelta();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        curWeightInput.addTextChangedListener(watcher);
        tarWeightInput.addTextChangedListener(watcher);

        card.addView(Ui.text(c, "计划开始日期", 12.5f, Ui.SLATE, true), Ui.matchWrap(12, c));
        startDateView = dateField(startDate.isEmpty() ? "选择日期" : startDate);
        startDateView.setOnClickListener(v -> pickDate(startDate, true));
        card.addView(startDateView, Ui.matchWrap(4, c));
        card.addView(Ui.text(c, "目标达成日期（选填）", 12.5f, Ui.SLATE, true), Ui.matchWrap(10, c));
        targetDateView = dateField(targetDate.isEmpty() ? "按节奏调整" : targetDate);
        targetDateView.setOnClickListener(v -> pickDate(targetDate, false));
        card.addView(targetDateView, Ui.matchWrap(4, c));

        saveButton = Ui.primaryButton(c, "保存为新版本");
        saveButton.setOnClickListener(v -> save());
        card.addView(saveButton, Ui.matchWrap(16, c));
        TextView note = Ui.text(c, "保存会新增一份计划版本，历史版本保留。", 12, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        card.addView(note, Ui.matchWrap(6, c));
        body.addView(card, Ui.matchWrap(12, c));
    }

    private LinearLayout tileBox(Context c, String label, String unit) {
        LinearLayout box = Ui.column(c);
        box.setBackground(roundStroke(Ui.SOFT, 14, Ui.BORDER));
        int p = Ui.dp(c, 12);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(c, label, 12, Ui.SLATE, true));
        return box;
    }

    private EditText tileInput(LinearLayout box) {
        EditText e = Ui.input(ctx(), "—", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackground(null);
        e.setTextSize(20);
        e.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(e, Ui.matchWrap(2, ctx()));
        return e;
    }

    private TextView dateField(String text) {
        TextView t = Ui.text(ctx(), text + "  📅", 14, Ui.INK, true);
        t.setBackground(roundStroke(Ui.SOFT, 12, Ui.BORDER));
        int hp = Ui.dp(ctx(), 12), vp = Ui.dp(ctx(), 11);
        t.setPadding(hp, vp, hp, vp);
        return t;
    }

    private TextView chip(String text, boolean active) {
        TextView t = Ui.text(ctx(), text, 12.5f, active ? Ui.ON_LIME : Ui.SLATE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(active ? Ui.LIME : Ui.SOFT, 999,
                active ? Ui.LIME : Ui.BORDER));
        int vp = Ui.dp(ctx(), 7);
        t.setPadding(Ui.dp(ctx(), 8), vp, Ui.dp(ctx(), 8), vp);
        return t;
    }

    private void refreshGoalChips() {
        for (int i = 0; i < 4; i++) {
            if (goalChips[i] == null) continue;
            boolean active = i == goalIndex;
            goalChips[i].setTextColor(active ? Ui.ON_LIME : Ui.SLATE);
            goalChips[i].setBackground(roundStroke(active ? Ui.LIME : Ui.SOFT, 999,
                    active ? Ui.LIME : Ui.BORDER));
        }
    }

    private void updateDelta() {
        if (deltaView == null) return;
        try {
            double cur = Double.parseDouble(formCur);
            double tar = Double.parseDouble(formTar);
            double delta = Math.round((tar - cur) * 10.0) / 10.0;
            deltaView.setText((delta > 0 ? "+" : "") + fmtDouble(delta) + " kg");
            deltaView.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            deltaView.setVisibility(View.GONE);
        }
    }

    private void pickDate(String current, final boolean isStart) {
        Calendar cal = Calendar.getInstance();
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH), d = cal.get(Calendar.DAY_OF_MONTH);
        if (current != null && current.matches("\\d{4}-\\d{2}-\\d{2}")) {
            y = Integer.parseInt(current.substring(0, 4));
            m = Integer.parseInt(current.substring(5, 7)) - 1;
            d = Integer.parseInt(current.substring(8, 10));
        }
        new DatePickerDialog(ctx(), (view, yy, mm, dd) -> {
            String val = String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd);
            if (isStart) {
                startDate = val;
                if (startDateView != null) startDateView.setText(val + "  📅");
            } else {
                targetDate = val;
                if (targetDateView != null) targetDateView.setText(val + "  📅");
            }
        }, y, m, d).show();
    }

    private void save() {
        if (saving || profile == null) return;
        String heightStr = fmtNum(profile, "height_cm");
        String curStr = formCur.trim();
        String tarStr = formTar.trim();
        if (heightStr.isEmpty() || curStr.isEmpty() || tarStr.isEmpty() || startDate.isEmpty()) {
            toast("请先在首页完成个人设置");
            return;
        }
        double cur, tar, height;
        try {
            cur = Double.parseDouble(curStr);
            tar = Double.parseDouble(tarStr);
            height = Double.parseDouble(heightStr);
        } catch (Exception e) {
            toast("请检查当前体重和目标体重");
            return;
        }
        if (cur <= 20 || cur > 500 || tar <= 20 || tar > 500) {
            toast("请检查当前体重和目标体重");
            return;
        }
        if (!targetDate.isEmpty() && targetDate.compareTo(startDate) < 0) {
            toast("目标日期不能早于开始日期");
            return;
        }
        saving = true;
        if (saveButton != null) { saveButton.setEnabled(false); saveButton.setText("正在保存…"); }

        try {
            JSONObject payload = new JSONObject();
            payload.put("height_cm", height);
            payload.put("current_weight", cur);
            payload.put("target_weight", tar);
            payload.put("start_date", startDate);
            payload.put("target_date", targetDate.isEmpty() ? JSONObject.NULL : targetDate);
            payload.put("goal", GOAL_VALUES[goalIndex]);
            // 每周安排已由 AI 计划接管, 保存时沿用档案里已有的 weekly_plan 不做改动
            JSONObject weekly = profile.optJSONObject("weekly_plan");
            payload.put("weekly_plan", weekly != null ? weekly : new JSONObject());
            JSONObject reminders = profile.optJSONObject("reminder_settings");
            payload.put("reminder_settings", reminders != null ? reminders : new JSONObject());

            api().request("/api/v1/profile", "POST", payload, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    saving = false;
                    if (saveButton != null) { saveButton.setEnabled(true); saveButton.setText("保存为新版本"); }
                    toast("新计划已保存");
                    formInit = false; // 下次 load 以新档案刷新表单初值
                    load();
                }
                @Override public void onError(String message) {
                    saving = false;
                    if (saveButton != null) { saveButton.setEnabled(true); saveButton.setText("保存为新版本"); }
                    toast(message == null || message.isEmpty() ? "保存失败" : message);
                }
            });
        } catch (Exception e) {
            saving = false;
            if (saveButton != null) { saveButton.setEnabled(true); saveButton.setText("保存为新版本"); }
            toast("保存失败");
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }
}
