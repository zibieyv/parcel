"""本地编译 APK：下载 JDK17 + Android SDK + Gradle，然后 assembleDebug。
不依赖 GitHub Actions。所有下载走本机代理 127.0.0.1:7897。
"""
import os
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(ROOT, "..", ".build")
PROXY = "http://127.0.0.1:7897"
OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
)

JDK_URL = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip"
CLT_URL = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
GRADLE_URL = "https://services.gradle.org/distributions/gradle-8.7-bin.zip"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 1024 * 1024:
        log(f"已存在 {os.path.basename(dest)} ({os.path.getsize(dest)//1048576}MB)")
        return
    log(f"下载 {url.split('/')[-1]}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with OPENER.open(url, timeout=60) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        got = 0
        last = 0
        while True:
            chunk = r.read(1024 * 256)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            if total and got - last > total // 10:
                last = got
                log(f"  {got*100//total}% ({got//1048576}MB/{total//1048576}MB)")
    os.replace(tmp, dest)
    log(f"完成 {os.path.basename(dest)}")


def unzip(zip_path, target):
    log(f"解压 {os.path.basename(zip_path)}")
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(target)


def find_dir(base, name):
    for root, dirs, _ in os.walk(base):
        if name in dirs:
            return os.path.join(root, name)
    return None


def run(cmd, env=None, cwd=None, input_text=None):
    log("$ " + " ".join(cmd))
    p = subprocess.run(
        cmd, env=env, cwd=cwd, input=input_text,
        capture_output=True, text=True, errors="replace"
    )
    out = (p.stdout or "") + (p.stderr or "")
    tail = out.strip().splitlines()[-15:]
    for line in tail:
        print("   ", line[:200], flush=True)
    return p.returncode, out


def main():
    os.makedirs(TOOLS, exist_ok=True)

    # 1. JDK 17
    jdk_zip = os.path.join(TOOLS, "jdk17.zip")
    download(JDK_URL, jdk_zip)
    if not os.path.isdir(os.path.join(TOOLS, "jdk17")):
        unzip(jdk_zip, TOOLS)
        jdk_dir = find_dir(TOOLS, "bin")
        for d in os.listdir(TOOLS):
            if d.startswith("amazon-corretto") and os.path.isdir(os.path.join(TOOLS, d)):
                os.rename(os.path.join(TOOLS, d), os.path.join(TOOLS, "jdk17"))
    java_home = os.path.join(TOOLS, "jdk17")
    log(f"JAVA_HOME={java_home}")

    # 2. Android cmdline-tools
    clt_zip = os.path.join(TOOLS, "cmdline-tools.zip")
    download(CLT_URL, clt_zip)
    sdk = os.path.join(TOOLS, "sdk")
    latest = os.path.join(sdk, "cmdline-tools", "latest")
    if not os.path.isdir(latest):
        tmp = os.path.join(TOOLS, "clt_tmp")
        unzip(clt_zip, tmp)
        os.makedirs(os.path.join(sdk, "cmdline-tools"), exist_ok=True)
        src = os.path.join(tmp, "cmdline-tools")
        if os.path.isdir(src):
            os.replace(src, latest)
        log(f"SDK cmdline-tools -> {latest}")

    sdkmanager = os.path.join(latest, "bin", "sdkmanager.bat")
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env["PATH"]
    env["ANDROID_HOME"] = sdk
    env["ANDROID_SDK_ROOT"] = sdk

    log("接受 SDK 许可")
    run([sdkmanager, "--sdk_root=" + sdk, "--licenses"], env=env, input_text="y\n" * 40)

    log("安装 platform-tools / android-34 / build-tools 34.0.0")
    rc, _ = run([sdkmanager, "--sdk_root=" + sdk, "platform-tools", "platforms;android-34",
                 "build-tools;34.0.0"], env=env, input_text="y\n" * 20)
    if rc != 0:
        log("SDK 安装失败")
        return 1

    # 3. Gradle
    gradle_zip = os.path.join(TOOLS, "gradle.zip")
    download(GRADLE_URL, gradle_zip)
    gradle_home = os.path.join(TOOLS, "gradle-8.7")
    if not os.path.isdir(gradle_home):
        unzip(gradle_zip, TOOLS)

    # 4. local.properties
    with open(os.path.join(ROOT, "..", "android", "local.properties"), "w") as f:
        f.write("sdk.dir=" + sdk.replace("\\", "\\\\") + "\n")

    gradle_bat = os.path.join(gradle_home, "bin", "gradle.bat")
    env["GRADLE_OPTS"] = (
        "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 "
        "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 "
        "-Dfile.encoding=UTF-8"
    )
    env["JAVA_TOOL_OPTIONS"] = ""

    log("开始编译 APK")
    rc, out = run([gradle_bat, "assembleDebug", "--no-daemon", "--stacktrace"],
                  env=env, cwd=os.path.join(ROOT, "..", "android"))
    if rc != 0:
        log("编译失败")
        with open(os.path.join(TOOLS, "build-error.log"), "w", encoding="utf-8") as f:
            f.write(out)
        return 1

    apk = os.path.join(ROOT, "..", "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if os.path.exists(apk):
        log(f"APK OK: {os.path.abspath(apk)} ({os.path.getsize(apk)//1024}KB)")
        return 0
    log("没找到 APK 产物")
    return 1


if __name__ == "__main__":
    sys.exit(main())
