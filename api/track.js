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

/**
 * 国内单号光靠自动识别经常匹配不到承运商，
 * 这里按前缀/长度给出候选，依次尝试注册。
 */
function isDomestic(no) {
  const n = String(no || '').toUpperCase();
  return /^(YT|ZT|YD|JT|SF)\d{10,}/.test(n)
    || /^JD[A-Z0-9]{10,}/.test(n)
    || /^9[1-8]\d{11}$/.test(n)
    || /^\d{12,15}$/.test(n);
}

function carrierCandidates(no) {
  const n = no.toUpperCase();
  const out = [];

  if (/^YT\d{10,}/.test(n)) out.push('yuantong');
  if (/^ZT\d{10,}/.test(n)) out.push('zhongtong');
  if (/^YD\d{10,}/.test(n)) out.push('yunda');
  if (/^JT\d{10,}/.test(n)) out.push('jtexpress');
  if (/^SF\d{10,}/.test(n)) out.push('shunfeng');
  if (/^JD[A-Z0-9]{10,}/.test(n)) out.push('jd');
  if (/^DPK\d+/.test(n)) out.push('dhl');
  if (/^[A-Z]{2}\d{9}[A-Z]{2}$/.test(n)) out.push('china-post', 'ems');

  // 中国邮政：93/94/95/96/97/98/91 开头的 13 位
  if (/^9[1-8]\d{11}$/.test(n)) out.push('youzhengguonei', 'ems', 'china-post');

  // 纯数字 12–15 位：韵达/中通/圆通/申通都可能，逐个试
  if (/^\d{12,15}$/.test(n)) {
    out.push('yunda', 'zhongtong', 'yuantong', 'shentong', 'jtexpress');
  }

  return [...new Set(out)];
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


/** 快递100 的类型代码（国内承运商） */
const KD100_TYPE = {
  yuantong: 'yuantong',
  zhongtong: 'zhongtong',
  yunda: 'yunda',
  shentong: 'shentong',
  jtexpress: 'jtexpress',
  shunfeng: 'shunfeng',
  youzhengguonei: 'youzhengguonei',
  ems: 'ems',
  jd: 'jd',
};


// 轨迹文本里出现别的快递品牌名，说明这单被匹配到别家了
const BRANDS = {
  yuantong: '\u5706\u901a', zhongtong: '\u4e2d\u901a', yunda: '\u97f5\u8fbe',
  shentong: '\u7533\u901a', shunfeng: '\u987a\u4e30', jtexpress: '\u6781\u5154',
  youzhengguonei: '\u90ae\u653f', ems: 'EMS', jd: '\u4eac\u4e1c',
};
function brandMismatch(text, type) {
  const s = String(text || '');
  for (const k of Object.keys(BRANDS)) {
    if (k === type) continue;
    if (s.includes(BRANDS[k])) return true;
  }
  return false;
}

const KD100_STATE = {
  0: [20, 'in_transit'],
  1: [10, 'in_transit'],
  2: [60, 'exception'],
  3: [50, 'delivered'],
  4: [60, 'exception'],
  5: [40, 'delivering'],
  6: [60, 'exception'],
  7: [60, 'exception'],
  8: [35, 'in_transit'],
  14: [60, 'exception'],
};

/** 走快递100的公开查询接口，国内单号命中率比 17TRACK 高得多 */
let kdCookie = '';
async function ensureKdCookie() {
  if (kdCookie) return kdCookie;
  try {
    const r = await fetch('https://www.kuaidi100.com/', {
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36' },
    });
    const raw = r.headers.get('set-cookie') || '';
    kdCookie = raw.split(/,(?=[^ ;]+=)/).map((c) => c.split(';')[0]).join('; ');
  } catch (e) {
    kdCookie = '';
  }
  return kdCookie;
}

async function queryKuaidi100(no, type, phone) {
  const temp = '0.' + String(no).length;
  const url = `https://www.kuaidi100.com/query?type=${encodeURIComponent(type)}&postid=${encodeURIComponent(no)}&temp=${temp}&phone=`;
  const cookie = await ensureKdCookie();
  const res = await fetch(url, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36',
      'Referer': 'https://www.kuaidi100.com/',
      'Accept': 'application/json, text/plain, */*',
      ...(cookie ? { Cookie: cookie } : {}),
    },
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { return null; }
  if (json.status !== '200' || !Array.isArray(json.data) || !json.data.length) return null;

  // 快递100 的 type 只是提示，它可能自动匹配到别的承运商，
  // 这时轨迹属于别的包裹，必须丢掉
  const com = String(json.com || '').toLowerCase();
  if (com && com !== String(type).toLowerCase()) return null;

  const joined = (json.data || []).slice(0, 3).map((e) => e.context || '').join(' ');
  if (brandMismatch(joined, type)) return null;

  const st = KD100_STATE[Number(json.state)] || [20, 'in_transit'];
  const events = json.data.map((e) => ({
    time: e.ftime || e.time || '',
    text: e.context || '',
    where: '',
  })).filter((e) => e.text && !/查无|暂无|无记录|查询无结果|单号不存在|请稍后/.test(e.text));

  return {
    code: st[0],
    status: st[1],
    statusText: events[0]?.text?.slice(0, 20) || '',
    latest: events[0]?.text || '',
    latestTime: events[0]?.time || '',
    carrier: type,
    events: events.slice(0, 40),
    source: 'kuaidi100',
  };
}



/* ---------------- 快递100 官方实时查询（正式API，100次/2年） ---------------- */
const KD100_OFFICIAL_STATE = {
  0: [20, 'in_transit'],
  1: [10, 'in_transit'],
  2: [60, 'exception'],
  3: [50, 'delivered'],
  4: [60, 'exception'],
  5: [40, 'delivering'],
  6: [60, 'exception'],
  7: [60, 'exception'],
  8: [35, 'in_transit'],
  14: [60, 'exception'],
};

async function queryKd100Official(no, com, phone) {
  const customer = process.env.KUAIDI100_CUSTOMER;
  const key = process.env.KUAIDI100_KEY;
  if (!customer || !key) return null;

  const param = JSON.stringify({ com, num: no, phone: phone || '', resultv2: '4', show: '0' });
  const sign = crypto.createHash('md5').update(param + key + customer, 'utf8').digest('hex').toUpperCase();
  const body = new URLSearchParams({ customer, sign, param });

  const res = await fetch('https://poll.kuaidi100.com/poll/query.do', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { return null; }
  if (String(json.status) !== '200' || !Array.isArray(json.data) || !json.data.length) return null;

  const st = KD100_OFFICIAL_STATE[Number(json.state)] || [20, 'in_transit'];
  const events = json.data.map((e) => ({
    time: e.ftime || e.time || '',
    text: e.context || '',
    where: '',
  })).filter((e) => e.text).reverse();

  return {
    code: st[0],
    status: st[1],
    statusText: events[0]?.text?.slice(0, 20) || '',
    latest: events[0]?.text || '',
    latestTime: events[0]?.time || '',
    carrier: com,
    events: events.slice(0, 40),
    source: 'kuaidi100-official',
  };
}

/* ---------------- 快递鸟（正规接口，按单号+手机查，不串号） ---------------- */
const crypto = require('crypto');

const KDN_SHIPPER = {
  yuantong: 'YTO',
  zhongtong: 'ZTO',
  yunda: 'YD',
  shentong: 'STO',
  jtexpress: 'JTSD',
  shunfeng: 'SF',
  youzhengguonei: 'YZPY',
  ems: 'EMS',
  jd: 'JD',
};

const KDN_STATE = {
  0: [0, 'notfound'],      // 无轨迹
  1: [10, 'in_transit'],   // 已揽收
  2: [20, 'in_transit'],   // 在途中
  3: [50, 'delivered'],    // 已签收
  4: [60, 'exception'],    // 问题件
  5: [40, 'delivering'],   // 派件中
  6: [60, 'exception'],    // 退回
  7: [60, 'exception'],    // 转投
  8: [35, 'in_transit'],   // 清关
  14: [60, 'exception'],   // 拒签
};

function kdnSign(json, key) {
  // 快递鸟要的是 base64(md5 的十六进制字符串)，不是 base64(md5 原始字节)
  const hex = crypto.createHash('md5').update(json + key, 'utf8').digest('hex');
  return Buffer.from(hex, 'utf8').toString('base64');
}

async function queryKdn(no, shipperCode, phone) {
  const id = process.env.KDNIAO_ID;
  const key = process.env.KDNIAO_KEY;
  if (!id || !key) return null;

  const payload = { OrderCode: '', ShipperCode: shipperCode, LogisticCode: no };
  if (phone) payload.CustomerName = phone;
  const requestData = JSON.stringify(payload);

  const body = new URLSearchParams({
    EBusinessID: id,
    RequestType: '8001',
    RequestData: requestData,
    DataSign: kdnSign(requestData, key),
    DataType: '2',
  });

  const res = await fetch('https://api.kdniao.com/Ebusiness/EbusinessOrderHandle.aspx', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { return null; }
  if (!json.Success || !Array.isArray(json.Traces) || !json.Traces.length) return null;

  const st = KDN_STATE[Number(json.State)] || [20, 'in_transit'];
  const events = json.Traces.map((t) => ({
    time: t.AcceptTime || '',
    text: t.AcceptStation || '',
    where: '',
  })).filter((e) => e.text).reverse();

  return {
    code: st[0],
    status: st[1],
    statusText: events[0]?.text?.slice(0, 20) || '',
    latest: events[0]?.text || '',
    latestTime: events[0]?.time || '',
    carrier: shipperCode,
    events: events.slice(0, 40),
    source: 'kdniao',
  };
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

    // 指定了承运商就只试那一个，否则按候选依次试
    const forced = normalizeNo(req.query && req.query.carrier).toLowerCase();
    const phoneTail = String((req.query && req.query.phone) || '').replace(/\D/g, '').slice(0, 4);
    const candidates = forced ? [forced] : carrierCandidates(no);

    let info = null;
    let lastReason = '暂无轨迹';
    const debug = [];

    // 国内单号直接跳过 17TRACK：它对国内承运商要么没数据，要么匹配到别的单
    const domestic = isDomestic(no);
    const force = String((req.query && req.query.force) || '').toLowerCase();

    // 先试无承运商（国际单号多半能自动识别）
    const tries = domestic ? [] : [null, ...candidates];
    for (const carrier of tries) {
      let regErr = '';
      try {
        const payload = carrier ? [{ number: no, carrier }] : [{ number: no }];
        await call17track('/register', payload, token);
      } catch (e) {
        regErr = String((e && e.message) || e).slice(0, 120);
      }

      let data;
      try {
        data = await call17track('/gettrackinfo', [{ number: no }], token);
      } catch (e) {
        lastReason = String((e && e.message) || e).slice(0, 120);
        continue;
      }

      const accepted = data.accepted || [];
      const rejected = data.rejected || [];
      const hit = accepted.find((a) => normalizeNo(a.number) === no) || accepted[0];
      const got = pickInfo(hit);

      if (got && (got.events.length > 0 || got.code > 0)) {
        info = got;
        break;
      }

      const rej = rejected.find((r) => normalizeNo(r.number) === no);
      if (rej && rej.reason) lastReason = String(rej.reason);
      debug.push({ carrier: carrier || 'auto', regErr, accepted: accepted.length, rejected: rejected.length, reason: rej && rej.reason, code: got ? got.code : null });
    }

    // 国内号优先走快递鸟（正规接口，带手机号不串号）
    if (!info && domestic && force !== 'kd100official' && force !== 'kd100web') {
      const codes = (forced ? [forced] : carrierCandidates(no)).map((c) => KDN_SHIPPER[c]).filter(Boolean);
      const list = codes.length ? codes : ['YZPY', 'YTO', 'ZTO', 'YD', 'STO', 'JTSD'];
      for (const code of [...new Set(list)].slice(0, 4)) {
        try {
          const kdn = await queryKdn(no, code, phoneTail);
          debug.push({ kdn: code, hit: !!(kdn && kdn.events.length) });
          if (kdn && kdn.events.length) { info = kdn; break; }
        } catch (e) {
          debug.push({ kdn: code, err: String((e && e.message) || e).slice(0, 80) });
        }
      }
    }

    // 快递鸟没有（或额度用完）时，用快递100 官方 API
    if (!info && domestic) {
      const codes = (forced ? [forced] : carrierCandidates(no)).filter((c) => KD100_TYPE[c]);
      const list = codes.length ? codes : ['youzhengguonei', 'yuantong', 'zhongtong', 'yunda'];
      for (const com of [...new Set(list)].slice(0, 3)) {
        try {
          const kd = await queryKd100Official(no, com, phoneTail);
          debug.push({ kd100official: com, hit: !!(kd && kd.events.length) });
          if (kd && kd.events.length) { info = kd; break; }
        } catch (e) {
          debug.push({ kd100official: com, err: String((e && e.message) || e).slice(0, 80) });
        }
      }
    }

    // 再退到快递100 网页接口
    if (!info) {
      const types = (forced ? [forced] : carrierCandidates(no)).map((c) => KD100_TYPE[c]).filter(Boolean);
      const tryTypes = types.length ? types : ['yunda', 'zhongtong', 'yuantong', 'shentong', 'youzhengguonei'];
      // 指定了承运商但查不到时，自动候选也试一遍
      if (forced) {
        for (const t of (carrierCandidates(no).map((c) => KD100_TYPE[c]).filter(Boolean)).slice(0, 4)) {
          if (tryTypes.includes(t)) continue;
          tryTypes.push(t);
        }
      }

      for (const t of tryTypes.slice(0, 5)) {
        try {
          const kd = await queryKuaidi100(no, t, phoneTail);
          debug.push({ kd100: t, hit: !!(kd && kd.events.length) });
          if (kd && kd.events.length) { info = kd; break; }
        } catch (e) {
          debug.push({ kd100: t, err: String((e && e.message) || e).slice(0, 100) });
        }
      }
    }

    if (!info) {
      return send(404, { ok: false, no, error: lastReason, debug });
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
