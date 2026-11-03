package art.qelora.welltrack.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import art.qelora.welltrack.MainActivity;
import art.qelora.welltrack.api.ApiClient;

/** 页面基类: 一个可滚动的纵向容器, 切换 Tab 时回调 onShow 刷新数据。 */
public abstract class BasePage extends FrameLayout {
    protected final android.widget.LinearLayout content;
    protected final ScrollView scroll;
    protected boolean created = false;

    public BasePage(Context ctx) {
        super(ctx);
        scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        content = Ui.column(ctx);
        int pad = Ui.dp(ctx, 16);
        content.setPadding(pad, pad, pad, Ui.dp(ctx, 24));
        scroll.addView(content);
        addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setBackgroundColor(Ui.BG);
    }

    /** 首次显示时构建静态骨架 (子类实现) */
    protected abstract void build();

    /** 每次切到本页时调用: 已构建则刷新数据 */
    public void onShow() {
        if (!created) { created = true; build(); }
    }

    public void onHide() {}

    protected ApiClient api() { return ApiClient.get(getContext()); }
    protected MainActivity activity() { return (MainActivity) getContext(); }
    protected void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
    protected Context ctx() { return getContext(); }
}
