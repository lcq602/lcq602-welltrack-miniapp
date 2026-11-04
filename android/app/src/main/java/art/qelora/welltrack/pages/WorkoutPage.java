package art.qelora.welltrack.pages;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import art.qelora.welltrack.api.ApiClient;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 跟练页 v2.4 (R2, Keep 式跟练体验, 以覆盖层打开):
 * 按今日 AI 计划的动作逐个进行: 当前动作卡 + 计时倒计时 (有分钟数的动作) /
 * 组间休息 30 秒倒计时 (可跳过) / 全部动作清单勾选状态;
 * 全部完成进入结算页, 自动记一条运动记录 (POST /api/v1/exercises, 失败可手动重试),
 * 中途退出不保存。
 */
public class WorkoutPage extends BasePage {
    private static final int REST_SECONDS = 30;

    private enum Phase { ACTION, REST, DONE }

    private final String focus;
    private final List<JSONObject> actions = new ArrayList<>();
    private final boolean[] doneFlags;

    private LinearLayout body;
    private Phase phase = Phase.ACTION;
    private int current = 0;            // 当前动作下标
    private int remainingSeconds = 0;   // 当前动作或休息的剩余秒数
    private boolean timerRunning = false;
    private int elapsedSeconds = 0;     // 本次跟练累计计时秒数 (仅动作计时段累计)
    private boolean saved = false;      // 结算记录是否已提交/成功
    private boolean saving = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!timerRunning) return;
            if (remainingSeconds > 0) {
                remainingSeconds--;
                if (phase == Phase.ACTION) elapsedSeconds++;
                render();
                if (remainingSeconds > 0) {
                    handler.postDelayed(this, 1000);
                } else {
                    timerRunning = false;
                    onTimerFinished();
                }
            }
        }
    };

    public WorkoutPage(Context ctx, JSONObject day) {
        super(ctx);
        focus = day != null ? day.optString("focus", "今日训练") : "今日训练";
        JSONArray arr = day != null ? day.optJSONArray("actions") : null;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a != null) actions.add(a);
            }
        }
        doneFlags = new boolean[actions.size()];
    }

    @Override
    protected void build() {
        body = Ui.column(ctx());
        content.addView(body);
        resetActionTimer();
        render();
    }

    @Override
    public void onHide() {
        super.onHide();
        stopTimer();
    }

    /* ---------------- 计时 ---------------- */

    private void resetActionTimer() {
        JSONObject a = currentAction();
        int minutes = a != null ? a.optInt("minutes", 0) : 0;
        remainingSeconds = minutes > 0 ? minutes * 60 : 0;
        timerRunning = false;
    }

    private void startTimer() {
        if (remainingSeconds <= 0 || timerRunning) return;
        timerRunning = true;
        handler.postDelayed(ticker, 1000);
    }

    private void stopTimer() {
        timerRunning = false;
        handler.removeCallbacks(ticker);
    }

    private void onTimerFinished() {
        if (phase == Phase.REST) {
            advanceToNext();
        } else {
            // 动作计时到: 自动算完成, 进入休息 (或直接结算)
            completeCurrent();
        }
    }

    private JSONObject currentAction() {
        return current >= 0 && current < actions.size() ? actions.get(current) : null;
    }

    private void completeCurrent() {
        stopTimer();
        if (current >= 0 && current < actions.size()) doneFlags[current] = true;
        if (current + 1 >= actions.size()) {
            phase = Phase.DONE;
            render();
            autoSave();
        } else {
            phase = Phase.REST;
            remainingSeconds = REST_SECONDS;
            timerRunning = false;
            render();
        }
    }

    private void advanceToNext() {
        stopTimer();
        current++;
        phase = Phase.ACTION;
        resetActionTimer();
        render();
    }

    /* ---------------- 结算保存 ---------------- */

    private int totalPlannedMinutes() {
        int total = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (doneFlags[i]) total += Math.max(0, actions.get(i).optInt("minutes", 0));
        }
        return total;
    }

    private int saveMinutes() {
        int planned = totalPlannedMinutes();
        if (planned > 0) return Math.min(1440, planned);
        int elapsed = Math.max(1, Math.round(elapsedSeconds / 60f));
        return Math.min(1440, elapsed);
    }

    private void autoSave() {
        if (saved || saving) return;
        saving = true;
        render();
        try {
            JSONObject payload = new JSONObject();
            payload.put("exercise_date", todayString());
            String kind = focus != null && !focus.isEmpty() && focus.length() <= 40 ? focus : "跟练";
            payload.put("kind", kind);
            payload.put("duration_minutes", saveMinutes());
            StringBuilder note = new StringBuilder("今日跟练完成: ");
            for (int i = 0; i < actions.size(); i++) {
                if (i > 0) note.append("、");
                note.append(actions.get(i).optString("title", ""));
            }
            note.append(" (共 ").append(saveMinutes()).append(" 分钟)");
            payload.put("note", note.toString());
            api().request("/api/v1/exercises", "POST", payload, new ApiClient.Callback() {
                @Override public void onOk(JSONObject data, int status) {
                    saved = true;
                    saving = false;
                    render();
                }
                @Override public void onError(String message) {
                    saving = false;
                    toast(message == null || message.isEmpty() ? "保存失败, 可点重试" : message);
                    render();
                }
            });
        } catch (Exception e) {
            saving = false;
            render();
        }
    }

    /* ---------------- 渲染 ---------------- */

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        renderBackBar();
        if (phase == Phase.DONE) {
            renderDone();
        } else {
            renderHeader();
            if (phase == Phase.REST) renderRest();
            else renderAction();
            renderChecklist();
        }
    }

    private void renderBackBar() {
        LinearLayout bar = Ui.row(ctx());
        TextView back = Ui.text(ctx(), "‹ 退出跟练", 15, Ui.PRIMARY, true);
        back.setPadding(0, Ui.dp(ctx(), 2), Ui.dp(ctx(), 12), Ui.dp(ctx(), 2));
        back.setOnClickListener(v -> {
            if (phase != Phase.DONE) toast("跟练已退出, 本次未保存");
            activity().closeOverlay();
        });
        bar.addView(back);
        body.addView(bar);
    }

    private void renderHeader() {
        body.addView(Ui.label(ctx(), "● WORKOUT SESSION"));
        body.addView(Ui.title(ctx(), focus), Ui.matchWrap(2, ctx()));
        int total = actions.size();
        int doneCount = 0;
        for (boolean b : doneFlags) if (b) doneCount++;
        LinearLayout head = Ui.row(ctx());
        head.addView(Ui.text(ctx(), "第 " + (current + 1) + " / " + total + " 项 · 已完成 "
                + doneCount + " 项", 12, Ui.SLATE, true), Ui.weight1());
        body.addView(head, Ui.matchWrap(8, ctx()));
        double ratio = total > 0 ? doneCount / (double) total : 0;
        body.addView(Ui.progressBar(ctx(), ratio, Ui.LIME, 6), Ui.matchWrap(2, ctx()));
    }

    private void renderAction() {
        JSONObject a = currentAction();
        if (a == null) return;
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        GradientDrawable bg = Ui.roundRect(Ui.CARD, 16, c);
        bg.setStroke(Ui.dp(c, 1), Ui.LIME);
        card.setBackground(bg);
        card.addView(Ui.sectionTitle(c, a.optString("title", "")), Ui.matchWrap(2, c));
        String detail = a.optString("detail", "");
        if (!detail.isEmpty()) {
            card.addView(Ui.text(c, detail, 13, Ui.SLATE, false), Ui.matchWrap(6, c));
        }
        int minutes = a.optInt("minutes", 0);
        if (minutes > 0) {
            TextView timer = Ui.text(c, fmtTime(remainingSeconds), 40, Ui.LIME, true);
            timer.setGravity(Gravity.CENTER);
            card.addView(timer, Ui.matchWrap(10, c));
            card.addView(Ui.text(c, "本项计划 " + minutes + " 分钟", 12, Ui.MUTED, false),
                    Ui.matchWrap(2, c));
            Button toggle = Ui.ghostButton(c, timerRunning ? "⏸ 暂停计时" : "▶ 开始计时");
            toggle.setOnClickListener(v -> {
                if (timerRunning) stopTimer(); else startTimer();
                render();
            });
            card.addView(toggle, Ui.matchWrap(12, c));
        } else {
            card.addView(Ui.text(c, "按自己的节奏完成, 不用赶时间", 12, Ui.MUTED, false),
                    Ui.matchWrap(8, c));
        }
        Button doneBtn = Ui.primaryButton(c, "✓ 完成本项");
        doneBtn.setOnClickListener(v -> completeCurrent());
        card.addView(doneBtn, Ui.matchWrap(12, c));
        body.addView(card, Ui.matchWrap(12, c));
    }

    private void renderRest() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionTitle(c, "组间休息"), Ui.matchWrap(2, c));
        card.addView(Ui.text(c, "喝口水, 放松一下, 下一项马上开始", 13, Ui.SLATE, false),
                Ui.matchWrap(6, c));
        TextView timer = Ui.text(c, fmtTime(remainingSeconds), 40, Ui.MOVE, true);
        timer.setGravity(Gravity.CENTER);
        card.addView(timer, Ui.matchWrap(10, c));
        Button toggle = Ui.ghostButton(c, timerRunning ? "⏸ 暂停休息计时" : "▶ 开始休息计时");
        toggle.setOnClickListener(v -> {
            if (timerRunning) stopTimer(); else startTimer();
            render();
        });
        card.addView(toggle, Ui.matchWrap(12, c));
        Button skip = Ui.primaryButton(c, "跳过休息, 下一项 ›");
        skip.setOnClickListener(v -> advanceToNext());
        card.addView(skip, Ui.matchWrap(10, c));
        body.addView(card, Ui.matchWrap(12, c));
    }

    private void renderChecklist() {
        Context c = ctx();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionHeader(c, "今日动作", null, null));
        for (int i = 0; i < actions.size(); i++) {
            JSONObject a = actions.get(i);
            LinearLayout row = Ui.row(c);
            String mark = doneFlags[i] ? "✓" : (i == current ? "▶" : "○");
            int color = doneFlags[i] ? Ui.PRIMARY_DARK : (i == current ? Ui.LIME : Ui.MUTED);
            row.addView(Ui.text(c, mark, 14, color, true));
            LinearLayout.LayoutParams lp = Ui.weight1();
            lp.leftMargin = Ui.dp(c, 8);
            row.addView(Ui.text(c, a.optString("title", ""), 14,
                    doneFlags[i] ? Ui.MUTED : Ui.INK, false), lp);
            int minutes = a.optInt("minutes", 0);
            if (minutes > 0) row.addView(Ui.chip(c, minutes + " 分钟", Ui.PRIMARY_DARK, Ui.GREEN_SOFT));
            card.addView(row, Ui.matchWrap(i == 0 ? 10 : 8, c));
        }
        body.addView(card, Ui.matchWrap(12, c));
    }

    private void renderDone() {
        Context c = ctx();
        body.addView(Ui.label(c, "● WORKOUT DONE"));
        body.addView(Ui.title(c, "🎉 跟练完成!"), Ui.matchWrap(2, c));
        LinearLayout card = Ui.card(c);
        card.addView(Ui.text(c, focus + " · 共完成 " + actions.size() + " 项动作",
                14, Ui.INK, true), Ui.matchWrap(2, c));
        card.addView(Ui.text(c, "累计 " + saveMinutes() + " 分钟, 已计入今日运动",
                13, Ui.SLATE, false), Ui.matchWrap(8, c));
        if (saved) {
            card.addView(Ui.text(c, "✓ 已自动记入运动记录, 可在运动页查看",
                    13, Ui.PRIMARY_DARK, true), Ui.matchWrap(8, c));
        } else if (saving) {
            card.addView(Ui.text(c, "正在保存运动记录…", 13, Ui.SLATE, false),
                    Ui.matchWrap(8, c));
        } else {
            card.addView(Ui.text(c, "⚠ 运动记录保存失败, 请检查网络后重试",
                    13, Ui.CAL_TEXT, false), Ui.matchWrap(8, c));
            Button retry = Ui.primaryButton(c, "重新保存");
            retry.setOnClickListener(v -> autoSave());
            card.addView(retry, Ui.matchWrap(10, c));
        }
        body.addView(card, Ui.matchWrap(12, c));
        Button back = Ui.primaryButton(c, "返回计划页");
        back.setOnClickListener(v -> activity().closeOverlay());
        body.addView(back, Ui.matchWrap(12, c));
    }

    private static String fmtTime(int seconds) {
        int m = seconds / 60, s = seconds % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
