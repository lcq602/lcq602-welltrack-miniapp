package art.qelora.welltrack.health;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.11 健康摘要分享卡 (R9): 把本周统计画成一张深色 + 荧光绿的图片,
 * 保存到系统相册 (Pictures/WellTrack) 后调系统分享面板发出。
 * 纯 Canvas 绘制, 不依赖任何图片库; 保存走 MediaStore, Android 10+ 无需存储权限。
 */
public class ShareCard {

    /** 分享卡要展示的统计, 全部来自本周真实数据, 不编造 */
    public static class Stats {
        public String dateRange = "";
        public int score = 0;
        public String scoreLabel = "";
        public long totalSteps = 0;
        public int avgSteps = 0;
        public double totalCalories = 0;
        public int stepDays = 0;
        public int calDays = 0;
        public int workoutDays = 0;
        public double workoutMinutes = 0;
        public double avgSleep = 0; // 0 表示无睡眠数据
        public Double weightChange = null; // null 表示无体重数据
    }

    private static final int W = 1080;
    private static final int H = 1440;
    private static final int BG = Color.parseColor("#0B0E0B");
    private static final int CARD = Color.parseColor("#161A16");
    private static final int LIME = Color.parseColor("#B8F135");
    private static final int INK = Color.parseColor("#F4F7F2");
    private static final int SLATE = Color.parseColor("#A9B4A6");
    private static final int MUTED = Color.parseColor("#7C877A");
    private static final int BORDER = Color.parseColor("#2A312A");
    private static final int GREEN = Color.parseColor("#10b981");
    private static final int ORANGE = Color.parseColor("#f97316");
    private static final int PURPLE = Color.parseColor("#a78bfa");
    private static final int BLUE = Color.parseColor("#38bdf8");

    /** 绘制 1080x1440 分享卡 */
    public static Bitmap render(Context ctx, Stats s) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 底色 + 顶部荧光绿横条
        p.setColor(BG);
        c.drawRect(0, 0, W, H, p);
        p.setColor(LIME);
        c.drawRect(0, 0, W, 14, p);

        // 标题区
        p.setColor(LIME);
        p.setTextSize(44);
        p.setFakeBoldText(true);
        c.drawText("WELLTRACK", 72, 130, p);
        p.setColor(INK);
        p.setTextSize(64);
        c.drawText("本周健康摘要", 72, 215, p);
        p.setColor(SLATE);
        p.setTextSize(32);
        p.setFakeBoldText(false);
        c.drawText(s.dateRange, 72, 275, p);

        // 健康分大卡
        p.setColor(CARD);
        RectF scoreBox = new RectF(72, 320, W - 72, 560);
        c.drawRoundRect(scoreBox, 36, 36, p);
        p.setColor(BORDER);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        c.drawRoundRect(scoreBox, 36, 36, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(MUTED);
        p.setTextSize(30);
        c.drawText("本周健康分", 120, 390, p);
        p.setColor(LIME);
        p.setTextSize(150);
        p.setFakeBoldText(true);
        c.drawText(String.valueOf(s.score), 120, 520, p);
        p.setColor(INK);
        p.setTextSize(36);
        p.setFakeBoldText(false);
        float scoreW = p.measureText(String.valueOf(s.score));
        c.drawText(" / 100 · " + s.scoreLabel, 120 + scoreW + 16, 520, p);

        // 指标网格: 2 列 x 3 行
        int top = 600;
        int gap = 28;
        int cellW = (W - 144 - gap) / 2;
        int cellH = 210;
        tile(c, 72, top, cellW, cellH, "👟", "累计步数", fmtLong(s.totalSteps) + " 步",
                "日均 " + fmtLong(s.avgSteps) + " 步 · 达标 " + s.stepDays + " 天", GREEN);
        tile(c, 72 + cellW + gap, top, cellW, cellH, "🔥", "消耗能量",
                fmtNum(s.totalCalories) + " kcal", "达标 " + s.calDays + " 天", ORANGE);
        tile(c, 72, top + cellH + gap, cellW, cellH, "🏃", "运动",
                s.workoutDays + " 天 · " + fmtNum(s.workoutMinutes) + " 分钟", "本周运动安排", BLUE);
        tile(c, 72 + cellW + gap, top + cellH + gap, cellW, cellH, "🌙", "平均睡眠",
                s.avgSleep > 0 ? fmtNum(s.avgSleep) + " 小时" : "暂无数据", "目标 7–8 小时", PURPLE);
        tile(c, 72, top + 2 * (cellH + gap), cellW, cellH, "⚖️", "体重变化",
                s.weightChange == null ? "暂无数据"
                        : (s.weightChange < 0 ? "↓ " : "↑ ") + fmtNum(Math.abs(s.weightChange)) + " kg",
                "本周首末体重差", GREEN);
        tile(c, 72 + cellW + gap, top + 2 * (cellH + gap), cellW, cellH, "✅", "坚持打卡",
                "步数 + 消耗双达标 " + Math.min(s.stepDays, s.calDays) + " 天",
                "每天一小步, 长期看得见", LIME);

        // 底部落款
        int footY = top + 3 * cellH + 2 * gap + 110;
        p.setColor(MUTED);
        p.setTextSize(30);
        c.drawText("轻衡健康 · 每一步都看得见", 72, footY, p);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        p.setTextSize(26);
        c.drawText("WellTrack 生成于 " + today, 72, footY + 48, p);
        return bmp;
    }

    private static void tile(Canvas c, int x, int y, int w, int h,
                             String icon, String label, String value, String foot, int accent) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(CARD);
        RectF r = new RectF(x, y, x + w, y + h);
        c.drawRoundRect(r, 28, 28, p);
        p.setColor(BORDER);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        c.drawRoundRect(r, 28, 28, p);
        p.setStyle(Paint.Style.FILL);
        // 左侧强调条
        p.setColor(accent);
        c.drawRoundRect(new RectF(x, y, x + 10, y + h), 5, 5, p);
        p.setColor(INK);
        p.setTextSize(34);
        c.drawText(icon + "  " + label, x + 36, y + 62, p);
        p.setFakeBoldText(true);
        p.setTextSize(40);
        c.drawText(value, x + 36, y + 128, p);
        p.setFakeBoldText(false);
        p.setColor(SLATE);
        p.setTextSize(28);
        c.drawText(foot, x + 36, y + 178, p);
    }

    /** 保存到相册 Pictures/WellTrack, 返回 content Uri (Android 10+ 才支持免权限相对路径) */
    public static Uri saveToGallery(Context ctx, Bitmap bmp, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("分享卡保存需要 Android 10 及以上系统");
        }
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WellTrack");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建相册文件");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("图片写入失败");
            }
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    /** 保存到相册并打开系统分享面板; 返回相册文件名, 失败抛 IOException */
    public static String share(Context ctx, Stats s) throws IOException {
        Bitmap bmp = render(ctx, s);
        String fileName = "WellTrack-周摘要-" + s.dateRange.replace(" — ", "_") + ".png";
        Uri uri = saveToGallery(ctx, bmp, fileName);
        bmp.recycle();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_TEXT, "我的 WellTrack 本周健康摘要");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(intent, "分享本周健康摘要"));
        return fileName;
    }

    private static String fmtLong(long v) { return String.valueOf(v); }

    private static String fmtNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return new java.math.BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }
}
