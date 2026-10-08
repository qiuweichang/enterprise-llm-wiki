"""真实中文 ONNX 模型测试；首次运行需要已下载模型或可访问模型下载源。"""

import numpy as np
import pytest
from fastapi import HTTPException
from llm_wiki_worker.embeddings import EmbeddingRequest, embeddings, DIMENSIONS


def test_real_chinese_semantic_similarity():
    """换一种说法仍应召回差旅制度，且高于两个无关主题；不使用 mock 向量。"""
    docs = embeddings(EmbeddingRequest(texts=[
        "员工差旅规定：出差住宿费每晚最高报销五百元，须提供酒店发票。",
        "苹果种植技术：果树需要浇水、修剪枝条和预防病虫害。",
        "客户服务平台支持在线客服接待与工单分派。",
    ]))
    query = embeddings(EmbeddingRequest(texts=["住旅馆花的钱公司给报多少"], kind="query"))
    matrix = np.asarray(docs["vectors"])
    assert matrix.shape == (3, DIMENSIONS)
    assert np.allclose(np.linalg.norm(matrix, axis=1), 1, atol=1e-5)
    scores = matrix @ np.asarray(query["vectors"][0])
    assert scores[0] > 0.5
    assert scores[0] > max(scores[1:]) + 0.15


def test_embedding_rejects_empty_or_oversized_text():
    """输入边界应在推理前返回 422，避免浪费计算资源。"""
    for value in [" ", "长" * 4001]:
        with pytest.raises(HTTPException) as error:
            embeddings(EmbeddingRequest(texts=[value]))
        assert error.value.status_code == 422
