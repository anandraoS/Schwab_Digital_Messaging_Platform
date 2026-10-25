"""Builds Schwab_Digital_Messaging_Platform.zip at the repository root. Run: python -I scripts/make-zip.py

Excludes build/dependency output and previous archives. Never follows symlinks. Prints file count and size."""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_NAME = "Schwab_Digital_Messaging_Platform.zip"
OUT_PATH = os.path.join(ROOT, OUT_NAME)
EXCLUDE_DIRS = {"out", "node_modules", "target", ".angular", ".git", ".work", ".run", "dist", "__pycache__", ".idea", ".vscode"}
EXCLUDE_SUFFIXES = (".zip", ".pid", ".log", ".iml")
EXCLUDE_FILES = {".env"}  # real local env file must never be shipped; .env.example is kept


def should_skip_file(name: str) -> bool:
    return name in EXCLUDE_FILES or name.endswith(EXCLUDE_SUFFIXES)


def main() -> int:
    count = 0
    tmp_path = OUT_PATH + ".tmp"
    with zipfile.ZipFile(tmp_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for dirpath, dirnames, filenames in os.walk(ROOT, followlinks=False):
            dirnames[:] = sorted(d for d in dirnames if d not in EXCLUDE_DIRS)
            for fn in sorted(filenames):
                if should_skip_file(fn):
                    continue
                full = os.path.join(dirpath, fn)
                if os.path.islink(full):
                    continue
                arc = os.path.join("Schwab_Digital_Messaging_Platform", os.path.relpath(full, ROOT)).replace(os.sep, "/")
                zf.write(full, arc)
                count += 1
    os.replace(tmp_path, OUT_PATH)
    size_mb = os.path.getsize(OUT_PATH) / (1024 * 1024)
    print(f"wrote {OUT_PATH}: {count} files, {size_mb:.2f} MiB")
    with zipfile.ZipFile(OUT_PATH) as zf:
        bad = zf.testzip()
        names = zf.namelist()
        leaked = [n for n in names if "/node_modules/" in n or "/target/" in n or "/.git/" in n or n.endswith(".zip")]
        print(f"zip integrity: {'OK' if bad is None else 'BAD ' + bad}; excluded-dir leaks: {len(leaked)}")
        return 0 if bad is None and not leaked else 1


if __name__ == "__main__":
    sys.exit(main())
