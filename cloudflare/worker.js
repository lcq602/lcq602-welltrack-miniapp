// WellTrack 健康管理 API — Cloudflare Worker + D1 版
// 与 FastAPI 版 (backend/) 接口契约完全一致: 路径、字段、状态码、错误格式 {"detail": "..."}
// 绑定: DB (D1), AI (Workers AI)
// 变量: MIHEALTH_ENABLED / MIHEALTH_TOKEN_KEY / WECHAT_APP_ID / WECHAT_APP_SECRET / AI_MODEL
// 说明: 小米直连登录协议已移植 (账号密码登录/二次验证/免密刷新/签名加密同步);
//       Cron 每 10 分钟为已绑定用户自动增量同步近 2 天数据, 无需手动点击。

const TZ = "Asia/Shanghai";

// ---------- 基础工具 ----------
function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...corsHeaders(), ...extraHeaders },
  });
}
function err(status, detail) { return json({ detail }, status); }
function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,DELETE,OPTIONS",
    "Access-Control-Allow-Headers": "Authorization,Content-Type",
  };
}
async function sha256Hex(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function randomHex(bytes) {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return [...a].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function randomToken() {
  const a = new Uint8Array(32);
  crypto.getRandomValues(a);
  return btoa(String.fromCharCode(...a)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
// 按 Asia/Shanghai 计算日期 (与原后端服务器本地日期口径一致)
function todayStr(offsetDays = 0) {
  const now = new Date(Date.now() + offsetDays * 86400000);
  return new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}
function addDays(dateStr, days) {
  const d = new Date(dateStr + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
function parseJson(text, fallback) {
  try { return JSON.parse(text || ""); } catch { return fallback; }
}
function isDateStr(s) { return typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && !isNaN(Date.parse(s + "T00:00:00Z")); }
function round1(v) { return Math.round(v * 10) / 10; }

// ---------- 鉴权 ----------
async function userId(request, env) {
  const auth = request.headers.get("Authorization") || "";
  if (!auth.startsWith("Bearer ")) throw httpErr(401, "请先创建或恢复本地会话");
  const digest = await sha256Hex(auth.slice(7));
  const row = await env.DB.prepare("SELECT user_id FROM sessions WHERE token_hash=?").bind(digest).first();
  if (!row) throw httpErr(401, "会话无效");
  return row.user_id;
}
function httpErr(status, detail) { const e = new Error(detail); e.status = status; e.detail = detail; return e; }

// ---------- 资料 ----------
async function getProfile(env, uid) {
  const row = await env.DB.prepare(
    "SELECT height_cm,current_weight,target_weight,start_date,goal,target_date,weekly_plan,reminder_settings FROM profile_versions WHERE user_id=? ORDER BY id DESC LIMIT 1"
  ).bind(uid).first();
  if (!row) return null;
  return {
    height_cm: row.height_cm, current_weight: row.current_weight, target_weight: row.target_weight,
    start_date: row.start_date, goal: row.goal, target_date: row.target_date,
    weekly_plan: parseJson(row.weekly_plan, {}), reminder_settings: parseJson(row.reminder_settings, {}),
    configured: row.height_cm !== null && row.height_cm !== undefined,
  };
}
function recordOut(row) {
  const r = { ...row };
  delete r.user_id;
  r.habits = parseJson(typeof r.habits === "string" ? r.habits : "[]", []);
  return r;
}
function habitsList(v) {
  if (Array.isArray(v)) return v;
  const p = parseJson(v || "[]", []);
  return Array.isArray(p) ? p : [];
}

// ---------- 路由 ----------
export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/$/, "") || "/";
    try {
      const route = `${request.method} ${path}`;
      if (route === "GET /health") return json({ ok: true });
      if (route === "POST /api/v1/auth/local") return await authLocal(request, env);
      if (route === "POST /api/v1/auth/wechat") return await authWechat(request, env);

      // 以下均需会话
      const uid = await userId(request, env);
      const dietDel = path.match(/^\/api\/v1\/diet\/(\d+)$/);
      if (request.method === "DELETE" && dietDel) return await deleteDiet(env, uid, Number(dietDel[1]));
      switch (route) {
        case "GET /api/v1/profile": return json(await getProfile(env, uid));
        case "POST /api/v1/profile": return await saveProfile(request, env, uid);
        case "GET /api/v1/dashboard": return json(await dashboard(env, uid));
        case "GET /api/v1/records": return json(await listRecords(env, uid, url));
        case "POST /api/v1/records": return await createRecord(request, env, uid);
        case "GET /api/v1/exercises": return json(await listExercises(env, uid, url));
        case "POST /api/v1/exercises": return await createExercise(request, env, uid);
        case "GET /api/v1/analytics": return json(await analytics(env, uid, url));
        case "GET /api/v1/export.csv": return await exportCsv(env, uid);
        case "GET /api/v1/backup": return json(await backup(env, uid));
        case "POST /api/v1/import": return await importBackup(request, env, uid);
        case "GET /api/v1/integrations/mihealth/status": return json(await mihealthStatus(env, uid));
        case "POST /api/v1/integrations/mihealth/connect": return await mihealthConnect(request, env, uid);
        case "POST /api/v1/integrations/mihealth/verify": return await mihealthVerify(request, env, uid);
        case "POST /api/v1/integrations/mihealth/sync": return await mihealthSync(request, env, uid);
        case "DELETE /api/v1/integrations/mihealth/connection": return await mihealthUnbind(env, uid);
        case "GET /api/v1/diet": return await listDiet(env, uid, url);
        case "POST /api/v1/diet": return await createDiet(request, env, uid);
        case "POST /api/v1/water": return await createWater(request, env, uid);
        case "POST /api/v1/agent/food-photo": return await foodPhoto(request, env, uid);
        case "POST /api/v1/agent/chat": return await agentChat(request, env, uid);
        case "GET /api/v1/agent/daily-insight": return await agentDailyInsight(env, uid);
        case "GET /api/v1/agent/plan": return await agentPlan(request, env, uid);
        case "POST /api/v1/agent/plan": return await agentPlan(request, env, uid);
        default: return err(404, "接口不存在");
      }
    } catch (e) {
      if (e && e.status) return err(e.status, e.detail || e.message);
      return err(500, "服务内部错误: " + (e && e.message ? e.message : String(e)));
    }
  },
  async scheduled(event, env, ctx) {
    ctx.waitUntil(miScheduledSync(env));
  },
};

// ---------- 认证 ----------
async function createSession(env, uid) {
  const token = randomToken();
  const digest = await sha256Hex(token);
  await env.DB.prepare("INSERT INTO sessions(token_hash,user_id) VALUES(?,?)").bind(digest, uid).run();
  return token;
}
async function authLocal(request, env) {
  if ((env.LOCAL_AUTH_ENABLED || "true") === "false") throw httpErr(404, "本地登录已关闭");
  const uid = randomHex(16);
  await env.DB.prepare("INSERT INTO users(id) VALUES(?)").bind(uid).run();
  const token = await createSession(env, uid);
  return json({ token, configured: false });
}
async function authWechat(request, env) {
  const body = await request.json().catch(() => ({}));
  const code = body.code;
  if (typeof code !== "string" || !code || code.length > 256) throw httpErr(422, "微信登录凭证无效");
  if (!env.WECHAT_APP_ID || !env["WECHAT_APP_SECRET"]) throw httpErr(503, "微信登录尚未配置；本地开发请使用本地会话");
  const q = new URLSearchParams({ appid: env.WECHAT_APP_ID, secret: env["WECHAT_APP_SECRET"], js_code: code, grant_type: "authorization_code" });
  let result;
  try {
    const resp = await fetch("https://api.weixin.qq.com/sns/jscode2session?" + q.toString());
    result = await resp.json();
  } catch { throw httpErr(502, "微信登录服务暂时不可用"); }
  const openid = result.openid;
  if (!openid || result.errcode) throw httpErr(401, "微信登录凭证校验失败");
  let row = await env.DB.prepare("SELECT id FROM users WHERE wechat_openid=?").bind(openid).first();
  let uid;
  if (row) uid = row.id;
  else {
    uid = randomHex(16);
    await env.DB.prepare("INSERT INTO users(id,wechat_openid) VALUES(?,?)").bind(uid, openid).run();
  }
  const token = await createSession(env, uid);
  const profile = await getProfile(env, uid);
  return json({ token, configured: profile !== null });
}

// ---------- 资料保存 ----------
const GOALS = ["weight_loss", "fat_loss", "maintenance", "healthy_habits"];
async function saveProfile(request, env, uid) {
  const b = await request.json().catch(() => ({}));
  const num = (v, lo, hi, name) => {
    if (typeof v !== "number" || !(v > lo && v <= hi)) throw httpErr(422, `${name} 超出允许范围`);
    return v;
  };
  const height = num(b.height_cm, 80, 250, "身高");
  const cur = num(b.current_weight, 20, 500, "当前体重");
  const tgt = num(b.target_weight, 20, 500, "目标体重");
  if (!isDateStr(b.start_date)) throw httpErr(422, "开始日期无效");
  if (!GOALS.includes(b.goal)) throw httpErr(422, "目标类型无效");
  if (b.target_date !== null && b.target_date !== undefined && !isDateStr(b.target_date)) throw httpErr(422, "目标日期无效");
  const weekly = b.weekly_plan && typeof b.weekly_plan === "object" ? b.weekly_plan : {};
  if (Object.keys(weekly).length > 7 || Object.entries(weekly).some(([k, v]) => String(k).length > 12 || String(v).length > 40))
    throw httpErr(422, "每周安排最多 7 项，每项文字不超过 40 个字符");
  const reminder = b.reminder_settings && typeof b.reminder_settings === "object" ? b.reminder_settings : {};
  await env.DB.prepare(
    "INSERT INTO profile_versions(user_id,height_cm,current_weight,target_weight,start_date,goal,target_date,weekly_plan,reminder_settings) VALUES(?,?,?,?,?,?,?,?,?)"
  ).bind(uid, height, cur, tgt, b.start_date, b.goal, b.target_date || null, JSON.stringify(weekly), JSON.stringify(reminder)).run();
  return json(await getProfile(env, uid), 201);
}

// ---------- 记录 ----------
async function createRecord(request, env, uid) {
  const b = await request.json().catch(() => ({}));
  if (!isDateStr(b.record_date)) throw httpErr(422, "记录日期无效");
  const optNum = (v, lo, hi, exclusiveLo, name) => {
    if (v === null || v === undefined) return null;
    if (typeof v !== "number" || (exclusiveLo ? !(v > lo && v <= hi) : !(v >= lo && v <= hi))) throw httpErr(422, `${name} 超出允许范围`);
    return v;
  };
  const weight = optNum(b.weight, 20, 500, true, "体重");
  const waist = optNum(b.waist_cm, 20, 300, true, "腰围");
  const water = optNum(b.water_ml, 0, 10000, false, "饮水");
  const sleep = optNum(b.sleep_hours, 0, 24, false, "睡眠");
  const diet = typeof b.diet === "string" ? b.diet.slice(0, 2000) : "";
  const note = typeof b.note === "string" ? b.note.slice(0, 2000) : "";
  let habits = Array.isArray(b.habits) ? b.habits.filter((h) => typeof h === "string").slice(0, 30) : [];
  if (habits.some((h) => h.length > 50)) throw httpErr(422, "习惯标签不能超过 50 个字符");
  habits = [...new Set(habits)];
  try {
    const res = await env.DB.prepare(
      "INSERT INTO daily_records(user_id,record_date,weight,waist_cm,diet,water_ml,sleep_hours,habits,note) VALUES(?,?,?,?,?,?,?,?,?)"
    ).bind(uid, b.record_date, weight, waist, diet, water, sleep, JSON.stringify(habits), note).run();
    const row = await env.DB.prepare("SELECT * FROM daily_records WHERE id=? AND user_id=?").bind(res.meta.last_row_id, uid).first();
    return json(recordOut(row), 201);
  } catch (e) {
    if (String(e && e.message).includes("UNIQUE")) throw httpErr(409, "该日期已有记录，请编辑现有记录");
    throw e;
  }
}

const WATER_GOAL_ML = 2000;

// ---------- 快捷饮水 (water_logs 累加, 不写 daily_records, 不影响同日手工记录) ----------
async function waterTotals(env, uid, startStr, endStr) {
  const { results } = await env.DB.prepare(
    "SELECT record_date, COALESCE(SUM(amount_ml),0) AS total FROM water_logs WHERE user_id=? AND record_date BETWEEN ? AND ? GROUP BY record_date"
  ).bind(uid, startStr, endStr).all();
  const out = {};
  for (const r of results) out[r.record_date] = r.total;
  return out;
}

function mergeWater(records, totals) {
  for (const rec of records) {
    const extra = totals[rec.record_date] || 0;
    if (extra) rec.water_ml = (rec.water_ml === null || rec.water_ml === undefined ? 0 : rec.water_ml) + extra;
  }
  return records;
}

async function createWater(request, env, uid) {
  const b = await request.json().catch(() => ({}));
  if (!isDateStr(b.record_date)) throw httpErr(422, "记录日期无效");
  if (!Number.isInteger(b.amount_ml) || b.amount_ml < 10 || b.amount_ml > 2000) throw httpErr(422, "单次饮水量需在 10~2000 ml 之间");
  await env.DB.prepare(
    "INSERT INTO water_logs(user_id,record_date,amount_ml) VALUES(?,?,?)"
  ).bind(uid, b.record_date, b.amount_ml).run();
  const base = await env.DB.prepare(
    "SELECT water_ml FROM daily_records WHERE user_id=? AND record_date=?"
  ).bind(uid, b.record_date).first();
  const logged = await env.DB.prepare(
    "SELECT COALESCE(SUM(amount_ml),0) AS total FROM water_logs WHERE user_id=? AND record_date=?"
  ).bind(uid, b.record_date).first();
  const total = (base && base.water_ml !== null && base.water_ml !== undefined ? base.water_ml : 0) + (logged ? logged.total : 0);
  return json({ record_date: b.record_date, added_ml: b.amount_ml, water_ml: total,
    goal_ml: WATER_GOAL_ML, progress_pct: Math.min(100, Math.round(total / WATER_GOAL_ML * 100)) }, 201);
}

async function miByDay(env, uid, startStr, endStrExclusive) {
  const { results } = await env.DB.prepare(
    "SELECT substr(recorded_at,1,10) AS day, metric, recorded_at, value, unit, payload FROM mihealth_imports WHERE user_id=? AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at ASC"
  ).bind(uid, startStr + "T00:00:00", endStrExclusive + "T00:00:00").all();
  const byDay = {};
  for (const r of results) {
    const day = r.day;
    if (!byDay[day]) byDay[day] = { weight: null, sleep_hours: null, steps: 0, calories: 0, workout_minutes: 0, has_data: false };
    const d = byDay[day], val = r.value;
    if (val === null || val === undefined) continue;
    if (r.metric === "weight") { d.weight = val; d.has_data = true; }
    else if (r.metric === "sleep_hours") { d.sleep_hours = val; d.has_data = true; }
    else if (r.metric === "steps") { d.steps += Math.trunc(val); d.has_data = true; }
    else if (r.metric === "calories") { d.calories += round1(val); d.has_data = true; }
    else if (r.metric === "workout_minutes") { d.workout_minutes += round1(val); d.has_data = true; }
  }
  return byDay;
}

async function listRecords(env, uid, url) {
  const today = todayStr();
  const start = url.searchParams.get("start") || addDays(today, -365);
  const end = url.searchParams.get("end") || today;
  const limit = Math.min(366, Math.max(1, parseInt(url.searchParams.get("limit") || "100", 10) || 100));
  const offset = Math.max(0, parseInt(url.searchParams.get("offset") || "0", 10) || 0);
  const { results: manualRows } = await env.DB.prepare(
    "SELECT * FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date DESC"
  ).bind(uid, start, end).all();
  const mi = await miByDay(env, uid, start, addDays(end, 1));
  const byDay = {};
  for (const r of manualRows) {
    const rec = recordOut(r);
    const day = rec.record_date;
    const m = mi[day];
    rec.mihealth_weight = m ? m.weight : null;
    rec.mihealth_sleep_hours = m ? m.sleep_hours : null;
    rec.steps = m && m.steps > 0 ? m.steps : null;
    rec.calories = m && m.calories > 0 ? round1(m.calories) : null;
    rec.workout_minutes = m && m.workout_minutes > 0 ? round1(m.workout_minutes) : null;
    rec.is_mihealth_only = false;
    if (rec.weight !== null && rec.weight !== undefined) rec.weight_source = "manual";
    else if (m && m.weight !== null) { rec.weight = m.weight; rec.weight_source = "mihealth"; }
    else rec.weight_source = null;
    if (rec.sleep_hours !== null && rec.sleep_hours !== undefined) rec.sleep_source = "manual";
    else if (m && m.sleep_hours !== null) { rec.sleep_hours = m.sleep_hours; rec.sleep_source = "mihealth"; }
    else rec.sleep_source = null;
    byDay[day] = rec;
  }
  for (const [day, m] of Object.entries(mi)) {
    if (!byDay[day] && m.has_data) {
      byDay[day] = {
        id: null, record_date: day,
        weight: m.weight, weight_source: m.weight !== null ? "mihealth" : null, mihealth_weight: m.weight,
        waist_cm: null, diet: "", water_ml: null,
        sleep_hours: m.sleep_hours, sleep_source: m.sleep_hours !== null ? "mihealth" : null, mihealth_sleep_hours: m.sleep_hours,
        habits: [], note: "",
        steps: m.steps > 0 ? m.steps : null,
        calories: m.calories > 0 ? round1(m.calories) : null,
        workout_minutes: m.workout_minutes > 0 ? round1(m.workout_minutes) : null,
        is_mihealth_only: true,
      };
    }
  }
  const water = await waterTotals(env, uid, start, end);
  mergeWater(Object.values(byDay), water);
  for (const [day, total] of Object.entries(water)) {
    if (!byDay[day] && total > 0) {
      byDay[day] = { id: null, record_date: day, weight: null, weight_source: null,
        mihealth_weight: null, waist_cm: null, diet: "", water_ml: total,
        sleep_hours: null, sleep_source: null, mihealth_sleep_hours: null,
        habits: [], note: "", steps: null, calories: null, workout_minutes: null,
        is_mihealth_only: false };
    }
  }
  const all = Object.values(byDay).sort((a, b) => (a.record_date < b.record_date ? 1 : -1));
  return all.slice(offset, offset + limit);
}

// ---------- 运动 ----------
async function listExercises(env, uid, url) {
  const today = todayStr();
  const start = url.searchParams.get("start") || addDays(today, -30);
  const end = url.searchParams.get("end") || addDays(today, 365);
  const { results } = await env.DB.prepare(
    "SELECT id,exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ? ORDER BY exercise_date DESC,id DESC LIMIT 500"
  ).bind(uid, start, end).all();
  return results;
}
async function createExercise(request, env, uid) {
  const b = await request.json().catch(() => ({}));
  if (!isDateStr(b.exercise_date)) throw httpErr(422, "运动日期无效");
  if (typeof b.kind !== "string" || b.kind.length < 1 || b.kind.length > 40) throw httpErr(422, "运动类型无效");
  if (!Number.isInteger(b.duration_minutes) || b.duration_minutes < 1 || b.duration_minutes > 1440) throw httpErr(422, "运动时长无效");
  const note = typeof b.note === "string" ? b.note.slice(0, 1000) : "";
  const res = await env.DB.prepare(
    "INSERT INTO exercises(user_id,exercise_date,kind,duration_minutes,note) VALUES(?,?,?,?,?)"
  ).bind(uid, b.exercise_date, b.kind, b.duration_minutes, note).run();
  const row = await env.DB.prepare("SELECT id,exercise_date,kind,duration_minutes,note FROM exercises WHERE id=? AND user_id=?").bind(res.meta.last_row_id, uid).first();
  return json(row, 201);
}

// ---------- 小米数据状态 (读模型, 与原 mihealth_status 一致) ----------
function miEnabled(env) { return (env.MIHEALTH_ENABLED || "false") === "true"; }
async function mihealthStatus(env, uid) {
  const keyReady = miEnabled(env) && Boolean(env.MIHEALTH_TOKEN_KEY);
  const conn = await env.DB.prepare("SELECT linked_at,last_synced_at FROM mihealth_connections WHERE user_id=?").bind(uid).first();
  const countRow = await env.DB.prepare("SELECT COUNT(*) AS n FROM mihealth_imports WHERE user_id=?").bind(uid).first();
  const { results: rows } = await env.DB.prepare(
    "SELECT metric,recorded_at,value,unit FROM mihealth_imports WHERE user_id=? AND metric<>'steps' AND metric<>'calories' ORDER BY recorded_at DESC LIMIT 200"
  ).bind(uid).all();
  const daySum = async (metric) => env.DB.prepare(
    "SELECT substr(recorded_at,1,10) AS day,SUM(value) AS value FROM mihealth_imports WHERE user_id=? AND metric=? GROUP BY substr(recorded_at,1,10) ORDER BY day DESC LIMIT 1"
  ).bind(uid, metric).first();
  const stepTotal = await daySum("steps");
  const calTotal = await daySum("calories");
  const today = todayStr();
  const todayVal = async (metric, agg) => env.DB.prepare(
    `SELECT ${agg}(value) AS s FROM mihealth_imports WHERE user_id=? AND metric=? AND substr(recorded_at,1,10)=?`
  ).bind(uid, metric, today).first();
  const tSteps = await todayVal("steps", "SUM");
  const tCal = await todayVal("calories", "SUM");
  const tSleep = await todayVal("sleep_hours", "MAX");
  const tWorkout = await todayVal("workout_minutes", "SUM");
  const latest = {
    weight: { recorded_at: null, value: null, unit: "kg" },
    sleep_hours: { recorded_at: null, value: null, unit: "h" },
    steps: { recorded_at: null, value: null, unit: "steps" },
    calories: { recorded_at: null, value: null, unit: "kcal" },
    workout_minutes: { recorded_at: null, value: null, unit: "min" },
  };
  for (const row of rows) {
    if (row.metric in latest && latest[row.metric].recorded_at === null) {
      latest[row.metric] = { recorded_at: row.recorded_at, value: row.value, unit: row.unit };
    }
  }
  if (stepTotal && stepTotal.value !== null) latest.steps = { recorded_at: stepTotal.day, value: Math.trunc(stepTotal.value), unit: "steps" };
  if (calTotal && calTotal.value !== null) latest.calories = { recorded_at: calTotal.day, value: round1(calTotal.value), unit: "kcal" };
  const todaySummary = {
    steps: tSteps && tSteps.s !== null ? Math.trunc(tSteps.s) : (latest.steps.value !== null ? Math.trunc(latest.steps.value) : 0),
    calories: tCal && tCal.s !== null ? round1(tCal.s) : (latest.calories.value !== null ? round1(latest.calories.value) : 0),
    sleep_hours: tSleep && tSleep.s !== null ? round1(tSleep.s) : (latest.sleep_hours.value !== null ? round1(latest.sleep_hours.value) : null),
    workout_minutes: tWorkout && tWorkout.s !== null ? round1(tWorkout.s) : (latest.workout_minutes.value !== null ? round1(latest.workout_minutes.value) : 0),
  };
  return {
    enabled: miEnabled(env), ready: keyReady,
    connected: Boolean(conn),
    linked_at: conn ? conn.linked_at : null,
    last_synced_at: conn ? conn.last_synced_at : null,
    import_count: countRow ? countRow.n : 0,
    latest, today: todaySummary, challenge: null,
  };
}
function miRequireReady(env) {
  if (!miEnabled(env)) throw httpErr(503, "小米连接未启用");
  if (!env.MIHEALTH_TOKEN_KEY) throw httpErr(503, "小米连接尚未配置");
}
async function mihealthConnect(request, env, uid) {
  miRequireReady(env);
  const b = await request.json().catch(() => ({}));
  const username = String(b.username || "").trim();
  const password = String(b.password || "");
  if (!username || !password) throw httpErr(422, "账号和密码不能为空");
  let session;
  try {
    session = await miConnect(username, password);
  } catch (e) {
    if (e instanceof MiChallenge) {
      if (!e.state) throw httpErr(428, e.message);
      const challengeId = randomToken();
      const expiresAt = Math.floor(Date.now() / 1000) + 600;
      const encState = await miEncryptSession(e.state, env.MIHEALTH_TOKEN_KEY);
      await env.DB.prepare("DELETE FROM mihealth_challenges WHERE user_id=?").bind(uid).run();
      await env.DB.prepare("INSERT INTO mihealth_challenges(challenge_id,user_id,encrypted_state,expires_at) VALUES(?,?,?,?)")
        .bind(challengeId, uid, encState, expiresAt).run();
      return json({ requires_verification: true, challenge_id: challengeId, hint: e.hint,
        method: e.state.method || "Phone", attempts_remaining: 5, expires_at: expiresAt }, 202);
    }
    if (e instanceof MiErr) throw httpErr(502, e.message);
    throw httpErr(502, "小米运动健康暂时无法连接，请稍后重试");
  }
  const encrypted = await miEncryptSession(session, env.MIHEALTH_TOKEN_KEY);
  await env.DB.prepare("INSERT INTO mihealth_connections(user_id,encrypted_session) VALUES(?,?) " +
    "ON CONFLICT(user_id) DO UPDATE SET encrypted_session=excluded.encrypted_session, linked_at=CURRENT_TIMESTAMP, last_synced_at=NULL")
    .bind(uid, encrypted).run();
  await env.DB.prepare("DELETE FROM mihealth_challenges WHERE user_id=?").bind(uid).run();
  return json({ connected: true, linked_at: miLocalDatetime(Date.now() / 1000) });
}
async function mihealthVerify(request, env, uid) {
  miRequireReady(env);
  const b = await request.json().catch(() => ({}));
  const challengeId = String(b.challenge_id || "");
  const code = String(b.code || "");
  if (!challengeId || !/^\d{4,10}$/.test(code)) throw httpErr(422, "验证码格式不正确");
  const now = Math.floor(Date.now() / 1000);
  const pending = await env.DB.prepare("SELECT encrypted_state,expires_at,attempts FROM mihealth_challenges WHERE challenge_id=? AND user_id=?")
    .bind(challengeId, uid).first();
  if (!pending) throw httpErr(404, "验证请求不存在或已完成");
  if (pending.expires_at <= now) {
    await env.DB.prepare("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?").bind(challengeId, uid).run();
    throw httpErr(410, "验证码已过期，请重新连接");
  }
  if (pending.attempts >= 5) {
    await env.DB.prepare("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?").bind(challengeId, uid).run();
    throw httpErr(429, "验证码尝试次数过多，请重新连接");
  }
  const attempts = pending.attempts + 1;
  await env.DB.prepare("UPDATE mihealth_challenges SET attempts=attempts+1 WHERE challenge_id=? AND user_id=?").bind(challengeId, uid).run();
  let session;
  try {
    const state = await miDecryptSession(pending.encrypted_state, env.MIHEALTH_TOKEN_KEY);
    session = await miVerifyChallenge(state, code);
  } catch (e) {
    if (e instanceof MiVerificationError) {
      const remaining = Math.max(0, 5 - attempts);
      if (remaining === 0) {
        await env.DB.prepare("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?").bind(challengeId, uid).run();
        throw httpErr(429, "验证码尝试次数过多，请重新连接");
      }
      throw httpErr(422, `验证码不正确，还可尝试 ${remaining} 次`);
    }
    if (e instanceof MiErr) throw httpErr(502, e.message);
    throw httpErr(502, "小米验证码验证暂时失败，请重试");
  }
  const encrypted = await miEncryptSession(session, env.MIHEALTH_TOKEN_KEY);
  await env.DB.prepare("INSERT INTO mihealth_connections(user_id,encrypted_session) VALUES(?,?) " +
    "ON CONFLICT(user_id) DO UPDATE SET encrypted_session=excluded.encrypted_session, linked_at=CURRENT_TIMESTAMP, last_synced_at=NULL")
    .bind(uid, encrypted).run();
  await env.DB.prepare("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?").bind(challengeId, uid).run();
  return json({ connected: true, linked_at: miLocalDatetime(Date.now() / 1000) });
}
async function miSyncUserData(env, uid, days) {
  const conn = await env.DB.prepare("SELECT encrypted_session FROM mihealth_connections WHERE user_id=?").bind(uid).first();
  if (!conn) throw httpErr(409, "请先绑定小米运动健康");
  let session = await miDecryptSession(conn.encrypted_session, env.MIHEALTH_TOKEN_KEY);
  const endMs = Date.now();
  const startMs = endMs - days * 86400000;
  let readings;
  try {
    readings = await miReadMetrics(session, startMs, endMs);
  } catch (e) {
    if (e instanceof MiAuthError) {
      session = await miRefreshSession(session);
      readings = await miReadMetrics(session, startMs, endMs);
    } else throw e;
  }
  const current = await env.DB.prepare("SELECT encrypted_session FROM mihealth_connections WHERE user_id=?").bind(uid).first();
  if (!current) throw httpErr(409, "小米连接已解除，请重新绑定");
  const encrypted = await miEncryptSession(session, env.MIHEALTH_TOKEN_KEY);
  await env.DB.prepare("UPDATE mihealth_connections SET encrypted_session=?, last_synced_at=CURRENT_TIMESTAMP WHERE user_id=?")
    .bind(encrypted, uid).run();
  let imported = 0;
  if (readings.length) {
    const stmt = env.DB.prepare("INSERT OR IGNORE INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit,payload) VALUES(?,?,?,?,?,?,?)");
    const results = await env.DB.batch(readings.map((r) =>
      stmt.bind(uid, r.source_id, r.metric, r.recorded_at, r.value, r.unit, JSON.stringify(r.payload))));
    imported = results.reduce((acc, r) => acc + (r.meta && r.meta.changes ? r.meta.changes : 0), 0);
  }
  return { imported, received: readings.length, days, synced_at: miLocalDatetime(Date.now() / 1000) };
}
async function mihealthSync(request, env, uid) {
  miRequireReady(env);
  const b = await request.json().catch(() => ({}));
  let days = parseInt(b.days !== undefined ? b.days : 14, 10);
  if (!isFinite(days) || days < 1) days = 14;
  if (days > 90) days = 90;
  try {
    return json(await miSyncUserData(env, uid, days));
  } catch (e) {
    if (e && e.status) throw e;
    if (e instanceof MiErr) throw httpErr(502, e.message);
    throw httpErr(502, "小米运动健康暂时无法同步，请稍后重试");
  }
}
// Cron 每 10 分钟触发: 为所有已绑定用户增量同步近 2 天数据 (去重入库, 单个用户失败不影响其他用户)
async function miScheduledSync(env) {
  if (!miEnabled(env) || !env.MIHEALTH_TOKEN_KEY) return;
  const { results } = await env.DB.prepare("SELECT user_id FROM mihealth_connections").all();
  for (const row of results || []) {
    try { await miSyncUserData(env, row.user_id, 2); } catch (e) { /* 隔离单个用户失败 */ }
  }
}
async function mihealthUnbind(env, uid) {
  await env.DB.prepare("DELETE FROM mihealth_connections WHERE user_id=?").bind(uid).run();
  await env.DB.prepare("DELETE FROM mihealth_challenges WHERE user_id=?").bind(uid).run();
  return json({ ok: true });
}

// ---------- 首页汇总 ----------
function mergeMiWeights(records, importedRows) {
  const byDate = {};
  for (const item of records) byDate[item.record_date] = item;
  for (const row of importedRows) {
    const day = row.record_date;
    let item = byDate[day];
    if (!item) {
      item = { record_date: day, weight: null, waist_cm: null, diet: "", water_ml: null, sleep_hours: null, habits: [], note: "" };
      byDate[day] = item;
    }
    item.weight = row.value;
    item.weight_source = "mihealth";
  }
  for (const item of records) {
    if (item.weight_source === undefined) item.weight_source = item.weight !== null && item.weight !== undefined ? "manual" : null;
  }
  return Object.keys(byDate).sort().map((d) => byDate[d]);
}

async function dashboard(env, uid) {
  const p = await getProfile(env, uid);
  const today = todayStr();
  const start = addDays(today, -6);
  const { results: rows } = await env.DB.prepare(
    "SELECT * FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date"
  ).bind(uid, start, today).all();
  const exRow = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ?"
  ).bind(uid, start, today).first();
  const { results: importedWeights } = await env.DB.prepare(
    "SELECT substr(recorded_at,1,10) AS record_date,recorded_at,value FROM mihealth_imports WHERE user_id=? AND metric='weight' AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at"
  ).bind(uid, start + "T00:00:00", addDays(today, 1) + "T00:00:00").all();
  const latestImportedWeight = await env.DB.prepare(
    "SELECT recorded_at,value FROM mihealth_imports WHERE user_id=? AND metric='weight' ORDER BY recorded_at DESC,id DESC LIMIT 1"
  ).bind(uid).first();
  const manualRecords = rows.map(recordOut);
  const records = mergeMiWeights(manualRecords, importedWeights);
  mergeWater(records, await waterTotals(env, uid, start, today));
  const waterBase = rows.find((r) => r.record_date === today);
  const waterLogged = await env.DB.prepare(
    "SELECT COALESCE(SUM(amount_ml),0) AS total FROM water_logs WHERE user_id=? AND record_date=?"
  ).bind(uid, today).first();
  const waterTodayMl = (waterBase && waterBase.water_ml !== null && waterBase.water_ml !== undefined ? waterBase.water_ml : 0)
    + (waterLogged ? waterLogged.total : 0);
  const waterToday = { water_ml: waterTodayMl, goal_ml: WATER_GOAL_ML,
    progress_pct: Math.min(100, Math.round(waterTodayMl / WATER_GOAL_ML * 100)) };
  let latestManualWeight = null;
  for (let i = manualRecords.length - 1; i >= 0; i--) {
    if (manualRecords[i].weight !== null && manualRecords[i].weight !== undefined) { latestManualWeight = manualRecords[i].weight; break; }
  }
  const latestWeight = latestImportedWeight ? latestImportedWeight.value
    : (latestManualWeight !== null ? latestManualWeight : (p ? p.current_weight : null));
  const weightSource = latestImportedWeight ? "mihealth" : (latestManualWeight !== null ? "manual" : "profile");
  const bmi = latestWeight && p && p.height_cm ? round1(latestWeight / Math.pow(p.height_cm / 100, 2)) : null;
  const delta = p && p.current_weight && p.target_weight ? p.current_weight - p.target_weight : 0;
  const achieved = p && latestWeight ? p.current_weight - latestWeight : 0;
  const progress = delta ? Math.max(0, Math.min(100, Math.round(achieved / delta * 100))) : 100;
  const goal = p ? p.goal : "weight_loss";
  const todosMap = {
    weight_loss: ["记录体重趋势", "安排日常活动", "保持饮食与睡眠习惯"],
    fat_loss: ["安排力量训练", "记录腰围", "安排有氧运动"],
    maintenance: ["记录体重范围", "完成日常活动", "保持睡眠习惯"],
    healthy_habits: ["安排运动", "记录饮食", "记录饮水与睡眠", "减少久坐"],
  };
  const mi = await mihealthStatus(env, uid);
  const dietRow = await env.DB.prepare("SELECT COUNT(*) AS c, COALESCE(SUM(calories_kcal),0) AS s FROM diet_entries WHERE user_id=? AND entry_date=?").bind(uid, today).first();
  const dietToday = { total_kcal: round1(dietRow ? dietRow.s : 0), count: dietRow ? dietRow.c : 0 };
  return {
    profile: p, today, current_weight: latestWeight, current_weight_source: weightSource,
    target_weight: p ? p.target_weight : null,
    progress, bmi, bmi_label: "仅供参考，不用于诊断", records,
    exercise_count_7d: exRow ? exRow.n : 0, record_days_7d: manualRecords.length,
    weekly_completion: Math.min(100, Math.round(manualRecords.length / 7 * 100)),
    todos: todosMap[goal], configured: Boolean(p && p.configured), diet_today: dietToday,
    water_today: waterToday,
    mihealth: { connected: mi.connected, last_synced_at: mi.last_synced_at, today: mi.today, latest: mi.latest },
  };
}

// ---------- 趋势分析 ----------
async function analytics(env, uid, url) {
  const period = url.searchParams.get("period") || "week";
  if (!["week", "month", "calendar"].includes(period)) throw httpErr(422, "period 无效");
  const monthParam = url.searchParams.get("month");
  if (monthParam && !/^\d{4}-(0[1-9]|1[0-2])$/.test(monthParam)) throw httpErr(422, "month 格式无效");
  const anchor = monthParam ? monthParam + "-01" : todayStr();
  let start, end;
  if (period === "week") {
    const d = new Date(anchor + "T00:00:00Z");
    const mondayOffset = (d.getUTCDay() + 6) % 7;
    start = addDays(anchor, -mondayOffset);
    end = addDays(start, 6);
  } else {
    start = anchor.slice(0, 7) + "-01";
    const nextMonth = addDays(start, 32).slice(0, 7) + "-01";
    end = addDays(nextMonth, -1);
  }
  const { results: rows } = await env.DB.prepare(
    "SELECT record_date,weight,waist_cm,sleep_hours,habits FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date"
  ).bind(uid, start, end).all();
  const { results: importedWeights } = await env.DB.prepare(
    "SELECT substr(recorded_at,1,10) AS record_date,recorded_at,value FROM mihealth_imports WHERE user_id=? AND metric='weight' AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at"
  ).bind(uid, start + "T00:00:00", addDays(end, 1) + "T00:00:00").all();
  const { results: miMetrics } = await env.DB.prepare(
    "SELECT substr(recorded_at,1,10) AS day, metric, SUM(value) AS total_val, MAX(value) AS max_val FROM mihealth_imports WHERE user_id=? AND metric IN ('steps','calories','sleep_hours','workout_minutes') AND recorded_at>=? AND recorded_at<? GROUP BY substr(recorded_at,1,10), metric"
  ).bind(uid, start + "T00:00:00", addDays(end, 1) + "T00:00:00").all();
  const ex = await env.DB.prepare(
    "SELECT COUNT(*) AS n, SUM(duration_minutes) AS minutes FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ?"
  ).bind(uid, start, end).first();

  let values = rows.map((r) => ({ ...r }));
  values = mergeMiWeights(values, importedWeights);
  const byDayMetric = {};
  for (const r of miMetrics) {
    if (!byDayMetric[r.day]) byDayMetric[r.day] = {};
    byDayMetric[r.day][r.metric] = [r.total_val, r.max_val];
  }
  const existing = {};
  for (const r of values) existing[r.record_date] = r;
  for (const day of Object.keys(byDayMetric)) {
    if (!existing[day]) {
      const item = { record_date: day, weight: null, waist_cm: null, sleep_hours: null, habits: [] };
      existing[day] = item;
      values.push(item);
    }
  }
  values.sort((a, b) => (a.record_date < b.record_date ? -1 : 1));
  for (const r of values) {
    const mets = byDayMetric[r.record_date] || {};
    r.steps = mets.steps && mets.steps[0] !== null ? Math.trunc(mets.steps[0]) : 0;
    r.calories = mets.calories && mets.calories[0] !== null ? round1(mets.calories[0]) : 0;
    if ((r.sleep_hours === null || r.sleep_hours === undefined) && mets.sleep_hours) r.sleep_hours = round1(mets.sleep_hours[1]);
    r.workout_minutes = mets.workout_minutes && mets.workout_minutes[0] !== null ? round1(mets.workout_minutes[0]) : 0;
  }
  const sleeps = values.filter((r) => r.sleep_hours !== null && r.sleep_hours !== undefined).map((r) => r.sleep_hours);
  const stepsList = values.filter((r) => r.steps).map((r) => r.steps);
  const calsList = values.filter((r) => r.calories).map((r) => r.calories);
  const totalSteps = stepsList.reduce((a, b) => a + b, 0);
  const totalCalories = round1(calsList.reduce((a, b) => a + b, 0));
  const dayCount = Math.round((Date.parse(end + "T00:00:00Z") - Date.parse(start + "T00:00:00Z")) / 86400000) + 1;
  return {
    period, start, end,
    days: values.map((r) => ({ ...r, habits: habitsList(r.habits) })),
    exercise_count: ex ? ex.n || 0 : 0, exercise_minutes: ex ? ex.minutes || 0 : 0,
    average_sleep: sleeps.length ? round1(sleeps.reduce((a, b) => a + b, 0) / sleeps.length) : null,
    habit_completion: Math.round(values.filter((r) => habitsList(r.habits).length > 0).length / Math.max(1, dayCount) * 100),
    total_steps: totalSteps,
    average_steps: stepsList.length ? Math.trunc(totalSteps / stepsList.length) : 0,
    total_calories: totalCalories,
    average_calories: calsList.length ? round1(totalCalories / calsList.length) : 0,
  };
}

// ---------- 导出与备份 ----------
function csvCell(v) {
const text = v === null || v === undefined? "": String(v);
const safe = /^[=+\-@]/.test(text.trimStart())? "'" + text: text;
return /[",\n]/.test(safe)? '"' + safe.replace(/"/g, '""') + '"': safe;
}
async function exportCsv(env, uid) {
const { results: rows} = await env.DB.prepare(
"SELECT record_date,weight,waist_cm,diet,water_ml,sleep_hours,habits,note FROM daily_records WHERE user_id=? ORDER BY record_date"
).bind(uid).all();
const { results: exercises} = await env.DB.prepare(
"SELECT exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? ORDER BY exercise_date"
).bind(uid).all();
const { results: mihealth} = await env.DB.prepare(
"SELECT metric,recorded_at,value,unit,payload FROM mihealth_imports WHERE user_id=? ORDER BY recorded_at"
).bind(uid).all();
const waterMap = {};
for (const w of (await env.DB.prepare(
"SELECT record_date, COALESCE(SUM(amount_ml),0) AS total FROM water_logs WHERE user_id=? GROUP BY record_date"
).bind(uid).all()).results) waterMap[w.record_date] = w.total;
const lines = [["记录类型", "日期", "体重kg", "腰围cm", "饮食", "饮水ml", "睡眠小时", "习惯", "运动类型", "运动分钟", "卡路里kcal", "步数", "备注"].join(",")];
for (const r of rows) lines.push(["日常", r.record_date, r.weight, r.waist_cm, r.diet, (r.water_ml === null || r.water_ml === undefined ? (waterMap[r.record_date] || null) : r.water_ml + (waterMap[r.record_date] || 0)), r.sleep_hours, r.habits, "", "", "", "", r.note].map(csvCell).join(","));
for (const r of exercises) lines.push(["运动", r.exercise_date, "", "", "", "", "", "", r.kind, r.duration_minutes, "", "", r.note].map(csvCell).join(","));
for (const row of mihealth) {
const payload = parseJson(row.payload, {});
const day = String(row.recorded_at).slice(0, 10);
const metric = row.metric;
const weight = metric === "weight"? row.value: "";
const sleep = metric === "sleep_hours"? row.value: "";
const calories = metric === "calories"? row.value: "";
const steps = metric === "steps"? row.value: "";
const kind = payload.kind || metric;
const minutes = metric === "workout_minutes"? row.value: "";
const note = metric === "steps" || metric === "calories"? `${row.value} ${row.unit}`: "小米运动健康只读同步";
lines.push(["小米同步", day, weight, "", "", "", sleep, "", kind, minutes, calories, steps, note].map(csvCell).join(","));
}
return new Response(lines.join("\r\n"), {
status: 200,
headers: {
"Content-Type": "text/csv; charset=utf-8",
"Content-Disposition": "attachment; filename=welltrack-records.csv",
...corsHeaders(),
},
});
}
async function backup(env, uid) {
const { results: recRows} = await env.DB.prepare("SELECT * FROM daily_records WHERE user_id=? ORDER BY record_date").bind(uid).all();
const { results: exRows} = await env.DB.prepare("SELECT exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? ORDER BY exercise_date").bind(uid).all();
const { results: miRows} = await env.DB.prepare("SELECT source_id,metric,recorded_at,value,unit,payload FROM mihealth_imports WHERE user_id=? ORDER BY recorded_at").bind(uid).all();
const { results: dietRows} = await env.DB.prepare("SELECT entry_date,name,grams,calories_kcal,source FROM diet_entries WHERE user_id=? ORDER BY entry_date, id").bind(uid).all();
return {
version: 1,
profile: await getProfile(env, uid),
records: recRows.map(recordOut),
exercises: exRows,
mihealth_data: miRows.map((r) => ({...r, payload: parseJson(r.payload, {})})),
diet_entries: dietRows,
};
}
// 从旧后端 /api/v1/backup 的 JSON 导入 (数据迁移用): 幂等, 记录按日期 INSERT OR REPLACE
async function importBackup(request, env, uid) {
const b = await request.json().catch(() => ({}));
let counts = { profile: 0, records: 0, exercises: 0, mihealth_data: 0, diet_entries: 0};
if (b.profile && typeof b.profile === "object" && b.profile.height_cm) {
await env.DB.prepare(
"INSERT INTO profile_versions(user_id,height_cm,current_weight,target_weight,start_date,goal,target_date,weekly_plan,reminder_settings) VALUES(?,?,?,?,?,?,?,?,?)"
).bind(uid, b.profile.height_cm, b.profile.current_weight, b.profile.target_weight, b.profile.start_date, b.profile.goal,
b.profile.target_date || null, JSON.stringify(b.profile.weekly_plan || {}), JSON.stringify(b.profile.reminder_settings || {})).run();
counts.profile = 1;
}
if (Array.isArray(b.records)) {
for (const r of b.records) {
if (!isDateStr(r.record_date)) continue;
await env.DB.prepare(
"INSERT OR REPLACE INTO daily_records(user_id,record_date,weight,waist_cm,diet,water_ml,sleep_hours,habits,note) VALUES(?,?,?,?,?,?,?,?,?)"
).bind(uid, r.record_date, r.weight?? null, r.waist_cm?? null, r.diet || "", r.water_ml?? null, r.sleep_hours?? null,
JSON.stringify(Array.isArray(r.habits)? r.habits: []), r.note || "").run();
counts.records++;
}
}
if (Array.isArray(b.exercises)) {
for (const e of b.exercises) {
if (!isDateStr(e.exercise_date)) continue;
await env.DB.prepare("INSERT INTO exercises(user_id,exercise_date,kind,duration_minutes,note) VALUES(?,?,?,?,?)")
.bind(uid, e.exercise_date, String(e.kind || "运动").slice(0, 40), Math.trunc(e.duration_minutes) || 30, e.note || "").run();
counts.exercises++;
}
}
if (Array.isArray(b.mihealth_data)) {
for (const m of b.mihealth_data) {
if (!m.source_id ||!m.metric ||!m.recorded_at) continue;
const res = await env.DB.prepare(
"INSERT OR IGNORE INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit,payload) VALUES(?,?,?,?,?,?,?)"
).bind(uid, String(m.source_id), String(m.metric), String(m.recorded_at), m.value?? null, m.unit || "",
JSON.stringify(m.payload && typeof m.payload === "object"? m.payload: {})).run();
counts.mihealth_data += res.meta.changes? 1: 0;
}
if (counts.mihealth_data > 0) {
await env.DB.prepare(
"INSERT OR IGNORE INTO mihealth_connections(user_id,encrypted_session,last_synced_at) VALUES(?,'migrated',CURRENT_TIMESTAMP)"
).bind(uid).run();
}
}
if (Array.isArray(b.diet_entries)) {
for (const d of b.diet_entries) {
if (!isDateStr(d.entry_date) || !d.name) continue;
await env.DB.prepare("INSERT INTO diet_entries(user_id,entry_date,name,grams,calories_kcal,source) VALUES(?,?,?,?,?,?)")
.bind(uid, d.entry_date, String(d.name).slice(0, 60), d.grams ?? null, Number(d.calories_kcal) || 0, d.source === "photo" ? "photo" : "manual").run();
counts.diet_entries++;
}
}
return json({ ok: true, imported: counts});
}

// ---------- 饮食记录 ----------
async function createDiet(request, env, uid) {
  const b = await request.json().catch(() => ({}));
  if (!isDateStr(b.entry_date)) throw httpErr(422, "日期格式不正确");
  const name = typeof b.name === "string" ? b.name.trim() : "";
  if (!name || name.length > 60) throw httpErr(422, "食物名称不合法");
  const kcal = Number(b.calories_kcal);
  if (!Number.isFinite(kcal) || kcal < 0 || kcal > 10000) throw httpErr(422, "热量不合法");
  const grams = b.grams === null || b.grams === undefined ? null : Number(b.grams);
  if (grams !== null && (!Number.isFinite(grams) || grams <= 0 || grams > 5000)) throw httpErr(422, "重量不合法");
  const source = b.source === "photo" ? "photo" : "manual";
  const res = await env.DB.prepare("INSERT INTO diet_entries(user_id,entry_date,name,grams,calories_kcal,source) VALUES(?,?,?,?,?,?)")
    .bind(uid, b.entry_date, name, grams, kcal, source).run();
  const row = await env.DB.prepare("SELECT id,entry_date,name,grams,calories_kcal,source,created_at FROM diet_entries WHERE id=? AND user_id=?")
    .bind(res.meta.last_row_id, uid).first();
  return json(row, 201);
}

async function listDiet(env, uid, url) {
  const day = url.searchParams.get("date") || todayStr();
  if (!isDateStr(day)) throw httpErr(422, "日期格式不正确");
  const { results } = await env.DB.prepare("SELECT id,entry_date,name,grams,calories_kcal,source,created_at FROM diet_entries WHERE user_id=? AND entry_date=? ORDER BY id").bind(uid, day).all();
  const total = results.reduce((a, r) => a + (r.calories_kcal || 0), 0);
  return json({ date: day, entries: results, total_kcal: round1(total), count: results.length });
}

async function deleteDiet(env, uid, id) {
  const res = await env.DB.prepare("DELETE FROM diet_entries WHERE id=? AND user_id=?").bind(id, uid).run();
  if (!res.meta.changes) throw httpErr(404, "饮食记录不存在");
  return json({ ok: true });
}

// ---------- 拍照识别热量 (Workers AI 视觉模型) ----------
// 方案与 GitHub 主流开源实现一致: 视觉大模型一次调用输出结构化 JSON (食物/克数/热量),
// 总热量由服务端代码重算, 不信任模型给的总数; 结果仅作估算, 由用户在 App 内确认修改后才入账。
const FOOD_PHOTO_SYSTEM = `你是食物识别与营养估算助手。识别照片中的每一种食物, 估算每种的重量 (克) 与热量 (千卡)。
只输出一个 JSON 对象, 不要输出任何其他文字或 Markdown 标记:
{"items":[{"name":"食物名","grams":150,"calories_kcal":200}],"note":"一句话说明识别把握与不确定处"}
要求: 最多列 8 项; 完整食物按整体算一项 (如汉堡算一项, 不要把面包/肉饼/生菜拆开列), 只有照片中确实分开摆放的才分开列; 同种食物只出现一次, 绝不重复; 克数按照片中实际份量估算 (参考餐具大小); 热量按常见做法估算; 看不清的食物不要编造, 在 note 中说明。`;

async function foodPhoto(request, env, uid) {
  const b = await request.json().catch(() => ({}));
  const b64 = typeof b.image_base64 === "string" ? b.image_base64 : "";
  if (!b64 || b64.length < 1000) throw httpErr(422, "图片数据不完整");
  if (b64.length > 2_800_000) throw httpErr(413, "图片过大, 请压缩后重试");
  let bytes;
  try {
    const bin = atob(b64.replace(/\s/g, ""));
    bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  } catch (e) { throw httpErr(422, "图片解码失败"); }
  const model = env.VISION_MODEL || "@cf/meta/llama-3.2-11b-vision-instruct";
  const result = await env.AI.run(model, { image: Array.from(bytes), prompt: FOOD_PHOTO_SYSTEM + "\n\n请识别这张照片里的食物。", max_tokens: 900, temperature: 0.2 });
  const respVal = result && (result.response !== undefined ? result.response : (result.description || result.text));
  let raw = null;
  if (respVal && typeof respVal === "object") raw = respVal;
  else {
    const text = String(respVal || "");
    const start = text.indexOf("{"), end = text.lastIndexOf("}");
    if (start >= 0 && end > start) { try { raw = JSON.parse(text.slice(start, end + 1)); } catch (e) { raw = null; } }
    if (!raw && start >= 0) {
      // 输出被截断时抢救已完整生成的 item 对象
      const salvaged = [];
      const re = /\{"name"\s*:\s*"([^"]+)"\s*,\s*"grams"\s*:\s*(\d+)\s*,\s*"calories_kcal"\s*:\s*(\d+)\s*\}/g;
      let m;
      while ((m = re.exec(text)) !== null && salvaged.length < 12) {
        salvaged.push({ name: m[1], grams: Number(m[2]), calories_kcal: Number(m[3]) });
      }
      if (salvaged.length) raw = { items: salvaged, note: "识别结果较多, 已自动合并同类项, 请核对" };
    }
  }
  if (!raw || !Array.isArray(raw.items)) throw httpErr(502, "没有识别出食物, 换个角度或更近一点再拍");
  const seen = new Map();
  for (const it of raw.items) {
    if (!it || typeof it.name !== "string" || !it.name.trim()) continue;
    const key = String(it.name).trim().slice(0, 40);
    const norm = {
      name: key,
      grams: Number.isFinite(Number(it.grams)) ? Math.min(3000, Math.max(1, Math.round(Number(it.grams)))) : null,
      calories_kcal: Number.isFinite(Number(it.calories_kcal)) ? Math.min(5000, Math.max(0, Math.round(Number(it.calories_kcal)))) : 0,
    };
    const prev = seen.get(key);
    if (!prev || norm.calories_kcal > prev.calories_kcal) seen.set(key, norm);
  }
  const items = [...seen.values()].slice(0, 8);
  if (!items.length) throw httpErr(502, "没有识别出食物, 换个角度或更近一点再拍");
  const total = items.reduce((a, it) => a + it.calories_kcal, 0);
  return json({ items, total_kcal: total, note: typeof raw.note === "string" ? raw.note.slice(0, 200) : "", model });
}

// ---------- 智能体 (Workers AI) ----------
const AGENT_SYSTEM = `你是「轻衡健康」的健康智能体, 一位温和、专业、说人话的健康教练。
你会收到用户最近的健康数据 (资料、今日小米运动健康活力、本周记录与趋势)。
工作方式:
1. 先看数据再说话: 引用用户的真实数字 (步数、活动消耗、睡眠、运动、体重变化), 不要编造数据; 数据缺失时直说并给出补数据的方法。
2. 消耗达标口径: 每日活动消耗目标 400 千卡、步数 8000 步、运动 30 分钟、睡眠 8 小时。评价今天是否达标, 未达标时给出可落地的补法 (如晚饭后步行约多少分钟)。
3. 锻炼建议要具体: 项目、时长、强度, 并结合用户目标 (减重/减脂/维持/健康习惯) 与本周已运动次数; 睡眠不足时主动降低建议强度。
4. 体重问题看趋势不看单日波动, 节奏参考每周 0.2-1 kg。
5. 语气像朋友, 简洁分点, 不说教。涉及疾病、用药、疼痛时明确建议就医, 不做诊断。
6. 回答用中文, 一般不超过 300 字, 除非用户要求详细计划。`;

async function buildAgentContext(env, uid) {
const dash = await dashboard(env, uid);
const week = await analytics(env, uid, new URL("https://x/api/v1/analytics?period=week"));
const p = dash.profile || {};
const lines = [];
lines.push(`今天: ${dash.today}`);
if (dash.configured) {
lines.push(`用户目标: ${p.goal} | 身高 ${p.height_cm}cm | 当前体重 ${dash.current_weight}kg | 目标体重 ${p.target_weight}kg | 目标进度 ${dash.progress}% | BMI ${dash.bmi}`);
} else {
lines.push("用户尚未建立健康档案 (无身高/体重目标)。");
}
const t = dash.mihealth.today || {};
const connected = dash.mihealth.connected;
lines.push(connected
? `今日小米数据: 步数 ${t.steps}, 活动消耗 ${t.calories} 千卡, 睡眠 ${t.sleep_hours === null? "无": t.sleep_hours + " 小时"}, 运动 ${t.workout_minutes} 分钟 (已连接小米运动健康)`
: "今日小米数据: 未连接小米运动健康, 无步数/消耗/睡眠数据");
lines.push(`本周: 累计步数 ${week.total_steps} (日均 ${week.average_steps}), 累计活动消耗 ${week.total_calories} 千卡 (日均 ${week.average_calories}), 平均睡眠 ${week.average_sleep === null? "无数据": week.average_sleep + " 小时"}, 运动记录 ${week.exercise_count} 次共 ${week.exercise_minutes} 分钟`);
const weightDays = (week.days || []).filter((d) => d.weight!== null && d.weight!== undefined);
if (weightDays.length >= 2) {
const first = weightDays[0], last = weightDays[weightDays.length - 1];
lines.push(`本周体重: ${first.record_date} ${first.weight}kg → ${last.record_date} ${last.weight}kg`);
} else if (weightDays.length === 1) {
lines.push(`本周仅有一次体重记录: ${weightDays[0].weight}kg`);
}
const plan = p.weekly_plan || {};
const keys = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
const planToday = plan[keys[new Date(todayStr() + "T00:00:00Z").getUTCDay()]];
if (planToday) lines.push(`今日周计划安排: ${planToday}`);
return lines.join("\n");
}

async function runAi(env, messages) {
const model = env.AI_MODEL || "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const result = await env.AI.run(model, { messages, max_tokens: 900, temperature: 0.6});
const reply = (result && (result.response || result.text)) || "";
return { reply: String(reply).trim(), model};
}

async function agentChat(request, env, uid) {
const b = await request.json().catch(() => ({}));
const message = typeof b.message === "string"? b.message.trim(): "";
if (!message) throw httpErr(422, "消息不能为空");
if (message.length > 2000) throw httpErr(422, "消息过长");
const history = Array.isArray(b.history)? b.history.slice(-8).filter(
(m) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string"
).map((m) => ({ role: m.role, content: m.content.slice(0, 2000)})): [];
let context = "";
if (typeof b.context === "string" && b.context.trim()) context = b.context.trim().slice(0, 4000);
else if (b.context && typeof b.context === "object") context = JSON.stringify(b.context).slice(0, 4000);
if (!context) context = await buildAgentContext(env, uid);
const messages = [
{ role: "system", content: AGENT_SYSTEM + "\n\n\n" + context},
...history,
{ role: "user", content: message},
];
const { reply, model} = await runAi(env, messages);
if (!reply) throw httpErr(502, "智能体暂时没有回应, 请稍后再试");
return json({ reply, model});
}

const PLAN_SYSTEM = `你是「轻衡健康」的 AI 健康教练。基于用户的目标与近期真实数据, 制定一份具体可执行的本周计划。
必须只输出一个 JSON 对象, 不要输出任何其他文字、解释或 Markdown 代码块标记。结构:
{"week_goal":"一句话本周重点(30字内)","targets":{"steps":8000,"calories_kcal":400,"exercise_minutes":30,"sleep_hours":8},"days":[{"key":"mon","focus":"主题(8字内)","actions":[{"title":"动作名","detail":"具体做法与强度(40字内)","minutes":30}],"steps_target":8000,"calories_target":400}, 共7天 key 依次 mon tue wed thu fri sat sun],"tips":["建议1","建议2","建议3"]}
制定原则:
- 循序渐进: 近期日均步数/消耗明显低于标准目标时, 本周目标定在近期水平上浮 10%-20%, 不要一步到位; 已接近或超过标准则用标准或略高。
- 一周安排 2-3 天运动日 (结合用户常做的运动类型)、1-2 天主动恢复 (散步/拉伸)、至少 1 天完全休息; 运动日 2-3 个具体动作, 恢复日 1-2 个。
- 动作要具体: 做什么、多久、什么强度, 能直接照做; 减重人群优先低冲击有氧+力量, 睡眠不足时降低强度。
- tips 3-4 条, 覆盖饮食/睡眠/补水或补足技巧, 贴合数据短板。`;

function isoWeekStartStr() {
  const t = todayStr();
  const dow = (new Date(t + "T00:00:00Z").getUTCDay() + 6) % 7;
  return addDays(t, -dow);
}

function normalizePlan(raw) {
  const t = (raw && raw.targets) || {};
  const clamp = (v, lo, hi, dft) => {
    const n = Number(v);
    return Number.isFinite(n) ? Math.min(hi, Math.max(lo, Math.round(n))) : dft;
  };
  const plan = {
    week_goal: typeof (raw && raw.week_goal) === "string" && raw.week_goal ? raw.week_goal.slice(0, 60) : "稳步提升活动量, 养成规律节奏",
    targets: {
      steps: clamp(t.steps, 3000, 20000, 8000),
      calories_kcal: clamp(t.calories_kcal, 100, 1200, 400),
      exercise_minutes: clamp(t.exercise_minutes, 0, 180, 30),
      sleep_hours: clamp(t.sleep_hours, 5, 10, 8),
    },
    days: [],
    tips: Array.isArray(raw && raw.tips) ? raw.tips.filter((x) => typeof x === "string").slice(0, 5) : [],
  };
  const keys = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
  const byKey = {};
  if (Array.isArray(raw && raw.days)) for (const d of raw.days) if (d && keys.includes(d.key)) byKey[d.key] = d;
  for (const k of keys) {
    const d = byKey[k] || {};
    const actions = Array.isArray(d.actions) ? d.actions.filter((a) => a && typeof a.title === "string").slice(0, 4).map((a) => ({
      title: String(a.title).slice(0, 30),
      detail: typeof a.detail === "string" ? a.detail.slice(0, 80) : "",
      minutes: Number.isFinite(Number(a.minutes)) ? Math.max(0, Math.round(Number(a.minutes))) : null,
    })) : [];
    plan.days.push({
      key: k,
      focus: typeof d.focus === "string" && d.focus ? d.focus.slice(0, 16) : "日常活动",
      actions,
      steps_target: clamp(d.steps_target, 2000, 25000, plan.targets.steps),
      calories_target: clamp(d.calories_target, 50, 1500, plan.targets.calories_kcal),
    });
  }
  if (!plan.tips.length) plan.tips = ["晚饭后步行 20-30 分钟是补步数最稳的办法", "睡前 1 小时放下手机, 保证 7-8 小时睡眠", "每天喝够 1500-2000ml 水"];
  return plan;
}

async function agentPlan(request, env, uid) {
  const weekStart = isoWeekStartStr();
  if (request.method === "GET") {
    const row = await env.DB.prepare("SELECT content FROM agent_cache WHERE user_id=? AND cache_date=? AND kind='weekly_plan'").bind(uid, weekStart).first();
    if (!row) throw httpErr(404, "本周计划尚未生成");
    return json({ plan: JSON.parse(row.content), week_start: weekStart, cached: true, source: "ai" });
  }
  const b = await request.json().catch(() => ({}));
  if (!b.force) {
    const row = await env.DB.prepare("SELECT content FROM agent_cache WHERE user_id=? AND cache_date=? AND kind='weekly_plan'").bind(uid, weekStart).first();
    if (row) return json({ plan: JSON.parse(row.content), week_start: weekStart, cached: true, source: "ai" });
  }
  let context = "";
  if (typeof b.context === "string" && b.context.trim()) context = b.context.trim().slice(0, 4000);
  else if (b.context && typeof b.context === "object") context = JSON.stringify(b.context).slice(0, 4000);
  if (!context) context = await buildAgentContext(env, uid);
  const model = env.AI_MODEL || "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
  const result = await env.AI.run(model, {
    messages: [
      { role: "system", content: PLAN_SYSTEM },
      { role: "user", content: "【用户健康数据】\n" + context + "\n\n请制定本周 (周一到周日) 计划, 只输出 JSON。" },
    ],
    max_tokens: 3000, temperature: 0.5,
  });
  const respVal = result && (result.response !== undefined ? result.response : result.text);
  let raw = null;
  if (respVal && typeof respVal === "object") raw = respVal;
  else {
    const text = String(respVal || "");
    const start = text.indexOf("{"), end = text.lastIndexOf("}");
    if (start >= 0 && end > start) { try { raw = JSON.parse(text.slice(start, end + 1)); } catch (e) { raw = null; } }
  }
  if (!raw) throw httpErr(502, "计划生成失败, 请稍后再试");
  const plan = normalizePlan(raw);
  plan.model = model;
  await env.DB.prepare("INSERT OR REPLACE INTO agent_cache(user_id,cache_date,kind,content) VALUES(?,?,'weekly_plan',?)").bind(uid, weekStart, JSON.stringify(plan)).run();
  return json({ plan, week_start: weekStart, cached: false, source: "ai" });
}

async function agentDailyInsight(env, uid) {
const today = todayStr();
const cached = await env.DB.prepare("SELECT content FROM agent_cache WHERE user_id=? AND cache_date=? AND kind='daily_insight'").bind(uid, today).first();
if (cached) return json({ date: today, insight: cached.content, cached: true});
const context = await buildAgentContext(env, uid);
const { reply, model} = await runAi(env, [
{ role: "system", content: AGENT_SYSTEM},
{ role: "user", content: "根据我的健康数据, 给我今天的健康洞察和一条最值得执行的建议, 120 字以内。\n\n\n" + context},
]);
if (reply) {
await env.DB.prepare("INSERT OR REPLACE INTO agent_cache(user_id,cache_date,kind,content) VALUES(?,?,'daily_insight',?)").bind(uid, today, reply).run();
}
return json({ date: today, insight: reply, cached: false, model});
}

// ============================================================
// 小米运动健康协议移植 (自 backend/app/integrations/vendor/{client,login}.py 与 mihealth.py)
// 覆盖: 账号密码登录 / 二次验证 / pass_token 免密刷新 / 签名加密数据读取 / 会话 Fernet 加解密
// 自动同步: export 的 scheduled 处理器由 Cron 每 10 分钟触发, 为所有已绑定用户增量同步近 2 天数据
// ============================================================

class MiErr extends Error {}
class MiAuthError extends MiErr {}
class MiVerificationError extends MiErr {}
class MiChallenge extends MiErr {
  constructor(message, state, hint) { super(message); this.state = state || null; this.hint = hint || ""; }
}

// ---------- base64 / 字节工具 ----------
function b64enc(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
function b64dec(str) {
  const bin = atob(String(str).replace(/\s/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function b64urlEnc(bytes) { return b64enc(bytes).replace(/\+/g, "-").replace(/\//g, "_"); }
function b64urlDec(str) { return b64dec(String(str).replace(/-/g, "+").replace(/_/g, "/")); }
function utf8Bytes(s) { return new TextEncoder().encode(s); }
function bytesToUtf8(b) { return new TextDecoder().decode(b); }
async function shaDigest(algo, bytes) { return new Uint8Array(await crypto.subtle.digest(algo, bytes)); }
async function sha1B64(items) { return b64enc(await shaDigest("SHA-1", utf8Bytes(items.join("&")))); }
function sleepMs(ms) { return new Promise((r) => setTimeout(r, ms)); }

// ---------- MD5 (纯 JS; WebCrypto 不提供, 登录密码哈希与 deviceId 需要) ----------
function md5Bytes(input) {
  const S = [7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22, 5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
             4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23, 6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21];
  const K = [];
  for (let i = 0; i < 64; i++) K.push(Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296));
  const bitLen = input.length * 8;
  const padded = new Uint8Array(((input.length + 8) >> 6 << 6) + 64);
  padded.set(input);
  padded[input.length] = 0x80;
  const dv = new DataView(padded.buffer);
  dv.setUint32(padded.length - 8, bitLen >>> 0, true);
  dv.setUint32(padded.length - 4, Math.floor(bitLen / 4294967296), true);
  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  const M = new Uint32Array(16);
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) M[i] = dv.getUint32(off + i * 4, true);
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F, g;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      const tmp = D; D = C; C = B;
      const sum = (A + F + K[i] + M[g]) >>> 0;
      B = (B + (((sum << S[i]) | (sum >>> (32 - S[i]))) >>> 0)) >>> 0;
      A = tmp;
    }
    a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
  }
  const out = new Uint8Array(16);
  const odv = new DataView(out.buffer);
  odv.setUint32(0, a0, true); odv.setUint32(4, b0, true); odv.setUint32(8, c0, true); odv.setUint32(12, d0, true);
  return out;
}
function md5Upper(s) {
  return [...md5Bytes(utf8Bytes(s))].map((b) => b.toString(16).padStart(2, "0")).join("").toUpperCase();
}

// ---------- RC4 (drop 1024, 与 vendor/client.py 一致) ----------
class RC4 {
  constructor(keyBytes, drop = 1024) {
    if (keyBytes.length !== 32) throw new Error("rc4Key length is invalid");
    const s = new Uint8Array(256);
    for (let i = 0; i < 256; i++) s[i] = i;
    let j = 0;
    for (let i = 0; i < 256; i++) { j = (j + s[i] + keyBytes[i % keyBytes.length]) & 0xff; const t = s[i]; s[i] = s[j]; s[j] = t; }
    this.s = s; this.i = 0; this.j = 0;
    if (drop) this._gen(drop);
  }
  _gen(n) {
    const s = this.s; let i = this.i, j = this.j;
    for (let k = 0; k < n; k++) {
      i = (i + 1) & 0xff; j = (j + s[i]) & 0xff;
      const t = s[i]; s[i] = s[j]; s[j] = t;
    }
    this.i = i; this.j = j;
  }
  crypt(data) {
    const s = this.s; let i = this.i, j = this.j;
    const out = new Uint8Array(data.length);
    for (let n = 0; n < data.length; n++) {
      i = (i + 1) & 0xff; j = (j + s[i]) & 0xff;
      const t = s[i]; s[i] = s[j]; s[j] = t;
      out[n] = data[n] ^ s[(s[i] + s[j]) & 0xff];
    }
    this.i = i; this.j = j;
    return out;
  }
}

// ---------- Fernet (会话加解密; 格式与 cryptography.Fernet 互通) ----------
async function fernetKeys(keyStr) {
  if (!keyStr) throw new MiErr("尚未设置小米会话加密密钥");
  let raw;
  try { raw = b64urlDec(String(keyStr).trim()); } catch (e) { throw new MiErr("小米会话加密密钥格式无效"); }
  if (raw.length !== 32) throw new MiErr("小米会话加密密钥格式无效");
  return { signKey: raw.subarray(0, 16), encKey: raw.subarray(16, 32) };
}
async function hmacSha256(keyBytes, dataBytes) {
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, dataBytes));
}
async function fernetEncrypt(plainBytes, keyStr) {
  const { signKey, encKey } = await fernetKeys(keyStr);
  const iv = new Uint8Array(16); crypto.getRandomValues(iv);
  const ts = new Uint8Array(8);
  new DataView(ts.buffer).setBigUint64(0, BigInt(Math.floor(Date.now() / 1000)), false);
  const akey = await crypto.subtle.importKey("raw", encKey, { name: "AES-CBC" }, false, ["encrypt"]);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-CBC", iv }, akey, plainBytes));
  const body = new Uint8Array(1 + 8 + 16 + ct.length);
  body[0] = 0x80; body.set(ts, 1); body.set(iv, 9); body.set(ct, 25);
  const mac = await hmacSha256(signKey, body);
  const token = new Uint8Array(body.length + 32);
  token.set(body); token.set(mac, body.length);
  return b64urlEnc(token);
}
async function fernetDecrypt(tokenStr, keyStr) {
  const { signKey, encKey } = await fernetKeys(keyStr);
  let raw;
  try { raw = b64urlDec(String(tokenStr).trim()); } catch (e) { throw new MiErr("小米会话无法解密，请重新绑定"); }
  if (raw.length < 57 || raw[0] !== 0x80) throw new MiErr("小米会话无法解密，请重新绑定");
  const body = raw.subarray(0, raw.length - 32), mac = raw.subarray(raw.length - 32);
  const expect = await hmacSha256(signKey, body);
  let diff = 0; for (let i = 0; i < 32; i++) diff |= expect[i] ^ mac[i];
  if (diff !== 0) throw new MiErr("小米会话无法解密，请重新绑定");
  const iv = raw.subarray(9, 25), ct = raw.subarray(25, raw.length - 32);
  const akey = await crypto.subtle.importKey("raw", encKey, { name: "AES-CBC" }, false, ["decrypt"]);
  try {
    return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, akey, ct));
  } catch (e) { throw new MiErr("小米会话无法解密，请重新绑定"); }
}
async function miEncryptSession(sessionObj, keyStr) {
  return fernetEncrypt(utf8Bytes(JSON.stringify(sessionObj)), keyStr);
}
async function miDecryptSession(blob, keyStr) {
  const clear = await fernetDecrypt(blob, keyStr);
  let obj;
  try { obj = JSON.parse(bytesToUtf8(clear)); } catch (e) { throw new MiErr("小米会话无法解密，请重新绑定"); }
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) throw new MiErr("小米会话格式无效，请重新绑定");
  return obj;
}

