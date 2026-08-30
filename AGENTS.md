# 项目协作规则

## 网络 / GitHub 推送

- **任何推送到 GitHub（git push / gh release 等）之前，必须先检测连通性**：
  - Windows Git Bash：`ping -n 2 github.com`
  - 不通时：**停止重试，直接告知用户开启本地代理**，等待确认后再继续（盲目重试只会白白超时等待）；
- 推送成功后若需要创建 Release，继续使用 `gh release create vX.Y.Z --notes-file ... "release/xxx.apk"`；
- Release 资产文件名用 ASCII（如 `auto-study-vX.Y.Z.apk`），避免中文文件名下载乱码。

## 版本发布

- `versionCode` 单调递增（覆盖安装依赖）；`versionName` 与发布 tag 一致；
- 每次发版同步更新：`使用说明.md` 版本表、`产品设计开发文档.md` 版本表、`release/` 下 APK（中文+ASCII 各一份）。
