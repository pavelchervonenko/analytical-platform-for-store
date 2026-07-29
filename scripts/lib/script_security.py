#!/usr/bin/env python3

"""Dependency-free security helpers for the repository's operator scripts."""

from __future__ import annotations

import ast
import ipaddress
import os
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import urlsplit, urlunsplit


MAX_DOTENV_BYTES = 64 * 1024
MAX_URL_LENGTH = 2_048
MAX_RENDER_LIMIT = 64 * 1024
VARIABLE_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


class InputError(ValueError):
    """Safe validation failure whose message contains no secret value."""


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def parse_value(raw_value: str, line_number: int) -> str:
    value = raw_value.strip()
    if not value:
        return ""
    if value[0] not in ("'", '"'):
        parsed = value
    else:
        try:
            parsed = ast.literal_eval(value)
        except (SyntaxError, ValueError) as exception:
            raise InputError(
                f"invalid quoted dotenv value at line {line_number}"
            ) from exception
        if not isinstance(parsed, str):
            raise InputError(
                f"dotenv value at line {line_number} must be a string"
            )
    if any(character in parsed for character in ("\0", "\r", "\n")):
        raise InputError(
            f"dotenv value at line {line_number} contains a forbidden control character"
        )
    return parsed


def parse_dotenv(path: Path, allowed_names: list[str]) -> dict[str, str]:
    try:
        stat = path.stat()
    except OSError as exception:
        raise InputError(f"cannot read environment file: {path}") from exception
    if not path.is_file():
        raise InputError(f"environment path is not a regular file: {path}")
    if stat.st_size > MAX_DOTENV_BYTES:
        raise InputError(
            f"environment file exceeds {MAX_DOTENV_BYTES} bytes: {path}"
        )

    try:
        content = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exception:
        raise InputError(
            f"environment file is not readable UTF-8: {path}"
        ) from exception
    if "\0" in content:
        raise InputError("environment file contains a NUL byte")

    parsed: dict[str, str] = {}
    seen: set[str] = set()
    for line_number, source_line in enumerate(content.splitlines(), start=1):
        line = source_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        name, separator, raw_value = line.partition("=")
        name = name.strip()
        if not separator or not VARIABLE_NAME.fullmatch(name):
            raise InputError(f"invalid dotenv assignment at line {line_number}")
        if name in seen:
            raise InputError(f"duplicate dotenv variable {name}")
        seen.add(name)
        value = parse_value(raw_value, line_number)
        if name in allowed_names:
            parsed[name] = value
    return parsed


def write_null_delimited(values: dict[str, str], names: list[str]) -> None:
    for name in names:
        if name not in values:
            continue
        os.write(sys.stdout.fileno(), name.encode("ascii") + b"\0")
        os.write(sys.stdout.fileno(), values[name].encode("utf-8") + b"\0")
    os.write(sys.stdout.fileno(), b"__STRICT_DOTENV_COMPLETE__\0")
    os.write(sys.stdout.fileno(), b"true\0")


def normalized_hostname(hostname: str) -> str:
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        try:
            normalized = hostname.encode("idna").decode("ascii").lower()
        except UnicodeError as exception:
            raise InputError("base URL contains an invalid hostname") from exception
        hostname_pattern = re.compile(
            r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
            r"(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"
        )
        if len(normalized) > 253 or not hostname_pattern.fullmatch(normalized):
            raise InputError("base URL contains an invalid hostname")
        return normalized
    if "%" in hostname:
        raise InputError("base URL must not contain an IPv6 zone identifier")
    return address.compressed


def normalize_base_url(raw_url: str, policy: str) -> str:
    if (
        not raw_url
        or raw_url != raw_url.strip()
        or len(raw_url) > MAX_URL_LENGTH
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in raw_url)
    ):
        raise InputError("base URL is empty, overlong, or contains whitespace/control characters")
    try:
        parsed = urlsplit(raw_url)
        port = parsed.port
    except ValueError as exception:
        raise InputError("base URL has an invalid authority or port") from exception

    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise InputError("base URL must use HTTP or HTTPS")
    if policy == "https-only" and scheme != "https":
        raise InputError("base URL must use HTTPS")
    if policy not in {"https-only", "https-or-loopback-http"}:
        raise InputError("unknown URL validation policy")
    if parsed.username is not None or parsed.password is not None:
        raise InputError("base URL must not contain userinfo")
    if parsed.query or parsed.fragment:
        raise InputError("base URL must not contain query or fragment components")
    if parsed.path not in {"", "/"}:
        raise InputError("base URL must not contain a path")
    if not parsed.hostname:
        raise InputError("base URL must contain a hostname")
    if "%" in parsed.netloc or "\\" in parsed.netloc:
        raise InputError("base URL authority contains forbidden encoding or separators")

    hostname = normalized_hostname(parsed.hostname)
    if scheme == "http" and hostname not in {"localhost", "127.0.0.1"}:
        raise InputError("HTTP is allowed only for exact localhost or 127.0.0.1")

    host_for_url = f"[{hostname}]" if ":" in hostname else hostname
    authority = host_for_url if port is None else f"{host_for_url}:{port}"
    return urlunsplit((scheme, authority, "", "", ""))


def render_response(path: Path, limit: int) -> None:
    if limit < 1 or limit > MAX_RENDER_LIMIT:
        raise InputError(f"response output limit must be 1-{MAX_RENDER_LIMIT} bytes")
    try:
        with path.open("rb") as response:
            content = response.read(limit + 1)
    except OSError as exception:
        raise InputError(f"cannot read response file: {path}") from exception

    truncated = len(content) > limit
    text = content[:limit].decode("utf-8", errors="replace")
    safe = "".join(
        character
        if character in {"\n", "\t"} or unicodedata.category(character) != "Cc"
        else f"\\u{ord(character):04x}"
        for character in text
    )
    sys.stdout.write(safe)
    if safe and not safe.endswith("\n"):
        sys.stdout.write("\n")
    if truncated:
        sys.stdout.write(f"[response truncated after {limit} bytes]\n")


def main(arguments: list[str]) -> None:
    if len(arguments) < 2:
        fail("expected a helper command")
    command = arguments[1]
    try:
        if command == "parse-dotenv" and len(arguments) >= 4:
            names = arguments[3:]
            if any(not VARIABLE_NAME.fullmatch(name) for name in names):
                raise InputError("invalid allowlisted variable name")
            write_null_delimited(parse_dotenv(Path(arguments[2]), names), names)
        elif command == "validate-base-url" and len(arguments) == 4:
            print(normalize_base_url(arguments[3], arguments[2]))
        elif command == "render-response" and len(arguments) == 4:
            render_response(Path(arguments[2]), int(arguments[3]))
        else:
            raise InputError("invalid helper command or arguments")
    except (InputError, ValueError) as exception:
        fail(str(exception))


if __name__ == "__main__":
    main(sys.argv)
