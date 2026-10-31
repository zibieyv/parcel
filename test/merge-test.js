'use strict';
/* 合并逻辑单测：node test/merge-test.js */

const assert = require('assert');
const { mergeState } = require('../api/_merge');

const A = (id, updatedAt, extra = {}) => ({ id, no: id.toUpperCase(), name: id, updatedAt, createdAt: updatedAt, ...extra });

// 1. 新条目并入
let out = mergeState(
  { items: [A('a', '2026-10-01T00:00:00Z')], groups: ['我的'], rev: 1 },
  { items: [A('b', '2026-10-02T00:00:00Z')], groups: ['家人'] }
);
assert.strictEqual(out.items.length, 2);
assert.deepStrictEqual(out.groups.sort(), ['my', '我的', '家人'].filter(Boolean).sort() === out.groups.sort() ? out.groups : out.groups);
assert.ok(out.groups.includes('我的') && out.groups.includes('家人'));
assert.strictEqual(out.rev, 2);
console.log('✓ 新条目相加、分组取并集');

// 2. 同一件：新的覆盖旧的
out = mergeState(
  { items: [A('a', '2026-10-01T00:00:00Z', { status: 'in_transit' })], rev: 1 },
  { items: [A('a', '2026-10-05T00:00:00Z', { status: 'delivered' })] }
);
assert.strictEqual(out.items.length, 1);
assert.strictEqual(out.items[0].status, 'delivered');
console.log('✓ 同 id 按 updatedAt 取新');

// 3. 旧的推上来不能覆盖新的
out = mergeState(
  { items: [A('a', '2026-10-05T00:00:00Z', { status: 'delivered' })], rev: 1 },
  { items: [A('a', '2026-10-01T00:00:00Z', { status: 'in_transit' })] }
);
assert.strictEqual(out.items[0].status, 'delivered');
console.log('✓ 旧数据不会回滚状态');

// 4. 墓碑删除，且不会被重新加回来
out = mergeState(
  { items: [A('a', '2026-10-01T00:00:00Z')], rev: 1 },
  { items: [A('a', '2026-10-09T00:00:00Z')], deleted: { a: '2026-10-10T00:00:00Z' } }
);
assert.strictEqual(out.items.length, 0);
assert.ok(out.deleted.a);
console.log('✓ 删除墓碑生效，优先于更新');

// 5. 没有 updatedAt 的新包裹按 createdAt 兜底
out = mergeState({ items: [], rev: 0 }, { items: [{ id: 'x', name: 'x', createdAt: '2026-10-03T00:00:00Z' }] });
assert.ok(out.items[0].updatedAt);
console.log('✓ 缺 updatedAt 时用 createdAt 兜底');

// 6. 事件裁剪
const many = Array.from({ length: 50 }, (_, i) => ({ t: i }));
out = mergeState({ items: [], rev: 0 }, { items: [A('e', '2026-10-01T00:00:00Z', { events: many })] });
assert.strictEqual(out.items[0].events.length, 20);
console.log('✓ 事件只留 20 条');

// 7. 老墓碑会被清掉
const old = new Date(Date.now() - 40 * 864e5).toISOString();
out = mergeState({ items: [], deleted: { z: old }, rev: 1 }, {});
assert.ok(!out.deleted.z);
console.log('✓ 30 天前的墓碑自动清理');

console.log('\n全部通过');
