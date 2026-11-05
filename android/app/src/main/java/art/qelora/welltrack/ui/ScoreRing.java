package art.qelora.welltrack.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 今日健康分大环 (v3.0 首页 Hero): 单环粗描边, 荧光绿进度 + 深色轨道, 圆头端点。
 * 中心的分数文字由外层布局叠加 TextView 实现, 本视图只画环。
 */
public class ScoreRing extends View {
    private double ratio = 0;
    private int color = Ui.LIME;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScoreRing(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setScore(int score, int color) {
        this.ratio = Math.max(0, Math.min(1, score / 100.0));
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        float stroke = size * 0.11f;
        paint.setStrokeWidth(stroke);
        float radius = size / 2f - stroke / 2f - size * 0.02f;
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setColor(Color.parseColor("#232a23"));
        canvas.drawArc(oval, 0, 360, false, paint);
        if (ratio > 0.005) {
            paint.setColor(color);
            canvas.drawArc(oval, -90, (float) (360 * ratio), false, paint);
        }
    }
}
