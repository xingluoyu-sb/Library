# 资料库 · Library

一个**完全不联网**的 Android 个人资料库：把散在手机里的文件、随手记的文字、各平台的登录密码收进同一个地方，离线可查。

> 没有账号、没有云同步、没有统计上报——`AndroidManifest.xml` 里连 `INTERNET` 权限都没申请。

[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![minSdk](https://img.shields.io/badge/minSdk-30-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF)

## 三个模块

| 模块 | 用途 |
|---|---|
| **资料库** | 把文件按层级归档、整理，便于查询。支持导入整个文件夹，保留原有的目录结构 |
| **记录** | 随手记待办、当前状态、心情、一段文字、对未来的规划 |
| **密码** | 存各平台的登录密码，同样便于查询 |

## 功能

**资料库**
- 导入文件夹 / 多个文件 / 单个文件，保留原有层级
- **只记录索引，不搬运文件**——导入的源文件仍然留在原来的位置
- 图片、视频、音频、PDF、文本的缩略图与预览
- 手动排序、按类型筛选、全文搜索

**记录**
- 纯文本 / 轻量 Markdown 书写，长文分段阅读
- 按分组归类，支持待办式的勾选内容

**密码**
- 按平台分组保存账号密码，一键复制
- 密码本体经 **AES-GCM** 加密后入库，密钥存在 **Android Keystore**，数据库里没有明文

**通用**
- 回收站：删除先留副本，可原样恢复回原目录
- 备份：把数据库导出成一份 `.db` 副本；也能从副本恢复
- 上锁：应用密码（PBKDF2-HMAC-SHA256，12 万轮，只存盐和派生值）或调用手机自带的指纹 / 锁屏密码
- 中文 / English 双语，首次启动有引导

## 两条设计底线

1. **不联网。** 应用没有任何网络权限，也没有一行联网代码。数据不外流，代价是没有云同步——备份和恢复都靠你自己选目录。
2. **导入不动源文件。** 应用保存的是文件的位置（SAF 授权），不是副本。所以照片还在相册里、视频还在下载文件夹里。反过来说，源文件被删掉或被移走，索引就会失效——这是这个设计必然的代价。

## 构建

需要 **JDK 25**（AGP 9.2.1 的工具链要求）和 **Android SDK 37**。

```bash
# debug 包，直接能装
./gradlew :app:assembleDebug

# release 包：需要签名信息
cp keystore.properties.example keystore.properties   # 然后填上自己的 keystore
./gradlew :app:assembleRelease
```

Windows 下也可以直接双击 `build.bat`（用的是系统里的 `JAVA_HOME` / `ANDROID_HOME`）。

`keystore.properties` 和 `keystore/` 已被 `.gitignore` 排除，自己的签名材料不会误传。

## 代码结构

```
app/src/main/java/com/library/app/
├── data/
│   ├── db/          Schema + SQLiteOpenHelper 手写迁移（当前 version 7）
│   ├── dao/         Entry / Folder / Module 的 SQL
│   ├── files/       FileStore、FolderImporter、TrashStore、BackupManager
│   ├── crypto/      CryptoBox（AES-GCM + Keystore）、BiometricGate
│   ├── AppLock.kt   应用密码 / 手机凭证
│   └── Repository.kt  业务入口
├── ui/
│   ├── nav/         自研 Navigator + Route（没有用 Navigation 库）
│   ├── components/  顶栏、底部 tab、对话框、图标（全部是代码画的 VectorDrawable）
│   └── screens/     各页面 + ViewModel
└── i18n/            中英文案表
```

技术栈：Kotlin + Jetpack Compose (Material 3)，原生 `SQLiteOpenHelper` 手写迁移，零第三方网络 / 图片库依赖。全项目约 1.2 万行 Kotlin。

## 已知限制

- 只有 Android（minSdk 30），没有 iOS / 桌面版
- 没有云同步，多设备之间只能靠导出的 `.db` 手动搬
- 索引依赖系统给目录的持久授权，卸载重装后需要重新导入
- 忘记应用密码只能在验证手机凭证后重设；数据不会丢，但密码框本身没有「找回」通道

## 协议

[GPL-3.0](LICENSE)

## 开发

本应用使用 deepseek-flash-1017 模型完成所有代码层面的开发。
