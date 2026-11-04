package art.qelora.welltrack.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 单环饮水进度环: 蓝色圆弧 + 深色轨道, 圆头端点。
 * setRatio 传入 0..1 的完成比 (相对 2000 ml 目标)。
 */
public class WaterRing extends View {
    private double ratio = 0;
    private int trackColor = Color.parseColor("#242b24");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaterRing(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setRatio(double ratio) {
        this.ratio = Math.max(0, Math.min(1, ratio));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        float stroke = size * 0.13f;
        paint.setStrokeWidth(stroke);
        float radius = size / 2f - stroke / 2f;
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setColor(trackColor);
        canvas.drawArc(oval, 0, 360, false, paint);
        if (ratio > 0.005) {
            paint.setColor(Ui.MOVE);
            canvas.drawArc(oval, -90, (float) (360 * ratio), false, paint);
        }
    }
}
