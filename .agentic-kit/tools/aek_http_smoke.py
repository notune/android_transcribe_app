#!/usr/bin/env python3
"""HTTP smoke test for an approved test server (stdlib, GET/HEAD only)."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import datetime as dt
import json
import math
import os
from pathlib import Path
import statistics
import sys
import tempfile
import time
from urllib import error, parse, request


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def checked_url(base, endpoint, allow_remote=False):
    origin = parse.urlsplit(base)
    if (origin.scheme != "http" or not origin.netloc or origin.username is not None
            or origin.password is not None or origin.fragment or origin.path not in ("", "/")
            or origin.query):
        raise ValueError("Base URL must be an http origin without credentials, path or query")
    if not allow_remote and origin.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Only loopback is allowed by default; remote test hosts require --allow-remote")
    if (not endpoint.startswith("/") or endpoint.startswith("//") or "\r" in endpoint
            or "\n" in endpoint or "#" in endpoint):
        raise ValueError("Endpoints must be absolute paths on the same origin")
    parsed = parse.urlsplit(endpoint)
    if parsed.netloc or parsed.scheme or any(p == ".." for p in parsed.path.split("/")):
        raise ValueError("Invalid endpoint path")
    return parse.urlunsplit((origin.scheme, origin.netloc, parsed.path, parsed.query, ""))


def expected_statuses(values, endpoints):
    rules = {}
    for value in values:
        if "=" not in value:
            raise ValueError("Use --expect /path=200,401")
        endpoint, codes = value.rsplit("=", 1)
        if endpoint not in endpoints:
            raise ValueError("Unknown endpoint in --expect: " + endpoint)
        statuses = set()
        for code in codes.split(","):
            if not code.isdecimal() or not 100 <= int(code) <= 599:
                raise ValueError("Invalid expected HTTP status")
            statuses.add(int(code))
        rules[endpoint] = statuses
    return rules


def once(url, endpoint, phase, method, timeout, expected):
    start = time.monotonic()
    opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
    req = request.Request(url, method=method, headers={"User-Agent": "AEK-Smoke/1"})
    code, failure = None, None
    try:
        with opener.open(req, timeout=timeout) as resp:
            code = resp.status
    except error.HTTPError as exc:
        code = exc.code
        exc.close()
    except (error.URLError, OSError, TimeoutError) as exc:
        failure = type(exc).__name__
    elapsed = round((time.monotonic() - start) * 1000, 2)
    passed = (code in expected) if expected is not None else (code is not None and 200 <= code < 300)
    if failure is not None:
        passed = False
    return {"endpoint": endpoint, "phase": phase, "status": code,
            "elapsed_ms": elapsed, "error": failure, "passed": passed}


def run(base_url, endpoints, requests, concurrency, timeout, method, expectations=None, allow_remote=False):
    if not endpoints or not 1 <= requests <= 1000 or not 1 <= concurrency <= 32:
        raise ValueError("Require endpoints, requests 1..1000 and concurrency 1..32")
    if not 0 < timeout <= 30:
        raise ValueError("Timeout must be greater than zero and at most 30 seconds")
    urls = {e: checked_url(base_url, e, allow_remote=allow_remote) for e in endpoints}
    expectations = expected_statuses(expectations or [], endpoints)
    observations = []
    for endpoint in endpoints:
        url = urls[endpoint]
        expected = expectations.get(endpoint)
        first = once(url, endpoint, "sequential", method, timeout, expected)
        observations.append(first)
        if requests > 1:
            with ThreadPoolExecutor(max_workers=min(concurrency, requests - 1)) as pool:
                jobs = [pool.submit(once, url, endpoint, "concurrent", method, timeout, expected)
                        for _ in range(requests - 1)]
                for future in as_completed(jobs):
                    observations.append(future.result())
    delays = sorted(x["elapsed_ms"] for x in observations)
    percentile95 = delays[min(len(delays) - 1, math.ceil(len(delays) * .95) - 1)]
    failures = [x for x in observations if not x["passed"]]
    return {
        "schema_version": 1, "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "base_url": base_url, "method": method, "requests_per_endpoint": requests,
        "concurrency": concurrency, "endpoints": endpoints,
        "expectations": {k: sorted(v) for k, v in expectations.items()},
        "total": len(observations), "failed": len(failures),
        "latency_ms": {"median": round(statistics.median(delays), 2), "p95": percentile95},
        "results": observations, "success": len(failures) == 0,
        "limitations": "HTTP smoke does not prove security, data correctness or all concurrency behaviors.",
    }


def save_report(path, report):
    dest = Path(path)
    dest.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".aek-smoke-", dir=dest.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(report, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
        os.replace(temporary, dest)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description="Safe HTTP smoke test against an approved test server")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--endpoint", action="append", required=True)
    parser.add_argument("--requests", type=int, default=20)
    parser.add_argument("--concurrency", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--method", choices=("GET", "HEAD"), default="GET")
    parser.add_argument("--expect", action="append", default=[], help="Expected statuses: /path=200,401")
    parser.add_argument("--allow-remote", action="store_true", help="Requires explicit user authorization")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = run(args.base_url, args.endpoint, args.requests, args.concurrency,
                     args.timeout, args.method, args.expect, args.allow_remote)
        if args.output:
            save_report(args.output, report)
        print(json.dumps({"total": report["total"], "failed": report["failed"],
                          "success": report["success"], "p95_ms": report["latency_ms"]["p95"]}))
        return 0 if report["success"] else 2
    except (ValueError, OSError) as exc:
        print("AEK HTTP SMOKE ERROR:", exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
