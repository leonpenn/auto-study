# 项目协作规则

## 网络 / GitHub 推送

- **任何推送到 GitHub（git push / gh release 等）之前，必须先检测连通性**：
  - Windows Git Bash：`ping -n 2 github.com`
  - 不通时：**停止重试，直接告知用户开启本地代理**，等待确认后再继续（盲目重试只会白白超时等待）；
- 推送成功后若需要创建 Release，继续使用 `gh release create vX.Y.Z --notes-file ... "release/xxx.apk"`；
- Release 资产文件名用 ASCII（如 `auto-study-vX.Y.Z.apk`），避免中文文件名下载乱码。

## 版本发布

- `versionCode` 单调递增（覆盖安装依赖）；`versionName` 与发布 tag 一致；
- 每次发版同步更新：`使用说明.md` 版本表、`产品设计开发文档.md` 版本表、`release/` 下 APK（中文+ASCII 各一份）；
- **签名信息不入库**：keystore.jks 与 keystore.properties（含密码）已被 .gitignore 排除、由 Leon 本地保管；clone 后需自建 keystore.properties 才能签名构建（格式见 app/build.gradle 读取逻辑）。

## 构建环境

- 本项目已改用用户级共享构建环境：`C:\Users\Leon\android-build-tools\`（JDK17/SDK/Gradle，环境变量 JAVA_HOME、ANDROID_HOME 已指向），项目内 build-env/ 已废弃删除；
- local.properties 的 sdk.dir 指向 `C:/Users/Leon/android-build-tools/sdk`。
