package art.qelora.welltrack.pages;

import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 记录页: 与小程序 pages/records 同构。
 * 日期 + 三组表单保存 POST /api/v1/records; 历史列表 GET /api/v1/records?limit=60
 * (含手环同步合并数据), 支持 全部/手环同步/手工记录 筛选;
 * 顶部可导出 CSV (/api/v1/export.csv) 与 JSON 备份 (/api/v1/backup) 到应用下载目录。
 */
public class RecordsPage extends BasePage {
    private static final String[] HABIT_NAMES = {"运动", "均衡饮食", "喝够水", "按时睡眠", "减少久坐"};

    private String recordDate;
    private EditText weightInput, waistInput, waterInput, sleepInput, dietInput, noteInput;
    private TextView dateValueView, miHintView, alreadyView, historySubtitle;
    private Button saveButton;
    private final TextView[] habitChips = new TextView[HABIT_NAMES.length];
    private final boolean[] habitSelected = new boolean[HABIT_NAMES.length];
    private final TextView[] filterChips = new TextView[3];
    private LinearLayout historyList;

    private final List<JSONObject> records = new ArrayList<>();
    private String filterType = "all";
    private boolean loading = true;
    private boolean hasLoaded = false;
    private boolean saving = false;
    private boolean alreadySaved = false;

