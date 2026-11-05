package art.qelora.welltrack.pages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import art.qelora.welltrack.ui.BasePage;
import art.qelora.welltrack.ui.Ui;

/**
 * 自研相机页 (v3.1): 替代系统相机 Intent 的老式取景与低清缩略图。
 * Camera2 实时预览 + 全分辨率 JPEG 拍摄; 取景框角标、圆形快门、闪光灯、
 * 前后切换、相册入口, 与 App 深色风格一致。拍完直接把 Bitmap 交给调用方。
 */
public class CameraPage extends BasePage {

    private TextureView preview;
    private TextView flashBtn;
    private TextView flipBtn;

    private CameraManager manager;
    private String backId, frontId, currentId;
    private int sensorOrientation = 90;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder previewBuilder;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private boolean flashOn = false;
    private boolean useFront = false;
    private boolean captured = false;

    public CameraPage(Context ctx) {
        super(ctx);
        scroll.setVisibility(View.GONE);
        setBackgroundColor(0xFF000000);
        buildUi();
    }

    @Override protected void build() {}

    private void buildUi() {
        Context c = ctx();
        preview = new TextureView(c);
        addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { configTransform(w, h); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        // 取景框角标
        addView(new CornersView(c), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 顶部栏: 关闭 + 标题 + 闪光灯
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        addView(top, topLp);

        TextView close = circleBtn(c, "✕");
        close.setOnClickListener(v -> activity().closeOverlay());
        top.addView(close);
        TextView title = Ui.text(c, "拍照识别热量", 16f, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        flashBtn = circleBtn(c, "⚡");
        flashBtn.setTextColor(0xFF8A918A);
        flashBtn.setOnClickListener(v -> {
            flashOn = !flashOn;
            flashBtn.setTextColor(flashOn ? Ui.LIME : 0xFF8A918A);
            applyPreviewSettings();
        });
        top.addView(flashBtn);

        // 底部栏: 相册 / 快门 / 翻转
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(Ui.dp(c, 24), Ui.dp(c, 16), Ui.dp(c, 24), Ui.dp(c, 28));
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        botLp.gravity = Gravity.BOTTOM;
        addView(bottom, botLp);

        TextView gallery = Ui.text(c, "🖼  相册", 15f, 0xFFFFFFFF, true);
        gallery.setGravity(Gravity.CENTER);
        gallery.setBackground(Ui.roundRect(0x66000000, 14, c));
        gallery.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        gallery.setOnClickListener(v -> {
            activity().closeOverlay();
            activity().deliverCameraGallery();
        });
        bottom.addView(gallery, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View shutter = buildShutter(c);
        bottom.addView(shutter);

        flipBtn = Ui.text(c, "🔄  翻转", 15f, 0xFFFFFFFF, true);
        flipBtn.setGravity(Gravity.CENTER);
        flipBtn.setBackground(Ui.roundRect(0x66000000, 14, c));
        flipBtn.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        flipBtn.setOnClickListener(v -> { useFront = !useFront; closeCamera(); openCamera(); });
        bottom.addView(flipBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = Ui.text(c, "对准食物拍一张，AI 自动算热量", 13f, 0xCCFFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM;
        hintLp.bottomMargin = Ui.dp(c, 122);
        addView(hint, hintLp);
    }

    private TextView circleBtn(Context c, String s) {
        TextView t = Ui.text(c, s, 17f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.roundRect(0x66000000, 22, c));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        return t;
    }

    private View buildShutter(Context c) {
        FrameLayout ring = new FrameLayout(c);
        int size = Ui.dp(c, 78);
        ring.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        View outer = new View(c);
        android.graphics.drawable.GradientDrawable od = new android.graphics.drawable.GradientDrawable();
        od.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        od.setColor(0x00000000);
        od.setStroke(Ui.dp(c, 4), 0xFFFFFFFF);
        outer.setBackground(od);
        ring.addView(outer, new FrameLayout.LayoutParams(size, size));
        View inner = new View(c);
        android.graphics.drawable.GradientDrawable id = new android.graphics.drawable.GradientDrawable();
        id.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        id.setColor(0xFFFFFFFF);
        inner.setBackground(id);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 62), Ui.dp(c, 62));
        ilp.gravity = Gravity.CENTER;
        ring.addView(inner, ilp);
        ring.setOnClickListener(v -> {
            ring.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80)
                    .withEndAction(() -> ring.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            takePhoto();
        });
        return ring;
    }

    /** 取景框四角标记 */
    private static class CornersView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CornersView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 3));
            paint.setColor(Ui.LIME);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int m = Ui.dp(getContext(), 36);
            int len = Ui.dp(getContext(), 30);
            RectF box = new RectF(m, h * 0.22f, w - m, h * 0.66f);
            float r = Ui.dp(getContext(), 18);
            canvas.save(); canvas.clipRect(box.left, box.top, box.left + len, box.top + len); canvas.drawRoundRect(box, r, r, paint); canvas.restore();
            canvas.save(); canvas.clipRect(box.right - len, box.top, box.right, box.top + len); canvas.drawRoundRect(box, r, r, paint); canvas.restore();
            canvas.save(); canvas.clipRect(box.left, box.bottom - len, box.left + len, box.bottom); canvas.drawRoundRect(box, r, r, paint); canvas.restore();
            canvas.save(); canvas.clipRect(box.right - len, box.bottom - len, box.right, box.bottom); canvas.drawRoundRect(box, r, r, paint); canvas.restore();
        }
    }

