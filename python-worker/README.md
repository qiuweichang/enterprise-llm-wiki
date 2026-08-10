# LLM Wiki Python Worker

独立提供网页正文提取、文档/OCR 和音视频转写。Java 服务只通过 HTTP 调用它，因此重型解析依赖和模型显存不会污染主进程。

```powershell
python -m venv .venv
.venv\Scripts\pip install -e ".[test]"
$env:LLM_WIKI_STORAGE_ROOT="..\server\data"
.venv\Scripts\uvicorn llm_wiki_worker.main:app --host 127.0.0.1 --port 8101
```

本地默认与 Java 服务共享 `../server/data`；部署时可通过 `LLM_WIKI_STORAGE_ROOT` 指向共同挂载的绝对目录。

扫描件 OCR 需要安装 `.[ocr]` 和系统 Tesseract/Poppler；音视频转写需要 `.[transcription]`，首次使用会下载 Whisper 模型。默认禁止网页提取访问私网地址；确有企业内网页面需求时，应通过 `LLM_WIKI_ALLOWED_HOSTS` 精确加入域名，不建议全局开启私网访问。
