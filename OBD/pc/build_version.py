"""Build metadata packaged by build.bat; source runs have no compilation date."""
from pathlib import Path
import re
import sys


def build_date():
    if not getattr(sys, 'frozen', False):
        return None
    try:
        value = Path(__file__).with_name('build_date.txt').read_text(encoding='ascii').strip()
    except (OSError, UnicodeError):
        return None
    return value if re.fullmatch(r'[0-9]{8}', value) else None


BUILD_DATE = build_date()
APP_VERSION = BUILD_DATE or 'development'
