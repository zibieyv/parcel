# parcel · 物流看板（后端部分）

给安卓端和网页端用的**代查接口**。部署在 Vercel（境外），由它去访问 17TRACK，手机只管调用 `parcel.zibieyv.com`。

不存任何数据，纯转发。

```
GET https://parcel.zibieyv.com/api/track?no=EB123456789TW&token=你的APP_TOKEN

{
  "ok": true,
  "no": "EB123456789TW",
  "status": "delivering",      // pending|in_transit|pickup|delivering|delivered|notfound
  "statusText": "Out for Delivery",
  "carrier": "中华邮政",
  "latest": "派送中，投递员：张师傅",
  "latestTime": "2026-10-30T10:00:00Z",
  "events": [{ "time": "...", "text": "...", "where": "..." }]
}
```

## 环境变量（Vercel → Settings → Environment Variables）

| 名称 | 必填 | 说明 |
|---|---|---|
| `TRACK17_TOKEN` | 是 | 17TRACK 控制台里的 Security Key |
| `APP_TOKEN` | 建议 | 自己编一串长随机串。设了之后请求必须带 `?token=` 或 `x-app-token` 头，防止别人白嫖你的查询额度 |

改了环境变量要 **Redeploy** 一次才生效。

## 部署（GitHub → Vercel）

1. GitHub 新建仓库，比如 `parcel`（Private 没问题），把本目录内容推上去。
2. Vercel → **Add New → Project** → 选这个仓库 → Framework Preset 选 **Other** → Deploy。
3. 部署完成后进 Settings → Environment Variables，填上面两个变量 → 重新 Deploy。
4. Settings → Domains → 添加 `parcel.zibieyv.com`（先在 `ruikeyao` 项目里 Remove 掉这个域名）。
5. 访问 `https://parcel.zibieyv.com/api/track?no=你的单号&token=你的APP_TOKEN` 验证。

阿里云的 A 记录（`parcel` → `216.198.79.1`）不用动。

### 也可以不用 GitHub

装了 Node 的话，在本目录执行：

```bash
npx vercel login
npx vercel --prod
```

一样能部署。

## 建议：函数区域选香港

Vercel → Settings → Functions → Region 选 **Hong Kong (hkg1)**，国内访问快不少。

## 本地自测

```bash
node test/local-test.js
```

用假 fetch 跑一遍解析和错误分支，不需要网络也不需要 key。

## 常见返回

| 状态码 | 含义 | 处理 |
|---|---|---|
| 200 | 正常 | — |
| 400 | 缺单号 / 单号格式不对 | 检查参数 |
| 401 | token 不对 | 检查 `APP_TOKEN` 和请求里的 token |
| 404 | 17TRACK 查不到（刚发货还没揽收最常见） | 过几小时再刷 |
| 502 | 17TRACK 侧报错 | 看 `error` 字段，常见是额度用尽 |

## 额度说明

免费额度约 200 单/月，**每次请求算一次查询**。所以手机端别做"每分钟自动轮询"，用刷新按钮手动触发，或者打开 App 时刷一次即可。
