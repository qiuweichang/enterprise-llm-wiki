"""Python 提取服务的关键安全与纯文本流程测试。"""

from pathlib import Path

import pytest
from fastapi import HTTPException

from llm_wiki_worker.main import extract_file_content, format_timestamp, validate_remote_url


def test_plain_text_extraction(tmp_path: Path) -> None:
    """纯文本文件应保持内容并返回确定的提取器名称。"""
    source = tmp_path / "source.md"
    source.write_text("# Enterprise knowledge\n\nTraceable evidence.", encoding="utf-8")
    content, extractor = extract_file_content(source, "text/markdown")
    assert "Traceable evidence" in content
    assert extractor == "plain-text"


def test_loopback_url_is_rejected() -> None:
    """默认配置必须阻止网页提取访问环回地址。"""
    with pytest.raises(HTTPException) as error:
        validate_remote_url("http://127.0.0.1:8080/private")
    assert error.value.status_code == 400


def test_timestamp_format() -> None:
    """转写时间戳应稳定使用 HH:MM:SS。"""
    assert format_timestamp(3661.9) == "01:01:01"
