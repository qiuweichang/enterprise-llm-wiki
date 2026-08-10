"""FastAPI 入口：提供受限网页抓取、文档/OCR 提取和音视频转写。"""

from __future__ import annotations

import hashlib
import ipaddress
import logging
import mimetypes
import os
import re
import socket
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup
from fastapi import FastAPI, HTTPException
from markdownify import markdownify
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from pypdf import PdfReader

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("llm_wiki_worker")


class Settings(BaseSettings):
    """进程配置；共享存储根目录和 SSRF 例外只能通过环境变量注入。"""

    # 本地启动时 Java 模块以 server 为工作目录，二者必须指向同一原始对象根目录。
    storage_root: Path = Path("../server/data")
    allow_private_urls: bool = False
    allowed_hosts: str = ""
    max_download_bytes: int = 20 * 1024 * 1024
    request_timeout_seconds: float = 30.0
    whisper_model: str = "small"

    model_config = SettingsConfigDict(env_prefix="LLM_WIKI_", extra="ignore")

    def allowed_host_set(self) -> set[str]:
        """返回明确允许访问的企业内部主机名集合。"""
        return {host.strip().lower() for host in self.allowed_hosts.split(",") if host.strip()}


settings = Settings()
app = FastAPI(title="LLM Wiki Python Worker", version="1.0.0")


class WebExtractRequest(BaseModel):
    """网页提取请求。"""

    url: str = Field(min_length=8, max_length=4096)


class FileExtractRequest(BaseModel):
    """共享对象存储文件提取请求。"""

    path: str = Field(min_length=1, max_length=32768)
    content_type: str = "application/octet-stream"


class TranscribeRequest(BaseModel):
    """音视频转写请求。"""

    path: str = Field(min_length=1, max_length=32768)


class ExtractionResponse(BaseModel):
    """Java 主服务消费的统一提取结果。"""

    title: str
    markdown: str
    contentHash: str
    metadata: dict[str, Any]


@app.get("/health")
def health() -> dict[str, str]:
    """返回轻量存活状态，不触发重型 OCR 或转写模型加载。"""
    return {"status": "UP", "service": "llm-wiki-python-worker"}


@app.post("/extract/web", response_model=ExtractionResponse)
async def extract_web(request: WebExtractRequest) -> ExtractionResponse:
    """抓取通过 SSRF 校验的网页，移除脚本与导航噪声并转换为 Markdown。"""
    url = request.url.strip()
    validate_remote_url(url)
    try:
        html, final_url, content_type = await download_with_safe_redirects(url)
        if "html" not in content_type.lower():
            raise HTTPException(status_code=415, detail="URL did not return HTML content")
        title, content = html_to_markdown(html, final_url)
        logger.info("Web extraction completed url=%s characters=%s", final_url, len(content))
        return response(title or final_url, content, {
            "extractor": "beautifulsoup-markdownify",
            "finalUrl": final_url,
            "contentType": content_type,
        })
    except HTTPException:
        raise
    except Exception as exception:
        logger.error("Web extraction failed url=%s", url, exc_info=exception)
        raise HTTPException(status_code=502, detail="Web extraction failed") from exception


@app.post("/extract/file", response_model=ExtractionResponse)
def extract_file(request: FileExtractRequest) -> ExtractionResponse:
    """按文件类型提取文本；扫描图片/PDF 在安装 OCR 可选依赖后自动降级。"""
    path = resolve_storage_path(request.path)
    try:
        content, extractor = extract_file_content(path, request.content_type)
        if not content.strip():
            raise HTTPException(status_code=422, detail="No text could be extracted from the file")
        logger.info("File extraction completed path=%s extractor=%s characters=%s", path, extractor, len(content))
        return response(path.stem, content, {
            "extractor": extractor,
            "contentType": request.content_type,
            "objectSize": path.stat().st_size,
        })
    except HTTPException:
        raise
    except Exception as exception:
        logger.error("File extraction failed path=%s", path, exc_info=exception)
        raise HTTPException(status_code=500, detail="File extraction failed") from exception


