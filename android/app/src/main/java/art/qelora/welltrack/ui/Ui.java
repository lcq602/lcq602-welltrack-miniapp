package art.qelora.welltrack.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原生控件工厂。v2.2 起全局「深色 + 荧光绿」主题 (参考健身 App 深色风):
 * 近黑底 / 深卡面 / 荧光黄绿强调; 浅色语义对成对翻转 (底变深、字变浅) 保证深底可读。
 */
public class Ui {
    public static final int BG = Color.parseColor("#0B0E0B");
    public static final int CARD = Color.parseColor("#161A16");
    public static final int INK = Color.parseColor("#F4F7F2");
    public static final int SLATE = Color.parseColor("#A9B4A6");
    public static final int MUTED = Color.parseColor("#7C877A");
    public static final int BORDER = Color.parseColor("#2A312A");
    public static final int PRIMARY = Color.parseColor("#10b981");
    /** 深底上的绿文字 (原深绿文字翻转为浅绿, 与 GREEN_SOFT 深底成对使用) */
    public static final int PRIMARY_DARK = Color.parseColor("#7ee2a8");
    public static final int SOFT = Color.parseColor("#1E241E");
    public static final int BLUE_SOFT = Color.parseColor("#12283d");
    public static final int RED = Color.parseColor("#f87171");
    /** v2.2 荧光绿强调色 (CTA / 高亮 / 今天标记), 及它上面的近黑文字 */
    public static final int LIME = Color.parseColor("#B8F135");
    public static final int ON_LIME = Color.parseColor("#131806");
    // 指标专属色 (参考 Mi Fitness 活力值体系: 步数绿 / 消耗橙 / 睡眠紫 / 运动蓝), 深色版: 底变深、字变浅
    public static final int CAL = Color.parseColor("#f97316");
    public static final int CAL_TEXT = Color.parseColor("#fdba74");
    public static final int CAL_SOFT = Color.parseColor("#2a1a0d");
    public static final int SLEEP = Color.parseColor("#a78bfa");
    public static final int SLEEP_SOFT = Color.parseColor("#1e1836");
    public static final int MOVE = Color.parseColor("#38bdf8");
    public static final int MOVE_SOFT = Color.parseColor("#0c2231");
    public static final int GREEN_SOFT = Color.parseColor("#12261a");
    public static final int RED_SOFT = Color.parseColor("#2a1212");
    public static final int AMBER_SOFT_DARK = Color.parseColor("#2a2110");
    public static final int AMBER_TEXT = Color.parseColor("#fbbf24");
    public static final int DEEP = Color.parseColor("#0a130d");
    public static final int DEEP2 = Color.parseColor("#1c4630");

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable roundRect(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable cardBg(Context c) {
        GradientDrawable d = roundRect(CARD, 16, c);
        d.setStroke(1, BORDER);
        return d;
    }

    /** 荧光绿渐变 (主按钮 / 用户气泡等强调面) */
    public static GradientDrawable primaryGradient(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{LIME, Color.parseColor("#93d42a")});
        d.setCornerRadius(dp(c, 14));
        return d;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    public static TextView label(Context c, String s) { return text(c, s, 12, MUTED, false); }
    public static TextView title(Context c, String s) { return text(c, s, 20, INK, true); }
    public static TextView sectionTitle(Context c, String s) { return text(c, s, 15, INK, true); }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 深色圆角卡片容器 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(cardBg(c));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static Button primaryButton(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(ON_LIME);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(primaryGradient(c));
        b.setAllCaps(false);
        int v = dp(c, 6);
        b.setPadding(0, v, 0, v);
        return b;
    }

    public static Button ghostButton(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(INK);
        GradientDrawable bg = roundRect(SOFT, 12, c);
        bg.setStroke(1, BORDER);
        b.setBackground(bg);
        b.setAllCaps(false);
        return b;
    }

    public static EditText input(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(roundRect(SOFT, 10, c));
        int h = dp(c, 10), v = dp(c, 10);
        e.setPadding(h, v, h, v);
        return e;
    }

    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static LinearLayout.LayoutParams matchWrap(int topMarginDp, Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    public static LinearLayout.LayoutParams weight1() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        return lp;
    }

    // ---------- v2 设计系统 (视觉升级轮次加入) ----------

    /** 大圆角深绿黑渐变主视觉卡背景 */
    public static GradientDrawable heroGradient(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{DEEP2, DEEP});
        d.setCornerRadius(dp(c, 24));
        return d;
    }

