# Fairy Android 文字客户端基础

Requirement: REQ-20261022-4919F1。原生 Java/Views、Android 16 / API36，包名
`com.jikestardy.flovvy`。这是同一 Host 的文字入口基础；实时语音、资料阅读、学习进度
及完整 M3 验收仍待后续实现。APK 编译成功不代表真机或语音已经通过。

## 连接和使用

1. 从指定 GitHub Actions run 下载构建附件，核对 `flovvy.apk.sha256`、构建 manifest、
   源码 SHA 和公钥证书指纹，再通过 Android 系统安装器打开 APK，按系统指引允许该来源安装。
2. Host 须已有手机可达的 HTTPS 地址和系统信任的有效证书；App 不接受明文连接、重定向
   或忽略证书校验。构建不部署 Host，也不把个人地址、模型服务或密钥放进 APK。
3. 在 Host 管理界面生成一次性配对码。App 填 HTTPS **origin**（不含路径、账号或参数）
   与配对码，点击“配对”。配对码不会重试；回执不明时在 Host 核对设备并生成新码。
4. 连接后读取 Host 最近100条 `owner` 会话历史及当前生成内容。文字最多16,000个
   Unicode code points；实际 JSON 请求最多65,536 UTF-8字节，响应最多2MiB，超限拒绝，
   不截断成看似完整的消息。输入、人格、记忆和任务仍由 Host 处理。
5. 发送结果未知时不标成已接收，不自动重发。可刷新历史核对；“重试同一请求”仅在你
   点击后沿用原逻辑请求ID和原文，让现有 Host 请求去重生效。“另写新消息”只放弃本机
   回执等待，不取消旧请求。没有自动推理重试或隐藏每日调用配额。

旋转屏幕等配置重建只通过平台的进程内交接保留草稿、未知回执的原ID/原文和原绑定指纹，
不保存到 Bundle 或明文文件。新界面重建网络并先读取新快照，之后才能手动重试同一请求。
配对期间不能发文字；新 Host、设备或 token 绑定成功会清除旧草稿与待核对请求。配对失败
可重连原绑定继续核对。进程被系统终止后没有本机对话重放，重新读取 Host 历史。

退到后台、锁屏、退出或切换绑定会使旧代次失效并关闭本机网络工作。再次打开只重新读取
Host 状态，不重放旧消息。故障后的事件读取仅做1/2/4秒三次重连，之后使用“重连”。
尚未接入音频，因此本版本不请求麦克风权限，不提供冒充停止播报或取消任务的按钮。

设备 token 和 origin 只在 Android Keystore AES-GCM 保护的私有、不可备份文件里保存；
损坏、密钥失效、撤权要求重新配对，没有明文 fallback。对话仅在当前进程显示，不另建
人格/记忆/资料数据库。清除本机绑定只清除手机凭据；服务端撤权需在 Host 管理界面完成。
关闭/清除 App 不会取消独立后台任务。忘记或纠正内容以 Host 新快照为准，旧文本不回写。

当前复用已验收的旧 HTTP gateway，它在 Host 内仍将交互命令标为 `presentation.web`；
本切片不伪造一个服务器尚不支持的 Android 来源字段。独立移动来源、实时语音的身份/
代次协议及跨入口完整验收属于后续 Host 公开协议扩展。

原生播放内部组件 `PcmOutput` / `AndroidPcmOutput` 尚未连接 Activity 或 Host。它只接收
已确认 PCM16LE/mono/16kHz 的单个语音段，每包最多6,400字节、未写队列64,000字节、
每段320,000字节；队列满返回背压，不消费序号。每次非阻塞写入最多320个short样本，
显式解码LE；不使用已弃用的PCM16 byte-array写接口。停止先失效本段身份，再在同一
设备gate下pause/flush/release；迟到的设备打开结果不会play。未完成的设备打开或清理
保留原owner，不能启动替代设备。播放head达到全部样本才算自然播完，API成功不代表已测得
物理静音。组件不创建线程、不请求麦克风、不自动开始下一段，也不取消Host任务。
焦点、路由、Activity生命周期、录音/插话和鉴权语音协议仍需接入；Java测试不能代替真机。
AudioTrack 在 play 前将实际启动阈值设为并核验为1帧，让合法短段及欠载后的短尾部能够
起播。每次写入和读取播放head前复检该值；不支持或输出设备变化导致阈值漂移时，显式
失败并收尾原对象，不能等待到永久占用本段或自动重播。

原生录音内部组件 `PcmInput` / `AndroidPcmInput` 也尚未连接 Activity/Host。它使用
VOICE_COMMUNICATION 的非阻塞 short-array API，每次最多20ms，显式编码 PCM16LE；
每200ms生成一个最多6,400字节的不可变包，正常结束保留非空尾包。未确认音频（含尾部）
最多64,000字节，满后拒绝下一次读取并明确停止本句；不静默丢帧或自动重传。录音最多
30秒单调时间且最多960,000字节，到界收尾原设备，确认排空后仍须显式结束本句。
硬停止先失效身份再清理并丢弃未发音频；停止/正常结束发生后的迟到读取不能再提交。
打开、开始和读前/读后复检权限、格式与原生缓冲；失败保留原设备及实际清理错误。
没有新增后台线程或自动录音；运行时权限界面、Host-ready、路由/焦点、上传回执核对及
10秒输入/2秒上传无进展处理仍由后续前台控制器接入。设备句柄及包均不构成授权。

## GitHub Actions 构建

公开仓库 `JikeStardy/flovvy` 的 `dev` 只接收经过审查的 Android 文件投影，继承原远端
初始化 main；完整本地开发历史、用户资料和配置不上传。集成负责人冻结本地候选后生成
`android-source.json` schema1，字段为 `schema`、`requirement`、`implementation_sha`、
`baseline_sha`、`files`（各相对输入路径→SHA256），不包含它自身的 hash。

