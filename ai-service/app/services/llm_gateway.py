"""LLM 网关（C1 简化形态）：模型切换、上下文组装、结构化输出、超时重试、Tokens 审计。

硬约束（权限边界）：输出仅为建议/解释/报告文字；不写库、不改金额、不置结论。
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)

# 复用 HTTP 连接，避免每次调用都重新握手（大模型服务多为 https，握手开销明显）
_client: httpx.AsyncClient | None = None

# 按任务收紧输出上限：日志段只需短文本，过大 max_tokens 会显著拉长生成时间
_TASK_MAX_TOKENS = {"ATTRIBUTION": 220, "REPORT": 420, "ALIGN": 800}


def _get_client(timeout: float) -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(timeout=timeout)
    return _client


def _plain_text(content: str) -> str:
    """把模型常见的 ```json {...} ``` 包裹解析为纯文本，取 advice/suggestion/attribution 字段。

    模型即便被要求输出纯文本也常返回 JSON 代码块，直接存进报告会出现代码围栏。
    解析失败时退化为去掉代码围栏的原文本，绝不丢内容。
    """
    text = (content or "").strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1] if "\n" in text else text
        if text.endswith("```"):
            text = text[:-3]
        text = text.strip()
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return text
    if isinstance(data, dict):
        parts = []
        for key in ("advice", "suggestion", "attribution", "content", "text"):
            value = data.get(key)
            if isinstance(value, str) and value.strip():
                parts.append(value.strip())
        if parts:
            return "\n".join(parts)
    return text

TASK_INSTRUCTIONS = {
    "ATTRIBUTION": (
        "任务：为造价预审疑点生成归因解释与核查建议。"
        "要求：不超过 120 字，说明应核对哪些资料、依据什么口径，禁止给出金额判定或审计结论。"
    ),
    "REPORT": (
        "任务：撰写预审报告中的文字段落（概况/建议）。"
        "要求：不超过 300 字，客观、可执行；不得编造数据，不得修改或推演金额，不得下最终结论。"
    ),
    "ALIGN": (
        "任务：图纸分项与清单分项的语义对齐。"
        "要求：仅输出 JSON，格式 {\"matches\":[{\"drawing\":\"...\",\"boq\":\"...\",\"confidence\":0.0}]}，"
        "confidence 取值 0~1；不要输出金额。"
    ),
}


class LlmNotConfigured(RuntimeError):
    """LLM 未配置（元景/vLLM 均不可用）"""


def _completion_url(base_url: str) -> str:
    base = (base_url or "").rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    if base.endswith("/v1"):
        return f"{base}/chat/completions"
    return f"{base}/v1/chat/completions"


def _build_messages(task_type: str, prompt: str, context: dict[str, Any] | None) -> list[dict[str, str]]:
    settings = get_settings()
    system = settings.ai_boundary_prompt
    instruction = TASK_INSTRUCTIONS.get(task_type, TASK_INSTRUCTIONS["ATTRIBUTION"])
    messages = [{"role": "system", "content": f"{system}\n{instruction}"}]
    user_content = prompt.strip()
    if context:
        user_content += "\n\n[上下文]\n" + json.dumps(context, ensure_ascii=False, indent=2)
    messages.append({"role": "user", "content": user_content})
    return messages


async def chat(task_type: str, prompt: str, context: dict[str, Any] | None = None) -> dict[str, Any]:
    settings = get_settings()
    if not (settings.llm_base_url and settings.llm_api_key):
        raise LlmNotConfigured("LLM 未配置（AI_LLM_BASE_URL / AI_LLM_API_KEY）")

    payload = {
        "model": settings.llm_model,
        "messages": _build_messages(task_type, prompt, context),
        "temperature": settings.llm_temperature,
        "max_tokens": min(settings.llm_max_tokens, _TASK_MAX_TOKENS.get(task_type, settings.llm_max_tokens)),
    }
    if task_type == "ALIGN":
        payload["response_format"] = {"type": "json_object"}

    url = _completion_url(settings.llm_base_url)
    headers = {"Authorization": f"Bearer {settings.llm_api_key}", "Content-Type": "application/json"}

    last_error: Exception | None = None
    for attempt in range(settings.llm_max_retries + 1):
        try:
            client = _get_client(settings.llm_timeout_seconds)
            response = await client.post(url, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
            choice = (body.get("choices") or [{}])[0]
            content = (choice.get("message") or {}).get("content") or ""
            usage = body.get("usage") or {}
            tokens = int(usage.get("total_tokens") or 0)
            logger.info("LLM 调用成功 task=%s model=%s tokens=%s", task_type, body.get("model"), tokens)
            # 文本类任务去掉模型常带的 json 代码围栏；ALIGN 需保留 JSON 供结构化校验
            result_content = content.strip() if task_type == "ALIGN" else _plain_text(content)
            return {
                "content": result_content,
                "model": body.get("model") or settings.llm_model,
                "tokens_used": tokens,
            }
        except Exception as exc:  # noqa: BLE001 网关需容忍模型侧抖动
            last_error = exc
            logger.warning("LLM 调用失败（第 %s 次）: %s", attempt + 1, exc)
            if attempt < settings.llm_max_retries:
                await asyncio.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"LLM 调用失败：{last_error}")


def check_json(content: str) -> Any:
    """护栏：结构化任务输出 JSON 校验（失败返回 None，由调用方降级）。"""
    try:
        return json.loads(content)
    except (json.JSONDecodeError, TypeError):
        return None
