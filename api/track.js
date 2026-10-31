'use strict';
/**
 * GET /api/track?no=EB123456789TW
 *
 * 作用：由 Vercel（境外）代查 17TRACK，绕开国内网络问题。
 * 不存任何数据，纯转发 + 字段归一化。
 *
 * 环境变量：
 *   TRACK17_TOKEN  必填，17TRACK 控制台里的 Security Key
 *   APP_TOKEN      可选，设置后所有请求必须带 ?token= 或 x-app-token 头
 */

const TRACK17_BASE = process.env.TRACK17_BASE || 'https://api.17track.net/track/v2.2';

// 17TRACK v2.2 的 status 有时是数字、有时是字符串枚举，两种都要认
const STATUS_TEXT = {
  0: '查询不到',
  10: '已揽收',
  20: '运输中',
  30: '到达待取',
  35: '清关中',
  40: '派送中',
  50: '已签收',
  60: '异常',
  70: '超时未更新',
};

const STRING_STATUS = {
  NotFound: [0, 'notfound'],
  InfoReceived: [10, 'in_transit'],
  InTransit: [20, 'in_transit'],
  AvailableForPickup: [30, 'pickup'],
  Customs: [35, 'in_transit'],
  OutForDelivery: [40, 'delivering'],
  Delivered: [50, 'delivered'],
  Exception: [60, 'exception'],
  Expired: [70, 'exception'],
};

function statusOf(raw) {
  let code;
  if (typeof raw === 'string') {
    const hit = STRING_STATUS[raw];
    if (hit) return { code: hit[0], key: hit[1] };
    code = Number(raw);
  } else {
    code = Number(raw || 0);
  }
  if (!Number.isFinite(code)) return { code: 0, key: 'notfound' };
  if (code >= 60) return { code, key: 'exception' };
  if (code >= 50) return { code, key: 'delivered' };
  if (code >= 40) return { code, key: 'delivering' };
  if (code >= 30) return { code, key: 'pickup' };
  if (code >= 10) return { code, key: 'in_transit' };
  if (code === 0) return { code, key: 'notfound' };
  return { code, key: 'pending' };
}

function normalizeNo(raw) {
  return String(raw || '').replace(/\s+/g, '').toUpperCase();
}

async function call17track(path, payload, token) {
  const res = await fetch(`${TRACK17_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', '17token': token },
    body: JSON.stringify(payload),
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`17TRACK 返回非 JSON（HTTP ${res.status}）：${text.slice(0, 120)}`);
  }
  if (json.code !== 0) {
    throw new Error(`17TRACK 错误 ${json.code}：${json.message || '未知'}`);
  }
  return json.data || {};
}

function pickInfo(entry) {
  const info = entry && entry.track_info;
  if (!info) return null;
  const events = (info.tracking?.providers || [])
    .flatMap((p) => p.events || [])
    .map((e) => ({
      time: e.time_iso || e.time_utc || '',
      text: e.description || '',
      where: e.location || '',
    }))
    .filter((e) => e.text)
    .sort((a, b) => String(b.time).localeCompare(String(a.time)));

  const st = statusOf(info.latest_status?.status);
  return {
    code: st.code,
    status: st.key,
    statusText: info.latest_status?.sub_status_descr || STATUS_TEXT[st.code] || '',
    latest: info.latest_event?.description || events[0]?.text || '',
    latestTime: info.latest_event?.time_iso || events[0]?.time || '',
    carrier: info.tracking?.providers?.[0]?.provider?.name || '',
    events: events.slice(0, 40),
  };
}

module.exports = async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'x-app-token, content-type');
  if (req.method === 'OPTIONS') {
    res.statusCode = 204;
    return res.end();
  }

  const send = (code, body) => {
    res.statusCode = code;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', code === 200 ? 'public, s-maxage=60, stale-while-revalidate=300' : 'no-store');
    res.end(JSON.stringify(body));
  };

  try {
    const token = process.env.TRACK17_TOKEN;
    if (!token) return send(500, { ok: false, error: '服务端未配置 TRACK17_TOKEN' });

    const appToken = process.env.APP_TOKEN;
    if (appToken) {
      const given = req.headers['x-app-token'] || (req.query && req.query.token);
      if (given !== appToken) return send(401, { ok: false, error: 'token 不正确' });
    }

    const no = normalizeNo(req.query && req.query.no);
    if (!no) return send(400, { ok: false, error: '缺少单号参数 no' });
    if (!/^[A-Z0-9]{6,40}$/.test(no)) return send(400, { ok: false, error: '单号格式不对' });

    // 先注册（已注册会返回已存在，忽略即可），再取轨迹
    try {
      await call17track('/register', [{ number: no }], token);
    } catch (e) {
      // 注册失败不致命，gettrackinfo 仍可能拿到已注册的号
    }

    const data = await call17track('/gettrackinfo', [{ number: no }], token);
    const accepted = data.accepted || [];
    const rejected = data.rejected || [];

    const hit = accepted.find((a) => normalizeNo(a.number) === no) || accepted[0];
    const info = pickInfo(hit);

    if (!info) {
      const reason = rejected.find((r) => normalizeNo(r.number) === no)?.reason || '暂无轨迹';
      return send(404, { ok: false, no, error: String(reason) });
    }

    return send(200, {
      ok: true,
      no,
      ...info,
      updatedAt: new Date().toISOString(),
    });
  } catch (err) {
    return send(502, { ok: false, error: String((err && err.message) || err) });
  }
};
