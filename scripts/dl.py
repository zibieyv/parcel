"""把 URL 分块下载到文件，带进度输出。用法: python scripts/dl.py <url> <out>"""
import os
import sys
import time
import urllib.request

PROXY = "http://127.0.0.1:7897"


def main():
    if len(sys.argv) < 3:
        print("usage: dl.py <url> <out>")
        return 1
    url, out = sys.argv[1], sys.argv[2]
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
    )
    # 先试直连（Azure blob 直连更快），失败再走代理
    for label, op in (("direct", urllib.request.build_opener(urllib.request.ProxyHandler({}))), ("proxy", opener)):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with op.open(req, timeout=60) as r:
                total = int(r.headers.get("Content-Length") or 0)
                got = 0
                t0 = time.time()
                tmp = out + ".part"
                with open(tmp, "wb") as f:
                    while True:
                        chunk = r.read(1024 * 512)
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
                        if got % (1024 * 1024 * 4) < 1024 * 512:
                            speed = got / max(0.1, time.time() - t0) / 1024
                            print(f"{label} {got//1048576}MB/{total//1048576}MB {speed:.0f}KB/s", flush=True)
                os.replace(tmp, out)
                print(f"DONE {label} {got} bytes -> {out}", flush=True)
                return 0
        except Exception as e:
            print(f"{label} failed: {type(e).__name__}: {str(e)[:80]}", flush=True)
    return 1


if __name__ == "__main__":
    sys.exit(main())
