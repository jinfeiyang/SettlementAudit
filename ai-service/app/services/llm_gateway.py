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
        "max_tokens": settings.llm_max_tokens,
    }
    if task_type == "ALIGN":
        payload["response_format"] = {"type": "json_object"}

    url = _completion_url(settings.llm_base_url)
    headers = {"Authorization": f"Bearer {settings.llm_api_key}", "Content-Type": "application/json"}

    last_error: Exception | None = None
    for attempt in range(settings.llm_max_retries + 1):
        try:
            async with httpx.AsyncClient(timeout=settings.llm_timeout_seconds) as client:
                response = await client.post(url, headers=headers, json=payload)
                response.raise_for_status()
                body = response.json()
            choice = (body.get("choices") or [{}])[0]
            content = (choice.get("message") or {}).get("content") or ""
            usage = body.get("usage") or {}
            tokens = int(usage.get("total_tokens") or 0)
            logger.info("LLM 调用成功 task=%s model=%s tokens=%s", task_type, body.get("model"), tokens)
            return {
                "content": content.strip(),
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