// ---------- Cookie 罐 (替代 requests.Session 的 cookie 行为) ----------
class CookieJar {
  constructor() { this.cookies = []; }
  set(name, value, domain, path = "/", secure = false, expires = null) {
    const d = (domain || "").toLowerCase();
    this.cookies = this.cookies.filter((c) => !(c.name === name && c.domain === d && c.path === path));
    this.cookies.push({ name, value: String(value), domain: d, path, secure: !!secure, expires });
  }
  get(name, domainHint) {
    let fallback = null;
    for (const c of this.cookies) {
      if (c.name !== name) continue;
      if (domainHint && (domainHint === c.domain || domainHint.endsWith("." + c.domain.replace(/^\./, "")))) return c.value;
      if (!fallback) fallback = c.value;
    }
    return fallback;
  }
  headerFor(host) {
    const h = host.toLowerCase();
    const pairs = [];
    for (const c of this.cookies) {
      const d = c.domain.replace(/^\./, "");
      if (h === d || h.endsWith("." + d)) pairs.push(`${c.name}=${c.value}`);
    }
    return pairs.join("; ");
  }
  absorb(resp) {
    let list = [];
    try { if (typeof resp.headers.getSetCookie === "function") list = resp.headers.getSetCookie(); } catch (e) {}
    if (!list || !list.length) { const single = resp.headers.get("set-cookie"); if (single) list = [single]; }
    for (const line of list || []) {
      const parts = String(line).split(";");
      const first = parts[0]; const eq = first.indexOf("=");
      if (eq <= 0) continue;
      const name = first.slice(0, eq).trim(), value = first.slice(eq + 1).trim();
      let domain = "", path = "/", secure = false, expires = null;
      for (const attr of parts.slice(1)) {
        const kv = attr.split("="); const k = (kv[0] || "").trim().toLowerCase();
        const v = kv.slice(1).join("=").trim();
        if (k === "domain") domain = v.toLowerCase();
        else if (k === "path") path = v || "/";
        else if (k === "secure") secure = true;
        else if (k === "expires") { const t = Date.parse(v); expires = isNaN(t) ? null : Math.floor(t / 1000); }
      }
      this.set(name, value, domain, path, secure, expires);
    }
  }
  serialize() { return this.cookies.map((c) => ({ ...c })); }
  static restore(arr) {
    const jar = new CookieJar();
    for (const c of arr || []) jar.set(c.name, c.value, c.domain || "", c.path || "/", c.secure, c.expires);
    return jar;
  }
}

