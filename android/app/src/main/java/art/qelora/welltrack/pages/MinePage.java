package art.qelora.welltrack.pages;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import art.qelora.welltrack.R;
import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.health.Insights;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 我的页 (对应小程序 pages/mine): 资料概览 (GET /api/v1/profile)、
 * 小米运动健康连接 (status / connect / verify / sync / connection DELETE)、
 * 以及接口地址显示与修改 (ApiClient 的 welltrack_api 配置)。
 */
public class MinePage extends BasePage {
    private JSONObject profile;
    private boolean loading = true;
    private String goalLabel = "";

    private boolean miEnabled, miReady, miConnected;
    private int miImportCount;
    private JSONObject miLatest = new JSONObject();
    private JSONObject miChallenge;
    private boolean connecting, syncing;
    private boolean codeReady;

    private TextView heroName, heroDesc, heroPill;
    private LinearLayout baselineBox;
    private TextView baselineNote;
    private TextView baselineLink;
    private TextView miPill;
    private LinearLayout miBox;
    private TextView apiCurrent;

    private EditText codeInput;
    private Button verifyBtn;

    private static final Map<String, String> GOAL_LABELS = new HashMap<>();
    static {
        GOAL_LABELS.put("weight_loss", "减重");
        GOAL_LABELS.put("fat_loss", "减脂");
        GOAL_LABELS.put("maintenance", "维持");
        GOAL_LABELS.put("healthy_habits", "健康习惯");
    }

    public MinePage(Context ctx) { super(ctx); }

