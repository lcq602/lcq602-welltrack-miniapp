package art.qelora.welltrack.pages;

import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import art.qelora.welltrack.R;
import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.health.Sports;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 运动页 v2 (视觉重构, 对应小程序 pages/exercise, 以覆盖层打开):
 * 返回栏 → 瑜伽横幅主视觉 → 录入卡 (日期/类型 2×2 瓷砖/时长/备注/整宽保存) →
 * 历史列表 (彩色圆块 emoji + 时长蓝 pill)。
 * 数据不变: GET /api/v1/exercises (数组, requestText 解析); POST /api/v1/exercises。
 * v2.10 运动库: 类型改为 Sports 运动库 (15 项带 MET 值, emoji 瓷砖),
 * 时长 + 体重自动估算消耗千卡并在保存时写入备注, 不新增接口与字段;
 * 旧的笼统类型 (如「有氧运动」) 在历史中仍按旧图标口径显示。
 */
public class ExercisePage extends BasePage {

    private LinearLayout body;

    private int kindIndex = 0;
    private String duration = "";
    private String note = "";
    private String exerciseDate = todayString();
    private boolean saving = false;

    /* v2.10 运动库估算: 体重默认 60 kg, 后台拉到最近体重后自动替换 (用户改过则不覆盖) */
    private double weightKg = 60;
    private boolean weightEdited = false;
    private boolean weightLoaded = false;
    private TextView estimateView;

    private final List<JSONObject> items = new ArrayList<>();
    private boolean loading = true;
    private boolean hasLoaded = false;

