# Qoder WASM 的 Java 接入分析

## 应用运行配置

设置环境变量 `QODER_WASM_PATH` 指向已有的 `qoder-auth-wasm.wasm`，或使用配置项 `proxy.oauth.qoder.wasm-path`。生产运行只需要 Java；Node 仅用于独立对照验证脚本。

应用启动、其他渠道和 Qoder 模型目录不依赖该文件。只有 Qoder 推理才加载文件；未配置时明确提示配置项，文件大小或 SHA-256 不匹配时拒绝加载。接受的文件大小与哈希见下文，更新文件需重新验证 ABI 和加密一致性。

每次推理创建独立 WASM 实例和对象句柄堆，实例只在当前调用中串行访问；线性内存限制为 512 页（32 MiB）。随机源使用 `SecureRandom`，时钟使用实际系统时间。返回字符串、请求结果和上下文完成后显式释放，实例不跨账户共享。401 刷新后使用原始未加密正文和新凭据重新生成签名与密文。

目录与源项目一致，采用随包快照，标记 `catalog_source=snapshot`，不把采集时的促销价格作为当前价格。具体模型的可用性仍由上游账户权益决定。请求保留文本、图片、历史消息和工具定义；响应解开 JSON 信封后进入统一 OpenAI 流处理及非流聚合。

当前接入覆盖基本认证与推理协议，并非源适配器全部容错行为的逐项复刻。暂未实现业务码 10605 的排队等待、403 内业务码 105 的特殊刷新、409 重复请求的专用重发、专用首 token/帧间空闲超时，以及旧凭据缺少 UID 时的自动补全。队列和流内业务错误会明确报告；旧凭据缺少必需字段时应重新登录。原生请求接口为 `/v1/chat/completions`。

本分析读取 OFM 的 `vendor/channel-pack/src/qoder-wasm.ts`、`qoder-envelope.ts` 和随包 WASM，并解析二进制的类型、导入、导出与内存节。2026-10-16 已在独立 Java 验证项目执行该模块，并与原 Node bridge 完成离线对照；没有验证真实 Qoder 账号登录或上游推理。

## 可行性结论

已验证可以使用 Chicory 在 Java 内直接运行 WASM。模块没有浏览器 DOM、网络、文件系统或 WASI 导入；它需要的是 wasm-bindgen 的 JavaScript 对象桥。这些桥接行为能够用 Java 的对象句柄表、字节数组视图、UTF-8 编解码、随机数和时钟实现，因此不必把整个 Node 服务或 JavaScript 引擎嵌入代理。生产实现必须使用安全随机数；离线对照专用的确定性随机源不能用于真实认证。

离线验证排除了 WASM 无法在 Java 中运行这一障碍。随后已整合国际版与中国版的登录轮询、令牌刷新、模型快照、加密推理和 SSE 适配，并添加渠道入口；真实账号和真实上游仍需登录后验证。

## 已完成的离线验证

独立项目位于 `scripts/qoder-wasm-probe/`。应用 POM 现已加入 Chicory 依赖，外部 WASM 未复制到应用资源目录。测试数据为合成 uid、无效 token、固定机器标识和简单文本请求。

原 Node bridge 与 Java bridge 使用相同测试时钟、随机序列和 JSON 字节，以下项目全部一致：

- `generate_runtime_auth_fields` 返回的认证字段。
- `qodercontext_prepareInferRequest` 返回的 URL、完整请求头及 COSY 签名。
- 加密请求正文和模型缓存密文，均逐字节一致。
- Node 加密、Java 解密，以及 Java 加密、Node 解密均还原原始 Unicode JSON。
- 两边随机源消耗均为 279 字节。
- Java 连续生成并释放 100 个推理请求结果，线性内存保持 18 页，没有继续增长。

验证过程发现 Chicory 的 void 导出可返回 null，桥接代码已适配。Java 还显式释放 `requestresult_url/body` 返回的字符串、请求结果和上下文；不能只恢复栈指针而忘记堆对象。

当前对照仅覆盖这一组推理输入、模型缓存与实际触发的宿主分支。更长上下文、图片、所有异常路径、生产随机源、并发账号隔离和真实模型可用性仍需继续验证。

## 已读取的二进制事实

- 文件大小为 298606 字节。
- SHA-256：`6419471effa631519def7797d76d7ede38b9fcfa9a83c2148c8ef5d43355b43d`。
- 31 个导入，全部来自 `./qoder_auth_wasm_bg.js`，全部是函数。参数和返回值主要是 i32；时间函数返回 f64。
- 34 个导出，包括线性内存、分配与释放函数、认证字段生成、上下文构造、请求构造和缓存加解密。
- 线性内存初始 17 页，共 1114112 字节；模块没有声明最大页数。Java 宿主需要设置资源上限。

