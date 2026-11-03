package art.qelora.welltrack.pages;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 健康智能体页 v2.1 (覆盖层打开, 与运动页同一机制):
 * 顶部返回栏 (标题/副标题) → 消息流 → 底部输入栏, 不用基类的滚动容器。
 * 智能体服务在 Cloudflare Worker (ApiClient.AGENT_BASE, 与主后端独立会话);
 * 每次提问附带本机从主后端组装的数据上下文 (dashboard + 本周 analytics),
 * 让智能体能按用户的步数/消耗/睡眠/体重数据精准回答。上下文拉取失败不阻塞聊天。
 */
public class AgentPage extends BasePage {
    private static final String[] QUICK_QUESTIONS = {
            "今天消耗达标了吗?",
            "给我今天的运动建议",
            "我的体重趋势怎么样?",
    };
    private static final String WELCOME =
            "你好, 我是你的健康智能体 🌱\n"
                    + "我会结合你小米运动健康的步数、活动消耗、睡眠和体重记录来回答, "
                    + "比如今天消耗有没有达标、接下来该怎么练。直接问我, 或者点下面的快捷问题。";

    private ScrollView msgScroll;
    private LinearLayout msgList;
    private LinearLayout quickBox;
    private EditText input;
    private Button sendBtn;
    private boolean welcomed = false;
    private boolean sending = false;

    /* 已完成轮次 (user/assistant 交替), 发送时取最近 8 条作为 history */
    private final List<JSONObject> history = new ArrayList<>();
    /* 主后端数据组装的上下文字符串; 拉取失败保持空串 */
    private String contextStr = "";
    private JSONObject dashData;
    private JSONObject weekData;
    private boolean dashDone = false;
    private boolean weekDone = false;

    public AgentPage(Context ctx) {
        super(ctx);
        // 聊天页是三段式布局, 隐藏基类的滚动容器
        scroll.setVisibility(View.GONE);
    }

    @Override
    protected void build() {
        LinearLayout root = Ui.column(ctx());
        root.addView(buildTopBar());
        msgScroll = new ScrollView(ctx());
        msgScroll.setFillViewport(true);
        msgList = Ui.column(ctx());
        int pad = Ui.dp(ctx(), 16);
        msgList.setPadding(pad, pad, pad, pad);
        msgScroll.addView(msgList);
        root.addView(msgScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildInputBar());
        addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onShow() {
        super.onShow();
        if (!welcomed) {
            welcomed = true;
            addAgentBubble(WELCOME);
            showQuickQuestions();
            loadContext();
        }
    }

    /* ---------------- 布局 ---------------- */

    private LinearLayout buildTopBar() {
        LinearLayout bar = Ui.column(ctx());
        bar.setBackgroundColor(Ui.CARD);
        LinearLayout row = Ui.row(ctx());
        int h = Ui.dp(ctx(), 14);
        row.setPadding(h, Ui.dp(ctx(), 10), h, Ui.dp(ctx(), 10));
        TextView back = Ui.text(ctx(), "‹ 返回", 15, Ui.LIME, true);
        back.setPadding(0, Ui.dp(ctx(), 2), Ui.dp(ctx(), 12), Ui.dp(ctx(), 2));
        back.setOnClickListener(v -> activity().closeOverlay());
        row.addView(back);
        LinearLayout titles = Ui.column(ctx());
        titles.addView(Ui.text(ctx(), "健康智能体", 17, Ui.INK, true));
        titles.addView(Ui.text(ctx(), "基于你的小米数据 · Cloudflare AI", 11, Ui.MUTED, false),
                Ui.matchWrap(1, ctx()));
        row.addView(titles, Ui.weight1());
        bar.addView(row);
        bar.addView(dividerLine());
        return bar;
    }

    private LinearLayout buildInputBar() {
        LinearLayout bar = Ui.column(ctx());
        bar.setBackgroundColor(Ui.CARD);
        bar.addView(dividerLine());
        LinearLayout row = Ui.row(ctx());
        int p = Ui.dp(ctx(), 10);
        row.setPadding(p, p, p, p);
        input = Ui.input(ctx(), "问问你的健康助手…", InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                trySendFromInput();
                return true;
            }
            return false;
        });
        row.addView(input, Ui.weight1());
        sendBtn = Ui.primaryButton(ctx(), "发送");
        sendBtn.setOnClickListener(v -> trySendFromInput());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(ctx(), 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(ctx(), 8);
        row.addView(sendBtn, slp);
        bar.addView(row);
        return bar;
    }

