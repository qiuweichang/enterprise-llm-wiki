"""本地 CPU 中文向量服务；不调用聊天模型，不把知识正文传给第三方 API。"""

import logging
from pathlib import Path
from threading import Lock
from typing import Literal

import numpy as np
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter()
logger = logging.getLogger(__name__)
# 版本号同时标识模型、量化实现及分块规则；改变任一项时必须升级 Java 的索引标识并重建。
MODEL_ID = "bge-small-zh-v1.5-fastembed074-chunk320-v1"
MODEL_NAME = "BAAI/bge-small-zh-v1.5"
DIMENSIONS = 512
_model = None
# 单个 ONNX 模型串行推理、两条 CPU 线程，防止后台补建挤爆 CPU/内存。
_lock = Lock()


class EmbeddingRequest(BaseModel):
    """有界向量请求；Java 每批最多发送 16 个短文本片段。"""

    texts: list[str] = Field(min_length=1, max_length=16)
    kind: Literal["query", "passage"] = "passage"


@router.post("/embeddings")
def embeddings(request: EmbeddingRequest) -> dict:
    """惰性加载固定模型并返回单位向量；异常返回明确 503，不用伪造向量降级。"""
    global _model
    if any(not text.strip() or len(text) > 4000 for text in request.texts):
        raise HTTPException(422, "向量文本必须为 1～4000 个字符")
    try:
        with _lock:
            if _model is None:
                from fastembed import TextEmbedding
                logger.info("加载语义模型 model=%s", MODEL_NAME)
                _model = TextEmbedding(MODEL_NAME, cache_dir=str(Path(__file__).resolve().parents[1] / "models"), threads=2)
            texts = request.texts
            if request.kind == "query":
                texts = ["为这个句子生成表示以用于检索相关文章：" + text for text in texts]
            vectors = np.asarray(list(_model.embed(texts, batch_size=16)), dtype=np.float32)
            norms = np.linalg.norm(vectors, axis=1, keepdims=True)
            if vectors.shape != (len(texts), DIMENSIONS) or not np.isfinite(vectors).all() or (norms <= 0).any():
                raise ValueError("模型返回了无效向量")
            vectors = vectors / norms
        logger.info("语义向量完成 kind=%s count=%s", request.kind, len(texts))
        return {"modelId": MODEL_ID, "dimensions": DIMENSIONS, "vectors": vectors.tolist()}
    except Exception as exc:
        logger.error("语义模型不可用", exc_info=True)
        raise HTTPException(503, "本地语义模型加载或推理失败，请检查 Python 日志、模型缓存及依赖") from exc
