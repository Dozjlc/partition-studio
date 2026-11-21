# 分区镜像 v1.8 源码

原生 Android Java 项目，无 AndroidX / Gradle 依赖，UI 由 Java 动态构建。
最低 Android 8.0（API 26），targetSdk 34。

## 目录

- `app/AndroidManifest.xml`
- `app/src/com/dsh/partitionstudio/*.java`
- `app/res/`：主题、图标、底部导航图标
- `app/assets/d.bin`：加密后的赞赏码资源（运行时解密）
- `proguard-rules.pro`：R8 保留规则
- `build.sh`：无 Gradle 的 aapt2 + javac + R8 + apksigner 构建脚本

## 构建前提

当前脚本针对 ARM64 Linux（手机上的 Ubuntu proot 环境）编写，需要：

- JDK 17
- Android SDK `platforms;android-35` 与 `platforms;android-28`
  （资源链接使用 API 28，因为 ARM64 可用的 aapt2 2.19 读不了 API 35 的 resources.arsc）
- build-tools 35.0.0（提供 `lib/d8.jar`，其中包含 R8）
- 系统 `aapt2`、`zipalign`、`apksigner`
- Python 3、zip、keytool

在普通 x86_64 电脑上，把 `AAPT2` 换成 build-tools 35 的 aapt2，并相应调整 `SDK` 路径即可。

## 构建

源码**不包含签名 keystore**。请准备你自己的 keystore：

```bash
export ANDROID_SDK=/opt/android-sdk
export KEYSTORE=/absolute/path/your-release.jks
export KS_PASS='your-password'
export KEY_ALIAS=your-alias
./build.sh
```

产物在 `build/分区镜像-v1.8.apk`。

## 重要：签名校验

`Integrity.java` 里固定了官方签名证书 SHA-256 和最终 DEX/资源/清单/赞赏码哈希。
如果你用自己的 keystore 签名，首次构建会在最后一步报 `signer pin mismatch`。

二选一：

1. 用你自己的证书 SHA-256 替换 `Integrity.java` 中的 `EXPECTED_SIGNER`，然后重新构建；
2. 或删除 `Integrity.java` 的固定签名校验（仅限你完全掌控的分支）。

构建脚本会自动生成 `assets/i.bin`（DEX/资源/清单/赞赏码哈希清单），不要手动提交该文件。

## 不包含的内容

- 签名 keystore / 密码
- 已编译 APK、idsig、R8 mapping
- `build/` 目录、缓存
- 明文赞赏码图片（只保留加密后的 `app/assets/d.bin`）

## 说明

- 源码一旦外发，R8 混淆和签名校验只能防止成品被随意重签，无法阻止拿到源码的人重新编译。
- `Integrity.java` 的证书哈希是公开信息，不是密钥。
- 设置页包含作者 QQ 和赞赏码；如果要公开分发源码，请自行替换或删除个人信息。

## 免责声明

本工具会直接读写手机块设备，错误镜像或错误分区可能导致设备无法开机、数据丢失甚至硬件损坏。
请只在明确知道风险、并已备份重要数据的情况下使用。作者不对任何直接或间接损失负责。
