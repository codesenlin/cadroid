# 命令助手Android
## 简介
命令助手Android是[命令助手](https://gitee.com/projectxero/ca)的App版本，增加了命令助手的功能，如一键粘贴、适配器、从文件管理器加载命令库等。

### 增加特性
* 支持无障碍服务，提供粘贴与监听非打印按键接口
* 支持特殊Intent调用
* 支持适配器
* 支持开机自启

## 编译步骤
1. 将编译完成的，没有压缩的命令助手代码写入[assets](https://gitee.com/projectxero/cadroid/tree/master/src/main/assets)/script.js。
2. 对script.js进行GZIP编码。
3. 使用签名对script.js进行加密，加密方法为逐位异或法，签名为PackageManager返回的Signature字节数组。
4. 编译Apk。

> 上面 1~3 步由 [CA](https://gitee.com/projectxero/ca) 仓库的构建工具自动完成：
> `cabuild shellPrepareDebug`（调试包）或 `cabuild shellBuildRelease`（正式包，
> 含 zipalign + apksigner 签名），配置见 `CA/config/shell.local.txt`（首次运行时由模板生成）。
> 日常调试也可以直接 `./gradlew :app:assembleDebug`（用的是 assets 里已有的 script.js）。

## 本地依赖与本机密钥（可选）

工程**不依赖任何本地文件即可构建**：缺少的统计/广告 SDK 会被自动跳过，对应功能关闭。

需要完整功能时，把下面这些放到本机（都不会进版本库）：

### 1. 本地依赖 → 放 `app/libs/`

| 文件 | 作用 | 缺失时 |
| --- | --- | --- |
| `SaaS_TalkingDataSDK_Android_V5.0.38.jar` | TalkingData 统计 | 用 `app/src/stub/talkingdata` 里的空实现编译，统计功能关闭 |
| `GDTActionSDK.min.1.9.8.aar` | 优量汇**转化（回传）SDK**，与广告 SDK 配套 | **整套优量汇都不装配**，不请求开屏广告 |

优量汇的广告 SDK（`com.qq.e.union:union:4.710.1580`，由 Maven 拉取）与转化 SDK 是配套使用的：
**有 `GDTActionSDK.min.1.9.8.aar` 就两个都加载，没有就两个都不加载**，避免出现「投放广告但缺转化回传」
的半套状态。缺 aar 时用 `app/src/stub/gdt` 里的空实现编译，并把 `BuildConfig.GDT_ENABLED` 置为
`false`——`XApplication` 不再初始化 SDK，`AdProvider` 直接跳过广告（连 10 秒等待都不会有）。

### 2. 账号/密钥 → 逐条合并（`private` > `default` > `template`）

三份候选文件都在 `app/src/main/java/com/xero/ca/` 下：

| 优先级 | 文件 | 说明 |
| --- | --- | --- |
| 1 | `Secret.java` | **本机私人配置**（同目录 `.gitignore` 已忽略，可选） |
| 2 | `config_default/Secret.java` | **默认配置**：CA 原作者公开的那份值（TalkingData token、优量汇 AppID/广告位、Gitee OAuth 凭据） |
| 3 | `config_template/Secret.java` | **模板**：全部留空，兜底 |

**合并是按条目（每个 getter）来的，不是整份文件**：例如私人文件里只填了 TalkingData
token、其余留空，那么广告 AppID / 广告位 / Gitee 那几项会各自回退到默认配置的值；
某一条在默认里也是空，才落回模板的空实现（该功能关闭）。

**怎么用自己的值**：把 `config_template/Secret.java` 复制成 `com/xero/ca/Secret.java`，填上自己的
条目即可（只填需要的，其余留空就会自动用默认值）；这个路径不会被提交。

**实现**：`app/secret-config.gradle` 在构建时解析这三份文件、逐条合并，生成
`com/xero/ca/gen/Secret.java` 后参与编译（放在 `gen` 包下，避免和私人文件名抢同一个
类名；`private` / `default` / `template` 三份都只当数据源、不直接编译）。构建日志会列出
每一项最终来自哪份：

```
Secret 配置（逐条合并）:
  getTDToken: private（本机） （已配置）
  getGDTAppID: default（默认） （已配置）
  ...
```

热更新校验公钥是公开信息，`default` / `template` 里都写死同一份（我们自己的那张，配
`app/signatures/privateKey.pem`），换签名密钥时记得同步。

