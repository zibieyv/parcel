"""带续传的 APK 抓取：每次失败就重新拿一次 GitHub 的临时地址，接着下。

用法：python scripts/grab_apk.py <sha前缀> <输出文件>
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

PROXY = "http://127.0.0.1:7897"
REPO = "zibieyv/parcel"
TOKEN = os.environ.get("GH_TOKEN", "")
API = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
)
DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


NOREDIR = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}), NoRedirect
)


def api(path):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={
            "Authorization": "Bearer " + TOKEN,
            "Accept": "application/vnd.github+json",
            "User-Agent": "grab",
        },
    )
    return json.loads(API.open(req, timeout=30).read().decode())


def fresh_url(run_id):
    arts = api(f"/repos/{REPO}/actions/runs/{run_id}/artifacts")["artifacts"]
    art = arts[0]
    try:
        NOREDIR.open(
            urllib.request.Request(
                art["archive_download_url"],
                headers={"Authorization": "Bearer " + TOKEN},
            ),
            timeout=40,
        )
        raise RuntimeError("没有拿到重定向")
    except urllib.error.HTTPError as e:
        return e.headers["Location"], art["size_in_bytes"]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    sha, out = sys.argv[1], sys.argv[2]
    runs = api(f"/repos/{REPO}/actions/runs?per_page=10")["workflow_runs"]
    run = next(r for r in runs if r["head_sha"].startswith(sha))
    run_id = run["id"]

    part = out + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    total = 0
    t0 = time.time()

    for attempt in range(40):
        try:
            url, total = fresh_url(run_id)
            headers = {"User-Agent": "Mozilla/5.0"}
            if have:
                headers["Range"] = f"bytes={have}-"
            req = urllib.request.Request(url, headers=headers)
            # 实测：走代理 600KB/s，直连只有 65KB/s
            with API.open(req, timeout=60) as r:
                if have and r.status != 206:
                    have = 0
                    open(part, "wb").close()
                with open(part, "ab") as f:
                    while True:
                        chunk = r.read(1024 * 256)
                        if not chunk:
                            break
                        f.write(chunk)
                        have += len(chunk)
                        if have % (1024 * 1024) < 1024 * 256:
                            speed = have / max(0.1, time.time() - t0) / 1024
                            print(f"{have//1048576}MB/{total//1048576}MB {speed:.0f}KB/s", flush=True)
            if total and have >= total:
                os.replace(part, out)
                print(f"DONE {have} bytes", flush=True)
                return 0
        except Exception as e:
            print(f"attempt {attempt}: {type(e).__name__} {str(e)[:70]} (have {have//1024}KB)", flush=True)
            time.sleep(2)
    print("FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