    public RecordsPage(Context ctx) {
        super(ctx);
        recordDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    @Override
    protected void build() {
        Context c = ctx();
        // 页头 + 导出入口
        content.addView(Ui.label(c, "● A GENTLE DAILY CHECK-IN"));
        TextView title = Ui.title(c, "记录今天");
        title.setTextSize(24);
        content.addView(title, Ui.matchWrap(2, c));
        LinearLayout exportRow = Ui.row(c);
        Button csvBtn = Ui.ghostButton(c, "📊 导出 CSV");
        csvBtn.setOnClickListener(v -> exportFile("/api/v1/export.csv", "welltrack-export.csv", "CSV 数据"));
        Button bakBtn = Ui.ghostButton(c, "💾 备份");
        bakBtn.setOnClickListener(v -> exportFile("/api/v1/backup", "welltrack-backup.json", "JSON 备份"));
        exportRow.addView(csvBtn);
        LinearLayout.LayoutParams blp = Ui.weight1();
        blp.leftMargin = Ui.dp(c, 8);
        LinearLayout spacer = Ui.column(c);
        exportRow.addView(spacer, blp);
        exportRow.addView(bakBtn);
        content.addView(exportRow, Ui.matchWrap(10, c));

        // 日期横幅
        LinearLayout dateCard = Ui.card(c);
        LinearLayout dateRow = Ui.row(c);
        TextView dateIcon = Ui.text(c, "📅", 20, Ui.PRIMARY, false);
        dateRow.addView(dateIcon);
        LinearLayout dateTexts = Ui.column(c);
        dateTexts.addView(Ui.text(c, "正在记录日期", 11.5f, Ui.MUTED, false));
        dateValueView = Ui.text(c, recordDate, 16, Ui.INK, true);
        dateTexts.addView(dateValueView);
        LinearLayout.LayoutParams dtlp = Ui.weight1();
        dtlp.leftMargin = Ui.dp(c, 10);
        dateRow.addView(dateTexts, dtlp);
        Button changeDate = Ui.ghostButton(c, "更改日期 ⌄");
        changeDate.setOnClickListener(v -> pickDate());
        dateRow.addView(changeDate);
        dateCard.addView(dateRow);
        content.addView(dateCard, Ui.matchWrap(12, c));

        // 表单卡片
        LinearLayout form = Ui.card(c);
        // 01 身体数据
        form.addView(sectionHead(c, "01", "身体数据", "不必每天都称重，记录方便观察的项目",
                Color.parseColor("#ecfdf5"), Ui.PRIMARY));
        LinearLayout grid1 = Ui.row(c);
        weightInput = tileInput(form, grid1, "体重 · kg", "—", "kg", true);
        waistInput = tileInput(form, grid1, "腰围 · cm", "—", "cm", false);
        form.addView(grid1, Ui.matchWrap(8, c));
        // 02 日常状态
        form.addView(sectionHead(c, "02", "日常状态", "关注补水、作息与饱腹体感",
                Ui.BLUE_SOFT, Color.parseColor("#2563eb")), Ui.matchWrap(16, c));
        LinearLayout grid2 = Ui.row(c);
        waterInput = tileInput(form, grid2, "饮水 · ml", "如 2000", "ml", true);
        sleepInput = tileInput(form, grid2, "睡眠 · 小时", "如 7.5", "h", false);
        form.addView(grid2, Ui.matchWrap(8, c));
        form.addView(Ui.text(c, "饮食简记", 12.5f, Ui.SLATE, true), Ui.matchWrap(10, c));
        dietInput = Ui.input(c, "例如：三餐规律，晚餐蔬菜过半，无夜宵", InputType.TYPE_CLASS_TEXT);
        form.addView(dietInput, Ui.matchWrap(4, c));
        // 03 小习惯
        form.addView(sectionHead(c, "03", "今天做到的小习惯", "勾选达成的行动，累积正向复利",
                Color.parseColor("#fffbeb"), Color.parseColor("#d97706")), Ui.matchWrap(16, c));
        GridLayout habitGrid = new GridLayout(c);
        habitGrid.setColumnCount(2);
        for (int i = 0; i < HABIT_NAMES.length; i++) {
            final int idx = i;
            TextView chipView = habitChip(HABIT_NAMES[i], false);
            chipView.setOnClickListener(v -> {
                habitSelected[idx] = !habitSelected[idx];
                refreshHabitChip(idx);
            });
            habitChips[i] = chipView;
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = 0;
            glp.columnSpec = GridLayout.spec(i % 2, 1f);
            glp.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
            habitGrid.addView(chipView, glp);
        }
        form.addView(habitGrid, Ui.matchWrap(6, c));
        // 备注
        form.addView(Ui.text(c, "补充备注 · 选填", 12.5f, Ui.SLATE, true), Ui.matchWrap(10, c));
        noteInput = Ui.input(c, "写下今天身体感受或值得记住的一件事…", InputType.TYPE_CLASS_TEXT);
        form.addView(noteInput, Ui.matchWrap(4, c));
        // 手环同步提示 / 已有记录提示
        miHintView = Ui.text(c, "", 12.5f, Color.parseColor("#0369a1"), false);
        miHintView.setVisibility(View.GONE);
        form.addView(miHintView, Ui.matchWrap(10, c));
        alreadyView = Ui.text(c, "✓ 这一天已有手工记录，可切换上方日期查看或修改。", 12.5f, Ui.PRIMARY, false);
        alreadyView.setVisibility(View.GONE);
        form.addView(alreadyView, Ui.matchWrap(8, c));
        // 保存
        saveButton = Ui.primaryButton(c, "保存今天的记录");
        saveButton.setOnClickListener(v -> save());
        form.addView(saveButton, Ui.matchWrap(14, c));
        TextView foot = Ui.text(c, "每个日期可保存一份手工记录；手环数据会自动在此合并汇流。", 11.5f, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        form.addView(foot, Ui.matchWrap(6, c));
        content.addView(form, Ui.matchWrap(12, c));

        // 历史记录
        content.addView(Ui.sectionTitle(c, "历史记录流"), Ui.matchWrap(16, c));
        historySubtitle = Ui.text(c, "", 12.5f, Ui.SLATE, false);
        content.addView(historySubtitle, Ui.matchWrap(2, c));
        LinearLayout filterRow = Ui.row(c);
        String[] filterLabels = {"全部", "⌚ 手环同步", "📝 手工记录"};
        String[] filterTypes = {"all", "mihealth", "manual"};
        for (int i = 0; i < 3; i++) {
            final String type = filterTypes[i];
            TextView chipView = filterChip(filterLabels[i], i == 0);
            chipView.setOnClickListener(v -> {
                filterType = type;
                refreshFilterChips();
                renderHistory();
            });
            filterChips[i] = chipView;
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) flp.leftMargin = Ui.dp(c, 8);
            filterRow.addView(chipView, flp);
        }
        content.addView(filterRow, Ui.matchWrap(10, c));
        historyList = Ui.column(c);
        content.addView(historyList, Ui.matchWrap(8, c));
    }

    @Override
    public void onShow() {
        super.onShow();
        load();
    }

    /* ---------- 控件小工厂 ---------- */
    private GradientDrawable roundStroke(int color, float radiusDp, int strokeColor) {
        GradientDrawable d = Ui.roundRect(color, radiusDp, ctx());
        d.setStroke(1, strokeColor);
        return d;
    }

    private LinearLayout sectionHead(Context c, String badge, String title, String subtitle,
                                     int badgeBg, int badgeColor) {
        LinearLayout rowLayout = Ui.row(c);
        TextView b = Ui.text(c, badge, 12, badgeColor, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.roundRect(badgeBg, 9, c));
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));
        rowLayout.addView(b);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, title, 15, Ui.INK, true));
        texts.addView(Ui.text(c, subtitle, 12, Ui.SLATE, false));
        LinearLayout.LayoutParams lp = Ui.weight1();
        lp.leftMargin = Ui.dp(c, 10);
        rowLayout.addView(texts, lp);
        return rowLayout;
    }

    /** 在一行网格里加一个数值输入瓷砖, 返回输入框 */
    private EditText tileInput(LinearLayout form, LinearLayout grid, String label, String hint,
                               String unit, boolean first) {
        Context c = ctx();
        LinearLayout box = Ui.column(c);
        box.setBackground(roundStroke(Color.parseColor("#f8fafc"), 14, Ui.BORDER));
        int p = Ui.dp(c, 12);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(c, label, 12, Ui.SLATE, true));
        EditText e = Ui.input(c, hint, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackground(null);
        e.setTextSize(17);
        box.addView(e, Ui.matchWrap(2, c));
        LinearLayout.LayoutParams lp = Ui.weight1();
        if (!first) lp.leftMargin = Ui.dp(c, 10);
        grid.addView(box, lp);
        return e;
    }

    private TextView habitChip(String name, boolean active) {
        TextView t = Ui.text(ctx(), (active ? "✓ " : "+ ") + name, 13.5f,
                active ? Color.WHITE : Color.parseColor("#475569"), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(active ? Ui.PRIMARY : Color.parseColor("#f8fafc"), 999,
                active ? Ui.PRIMARY : Ui.BORDER));
        int vp = Ui.dp(ctx(), 8);
        t.setPadding(0, vp, 0, vp);
        return t;
    }

    private void refreshHabitChip(int i) {
        if (habitChips[i] == null) return;
        boolean active = habitSelected[i];
        habitChips[i].setText((active ? "✓ " : "+ ") + HABIT_NAMES[i]);
        habitChips[i].setTextColor(active ? Color.WHITE : Color.parseColor("#475569"));
        habitChips[i].setBackground(roundStroke(active ? Ui.PRIMARY : Color.parseColor("#f8fafc"), 999,
                active ? Ui.PRIMARY : Ui.BORDER));
    }

    private TextView filterChip(String text, boolean active) {
        TextView t = Ui.text(ctx(), text, 12.5f, active ? Color.WHITE : Color.parseColor("#475569"), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(active ? Ui.PRIMARY : Color.WHITE, 999, active ? Ui.PRIMARY : Ui.BORDER));
        int vp = Ui.dp(ctx(), 7), hp = Ui.dp(ctx(), 12);
        t.setPadding(hp, vp, hp, vp);
        return t;
    }

    private void refreshFilterChips() {
        String[] types = {"all", "mihealth", "manual"};
        for (int i = 0; i < 3; i++) {
            if (filterChips[i] == null) continue;
            boolean active = types[i].equals(filterType);
            filterChips[i].setTextColor(active ? Color.WHITE : Color.parseColor("#475569"));
            filterChips[i].setBackground(roundStroke(active ? Ui.PRIMARY : Color.WHITE, 999,
                    active ? Ui.PRIMARY : Ui.BORDER));
        }
        // “全部”胶囊带总数, 与源码一致
        if (filterChips[0] != null) filterChips[0].setText("全部 (" + records.size() + ")");
    }

    /* ---------- 数据 ---------- */
    private void load() {
        loading = !hasLoaded;
        if (saveButton != null) saveButton.setText("正在检查记录…");
        renderHistory();
        api().requestText("/api/v1/records?limit=60", new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                records.clear();
                try {
                    JSONArray arr = new JSONArray(text == null ? "[]" : text);
                    for (int i = 0; i < arr.length(); i++) records.add(arr.optJSONObject(i));
                } catch (Exception ignored) {}
                loading = false;
                hasLoaded = true;
                updateDateFlags();
                refreshFilterChips();
                renderHistory();
            }
            @Override public void onError(String message) {
                loading = false;
                hasLoaded = true;
                toast(message == null || message.isEmpty() ? "读取失败" : message);
                renderHistory();
            }
        });
    }

    private void updateDateFlags() {
        boolean manual = false;
        String hint = "";
        for (JSONObject r : records) {
            if (r == null) continue;
            if (!recordDate.equals(r.optString("record_date"))) continue;
            if (!r.isNull("id")) manual = true;
            if ("mihealth".equals(r.optString("weight_source")) && !r.isNull("weight")) {
                hint = "⌚ 已由小米手环/体脂秤同步体重 " + fmtNum(r, "weight") + " kg";
            }
        }
        alreadySaved = manual;
        if (miHintView != null) {
            miHintView.setText(hint);
            miHintView.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (alreadyView != null) alreadyView.setVisibility(alreadySaved ? View.VISIBLE : View.GONE);
        updateSaveButton();
    }

    private void updateSaveButton() {
        if (saveButton == null) return;
        if (saving) { saveButton.setText("正在保存…"); saveButton.setEnabled(false); return; }
        if (loading && !hasLoaded) { saveButton.setText("正在检查记录…"); saveButton.setEnabled(false); return; }
        if (alreadySaved) { saveButton.setText("当天手工记录已存在"); saveButton.setEnabled(false); return; }
        saveButton.setText("保存今天的记录");
        saveButton.setEnabled(true);
    }

    private List<JSONObject> applyFilter() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject r : records) {
            if (r == null) continue;
            if ("mihealth".equals(filterType)) {
                if ("mihealth".equals(r.optString("weight_source")) || !r.isNull("steps")
                        || !r.isNull("calories") || !r.isNull("workout_minutes")) out.add(r);
            } else if ("manual".equals(filterType)) {
                if (!r.isNull("id")) out.add(r);
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private void renderHistory() {
        if (historyList == null) return;
        historyList.removeAllViews();
        Context c = ctx();
        if (historySubtitle != null) {
            historySubtitle.setText("共 " + records.size() + " 天数据，含手工日常与手环同步");
        }
        if (loading && !hasLoaded) {
            LinearLayout card = Ui.card(c);
            card.addView(Ui.text(c, "正在整理最近记录…", 14, Ui.SLATE, false));
            historyList.addView(card);
            return;
        }
        List<JSONObject> list = applyFilter();
        if (list.isEmpty()) {
            LinearLayout card = Ui.card(c);
            TextView t = Ui.sectionTitle(c, "📝 该筛选下暂无记录");
            card.addView(t);
            card.addView(Ui.text(c, "选择其他分类或在上方记录今天的数据。", 12.5f, Ui.SLATE, false),
                    Ui.matchWrap(4, c));
            historyList.addView(card);
            return;
        }
        for (JSONObject r : list) {
            historyList.addView(historyCard(r), Ui.matchWrap(10, c));
        }
    }

    private View historyCard(JSONObject r) {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        // 顶部: 日期 + 手环徽标 + 体重
        LinearLayout top = Ui.row(c);
        LinearLayout left = Ui.column(c);
        String badge = r.optString("record_date");
        boolean miBadge = "mihealth".equals(r.optString("weight_source")) || r.optBoolean("is_mihealth_only");
        if (miBadge) {
            badge += r.optBoolean("is_mihealth_only") ? "   ⌚ 手环同步" : "   ⌚ 手环体重";
        }
        left.addView(Ui.text(c, badge, 13.5f, Ui.INK, true));
        JSONArray habits = r.optJSONArray("habits");
        if (habits != null && habits.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < habits.length(); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(habits.optString(i));
            }
            left.addView(Ui.text(c, sb.toString(), 12, Ui.SLATE, false), Ui.matchWrap(2, c));
        }
        top.addView(left, Ui.weight1());
        if (!r.isNull("weight")) {
            TextView w = Ui.text(c, fmtNum(r, "weight") + " kg", 16, Ui.PRIMARY, true);
            w.setBackground(roundStroke(Color.parseColor("#ecfdf5"), 999, Color.parseColor("#ecfdf5")));
            int hp = Ui.dp(c, 10), vp = Ui.dp(c, 5);
            w.setPadding(hp, vp, hp, vp);
            top.addView(w);
        }
        card.addView(top);

        // 小标签 (步数/热量/睡眠/运动/腰围/饮水)
        List<String[]> tags = new ArrayList<>();
        if (!r.isNull("steps")) tags.add(new String[]{"👟 步数", fmtNum(r, "steps") + " 步"});
        if (!r.isNull("calories")) tags.add(new String[]{"🔥 消耗", fmtNum(r, "calories") + " kcal"});
        if (!r.isNull("sleep_hours")) tags.add(new String[]{"🌙 睡眠", fmtNum(r, "sleep_hours") + " h"});
        if (!r.isNull("workout_minutes")) tags.add(new String[]{"⏱️ 运动", fmtNum(r, "workout_minutes") + " min"});
        if (!r.isNull("waist_cm")) tags.add(new String[]{"腰围", fmtNum(r, "waist_cm") + " cm"});
        if (!r.isNull("water_ml")) tags.add(new String[]{"饮水", fmtNum(r, "water_ml") + " ml"});
        if (!tags.isEmpty()) {
            GridLayout grid = new GridLayout(c);
            grid.setColumnCount(2);
            for (String[] tag : tags) {
                TextView t = Ui.text(c, tag[0] + "  " + tag[1], 12, Color.parseColor("#334155"), false);
                t.setBackground(roundStroke(Color.parseColor("#f8fafc"), 8, Ui.BORDER));
                int hp = Ui.dp(c, 8), vp = Ui.dp(c, 5);
                t.setPadding(hp, vp, hp, vp);
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = 0;
                glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                glp.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
                grid.addView(t, glp);
            }
            card.addView(grid, Ui.matchWrap(8, c));
        }

        String diet = r.optString("diet", "");
        if (!diet.isEmpty()) {
            TextView d = Ui.text(c, "🥗 " + diet, 12.5f, Color.parseColor("#334155"), false);
            d.setBackground(roundStroke(Color.parseColor("#f0fdf4"), 10, Color.parseColor("#dcfce7")));
            int p = Ui.dp(c, 10);
            d.setPadding(p, p, p, p);
            card.addView(d, Ui.matchWrap(8, c));
        }
        String note = r.optString("note", "");
        if (!note.isEmpty()) {
            card.addView(Ui.text(c, "“" + note + "”", 12.5f, Ui.SLATE, false), Ui.matchWrap(6, c));
        }
        return card;
    }

    /* ---------- 交互 ---------- */
    private void pickDate() {
        Calendar cal = Calendar.getInstance();
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH), d = cal.get(Calendar.DAY_OF_MONTH);
        if (recordDate != null && recordDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            y = Integer.parseInt(recordDate.substring(0, 4));
            m = Integer.parseInt(recordDate.substring(5, 7)) - 1;
            d = Integer.parseInt(recordDate.substring(8, 10));
        }
        new DatePickerDialog(ctx(), (view, yy, mm, dd) -> {
            recordDate = String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd);
            if (dateValueView != null) dateValueView.setText(recordDate);
            updateDateFlags();
        }, y, m, d).show();
    }

    private List<String> selectedHabits() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < HABIT_NAMES.length; i++) if (habitSelected[i]) out.add(HABIT_NAMES[i]);
        return out;
    }

    private void save() {
        if (loading && !hasLoaded || saving) { toast("正在加载或保存"); return; }
        if (alreadySaved) { toast("这一天已有手工记录，请选择其他日期"); return; }

        String weight = weightInput.getText().toString().trim();
        String waist = waistInput.getText().toString().trim();
        String water = waterInput.getText().toString().trim();
        String sleep = sleepInput.getText().toString().trim();
        String diet = dietInput.getText().toString().trim();
        String note = noteInput.getText().toString().trim();
        List<String> habits = selectedHabits();

        boolean hasValue = !(weight.isEmpty() && waist.isEmpty() && water.isEmpty()
                && sleep.isEmpty() && diet.isEmpty() && note.isEmpty()) || !habits.isEmpty();
        if (!hasValue) { toast("先填写一项数据或选择一个习惯"); return; }

        // 范围校验与源码一致: 体重(20,500] 腰围(20,300] 饮水[0,10000] 睡眠[0,24]
        if (!checkRange("体重", weight, 20, 500, false)) return;
        if (!checkRange("腰围", waist, 20, 300, false)) return;
        if (!checkRange("饮水", water, 0, 10000, true)) return;
        if (!checkRange("睡眠", sleep, 0, 24, true)) return;

        saving = true;
        updateSaveButton();
        try {
            JSONObject body = new JSONObject();
            body.put("record_date", recordDate);
            body.put("weight", weight.isEmpty() ? JSONObject.NULL : Double.parseDouble(weight));
            body.put("waist_cm", waist.isEmpty() ? JSONObject.NULL : Double.parseDouble(waist));
            body.put("water_ml", water.isEmpty() ? JSONObject.NULL : parseWater(water));
            body.put("sleep_hours", sleep.isEmpty() ? JSONObject.NULL : Double.parseDouble(sleep));
            body.put("diet", diet);
            body.put("note", note);
            body.put("habits", new JSONArray(habits));

            api().request("/api/v1/records", "POST", body, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    saving = false;
                    toast("今天已记录");
                    weightInput.setText(""); waistInput.setText(""); waterInput.setText("");
                    sleepInput.setText(""); dietInput.setText(""); noteInput.setText("");
                    for (int i = 0; i < HABIT_NAMES.length; i++) { habitSelected[i] = false; refreshHabitChip(i); }
                    alreadySaved = true;
                    if (alreadyView != null) alreadyView.setVisibility(View.VISIBLE);
                    updateSaveButton();
                    load();
                }
                @Override public void onError(String message) {
                    saving = false;
                    updateSaveButton();
                    if (message != null && message.contains("409")) toast("这一天已有记录，请换一天查看");
                    else toast(message == null || message.isEmpty() ? "保存失败" : message);
                }
            });
        } catch (Exception e) {
            saving = false;
            updateSaveButton();
            toast("保存失败");
        }
    }

    private Object parseWater(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return Double.parseDouble(s); }
    }

    private boolean checkRange(String label, String value, double min, double max, boolean minInclusive) {
        if (value.isEmpty()) return true;
        double v;
        try { v = Double.parseDouble(value); } catch (Exception e) { toast(label + "超出可记录范围"); return false; }
        if ((minInclusive ? v < min : v <= min) || v > max) { toast(label + "超出可记录范围"); return false; }
        return true;
    }

    /** 导出: 取回文本后写到应用专属下载目录并提示路径 (原生替代 downloadFile+saveFile) */
    private void exportFile(String path, final String fileName, final String label) {
        api().requestText(path, new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                try {
                    File dir = ctx().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = ctx().getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                    }
                    toast(label + "已保存: " + out.getAbsolutePath());
                } catch (Exception e) {
                    toast("文件保存失败");
                }
            }
            @Override public void onError(String message) { toast("导出失败"); }
        });
    }

    /* ---------- 数字格式: 与 JS 显示一致 (整数不带小数点) ---------- */
    static String fmtNum(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        Object v = o.opt(key);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }
}