    private View dividerLine() {
        View line = new View(ctx());
        line.setBackgroundColor(Ui.BORDER);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return line;
    }

    /* ---------------- 消息气泡 ---------------- */

    private void showQuickQuestions() {
        quickBox = Ui.column(ctx());
        quickBox.addView(Ui.text(ctx(), "试试这样问:", 12, Ui.MUTED, false));
        for (final String q : QUICK_QUESTIONS) {
            TextView chipView = Ui.text(ctx(), q, 13, Ui.PRIMARY, false);
            chipView.setBackground(Ui.cardBg(ctx()));
            int hp = Ui.dp(ctx(), 12), vp = Ui.dp(ctx(), 9);
            chipView.setPadding(hp, vp, hp, vp);
            chipView.setOnClickListener(v -> sendMessage(q));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx(), 8);
            quickBox.addView(chipView, lp);
        }
        msgList.addView(quickBox, Ui.matchWrap(10, ctx()));
    }

    private void hideQuickQuestions() {
        if (quickBox != null) {
            msgList.removeView(quickBox);
            quickBox = null;
        }
    }

    private TextView addUserBubble(String text) { return addBubble(text, true); }
    private TextView addAgentBubble(String text) { return addBubble(text, false); }

    private TextView addBubble(String text, boolean mine) {
        LinearLayout row = Ui.row(ctx());
        row.setGravity(mine ? Gravity.END : Gravity.START);
        TextView bubble = Ui.text(ctx(), text, 14, mine ? Ui.ON_LIME : Ui.INK, false);
        bubble.setBackground(mine ? Ui.primaryGradient(ctx()) : Ui.cardBg(ctx()));
        int hp = Ui.dp(ctx(), 12), vp = Ui.dp(ctx(), 9);
        bubble.setPadding(hp, vp, hp, vp);
        bubble.setMaxWidth(Ui.dp(ctx(), 288));
        row.addView(bubble);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx(), 10);
        msgList.addView(row, lp);
        scrollToBottom();
        return bubble;
    }

    private void scrollToBottom() {
        if (msgScroll == null) return;
        msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
    }

    /* ---------------- 发送 ---------------- */

    private void trySendFromInput() {
        if (input.getText() == null) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        sendMessage(text);
    }

    private void sendMessage(final String text) {
        if (sending) return;
        sending = true;
        sendBtn.setEnabled(false);
        hideQuickQuestions();
        addUserBubble(text);
        final TextView thinking = addAgentBubble("智能体正在思考…");
        // history 只带已完成轮次, 最近 8 条
        JSONArray hist = new JSONArray();
        int from = Math.max(0, history.size() - 8);
        for (int i = from; i < history.size(); i++) hist.put(history.get(i));
        api().agentChat(text, hist, contextStr, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                sending = false;
                sendBtn.setEnabled(true);
                String reply = data.optString("reply", "");
                if (reply.isEmpty()) reply = "(智能体这次没有给出回复, 请再问一次)";
                thinking.setText(reply);
                try {
                    JSONObject u = new JSONObject();
                    u.put("role", "user");
                    u.put("content", text);
                    JSONObject a = new JSONObject();
                    a.put("role", "assistant");
                    a.put("content", reply);
                    history.add(u);
                    history.add(a);
                } catch (Exception ignored) {}
                scrollToBottom();
            }

            @Override public void onError(String message) {
                sending = false;
                sendBtn.setEnabled(true);
                String m = (message == null || message.isEmpty()) ? "网络异常" : message;
                thinking.setText("⚠️ 智能体暂时不可用 (" + m + "), 请稍后再试");
                scrollToBottom();
            }
        });
    }

    /* ---------------- 数据上下文 ---------------- */

    /* 静默组装: dashboard 与本周 analytics 都返回后拼字符串, 任一失败不阻塞聊天 */
    private void loadContext() {
        api().request("/api/v1/dashboard", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                dashData = data;
                dashDone = true;
                maybeBuildContext();
            }

            @Override public void onError(String message) {
                dashDone = true;
                maybeBuildContext();
            }
        });
        api().request("/api/v1/analytics?period=week", "GET", null, new ApiClient.Callback() {
            @Override public void onOk(JSONObject data, int status) {
                weekData = data;
                weekDone = true;
                maybeBuildContext();
            }

            @Override public void onError(String message) {
                weekDone = true;
                maybeBuildContext();
            }
        });
    }

    private void maybeBuildContext() {
        if (!dashDone || !weekDone) return;
        List<String> parts = new ArrayList<>();
        if (dashData != null) {
            JSONObject profile = dashData.optJSONObject("profile");
            if (profile != null) {
                StringBuilder sb = new StringBuilder("用户目标: ");
                sb.append(profile.optString("goal", ""));
                sb.append(" | 身高 ").append(fmt(profile.optDouble("height_cm", 0))).append("cm");
                Double cw = num(dashData, "current_weight");
                sb.append(" | 当前体重 ").append(cw != null ? fmt(cw) : "--").append("kg");
                Double tw = num(dashData, "target_weight");
                if (tw == null) tw = num(profile, "target_weight");
                sb.append(" | 目标体重 ").append(tw != null ? fmt(tw) : "--").append("kg");
                sb.append(" | 目标进度 ").append(dashData.optInt("progress", 0)).append("%");
                parts.add(sb.toString());
            }
            JSONObject mi = dashData.optJSONObject("mihealth");
            boolean connected = mi != null && mi.optBoolean("connected", false);
            JSONObject today = mi != null ? mi.optJSONObject("today") : null;
            if (connected && today != null) {
                StringBuilder sb = new StringBuilder("今日小米数据: 步数 ");
                sb.append(today.optInt("steps", 0));
                sb.append(", 活动消耗 ").append(fmt(today.optDouble("calories", 0))).append(" 千卡");
                Double sleep = num(today, "sleep_hours");
                sb.append(", 睡眠 ").append(sleep != null ? fmt(sleep) : "--").append(" 小时");
                sb.append(", 运动 ").append(fmt(today.optDouble("workout_minutes", 0))).append(" 分钟 (已连接)");
                parts.add(sb.toString());
            } else {
                parts.add("今日小米数据: 未连接小米运动健康");
            }
        }
        if (weekData != null) {
            StringBuilder sb = new StringBuilder("本周: 累计步数 ");
            sb.append(fmt(weekData.optDouble("total_steps", 0)));
            sb.append(" (日均 ").append(fmt(weekData.optDouble("average_steps", 0))).append(")");
            sb.append(", 累计消耗 ").append(fmt(weekData.optDouble("total_calories", 0))).append(" 千卡");
            Double avgSleep = num(weekData, "average_sleep");
            sb.append(", 平均睡眠 ").append(avgSleep != null ? fmt(avgSleep) : "--").append(" 小时");
            sb.append(", 运动 ").append(weekData.optInt("exercise_count", 0)).append(" 次");
            parts.add(sb.toString());
        }
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) all.append(" | ");
            all.append(parts.get(i));
        }
        contextStr = all.toString();
    }

    /* ---------------- 工具 ---------------- */

    private static Double num(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        double d = o.optDouble(key, Double.NaN);
        return Double.isNaN(d) ? null : d;
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String fmt(Double d) {
        return d == null ? "--" : fmt(d.doubleValue());
    }
}
