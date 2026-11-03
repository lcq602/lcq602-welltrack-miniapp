package art.qelora.welltrack;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import art.qelora.welltrack.pages.AnalysisPage;
import art.qelora.welltrack.pages.ExercisePage;
import art.qelora.welltrack.pages.IndexPage;
import art.qelora.welltrack.pages.MinePage;
import art.qelora.welltrack.pages.PlanPage;
import art.qelora.welltrack.pages.RecordsPage;
import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 轻衡健康 WellTrack (原生安卓版, 由微信小程序同构转化)
 * 底部 5 Tab 与小程序自定义 Tab 栏一致: 首页/计划/记录/趋势/我的;
 * 运动页以覆盖层打开 (对应小程序 navigateTo), 返回键关闭。
 */
public class MainActivity extends Activity {
    private FrameLayout pageHost;
    private BasePage[] tabPages;
    private int currentTab = -1;
    private BasePage overlayPage = null;
    private final int[] tabIcons = {
            R.drawable.ic_tab_home, R.drawable.ic_tab_plan, R.drawable.ic_tab_records,
            R.drawable.ic_tab_analysis, R.drawable.ic_tab_mine};
    private final String[] tabLabels = {"首页", "计划", "记录", "趋势", "我的"};
    private LinearLayout[] tabItems;
    private ImageView[] tabIconViews;
    private TextView[] tabLabelViews;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Ui.BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildTabBar());
        setContentView(root);

        tabPages = new BasePage[]{
                new IndexPage(this), new PlanPage(this), new RecordsPage(this),
                new AnalysisPage(this), new MinePage(this)};
        for (BasePage p : tabPages) {
            p.setVisibility(View.GONE);
            pageHost.addView(p, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        switchTab(0);
    }

    private View buildTabBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setBackgroundColor(Ui.BG);
        int m = Ui.dp(this, 12);
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(Ui.roundRect(Color.WHITE, 28, this));
        dock.setElevation(Ui.dp(this, 6));
        int pv = Ui.dp(this, 8);
        dock.setPadding(pv, pv, pv, pv);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dockLp.setMargins(m, Ui.dp(this, 6), m, m);
        wrap.addView(dock, dockLp);

        tabItems = new LinearLayout[5];
        tabIconViews = new ImageView[5];
        tabLabelViews = new TextView[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER);
            int ph = Ui.dp(this, 16), pvv = Ui.dp(this, 4);
            pill.setPadding(ph, pvv, ph, pvv);
            ImageView icon = new ImageView(this);
            icon.setImageResource(tabIcons[i]);
            icon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            pill.addView(icon);
            TextView label = Ui.text(this, tabLabels[i], 11, Ui.SLATE, false);
            label.setGravity(Gravity.CENTER);
            item.addView(pill);
            item.addView(label);
            item.setOnClickListener(v -> switchTab(idx));
            dock.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tabItems[i] = item;
            tabIconViews[i] = icon;
            tabLabelViews[i] = label;
            // pill 容器供高亮时换背景
            item.setTag(pill);
        }
        return wrap;
    }

    public void switchTab(int idx) {
        if (overlayPage != null) closeOverlay();
        if (currentTab == idx) return;
        if (currentTab >= 0) {
            tabPages[currentTab].onHide();
            tabPages[currentTab].setVisibility(View.GONE);
        }
        currentTab = idx;
        tabPages[idx].setVisibility(View.VISIBLE);
        tabPages[idx].onShow();
        for (int i = 0; i < 5; i++) {
            boolean active = i == idx;
            LinearLayout pill = (LinearLayout) tabItems[i].getTag();
            pill.setBackground(active ? Ui.roundRect(Color.parseColor("#d1fae5"), 18, this) : null);
            tabIconViews[i].setColorFilter(active ? Ui.PRIMARY : Ui.SLATE);
            tabLabelViews[i].setTextColor(active ? Ui.PRIMARY : Ui.SLATE);
            tabLabelViews[i].setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** 打开覆盖页 (目前是运动页), 对应小程序 wx.navigateTo */
    public void openExercise() {
        if (overlayPage != null) return;
        overlayPage = new ExercisePage(this);
        pageHost.addView(overlayPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayPage.onShow();
    }

    public void closeOverlay() {
        if (overlayPage == null) return;
        overlayPage.onHide();
        pageHost.removeView(overlayPage);
        overlayPage = null;
    }

    @Override
    public void onBackPressed() {
        if (overlayPage != null) { closeOverlay(); return; }
        super.onBackPressed();
    }
}
