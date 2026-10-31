'use strict';
/* 本地自测：用假 fetch 跑一遍 handler，验证解析与错误分支。 node test/local-test.js */

process.env.TRACK17_TOKEN = 'test-token';
process.env.TRACK17_BASE = 'https://mock.local/track/v2.2';

const assert = require('assert');

const SAMPLE = {
  code: 0,
  data: {
    accepted: [
      {
        number: 'EB123456789TW',
        track_info: {
          latest_status: { status: 40, sub_status: 'in_transit', sub_status_descr: 'Out for Delivery' },
          latest_event: { time_iso: '2026-10-30T10:00:00Z', description: '派送中，投递员：张师傅' },
          tracking: {
            providers: [
              {
                provider: { name: '中华邮政' },
                events: [
                  { time_iso: '2026-10-25T08:00:00Z', description: '交寄', location: '台北' },
                  { time_iso: '2026-10-28T09:00:00Z', description: '到达上海互换局', location: '上海' },
                  { time_iso: '2026-10-30T10:00:00Z', description: '派送中，投递员：张师傅', location: '上海闵行' },
                ],
              },
            ],
          },
        },
      },
    ],
    rejected: [],
  },
};

global.fetch = async (url, opts) => {
  const body = JSON.parse(opts.body);
  assert.ok(body[0].number === 'EB123456789TW', '请求里应带单号');
  assert.strictEqual(opts.headers['17token'], 'test-token', '应带 17token 头');
  return { ok: true, status: 200, text: async () => JSON.stringify(SAMPLE) };
};

const handler = require('../api/track.js');

function mockRes() {
  return {
    statusCode: 0,
    headers: {},
    body: '',
    setHeader(k, v) { this.headers[k.toLowerCase()] = v; },
    end(b) { this.body = b; },
    json() { return JSON.parse(this.body); },
  };
}

(async () => {
  // 1. 正常查询
  let res = mockRes();
  await handler({ method: 'GET', headers: {}, query: { no: ' eb123456789tw ' } }, res);
  const out = res.json();
  assert.strictEqual(res.statusCode, 200);
  assert.strictEqual(out.no, 'EB123456789TW');
  assert.strictEqual(out.status, 'delivering');
  assert.strictEqual(out.carrier, '中华邮政');
  assert.strictEqual(out.events.length, 3);
  assert.strictEqual(out.events[0].text, '派送中，投递员：张师傅');
  console.log('✓ 正常查询 / 大写归一 / 事件倒序 / 状态映射');

  // 2. 缺单号
  res = mockRes();
  await handler({ method: 'GET', headers: {}, query: {} }, res);
  assert.strictEqual(res.statusCode, 400);
  console.log('✓ 缺单号 → 400');

  // 3. 单号格式非法
  res = mockRes();
  await handler({ method: 'GET', headers: {}, query: { no: '坏单号' } }, res);
  assert.strictEqual(res.statusCode, 400);
  console.log('✓ 非法单号 → 400');

  // 4. CORS 预检
  res = mockRes();
  await handler({ method: 'OPTIONS', headers: {}, query: {} }, res);
  assert.strictEqual(res.statusCode, 204);
  assert.strictEqual(res.headers['access-control-allow-origin'], '*');
  console.log('✓ OPTIONS 预检');

  // 5. token 校验
  process.env.APP_TOKEN = 'secret1';
  res = mockRes();
  await handler({ method: 'GET', headers: {}, query: { no: 'EB123456789TW' } }, res);
  assert.strictEqual(res.statusCode, 401);
  res = mockRes();
  await handler({ method: 'GET', headers: { 'x-app-token': 'secret1' }, query: { no: 'EB123456789TW' } }, res);
  assert.strictEqual(res.statusCode, 200);
  delete process.env.APP_TOKEN;
  console.log('✓ APP_TOKEN 校验');

  // 6. 字符串状态（v2.2 真实返回长这样）
  global.fetch = async () => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({
      code: 0,
      data: {
        accepted: [{
          number: 'EB123456789TW',
          track_info: {
            latest_status: { status: 'NotFound' },
            latest_event: null,
            tracking: { providers: [] },
          },
        }],
        rejected: [],
      },
    }),
  });
  res = mockRes();
  await handler({ method: 'GET', headers: {}, query: { no: 'EB123456789TW' } }, res);
  let nf = res.json();
  assert.strictEqual(res.statusCode, 200);
  assert.strictEqual(nf.status, 'notfound');
  console.log('✓ 字符串状态 NotFound → notfound');

  global.fetch = async () => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({
      code: 0,
      data: {
        accepted: [{
          number: 'EB123456789TW',
          track_info: {
            latest_status: { status: 'Delivered', sub_status_descr: 'Delivered' },
            latest_event: { time_iso: '2026-10-30T10:00:00Z', description: '已签收' },
            tracking: { providers: [{ provider: { name: 'Chunghwa Post' }, events: [] }] },
          },
        }],
      },
    }),
  });
  res = mockRes();
  await handler({ method: 'GET', headers: {}, query: { no: 'EB123456789TW' } }, res);
  const dl = res.json();
  assert.strictEqual(dl.status, 'delivered');
  assert.strictEqual(dl.code, 50);
  console.log('✓ 字符串状态 Delivered → delivered');

  console.log('\n全部通过');
})().catch((e) => {
  console.error('✗ 失败：', e.message);
  process.exit(1);
});
