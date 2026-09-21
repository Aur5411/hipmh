# -*- coding: utf-8 -*-
"""打包交付：APK + 同名 zip(内装 APK) + 工程源码 zip(排除 .git/.gradle/build) 放入桌面 1 文件夹。"""
import os, shutil, zipfile, glob

VER = "2.1.0"
NAME = "嘻皮漫画"
PROJ = r"C:\Users\Administrator\WorkBuddy\2026-09-19-23-37-20\HipManga"
DEST = r"C:\Users\Administrator\Desktop\1"

os.makedirs(DEST, exist_ok=True)

apk_src = os.path.join(PROJ, "app", "build", "outputs", "apk", "release", "app-release.apk")
apk_name = "%s-v%s.apk" % (NAME, VER)
apk_dst = os.path.join(DEST, apk_name)

# 1) 复制 APK
shutil.copy2(apk_src, apk_dst)
size = os.path.getsize(apk_dst)

# 2) 同名 zip（内含该 APK）
zip_apk = os.path.join(DEST, "%s-v%s.zip" % (NAME, VER))
with zipfile.ZipFile(zip_apk, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(apk_dst, apk_name)

# 3) 工程源码 zip
zip_src = os.path.join(DEST, "%s-v%s-工程源码.zip" % (NAME, VER))
EXCLUDE_DIRS = {".git", ".gradle", "build", ".idea", ".workbuddy", ".gradle-home",
                "outputs", ".cache", "node_modules"}
EXCLUDE_FILES = {"local.properties", "keystore.properties", "hipmh.keystore",
                  "build_v14.log", "build.log", "build_log.txt",
                  "_build.txt", "_jscheck.txt", "_env.txt", "_deliver.txt"}
# 一次性排查脚本（diag_*/probe_*/diagN_*）与临时 runner（下划线开头）不进源码 zip
EXCLUDE_PREFIXES = ("diag_", "probe", "probe_", "diag", "_")
n = 0
with zipfile.ZipFile(zip_src, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(PROJ):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        # 跳过 app/build 下的全部内容
        if os.path.relpath(root, PROJ).replace("\\", "/").startswith("app/build"):
            continue
        for f in files:
            if f in EXCLUDE_FILES:
                continue
            if f.startswith(EXCLUDE_PREFIXES):
                continue
            p = os.path.join(root, f)
            rel = os.path.relpath(p, PROJ)
            try:
                z.write(p, rel)
                n += 1
            except Exception as e:
                print("skip", rel, e)

print("APK      : %s  (%d B)" % (apk_name, size))
print("APK zip  : %s  (%d B)" % (os.path.basename(zip_apk), os.path.getsize(zip_apk)))
print("SRC zip  : %s  (%d B, %d files)" % (os.path.basename(zip_src),
                                           os.path.getsize(zip_src), n))
print("")
print("== 源码 zip 内容清单 ==")
with zipfile.ZipFile(zip_src) as z:
    for i in sorted(z.infolist(), key=lambda x: x.filename):
        print("  %-58s %6d" % (i.filename, i.file_size))
print("")
print("== DEST 目录现状 ==")
for f in sorted(os.listdir(DEST)):
    if NAME in f or "嘻皮" in f:
        print("  %-50s %d B" % (f, os.path.getsize(os.path.join(DEST, f))))
