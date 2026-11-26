# 公平运行内存机制适配

cadroid 已接入 ITGSA 金标联盟的「公平运行内存机制」客户端广播。

## 系统广播

| Action | 含义 | 应用动作 |
| --- | --- | --- |
| `itgsa.intent.action.TRIM` | 内存预警 | 尽快释放可回收资源，3 秒内回调 |
| `itgsa.intent.action.KILL` | 异常查杀 | 保存现场，3 秒内回调后由系统结束进程 |

广播结构：

```text
common:  notifyType / notifyId / reason / action / callback
extra:   pss / pssLimit / heapSize / heapCapacity
```

`notifyType`：

```text
1000  物理内存异常
2000  Java 堆内存异常
```

## 回调

应用通过 `common.callback` 的 `IBinder` 回传：

```text
notifyType
notifyId
result      0 = 已处理，1 = 未处理
extra       reply: 处理说明
```

3 秒内未回传时，系统会按超时处理。

## 当前实现

代码：

```text
app/src/main/java/com/xero/ca/FairMemoryAdapter.kt
```

- `XApplication.onCreate()` 中注册动态广播接收器
- Android 13+ 使用 `RECEIVER_EXPORTED`
- 接收器在独立 `HandlerThread` 上处理，避免阻塞主线程
- `TRIM`：触发一次显式 GC，回收本进程可立即释放的临时内存
- `KILL`：当前没有需要持久化的页面现场，直接回传成功
- 回调通过 Binder `TRANSACTION_EXCEPTION_REPLY` 完成

## 后续

CA 的图片缓存、WebView 缓存由 CA 自己持有，目前不强行清空。等 CA 侧需要响应公平内存时，可以在 `FairMemoryAdapter` 里加一个回调接口，让 CA 注册自己的释放逻辑。
