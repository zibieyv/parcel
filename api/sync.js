'use strict';
/**
 * POST /api/sync?room=ABC123&token=xxx
 *   body: { items:[...], groups:[...], deleted:{id:ts} }
 *   → 把本地状态和服务端存的对一遍，返回合并后的结果并落库
 *
 * GET  /api/sync?room=ABC123&token=xxx
 *   → 只读当前房间状态
 *
 * 需要环境变量：
 *   UPSTASH_REDIS_REST_URL / UPSTASH_REDIS_REST_TOKEN
 *   APP_TOKEN（和查件接口共用）
 */

const { mergeState } = require('./_merge');

const ROOM_RE = /^[A-Z0-9]{4,12}$/;
const KEY_PREFIX = 'parcel:room:';

function redisBase() {
  // 不同接入方式注入的名字不一样，全都认：
  //  - Upstash 官方集成：UPSTASH_REDIS_REST_URL / _TOKEN
  //  - Vercel KV：KV_REST_API_URL / _TOKEN
  //  - Vercel Storage 面板带自定义前缀（比如 STORAGE）：STORAGE_URL / STORAGE_TOKEN
  const url =
    process.env.UPSTASH_REDIS_REST_URL ||
    process.env.KV_REST_API_URL ||
    process.env.STORAGE_URL ||
    process.env.STORAGE_REST_URL;
  const token =
    process.env.UPSTASH_REDIS_REST_TOKEN ||
    process.env.KV_REST_API_TOKEN ||
    process.env.STORAGE_TOKEN ||
    process.env.STORAGE_REST_TOKEN;
  if (!url || !token) throw new Error('未配置 Redis 环境变量，请在 Vercel 项目里连接 Upstash 后重新部署');
  return { url: url.replace(/\/$/, ''), token };
}

async function redisGet(key) {
  const { url, token } = redisBase();
  const r = await fetch(`${url}/get/${encodeURIComponent(key)}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!r.ok) throw new Error(`Redis GET ${r.status}`);
  const j = await r.json();
  return j.result ? String(j.result) : null;
}

async function redisSet(key, value, ttlSeconds) {
  const { url, token } = redisBase();
  const path = ttlSeconds
    ? `${url}/set/${encodeURIComponent(key)}?EX=${ttlSeconds}`
    : `${url}/set/${encodeURIComponent(key)}`;
  const r = await fetch(path, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'text/plain' },
    body: value,
  });
  if (!r.ok) throw new Error(`Redis SET ${r.status}`);
}

module.exports = async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'content-type, x-app-token');
  if (req.method === 'OPTIONS') {
    res.statusCode = 204;
    return res.end();
  }

  const send = (code, body) => {
    res.statusCode = code;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.end(JSON.stringify(body));
  };

  try {
    const appToken = process.env.APP_TOKEN;
    const given = (req.headers && req.headers['x-app-token']) || (req.query && req.query.token);
    if (appToken && given !== appToken) return send(401, { ok: false, error: 'token 不正确' });

    const room = String((req.query && req.query.room) || '').toUpperCase();
    if (!ROOM_RE.test(room)) return send(400, { ok: false, error: '房间码要 4-12 位字母数字' });

    const key = KEY_PREFIX + room;

    let prev = null;
    const raw = await redisGet(key);
    if (raw) {
      try { prev = JSON.parse(raw); } catch { prev = null; }
    }

    if (req.method === 'GET') {
      return send(200, {
        ok: true,
        rev: prev?.rev || 0,
        items: prev?.items || [],
        groups: prev?.groups || [],
        updatedAt: prev?.updatedAt || null,
      });
    }

    if (req.method !== 'POST') return send(405, { ok: false, error: '只支持 GET / POST' });

    let body = req.body;
    if (typeof body === 'string') {
      try { body = JSON.parse(body || '{}'); } catch { return send(400, { ok: false, error: 'body 不是 JSON' }); }
    }
    body = body || {};

    const merged = mergeState(prev, {
      items: Array.isArray(body.items) ? body.items : [],
      groups: Array.isArray(body.groups) ? body.groups : [],
      deleted: body.deleted && typeof body.deleted === 'object' ? body.deleted : {},
    });

    // 房间 90 天没人动就自动过期，免得白占存储
    await redisSet(key, JSON.stringify(merged), 90 * 86400);

    return send(200, {
      ok: true,
      rev: merged.rev,
      items: merged.items,
      groups: merged.groups,
      updatedAt: merged.updatedAt,
    });
  } catch (err) {
    return send(500, { ok: false, error: String((err && err.message) || err) });
  }
};