    @Override
    protected void build() {
        // Header
        LinearLayout head = Ui.row(ctx());
        LinearLayout headLeft = Ui.column(ctx());
        LinearLayout eyebrow = Ui.row(ctx());
        View dot = new View(ctx());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 7), Ui.dp(ctx(), 7));
        dlp.rightMargin = Ui.dp(ctx(), 6);
        dot.setLayoutParams(dlp);
        dot.setBackground(Ui.roundRect(Ui.PRIMARY, 4, ctx()));
        eyebrow.addView(dot);
        eyebrow.addView(Ui.text(ctx(), "YOUR HEALTH SPACE", 10, Ui.MUTED, true));
        headLeft.addView(eyebrow);
        headLeft.addView(Ui.title(ctx(), "我的"), Ui.matchWrap(4, ctx()));
        head.addView(headLeft, Ui.weight1());
        heroPill = pill("健康空间", Ui.GREEN_SOFT, Ui.PRIMARY_DARK);
        heroPill.setVisibility(GONE);
        head.addView(heroPill);
        content.addView(head);

        // Hero 卡片 (v2: 深绿渐变主视觉, 白字)
        LinearLayout hero = Ui.card(ctx());
        hero.setBackground(Ui.heroGradient(ctx()));
        LinearLayout heroRow = Ui.row(ctx());
        FrameLayout avatar = new FrameLayout(ctx());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(ctx(), 54), Ui.dp(ctx(), 54));
        avatar.setLayoutParams(alp);
        TextView avatarBg = Ui.text(ctx(), "W", 22, Color.WHITE, true);
        avatarBg.setGravity(Gravity.CENTER);
        avatarBg.setBackground(Ui.roundRect(Color.parseColor("#33ffffff"), 999, ctx()));
        avatar.addView(avatarBg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView badge = Ui.text(ctx(), "✓", 9, Ui.ON_LIME, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.roundRect(Ui.LIME, 999, ctx()));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(ctx(), 16), Ui.dp(ctx(), 16));
        blp.gravity = Gravity.BOTTOM | Gravity.END;
        avatar.addView(badge, blp);
        heroRow.addView(avatar);
        LinearLayout meta = Ui.column(ctx());
        LinearLayout.LayoutParams mlp = Ui.weight1();
        mlp.leftMargin = Ui.dp(ctx(), 12);
        heroName = Ui.text(ctx(), "正在加载资料…", 15, Color.WHITE, true);
        meta.addView(heroName);
        heroDesc = Ui.text(ctx(), "", 12, Color.parseColor("#c8f2df"), false);
        meta.addView(heroDesc, Ui.matchWrap(4, ctx()));
        heroRow.addView(meta, mlp);
        hero.addView(heroRow);
        content.addView(hero, Ui.matchWrap(14, ctx()));

        // 基础体征卡片
        LinearLayout baseline = Ui.card(ctx());
        baseline.addView(Ui.sectionHeader(ctx(), "基础体征与目标", "调整计划 ›",
                v -> activity().switchTab(1)));
        baselineBox = Ui.column(ctx());
        baseline.addView(baselineBox, Ui.matchWrap(12, ctx()));
        baselineNote = Ui.text(ctx(), "💡 当前体重已通过小米运动健康自动同步；初始计划基线保持稳定。",
                12, Ui.SLATE, false);
        baselineNote.setVisibility(GONE);
        baseline.addView(baselineNote, Ui.matchWrap(10, ctx()));
        baselineLink = Ui.text(ctx(), "查看详细每周计划与阶段目标  ›", 13, Ui.PRIMARY, false);
        baselineLink.setVisibility(GONE);
        baselineLink.setOnClickListener(v -> activity().switchTab(1));
        baseline.addView(baselineLink, Ui.matchWrap(12, ctx()));
        content.addView(baseline, Ui.matchWrap(14, ctx()));

        // 小米运动健康卡片
        LinearLayout mi = Ui.card(ctx());
        LinearLayout mHead = Ui.row(ctx());
        LinearLayout mLeft = Ui.column(ctx());
        mLeft.addView(Ui.text(ctx(), "MI FITNESS · READ ONLY", 10, Ui.MUTED, true));
        mLeft.addView(Ui.sectionTitle(ctx(), "小米运动健康"), Ui.matchWrap(3, ctx()));
        mLeft.addView(Ui.text(ctx(), "只读取本人健康摘要，密码不持久化", 11, Ui.MUTED, false), Ui.matchWrap(3, ctx()));
        mHead.addView(mLeft, Ui.weight1());
        miPill = pill("未连接", Ui.SOFT, Ui.SLATE);
        mHead.addView(miPill);
        mi.addView(mHead);
        miBox = Ui.column(ctx());
        mi.addView(miBox, Ui.matchWrap(12, ctx()));
        TextView miFoot = Ui.text(ctx(),
                "此为只读健康数据同步接口，不会更改任何小米账号数据。请勿在截图或聊天中分享个人凭证。",
                11, Ui.MUTED, false);
        mi.addView(miFoot, Ui.matchWrap(14, ctx()));
        content.addView(mi, Ui.matchWrap(14, ctx()));

        buildApiCard();

        renderAll();
    }

    private void buildApiCard() {
        LinearLayout card = Ui.card(ctx());
        card.addView(Ui.text(ctx(), "SERVICE ENDPOINT", 10, Ui.MUTED, true));
        card.addView(Ui.sectionTitle(ctx(), "接口地址"), Ui.matchWrap(3, ctx()));
        apiCurrent = Ui.text(ctx(), api().getApiBase(), 12, Ui.SLATE, false);
        apiCurrent.setTextIsSelectable(true);
        card.addView(apiCurrent, Ui.matchWrap(8, ctx()));
        final EditText input = Ui.input(ctx(), "如 https://qelora.art", InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(api().getApiBase());
        card.addView(input, Ui.matchWrap(10, ctx()));
        LinearLayout btnRow = Ui.row(ctx());
        Button save = Ui.primaryButton(ctx(), "保存接口地址");
        save.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) value = ApiClient.API_DEFAULT;
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            api().setApiBase(value);
            input.setText(api().getApiBase());
            apiCurrent.setText(api().getApiBase());
            toast("接口地址已保存，将重新建立会话");
            loadProfile();
            loadMiHealth();
        });
        btnRow.addView(save, Ui.weight1());
        Button reset = Ui.ghostButton(ctx(), "恢复默认");
        LinearLayout.LayoutParams rlp = Ui.weight1();
        rlp.leftMargin = Ui.dp(ctx(), 10);
        reset.setOnClickListener(v -> {
            api().setApiBase(ApiClient.API_DEFAULT);
            input.setText(ApiClient.API_DEFAULT);
            apiCurrent.setText(ApiClient.API_DEFAULT);
            toast("已恢复默认接口地址");
            loadProfile();
            loadMiHealth();
        });
        btnRow.addView(reset, rlp);
        card.addView(btnRow, Ui.matchWrap(12, ctx()));
        card.addView(Ui.text(ctx(), "修改后会清除当前会话并用新地址重新连接后端。",
                11, Ui.MUTED, false), Ui.matchWrap(8, ctx()));
        content.addView(card, Ui.matchWrap(14, ctx()));
    }

    @Override
    public void onShow() {
        super.onShow();
        loadProfile();
        loadMiHealth();
    }

    /* ---------------- 数据加载 ---------------- */

    private void loadProfile() {
        loading = profile == null;
        api().request("/api/v1/profile", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                profile = data;
                String goal = data.optString("goal", "");
                goalLabel = GOAL_LABELS.containsKey(goal) ? GOAL_LABELS.get(goal) : "";
                loading = false;
                renderAll();
            }
            @Override public void onError(String message) {
                loading = false;
                // 后端无资料时返回 JSON null, ApiClient 会以转换异常回调, 按未设置资料处理
                if (message != null && message.contains("cannot be converted")) {
                    profile = null;
                } else {
                    toast((message == null || message.isEmpty()) ? "资料加载失败" : message);
                }
                renderAll();
            }
        });
    }

    private void loadMiHealth() {
        api().request("/api/v1/integrations/mihealth/status", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                miEnabled = data.optBoolean("enabled", false);
                miReady = data.optBoolean("ready", false);
                miConnected = data.optBoolean("connected", false);
                miImportCount = data.optInt("import_count", 0);
                miLatest = transformLatest(data.optJSONObject("latest"));
                miChallenge = data.isNull("challenge") ? null : data.optJSONObject("challenge");
                renderAll();
            }
            @Override public void onError(String message) {
                miEnabled = false;
                miReady = false;
                miConnected = false;
                miLatest = new JSONObject();
                miImportCount = 0;
                renderAll();
            }
        });
    }

    /** 与 mine.js 一致: 为每个 latest 指标补 value_label / date_label */
    private JSONObject transformLatest(JSONObject latest) {
        JSONObject out = new JSONObject();
        if (latest == null) return out;
        java.util.Iterator<String> keys = latest.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = latest.optJSONObject(key);
            if (item == null) continue;
            try {
                JSONObject copy = new JSONObject(item.toString());
                Object value = item.isNull("value") ? null : item.opt("value");
                copy.put("value_label", value == null ? "暂无" : fmtValue(value));
                String recordedAt = item.optString("recorded_at", "");
                copy.put("date_label", recordedAt.length() >= 10 ? recordedAt.substring(0, 10) : "暂无记录");
                out.put(key, copy);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static String fmtValue(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return new java.math.BigDecimal(String.valueOf(d)).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    /* ---------------- 渲染 ---------------- */

    private TextView pill(String text, int bg, int fg) {
        TextView t = Ui.text(ctx(), text, 11, fg, true);
        t.setBackground(Ui.roundRect(bg, 999, ctx()));
        t.setPadding(Ui.dp(ctx(), 10), Ui.dp(ctx(), 5), Ui.dp(ctx(), 10), Ui.dp(ctx(), 5));
        return t;
    }

    private void renderAll() {
        if (heroName == null) return;
        renderHero();
        renderBaseline();
        renderMi();
    }

    private void renderHero() {
        heroPill.setVisibility(profile != null ? VISIBLE : GONE);
        if (loading && profile == null) {
            heroName.setText("正在加载资料…");
            heroDesc.setText("");
        } else if (profile != null) {
            heroName.setText((goalLabel == null || goalLabel.isEmpty() ? "" : goalLabel) + "进行中");
            heroDesc.setText("持续记录每一个微小变化，建立自己的节奏");
        } else {
            heroName.setText("欢迎使用轻衡健康");
            heroDesc.setText("先在首页设置起点，开启健康旅程");
        }
    }

    private void renderBaseline() {
        baselineBox.removeAllViews();
        JSONObject miWeight = miLatest.optJSONObject("weight");
        boolean hasMiWeight = miWeight != null && !miWeight.isNull("value");
        if (profile != null) {
            String currentWeight;
            if (hasMiWeight) currentWeight = fmtValue(miWeight.opt("value"));
            else currentWeight = profile.isNull("current_weight") ? "—" : fmtValue(profile.opt("current_weight"));
            LinearLayout row1 = Ui.row(ctx());
            row1.addView(baselineTile("身高",
                    profile.isNull("height_cm") ? "—" : fmtValue(profile.opt("height_cm")), "cm"), Ui.weight1());
            row1.addView(spacerW(10));
            row1.addView(baselineTile("当前体重", currentWeight, "kg"), Ui.weight1());
            baselineBox.addView(row1);
            LinearLayout row2 = Ui.row(ctx());
            row2.addView(baselineTile("目标体重",
                    profile.isNull("target_weight") ? "—" : fmtValue(profile.opt("target_weight")), "kg"), Ui.weight1());
            row2.addView(spacerW(10));
            row2.addView(baselineTile("计划开始",
                    profile.isNull("start_date") ? "—" : profile.optString("start_date"), ""), Ui.weight1());
            baselineBox.addView(row2, Ui.matchWrap(10, ctx()));
            baselineNote.setVisibility(hasMiWeight ? VISIBLE : GONE);
            baselineLink.setVisibility(VISIBLE);
        } else {
            baselineNote.setVisibility(GONE);
            baselineLink.setVisibility(GONE);
            TextView state = Ui.text(ctx(), loading ? "读取基础信息中…" : "完成首次设置后，基础信息会完整显示在此处。",
                    13, Ui.MUTED, false);
            state.setGravity(Gravity.CENTER);
            state.setPadding(0, Ui.dp(ctx(), 16), 0, Ui.dp(ctx(), 16));
            baselineBox.addView(state);
        }
    }

    private View spacerW(int dpVal) {
        View v = new View(ctx());
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx(), dpVal), 1));
        return v;
    }

    private LinearLayout baselineTile(String label, String value, String unit) {
        LinearLayout tile = Ui.column(ctx());
        tile.setBackground(Ui.roundRect(Ui.SOFT, 16, ctx()));
        int p = Ui.dp(ctx(), 12);
        tile.setPadding(p, p, p, p);
        tile.addView(Ui.text(ctx(), label, 11, Ui.MUTED, false));
        LinearLayout valRow = Ui.row(ctx());
        valRow.addView(Ui.text(ctx(), value, 16, Ui.INK, true));
        if (unit != null && !unit.isEmpty()) valRow.addView(Ui.text(ctx(), " " + unit, 11, Ui.MUTED, false));
        tile.addView(valRow, Ui.matchWrap(4, ctx()));
        return tile;
    }

    private void renderMi() {
        miPill.setText(miConnected ? "已连接" : "未连接");
        miPill.setBackground(Ui.roundRect(
                miConnected ? Ui.GREEN_SOFT : Ui.SOFT, 999, ctx()));
        miPill.setTextColor(miConnected ? Ui.PRIMARY_DARK : Ui.SLATE);
        miBox.removeAllViews();
        codeInput = null;
        verifyBtn = null;

        if (miConnected) {
            buildMiConnected();
        } else if (miReady) {
            buildMiForm();
        } else if (miEnabled) {
            miBox.addView(emptyText("连接器已启用，但后端缺少有效的会话密钥，请检查 .env 配置。"));
        } else {
            miBox.addView(emptyText("本地连接器默认关闭。需要在后端配置显式开启后方可连接。"));
        }
    }

    private TextView emptyText(String s) {
        TextView t = Ui.text(ctx(), s, 13, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(ctx(), 12), 0, Ui.dp(ctx(), 12));
        return t;
    }

    /* ---- 已连接 ---- */

    private void buildMiConnected() {
        // key, 标签, 单位, 图标, 主色, 浅底, 达标目标 (<=0 表示不画进度条)
        Object[][] metrics = {
                {"weight", "最近体重", "kg", "⚖️", Ui.PRIMARY, Ui.GREEN_SOFT, -1.0},
                {"sleep_hours", "最近睡眠", "h", "🌙", Ui.SLEEP, Ui.SLEEP_SOFT, Insights.SLEEP_GOAL},
                {"steps", "最近步数", "步", "👟", Ui.PRIMARY, Ui.GREEN_SOFT, (double) Insights.STEP_GOAL},
                {"calories", "能量消耗", "kcal", "🔥", Ui.CAL, Ui.CAL_SOFT, (double) Insights.CAL_GOAL},
                {"workout_minutes", "运动时长", "min", "🏃", Ui.MOVE, Ui.MOVE_SOFT, (double) Insights.WORKOUT_GOAL},
        };
        for (int i = 0; i < metrics.length; i += 2) {
            LinearLayout row = Ui.row(ctx());
            row.setGravity(Gravity.TOP);
            row.addView(miTile(metrics[i]), Ui.weight1());
            row.addView(spacerW(10));
            if (i + 1 < metrics.length) row.addView(miTile(metrics[i + 1]), Ui.weight1());
            else row.addView(new View(ctx()), Ui.weight1());
            miBox.addView(row, i == 0 ? new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : Ui.matchWrap(10, ctx()));
        }
        TextView note = Ui.text(ctx(), "💡 体重自动更新；睡眠、步数和运动摘要可通过下方按钮手动同步。",
                11, Ui.MUTED, false);
        miBox.addView(note, Ui.matchWrap(12, ctx()));
        if (!miReady) {
            TextView warn = Ui.text(ctx(), "后端加密密钥未配置；请检查环境变量后再进行同步。",
                    12, Ui.AMBER_TEXT, false);
            warn.setBackground(Ui.roundRect(Ui.AMBER_SOFT_DARK, 10, ctx()));
            warn.setPadding(Ui.dp(ctx(), 10), Ui.dp(ctx(), 10), Ui.dp(ctx(), 10), Ui.dp(ctx(), 10));
            miBox.addView(warn, Ui.matchWrap(10, ctx()));
        }
        Button syncBtn = Ui.primaryButton(ctx(), syncing ? "同步中…" : "手动同步最近两周摘要");
        syncBtn.setEnabled(!syncing && miReady);
        syncBtn.setAlpha(syncBtn.isEnabled() ? 1f : 0.55f);
        syncBtn.setOnClickListener(v -> syncMiHealth());
        miBox.addView(syncBtn, Ui.matchWrap(12, ctx()));
        Button unbindBtn = new Button(ctx());
        unbindBtn.setText("解除连接");
        unbindBtn.setTextSize(14);
        unbindBtn.setTextColor(Ui.RED);
        unbindBtn.setBackground(Ui.roundRect(Ui.RED_SOFT, 12, ctx()));
        unbindBtn.setAllCaps(false);
        unbindBtn.setEnabled(!syncing);
        unbindBtn.setOnClickListener(v -> unbindMiHealth());
        miBox.addView(unbindBtn, Ui.matchWrap(10, ctx()));
    }

    /** 已连接指标瓷砖 (Ui.metricTile 风格): 数值 + 单位·日期副文案 + 达标进度条 */
    private LinearLayout miTile(Object[] def) {
        JSONObject item = miLatest.optJSONObject((String) def[0]);
        boolean hasValue = item != null && !item.isNull("value");
        String value = hasValue ? item.optString("value_label", "暂无") : "暂无";
        String dateLabel = item == null ? "暂无记录" : item.optString("date_label", "暂无记录");
        String sub = def[2] + " · " + dateLabel;
        double goal = (Double) def[6];
        double ratio = (hasValue && goal > 0) ? item.optDouble("value", 0) / goal : -1;
        return Ui.metricTile(ctx(), (Integer) def[4], (Integer) def[5], (String) def[3],
                (String) def[1], value, sub, ratio);
    }

    /* ---- 未连接表单 ---- */

    private void buildMiForm() {
        // 未连接引导横幅 (手机 + 手环插画)
        miBox.addView(Ui.bannerImage(ctx(), R.drawable.img_connect, 120, 16));
        TextView intro = Ui.text(ctx(), "登录凭证仅发送给本机安全后端用于换取会话；小程序绝不保存密码。",
                12, Ui.MUTED, false);
        miBox.addView(intro, Ui.matchWrap(12, ctx()));

        if (miChallenge != null) {
            boolean email = "Email".equals(miChallenge.optString("method"));
            LinearLayout box = Ui.column(ctx());
            box.setBackground(Ui.roundRect(Ui.BLUE_SOFT, 12, ctx()));
            int p = Ui.dp(ctx(), 14);
            box.setPadding(p, p, p, p);
            box.addView(Ui.text(ctx(), "输入小米" + (email ? "邮箱" : "短信") + "验证码", 14, Ui.INK, true));
            String hint = miChallenge.optString("hint", "");
            box.addView(Ui.text(ctx(), "验证码已发送至 " + (hint.isEmpty() ? "你的绑定手机或邮箱" : hint) + "，10 分钟内有效。",
                    12, Ui.SLATE, false), Ui.matchWrap(6, ctx()));
            box.addView(fieldLabel(email ? "邮箱验证码" : "短信验证码"), Ui.matchWrap(12, ctx()));
            codeInput = Ui.input(ctx(), "输入收到的验证码", InputType.TYPE_CLASS_NUMBER);
            codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
            codeInput.addTextChangedListener(new TextWatcher() {
                private boolean editing;
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    if (editing) return;
                    String digits = e.toString().replaceAll("\\D", "");
                    if (digits.length() > 10) digits = digits.substring(0, 10);
                    if (!digits.equals(e.toString())) {
                        editing = true;
                        codeInput.setText(digits);
                        codeInput.setSelection(digits.length());
                        editing = false;
                    }
                    codeReady = digits.length() >= 4;
                    if (verifyBtn != null) {
                        verifyBtn.setEnabled(!connecting && codeReady);
                        verifyBtn.setAlpha(verifyBtn.isEnabled() ? 1f : 0.55f);
                    }
                }
            });
            box.addView(codeInput, Ui.matchWrap(6, ctx()));
            verifyBtn = Ui.primaryButton(ctx(), connecting ? "正在验证…" : "确认并完成绑定");
            verifyBtn.setEnabled(!connecting && codeReady);
            verifyBtn.setAlpha(verifyBtn.isEnabled() ? 1f : 0.55f);
            verifyBtn.setOnClickListener(v -> verifyMiHealthCode());
            box.addView(verifyBtn, Ui.matchWrap(12, ctx()));
            box.addView(Ui.text(ctx(), "验证码不存储在数据库；还可尝试 "
                    + miChallenge.optInt("attempts_remaining", 0) + " 次。", 11, Ui.MUTED, false),
                    Ui.matchWrap(10, ctx()));
            miBox.addView(box, Ui.matchWrap(12, ctx()));
            return;
        }

        miBox.addView(fieldLabel("小米账号"), Ui.matchWrap(12, ctx()));
        final EditText userInput = Ui.input(ctx(), "手机号、邮箱或小米 ID", InputType.TYPE_CLASS_TEXT);
        miBox.addView(userInput, Ui.matchWrap(6, ctx()));
        miBox.addView(fieldLabel("小米密码"), Ui.matchWrap(12, ctx()));
        final EditText passInput = Ui.input(ctx(), "仅用于本次安全鉴权",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        miBox.addView(passInput, Ui.matchWrap(6, ctx()));
        Button connectBtn = Ui.primaryButton(ctx(), connecting ? "正在连接…" : "连接我的小米账号");
        connectBtn.setEnabled(!connecting);
        connectBtn.setAlpha(connecting ? 0.55f : 1f);
        connectBtn.setOnClickListener(v -> connectMiHealth(userInput, passInput));
        miBox.addView(connectBtn, Ui.matchWrap(14, ctx()));
    }

    private TextView fieldLabel(String s) {
        TextView t = Ui.text(ctx(), s, 12, Ui.SLATE, true);
        return t;
    }

    /* ---------------- 小米操作 (与 mine.js 流程一致) ---------------- */

    private void connectMiHealth(final EditText userInput, final EditText passInput) {
        if (!miReady) { toast("请先在本机后端启用连接器并设置加密密钥"); return; }
        if (connecting) return;
        final String username = userInput.getText().toString().trim();
        final String password = passInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) { toast("请填写小米账号和密码"); return; }
        new AlertDialog.Builder(ctx())
                .setTitle("连接小米运动健康")
                .setMessage("仅用于读取你本人账号的健康摘要。登录信息会由本机后端提交给小米，不会保存在本小程序中。此连接依赖非官方接口。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续连接", (dialog, which) -> {
                    connecting = true;
                    passInput.setText("");
                    renderMi();
                    JSONObject body = new JSONObject();
                    try {
                        body.put("username", username);
                        body.put("password", password);
                    } catch (Exception ignored) {}
                    api().request("/api/v1/integrations/mihealth/connect", "POST", body, new ApiClient.Callback() {
                        @Override public void onOk(JSONObject data, int status) {
                            connecting = false;
                            if (data.optBoolean("requires_verification", false)) {
                                miChallenge = data;
                                codeReady = false;
                                toast("验证码已发送");
                            } else {
                                toast("小米账号已连接");
                                miChallenge = null;
                                loadMiHealth();
                            }
                            renderMi();
                        }
                        @Override public void onError(String message) {
                            connecting = false;
                            toast((message == null || message.isEmpty()) ? "连接失败" : message);
                            renderMi();
                        }
                    });
                })
                .show();
    }

    private void verifyMiHealthCode() {
        if (miChallenge == null || !codeReady || connecting) return;
        final String code = codeInput == null ? "" : codeInput.getText().toString();
        final String challengeId = miChallenge.optString("challenge_id", "");
        connecting = true;
        codeReady = false;
        if (codeInput != null) codeInput.setText("");
        renderMi();
        JSONObject body = new JSONObject();
        try {
            body.put("challenge_id", challengeId);
            body.put("code", code);
        } catch (Exception ignored) {}
        api().request("/api/v1/integrations/mihealth/verify", "POST", body, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                connecting = false;
                toast("小米账号已连接");
                miChallenge = null;
                renderMi();
                loadMiHealth();
            }
            @Override public void onError(String message) {
                connecting = false;
                toast((message == null || message.isEmpty()) ? "验证码验证失败" : message);
                loadMiHealth();
            }
        });
    }

    private void syncMiHealth() {
        if (syncing) return;
        syncing = true;
        renderMi();
        JSONObject body = new JSONObject();
        try { body.put("days", 14); } catch (Exception ignored) {}
        api().request("/api/v1/integrations/mihealth/sync", "POST", body, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                syncing = false;
                toast("新增 " + data.optInt("imported", 0) + " 条健康数据");
                renderMi();
                loadMiHealth();
            }
            @Override public void onError(String message) {
                syncing = false;
                toast((message == null || message.isEmpty()) ? "同步失败" : message);
                renderMi();
            }
        });
    }

    private void unbindMiHealth() {
        new AlertDialog.Builder(ctx())
                .setTitle("解除小米健康连接？")
                .setMessage("将移除本机保存的加密登录会话；已经同步到本机的只读记录会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("解除连接", (dialog, which) ->
                        api().request("/api/v1/integrations/mihealth/connection", "DELETE", null, new ApiClient.Callback() {
                            @Override public void onOk(JSONObject data, int status) {
                                toast("已解除连接");
                                loadMiHealth();
                            }
                            @Override public void onError(String message) {
                                toast((message == null || message.isEmpty()) ? "解绑失败" : message);
                            }
                        }))
                .show();
    }
}
