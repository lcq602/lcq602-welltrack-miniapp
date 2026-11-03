package art.qelora.welltrack.pages;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
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

import org.json.JSONObject;

import java.util.Calendar;

import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 计划页: 与小程序 pages/plan 同构。
 * GET /api/v1/profile 读取最新资料版本; 未配置时引导去首页;
 * 已配置时可改管理方向、当前/目标体重、开始/目标日期与每周安排,
 * POST /api/v1/profile 追加保存为新版本。
 */
public class PlanPage extends BasePage {
    private static final String[] WEEK_OPTIONS = {"有氧运动", "力量训练", "散步", "休息", "拉伸", "自由活动"};
    private static final String[] GOAL_OPTIONS = {"减重", "减脂", "维持", "健康习惯"};
    private static final String[] GOAL_VALUES = {"weight_loss", "fat_loss", "maintenance", "healthy_habits"};
    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] DAY_KEYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    private LinearLayout body;
    private JSONObject profile;          // null = 未配置
    private boolean loading = true;
    private boolean saving = false;

    // 表单状态 (已配置时有效)
    private int goalIndex = 0;
    private String startDate = "";
    private String targetDate = "";
    private final String[] dayValues = new String[7];
    private EditText curWeightInput;
    private EditText tarWeightInput;
    private TextView deltaView;
    private TextView startDateView;
    private TextView targetDateView;
    private final TextView[] goalChips = new TextView[4];
    private final TextView[] dayValueViews = new TextView[7];
    private final TextView[] dayTagViews = new TextView[7];
    private Button saveButton;

    public PlanPage(Context ctx) { super(ctx); }

    private static String activityTag(String activity) {
        if ("力量训练".equals(activity)) return "中高强度";
        if ("有氧运动".equals(activity)) return "心肺激活";
        if ("散步".equals(activity)) return "轻松适度";
        if ("休息".equals(activity)) return "恢复日";
        if ("拉伸".equals(activity)) return "柔韧放松";
        if ("自由活动".equals(activity)) return "生活活动";
        return "日常活动";
    }

    private static String[] templateFor(String goal) {
        if ("fat_loss".equals(goal)) return new String[]{"力量训练", "有氧运动", "休息", "力量训练", "散步", "有氧运动", "休息"};
        if ("maintenance".equals(goal)) return new String[]{"散步", "力量训练", "休息", "有氧运动", "休息", "自由活动", "休息"};
        if ("healthy_habits".equals(goal)) return new String[]{"散步", "拉伸", "休息", "有氧运动", "拉伸", "自由活动", "休息"};
        return new String[]{"散步", "休息", "有氧运动", "休息", "散步", "自由活动", "休息"}; // weight_loss
    }

    static String fmtNum(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        Object v = o.opt(key);
        if (v instanceof Number) return fmtDouble(((Number) v).doubleValue());
        return String.valueOf(v);
    }

    static String fmtDouble(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        String s = String.valueOf(d);
        return s;
    }

    @Override
    protected void build() {
        Context c = ctx();
        // 页头
        content.addView(Ui.label(c, "● A PLAN THAT FITS YOU"));
        TextView title = Ui.title(c, "阶段计划");
        title.setTextSize(24);
        content.addView(title, Ui.matchWrap(2, c));

        // 温和心念卡片
        LinearLayout quote = Ui.card(c);
        quote.setBackground(roundStroke(Color.parseColor("#f0fdf4"), 16, Color.parseColor("#c9ecda")));
        TextView qt = Ui.text(c, "✳  稳定，比赶进度更重要", 15, Ui.INK, true);
        quote.addView(qt);
        quote.addView(Ui.text(c, "让计划配合你的生活，而不是反过来。持续的低摩擦行动，胜过短暂的完美主义。",
                12.5f, Ui.SLATE, false), Ui.matchWrap(4, c));
        content.addView(quote, Ui.matchWrap(12, c));

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
        card.addView(Ui.text(ctx(), "正在读取你的专属计划…", 14, Ui.SLATE, false));
        body.addView(card);
    }

    private void load() {
        loading = true;
        // /profile 未配置时返回 JSON null, 用文本接口自行解析
        api().requestText("/api/v1/profile", new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                loading = false;
                try {
                    if (text == null || text.trim().equals("null") || text.trim().isEmpty()) {
                        profile = null;
                    } else {
                        profile = new JSONObject(text);
                    }
                } catch (Exception e) {
                    profile = null;
                }
                renderBody();
            }
            @Override public void onError(String message) {
                loading = false;
                toast(message == null || message.isEmpty() ? "加载失败" : message);
                renderBody();
            }
        });
    }

    private void renderBody() {
        if (body == null) return;
        body.removeAllViews();
        if (profile == null) {
            if (loading) { showLoading(); return; }
            renderEmpty();
            return;
        }
        renderConfigured();
    }

    private void renderEmpty() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        TextView icon = Ui.text(c, "⚙", 26, Ui.PRIMARY, false);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);
        TextView t = Ui.sectionTitle(c, "先建立你的健康起点");
        t.setGravity(Gravity.CENTER);
        card.addView(t, Ui.matchWrap(6, c));
        TextView desc = Ui.text(c, "完成身高、当前体重和目标设置后，就可以编辑每周安排。", 13, Ui.SLATE, false);
        desc.setGravity(Gravity.CENTER);
        card.addView(desc, Ui.matchWrap(6, c));
        Button go = Ui.primaryButton(c, "去首页完成设置 ›");
        go.setOnClickListener(v -> activity().switchTab(0));
        card.addView(go, Ui.matchWrap(14, c));
        body.addView(card);
    }

    private void renderConfigured() {
        Context c = ctx();
        JSONObject p = profile;
        goalIndex = indexOf(GOAL_VALUES, p.optString("goal", "weight_loss"));
        if (goalIndex < 0) goalIndex = 0;
        startDate = p.optString("start_date", "");
        targetDate = p.isNull("target_date") ? "" : p.optString("target_date", "");

        JSONObject weekly = p.optJSONObject("weekly_plan");
        String[] defaults = templateFor(p.optString("goal", "weight_loss"));
        for (int i = 0; i < 7; i++) {
            String v = weekly != null ? weekly.optString(DAY_KEYS[i], "") : "";
            dayValues[i] = v.isEmpty() ? defaults[i] : v;
        }

        // ---- 阶段目标卡片 ----
        LinearLayout card = Ui.card(c);
        card.addView(Ui.label(c, "MY DIRECTION"));
        card.addView(Ui.sectionTitle(c, "阶段目标"), Ui.matchWrap(2, c));
        TextView going = chip("进行中", true);
        card.addView(going, Ui.matchWrap(6, c));

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

        // 双体重输入
        LinearLayout grid = Ui.row(c);
        LinearLayout tile1 = tileBox(c, "当前体重", "kg");
        curWeightInput = tileInput(tile1);
        curWeightInput.setText(fmtNum(p, "current_weight"));
        LinearLayout tile2 = tileBox(c, "目标体重", "kg");
        tarWeightInput = tileInput(tile2);
        tarWeightInput.setText(fmtNum(p, "target_weight"));
        deltaView = Ui.text(c, "", 11, Ui.PRIMARY, true);
        deltaView.setBackground(roundStroke(Color.parseColor("#ecfdf5"), 999, Color.parseColor("#ecfdf5")));
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
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { updateDelta(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        curWeightInput.addTextChangedListener(watcher);
        tarWeightInput.addTextChangedListener(watcher);

        // 日期
        card.addView(Ui.text(c, "计划开始日期", 12.5f, Ui.SLATE, true), Ui.matchWrap(12, c));
        startDateView = dateField(startDate.isEmpty() ? "选择日期" : startDate);
        startDateView.setOnClickListener(v -> pickDate(startDate, true));
        card.addView(startDateView, Ui.matchWrap(4, c));
        card.addView(Ui.text(c, "目标达成日期（选填）", 12.5f, Ui.SLATE, true), Ui.matchWrap(10, c));
        targetDateView = dateField(targetDate.isEmpty() ? "按节奏调整" : targetDate);
        targetDateView.setOnClickListener(v -> pickDate(targetDate, false));
        card.addView(targetDateView, Ui.matchWrap(4, c));

        card.addView(Ui.text(c, "💡 日期是规划参考，可以按身体感受随时调整，不代表死板的进度承诺。",
                12, Ui.MUTED, false), Ui.matchWrap(12, c));
        body.addView(card);

        // ---- 周安排卡片 ----
        LinearLayout schedule = Ui.card(c);
        schedule.addView(Ui.label(c, "YOUR WEEK"));
        schedule.addView(Ui.sectionTitle(c, "安排一个现实的星期"), Ui.matchWrap(2, c));
        schedule.addView(Ui.text(c, "训练、活动和休息都算计划的一部分", 12.5f, Ui.SLATE, false), Ui.matchWrap(2, c));
        TextView weekPill = chip("周节奏", false);
        schedule.addView(weekPill, Ui.matchWrap(6, c));

        for (int i = 0; i < 7; i++) {
            final int idx = i;
            LinearLayout rowLayout = Ui.row(c);
            TextView badge = Ui.text(c, DAY_NAMES[i], 13, Color.parseColor("#2563eb"), true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(roundStroke(Ui.BLUE_SOFT, 10, Color.parseColor("#d7e7fd")));
            badge.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 34)));
            rowLayout.addView(badge);

            LinearLayout selector = Ui.column(c);
            selector.setBackground(roundStroke(Color.parseColor("#f8fafc"), 12, Ui.BORDER));
            int hp = Ui.dp(c, 12), vp = Ui.dp(c, 9);
            selector.setPadding(hp, vp, hp, vp);
            TextView val = Ui.text(c, dayValues[i], 14.5f, Ui.INK, true);
            TextView tag = Ui.text(c, activityTag(dayValues[i]), 11, Ui.SLATE, false);
            selector.addView(val);
            selector.addView(tag);
            dayValueViews[i] = val;
            dayTagViews[i] = tag;
            LinearLayout.LayoutParams slp = Ui.weight1();
            slp.leftMargin = Ui.dp(c, 10);
            selector.setOnClickListener(v -> pickActivity(idx));
            rowLayout.addView(selector, slp);
            schedule.addView(rowLayout, Ui.matchWrap(8, c));
        }

        saveButton = Ui.primaryButton(c, "保存为新版本");
        saveButton.setOnClickListener(v -> save());
        schedule.addView(saveButton, Ui.matchWrap(16, c));
        TextView note = Ui.text(c, "保存会新增一份计划版本，历史版本保留。", 12, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        schedule.addView(note, Ui.matchWrap(6, c));
        body.addView(schedule, Ui.matchWrap(12, c));
    }

    private LinearLayout tileBox(Context c, String label, String unit) {
        LinearLayout box = Ui.column(c);
        box.setBackground(roundStroke(Color.parseColor("#f8fafc"), 14, Ui.BORDER));
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
        t.setBackground(roundStroke(Color.parseColor("#f8fafc"), 12, Ui.BORDER));
        int hp = Ui.dp(ctx(), 12), vp = Ui.dp(ctx(), 11);
        t.setPadding(hp, vp, hp, vp);
        return t;
    }

    private TextView chip(String text, boolean active) {
        TextView t = Ui.text(ctx(), text, 12.5f, active ? Color.WHITE : Color.parseColor("#475569"), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(active ? Ui.PRIMARY : Color.parseColor("#f8fafc"), 999,
                active ? Ui.PRIMARY : Ui.BORDER));
        int vp = Ui.dp(ctx(), 7);
        t.setPadding(Ui.dp(ctx(), 8), vp, Ui.dp(ctx(), 8), vp);
        return t;
    }

    private void refreshGoalChips() {
        for (int i = 0; i < 4; i++) {
            if (goalChips[i] == null) continue;
            boolean active = i == goalIndex;
            goalChips[i].setTextColor(active ? Color.WHITE : Color.parseColor("#475569"));
            goalChips[i].setBackground(roundStroke(active ? Ui.PRIMARY : Color.parseColor("#f8fafc"), 999,
                    active ? Ui.PRIMARY : Ui.BORDER));
        }
    }

    private void updateDelta() {
        if (deltaView == null || curWeightInput == null || tarWeightInput == null) return;
        try {
            double cur = Double.parseDouble(curWeightInput.getText().toString());
            double tar = Double.parseDouble(tarWeightInput.getText().toString());
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
            String val = String.format(java.util.Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd);
            if (isStart) {
                startDate = val;
                if (startDateView != null) startDateView.setText(val + "  📅");
            } else {
                targetDate = val;
                if (targetDateView != null) targetDateView.setText(val + "  📅");
            }
        }, y, m, d).show();
    }

    private void pickActivity(final int dayIdx) {
        int choice = indexOf(WEEK_OPTIONS, dayValues[dayIdx]);
        if (choice < 0) choice = 0;
        new AlertDialog.Builder(ctx())
                .setTitle(DAY_NAMES[dayIdx] + "的安排")
                .setSingleChoiceItems(WEEK_OPTIONS, choice, (dialog, which) -> {
                    dayValues[dayIdx] = WEEK_OPTIONS[which];
                    if (dayValueViews[dayIdx] != null) dayValueViews[dayIdx].setText(dayValues[dayIdx]);
                    if (dayTagViews[dayIdx] != null) dayTagViews[dayIdx].setText(activityTag(dayValues[dayIdx]));
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void save() {
        if (saving || profile == null) return;
        String heightStr = fmtNum(profile, "height_cm");
        String curStr = curWeightInput != null ? curWeightInput.getText().toString().trim() : "";
        String tarStr = tarWeightInput != null ? tarWeightInput.getText().toString().trim() : "";
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
            JSONObject weekly = new JSONObject();
            for (int i = 0; i < 7; i++) weekly.put(DAY_KEYS[i], dayValues[i]);
            JSONObject payload = new JSONObject();
            payload.put("height_cm", height);
            payload.put("current_weight", cur);
            payload.put("target_weight", tar);
            payload.put("start_date", startDate);
            payload.put("target_date", targetDate.isEmpty() ? JSONObject.NULL : targetDate);
            payload.put("goal", GOAL_VALUES[goalIndex]);
            payload.put("weekly_plan", weekly);
            JSONObject reminders = profile.optJSONObject("reminder_settings");
            payload.put("reminder_settings", reminders != null ? reminders : new JSONObject());

            api().request("/api/v1/profile", "POST", payload, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    saving = false;
                    if (saveButton != null) { saveButton.setEnabled(true); saveButton.setText("保存为新版本"); }
                    toast("新计划已保存");
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
