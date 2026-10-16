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
> 含 zipalign + apksigner 签名），配置见 `CA/config/shell.txt`。
> 日常调试也可以直接 `./gradlew :app:assembleDebug`（用的是 assets 里已有的 script.js）。

## 本地依赖与本机密钥（可选）

工程**不依赖任何本地文件即可构建**：缺少的统计/广告 SDK 会被自动跳过，对应功能关闭。

需要完整功能时，把下面这些放到本机（都不会进版本库）：

### 1. 本地依赖 → 放 `app/libs/`

| 文件 | 作用 | 缺失时 |
| --- | --- | --- |
| `SaaS_TalkingDataSDK_Android_V5.0.38.jar` | TalkingData 统计 | 用 `app/src/stub/java` 里的空实现编译，统计功能关闭 |
| `GDTActionSDK.min.1.9.8.aar` | 优量汇转化（回传）SDK | 跳过该依赖，广告本身不受影响 |

广告 SDK 本体走 Maven（`com.qq.e.union:union:4.710.1580`），不需要手动放。

### 2. 账号/密钥 → 写 `local.properties`

`Secret.java` 入库但值是空的，真实值由 `local.properties` 经 BuildConfig 注入：

```properties
ca.tdToken=              # TalkingData 统计 token
ca.gdtAppId=             # 优量汇媒体 AppID（后台“媒体管理”里获取）
ca.gdtPosId=             # 优量汇开屏广告位 ID
ca.giteeClientId=        # Gitee OAuth 应用 client id
ca.giteeClientSecret=    # Gitee OAuth 应用 client secret
ca.giteeFeedbackToken=   # 自建反馈平台 token
```

留空即关闭对应功能（没有 AppID/PosID 时不请求广告，直接进脚本）。
`local.properties` 已在 `.gitignore` 中，请勿提交。


