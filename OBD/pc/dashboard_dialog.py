"""Compose a centered terminal dialog over a live Rich renderable."""
from rich.segment import Segment
from rich.style import Style


class DashboardDialog:
    def __init__(self, background, menu):
        self.background = background
        self.menu = menu

    def __rich_console__(self, console, options):
        width = options.max_width
        height = options.height or console.height
        if width < 1 or height < 1:
            return
        # Crop by terminal cells, not Python characters (Unicode may be double width).
        screen = console.render_lines(self.background, options.update(width=width, height=height))
        screen = [list(Segment.apply_style(row, post_style=Style(dim=True))) for row in screen]
        dialog_width = min(74, max(1, width - 8))
        dialog_height = min(self.menu.preferred_height(), max(1, height - 4))
        left = (width - dialog_width) // 2
        top = (height - dialog_height) // 2
        dialog = self.menu.render(console, dialog_width, dialog_height)
        foreground = console.render_lines(dialog, options.update(width=dialog_width, height=dialog_height))
        for y, row in enumerate(foreground, top):
            before, _, after = Segment.divide(screen[y], [left, left + dialog_width, width])
            screen[y] = before + row + after
        for y, row in enumerate(screen):
            yield from row
            if y < height - 1:
                yield Segment.line()
