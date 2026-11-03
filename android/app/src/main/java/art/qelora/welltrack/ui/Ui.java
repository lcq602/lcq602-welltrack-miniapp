package art.qelora.welltrack.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 与小程序 WXSS 主题一致的原生控件工厂 (Serene Balance 主题)。 */
public class Ui {
    public static final int BG = Color.parseColor("#f6f8fb");
    public static final int CARD = Color.WHITE;
    public static final int INK = Color.parseColor("#0f172a");
    public static final int SLATE = Color.parseColor("#64748b");
    public static final int MUTED = Color.parseColor("#94a3b8");
    public static final int BORDER = Color.parseColor("#e2e8f0");
    public static final int PRIMARY = Color.parseColor("#059669");
    public static final int PRIMARY_DARK = Color.parseColor("#00855d");
    public static final int SOFT = Color.parseColor("#f1f5f9");
    public static final int BLUE_SOFT = Color.parseColor("#eff6ff");
    public static final int RED = Color.parseColor("#dc2626");

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

    public static GradientDrawable primaryGradient(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{PRIMARY, PRIMARY_DARK});
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

    /** 白色圆角卡片容器 */
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
        b.setTextColor(Color.WHITE);
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
        b.setTextColor(PRIMARY);
        b.setBackground(roundRect(Color.parseColor("#ecfdf5"), 12, c));
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
}
