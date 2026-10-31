'use strict';
/**
 * 房间状态的合并逻辑（纯函数，方便单测）。
 *
 * 规则：
 *  - 按 item.id 合并，updatedAt 新的赢
 *  - 删除用墓碑（deleted: {id: 时间戳}），墓碑同样按时间取新
 *  - 分组取并集
 */

const MAX_ITEMS = 800;
const TOMBSTONE_DAYS = 30;

function ts(v) {
  const t = Date.parse(v || 0);
  return Number.isFinite(t) ? t : 0;
}

function trimItem(it) {
  const out = { ...it };
  if (Array.isArray(out.events) && out.events.length > 20) {
    out.events = out.events.slice(0, 20);
  }
  if (typeof out.note === 'string') out.note = out.note.slice(0, 300);
  if (typeof out.name === 'string') out.name = out.name.slice(0, 120);
  return out;
}

function mergeState(prev, incoming) {
  const base = prev && typeof prev === 'object' ? prev : {};
  const inc = incoming && typeof incoming === 'object' ? incoming : {};

  const byId = new Map();
  for (const it of base.items || []) {
    if (it && it.id) byId.set(it.id, it);
  }

  // 1. 先应用墓碑（两边都算）
  const deleted = { ...(base.deleted || {}) };
  for (const [id, when] of Object.entries(inc.deleted || {})) {
    if (!deleted[id] || ts(when) > ts(deleted[id])) deleted[id] = when;
  }
  const cutoff = Date.now() - TOMBSTONE_DAYS * 864e5;
  for (const id of Object.keys(deleted)) {
    if (ts(deleted[id]) < cutoff) delete deleted[id];
    else byId.delete(id);
  }

  // 2. 逐条合并
  for (const it of inc.items || []) {
    if (!it || !it.id) continue;
    if (deleted[it.id]) continue;
    const old = byId.get(it.id);
    if (!old) {
      byId.set(it.id, trimItem(it));
    } else if (ts(it.updatedAt) >= ts(old.updatedAt)) {
      byId.set(it.id, trimItem({ ...old, ...it }));
    }
  }

  // 3. 新加的包裹本地可能没有 updatedAt，补一个，避免永远被旧数据压住
  for (const [id, it] of byId) {
    if (!it.updatedAt) byId.set(id, { ...it, updatedAt: it.createdAt || new Date().toISOString() });
  }

  const items = [...byId.values()]
    .sort((a, b) => ts(b.createdAt) - ts(a.createdAt))
    .slice(0, MAX_ITEMS);

  const groups = [...new Set([...(base.groups || []), ...(inc.groups || [])].filter(Boolean))].slice(0, 30);

  return {
    items,
    groups,
    deleted,
    rev: (base.rev || 0) + 1,
    updatedAt: new Date().toISOString(),
  };
}

module.exports = { mergeState, ts };