    public ExercisePage(Context ctx) { super(ctx); }

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
    }

    /* ---------------- 数据 ---------------- */

    private void load() {
        loading = !hasLoaded;
        if (!hasLoaded) render();
        api().requestText("/api/v1/exercises", new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                items.clear();
                try {
                    JSONArray arr = new JSONArray(text);
                    for (int i = 0; i < arr.length(); i++) items.add(arr.optJSONObject(i));
                } catch (Exception ignored) {}
                loading = false;
                hasLoaded = true;
                render();
            }

            @Override public void onError(String message) {
                loading = false;
                hasLoaded = true;
                toast((message == null || message.isEmpty()) ? "加载失败" : message);
                render();
            }
        });
        loadLatestWeight();
    }

    /** v2.10: 从记录列表取最近一次非空体重, 作为 MET 估算的体重 (双后端记录列表均有 weight 字段) */
    private void loadLatestWeight() {
        if (weightLoaded) return;
        api().requestText("/api/v1/records?limit=60", new ApiClient.RawCallback() {
            @Override public void onOk(String text, int status) {
                weightLoaded = true;
                try {
                    JSONArray arr = new JSONArray(text == null ? "[]" : text);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.optJSONObject(i);
                        if (r == null || r.isNull("weight")) continue;
                        double w = r.optDouble("weight", 0);
                        if (w >= 30 && w <= 200 && !weightEdited) {
                            weightKg = w;
                            render();
                        }
                        break;
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onError(String message) {}
        });
    }

    /** 当前选择与输入下的估算消耗, 非法输入返回 -1 */
    private int estimatedKcal() {
        Sports.Sport sport = Sports.ALL.get(kindIndex);
        int minutes;
        try { minutes = (int) Double.parseDouble(duration.trim()); }
        catch (Exception e) { return -1; }
        return Sports.kcalFor(sport, weightKg, minutes);
    }

    private void updateEstimate() {
        if (estimateView == null) return;
        Sports.Sport sport = Sports.ALL.get(kindIndex);
        int kcal = estimatedKcal();
        if (kcal < 0) {
            estimateView.setText("输入 1–1440 分钟后, 按 " + sport.name + " (MET "
                    + fmtMet(sport.met) + ") 自动估算消耗");
        } else {
            estimateView.setText("约消耗 " + kcal + " 千卡 · " + sport.name + " MET "
                    + fmtMet(sport.met) + " × 体重 " + fmtWeight(weightKg)
                    + " kg × 时长 · 仅供参考, 保存时写入备注");
        }
    }

    private static String fmtMet(double met) {
        if (met == Math.floor(met)) return String.valueOf((int) met);
        return String.valueOf(met);
    }

    private static String fmtWeight(double w) {
        if (w == Math.floor(w)) return String.valueOf((int) w);
        return String.valueOf(w);
    }

    private void save() {
        double parsed;
        try { parsed = Double.parseDouble(duration.trim()); }
        catch (Exception e) { toast("时长请输入 1–1440 分钟"); return; }
        if (parsed != Math.floor(parsed) || parsed < 1 || parsed > 1440) {
            toast("时长请输入 1–1440 分钟");
            return;
        }
        if (saving) return;
        saving = true;
        render();
        try {
            JSONObject payload = new JSONObject();
            Sports.Sport sport = Sports.ALL.get(kindIndex);
            int kcal = Sports.kcalFor(sport, weightKg, (int) parsed);
            String noteToSave = note == null ? "" : note.trim();
            if (kcal >= 0) {
                String est = "约消耗 " + kcal + " 千卡 (MET " + fmtMet(sport.met)
                        + ", 按 " + fmtWeight(weightKg) + " kg 估算)";
                noteToSave = noteToSave.isEmpty() ? est : noteToSave + " · " + est;
            }
            payload.put("exercise_date", exerciseDate);
            payload.put("kind", sport.name);
            payload.put("duration_minutes", (int) parsed);
            payload.put("note", noteToSave);
            api().request("/api/v1/exercises", "POST", payload, new ApiClient.Callback() {
                @Override public void onOk(JSONObject res, int status) {
                    saving = false;
                    toast("活动已记录");
                    activity().closeOverlay();
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

    /* ---------------- 渲染 ---------------- */

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        renderBackBar();
        renderHead();
        renderBanner();
        renderForm();
        renderHistory();
    }

    private void renderBackBar() {
        LinearLayout bar = Ui.row(ctx());
        TextView back = Ui.text(ctx(), "‹ 返回", 15, Ui.PRIMARY, true);
        back.setPadding(0, Ui.dp(ctx(), 2), Ui.dp(ctx(), 12), Ui.dp(ctx(), 2));
        back.setOnClickListener(v -> activity().closeOverlay());
        bar.addView(back);
        body.addView(bar);
    }

    private void renderHead() {
        LinearLayout head = Ui.row(ctx());
        LinearLayout left = Ui.column(ctx());
        LinearLayout eyebrow = Ui.row(ctx());
        android.view.View dot = new android.view.View(ctx());
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(Ui.PRIMARY);
        dot.setBackground(d);
        eyebrow.addView(dot, new LinearLayout.LayoutParams(Ui.dp(ctx(), 7), Ui.dp(ctx(), 7)));
        eyebrow.addView(Ui.text(ctx(), "  MOVEMENT, YOUR WAY", 11, Ui.PRIMARY, true));
        left.addView(eyebrow);
        left.addView(Ui.title(ctx(), "运动打卡"), Ui.matchWrap(6, ctx()));
        head.addView(left, Ui.weight1());
        head.addView(pill("活力充沛", Ui.MOVE_SOFT, Ui.MOVE));
        body.addView(head, Ui.matchWrap(8, ctx()));
    }

    private void renderBanner() {
        body.addView(Ui.bannerImage(ctx(), R.drawable.img_plan, 130, 24), Ui.matchWrap(12, ctx()));
    }

    private void renderForm() {
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.text(ctx(), "NEW ACTIVITY", 11, Ui.PRIMARY, true));
        card.addView(Ui.sectionTitle(ctx(), "记录一次活动"), Ui.matchWrap(6, ctx()));
        card.addView(Ui.text(ctx(), "按实际完成情况记录，不需要达到固定强度", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));

        // 日期
        card.addView(fieldLabel("活动日期"), Ui.matchWrap(14, ctx()));
        TextView dateTv = Ui.text(ctx(), exerciseDate, 15, Ui.INK, false);
        dateTv.setBackground(Ui.roundRect(Ui.SOFT, 10, ctx()));
        int dh = Ui.dp(ctx(), 12), dv = Ui.dp(ctx(), 11);
        dateTv.setPadding(dh, dv, dh, dv);
        dateTv.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            try {
                c.set(Calendar.YEAR, Integer.parseInt(exerciseDate.substring(0, 4)));
                c.set(Calendar.MONTH, Integer.parseInt(exerciseDate.substring(5, 7)) - 1);
                c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(exerciseDate.substring(8, 10)));
            } catch (Exception ignored) {}
            new DatePickerDialog(ctx(), (dp, y, m, dd) -> {
                exerciseDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, dd);
                render();
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });
        LinearLayout dateRow = Ui.row(ctx());
        dateRow.addView(dateTv, Ui.weight1());
        TextView cal = Ui.text(ctx(), "📅", 15, Ui.INK, false);
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        calLp.leftMargin = Ui.dp(ctx(), 8);
        dateRow.addView(cal, calLp);
        card.addView(dateRow, Ui.matchWrap(6, ctx()));

        // 类型: 运动库瓷砖选择 (emoji + 名称 + MET, 选中绿底白字)
        card.addView(fieldLabel("运动项目 · 带 MET 值, 选后自动估算消耗"), Ui.matchWrap(12, ctx()));
        GridLayout kindGrid = new GridLayout(ctx());
        kindGrid.setColumnCount(2);
        for (int i = 0; i < Sports.ALL.size(); i++) {
            final int idx = i;
            boolean active = idx == kindIndex;
            Sports.Sport sport = Sports.ALL.get(i);
            TextView tile = Ui.text(ctx(), sport.emoji + " " + sport.name
                    + " · MET " + fmtMet(sport.met), 13, active ? Color.WHITE : Ui.SLATE, true);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Ui.roundRect(active ? Ui.PRIMARY : Ui.SOFT, 12, ctx()));
            int tv = Ui.dp(ctx(), 13);
            tile.setPadding(0, tv, 0, tv);
            tile.setOnClickListener(v -> { kindIndex = idx; render(); });
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = 0;
            glp.columnSpec = GridLayout.spec(i % 2, 1f);
            glp.setMargins(Ui.dp(ctx(), 3), Ui.dp(ctx(), 3), Ui.dp(ctx(), 3), Ui.dp(ctx(), 3));
            kindGrid.addView(tile, glp);
        }
        card.addView(kindGrid, Ui.matchWrap(6, ctx()));

        // 时长
        LinearLayout tile = Ui.column(ctx());
        tile.setBackground(Ui.roundRect(Ui.SOFT, 12, ctx()));
        int tp = Ui.dp(ctx(), 12);
        tile.setPadding(tp, tp, tp, tp);
        LinearLayout tHead = Ui.row(ctx());
        TextView tl = Ui.text(ctx(), "活动时长", 13, Ui.INK, true);
        tHead.addView(tl, Ui.weight1());
        tHead.addView(Ui.text(ctx(), "分钟 (1 ~ 1440)", 11, Ui.MUTED, false));
        tile.addView(tHead);
        LinearLayout tRow = Ui.row(ctx());
        EditText dur = Ui.input(ctx(), "30", InputType.TYPE_CLASS_NUMBER);
        dur.setBackground(null);
        dur.setText(duration);
        dur.setTextSize(18);
        dur.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { duration = s.toString(); }
            @Override public void afterTextChanged(Editable s) { updateEstimate(); }
        });
        tRow.addView(dur, Ui.weight1());
        tRow.addView(Ui.text(ctx(), "分钟", 13, Ui.MUTED, false));
        tile.addView(tRow, Ui.matchWrap(4, ctx()));
        card.addView(tile, Ui.matchWrap(12, ctx()));

        // 估算体重 + 消耗预览 (体重默认取最近记录, 未填过按 60 kg 估算并如实标注)
        card.addView(fieldLabel("估算体重 · 用于按 MET 算消耗, 仅影响本次预览与备注"), Ui.matchWrap(12, ctx()));
        LinearLayout wRow = Ui.row(ctx());
        EditText weightInput = Ui.input(ctx(), "60", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weightInput.setText(fmtWeight(weightKg));
        weightInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                weightEdited = true;
                try {
                    double w = Double.parseDouble(s.toString().trim());
                    if (w >= 30 && w <= 200) weightKg = w;
                } catch (Exception ignored) {}
                updateEstimate();
            }
        });
        wRow.addView(weightInput, Ui.weight1());
        TextView kgUnit = Ui.text(ctx(), "kg", 13, Ui.MUTED, false);
        LinearLayout.LayoutParams kgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kgLp.leftMargin = Ui.dp(ctx(), 8);
        wRow.addView(kgUnit, kgLp);
        card.addView(wRow, Ui.matchWrap(6, ctx()));
        estimateView = Ui.text(ctx(), "", 13, Ui.CAL_TEXT, true);
        updateEstimate();
        card.addView(estimateView, Ui.matchWrap(6, ctx()));

        // 备注
        card.addView(fieldLabel("备注 · 选填"), Ui.matchWrap(12, ctx()));
        EditText noteInput = Ui.input(ctx(), "例如：公园慢跑 4 公里，呼吸均匀体感轻松", InputType.TYPE_CLASS_TEXT);
        noteInput.setText(note);
        noteInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { note = s.toString(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        card.addView(noteInput, Ui.matchWrap(6, ctx()));

        android.widget.Button save = Ui.primaryButton(ctx(), saving ? "正在保存…" : "记下这次活动");
        save.setEnabled(!saving);
        save.setOnClickListener(v -> save());
        card.addView(save, Ui.matchWrap(16, ctx()));
        body.addView(card, Ui.matchWrap(12, ctx()));
    }

    private void renderHistory() {
        LinearLayout head = Ui.row(ctx());
        LinearLayout titles = Ui.column(ctx());
        titles.addView(Ui.sectionTitle(ctx(), "活动历史"));
        titles.addView(Ui.text(ctx(), "共累计 " + items.size() + " 次健康活动", 11, Ui.MUTED, false), Ui.matchWrap(2, ctx()));
        head.addView(titles, Ui.weight1());
        head.addView(pill("运动日志", Ui.MOVE_SOFT, Ui.MOVE));
        body.addView(head, Ui.matchWrap(16, ctx()));

        if (!items.isEmpty()) {
            for (JSONObject item : items) {
                body.addView(historyCard(item), Ui.matchWrap(8, ctx()));
            }
        } else if (loading && !hasLoaded) {
            LinearLayout card = Ui.card(ctx());
            LinearLayout box = Ui.column(ctx());
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            ProgressBar pb = new ProgressBar(ctx());
            box.addView(pb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(Ui.text(ctx(), "正在整理运动记录…", 13, Ui.SLATE, false), Ui.matchWrap(10, ctx()));
            card.addView(box);
            body.addView(card, Ui.matchWrap(10, ctx()));
        } else {
            LinearLayout card = Ui.card(ctx());
            LinearLayout box = Ui.column(ctx());
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView icon = Ui.text(ctx(), "🏃", 30, Ui.INK, false);
            icon.setGravity(Gravity.CENTER);
            box.addView(icon);
            TextView t = Ui.sectionTitle(ctx(), "暂无运动记录");
            t.setGravity(Gravity.CENTER);
            box.addView(t, Ui.matchWrap(8, ctx()));
            TextView m = Ui.text(ctx(), "完成任意一次快走、拉伸或训练，记下一笔吧！", 12, Ui.MUTED, false);
            m.setGravity(Gravity.CENTER);
            box.addView(m, Ui.matchWrap(4, ctx()));
            card.addView(box);
            body.addView(card, Ui.matchWrap(10, ctx()));
        }
    }

    private LinearLayout historyCard(JSONObject item) {
        String kind = item.optString("kind", "");
        Sports.Sport sport = Sports.find(kind);
        String icon = sport != null ? sport.emoji
                : "力量训练".equals(kind) ? "🏋️" : "步行".equals(kind) ? "🚶" : "拉伸".equals(kind) ? "🧘" : "🏃";
        String noteVal = item.optString("note", "");
        String date = item.optString("exercise_date", "");
        String noteText = (noteVal != null && !noteVal.isEmpty()) ? date + " · " + noteVal : date;
        int minutes = item.optInt("duration_minutes", 0);

        LinearLayout card = Ui.card(ctx());
        LinearLayout row = Ui.row(ctx());
        TextView sq = Ui.text(ctx(), icon, 20, Ui.INK, false);
        sq.setGravity(Gravity.CENTER);
        sq.setBackground(Ui.roundRect("力量训练".equals(kind)
                ? Ui.CAL_SOFT : Ui.MOVE_SOFT, 12, ctx()));
        int sz = Ui.dp(ctx(), 44);
        row.addView(sq, new LinearLayout.LayoutParams(sz, sz));
        LinearLayout info = Ui.column(ctx());
        LinearLayout nameRow = Ui.row(ctx());
        nameRow.addView(Ui.text(ctx(), kind, 14, Ui.INK, true));
        TextView tag = pill(minutes + " 分钟", Ui.MOVE_SOFT, Ui.MOVE);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Ui.dp(ctx(), 6);
        nameRow.addView(tag, tl);
        info.addView(nameRow);
        info.addView(Ui.text(ctx(), noteText, 12, Ui.MUTED, false), Ui.matchWrap(3, ctx()));
        LinearLayout.LayoutParams il = Ui.weight1(); il.leftMargin = Ui.dp(ctx(), 10);
        row.addView(info, il);
        LinearLayout right = Ui.column(ctx());
        right.setGravity(Gravity.CENTER);
        TextView dn = Ui.text(ctx(), String.valueOf(minutes), 18, Ui.INK, true);
        dn.setGravity(Gravity.CENTER);
        right.addView(dn);
        TextView du = Ui.text(ctx(), "min", 10, Ui.MUTED, false);
        du.setGravity(Gravity.CENTER);
        right.addView(du);
        row.addView(right);
        card.addView(row);
        return card;
    }

    /* ---------------- 小部件 ---------------- */

    private TextView fieldLabel(String s) {
        return Ui.text(ctx(), s, 12, Ui.SLATE, true);
    }

    private TextView pill(String text, int bg, int fg) {
        TextView t = Ui.text(ctx(), text, 11, fg, true);
        t.setBackground(Ui.roundRect(bg, 20, ctx()));
        int h = Ui.dp(ctx(), 8), v = Ui.dp(ctx(), 3);
        t.setPadding(h, v, h, v);
        return t;
    }

    private static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
