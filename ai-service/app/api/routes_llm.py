"""LLM 网关路由：统一封装模型切换、上下文组装、结构化输出、超时重试、Tokens 审计。

硬约束（权限边界）：输出仅为建议/解释/报告文字；不写库、不改金额、不置结论。
"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services import llm_gateway
from app.services.llm_gateway import LlmNotConfigured

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
async def chat(inp: ChatIn) -> ChatOut:
    try:
        result = await llm_gateway.chat(inp.task_type, inp.prompt, inp.context)
    except LlmNotConfigured as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001 模型侧异常统一转为 502，由 Java 降级
        raise HTTPException(status_code=502, detail=f"LLM 调用失败：{exc}") from exc
    return ChatOut(task_type=inp.task_type, content=result["content"],
                   model=result["model"], tokens_used=result["tokens_used"])
