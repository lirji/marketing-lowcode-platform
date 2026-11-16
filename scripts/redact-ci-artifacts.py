#!/usr/bin/env python3
"""Remove ephemeral configuration secrets before uploading CI diagnostics."""

import os
from pathlib import Path


def redact(root: Path, env_files: list[Path], environ: dict[str, str]) -> None:
    secret_values = set()
    configuration = dict(environ)
    for path in env_files:
        if path.is_file():
            for line in path.read_text().splitlines():
                if "=" in line and not line.startswith("#"):
                    key, value = line.split("=", 1)
                    configuration[key] = value.strip().strip('"\'')
    for key, value in configuration.items():
        if any(part in key for part in ("PASSWORD", "SECRET", "TOKEN", "PRIVATE_KEY")) and value:
            secret_values.add(value)
    # Prefer longer values so a short password cannot leave a token suffix visible.
    for path in root.rglob("*"):
        if path.is_file():
            content = path.read_text(errors="replace")
            for secret in sorted(secret_values, key=len, reverse=True):
                content = content.replace(secret, "[REDACTED]")
            path.write_text(content)


if __name__ == "__main__":
    redact(Path("artifacts"), [Path(".env"), Path(os.environ["DEV_INFRA_ROOT"]) / ".env"], dict(os.environ))
