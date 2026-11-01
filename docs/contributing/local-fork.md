# BrokenMyth 本地版本

当前版本：`6.4.1`。分发仓库为 [BrokenMyth/animeko](https://github.com/BrokenMyth/animeko)。代码提交与安装包 Release 分别管理，源码更新不代表已经发布对应安装包。

## 更新日志

### 2026-11-01 · 6.4.1（源码更新）

- 标记番剧“看过”时同时标记全部剧集，不弹确认窗口。
- 探索页顶部提供刷新推荐按钮，继续加载下一批推荐，末尾循环并打乱候选顺序；加载时显示进度圈。
- 电脑端可右键推荐卡片，直接标记为想看、在看、看过、搁置或抛弃；标记看过同时更新全部剧集。
- 设置 → 外观 → 推荐中提供“推荐中不显示已标记的番剧”，默认开启。想看、在看、看过、搁置、抛弃均被排除；首页与详情推荐共用规则。首页标记后保留当前推荐，手动刷新时更新过滤快照。联网刷新读取完整收藏，网络失败时使用本地已有收藏。
- “看过”卡片显示手动填写的日期；点击日期或菜单“设置看过时间”打开同一日期选择器，支持日历、手动输入切换与清除。未填写时隐藏，标记看过不会自动填写今天。
- “看过”标签栏右侧的排序菜单支持默认顺序、最近看过和最早看过，未设置日期的条目始终排在最后。
- 看过日期按账号和番剧 ID 独立保存在本地，保留已有日期及后续手动编辑、清除操作；云同步列为 TODO。
- 更新提醒、更新日志和开发构建查询使用本分发仓库。没有 Release 或没有适合当前平台的安装包时，不提示新版本。
- 提供 PowerShell 本地构建脚本，整理 Windows、Android 安装包及 SHA-1 校验文件；签名配置和个人日期导入资源不加入 Git。

Android 应用名称为 **Animeko · BrokenMyth**，包名 `me.him188.ani.brokenmyth`，与官方版并存。

构建记录：最新 Windows `:app:desktop:createReleaseDistributable` 已通过。Android APK 此前已构建，最近的日期选择器、排序与推荐交互调整仅重新构建 Windows，Android 尚未重新打包。按本次工作要求未运行功能测试，功能验证留待手动完成。本条日志为源码更新记录，不是安装包发布公告。

## 看过日期

日期按账号和番剧 ID 保存在独立的 `watchedDates` DataStore。清空收藏缓存、改变收藏状态不会清除日期。Windows 文件位于 `%APPDATA%\Him188\Ani\data\datastore\watchedDates`。

本地构建可选用以下私有资源导入已有日期：

`app/shared/app-data/src/commonMain/composeResources/files/local-watched-dates.json`

该文件受 Git 忽略，包含导入批次、目标账号和日期。两个平台只为匹配账号导入一次，保留已有日期及导入后的手工修改、清除操作。格式和实现见[条目系统](code/subjects.md#本地看过日期)。公开分发包应使用不含此私人资源的构建。

TODO：看过日期的云存档与多设备同步。当前各设备独立保存，不参与云端设置备份。

## 本地构建与交付

在源码目录使用 PowerShell 7 运行构建脚本：

```powershell
pwsh -File .\scripts\build-local-fork.ps1
```

脚本默认从源码父目录的 `build-tools` 查找带 JCEF 的 JBR 21，从私有 `local.properties` 读取 SDK，从 `build-tools/signing/signing.json` 读取签名。可通过 `-JbrHome`、`-AndroidSdk`、`-SigningConfig`、`-OutputDirectory` 指定路径；工具链要求见[开发环境](setup.md)。签名 JSON 必填 `alias`、`password`，可选 `storeFile`、`keyPassword`；默认密钥文件是同目录的 `brokenmyth-animeko.p12`。

脚本将配置合并到受 Git 忽略的 `local.properties`，构建 Windows 与 Android，并将 `Ani` 程序目录、ZIP、APK、同名 `.sha1` 和构建日志整理到源码父目录下的 `本地构建\版本-时间`。脚本不运行测试，不覆盖现用程序，不推送或发布。

Windows 的 `Ani.exe`、`app` 和 `runtime` 作为一个整体交付。覆盖安装目录前备份旧程序并关闭进程，保留源码和用户数据。Android 后续更新必须使用相同应用 ID 和签名；源码父目录下的 `build-tools\signing` 需保留，不加入 Git。

本地构建未配置弹弹play开发凭据，对应的第三方弹幕源不可用；普通 Ani 弹幕仍使用原接口。相关凭据配置说明见[构建打包](building.md#配置秘钥)。

## 后续手动发布

发布安装包时，在本仓库创建更高版本的标签及 Release，例如 `v6.4.2`，并上传符合下列命名的安装包。文件中的版本不带 `v`，与标签一致：

| 平台 | 文件名示例 |
| --- | --- |
| Android ARM64 | `ani-6.4.2-arm64-v8a.apk` |
| Android 通用包 | `ani-6.4.2-universal.apk` |
| Windows x64 | `ani-6.4.2-windows-x86_64.zip` |

每个安装包**必须同时上传同名 `.sha1` 文件**，内容为文件 SHA-1 的小写十六进制值，不能包含文件名或其他说明。客户端用它校验下载内容。例如：

```powershell
$releasePackageFile = Join-Path (Get-Location).Path 'ani-6.4.2-arm64-v8a.apk'
$releasePackageHash = (Get-FileHash -LiteralPath $releasePackageFile -Algorithm SHA1).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$releasePackageFile.sha1", $releasePackageHash, [Text.UTF8Encoding]::new($false))
```

稳定版使用 `v主版本.次版本.修订号` 标签。`-alpha01`、`-beta01` 等版本按应用内更新渠道筛选；草稿不会提示更新。Android 优先使用对应架构包，缺少时回退到通用包。

TODO：适配 fork 的发布 CI。现有 Release workflow 包含官方仓库条件、官方签名与 S3 配置，不能直接作为本仓库的发布流程。适配时修改 `.github/workflows/src.main.kts` 并重新生成 YAML。
