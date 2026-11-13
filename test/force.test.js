'use strict';
/**
 * 离线单测：不打任何真接口，验证 force 语义 + 免费层 suspect 标记。
 * 跑法：node test/force.test.js
 */
process.env.APP_TOKEN = 't';
process.env.TRACK17_TOKEN = 'x';
process.env.KDNIAO_ID = '1';
process.env.KDNIAO_KEY = 'k';
process.env.KUAIDI100_CUSTOMER = 'c';
process.env.KUAIDI100_KEY = 'k';

const calls = [];
global.fetch = async (url) => {
  const u = String(url);
  calls.push(u);
  const headers = { get: () => null };
  if (u.includes('api.kdniao.com')) {
    // 模拟套餐到期
    return { status: 200, headers, text: async () => JSON.stringify({ Success: false, Reason: '业务错误[没有可用套餐]' }) };
  }
  if (u.includes('poll.kuaidi100.com')) {
    return { status: 200, headers, text: async () => JSON.stringify({ result: false, returnCode: '500', message: '查询无结果' }) };
  }
  if (u.includes('kuaidi100.com/query')) {
    // 免费层只会「命中圆通」，其它承运商一律 com 对不上
    const m = /type=([a-z0-9]+)/.exec(u);
    const type = m ? m[1] : '';
    if (type !== 'yuantong' && type !== 'yunda') return { status: 200, headers, text: async () => JSON.stringify({ status: '201', message: '查无结果', data: [] }) };
    return { status: 200, headers, text: async () => JSON.stringify({ status: '200', com: type, state: '0', data: [{ context: '测试轨迹', ftime: '2026-11-13 10:00:00' }] }) };
  }
  return { status: 200, headers, text: async () => '{}' };
};

const handler = require('../api/track.js');

function run(query) {
  return new Promise((resolve) => {
    const req = { method: 'GET', query, headers: { 'x-app-token': 't' } };
    const res = {
      statusCode: 0,
      setHeader() {},
      end(b) {
        let body;
        try { body = JSON.parse(b); } catch { body = b; }
        resolve({ code: res.statusCode, body });
      },
    };
    handler(req, res);
  });
}

const hit = (p) => calls.some((u) => u.includes(p));
let failed = 0;
function check(name, cond, extra) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : '  ' + JSON.stringify(extra)}`);
  if (!cond) failed += 1;
}

(async () => {
  // 1. force=kd100official：只准走官方层
  calls.length = 0;
  let r = await run({ no: '1234567890123', force: 'kd100official' });
  check('force=kd100official 不碰网页层', !hit('kuaidi100.com/query'), calls);
  check('force=kd100official 不走快递鸟', !hit('kdniao'), calls);
  check('force=kd100official 只报官方层', r.code === 404 && r.body.tried.every((t) => t.provider === '快递100官方'), r.body);

  // 2. force=kd100web：只准走网页层，且不打 suspect 标（人工点名要的）
  calls.length = 0;
  r = await run({ no: 'YT1234567890123', force: 'kd100web' });
  check('force=kd100web 不碰官方层', !hit('poll.kuaidi100.com'), calls);
  check('force=kd100web 命中网页层且不标可疑', r.code === 200 && r.body.source === 'kuaidi100' && r.body.suspect === false, r.body);

  // 3. 默认链路 + 纯数字号：免费层照样兜底，但要标 suspect
  calls.length = 0;
  r = await run({ no: '1234567890123' });
  check('默认链路 纯数字仍走免费层', hit('kuaidi100.com/query'), calls);
  check('默认链路 纯数字结果标 suspect', r.code === 200 && r.body.suspect === true, r.body);

  // 4. 默认链路 + 带字母号：免费层兜底，不标 suspect
  calls.length = 0;
  r = await run({ no: 'YT1234567890123' });
  check('默认链路 字母号可用免费层且可信', r.code === 200 && r.body.source === 'kuaidi100' && r.body.suspect === false, r.body);

  // 5. 快递鸟账号级错误只试一次就跳出
  calls.length = 0;
  await run({ no: '1234567890123' });
  const kdnCalls = calls.filter((u) => u.includes('kdniao')).length;
  check('快递鸟账号级错误只尝试 1 次', kdnCalls === 1, kdnCalls);

  console.log(failed ? `\n${failed} 项失败` : '\n全部通过');
  process.exit(failed ? 1 : 0);
})();
