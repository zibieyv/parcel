"""从 GitHub Actions 取最新构建产物，存成 apk/parcel-<版本>.apk

用法：
    set GH_TOKEN=github_pat_xxx
    python scripts/fetch_apk.py 0.5 430cb27

第二个参数是提交 sha 前缀，用来定位对应的那次构建。
"""
import io
import json
import os
import sys
import urllib.error
import urllib.request
import zipfile

PROXY = "http://127.0.0.1:7897"
REPO = "zibieyv/parcel"
TOKEN = os.environ.get("GH_TOKEN", "")
OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def api(path):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={
            "Authorization": "Bearer " + TOKEN,
            "Accept": "application/vnd.github+json",
            "User-Agent": "parcel-release",
        },
    )
    return json.loads(OPENER.open(req, timeout=30).read().decode())


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    version, sha = sys.argv[1], sys.argv[2]

    runs = api(f"/repos/{REPO}/actions/runs?per_page=10")["workflow_runs"]
    run = next((r for r in runs if r["head_sha"].startswith(sha)), None)
    if not run:
        print("找不到对应构建")
        return 1
    if run["conclusion"] != "success":
        print("构建未成功：", run["conclusion"])
        return 1

    art = api(f"/repos/{REPO}/actions/runs/{run['id']}/artifacts")["artifacts"][0]
    opener2 = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}), NoRedirect
    )
    try:
        opener2.open(
            urllib.request.Request(
                art["archive_download_url"], headers={"Authorization": "Bearer " + TOKEN}
            ),
            timeout=60,
        )
        print("没拿到重定向")
        return 1
    except urllib.error.HTTPError as e:
        location = e.headers["Location"]

    # 直连 Azure blob，不走代理（走代理反而容易断）
    data = urllib.request.urlopen(
        urllib.request.Request(location, headers={"User-Agent": "Mozilla/5.0"}), timeout=120
    ).read()

    z = zipfile.ZipFile(io.BytesIO(data))
    apk = z.read("app-debug.apk")
    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "apk")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"parcel-{version}.apk")
    with open(out, "wb") as f:
        f.write(apk)
    print(f"OK {out} {len(apk)} bytes, v2 signed: {b'APK Sig Block 42' in apk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