@app.post("/transcribe", response_model=ExtractionResponse)
def transcribe(request: TranscribeRequest) -> ExtractionResponse:
    """使用可选 faster-whisper 模型转写音视频并输出带时间戳 Markdown。"""
    path = resolve_storage_path(request.path)
    try:
        from faster_whisper import WhisperModel  # type: ignore[import-not-found]
    except ImportError as exception:
        raise HTTPException(
            status_code=503,
            detail="Transcription dependencies are not installed; install the transcription optional group",
        ) from exception
    try:
        model = WhisperModel(settings.whisper_model, device="auto", compute_type="int8")
        segments, info = model.transcribe(str(path), vad_filter=True)
        lines = [f"# {path.stem} 转写\n"]
        for segment in segments:
            lines.append(f"- [{format_timestamp(segment.start)}–{format_timestamp(segment.end)}] {segment.text.strip()}")
        content = "\n".join(lines).strip()
        logger.info("Transcription completed path=%s language=%s characters=%s", path, info.language, len(content))
        return response(path.stem, content, {
            "extractor": "faster-whisper",
            "language": info.language,
            "languageProbability": info.language_probability,
            "model": settings.whisper_model,
        })
    except Exception as exception:
        logger.error("Transcription failed path=%s", path, exc_info=exception)
        raise HTTPException(status_code=500, detail="Transcription failed") from exception


def validate_remote_url(url: str) -> None:
    """校验 URL 协议、凭据和全部 DNS 结果，默认拒绝私网/环回/链路本地地址。"""
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(status_code=400, detail="Only http/https URLs are supported")
    if parsed.username or parsed.password:
        raise HTTPException(status_code=400, detail="URLs with embedded credentials are not allowed")
    hostname = parsed.hostname.lower().rstrip(".")
    if hostname in settings.allowed_host_set():
        return
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(hostname, parsed.port or 443)}
    except socket.gaierror as exception:
        raise HTTPException(status_code=400, detail="URL host could not be resolved") from exception
    if not addresses:
        raise HTTPException(status_code=400, detail="URL host has no address")
    if settings.allow_private_urls:
        return
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if any((ip.is_private, ip.is_loopback, ip.is_link_local, ip.is_multicast, ip.is_reserved, ip.is_unspecified)):
            raise HTTPException(status_code=400, detail="Private or special network addresses are not allowed")


async def download_with_safe_redirects(url: str) -> tuple[str, str, str]:
    """手动处理最多五次重定向，并对每一个新地址重新执行 SSRF 校验和下载上限。"""
    current = url
    timeout = httpx.Timeout(settings.request_timeout_seconds)
    headers = {"User-Agent": "LLM-Wiki-Extractor/1.0 (+enterprise knowledge compiler)"}
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, headers=headers) as client:
        for _ in range(6):
            validate_remote_url(current)
            async with client.stream("GET", current) as result:
                if result.status_code in {301, 302, 303, 307, 308}:
                    location = result.headers.get("location")
                    if not location:
                        raise HTTPException(status_code=502, detail="Redirect has no location")
                    current = urljoin(current, location)
                    continue
                result.raise_for_status()
                chunks: list[bytes] = []
                total = 0
                async for chunk in result.aiter_bytes():
                    total += len(chunk)
                    if total > settings.max_download_bytes:
                        raise HTTPException(status_code=413, detail="Remote document exceeds download limit")
                    chunks.append(chunk)
                encoding = result.encoding or "utf-8"
                return b"".join(chunks).decode(encoding, errors="replace"), str(result.url), result.headers.get(
                    "content-type", "text/html"
                )
    raise HTTPException(status_code=400, detail="Too many redirects")


def html_to_markdown(html: str, base_url: str) -> tuple[str, str]:
    """清理 HTML 噪声、选择主内容并把相对链接转换为绝对链接。"""
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    for element in soup.select("script, style, noscript, iframe, svg, canvas, nav, footer, form"):
        element.decompose()
    main = soup.select_one("article, main, [role='main']") or soup.body or soup
    for anchor in main.select("a[href]"):
        anchor["href"] = urljoin(base_url, anchor.get("href", ""))
    content = markdownify(str(main), heading_style="ATX", bullets="-")
    content = re.sub(r"\n{3,}", "\n\n", content).strip()
    return title, content


def resolve_storage_path(raw_path: str) -> Path:
    """把请求路径规范化到共享存储根目录内，阻止读取任意系统文件。"""
    root = settings.storage_root.expanduser().resolve()
    path = Path(raw_path).expanduser().resolve()
    try:
        path.relative_to(root)
    except ValueError as exception:
        raise HTTPException(status_code=400, detail="File path is outside the configured storage root") from exception
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Stored file does not exist")
    return path


