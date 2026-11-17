"""Last working ECU family; a hint, never an identification result."""
import json
import os
from pathlib import Path
import tempfile


class EcuPreferences:
    def __init__(self, path=None):
        if path is None:
            if os.name == 'nt':
                root = Path(os.environ.get('LOCALAPPDATA') or Path.home() / 'AppData' / 'Local')
            else:
                root = Path(os.environ.get('XDG_CONFIG_HOME') or Path.home() / '.config')
            path = root / 'MercedesOBD' / 'ecu-families.json'
        self.path = Path(path)

    def get(self):
        try:
            settings = json.loads(self.path.read_text(encoding='utf-8'))
            if isinstance(settings, dict):
                family = settings.get('last_ecu_family')
                if family in ('ME2.1', 'SIM4LE'):
                    return family
        except (OSError, ValueError):
            pass
        return None

    def remember(self, family):
        if family not in ('ME2.1', 'SIM4LE'):
            return
        if self.get() == family:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8',
                                             dir=self.path.parent, delete=False) as stream:
                temporary = Path(stream.name)
                # Replace the old per-adapter map; it has no reliable last-used order.
                json.dump({'last_ecu_family': family}, stream, indent=2)
                stream.write('\n')
            os.replace(temporary, self.path)
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()