// 带 cookie 罐的 HTTP 请求 (含手动重定向跟随, 以便吸收中间响应的 Set-Cookie)
async function xfetch(jar, method, urlStr, opts = {}) {
  const tries = opts.tries || 2;
  let lastErr = null;
  for (let attempt = 0; attempt < tries; attempt++) {
    try {
      return await xfetchOnce(jar, method, urlStr, opts);
    } catch (e) { lastErr = e; if (attempt + 1 < tries) await sleepMs(400 * (attempt + 1)); }
  }
  throw new MiErr("网络请求失败：" + (lastErr && lastErr.message ? lastErr.message : String(lastErr)));
}
async function xfetchOnce(jar, method, urlStr, opts) {
  let url = new URL(urlStr);
  if (opts.params) for (const [k, v] of Object.entries(opts.params)) if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
  let current = url.toString(), m = method.toUpperCase(), body = opts.data ? new URLSearchParams(opts.data).toString() : undefined;
  for (let hop = 0; hop < 6; hop++) {
    const headers = { "User-Agent": opts.ua || MI_UA_SDK, ...(opts.headers || {}) };
    if (body !== undefined && m !== "GET") headers["Content-Type"] = "application/x-www-form-urlencoded";
    const cookieParts = [];
    if (jar) { const h = jar.headerFor(new URL(current).hostname); if (h) cookieParts.push(h); }
    if (opts.cookies) for (const [k, v] of Object.entries(opts.cookies)) if (v) cookieParts.push(`${k}=${v}`);
    if (cookieParts.length) headers["Cookie"] = cookieParts.join("; ");
    const resp = await fetch(current, { method: m, headers, body: m === "GET" ? undefined : body, redirect: "manual" });
    if (jar) jar.absorb(resp);
    const status = resp.status;
    const location = resp.headers.get("location");
    if ((opts.followRedirects === false) || status < 300 || status >= 400 || !location) {
      const text = await resp.text();
      return { status, text, headers: resp.headers };
    }
    current = new URL(location, current).toString();
    if (status === 303 || ((status === 301 || status === 302) && m === "POST")) { m = "GET"; body = undefined; }
  }
  throw new MiErr("重定向次数过多");
}
function miJsonBody(text) {
  const i = (text || "").indexOf("{");
  if (i < 0) return {};
  try { return JSON.parse(text.slice(i)); } catch (e) { return {}; }
}