    /* ----- 相机生命周期 ----- */
    @Override public void onShow() {
        super.onShow();
        if (ctx().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startThread();
        } else {
            activity().ensureCameraPermission(
                    this::startThread,
                    () -> { toast("需要相机权限才能拍照"); activity().closeOverlay(); });
        }
    }

    @Override public void onHide() {
        closeCamera();
        stopThread();
    }

    private void startThread() {
        if (bgThread != null) { if (preview.isAvailable()) openCamera(); return; }
        bgThread = new HandlerThread("welltrack-camera");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        if (preview.isAvailable()) openCamera();
    }

    private void stopThread() {
        if (bgThread != null) { bgThread.quitSafely(); bgThread = null; bgHandler = null; }
    }

    private void openCamera() {
        if (bgHandler == null || !preview.isAvailable()) return;
        try {
            manager = (CameraManager) ctx().getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) backId = id;
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) frontId = id;
            }
            currentId = (useFront && frontId != null) ? frontId : (backId != null ? backId : manager.getCameraIdList()[0]);
            CameraCharacteristics ch = manager.getCameraCharacteristics(currentId);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so != null ? so : 90;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size capture = chooseSize(map.getOutputSizes(ImageFormat.JPEG), 2560);
            Size previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class), 1920);
            reader = ImageReader.newInstance(capture.getWidth(), capture.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onImage, bgHandler);
            SurfaceTexture st = preview.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configTransform(preview.getWidth(), preview.getHeight());
            if (ctx().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(currentId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) { device = cam; startPreview(); }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); }
                @Override public void onError(CameraDevice cam, int error) { cam.close(); toast("相机打开失败，试试从相册选"); }
            }, bgHandler);
        } catch (Exception e) {
            toast("相机不可用，试试从相册选");
        }
    }

    private Size chooseSize(Size[] sizes, int maxDim) {
        Size best = sizes[0];
        long bestArea = 0;
        for (Size s : sizes) {
            if (s.getWidth() > maxDim || s.getHeight() > maxDim) continue;
            long area = (long) s.getWidth() * s.getHeight();
            if (area > bestArea) { bestArea = area; best = s; }
        }
        return best;
    }

    private void startPreview() {
        if (device == null || !preview.isAvailable()) return;
        try {
            Surface surface = new Surface(preview.getSurfaceTexture());
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);
            List<Surface> targets = new ArrayList<>();
            targets.add(surface);
            device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    applyPreviewSettings();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { toast("相机初始化失败"); }
            }, bgHandler);
        } catch (CameraAccessException e) {
            toast("相机初始化失败");
        }
    }

    private void applyPreviewSettings() {
        if (session == null || previewBuilder == null) return;
        previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        previewBuilder.set(CaptureRequest.FLASH_MODE,
                flashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        try { session.setRepeatingRequest(previewBuilder.build(), null, bgHandler); }
        catch (CameraAccessException ignored) {}
    }

    private void takePhoto() {
        if (device == null || session == null || captured) return;
        captured = true;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.FLASH_MODE,
                    flashOn ? CaptureRequest.FLASH_MODE_SINGLE : CaptureRequest.FLASH_MODE_OFF);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            session.capture(b.build(), null, bgHandler);
        } catch (CameraAccessException e) {
            captured = false;
            toast("拍摄失败，再试一次");
        }
    }

    private int jpegOrientation() {
        int rotation = activity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default: degrees = 0;
        }
        if (useFront) return (sensorOrientation + degrees) % 360;
        return (sensorOrientation - degrees + 360) % 360;
    }

    private void onImage(ImageReader r) {
        try (Image image = r.acquireLatestImage()) {
            if (image == null) return;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) { captured = false; return; }
            int rot = jpegOrientation();
            if (rot != 0) {
                Matrix mtx = new Matrix();
                mtx.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mtx, true);
            }
            final Bitmap out = bmp;
            post(() -> {
                activity().closeOverlay();
                activity().deliverCameraPhoto(out);
            });
        } catch (Exception e) {
            captured = false;
        }
    }

    private void configTransform(int viewW, int viewH) {
        if (viewW == 0 || viewH == 0) return;
        // 预览面按 TextureView 默认拉伸即可满足取景; 这里保持简单拉伸 (拍照成片比例由传感器决定)
    }

    private void closeCamera() {
        try { if (session != null) { session.close(); session = null; } } catch (Exception ignored) {}
        try { if (device != null) { device.close(); device = null; } } catch (Exception ignored) {}
        try { if (reader != null) { reader.close(); reader = null; } } catch (Exception ignored) {}
        captured = false;
    }
}
