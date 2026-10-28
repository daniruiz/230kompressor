"""UI language only; protocol bytes, measurements and log schemas stay unchanged."""
import argparse
import ctypes
from functools import wraps
import locale
import os
import sys


def language_for(tag):
    """All Spanish regions use Spanish; unsupported or missing locales use English."""
    language = str(tag or '').split('.')[0].split('@')[0].replace('-', '_').split('_')[0].lower()
    return 'es' if language == 'es' else 'en'


def windows_ui_language():
    # Windows display language is independent of the regional number/date format.
    try:
        language_id = ctypes.windll.kernel32.GetUserDefaultUILanguage()
        return locale.windows_locale.get(language_id)
    except (AttributeError, OSError):
        return None


def detect_language():
    if sys.platform == 'win32':
        tag = windows_ui_language()
        if tag:
            return language_for(tag)
    # Respect the message language of the terminal on Unix, including C/POSIX.
    for name in ('LC_ALL', 'LC_MESSAGES', 'LANG'):
        tag = os.environ.get(name)
        if tag:
            if tag.split('.')[0] in ('C', 'POSIX'):
                return 'en'
            preferred = os.environ.get('LANGUAGE', '').split(':')[0]
            return language_for(preferred or tag)
    preferred = os.environ.get('LANGUAGE', '').split(':')[0]
    if preferred:
        return language_for(preferred)
    try:
        return language_for(locale.getlocale()[0])
    except (ValueError, TypeError):
        return 'en'


LANGUAGE = detect_language()


def tr(spanish, english):
    return spanish if LANGUAGE == 'es' else english


def rpm_text(value):
    return f'{value:,}'.replace(',', '.') if LANGUAGE == 'es' else f'{value:,}'


def mode_text(mode):
    return {'iniciando': tr('INICIANDO', 'STARTING'),
            'grouped': tr('AGRUPADO', 'GROUPED'),
            'individual': 'INDIVIDUAL'}.get(mode, mode.upper())


def profile_text(profile):
    return {'STN P3 adaptador': tr('STN P3 adaptador', 'STN adapter P3'),
            'STN rápido P4=0': tr('STN rápido P4=0', 'STN fast P4=0')}.get(profile, profile)


def localized_arguments(function):
    """Localize argparse's own help/errors during parsing, before workers start."""
    messages = {
        'usage: ': 'uso: ', 'positional arguments': 'argumentos posicionales',
        'options': 'opciones', 'optional arguments': 'argumentos opcionales',
        'show this help message and exit': 'mostrar esta ayuda y salir',
        'unrecognized arguments: %s': 'argumentos no reconocidos: %s',
        'the following arguments are required: %s': 'se requieren estos argumentos: %s',
        'argument %(argument_name)s: %(message)s': 'argumento %(argument_name)s: %(message)s',
        'expected one argument': 'se esperaba un argumento',
        "invalid %(type)s value: %(value)r": "valor %(type)s inválido: %(value)r",
        'invalid choice: %(value)r (choose from %(choices)s)':
            'opción inválida: %(value)r (elige entre %(choices)s)',
    }
    @wraps(function)
    def wrapped(*args, **kwargs):
        original = argparse._
        argparse._ = lambda message: tr(messages.get(message, message), message)
        try:
            return function(*args, **kwargs)
        finally:
            argparse._ = original
    return wrapped