// ---------- 小米账号登录 (vendor/login.py 移植) ----------
const MI_ACCOUNT_HOST = "https://account.xiaomi.com";
const MI_IDENTITY = "https://account.xiaomi.com/identity";
const MI_SID_HEALTH = "miothealth";
const MI_SDK_VERSION = "accountsdk-18.8.15";
const MI_UA_SDK = "XiaomiHealthApi-ABCDE APP/com.xiaomi.mihome APPV/10.5.201";
const MI_UA_APP_TPL = "MiHome/11.3.203 (com.xiaomi.mihome; build:11.3.203; Android 9) APP/com.xiaomi.mihome APPV/11.3.203 DeviceId/{dev} UserId/{uid} Platform/Android Region/CN L/zh_CN";

function miRandomDeviceId() {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  const a = new Uint8Array(16); crypto.getRandomValues(a);
  return [...a].map((b) => chars[b % chars.length]).join("");
}
function miNewLoginSession(deviceRaw) {
  const raw = deviceRaw || miRandomDeviceId();
  const jar = new CookieJar();
  for (const dom of ["mi.com", "xiaomi.com"]) {
    jar.set("sdkVersion", MI_SDK_VERSION, dom);
    jar.set("deviceId", raw, dom);
  }
  return { jar, deviceIdMd5: md5Upper(raw), userId: "" };
}
function miSerializeSession(sess) {
  return { device_id: sess.deviceIdMd5, user_id: sess.userId || "", cookies: sess.jar.serialize() };
}
function miRestoreSession(state) {
  return { jar: CookieJar.restore((state || {}).cookies || []), deviceIdMd5: (state || {}).device_id || "", userId: (state || {}).user_id || "" };
}
function miServiceTokenFromJar(jar) {
  return jar.get("serviceToken", "sts-hlth.io.mi.com") || jar.get("serviceToken");
}
async function miServiceLogin(sess, sid, extraCookies) {
  const r = await xfetch(sess.jar, "GET", `${MI_ACCOUNT_HOST}/pass/serviceLogin`,
    { params: { sid, _json: "true" }, cookies: extraCookies });
  const j = miJsonBody(r.text);
  if (!j._sign) throw new MiErr(`serviceLogin 未返回 _sign（HTTP ${r.status}）`);
  return j;
}
async function miLoginWithPassword(username, password, sid = MI_SID_HEALTH) {
  const sess = miNewLoginSession();
  const j = await miServiceLogin(sess, sid, { userId: username });
  const fields = {
    user: username, hash: md5Upper(password), sid, _json: "true", _sign: j._sign,
    qs: j.qs || `?sid=${sid}&_json=true`, callback: j.callback || "", locale: "zh_CN",
  };
  const r2 = await xfetch(sess.jar, "POST", `${MI_ACCOUNT_HOST}/pass/serviceLoginAuth2`,
    { params: fields, followRedirects: false });
  const j2 = miJsonBody(r2.text);
  if (j2.captchaUrl) {
    const e = new MiErr("需要验证码"); e.captchaUrl = j2.captchaUrl; e.code = j2.code; throw e;
  }
  if (!(typeof j2.ssecurity === "string" && j2.ssecurity.length > 4)) {
    if (j2.notificationUrl) {
      const e = new MiErr("账号需要二次验证（2FA）");
      e.need2fa = true; e.notificationUrl = j2.notificationUrl; e.raw = j2; e.sess = sess;
      throw e;
    }
    throw new MiErr(j2.desc || `登录失败 code=${j2.code}`);
  }
  let token = null;
  if (j2.location) {
    await xfetch(sess.jar, "GET", j2.location, {});
    token = miServiceTokenFromJar(sess.jar);
  }
  return { sess, result: {
    ssecurity: j2.ssecurity, service_token: token, cuser_id: j2.cUserId, user_id: j2.userId,
    pass_token: j2.passToken, sid, ts: Math.floor(Date.now() / 1000),
  } };
}
function miIdentityHeaders(sess) {
  return {
    "User-Agent": MI_UA_APP_TPL.replace("{dev}", sess.deviceIdMd5 || "").replace("{uid}", sess.userId || ""),
    "Content-Type": "application/x-www-form-urlencoded", "Referer": "https://account.xiaomi.com/",
  };
}
async function miIdCall(sess, method, url, step, opts = {}) {
  const r = await xfetch(sess.jar, method, url, {
    ...opts, headers: miIdentityHeaders(sess),
    cookies: sess.deviceIdMd5 ? { deviceId: sess.deviceIdMd5 } : {},
  });
  if (!(r.text || "").trim()) throw new MiErr(`[${step}] 服务端返回空响应（HTTP ${r.status}）——通常是 UA/deviceId 不被接受`);
  return r;
}
async function miStart2fa(sess, notificationUrl, sid = MI_SID_HEALTH) {
  let url = notificationUrl;
  if (!url.startsWith("http")) url = MI_ACCOUNT_HOST + url;
  await miIdCall(sess, "GET", url, "authStart");
  const q = new URL(url).searchParams;
  const ctx = q.get("context") || "";
  const useSid = q.get("sid") || sid;
  if (q.get("userId")) sess.userId = q.get("userId");
  const r = await miIdCall(sess, "GET", `${MI_IDENTITY}/list`, "identity/list",
    { params: { sid: useSid, supportedMask: "0", _locale: "zh_CN", context: ctx } });
  const idata = miJsonBody(r.text);
  const flag = idata.flag !== undefined ? idata.flag : 4;
  const method = flag === 8 ? "Email" : "Phone";
  await miIdCall(sess, "GET", `${MI_IDENTITY}/auth/verify${method}`, `verify${method}(trigger)`,
    { params: { _flag: String(flag), _json: "true" } });
  if (method === "Phone") {
    await miIdCall(sess, "POST", `${MI_IDENTITY}/auth/sendPhoneTicket`, "sendPhoneTicket",
      { data: { retry: "0", icode: "", _json: "true" } });
  }
  let hint = "";
  for (const k of ["mask", "notification", "email", "phone", "address"]) if (idata[k]) { hint = idata[k]; break; }
  return { context: ctx, flag, method, hint };
}
async function miFinish2fa(sess, code, context, flag = 4, sid = MI_SID_HEALTH) {
  const method = flag === 8 ? "Email" : "Phone";
  const r = await miIdCall(sess, "POST", `${MI_IDENTITY}/auth/verify${method}`, `verify${method}(submit)`, {
    params: { _dc: String(Date.now()), _flag: String(flag), _json: "true", sid, context },
    data: { _flag: String(flag), ticket: String(code).trim(), trust: "false", _json: "true" },
  });
  const vresp = miJsonBody(r.text);
  if (vresp.code !== undefined && vresp.code !== 0 && vresp.code !== null && !vresp.location) {
    const e = new MiErr(`验证码校验未通过（code=${vresp.code} ${vresp.desc || ""}）`); e.code = vresp.code; throw e;
  }
  let loc = vresp.location || r.headers.get("location");
  if (!loc) { const m = (r.text || "").match(/location\s*[:=]\s*["']([^"']+)/); loc = m ? m[1] : null; }
  if (!loc) throw new MiErr(`验证码校验后未拿到跳转地址（HTTP ${r.status}）`);
  if (!loc.startsWith("http")) loc = MI_ACCOUNT_HOST + loc;
  await xfetch(sess.jar, "GET", loc, {});
  const r3 = await xfetch(sess.jar, "GET", `${MI_ACCOUNT_HOST}/pass/serviceLogin`, { params: { sid, _json: "true" } });
  const j3 = miJsonBody(r3.text);
  if (j3.code !== 0 || !j3.ssecurity) throw new MiErr(`二次验证后登录未完成（code=${j3.code} ${j3.desc || ""}）`);
  let token = null;
  if (j3.location) {
    await xfetch(sess.jar, "GET", j3.location, { followRedirects: false });
    token = miServiceTokenFromJar(sess.jar);
  }
  return {
    ssecurity: j3.ssecurity, service_token: token,
    cuser_id: j3.cUserId || sess.jar.get("cUserId"), user_id: j3.userId || sess.jar.get("userId"),
    pass_token: j3.passToken || sess.jar.get("passToken"), sid, ts: Math.floor(Date.now() / 1000),
  };
}
async function miExchangePassToken(passToken, cuserId, userId, sid = MI_SID_HEALTH) {
  const jar = new CookieJar();
  const ck = { passToken, cUserId: cuserId, userId: userId === null || userId === undefined ? "" : String(userId) };
  for (const [k, v] of Object.entries(ck)) if (v) jar.set(k, v, ".account.xiaomi.com");
  const sess = { jar, deviceIdMd5: "", userId: "" };
  const r = await xfetch(jar, "GET", `${MI_ACCOUNT_HOST}/pass/serviceLogin`,
    { params: { sid, _json: "true" }, cookies: ck });
  const j = miJsonBody(r.text);
  if (j.code !== 0 || !j.location) throw new MiErr(`passToken 无效或已过期（code=${j.code} ${j.desc || ""}）`);
  await xfetch(jar, "GET", j.location, { cookies: ck, followRedirects: false });
  const token = miServiceTokenFromJar(jar);
  if (!token) throw new MiErr("STS 回调未返回 serviceToken");
  return { ssecurity: j.ssecurity, service_token: token, cuser_id: j.cUserId || cuserId,
           user_id: j.userId || userId, sid, ts: Math.floor(Date.now() / 1000) };
}
async function miRefreshSession(session) {
  if (!session.pass_token || !session.cuser_id) throw new MiErr("小米会话已过期，请重新绑定");
  const s = await miExchangePassToken(session.pass_token, session.cuser_id, session.user_id || "");
  // 保留 pass_token 以便下次继续免密续期 (与原 save_session 合并语义一致)
  s.pass_token = session.pass_token;
  s.region = session.region || "cn";
  return s;
}
async function miConnect(username, password) {
  let out;
  try {
    out = await miLoginWithPassword(username, password);
  } catch (e) {
    if (e && e.need2fa) {
      let challenge;
      try { challenge = await miStart2fa(e.sess, e.notificationUrl); }
      catch (e2) { throw new MiErr("无法启动小米短信验证，请稍后重试"); }
      const state = {
        session: miSerializeSession(e.sess), context: challenge.context, flag: challenge.flag,
        method: challenge.method, hint: challenge.hint, sid: (e.raw && e.raw.sid) || MI_SID_HEALTH,
      };
      const label = challenge.method === "Phone" ? "短信" : "邮箱";
      throw new MiChallenge(`小米已发送${label}验证码`, state, challenge.hint);
    }
    if (e && e.captchaUrl) throw new MiChallenge("小米账号要求验证码，当前连接流程暂不支持", null, "");
    if (e instanceof MiErr) throw new MiErr("小米账号登录失败，请检查账号密码或稍后重试");
    throw e;
  }
  const session = out.result;
  for (const f of ["ssecurity", "service_token", "cuser_id", "pass_token"]) {
    if (!session[f]) throw new MiErr("小米登录未返回完整健康会话");
  }
  session.region = "cn";
  return session;
}
async function miVerifyChallenge(state, code) {
  const sess = miRestoreSession(state.session);
  let result;
  try {
    result = await miFinish2fa(sess, code, state.context, parseInt(state.flag !== undefined ? state.flag : 4, 10), state.sid || MI_SID_HEALTH);
  } catch (e) {
    if (e && (e.code !== undefined && e.code !== null || String(e.message).includes("验证码校验未通过"))) {
      throw new MiVerificationError("验证码不正确或已过期");
    }
    if (e instanceof MiErr) throw new MiErr("小米验证码验证暂时失败，请重试");
    throw e;
  }
  for (const f of ["ssecurity", "service_token", "cuser_id", "pass_token"]) {
    if (!result[f]) throw new MiErr("小米验证完成，但未返回完整健康会话");
  }
  result.region = "cn";
  return result;
}

// ---------- 米健康数据客户端 (vendor/client.py 移植: 签名加密 + 分页) ----------
async function miSessionKey(ssecurity, nonce) {
  const a = b64dec(ssecurity), b = b64dec(nonce);
  const joined = new Uint8Array(a.length + b.length);
  joined.set(a); joined.set(b, a.length);
  return b64enc(await shaDigest("SHA-256", joined));
}
function miGenNonce() {
  const rand = new Uint8Array(8); crypto.getRandomValues(rand);
  const minutes = Math.floor(Date.now() / 60000);
  const out = new Uint8Array(12);
  out.set(rand);
  new DataView(out.buffer).setInt32(8, minutes, false);
  return b64enc(out);
}
async function miSignedEncrypted(method, path, params, nonce, security) {
  const skey = await miSessionKey(security, nonce);
  const plain = {};
  for (const [k, v] of Object.entries(params)) if (k && v !== "" && v !== null && v !== undefined) plain[k] = String(v);
  const p = (() => { try { return new URL(path, "https://x").pathname; } catch (e) { return path; } })();
  const keys = Object.keys(plain).sort();
  plain["rc4_hash__"] = await sha1B64([method.toUpperCase(), p, ...keys.map((k) => `${k}=${plain[k]}`), skey]);
  const rc4 = new RC4(b64dec(skey));
  const enc = {};
  for (const k of Object.keys(plain).sort()) enc[k] = b64enc(rc4.crypt(utf8Bytes(plain[k])));
  const sig = await sha1B64([method.toUpperCase(), p, ...Object.keys(enc).sort().map((k) => `${k}=${enc[k]}`), skey]);
  return { ...enc, signature: sig, _nonce: nonce };
}
async function miDecryptResponseAsync(bodyB64, nonce, security) {
  const skey = await miSessionKey(security, nonce);
  return bytesToUtf8(new RC4(b64dec(skey)).crypt(b64dec(bodyB64)));
}
const MI_SCALAR_FIELDS = {
  steps: ["steps"], calories: ["calories"], heart_rate: ["bpm", "heart_rate"], spo2: ["spo2"],
  stress: ["stress", "stress_value"], vo2_max: ["vo2_max"], pai: ["daily_pai", "pai", "value"],
  weight: ["weight"], energy: ["energy"],
};
function miCanonicalNumber(key, item) {
  for (const f of MI_SCALAR_FIELDS[key] || []) {
    const v = item[f];
    if (typeof v === "number" && isFinite(v)) return v;
  }
  return null;
}
function miDedupItems(items, key) {
  const byT = new Map();
  for (const x of items) {
    const t = x._t;
    if (t === undefined || t === null) continue;
    const cur = byT.get(t);
    if (!cur) { byT.set(t, { ...x }); continue; }
    const nNew = miCanonicalNumber(key || "", x), nCur = miCanonicalNumber(key || "", cur);
    if (nNew !== null && (nCur === null || nNew > nCur)) byT.set(t, { ...cur, ...x });
    else { for (const [k, v] of Object.entries(x)) if (!(k in cur)) cur[k] = v; }
  }
  return [...byT.values()].sort((a, b) => a._t - b._t);
}
class MiHealthClient {
  constructor(ssecurity, serviceToken, cuserId, region = "cn") {
    this.security = ssecurity; this.serviceToken = serviceToken; this.cuser = cuserId;
    this.base = region === "abroad" ? "https://region.hlth.io.mi.com" : "https://hlth.io.mi.com";
    this.lastSend = 0;
  }
  async _throttle() {
    const wait = 500 - (Date.now() - this.lastSend);
    if (wait > 0) await sleepMs(wait);
    this.lastSend = Date.now();
  }
  async call(subpath, params, method = "GET") {
    const nonce = miGenNonce();
    const q = await miSignedEncrypted(method, "/app/v1/" + subpath, params, nonce, this.security);
    const url = this.base + "/app/v1/" + subpath;
    await this._throttle();
    const headers = { "Cookie": `cUserId=${this.cuser}; serviceToken=${this.serviceToken}; locale=zh_cn` };
    let resp;
    if (method.toUpperCase() === "GET") {
      const u = new URL(url);
      for (const [k, v] of Object.entries(q)) u.searchParams.set(k, v);
      resp = await fetch(u.toString(), { headers });
    } else {
      resp = await fetch(url, { method: "POST", headers: { ...headers, "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(q).toString() });
    }
    const text = await resp.text();
    if (resp.status === 401 || resp.status === 403) throw new MiAuthError(`auth failed (HTTP ${resp.status})`);
    if (resp.status >= 400) throw new MiErr(`HTTP ${resp.status}: ${text.slice(0, 200)}`);
    let data;
    try { data = JSON.parse(await miDecryptResponseAsync(text, nonce, this.security)); }
    catch (e) {
      try { data = JSON.parse(text); } catch (e2) { throw new MiErr(`undecodable response: ${text.slice(0, 200)}`); }
    }
    const code = data ? data.code : undefined;
    if (code === 2 || (typeof code === "number" && String((data && data.message) || "").toLowerCase().includes("auth"))) {
      throw new MiAuthError(`auth failed (code=${code})`);
    }
    return data;
  }
  async _pagedAll(fetchPage, listField, startMs, endMs, maxPages, key) {
    let items = [], nk = null;
    for (let p = 0; p < maxPages; p++) {
      const res = ((await fetchPage(nk)) || {}).result || {};
      const batch = res[listField] || [];
      for (const it of batch) if (it._t === undefined) it._t = it.time;
      items = items.concat(batch.filter((it) => (it.time || 0) * 1000 >= startMs && (it.time || 0) * 1000 <= endMs));
      nk = res.next_key;
      if (!res.has_more || !nk) return { items: miDedupItems(items, key), hasMore: false };
      if (batch.length && Math.max(...batch.map((it) => it.time || 0)) * 1000 < startMs) {
        return { items: miDedupItems(items, key), hasMore: false };
      }
    }
    return { items: miDedupItems(items, key), hasMore: true };
  }
  getFitnessByTime(key, startMs, endMs, nextKey = null) {
    const data = { key, startTime: startMs, endTime: endMs, reverse: true, next_key: nextKey };
    return this.call("data/get_fitness_data_by_time", { data: JSON.stringify(data) });
  }
  async getFitnessAll(key, startMs, endMs, maxPages = 40) {
    const apiStart = Math.max(startMs, endMs - 86400000);
    const r = await this._pagedAll((nk) => this.getFitnessByTime(key, apiStart, endMs, nk), "data_list", startMs, endMs, maxPages, key);
    return { data_list: r.items, has_more: r.hasMore };
  }
  getSportByTime(startMs, endMs, nextKey = null) {
    const data = { startTime: startMs, endTime: endMs, next_key: nextKey, limit: 50, reverse: true };
    return this.call("data/get_sport_records_by_time", { data: JSON.stringify(data) });
  }
  async getSportAll(startMs, endMs, maxPages = 20) {
    const apiStart = Math.max(startMs, endMs - 86400000);
    const r = await this._pagedAll((nk) => this.getSportByTime(apiStart, endMs, nk), "sport_records", startMs, endMs, maxPages, null);
    return { sport_records: r.items, has_more: r.hasMore };
  }
}
function miDecode(item) {
  try { const d = JSON.parse((item && item.value) || "{}"); return d && typeof d === "object" && !Array.isArray(d) ? d : {}; }
  catch (e) { return {}; }
}
function miLocalDatetime(raw) {
  try {
    let stamp = parseFloat(raw);
    if (!isFinite(stamp)) return null;
    if (stamp > 100000000000) stamp /= 1000;
    const d = new Date(stamp * 1000);
    const parts = {};
    for (const p of new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).formatToParts(d)) parts[p.type] = p.value;
    const hh = parts.hour === "24" ? "00" : parts.hour;
    return `${parts.year}-${parts.month}-${parts.day}T${hh}:${parts.minute}:${parts.second}`;
  } catch (e) { return null; }
}
function miIsNum(v) { return typeof v === "number" && isFinite(v); }
async function miReadMetrics(session, startMs, endMs) {
  const client = new MiHealthClient(session.ssecurity, session.service_token, session.cuser_id, session.region || "cn");
  const readings = [];
  for (const key of ["weight", "sleep", "steps", "calories"]) {
    const result = await client.getFitnessAll(key, startMs, endMs, 40);
    if (result.has_more) throw new MiErr("小米健康记录较多，请缩短同步日期范围后重试");
    for (const item of result.data_list) {
      const detail = miDecode(item);
      const timestamp = item.time || detail.time || detail.wake_time;
      const recordedAt = miLocalDatetime(timestamp);
      if (!recordedAt) continue;
      if (key === "calories") {
        const cal = detail.calories !== undefined && detail.calories !== null ? detail.calories
          : (detail.energy !== undefined && detail.energy !== null ? detail.energy : detail.calorie);
        if (miIsNum(cal) && cal >= 0) readings.push({
          source_id: `calories:${item.sid || "xiaomi"}:${timestamp}`, metric: "calories",
          recorded_at: recordedAt, value: Math.round(cal * 10) / 10, unit: "kcal", payload: detail });
        continue;
      }
      if (key === "steps") {
        const count = detail.steps;
        if (miIsNum(count) && count >= 0) readings.push({
          source_id: `steps:${item.time}`, metric: "steps", recorded_at: recordedAt,
          value: parseFloat(count), unit: "steps", payload: { source: item.sid || "xiaomi" } });
        continue;
      }
      let metric, value, unit;
      if (key === "weight") { metric = "weight"; value = detail.weight; unit = "kg"; }
      else {
        const mins = ["sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration"]
          .reduce((acc, f) => acc + (parseFloat(detail[f]) || 0), 0);
        metric = "sleep_hours"; value = Math.round((mins / 60) * 100) / 100; unit = "h";
      }
      if (miIsNum(value) && value >= 0) readings.push({
        source_id: `${key}:${item.sid || "xiaomi"}:${timestamp}`, metric,
        recorded_at: recordedAt, value: parseFloat(value), unit, payload: detail });
    }
  }
  const sport = await client.getSportAll(startMs, endMs, 20);
  if (sport.has_more) throw new MiErr("小米运动记录较多，请缩短同步日期范围后重试");
  for (const item of sport.sport_records) {
    const detail = miDecode(item);
    const recordedAt = miLocalDatetime(item.time);
    const duration = detail.duration;
    if (!recordedAt || !miIsNum(duration) || duration <= 0) continue;
    const activity = String(detail.sport_type || item.category || item.key || "运动").slice(0, 40);
    const cal = detail.calories || detail.calorie;
    const calVal = miIsNum(cal) && cal > 0 ? Math.round(cal * 10) / 10 : null;
    readings.push({
      source_id: `workout:${item.sid || "xiaomi"}:${item.time}:${item.key || ""}`, metric: "workout_minutes",
      recorded_at: recordedAt, value: Math.round((duration / 60) * 10) / 10, unit: "min",
      payload: { kind: activity, duration_seconds: duration, calories: calVal } });
    if (calVal) readings.push({
      source_id: `workout_cal:${item.sid || "xiaomi"}:${item.time}:${item.key || ""}`, metric: "calories",
      recorded_at: recordedAt, value: calVal, unit: "kcal", payload: { kind: activity, from_workout: true } });
  }
  return readings;
}