关键导出包括 `generate_runtime_auth_fields`、`qodercontext_new`、`qodercontext_prepareInferRequest`、`requestresult_headers`、`requestresult_url`、`requestresult_body`、`model_cache_encrypt` 和 `model_cache_decrypt`。

## 桥接细节

原桥使用对象句柄堆：预留 1024 个 undefined，再放入 undefined、null、true、false 四个哨兵。普通对象通过空闲链表分配与回收。Java 实现必须区分 undefined 和 null，且不回收哨兵。

WASM 字符串采用 UTF-8 的指针与长度组合。内存读取使用小端序。字符串返回布局是 `ptr/len/errorObject/isError`；上下文指针返回布局是 `ptr/errorObject/isError`。调用前通过 `__wbindgen_add_to_stack_pointer(-16)` 分配结果区域，之后必须恢复栈。

两个 `getRandomValues` 导入语义不同：一个接收线性内存中的指针和长度，另一个接收对象句柄。混用会破坏随机数缓冲区。`set` 导入也要区分 Map 与 Uint8Array 的方法语义，不能仅依据 TypeScript 类型断言实现。

应使用每个实例独立的对象堆与串行访问。原桥的上下文不是线程安全对象；Java 接入不能将共享实例无锁交给多个请求。二进制导出了 `__wbg_qodercontext_free` 和 `__wbg_requestresult_free`，应在凭据轮换、请求处理完成或关闭时释放对象，并测试重复调用后的内存增长。

## 推理协议边界

加密请求不是普通 `/v1/chat/completions` 请求。上游需要模型目录中的 key、完整的 `chat_context`、`model_config`、消息历史、工具 schema、业务归属和产品 session type。

WASM 返回的 Authorization 是 `Bearer COSY.…`，必须与生成的 URL、请求体及其他请求头一起使用，不能再用普通 OAuth Bearer 覆盖。国际版与中国版使用不同推理域名；当前源适配器的实际调用在两个产品上均使用 `qodercli` session type，Java 与这一行为保持一致。

响应是 SSE 信封，其中 `body` 是 OpenAI chunk 的 JSON 字符串；源码说明该内层正文未加密。Java 侧可以剥除信封，再使用现有 OpenAI SSE 转换逻辑，同时保留错误码并识别心跳、流内失败和完成标记。

## 运行时选择

已选用 Maven 坐标 `com.dylibso.chicory:runtime`。官方 API 提供 `HostFunction`、`ImportValues`、`Instance`、内存限制和导出函数调用，实现所需的 31 个宿主函数。独立对照验证项目保留自己的 POM，应用另外声明生产运行依赖。

备选为 `io.github.kawamuray.wasmtime:wasmtime-java`。它通过 JNI 使用 Wasmtime，官方说明提供 Windows x64、Linux x64、macOS x64/aarch64 的预构建库，但需要管理 native 对象生命周期。当前任务优先尝试纯 Java 方案。

本次读取的公开资料：

- [Chicory 仓库](https://github.com/dylibso/chicory)
- [Chicory Maven 元数据](https://repo.maven.apache.org/maven2/com/dylibso/chicory/runtime/maven-metadata.xml)
- [wasmtime-java 仓库](https://github.com/kawamuray/wasmtime-java)
- [wasmtime-java Maven 元数据](https://repo.maven.apache.org/maven2/io/github/kawamuray/wasmtime/wasmtime-java/maven-metadata.xml)

## 独立验证顺序

1. 固定上述 WASM 哈希，使用合成 uid、token、machine id 与消息，禁止读取实际客户端账号文件。
2. 为原 Node bridge 与 Java bridge 注入相同测试时钟和确定性随机字节序列；该随机源仅存在于离线验证脚本中。
3. 比较运行时认证字段，再比较相同上下文和请求参数得到的 URL、headers、body。若结果不同，先对照调用的随机函数、对象句柄和返回布局，不使用真实上游请求定位。
4. 使用 `model_cache_encrypt` 与 `model_cache_decrypt` 做双向交叉验证：Node 加密、Java 解密；Java 加密、Node 解密。确认 Unicode、空串及较长 JSON 的一致性。
5. 验证错误路径、对象释放、重复调用及内存增长。只有离线验证通过后，才讨论应用接入与真实账号验证。

## 许可证与二进制来源

OFM 的 `vendor/channel-pack/NOTICE.md` 将账号包指向 [dsh-codearts-auth](https://gitee.com/iJetLi/deepseek-harness-codearts)，包内 MIT 版权声明为 `Copyright (c) 2026 Jet`。复制或翻译桥接源码应保留该声明及 MIT 许可文本。

**WASM 二进制需单独记录来源。** `qoder-wasm.ts` 将其描述为官方客户端内嵌产物；现有包的 MIT 声明本身不能证明该外部二进制的独立再分发授权。本阶段验证脚本读取用户已有的本地文件，不把二进制复制到应用仓库或发布产物。未来发布前，应明确二进制来源、哈希与分发方式。
