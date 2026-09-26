"""Cheap lexical sanity check for edited Java files while no JVM may be started.

Not a compiler: it only strips comments and literals and verifies the brace nesting returns to zero. A pass
here means nothing about type correctness; it just catches a truncated or mis-pasted edit early.
"""
import sys
from pathlib import Path

BACKSLASH = chr(92)
QUOTE = '"'
APOSTROPHE = "'"


def depth(text):
    level = 0
    maximum = 0
    index = 0
    size = len(text)
    in_string = in_char = in_line = in_block = escaped = False
    while index < size:
        char = text[index]
        if in_line:
            if char == '\n':
                in_line = False
        elif in_block:
            if char == '*' and index + 1 < size and text[index + 1] == '/':
                in_block = False
                index += 1
        elif in_string:
            if escaped:
                escaped = False
            elif char == BACKSLASH:
                escaped = True
            elif char == QUOTE:
                in_string = False
        elif in_char:
            if escaped:
                escaped = False
            elif char == BACKSLASH:
                escaped = True
            elif char == APOSTROPHE:
                in_char = False
        else:
            if char == '/' and index + 1 < size and text[index + 1] == '/':
                in_line = True
                index += 1
            elif char == '/' and index + 1 < size and text[index + 1] == '*':
                in_block = True
                index += 1
            elif char == QUOTE:
                in_string = True
            elif char == APOSTROPHE:
                in_char = True
            elif char == '{':
                level += 1
                maximum = max(maximum, level)
            elif char == '}':
                level -= 1
        index += 1
    return level, maximum


def main(paths):
    failures = 0
    for name in paths:
        final, maximum = depth(Path(name).read_text(encoding='utf-8'))
        status = 'ok' if final == 0 else 'UNBALANCED'
        if final != 0:
            failures += 1
        print(f'{status:11} final={final} maxNesting={maximum}  {name}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
