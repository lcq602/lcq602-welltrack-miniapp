package art.qelora.welltrack.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * 现代弹窗组件 (v3.1): 替代系统 AlertDialog / DatePickerDialog / TimePickerDialog 的老式外观。
 * - bottom(): 底部 Sheet (大圆角顶、拖拽柄、滑入动画、点击遮罩关闭)
 * - center(): 居中卡片弹窗 (进度提示等)
 * - timePicker()/datePicker(): 步进器式选择器 (大数字 + 加减按钮), 与全局深色荧光绿风格一致
 */
public final class Sheet {

    private Sheet() {}

    public interface TimeListener { void onPicked(int hour, int minute); }
    public interface DateListener { void onPicked(int year, int month1to12, int day); }

    /* ----- 底部 Sheet ----- */
    public static Dialog bottom(Context c, View content) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        float r = Ui.dp(c, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        wrap.setBackground(bg);

        View handle = new View(c);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xFF3A403A);
        hb.setCornerRadius(Ui.dp(c, 2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Ui.dp(c, 10);
        hlp.bottomMargin = Ui.dp(c, 6);
        wrap.addView(handle, hlp);
        wrap.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        wrap.setTranslationY(Ui.dp(c, 60));
        wrap.setAlpha(0f);
        wrap.animate().translationY(0).alpha(1f).setDuration(240).start();
        d.show();
        return d;
    }

    /* ----- 居中卡片弹窗 ----- */
    public static Dialog center(Context c, View content) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(Ui.roundRect(Ui.CARD, 20, c));
        int p = Ui.dp(c, 22);
        wrap.setPadding(p, p, p, p);
        wrap.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
        return d;
    }

    public static void close(Dialog d) {
        if (d != null && d.isShowing()) d.dismiss();
    }

    /* ----- 步进器行 ----- */
    private static LinearLayout stepperRow(Context c, String label, final int[] value, int min, int max,
                                           boolean wrap, String format, final Runnable onChange) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));

        TextView lab = Ui.text(c, label, 14f, Ui.MUTED, false);
        row.addView(lab, new LinearLayout.LayoutParams(Ui.dp(c, 52), ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView val = Ui.text(c, String.format(format, value[0]), 26f, Ui.INK, true);
        val.setGravity(Gravity.CENTER);

        Button minus = stepButton(c, "−");
        minus.setOnClickListener(v -> {
            value[0]--;
            if (value[0] < min) value[0] = wrap ? max : min;
            val.setText(String.format(format, value[0]));
            onChange.run();
        });
        Button plus = stepButton(c, "+");
        plus.setOnClickListener(v -> {
            value[0]++;
            if (value[0] > max) value[0] = wrap ? min : max;
            val.setText(String.format(format, value[0]));
            onChange.run();
        });
        row.addView(minus);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus);
        return row;
    }

    private static Button stepButton(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(20f);
        b.setTextColor(Ui.LIME);
        b.setAllCaps(false);
        b.setBackground(Ui.roundRect(Ui.SOFT, 12, c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 48));
        b.setLayoutParams(lp);
        return b;
    }

    private static LinearLayout sheetBox(Context c, String title) {
        LinearLayout box = Ui.column(c);
        int p = Ui.dp(c, 20);
        box.setPadding(p, Ui.dp(c, 8), p, p);
        TextView t = Ui.text(c, title, 17f, Ui.INK, true);
        t.setPadding(0, 0, 0, Ui.dp(c, 10));
        box.addView(t);
        return box;
    }

    /* ----- 时间选择 (时:分 步进) ----- */
    public static void timePicker(Context c, String title, int hour, int minute, final TimeListener l) {
        final int[] h = {hour}, m = {minute};
        LinearLayout box = sheetBox(c, title);
        final Dialog[] holder = new Dialog[1];
        box.addView(stepperRow(c, "小时", h, 0, 23, true, "%02d", () -> {}));
        box.addView(stepperRow(c, "分钟", m, 0, 59, true, "%02d", () -> {}));
        Button ok = Ui.primaryButton(c, "确定");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 50));
        lp.topMargin = Ui.dp(c, 14);
        ok.setLayoutParams(lp);
        ok.setOnClickListener(v -> { close(holder[0]); l.onPicked(h[0], m[0]); });
        box.addView(ok);
        holder[0] = bottom(c, box);
    }

    /* ----- 日期选择 (年/月/日 步进, 日按月自动钳制) ----- */
    public static void datePicker(Context c, String title, int year, int month, int day, final DateListener l) {
        final int[] y = {year}, mo = {month}, d = {day};
        LinearLayout box = sheetBox(c, title);
        final Dialog[] holder = new Dialog[1];
        final Runnable clamp = () -> {
            int max = daysInMonth(y[0], mo[0]);
            if (d[0] > max) d[0] = max;
        };
        // 日行需要随年月刷新上限: 用整盒重建的方式成本高, 这里在确认时钳制, 显示行步进范围 1..31
        box.addView(stepperRow(c, "年", y, 2000, 2100, false, "%d", clamp));
        box.addView(stepperRow(c, "月", mo, 1, 12, true, "%02d", clamp));
        box.addView(stepperRow(c, "日", d, 1, 31, true, "%02d", () -> {}));
        Button ok = Ui.primaryButton(c, "确定");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 50));
        lp.topMargin = Ui.dp(c, 14);
        ok.setLayoutParams(lp);
        ok.setOnClickListener(v -> {
            clamp.run();
            close(holder[0]);
            l.onPicked(y[0], mo[0], d[0]);
        });
        box.addView(ok);
        holder[0] = bottom(c, box);
    }

    /* ----- 确认弹窗 (底部 Sheet 版) ----- */
    public static void confirm(Context c, String title, String message, String confirmText,
                               final Runnable onConfirm) {
        LinearLayout box = sheetBox(c, title);
        TextView msg = Ui.text(c, message, 14f, Ui.SLATE, false);
        msg.setLineSpacing(0, 1.35f);
        box.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Dialog[] holder = new Dialog[1];
        Button ok = Ui.primaryButton(c, confirmText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 50));
        lp.topMargin = Ui.dp(c, 16);
        ok.setLayoutParams(lp);
        ok.setOnClickListener(v -> { close(holder[0]); if (onConfirm != null) onConfirm.run(); });
        box.addView(ok);
        Button cancel = Ui.ghostButton(c, "取消");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46));
        clp.topMargin = Ui.dp(c, 8);
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(v -> close(holder[0]));
        box.addView(cancel);
        holder[0] = bottom(c, box);
    }

    private static int daysInMonth(int year, int month) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1);
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
}
