package art.qelora.welltrack.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 与小程序 utils/api.js 等价的接口层:
 *  - 默认接口地址 https://qelora.art, 可在「我的」里改 (存 welltrack_api)
 *  - 首次请求自动用本地匿名会话 POST /api/v1/auth/local 换 token (存 welltrack_token)
 *  - 遇 401 自动清 token、重新建会话并重试一次
 */
public class ApiClient {
    public static final String API_DEFAULT = "https://qelora.art";
    /** 健康智能体服务 (Cloudflare Worker, 与主后端是独立的一套会话) */
    public static final String AGENT_BASE = "https://welltrack-api.sry602.workers.dev";
    private static final String AGENT_TOKEN_KEY = "welltrack_agent_token";
    private static ApiClient instance;

    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onOk(JSONObject data, int status);
        void onError(String message);
    }

    public interface RawCallback {
        void onOk(String text, int status);
        void onError(String message);
    }

    private ApiClient(Context ctx) {
        prefs = ctx.getSharedPreferences("welltrack", Context.MODE_PRIVATE);
    }

    public static synchronized ApiClient get(Context ctx) {
        if (instance == null) instance = new ApiClient(ctx.getApplicationContext());
        return instance;
    }

    public String getApiBase() {
        String saved = prefs.getString("welltrack_api", "");
        if (saved == null || saved.isEmpty()
                || saved.startsWith("http://127.0.0.1") || saved.startsWith("http://localhost")) {
            return API_DEFAULT;
        }
        return saved.replaceAll("/$", "");
    }

    public void setApiBase(String base) {
        prefs.edit().putString("welltrack_api", base).apply();
        prefs.edit().remove("welltrack_token").apply();
    }

    public String getToken() { return prefs.getString("welltrack_token", ""); }

    /** JSON 请求 (自动建会话 + 401 重试), 回调在主线程 */
    public void request(String path, String method, JSONObject body, Callback cb) {
        executor.execute(() -> {
            try {
                JSONObject data = requestSync(path, method, body, true);
                main.post(() -> cb.onOk(data, 200));
            } catch (Exception e) {
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    /** 文本请求 (导出 CSV/JSON 用) */
    public void requestText(String path, RawCallback cb) {
        executor.execute(() -> {
            try {
                String text = requestTextSync(path, true);
                main.post(() -> cb.onOk(text, 200));
            } catch (Exception e) {
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    public String getAgentToken() {
        String t = prefs.getString(AGENT_TOKEN_KEY, "");
        return t == null ? "" : t;
    }

    /**
     * 智能体对话 (POST AGENT_BASE/api/v1/agent/chat):
     * 先在 Worker 侧建匿名会话 (token 存 welltrack_agent_token), 401 清 token 重建重试一次。
     * history 为既往轮次 [{role, content}...], context 为 App 组装的用户数据摘要字符串。
     */
    public void agentChat(String message, JSONArray history, String context, Callback cb) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("message", message);
                body.put("history", history == null ? new JSONArray() : history);
                body.put("context", context == null ? "" : context);
                JSONObject data = agentChatSync(body, true);
                main.post(() -> cb.onOk(data, 200));
            } catch (Exception e) {
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    private void ensureAgentSession() throws Exception {
        if (!getAgentToken().isEmpty()) return;
        String text = rawRequestTo(AGENT_BASE, "/api/v1/auth/local", "POST", new JSONObject(), null);
        JSONObject obj = new JSONObject(text);
        prefs.edit().putString(AGENT_TOKEN_KEY, obj.optString("token", "")).apply();
    }

    private JSONObject agentChatSync(JSONObject body, boolean retry) throws Exception {
        return agentCallSync("/api/v1/agent/chat", "POST", body, retry);
    }

    /** 读取本周 AI 计划 (GET /api/v1/agent/plan): Worker 有缓存时直返, 无缓存返回 404 */
    public void agentGetPlan(Callback cb) {
        executor.execute(() -> {
            try {
                JSONObject data = agentCallSync("/api/v1/agent/plan", "GET", null, true);
                main.post(() -> cb.onOk(data, 200));
            } catch (Exception e) {
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    /** 生成本周 AI 计划 (POST /api/v1/agent/plan): context 为数据摘要, force=true 忽略缓存重生成 */
    public void agentPlan(String context, boolean force, Callback cb) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("context", context == null ? "" : context);
                body.put("force", force);
                JSONObject data = agentCallSync("/api/v1/agent/plan", "POST", body, true);
                main.post(() -> cb.onOk(data, 200));
            } catch (Exception e) {
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    /* ---------- 饮食记录 (主后端 /api/v1/diet, 与 Worker 契约一致) ---------- */

    /** 拉取某日饮食记录: 返回 {date, entries:[...], total_kcal, count} */
    public void dietList(String date, Callback cb) {
        request("/api/v1/diet?date=" + date, "GET", null, cb);
    }

    /** 新增一条饮食记录: body {entry_date, name, grams, calories_kcal, source} */
    public void dietAdd(JSONObject body, Callback cb) {
        request("/api/v1/diet", "POST", body, cb);
    }

    /** 删除一条饮食记录 (后端 DELETE /api/v1/diet/{id}) */
    public void dietDelete(int id, Callback cb) {
        request("/api/v1/diet/" + id, "DELETE", null, cb);
    }

    /**
     * 拍照识别热量 (POST AGENT_BASE/api/v1/agent/food-photo):
     * body {image_base64}, 返回 {items:[{name,grams,calories_kcal}], total_kcal, note, model}。
     * 走 agent 独立会话通道 (60 秒超时); 视觉模型未开通时后端返回错误, 由页面友好兜底。
     */
    public void agentFoodPhoto(String base64, Callback cb) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("image_base64", base64 == null ? "" : base64);
                JSONObject data = agentCallSync("/api/v1/agent/food-photo", "POST", body, true);
                main.post(() -> cb.onOk(data, 200));
            } catch (Exception e) {
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    /** 智能体域通用调用: 独立会话 + 401 重建重试一次 + 60 秒读超时 (大模型生成较慢) */
    private JSONObject agentCallSync(String path, String method, JSONObject body, boolean retry) throws Exception {
        ensureAgentSession();
        try {
            String text = rawRequestTo(AGENT_BASE, path, method, body, getAgentToken(), 60000);
            return new JSONObject(text);
        } catch (HttpError e) {
            if (e.status == 401 && retry) {
                prefs.edit().remove(AGENT_TOKEN_KEY).apply();
                ensureAgentSession();
                return agentCallSync(path, method, body, false);
            }
            throw e;
        }
    }

    private String friendly(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? "请求失败" : m;
    }

    private void ensureSession() throws Exception {
        if (!getToken().isEmpty()) return;
        String text = rawRequest("/api/v1/auth/local", "POST", new JSONObject(), null);
        JSONObject obj = new JSONObject(text);
        prefs.edit().putString("welltrack_token", obj.optString("token", "")).apply();
    }

    private JSONObject requestSync(String path, String method, JSONObject body, boolean retry) throws Exception {
        ensureSession();
        try {
            String text = rawRequest(path, method, body, getToken());
            return new JSONObject(text);
        } catch (HttpError e) {
            if (e.status == 401 && retry) {
                prefs.edit().remove("welltrack_token").apply();
                ensureSession();
                return requestSync(path, method, body, false);
            }
            throw e;
        }
    }

    private String requestTextSync(String path, boolean retry) throws Exception {
        ensureSession();
        try {
            return rawRequest(path, "GET", null, getToken());
        } catch (HttpError e) {
            if (e.status == 401 && retry) {
                prefs.edit().remove("welltrack_token").apply();
                ensureSession();
                return requestTextSync(path, false);
            }
            throw e;
        }
    }

    static class HttpError extends Exception {
        final int status;
        HttpError(int status, String detail) { super("HTTP " + status + (detail.isEmpty() ? "" : ": " + detail)); this.status = status; }
    }

    private String rawRequest(String path, String method, JSONObject body, String token) throws Exception {
        return rawRequestTo(getApiBase(), path, method, body, token);
    }

    private String rawRequestTo(String base, String path, String method, JSONObject body, String token) throws Exception {
        return rawRequestTo(base, path, method, body, token, 20000);
    }

    private String rawRequestTo(String base, String path, String method, JSONObject body, String token,
                                int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        if (status < 200 || status >= 300) {
            String detail = "";
            try { detail = new JSONObject(text).optString("detail", ""); } catch (Exception ignored) {}
            throw new HttpError(status, detail);
        }
        return text;
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