workflow 只响应 dev 的相关路径 push。虽声明手动入口，GitHub 要求 workflow 先存在默认
分支才提供手动运行；首次使用 push，不为此修改 main 或默认分支。动作固定不可变 SHA、
contents:read、15分钟、取消旧并发 run，工具缺失直接失败，不安装包或接受 SDK 许可。

runner 必须已有 JDK17、API36 revision2、Build Tools36.0.0、Python3.12+。依次核对投影、
执行 Python 测试和纯 Java harness，再实际 aapt2→javac→d8→zipalign→apksigner，并核验
最终包、SDK、仅 INTERNET 权限、签名证书与对齐。没有 Gradle、AndroidX 或第三方运行时。
五个 JDK harness 分别验证会话、播放状态、播放适配器阈值、录音状态与录音适配器。
两个适配器 harness 只在独立测试 classpath 使用第一方 `tests/platform` 行为夹具；
它们不代表真实 AudioTrack/AudioRecord 或声学测量。
Android 编译只使用生产源码和真实 SDK bootclasspath；测试与夹具不进入应用 classes.jar、
DEX 或 APK。

Android javac 保持 Java8 source/target，bootclasspath 依次使用 SDK 的 `android.jar` 和
Build Tools 根目录已有的 `core-lambda-stubs.jar`；后者为编译 lambda 所需的符号存根。
工具预检要求两个文件均存在，缺失时不启动任何工具。存根不进入应用 classes.jar、DEX
或 APK；D8 的平台 `--lib` 仍只有 `android.jar`。签名 manifest 记录两个库各自的实际
SHA256。此项修复真实 Actions39947017718 的 `LambdaMetafactory.metafactory` 缺失原因；
后续实际 Actions39949757966已通过该基础版本编译，签名因缺配置失败，无APK附件。
新增播放组件仍须在自己的冻结源码上重新运行真实Java/Android编译，不能沿用该结果。
录音组件也须独立冻结和运行其真实编译/Java检查，不能沿用播放版本的通过。

独立的 `--compile-only` 步骤不读取签名配置，使用同一条实际 Java harness、资源、Android
编译、DEX 和对齐检查路径。它不调用签名器，不创建密钥，也不保留或上传 APK、hash 或
交付 manifest。即使这个步骤通过，后续签名或验包缺配置/失败仍会使整个构建失败，不
上传任何交付文件；纯编译通过不能代替可安装 APK。

集成负责人配置两项 Secrets：`ANDROID_SIGNING_P12_BASE64`、`ANDROID_SIGNING_PASSWORD`；
私钥 alias 为 `flovvy-android`，保持同一 RSA3072 签名。另设非秘密变量
`ANDROID_SIGNING_CERT_SHA256` 为预期公钥证书 SHA256。缺配置或证书不符失败，不生成新
debug key。私钥严格解码到 runner 私有临时目录，finally/always 清理，从不上传或缓存。

仅上传 `flovvy.apk`、`flovvy.apk.sha256`、脱敏 `build-manifest.json`，保留7天。manifest
同时记录实现/投影 SHA、工具与 SDK revision/hash、实际 runner image、命令模板、签名和
APK hash。附件 ZIP 的 artifact digest 与 APK hash 是两个不同对象，不混用。

本地不需要 SDK 的检查：

```sh
python3.12 -B -m unittest discover -s clients/android/tests -p 'test_*.py' -v
```

仅在已具备固定工具和受控签名环境的 runner 上构建：

```sh
python3 -B clients/android/tools/build_apk.py \
  --source-root "$GITHUB_WORKSPACE" \
  --output "$RUNNER_TEMP/flovvy-delivery" \
  --version-code "$GITHUB_RUN_NUMBER"
```

默认构建不打印工具失败时的原始输出或秘密，失败会清除三个固定交付文件。工具路径、
版本、签名配置或源清单错误应先核对对应配置；不以旧 APK 作为这次成功产物。
每个工具的合并输出在产生时限制到65,536字节，超限终止；执行最多60秒，并在返回前对
自有进程组做有界TERM/KILL收尾，包括已退出launcher的后代。无法确认收尾则报
`TOOL_CLEANUP_PENDING`并阻止交付；主操作与收尾同时失败时保留两项安全错误类别。
未在相同源码/工具上完成两次真实比较前，不宣称 APK 位级可复现。

## 更新、恢复和未验收项

后续安装沿用包名和同一签名、更高 versionCode。回退代码也须构建更高版本号的兼容 APK；
不能用随机新 key 或卸载清数据作为例行更新方案。Host 持久化状态不由本 APK 迁移或清空。
重装/凭据损坏重新绑定；Host 的撤权和最新状态具有权威。

仍需真实 Android16 日用1440×3168设备安装/启动、真实实时 ASR/模型/TTS、20次受控停止/
插话故障、另20个真实有声输出停止样本（排序第19个≤500ms）、预先冻结的端到端门槛、
资料/跨入口/重启验收以及父目标14实际自然日和本人评分。fixture、编译和历史报告均不代替。

显式 `--compile-only --public-compiler-diagnostics` 仅用于已经审查的公开源码编译：
编译器失败时，在自有进程组收尾后输出一行带固定前缀的 JSON 转义诊断，转义后内容最多
16,384字符，可能截断。正文中的 `#`、`:` 也在截断前转为 Unicode 转义，以免被 runner
的旧版或新版日志协议识别为控制指令。只允许无秘密的编译工具环境；签名构建拒绝该选项，默认模式仍
只报告安全类别。workflow 只在无 Secrets 的编译步骤启用，不在签名步骤启用；它不生成
或上传交付文件，且不能替代编译、签名或真机验收。
