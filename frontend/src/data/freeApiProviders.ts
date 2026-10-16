export type FreeApiProviderCategory = 'free' | 'trial' | 'verify' | 'local';

export interface FreeApiProvider {
  id: string;
  name: string;
  url: string;
  category: FreeApiProviderCategory;
  description: [string, string];
}

// Source: dsh-our-free-model/src/providers/registry.json (76 source records).
// IDs, names and website URLs are preserved, including distinct source product
// entries. This is a source-derived discovery directory, not a current pricing
// audit. 'free' includes source-declared 'free-tier'; descriptions qualify both.
export const freeApiProviders: FreeApiProvider[] = [
  {
    "id": "agnes",
    "name": "Agnes AI",
    "url": "https://agnes-ai.com/",
    "category": "verify",
    "description": [
      "Agnes；API Key 接入。免费或试用资格请查官网。",
      "Agnes; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "alibaba_cloud",
    "name": "Alibaba Cloud",
    "url": "https://www.alibabacloud.com/en/product/modelstudio",
    "category": "verify",
    "description": [
      "Qwen、Kimi、GLM；API Key 接入。免费或试用资格请查官网。",
      "Qwen, Kimi, GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "anthropic",
    "name": "Anthropic",
    "url": "https://www.anthropic.com/",
    "category": "verify",
    "description": [
      "Claude；API Key 接入。免费或试用资格请查官网。",
      "Claude; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "azure_openai",
    "name": "Azure OpenAI",
    "url": "https://azure.microsoft.com/en-us/products/ai-services/openai-service/",
    "category": "verify",
    "description": [
      "托管模型；API Key 接入。免费或试用资格请查官网。",
      "Hosted models; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "baidu",
    "name": "Baidu Qianfan",
    "url": "https://qianfan.baidubce.com",
    "category": "verify",
    "description": [
      "DeepSeek、GLM；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek, GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "bedrock",
    "name": "Amazon Bedrock",
    "url": "https://aws.amazon.com/bedrock/",
    "category": "verify",
    "description": [
      "GPT-OSS；API Key 接入。免费或试用资格请查官网。",
      "GPT-OSS; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "cerebras",
    "name": "Cerebras",
    "url": "https://www.cerebras.ai/",
    "category": "verify",
    "description": [
      "GPT-OSS、GLM、Llama；API Key 接入。免费或试用资格请查官网。",
      "GPT-OSS, GLM, Llama; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "cheaperinference",
    "name": "Cheaper Inference",
    "url": "https://cheaperinference.com/",
    "category": "verify",
    "description": [
      "GPT；API Key 接入。免费或试用资格请查官网。",
      "GPT; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "chutes",
    "name": "Chutes",
    "url": "https://chutes.ai/",
    "category": "verify",
    "description": [
      "Qwen；API Key 接入。免费或试用资格请查官网。",
      "Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "cline_pass",
    "name": "ClinePass",
    "url": "https://cline.bot/",
    "category": "verify",
    "description": [
      "GLM、Kimi；API Key 接入。免费或试用资格请查官网。",
      "GLM, Kimi; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "cohere",
    "name": "Cohere",
    "url": "https://cohere.com/",
    "category": "verify",
    "description": [
      "Command；API Key 接入。免费或试用资格请查官网。",
      "Command; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "deepinfra",
    "name": "DeepInfra",
    "url": "https://deepinfra.com/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "deepseek",
    "name": "DeepSeek",
    "url": "https://www.deepseek.com/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "experiential",
    "name": "Experiential Labs",
    "url": "https://www.experientiallabs.ai/",
    "category": "verify",
    "description": [
      "Union Alpha；API Key 接入。免费或试用资格请查官网。",
      "Union Alpha; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "featherless",
    "name": "Featherless AI",
    "url": "https://featherless.ai/",
    "category": "verify",
    "description": [
      "DeepSeek、GLM；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek, GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "fireworks",
    "name": "Fireworks",
    "url": "https://fireworks.ai/",
    "category": "verify",
    "description": [
      "DeepSeek、Llama、Qwen；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek, Llama, Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "glm",
    "name": "Z.ai (Anthropic wire)",
    "url": "https://z.ai",
    "category": "verify",
    "description": [
      "GLM；API Key 接入。免费或试用资格请查官网。",
      "GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "glm-cn",
    "name": "Zhipu GLM (CN)",
    "url": "https://open.bigmodel.cn",
    "category": "verify",
    "description": [
      "GLM；API Key 接入。免费或试用资格请查官网。",
      "GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "groq",
    "name": "Groq",
    "url": "https://groq.com/",
    "category": "verify",
    "description": [
      "Llama、Qwen；API Key 接入。免费或试用资格请查官网。",
      "Llama, Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "huggingface",
    "name": "Hugging Face",
    "url": "https://huggingface.co/",
    "category": "verify",
    "description": [
      "Qwen；API Key 接入。免费或试用资格请查官网。",
      "Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "hyperbolic",
    "name": "Hyperbolic AI",
    "url": "https://hyperbolic.xyz",
    "category": "verify",
    "description": [
      "Qwen、DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "Qwen, DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "kimi",
    "name": "Kimi",
    "url": "https://www.kimi.com/",
    "category": "verify",
    "description": [
      "Kimi；API Key 接入。免费或试用资格请查官网。",
      "Kimi; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "kimi_code",
    "name": "Kimi Code",
    "url": "https://www.kimi.com/code",
    "category": "verify",
    "description": [
      "Kimi；API Key 接入。免费或试用资格请查官网。",
      "Kimi; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "lightning",
    "name": "Lightning AI",
    "url": "https://lightning.ai/",
    "category": "verify",
    "description": [
      "Qwen；API Key 接入。免费或试用资格请查官网。",
      "Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "llm7",
    "name": "LLM7.io",
    "url": "https://llm7.io/",
    "category": "verify",
    "description": [
      "GPT、Claude、DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "GPT, Claude, DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "minimax",
    "name": "MiniMax",
    "url": "https://www.minimax.io/",
    "category": "verify",
    "description": [
      "MiniMax；API Key 接入。免费或试用资格请查官网。",
      "MiniMax; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "minimax-cn",
    "name": "MiniMax (CN)",
    "url": "https://www.minimaxi.com",
    "category": "verify",
    "description": [
      "MiniMax；API Key 接入。免费或试用资格请查官网。",
      "MiniMax; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "mistral",
    "name": "Mistral",
    "url": "https://mistral.ai/",
    "category": "verify",
    "description": [
      "Mistral、Codestral；API Key 接入。免费或试用资格请查官网。",
      "Mistral, Codestral; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "mistral_codestral",
    "name": "Mistral Codestral",
    "url": "https://mistral.ai/",
    "category": "verify",
    "description": [
      "Codestral；API Key 接入。免费或试用资格请查官网。",
      "Codestral; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "morph",
    "name": "Morph LLM",
    "url": "https://morphllm.com",
    "category": "verify",
    "description": [
      "Morph、Qwen；API Key 接入。免费或试用资格请查官网。",
      "Morph, Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "nararoute",
    "name": "NaraRoute",
    "url": "https://router.bynara.id/",
    "category": "verify",
    "description": [
      "Kimi；API Key 接入。免费或试用资格请查官网。",
      "Kimi; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "nebius",
    "name": "Nebius Token Factory",
    "url": "https://tokenfactory.nebius.com/",
    "category": "verify",
    "description": [
      "Llama、Qwen；API Key 接入。免费或试用资格请查官网。",
      "Llama, Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "novita",
    "name": "Novita AI",
    "url": "https://novita.ai/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "openai_api",
    "name": "OpenAI API",
    "url": "https://platform.openai.com/",
    "category": "verify",
    "description": [
      "GPT；API Key 接入。免费或试用资格请查官网。",
      "GPT; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "opencode_go",
    "name": "OpenCode Go",
    "url": "https://opencode.ai/go",
    "category": "verify",
    "description": [
      "DeepSeek、GLM；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek, GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "opper",
    "name": "Opper",
    "url": "https://opper.ai/",
    "category": "verify",
    "description": [
      "Claude；API Key 接入。免费或试用资格请查官网。",
      "Claude; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "orcarouter",
    "name": "OrcaRouter",
    "url": "https://www.orcarouter.ai/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "perplexity",
    "name": "Perplexity",
    "url": "https://www.perplexity.ai",
    "category": "verify",
    "description": [
      "Sonar；API Key 接入。免费或试用资格请查官网。",
      "Sonar; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "poolside",
    "name": "Poolside AI",
    "url": "https://poolside.ai/",
    "category": "verify",
    "description": [
      "Laguna；API Key 接入。免费或试用资格请查官网。",
      "Laguna; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "qwencloud",
    "name": "QwenCloud Token Plan",
    "url": "https://qwencloud.com/",
    "category": "verify",
    "description": [
      "Qwen；API Key 接入。免费或试用资格请查官网。",
      "Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "qwencloud_coding",
    "name": "QwenCloud Coding Plan",
    "url": "https://qwencloud.com/",
    "category": "verify",
    "description": [
      "Qwen、Kimi、GLM；API Key 接入。免费或试用资格请查官网。",
      "Qwen, Kimi, GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "sambanova",
    "name": "SambaNova",
    "url": "https://sambanova.ai/",
    "category": "verify",
    "description": [
      "Llama；API Key 接入。免费或试用资格请查官网。",
      "Llama; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "scaleway",
    "name": "Scaleway",
    "url": "https://www.scaleway.com/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "siliconflow",
    "name": "SiliconFlow",
    "url": "https://siliconflow.com/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "tencent",
    "name": "Tencent Hunyuan",
    "url": "https://cloud.tencent.com/product/hunyuan",
    "category": "verify",
    "description": [
      "Hunyuan；API Key 接入。免费或试用资格请查官网。",
      "Hunyuan; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "together",
    "name": "Together AI",
    "url": "https://www.together.ai/",
    "category": "verify",
    "description": [
      "Llama、DeepSeek、Qwen；API Key 接入。免费或试用资格请查官网。",
      "Llama, DeepSeek, Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "tokenrouter",
    "name": "TokenRouter",
    "url": "https://www.tokenrouter.com/",
    "category": "verify",
    "description": [
      "Claude；API Key 接入。免费或试用资格请查官网。",
      "Claude; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "venice",
    "name": "Venice AI",
    "url": "https://venice.ai",
    "category": "verify",
    "description": [
      "Venice Uncensored 1.2、GLM、Qwen；API Key 接入。免费或试用资格请查官网。",
      "Venice Uncensored 1.2, GLM, Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "vercel",
    "name": "Vercel AI Gateway",
    "url": "https://vercel.com/ai-gateway",
    "category": "verify",
    "description": [
      "GPT；API Key 接入。免费或试用资格请查官网。",
      "GPT; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "vertex",
    "name": "Google Vertex AI",
    "url": "https://cloud.google.com/vertex-ai",
    "category": "verify",
    "description": [
      "Gemini；Google Cloud 身份认证。免费或试用资格请查官网。",
      "Gemini; Google Cloud identity. Check official free or trial eligibility."
    ]
  },
  {
    "id": "vertex-partner",
    "name": "Vertex Partner Models",
    "url": "https://cloud.google.com/vertex-ai/generative-ai/docs/partner-models/use-partner-models",
    "category": "verify",
    "description": [
      "DeepSeek、Qwen；Google Cloud 身份认证。免费或试用资格请查官网。",
      "DeepSeek, Qwen; Google Cloud identity. Check official free or trial eligibility."
    ]
  },
  {
    "id": "volcengine-ark",
    "name": "Volcengine Ark",
    "url": "https://console.volcengine.com/ark",
    "category": "verify",
    "description": [
      "Seed；API Key 接入。免费或试用资格请查官网。",
      "Seed; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "wafer",
    "name": "Wafer",
    "url": "https://www.wafer.ai/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "wandb",
    "name": "W&B Inference",
    "url": "https://wandb.ai/site/inference/",
    "category": "verify",
    "description": [
      "GPT-OSS；API Key 接入。免费或试用资格请查官网。",
      "GPT-OSS; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "xai",
    "name": "xAI (Grok)",
    "url": "https://x.ai/",
    "category": "verify",
    "description": [
      "Grok；API Key 接入。免费或试用资格请查官网。",
      "Grok; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "xiaomi-tokenplan",
    "name": "Xiaomi MiMo Token Plan",
    "url": "https://mimo.xiaomi.com",
    "category": "verify",
    "description": [
      "MiMo、Claude；API Key 接入。免费或试用资格请查官网。",
      "MiMo, Claude; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "xkiro",
    "name": "xKiro",
    "url": "https://xkiro.com/",
    "category": "verify",
    "description": [
      "Qwen；API Key 接入。免费或试用资格请查官网。",
      "Qwen; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "zai",
    "name": "Z.ai Coding Plan",
    "url": "https://z.ai/",
    "category": "verify",
    "description": [
      "GLM；API Key 接入。免费或试用资格请查官网。",
      "GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "zai_api",
    "name": "Z.ai API",
    "url": "https://z.ai/",
    "category": "verify",
    "description": [
      "GLM；API Key 接入。免费或试用资格请查官网。",
      "GLM; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "zenmux",
    "name": "ZenMux",
    "url": "https://zenmux.ai/",
    "category": "verify",
    "description": [
      "DeepSeek；API Key 接入。免费或试用资格请查官网。",
      "DeepSeek; API key access. Check official free or trial eligibility."
    ]
  },
  {
    "id": "opencode",
    "name": "OpenCode Free",
    "url": "https://opencode.ai",
    "category": "free",
    "description": [
      "Muse Spark、Union Alpha；无需 API Key。免费模型、额度和资格请查官网。",
      "Muse Spark, Union Alpha; No API key. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "bazaarlink",
    "name": "BazaarLink",
    "url": "https://bazaarlink.ai",
    "category": "free",
    "description": [
      "Auto、Claude；API Key 接入。免费模型、额度和资格请查官网。",
      "Auto, Claude; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "byteplus",
    "name": "BytePlus Ark",
    "url": "https://console.byteplus.com/ark",
    "category": "free",
    "description": [
      "Seed；API Key 接入。免费模型、额度和资格请查官网。",
      "Seed; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "cloudflare",
    "name": "Cloudflare",
    "url": "https://www.cloudflare.com/developer-platform/products/workers-ai/",
    "category": "free",
    "description": [
      "Llama；API Key 接入。免费模型、额度和资格请查官网。",
      "Llama; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "gemini",
    "name": "Gemini",
    "url": "https://ai.google.dev/",
    "category": "free",
    "description": [
      "Gemini；API Key 接入。免费模型、额度和资格请查官网。",
      "Gemini; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "kilo",
    "name": "Kilo.ai",
    "url": "https://kilo.ai/",
    "category": "free",
    "description": [
      "Kilo Auto、Nemotron；API Key 接入。免费模型、额度和资格请查官网。",
      "Kilo Auto, Nemotron; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "kimchi",
    "name": "Kimchi",
    "url": "https://kimchi.dev",
    "category": "free",
    "description": [
      "MiniMax、Kimi；API Key 接入。免费模型、额度和资格请查官网。",
      "MiniMax, Kimi; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "nvidia_nim",
    "name": "NVIDIA NIM",
    "url": "https://build.nvidia.com/",
    "category": "free",
    "description": [
      "MiniMax、GLM；API Key 接入。免费模型、额度和资格请查官网。",
      "MiniMax, GLM; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "ollama_cloud",
    "name": "Ollama Cloud",
    "url": "https://ollama.com/",
    "category": "free",
    "description": [
      "GPT-OSS、Kimi、GLM；API Key 接入。免费模型、额度和资格请查官网。",
      "GPT-OSS, Kimi, GLM; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "open_router",
    "name": "OpenRouter",
    "url": "https://openrouter.ai/",
    "category": "free",
    "description": [
      "OpenRouterRouter；API Key 接入。免费模型、额度和资格请查官网。",
      "OpenRouterRouter; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "opencode_zen",
    "name": "OpenCode Zen",
    "url": "https://opencode.ai/zen",
    "category": "free",
    "description": [
      "Claude；API Key 接入。免费模型、额度和资格请查官网。",
      "Claude; API key access. Check eligible free models, quotas and requirements."
    ]
  },
  {
    "id": "llamacpp",
    "name": "LLaMA.cpp",
    "url": "https://github.com/ggml-org/llama.cpp",
    "category": "local",
    "description": [
      "本地模型 API 服务，无需云账号；需自备硬件并核查模型许可。",
      "Local model API, no cloud account required; bring your own hardware and check model licenses."
    ]
  },
  {
    "id": "lmstudio",
    "name": "LM Studio",
    "url": "https://lmstudio.ai/",
    "category": "local",
    "description": [
      "本地模型 API 服务，无需云账号；需自备硬件并核查模型许可。",
      "Local model API, no cloud account required; bring your own hardware and check model licenses."
    ]
  },
  {
    "id": "ollama",
    "name": "Ollama",
    "url": "https://ollama.com/",
    "category": "local",
    "description": [
      "本地模型 API 服务，无需云账号；需自备硬件并核查模型许可。",
      "Local model API, no cloud account required; bring your own hardware and check model licenses."
    ]
  },
  {
    "id": "github_copilot",
    "name": "GitHub Copilot",
    "url": "https://github.com/features/copilot",
    "category": "verify",
    "description": [
      "GPT；OAuth 账户授权。免费或试用资格请查官网。",
      "GPT; OAuth sign-in. Check official free or trial eligibility."
    ]
  },
  {
    "id": "openai",
    "name": "OpenAI / ChatGPT",
    "url": "https://openai.com/",
    "category": "verify",
    "description": [
      "GPT；OAuth 账户授权。免费或试用资格请查官网。",
      "GPT; OAuth sign-in. Check official free or trial eligibility."
    ]
  }
];
