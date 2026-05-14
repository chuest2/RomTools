# RomTools App — Android 端 ROM 定制

把原仓库的 `setup.sh + start.sh` 整套流程封装为一个 **不依赖 Termux** 的安卓 App，
在手机上完成 HyperOS 的镜像定制；最终的 `flash.bat` 仍需在 PC 上 fastboot 刷入。

## 目录映射（原仓库 ↔ App）
| 原仓库 | App 中位置 |
| --- | --- |
| `start.sh` 流程 | `app/src/main/java/com/chuest/romtools/core/RomPipeline.kt` |
| `services.jar / miui-services.jar` 等 dex 改 | `core/DexPatcher.kt`（dexlib2） |
| `Settings.apk` 资源 + dex 改 | `core/ArscPatcher.kt` (ARSCLib) + `core/SmaliAssembler.kt` (google smali) + dexlib2 |
| `bin/*` 原生工具 | `jniLibs/arm64-v8a/lib*.so`（见 `prepare-jnilibs.sh`） |
| `lib/lib*.so` (ART) | 同上目录，保留原名 |
| `files/**` | `assets/files/**` |
| `bin/kpimg` | `assets/blobs/kpimg` |

> `apktool.jar / APKEditor.jar / d8.dex` 已彻底移除：所有 APK / jar 改动改走纯 Java 依赖
> （`smali-dexlib2:3.0.5`、`smali:3.0.5`、`ARSCLib:1.3.8`），不再需要在 ART 上跑 apktool/APKEditor JVM 代码。

## 一次性准备
```bash
# zipalign — 取 Android SDK build-tools 里的 arm64 静态版
cp /path/to/zipalign-arm64       bin/zipalign

# 把仓库的 bin/ lib/ files/ 同步成 App 的 jniLibs / assets
bash android-app/prepare-jnilibs.sh
```

要点：
- jniLibs 里的"伪 so"实际上是可执行 ELF。Android 10+ 只允许 exec `nativeLibraryDir` 里的文件，
  且包安装时只会展开名字符合 `lib*.so` 的条目，所以重命名是硬性要求。
- `dex2oat` 链接 `libart.so` 等 17 个 ART 库，全部放在同一目录里，App 启动子进程时会自动
  设置 `LD_LIBRARY_PATH=<nativeLibraryDir>`。

## 构建 & 运行
```bash
cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
点 **选择 ROM zip** → 选本地 `miui_HOUJI_*.zip` → **开始打包**。
工作目录默认放在 `Android/data/com.chuest.romtools/files/rt-work/`（外部存储，约需 30–40 GB 可用空间）。

完成后 `rt-work/work/images/` 下就是要刷的镜像，连同 `flash.bat` 拷到 PC 上 fastboot 即可。

## 注意 / 已知边界
- 仅适配 **小米 14（houji） + arm64-v8a**，与原脚本一致。
- 不下载 ROM，**必须本地选 zip**（SAF 流式解压，省一份拷贝）。
- 没有 root 需求；只用前台 Service + WakeLock 保活。
- 错误处理在日志里实时显示；中途崩溃后清掉 `rt-work/work/` 重试即可。
