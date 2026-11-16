"""LLM 网关路由：统一封装模型切换、上下文组装、结构化输出、超时重试、Tokens 审计。

硬约束（权限边界）：输出仅为建议/解释/报告文字；不写库、不改金额、不置结论。
"""
from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.core.config import get_settings

router = APIRouter(prefix="/llm", tags=["llm"])


class ChatIn(BaseModel):
    task_type: str = Field(..., description="ATTRIBUTION 疑点归因 / REPORT 成文 / ALIGN 语义对齐")
    prompt: str
    context: dict | None = None


class ChatOut(BaseModel):
    task_type: str
    content: str
    model: str
    tokens_used: int = 0
    boundary: str = "advice_only"  # 仅建议，不作为结论


@router.post("/chat", response_model=ChatOut)
def chat(inp: ChatIn) -> ChatOut:
    """LLM 网关占位：骨架阶段返回固定结构，W3~W4 接元景 API/内网 vLLM。"""
    s = get_settings()
    if not (s.llm_base_url and s.llm_api_key):
        raise RuntimeError("LLM 未配置（AI_LLM_BASE_URL / AI_LLM_API_KEY）")
    # TODO(W3~W4): httpx 调用元景 API，结构化输出 Schema 校验，超时/重试/Tokens 审计
    return ChatOut(task_type=inp.task_type, content="", model=s.llm_model)