def extract_file_content(path: Path, content_type: str) -> tuple[str, str]:
    """根据后缀/MIME 执行文本、Markdown、HTML、DOCX、PDF 或图片 OCR 提取。"""
    suffix = path.suffix.lower()
    detected = content_type or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    if suffix in {".txt", ".md", ".markdown", ".csv", ".json", ".yaml", ".yml", ".xml"}:
        return path.read_text(encoding="utf-8", errors="replace"), "plain-text"
    if suffix in {".html", ".htm"}:
        _, content = html_to_markdown(path.read_text(encoding="utf-8", errors="replace"), path.as_uri())
        return content, "local-html"
    if suffix == ".docx":
        return extract_docx_markdown(path), "python-docx"
    if suffix == ".pdf" or detected == "application/pdf":
        text = "\n\n".join(page.extract_text() or "" for page in PdfReader(path).pages).strip()
        if text:
            return text, "pypdf"
        return ocr_pdf(path), "tesseract-pdf-ocr"
    if detected.startswith("image/") or suffix in {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp"}:
        return ocr_image(path), "tesseract-image-ocr"
    raise HTTPException(status_code=415, detail=f"Unsupported file type: {suffix or detected}")


def extract_docx_markdown(path: Path) -> str:
    """按正文顺序提取 DOCX 段落和表格，避免产品白皮书中的功能表被遗漏。"""
    from docx import Document
    from docx.table import Table

    document = Document(path)
    blocks: list[str] = []
    for item in document.iter_inner_content():
        if isinstance(item, Table):
            table_markdown = docx_table_markdown(item)
            if table_markdown:
                blocks.append(table_markdown)
            continue
        text = item.text.strip()
        if not text:
            continue
        style_name = item.style.name if item.style is not None else ""
        heading_match = re.match(r"Heading\s+(\d+)", style_name, re.IGNORECASE)
        blocks.append(f"{'#' * min(6, int(heading_match.group(1)))} {text}" if heading_match else text)
    if blocks:
        return "\n\n".join(blocks)
    # 少数文档把全部内容放在文本框中；此时从 OOXML 文本节点安全降级。
    return "\n\n".join(text.strip() for text in document.element.xpath(".//w:t/text()") if text.strip())


def docx_table_markdown(table: Any) -> str:
    """把 Word 表格转成 Markdown，保留多行单元格并按首行生成表头。"""
    rows: list[list[str]] = []
    for row in table.rows:
        values = [re.sub(r"\s+", " ", cell.text).strip().replace("|", "\\|") for cell in row.cells]
        if any(values):
            rows.append(values)
    if not rows:
        return ""
    width = max(len(row) for row in rows)
    normalized = [row + [""] * (width - len(row)) for row in rows]
    lines = ["| " + " | ".join(normalized[0]) + " |", "| " + " | ".join(["---"] * width) + " |"]
    lines.extend("| " + " | ".join(row) + " |" for row in normalized[1:])
    return "\n".join(lines)


def ocr_image(path: Path) -> str:
    """使用可选 Pillow/pytesseract 识别单张图片。"""
    try:
        import pytesseract  # type: ignore[import-not-found]
        from PIL import Image  # type: ignore[import-not-found]
    except ImportError as exception:
        raise HTTPException(status_code=503, detail="OCR dependencies are not installed") from exception
    return pytesseract.image_to_string(Image.open(path), lang=os.getenv("LLM_WIKI_OCR_LANG", "chi_sim+eng")).strip()


def ocr_pdf(path: Path) -> str:
    """把扫描 PDF 渲染为图片后逐页 OCR；依赖缺失时返回可操作错误。"""
    try:
        import pytesseract  # type: ignore[import-not-found]
        from pdf2image import convert_from_path  # type: ignore[import-not-found]
    except ImportError as exception:
        raise HTTPException(status_code=503, detail="PDF OCR dependencies are not installed") from exception
    language = os.getenv("LLM_WIKI_OCR_LANG", "chi_sim+eng")
    pages = convert_from_path(path, dpi=220)
    return "\n\n".join(pytesseract.image_to_string(page, lang=language) for page in pages).strip()


def format_timestamp(seconds: float) -> str:
    """把秒数转换为适合 Markdown 引用的 HH:MM:SS。"""
    total = max(0, int(seconds))
    return f"{total // 3600:02d}:{(total % 3600) // 60:02d}:{total % 60:02d}"


def response(title: str, markdown: str, metadata: dict[str, Any]) -> ExtractionResponse:
    """构造带内容哈希的统一响应。"""
    content = markdown.strip()
    return ExtractionResponse(
        title=title.strip() or "Untitled source",
        markdown=content,
        contentHash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
        metadata=metadata,
    )
