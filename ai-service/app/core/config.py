from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """配置外置：生产在容器云以环境变量注入（容器云方案 04）。"""

    model_config = SettingsConfigDict(env_file=".env", env_prefix="AI_", extra="ignore")

    app_name: str = "通信工程造价结算 AI 预审分析工具 - AI/解析服务"
    version: str = "0.1.0"

    # LLM 网关：首选联通元景 API；不可用则内网 Qwen + vLLM。禁止外部商用 API。
    # 采用 OpenAI 兼容协议（/v1/chat/completions），同时适配元景与 vLLM。
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "yuanjing-default"
    llm_timeout_seconds: float = 60.0
    llm_max_retries: int = 2
    llm_temperature: float = 0.2
    llm_max_tokens: int = 1200

    # ODA File Converter 可执行文件路径（DWG -> DXF）
    # 留空时自动探测常见安装路径（macOS: /Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter，
    # Linux: /usr/bin/ODAFileConverter）；未安装则 DWG 解析返回明确失败原因，DXF 不受影响。
    oda_converter_path: str = ""

    # DXF 解析：单次返回的图元样例上限
    dxf_sample_limit: int = 300

    # 图纸转换产物缓存目录（DWG→DXF 结果按内容哈希存放）；容器内挂 PVC /data/cache。
    # 目录不可写时自动回退系统临时目录，本地开发无需配置。
    cache_dir: str = "/data/cache"

    # 结构化输出约束：AI 仅输出建议/解释/报告文字，不改金额、不下结论
    ai_boundary_prompt: str = (
        "你是造价预审助手。仅输出对疑点的归因解释与报告文字建议；"
        "禁止修改任何金额数据、禁止下最终审计结论。输出遵循给定 JSON Schema。"
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
