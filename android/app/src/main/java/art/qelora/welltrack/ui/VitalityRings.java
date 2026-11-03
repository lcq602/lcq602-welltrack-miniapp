package art.qelora.welltrack.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 活力三环 (参考 Mi Fitness 活力值): 外环步数(绿) / 中环消耗(橙) / 内环运动(蓝),
 * 圆头端点, 灰色轨道。setData 传入 0..1 的完成比。
 */
public class VitalityRings extends View {
    private double steps = 0, calories = 0, move = 0;
    private int trackColor = Color.parseColor("#e8edf2");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public VitalityRings(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setData(double stepsRatio, double caloriesRatio, double moveRatio) {
        steps = clamp(stepsRatio);
        calories = clamp(caloriesRatio);
        move = clamp(moveRatio);
        invalidate();
    }

    public void setTrackColor(int color) { trackColor = color; invalidate(); }

    private double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        float stroke = size * 0.085f;
        paint.setStrokeWidth(stroke);
        double[] ratios = {steps, calories, move};
        int[] colors = {Ui.PRIMARY, Ui.CAL, Ui.MOVE};
        for (int i = 0; i < 3; i++) {
            float radius = size / 2f - stroke / 2f - i * (stroke * 1.45f);
            RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setColor(trackColor);
            canvas.drawArc(oval, 0, 360, false, paint);
            if (ratios[i] > 0.005) {
                paint.setColor(colors[i]);
                canvas.drawArc(oval, -90, (float) (360 * ratios[i]), false, paint);
            }
        }
    }
}
