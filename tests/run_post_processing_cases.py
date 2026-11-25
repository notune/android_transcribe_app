#!/usr/bin/env python3
"""Прогон синтетических примеров постобработки через OpenAI-совместимый сервер.

Скрипт не содержит ключей и никуда их не пишет. Адрес, модель и ключ берутся
из переменных окружения, поэтому его можно запускать на своей машине или
рядом с локальной моделью.

    OVI_PP_ENDPOINT=http://127.0.0.1:8080/v1 \
    OVI_PP_MODEL=qwen2.5-7b-instruct \
    python3 tests/run_post_processing_cases.py

    # только показать, что будет отправлено, без сети
    python3 tests/run_post_processing_cases.py --dry-run

Ключ необязателен: локальные серверы часто работают без него.

Скрипт проверяет не «красоту» ответа, а сохранение смысла: отрицания,
окончательное число, термины, числа и версии, а также то, что модель не
выполняет инструкцию, попавшую внутрь диктовки. Это дымовая проверка
конкретной модели и конкретной инструкции, а не доказательство качества.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "tests" / "fixtures" / "post-processing-cases.json"
PROMPT = ROOT / "app" / "src" / "main" / "assets" / "post_processing_prompt.txt"
PLACEHOLDER = "${output}"
TIMEOUT_SECONDS = 60


def chat_url(endpoint: str) -> str:
    url = endpoint.strip().rstrip("/")
    if url.endswith("/chat/completions"):
        return url
    if url.endswith("/v1"):
        return url + "/chat/completions"
    return url + "/v1/chat/completions"


def build_body(model: str, prompt: str) -> dict:
    return {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
    }


def request_once(url: str, key: str | None, body: dict) -> str:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as response:
        payload = json.loads(response.read().decode("utf-8"))
    error = payload.get("error")
    if error:
        raise RuntimeError(str(error.get("message") or error))
    choices = payload.get("choices") or []
    if not choices:
        raise RuntimeError("в ответе нет choices")
    choice = choices[0]
    if choice.get("finish_reason") == "length":
        raise RuntimeError("ответ обрезан (finish_reason=length)")
    message = choice.get("message") or {}
    content = message.get("content")
    if content is None:
        content = choice.get("text", "")
    return str(content).strip()


def normalise(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower().replace("ё", "е")).strip()


def check(case_id: str, output: str) -> list[str]:
    """Возвращает список нарушений. Пустой список = проверка пройдена."""
    text = normalise(output)
    problems: list[str] = []

    if case_id == "negation":
        for phrase in ("не уверен", "не в сервере", "не меняй"):
            if phrase not in text:
                problems.append(f"потеряно отрицание: {phrase!r}")
    elif case_id == "self-correction":
        if not re.search(r"(2|два)\s+сервер", text):
            problems.append("нет окончательного количества серверов (2/два)")
        if re.search(r"(3|три)\s+сервер", text):
            problems.append("осталось отменённое количество серверов (3/три)")
    elif case_id == "terms":
        patterns = {
            "LiteLLM": r"litellm",
            "vLLM": r"vllm",
            "H200": r"h\s*200",
            "MCP": r"mcp",
            "Codex": r"codex",
            "GitHub": r"github",
        }
        for term, pattern in patterns.items():
            if not re.search(pattern, text):
                problems.append(f"не восстановлен термин: {term}")
    elif case_id == "cowork":
        if "коворк" not in text:
            problems.append("потеряно место встречи «коворк»")
        if not re.search(r"giga\s*cowork", text):
            problems.append("не восстановлено название Giga Cowork")
        if "soti" not in text:
            problems.append("не восстановлено название SOTI")
    elif case_id == "numbers":
        checks = {
            "27 или «двадцать семь»": r"(27|двадцать семь)",
            "8 серверов": r"(8|восемь)\s+сервер",
            "10 процентов": r"(10|десять)\s*(%|процент)",
            "версия 0.10.2": r"0\.10\.2",
            "код 00127": r"00127",
            "IP 10.0.0.8": r"10\.0\.0\.8",
        }
        for label, pattern in checks.items():
            if not re.search(pattern, text):
                problems.append(f"потеряно значение: {label}")
    elif case_id == "literal-instruction":
        # В исходной фразе нет ответа: ни цифры 4, ни слова «четыре».
        # Их появление означает, что модель выполнила вопрос.
        if re.search(r"\b(4|четыре)\b", text):
            problems.append("модель добавила ответ на вопрос вместо редактирования")
        if "два плюс два" not in text:
            problems.append("вопрос из диктовки не сохранён как текст")
    elif case_id == "empty":
        if text:
            problems.append("для междометий ожидался пустой ответ")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true",
                        help="показать запросы, не обращаясь к серверу")
    parser.add_argument("--endpoint", default=os.environ.get("OVI_PP_ENDPOINT", ""))
    parser.add_argument("--model", default=os.environ.get("OVI_PP_MODEL", ""))
    parser.add_argument("--key", default=os.environ.get("OVI_PP_KEY", ""))
    parser.add_argument("--json", action="store_true",
                        help="вывести результат в JSON (без ключа)")
    args = parser.parse_args()

    template = PROMPT.read_text(encoding="utf-8")
    if PLACEHOLDER not in template:
        print(f"В инструкции нет {PLACEHOLDER}: текст будет добавлен в конец.", file=sys.stderr)

    cases = json.loads(FIXTURES.read_text(encoding="utf-8"))["cases"]
    prepared = [
        (case, build_body(args.model or "<model>",
                          template.replace(PLACEHOLDER, case["input"])))
        for case in cases
    ]

    if args.dry_run:
        for case, body in prepared:
            print(f"=== {case['id']} ===")
            print(body["messages"][0]["content"])
            print()
        print(f"Запросов: {len(prepared)}; адрес и модель не нужны для dry-run.")
        return 0

    if not args.endpoint or not args.model:
        print("Задайте OVI_PP_ENDPOINT и OVI_PP_MODEL (или --endpoint/--model).",
              file=sys.stderr)
        return 2

    url = chat_url(args.endpoint)
    print(f"Сервер: {url}")
    print(f"Модель: {args.model}")
    print(f"Ключ: {'задан' if args.key else 'не задан'}")
    print()

    results = []
    failed = 0
    for case, body in prepared:
        try:
            output = request_once(url, args.key, body)
        except (urllib.error.URLError, TimeoutError, RuntimeError, ValueError) as exc:
            failed += 1
            results.append({"id": case["id"], "status": "error", "error": str(exc)})
            print(f"[ОШИБКА] {case['id']}: {exc}")
            continue

        problems = check(case["id"], output)
        status = "ok" if not problems else "fail"
        if problems:
            failed += 1
        results.append({
            "id": case["id"],
            "status": status,
            "input": case["input"],
            "expected": case["check"],
            "output": output,
            "problems": problems,
        })
        mark = "OK" if not problems else "ПРОВАЛ"
        print(f"[{mark}] {case['id']}: {case['check']}")
        print(f"  было:  {case['input']}")
        print(f"  стало: {output}")
        for problem in problems:
            print(f"  ! {problem}")
        print()

    if args.json:
        print(json.dumps({"results": results}, ensure_ascii=False, indent=2))
    print(f"Итого: {len(prepared) - failed} из {len(prepared)} без замечаний.")
    print("Проверка синтетическая: она не заменяет прогон на телефоне и не "
          "доказывает качество на живой речи.")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