    /** 圆角裁剪的图片横幅 (CENTER_CROP) */
    public static ImageView bannerImage(Context c, int resId, int heightDp, float radiusDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        final float r = dp(c, radiusDp);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return iv;
    }

    /** 章节标题行: 大标题 + 可选右侧动作文字 */
    public static LinearLayout sectionHeader(Context c, String title, String action, View.OnClickListener onAction) {
        LinearLayout r = row(c);
        TextView t = text(c, title, 17, INK, true);
        r.addView(t, weight1());
        if (action != null) {
            TextView a = text(c, action, 13, PRIMARY, false);
            a.setPadding(dp(c, 8), dp(c, 4), 0, dp(c, 4));
            if (onAction != null) a.setOnClickListener(onAction);
            r.addView(a);
        }
        return r;
    }

    /** 细进度条 (轨道 + 彩色填充), ratio 0..1 */
    public static FrameLayout progressBar(Context c, double ratio, int color, int heightDp) {
        FrameLayout track = new FrameLayout(c);
        track.setBackground(roundRect(Color.parseColor("#242b24"), heightDp / 2f, c));
        track.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        final View fill = new View(c);
        fill.setBackground(roundRect(color, heightDp / 2f, c));
        double clamped = Math.max(0, Math.min(1, ratio));
        fill.setLayoutParams(new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        track.addView(fill);
        final double rr = clamped;
        track.post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            lp.width = (int) (track.getWidth() * rr);
            fill.setLayoutParams(lp);
        });
        return track;
    }

    /** 指标瓷砖: 彩色图标字 + 标签 + 大数值 + 可选达标进度条 */
    public static LinearLayout metricTile(Context c, int accent, int softBg, String emoji,
                                          String label, String value, String sub, double ratio) {
        LinearLayout t = column(c);
        t.setBackground(roundRect(softBg, 16, c));
        int p = dp(c, 12);
        t.setPadding(p, p, p, p);
        LinearLayout head = row(c);
        TextView em = text(c, emoji, 15, accent, true);
        TextView lb = text(c, label, 12, SLATE, false);
        lb.setPadding(dp(c, 5), 0, 0, 0);
        head.addView(em);
        head.addView(lb);
        t.addView(head);
        TextView v = text(c, value, 20, INK, true);
        LinearLayout.LayoutParams vlp = matchWrap(4, c);
        t.addView(v, vlp);
        if (sub != null) {
            TextView s = text(c, sub, 11, MUTED, false);
            t.addView(s, matchWrap(2, c));
        }
        if (ratio >= 0) {
            FrameLayout bar = progressBar(c, ratio, accent, 5);
            LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            blp.topMargin = dp(c, 8);
            bar.setLayoutParams(blp);
            t.addView(bar);
        }
        return t;
    }

    /** v3.0 快捷操作瓷砖: 大图标 + 标签, 整块可点 (首页快捷入口行) */
    public static LinearLayout quickAction(Context c, String emoji, String label,
                                           View.OnClickListener onClick) {
        LinearLayout t = column(c);
        t.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        t.setBackground(roundRect(SOFT, 18, c));
        int pv = dp(c, 12);
        t.setPadding(dp(c, 4), pv, dp(c, 4), pv);
        TextView em = text(c, emoji, 24, INK, false);
        em.setGravity(android.view.Gravity.CENTER);
        t.addView(em);
        TextView lb = text(c, label, 12, INK, true);
        lb.setGravity(android.view.Gravity.CENTER);
        t.addView(lb, matchWrap(6, c));
        if (onClick != null) t.setOnClickListener(onClick);
        return t;
    }

    /** v3.0 可选态切换片 (打卡面板的运动/时长选择): 选中 = 荧光绿底黑字 */
    public static TextView toggleChip(Context c, String s, boolean selected,
                                      View.OnClickListener onClick) {
        TextView t = text(c, s, 13, selected ? ON_LIME : SLATE, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(roundRect(selected ? LIME : SOFT, 999, c));
        int h = dp(c, 12), v = dp(c, 7);
        t.setPadding(h, v, h, v);
        if (onClick != null) t.setOnClickListener(onClick);
        return t;
    }

    /** 小胶囊标签 */
    public static TextView chip(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 11, fg, true);
        t.setBackground(roundRect(bg, 10, c));
        int h = dp(c, 8), v = dp(c, 3);
        t.setPadding(h, v, h, v);
        return t;
    }
}
