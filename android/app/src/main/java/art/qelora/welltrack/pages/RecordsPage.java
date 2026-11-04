package art.qelora.welltrack.pages;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import art.qelora.welltrack.MainActivity;
import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.health.Foods;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 记录页 v2 (视觉重构, 数据逻辑与小程序 pages/records 一致):
 * 页头 (标题+导出) → 今日体重主卡 (日期/大号体重/腰围/提示/保存) →
 * 日常状态卡 (饮水/睡眠/饮食, 彩色点行) → 小习惯瓷砖卡 → 备注与运动入口 →
 * 历史区 (总数副标题 + 三段筛选 + 彩色指标标签)。
 * 接口不变: POST /api/v1/records; GET /api/v1/records?limit=60;
 * 导出 /api/v1/export.csv 与 /api/v1/backup 写应用下载目录。
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

    /* v2.5 饮水快捷记录: 今日合计 + 进度条 + 一键加水 (接口 /api/v1/water) */
    private TextView waterQuickText;
    private LinearLayout waterBarBox;

    /* v2.3 饮食记录: 今日条目列表 + 合计 (接口 /api/v1/diet) */
    private static final int REQ_GALLERY = 4101;
    private static final int REQ_CAMERA = 4102;
    private LinearLayout dietListBox;
    private TextView dietSummaryView;
    private final List<JSONObject> dietEntries = new ArrayList<>();
    private AlertDialog photoProgressDialog;

    /** 选图/拍照结果监听: onShow 注册到 MainActivity, onHide 注销 */
    private final MainActivity.ActivityResultListener resultListener =
            new MainActivity.ActivityResultListener() {
                @Override
                public void onActivityResult(int requestCode, int resultCode, Intent data) {
                    handleActivityResult(requestCode, resultCode, data);
                }
            };

    public RecordsPage(Context ctx) {
        super(ctx);
        recordDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    @Override
    protected void build() {
        Context c = ctx();
        // 1. 页头行: 大标题 + 右侧导出 chip
        LinearLayout headRow = Ui.row(c);
        LinearLayout headTexts = Ui.column(c);
        headTexts.addView(Ui.label(c, "● A GENTLE DAILY CHECK-IN"));
        TextView title = Ui.title(c, "记录今天");
        title.setTextSize(24);
        headTexts.addView(title, Ui.matchWrap(2, c));
        headRow.addView(headTexts, Ui.weight1());
        TextView csvChip = Ui.chip(c, "📊 导出 CSV", Ui.PRIMARY, Ui.GREEN_SOFT);
        csvChip.setOnClickListener(v -> exportFile("/api/v1/export.csv", "welltrack-export.csv", "CSV 数据"));
        headRow.addView(csvChip);
        TextView bakChip = Ui.chip(c, "💾 备份", Ui.PRIMARY, Ui.GREEN_SOFT);
        bakChip.setOnClickListener(v -> exportFile("/api/v1/backup", "welltrack-backup.json", "JSON 备份"));
        LinearLayout.LayoutParams bakLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bakLp.leftMargin = Ui.dp(c, 8);
        headRow.addView(bakChip, bakLp);
        content.addView(headRow);

        // 2. 今日体重主卡: 日期行 / 大号体重 / 腰围 / 提示条 / 保存
        LinearLayout weightCard = Ui.card(c);
        LinearLayout dateRow = Ui.row(c);
        dateRow.addView(Ui.text(c, "📅", 20, Ui.PRIMARY, false));
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
        weightCard.addView(dateRow);

        weightCard.addView(Ui.text(c, "今日体重", 12.5f, Ui.SLATE, true), Ui.matchWrap(16, c));
        LinearLayout weightRow = Ui.row(c);
        weightInput = Ui.input(c, "—", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weightInput.setTextSize(22);
        int wvp = Ui.dp(c, 14);
        weightInput.setPadding(Ui.dp(c, 14), wvp, Ui.dp(c, 14), wvp);
        weightInput.setGravity(Gravity.CENTER_VERTICAL);
        weightRow.addView(weightInput, Ui.weight1());
        TextView kg = Ui.text(c, "kg", 16, Ui.MUTED, true);
        LinearLayout.LayoutParams kgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kgLp.leftMargin = Ui.dp(c, 10);
        weightRow.addView(kg, kgLp);
        weightCard.addView(weightRow, Ui.matchWrap(6, c));

        LinearLayout waistRow = Ui.row(c);
        waistRow.addView(Ui.text(c, "腰围", 13, Ui.SLATE, true));
        waistInput = Ui.input(c, "—", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams waistLp = Ui.weight1();
        waistLp.leftMargin = Ui.dp(c, 12);
        waistRow.addView(waistInput, waistLp);
        TextView cm = Ui.text(c, "cm", 13, Ui.MUTED, false);
        LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cmLp.leftMargin = Ui.dp(c, 8);
        waistRow.addView(cm, cmLp);
        weightCard.addView(waistRow, Ui.matchWrap(10, c));

        // 手环同步提示 / 已有记录提示 (原有逻辑)
        miHintView = Ui.text(c, "", 12.5f, Ui.MOVE, false);
        miHintView.setVisibility(View.GONE);
        weightCard.addView(miHintView, Ui.matchWrap(12, c));
        alreadyView = Ui.text(c, "✓ 这一天已有手工记录，可切换上方日期查看或修改。", 12.5f, Ui.PRIMARY, false);
        alreadyView.setVisibility(View.GONE);
        weightCard.addView(alreadyView, Ui.matchWrap(8, c));

        saveButton = Ui.primaryButton(c, "保存今天的记录");
        saveButton.setOnClickListener(v -> save());
        weightCard.addView(saveButton, Ui.matchWrap(14, c));
        TextView foot = Ui.text(c, "每个日期可保存一份手工记录；手环数据会自动在此合并汇流。", 11.5f, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        weightCard.addView(foot, Ui.matchWrap(6, c));
        content.addView(weightCard, Ui.matchWrap(14, c));

        // 3. 日常状态卡: 彩色点整齐行 (饮水蓝/睡眠紫/饮食橙)
        LinearLayout statusCard = Ui.card(c);
        statusCard.addView(Ui.sectionHeader(c, "日常状态", null, null));
        statusCard.addView(Ui.text(c, "关注补水、作息与饱腹体感", 12, Ui.SLATE, false), Ui.matchWrap(2, c));
        waterInput = statusRow(statusCard, Ui.MOVE, "饮水", "如 2000", "ml", true, true);
        // v2.5: 饮水快捷行 (今日合计走 records 列表, 与首页水卡同一口径)
        LinearLayout waterQuick = Ui.column(c);
        waterQuickText = Ui.text(c, "今日饮水：—", 12.5f, Ui.MOVE, true);
        waterQuick.addView(waterQuickText);
        waterBarBox = Ui.column(c);
        waterQuick.addView(waterBarBox, Ui.matchWrap(6, c));
        LinearLayout waterBtnRow = Ui.row(c);
        Button water250 = Ui.ghostButton(c, "＋ 250 ml");
        water250.setOnClickListener(v -> addWaterQuick(250));
        waterBtnRow.addView(water250, Ui.weight1());
        Button waterCustom = Ui.ghostButton(c, "自定义…");
        waterCustom.setOnClickListener(v -> showWaterCustomDialog());
        LinearLayout.LayoutParams wclp = Ui.weight1();
        wclp.leftMargin = Ui.dp(c, 8);
        waterBtnRow.addView(waterCustom, wclp);
        waterQuick.addView(waterBtnRow, Ui.matchWrap(8, c));
        statusCard.addView(waterQuick, Ui.matchWrap(10, c));
        sleepInput = statusRow(statusCard, Ui.SLEEP, "睡眠", "如 7.5", "小时", true, false);
        dietInput = statusRow(statusCard, Ui.CAL, "饮食简记", "例如：三餐规律，晚餐蔬菜过半，无夜宵", null, false, false);
        content.addView(statusCard, Ui.matchWrap(12, c));

        // 3.5 饮食记录卡 (v2.3): 今日条目 + 合计 + 选择食物/拍照识别入口
        LinearLayout dietCard = Ui.card(c);
        dietCard.addView(Ui.sectionHeader(c, "饮食记录", null, null));
        dietCard.addView(Ui.text(c, "从食物表点选，或拍一张照片让 AI 估算热量", 12, Ui.SLATE, false),
                Ui.matchWrap(2, c));
        dietListBox = Ui.column(c);
        dietCard.addView(dietListBox, Ui.matchWrap(10, c));
        dietSummaryView = Ui.text(c, "", 13, Ui.CAL_TEXT, true);
        dietCard.addView(dietSummaryView, Ui.matchWrap(8, c));
        LinearLayout dietBtnRow = Ui.row(c);
        Button pickFoodBtn = Ui.ghostButton(c, "＋ 选择食物");
        pickFoodBtn.setOnClickListener(v -> showFoodPicker());
        dietBtnRow.addView(pickFoodBtn, Ui.weight1());
        Button photoBtn = Ui.primaryButton(c, "📷 拍照识别");
        photoBtn.setOnClickListener(v -> showPhotoSource());
        LinearLayout.LayoutParams photoLp = Ui.weight1();
        photoLp.leftMargin = Ui.dp(c, 10);
        dietBtnRow.addView(photoBtn, photoLp);
        dietCard.addView(dietBtnRow, Ui.matchWrap(12, c));
        content.addView(dietCard, Ui.matchWrap(12, c));

        // 4. 小习惯卡: 2 列瓷砖勾选
        LinearLayout habitCard = Ui.card(c);
        habitCard.addView(Ui.sectionHeader(c, "今天做到的小习惯", null, null));
        habitCard.addView(Ui.text(c, "勾选达成的行动，累积正向复利", 12, Ui.SLATE, false), Ui.matchWrap(2, c));
        GridLayout habitGrid = new GridLayout(c);
        habitGrid.setColumnCount(2);
        for (int i = 0; i < HABIT_NAMES.length; i++) {
            final int idx = i;
            TextView tile = habitTile(HABIT_NAMES[i], false);
            tile.setOnClickListener(v -> {
                habitSelected[idx] = !habitSelected[idx];
                refreshHabitChip(idx);
            });
            habitChips[i] = tile;
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = 0;
            glp.columnSpec = GridLayout.spec(i % 2, 1f);
            glp.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
            habitGrid.addView(tile, glp);
        }
        habitCard.addView(habitGrid, Ui.matchWrap(8, c));
        content.addView(habitCard, Ui.matchWrap(12, c));

        // 5. 备注 + 运动快捷入口
        LinearLayout noteCard = Ui.card(c);
        noteCard.addView(Ui.text(c, "补充备注 · 选填", 12.5f, Ui.SLATE, true));
        noteInput = Ui.input(c, "写下今天身体感受或值得记住的一件事…", InputType.TYPE_CLASS_TEXT);
        noteCard.addView(noteInput, Ui.matchWrap(6, c));
        LinearLayout exerciseRow = Ui.row(c);
        exerciseRow.addView(Ui.text(c, "刚运动完？顺手记一笔", 12.5f, Ui.SLATE, false), Ui.weight1());
        TextView goExercise = Ui.text(c, "⚡ 记一笔运动 ›", 13, Ui.PRIMARY, true);
        goExercise.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), 0, Ui.dp(c, 6));
        goExercise.setOnClickListener(v -> activity().openExercise());
        exerciseRow.addView(goExercise);
        noteCard.addView(exerciseRow, Ui.matchWrap(10, c));
        content.addView(noteCard, Ui.matchWrap(12, c));

        // 6. 历史区
        content.addView(Ui.sectionHeader(c, "历史记录", null, null), Ui.matchWrap(18, c));
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
        activity().setActivityResultListener(resultListener);
        load();
        loadDiet();
    }

    @Override
    public void onHide() {
        super.onHide();
        activity().clearActivityResultListener(resultListener);
    }

    /* ---------- 控件小工厂 ---------- */
    private GradientDrawable roundStroke(int color, float radiusDp, int strokeColor) {
        GradientDrawable d = Ui.roundRect(color, radiusDp, ctx());
        d.setStroke(1, strokeColor);
        return d;
    }

    private View dotView(int color) {
        View dot = new View(ctx());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        dot.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx(), 8), Ui.dp(ctx(), 8)));
        return dot;
    }

    /** 日常状态一行: 彩色点 + 标签 + 输入 (+单位), 返回输入框 */
    private EditText statusRow(LinearLayout card, int dotColor, String label, String hint,
                               String unit, boolean numeric, boolean first) {
        Context c = ctx();
        LinearLayout rowLayout = Ui.row(c);
        rowLayout.addView(dotView(dotColor));
        TextView lb = Ui.text(c, label, 13.5f, Ui.INK, true);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(Ui.dp(c, 62), LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.leftMargin = Ui.dp(c, 8);
        rowLayout.addView(lb, lbLp);
        EditText e = Ui.input(c, hint, numeric
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT);
        rowLayout.addView(e, Ui.weight1());
        if (unit != null) {
            TextView u = Ui.text(c, unit, 12, Ui.MUTED, false);
            LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            uLp.leftMargin = Ui.dp(c, 8);
            rowLayout.addView(u, uLp);
        }
        card.addView(rowLayout, Ui.matchWrap(first ? 12 : 10, c));
        return e;
    }

    private TextView habitTile(String name, boolean active) {
        TextView t = Ui.text(ctx(), (active ? "✓ " : "+ ") + name, 13.5f,
                active ? Ui.PRIMARY_DARK : Ui.SLATE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.roundRect(active ? Ui.GREEN_SOFT : Ui.SOFT, 12, ctx()));
        int vp = Ui.dp(ctx(), 12);
        t.setPadding(0, vp, 0, vp);
        return t;
    }

    private void refreshHabitChip(int i) {
        if (habitChips[i] == null) return;
        boolean active = habitSelected[i];
        habitChips[i].setText((active ? "✓ " : "+ ") + HABIT_NAMES[i]);
        habitChips[i].setTextColor(active ? Ui.PRIMARY_DARK : Ui.SLATE);
        habitChips[i].setBackground(Ui.roundRect(active ? Ui.GREEN_SOFT : Ui.SOFT, 12, ctx()));
    }

    private TextView filterChip(String text, boolean active) {
        TextView t = Ui.text(ctx(), text, 12.5f, active ? Color.WHITE : Ui.SLATE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(active ? Ui.PRIMARY : Ui.SOFT, 999, active ? Ui.PRIMARY : Ui.BORDER));
        int vp = Ui.dp(ctx(), 7), hp = Ui.dp(ctx(), 12);
        t.setPadding(hp, vp, hp, vp);
        return t;
    }

    private void refreshFilterChips() {
        String[] types = {"all", "mihealth", "manual"};
        for (int i = 0; i < 3; i++) {
            if (filterChips[i] == null) continue;
            boolean active = types[i].equals(filterType);
            filterChips[i].setTextColor(active ? Color.WHITE : Ui.SLATE);
            filterChips[i].setBackground(roundStroke(active ? Ui.PRIMARY : Ui.SOFT, 999,
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
                updateWaterQuick();
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

    /* ---------- v2.5 饮水快捷记录 ---------- */
    private int todayWaterFromRecords() {
        String today = todayStr();
        for (JSONObject r : records) {
            if (r != null && today.equals(r.optString("record_date")) && !r.isNull("water_ml")) {
                return (int) Math.round(r.optDouble("water_ml", 0));
            }
        }
        return 0;
    }

    private void updateWaterQuick() {
        if (waterQuickText == null) return;
        int ml = todayWaterFromRecords();
        int pct = Math.min(100, (int) Math.round(ml / 2000.0 * 100));
        waterQuickText.setText("今日饮水：" + ml + " / 2000 ml · " + pct + "%"
                + (pct >= 100 ? " 🎉 已达标" : ""));
        if (waterBarBox != null) {
            waterBarBox.removeAllViews();
            waterBarBox.addView(Ui.progressBar(ctx(), ml / 2000.0, Ui.MOVE, 8));
        }
    }

    private void addWaterQuick(final int amountMl) {
        if (amountMl < 10 || amountMl > 2000) { toast("单次饮水量需在 10~2000 ml 之间"); return; }
        api().waterAdd(todayStr(), amountMl, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
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
                    addWaterQuick(amount);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateSaveButton() {
        if (saveButton == null) return;
        if (saving) { saveButton.setText("正在保存…"); saveButton.setEnabled(false); return; }
        if (loading && !hasLoaded) { saveButton.setText("正在检查记录…"); saveButton.setEnabled(false); return; }
        if (alreadySaved) { saveButton.setText("当天手工记录已存在"); saveButton.setEnabled(false); return; }
        saveButton.setText("保存今天的记录");
        saveButton.setEnabled(true);
    }

    /* ---------- v2.3 饮食记录 ---------- */
    private String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** 拉取今日饮食条目并渲染 (进入页面/增删后调用) */
    private void loadDiet() {
        api().dietList(todayStr(), new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                dietEntries.clear();
                JSONArray arr = data.optJSONArray("entries");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.optJSONObject(i);
                        if (e != null) dietEntries.add(e);
                    }
                }
                renderDiet(data.optDouble("total_kcal", 0));
            }
            @Override public void onError(String message) {
                renderDiet(-1);
            }
        });
    }

    private void renderDiet(double totalKcal) {
        if (dietListBox == null) return;
        dietListBox.removeAllViews();
        Context c = ctx();
        if (dietEntries.isEmpty()) {
            dietListBox.addView(Ui.text(c, "今天还没有饮食记录，点下方按钮添加第一餐。", 12.5f, Ui.MUTED, false));
            if (dietSummaryView != null) dietSummaryView.setText("今日摄入 0 千卡 · 0 项");
            return;
        }
        double sum = 0;
        for (JSONObject e : dietEntries) {
            sum += e.optDouble("calories_kcal", 0);
            dietListBox.addView(dietEntryRow(e), Ui.matchWrap(6, c));
        }
        double total = totalKcal >= 0 ? totalKcal : sum;
        if (dietSummaryView != null) {
            dietSummaryView.setText("今日摄入 " + Math.round(total) + " 千卡 · " + dietEntries.size() + " 项");
        }
    }

    private View dietEntryRow(final JSONObject e) {
        Context c = ctx();
        LinearLayout rowLayout = Ui.row(c);
        rowLayout.setBackground(roundStroke(Ui.SOFT, 10, Ui.BORDER));
        int hp = Ui.dp(c, 10), vp = Ui.dp(c, 8);
        rowLayout.setPadding(hp, vp, hp, vp);
        String name = e.optString("name", "");
        if ("photo".equals(e.optString("source"))) name = "📷 " + name;
        TextView nameView = Ui.text(c, name, 13.5f, Ui.INK, true);
        rowLayout.addView(nameView, Ui.weight1());
        String sub = "";
        if (!e.isNull("grams")) sub = Math.round(e.optDouble("grams", 0)) + " g · ";
        TextView kcalView = Ui.text(c, sub + Math.round(e.optDouble("calories_kcal", 0)) + " 千卡",
                12.5f, Ui.CAL_TEXT, true);
        rowLayout.addView(kcalView);
        TextView del = Ui.text(c, " ✕ ", 14, Ui.MUTED, true);
        del.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), 0, Ui.dp(c, 2));
        final int id = e.optInt("id", -1);
        del.setOnClickListener(v -> {
            if (id < 0) return;
            api().dietDelete(id, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    toast("已删除");
                    loadDiet();
                }
                @Override public void onError(String message) {
                    toast(message == null || message.isEmpty() ? "删除失败" : message);
                }
            });
        });
        rowLayout.addView(del);
        return rowLayout;
    }

    /** 通用新增一条饮食记录, 成功后刷新列表 */
    private void addDietEntry(String name, Integer grams, int kcal, String source) {
        try {
            JSONObject body = new JSONObject();
            body.put("entry_date", todayStr());
            body.put("name", name);
            body.put("grams", grams == null ? JSONObject.NULL : grams);
            body.put("calories_kcal", kcal);
            body.put("source", source);
            api().dietAdd(body, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    toast("已记录：" + name);
                    loadDiet();
                }
                @Override public void onError(String message) {
                    toast(message == null || message.isEmpty() ? "记录失败" : message);
                }
            });
        } catch (Exception ex) {
            toast("记录失败");
        }
    }

    /* ----- 选择食物: 列表对话框 → 份量对话框 ----- */
    private void showFoodPicker() {
        Context c = ctx();
        LinearLayout list = Ui.column(c);
        list.setBackgroundColor(Ui.CARD);
        int p = Ui.dp(c, 8);
        list.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(c);
        sv.addView(list);
        final AlertDialog[] holder = new AlertDialog[1];
        for (final Foods.Food food : Foods.ALL) {
            LinearLayout rowLayout = Ui.row(c);
            rowLayout.setBackground(roundStroke(Ui.SOFT, 10, Ui.BORDER));
            int hp = Ui.dp(c, 12), vp = Ui.dp(c, 10);
            rowLayout.setPadding(hp, vp, hp, vp);
            rowLayout.addView(Ui.text(c, food.emoji + "  " + food.name, 14.5f, Ui.INK, true), Ui.weight1());
            rowLayout.addView(Ui.text(c, food.kcalPer100g + " 千卡/100g", 12, Ui.SLATE, false));
            rowLayout.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                showPortionDialog(food);
            });
            list.addView(rowLayout, Ui.matchWrap(6, c));
        }
        holder[0] = new AlertDialog.Builder(c)
                .setTitle("选择食物")
                .setView(sv)
                .setNegativeButton("取消", null)
                .show();
    }

    /** 克数步进小部件: 一行 [−50][−10] 数值 [+10][+50], 变化时回调 */
    private LinearLayout gramsStepper(final int[] grams, final Runnable onChange) {
        Context c = ctx();
        LinearLayout rowLayout = Ui.row(c);
        rowLayout.setGravity(Gravity.CENTER);
        final TextView value = Ui.text(c, "", 18, Ui.INK, true);
        value.setGravity(Gravity.CENTER);
        int[] steps = {-50, -10, 10, 50};
        for (final int step : steps) {
            TextView b = Ui.text(c, (step > 0 ? "+" : "−") + Math.abs(step), 13, Ui.PRIMARY_DARK, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(roundStroke(Ui.GREEN_SOFT, 999, Ui.BORDER));
            int hp = Ui.dp(c, 10), vp = Ui.dp(c, 6);
            b.setPadding(hp, vp, hp, vp);
            b.setOnClickListener(v -> {
                grams[0] = Math.max(10, Math.min(3000, grams[0] + step));
                value.setText(grams[0] + " g");
                if (onChange != null) onChange.run();
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = Ui.dp(c, 4);
            blp.rightMargin = Ui.dp(c, 4);
            if (step == 10) {
                // 数值显示在 -10 与 +10 之间
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Ui.dp(c, 76),
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLayout.addView(value, vlp);
            }
            rowLayout.addView(b, blp);
        }
        value.setText(grams[0] + " g");
        return rowLayout;
    }

    private void showPortionDialog(final Foods.Food food) {
        Context c = ctx();
        final int[] grams = {food.defaultGrams};
        LinearLayout box = Ui.column(c);
        box.setBackgroundColor(Ui.CARD);
        int p = Ui.dp(c, 16);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(c, food.emoji + " " + food.name + " · " + food.kcalPer100g + " 千卡/100g",
                13, Ui.SLATE, false));
        final TextView kcalPreview = Ui.text(c, "", 20, Ui.CAL_TEXT, true);
        kcalPreview.setGravity(Gravity.CENTER);
        Runnable refresh = () -> kcalPreview.setText("≈ " + Foods.kcalFor(food, grams[0]) + " 千卡");
        box.addView(gramsStepper(grams, refresh), Ui.matchWrap(12, c));
        box.addView(kcalPreview, Ui.matchWrap(10, c));
        refresh.run();
        new AlertDialog.Builder(c)
                .setTitle("这一份有多重？")
                .setView(box)
                .setPositiveButton("记入今日", (d, w) ->
                        addDietEntry(food.name, grams[0], Foods.kcalFor(food, grams[0]), "manual"))
                .setNegativeButton("取消", null)
                .show();
    }

    /* ----- 拍照识别: 选图/拍照 → 压缩 Base64 → AI 识别 → 确认入账 ----- */
    private void showPhotoSource() {
        new AlertDialog.Builder(ctx())
                .setTitle("拍照识别热量")
                .setItems(new String[]{"📷 拍照", "🖼️ 从相册选"}, (d, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        activity().startActivityForResult(intent, REQ_CAMERA);
                    } else {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("image/*");
                        activity().startActivityForResult(
                                Intent.createChooser(intent, "选择一张食物照片"), REQ_GALLERY);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        if (requestCode == REQ_GALLERY && data.getData() != null) {
            Bitmap bmp = decodeUriScaled(data.getData(), 1024);
            if (bmp == null) { toast("图片读取失败，换一张试试"); return; }
            recognizeFoodPhoto(bmp);
        } else if (requestCode == REQ_CAMERA) {
            Object extra = data.getExtras() != null ? data.getExtras().get("data") : null;
            if (extra instanceof Bitmap) {
                Bitmap bmp = (Bitmap) extra;
                // 缩略图偏小时放大到最长边 512, 提高识别可用性
                int maxSide = Math.max(bmp.getWidth(), bmp.getHeight());
                if (maxSide > 0 && maxSide < 512) {
                    double scale = 512.0 / maxSide;
                    bmp = Bitmap.createScaledBitmap(bmp,
                            (int) (bmp.getWidth() * scale), (int) (bmp.getHeight() * scale), true);
                }
                recognizeFoodPhoto(bmp);
            } else {
                toast("没有取得照片，再试一次");
            }
        }
    }

    /** 相册图解码: 两段采样避免大图爆内存, 再缩放到最长边 ≤ maxSide */
    private Bitmap decodeUriScaled(Uri uri, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream is = ctx().getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, bounds);
            if (is != null) is.close();
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > maxSide * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            is = ctx().getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            return bmp == null ? null : scaleToMax(bmp, maxSide);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap scaleToMax(Bitmap bmp, int maxSide) {
        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longest <= maxSide || longest == 0) return bmp;
        double scale = (double) maxSide / longest;
        return Bitmap.createScaledBitmap(bmp,
                (int) (bmp.getWidth() * scale), (int) (bmp.getHeight() * scale), true);
    }

    private void recognizeFoodPhoto(Bitmap bmp) {
        Bitmap scaled = scaleToMax(bmp, 1024);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos);
        String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        Context c = ctx();
        LinearLayout box = Ui.column(c);
        box.setBackgroundColor(Ui.CARD);
        int p = Ui.dp(c, 20);
        box.setPadding(p, p, p, p);
        TextView t = Ui.text(c, "🤖 AI 正在识别食物与热量…", 14.5f, Ui.INK, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        photoProgressDialog = new AlertDialog.Builder(c)
                .setView(box)
                .setCancelable(false)
                .show();

        api().agentFoodPhoto(b64, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                dismissPhotoProgress();
                showPhotoConfirm(data);
            }
            @Override public void onError(String message) {
                dismissPhotoProgress();
                // 模型未开通/网络异常等一律友好兜底, 不展示原始错误
                toast("拍照识别暂不可用，可先用『选择食物』记录");
            }
        });
    }

    private void dismissPhotoProgress() {
        if (photoProgressDialog != null) {
            photoProgressDialog.dismiss();
            photoProgressDialog = null;
        }
    }

    /** 拍照识别结果的一项 (可在确认框内改名/改克数/删除) */
    private static class PhotoItem {
        String name;
        int grams;
        int baseGrams;
        int baseKcal;
        int kcal;
        boolean removed = false;
        EditText nameInput;
    }

    private void showPhotoConfirm(JSONObject data) {
        Context c = ctx();
        JSONArray arr = data.optJSONArray("items");
        final List<PhotoItem> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                PhotoItem it = new PhotoItem();
                it.name = o.optString("name", "");
                it.grams = o.isNull("grams") ? 0 : (int) Math.round(o.optDouble("grams", 0));
                it.baseGrams = it.grams;
                it.kcal = (int) Math.round(o.optDouble("calories_kcal", 0));
                it.baseKcal = it.kcal;
                if (!it.name.isEmpty()) items.add(it);
            }
        }
        if (items.isEmpty()) {
            toast("没有识别出食物，换个角度或更近一点再拍");
            return;
        }

        LinearLayout box = Ui.column(c);
        box.setBackgroundColor(Ui.CARD);
        int p = Ui.dp(c, 14);
        box.setPadding(p, p, p, p);
        String note = data.optString("note", "");
        if (!note.isEmpty()) {
            box.addView(Ui.text(c, "🤖 " + note, 12, Ui.SLATE, false));
        }
        final TextView totalView = Ui.text(c, "", 15, Ui.CAL_TEXT, true);
        final Runnable refreshTotal = () -> {
            int sum = 0;
            for (PhotoItem it : items) if (!it.removed) sum += it.kcal;
            totalView.setText("合计 ≈ " + sum + " 千卡（可点步进微调克数）");
        };

        LinearLayout rowsBox = Ui.column(c);
        for (final PhotoItem it : items) {
            final LinearLayout rowLayout = Ui.column(c);
            rowLayout.setBackground(roundStroke(Ui.SOFT, 10, Ui.BORDER));
            int hp = Ui.dp(c, 10), vp = Ui.dp(c, 8);
            rowLayout.setPadding(hp, vp, hp, vp);
            LinearLayout nameRow = Ui.row(c);
            EditText nameInput = Ui.input(c, "食物名", InputType.TYPE_CLASS_TEXT);
            nameInput.setText(it.name);
            nameInput.setTextSize(14);
            it.nameInput = nameInput;
            nameRow.addView(nameInput, Ui.weight1());
            TextView del = Ui.text(c, " ✕ ", 15, Ui.MUTED, true);
            del.setOnClickListener(v -> {
                it.removed = true;
                rowLayout.setVisibility(View.GONE);
                refreshTotal.run();
            });
            nameRow.addView(del);
            rowLayout.addView(nameRow);
            final int[] gramsHolder = {Math.max(10, it.grams > 0 ? it.grams : 100)};
            it.grams = gramsHolder[0];
            final TextView kcalView = Ui.text(c, "", 13, Ui.CAL_TEXT, true);
            kcalView.setGravity(Gravity.CENTER);
            Runnable refreshKcal = () -> {
                it.kcal = Foods.scaleKcal(it.baseKcal, it.baseGrams, gramsHolder[0]);
                kcalView.setText("≈ " + it.kcal + " 千卡");
                refreshTotal.run();
            };
            rowLayout.addView(gramsStepper(gramsHolder, refreshKcal), Ui.matchWrap(6, c));
            rowLayout.addView(kcalView, Ui.matchWrap(4, c));
            refreshKcal.run();
            rowsBox.addView(rowLayout, Ui.matchWrap(8, c));
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(rowsBox);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 320));
        box.addView(sv, svLp);
        box.addView(totalView, Ui.matchWrap(10, c));
        refreshTotal.run();

        new AlertDialog.Builder(c)
                .setTitle("确认识别结果")
                .setView(box)
                .setPositiveButton("全部记入今日", (d, w) -> savePhotoItems(items))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把确认后的识别结果逐条入账 (source=photo), 全部完成后刷新列表 */
    private void savePhotoItems(List<PhotoItem> items) {
        final List<PhotoItem> pending = new ArrayList<>();
        for (PhotoItem it : items) {
            if (it.removed) continue;
            String name = it.nameInput != null
                    ? it.nameInput.getText().toString().trim() : it.name;
            if (name.isEmpty()) continue;
            PhotoItem copy = new PhotoItem();
            copy.name = name;
            copy.grams = it.grams;
            copy.kcal = it.kcal;
            pending.add(copy);
        }
        if (pending.isEmpty()) { toast("没有要记录的食物"); return; }
        final int[] left = {pending.size()};
        final int[] okCount = {0};
        for (final PhotoItem it : pending) {
            try {
                JSONObject body = new JSONObject();
                body.put("entry_date", todayStr());
                body.put("name", it.name);
                body.put("grams", it.grams > 0 ? it.grams : JSONObject.NULL);
                body.put("calories_kcal", it.kcal);
                body.put("source", "photo");
                api().dietAdd(body, new ApiClient.Callback() {
                    @Override public void onOk(JSONObject data, int status) {
                        okCount[0]++;
                        if (--left[0] == 0) finishPhotoSave(okCount[0], pending.size());
                    }
                    @Override public void onError(String message) {
                        if (--left[0] == 0) finishPhotoSave(okCount[0], pending.size());
                    }
                });
            } catch (Exception e) {
                if (--left[0] == 0) finishPhotoSave(okCount[0], pending.size());
            }
        }
    }

    private void finishPhotoSave(int ok, int total) {
        if (ok > 0) {
            toast("已记入 " + ok + " 项饮食");
            loadDiet();
        } else {
            toast("记录失败，请稍后再试");
        }
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
            w.setBackground(Ui.roundRect(Ui.GREEN_SOFT, 999, c));
            int hp = Ui.dp(c, 10), vp = Ui.dp(c, 5);
            w.setPadding(hp, vp, hp, vp);
            top.addView(w);
        }
        card.addView(top);

        // 小标签 (按指标专属色: 步数绿/消耗橙/睡眠紫/运动蓝/饮水蓝/腰围灰)
        List<Object[]> tags = new ArrayList<>();
        if (!r.isNull("steps")) tags.add(new Object[]{"👟 步数", fmtNum(r, "steps") + " 步", Ui.PRIMARY, Ui.GREEN_SOFT});
        if (!r.isNull("calories")) tags.add(new Object[]{"🔥 消耗", fmtNum(r, "calories") + " kcal", Ui.CAL, Ui.CAL_SOFT});
        if (!r.isNull("sleep_hours")) tags.add(new Object[]{"🌙 睡眠", fmtNum(r, "sleep_hours") + " h", Ui.SLEEP, Ui.SLEEP_SOFT});
        if (!r.isNull("workout_minutes")) tags.add(new Object[]{"⏱️ 运动", fmtNum(r, "workout_minutes") + " min", Ui.MOVE, Ui.MOVE_SOFT});
        if (!r.isNull("waist_cm")) tags.add(new Object[]{"腰围", fmtNum(r, "waist_cm") + " cm", Ui.SLATE, Ui.SOFT});
        if (!r.isNull("water_ml")) tags.add(new Object[]{"饮水", fmtNum(r, "water_ml") + " ml", Ui.MOVE, Ui.MOVE_SOFT});
        if (!tags.isEmpty()) {
            GridLayout grid = new GridLayout(c);
            grid.setColumnCount(2);
            for (Object[] tag : tags) {
                TextView t = Ui.text(c, tag[0] + "  " + tag[1], 12, (Integer) tag[2], true);
                t.setBackground(Ui.roundRect((Integer) tag[3], 8, c));
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
            TextView d = Ui.text(c, "🥗 " + diet, 12.5f, Ui.PRIMARY_DARK, false);
            d.setBackground(roundStroke(Ui.GREEN_SOFT, 10, Ui.BORDER));
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
