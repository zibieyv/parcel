'use strict';
/**
 * GET /api/envcheck
 * 只报环境变量的「名字是否存在」，不返回任何值，方便排查配置问题。
 */

module.exports = async function handler(req, res) {
  const names = [
    'UPSTASH_REDIS_REST_URL',
    'UPSTASH_REDIS_REST_TOKEN',
    'KV_REST_API_URL',
    'KV_REST_API_TOKEN',
    'STORAGE_URL',
    'STORAGE_TOKEN',
    'STORAGE_REST_URL',
    'STORAGE_REST_TOKEN',
    'APP_TOKEN',
    'TRACK17_TOKEN',
  ];
  const present = {};
  for (const n of names) present[n] = !!process.env[n];

  // 顺便看看有没有其他疑似 redis 的变量名（只给名字）
  const redisLike = Object.keys(process.env).filter((k) => /REDIS|UPSTASH|KV_/i.test(k));

  res.statusCode = 200;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify({
    ok: true,
    present,
    redisLikeNames: redisLike,
    deployment: process.env.VERCEL_GIT_COMMIT_SHA?.slice(0, 7) || 'unknown',
    env: process.env.VERCEL_ENV || 'unknown',
  }));
};
