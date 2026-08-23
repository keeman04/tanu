import os
import tempfile
from pathlib import Path

_root = Path(tempfile.gettempdir()) / "mai-backend-tests"
_root.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("MAI_AUTH_DB", str(_root / "auth.sqlite3"))
os.environ.setdefault("MAI_JOB_DB", str(_root / "jobs.sqlite3"))
os.environ.setdefault("MAI_JOB_ROOT", str(_root / "meetings"))
