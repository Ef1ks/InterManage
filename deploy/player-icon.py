"""Składa player.ico (zaokrąglony kwadrat z białym trójkątem startu) bez bibliotek: python deploy/player-icon.py."""
import struct
import zlib
from pathlib import Path

BACKGROUND = (0xC8, 0x10, 0x2E)
FOREGROUND = (0xFF, 0xFF, 0xFF)
SIZES = (16, 20, 24, 32, 40, 48, 64, 256)
SAMPLES = 4


def inside_square(x, y):
    """Punkt w kwadracie 0-1 o zaokrąglonych rogach."""
    radius = 0.2
    dx = max(radius - x, x - (1 - radius), 0)
    dy = max(radius - y, y - (1 - radius), 0)
    return dx * dx + dy * dy <= radius * radius


def inside_triangle(x, y):
    """Trójkąt startu, lekko w prawo, żeby optycznie stał na środku."""
    left, right, top, bottom = 0.36, 0.76, 0.24, 0.76
    if x < left or x > right:
        return False
    half = (bottom - top) / 2 * (right - x) / (right - left)
    return abs(y - 0.5) <= half


def pixels(size):
    """Wiersze od góry, RGBA, z wygładzaniem krawędzi."""
    rows = []
    for row in range(size):
        line = bytearray()
        for column in range(size):
            square = mark = 0
            for sub_y in range(SAMPLES):
                for sub_x in range(SAMPLES):
                    x = (column + (sub_x + 0.5) / SAMPLES) / size
                    y = (row + (sub_y + 0.5) / SAMPLES) / size
                    if inside_square(x, y):
                        square += 1
                        if inside_triangle(x, y):
                            mark += 1
            share = mark / square if square else 0
            color = [round(BACKGROUND[i] + (FOREGROUND[i] - BACKGROUND[i]) * share) for i in range(3)]
            line += bytes((*color, round(255 * square / SAMPLES ** 2)))
        rows.append(bytes(line))
    return rows


def dib(size):
    """Małe rozmiary jako bitmapa: tak czyta je pasek zadań i kompilator ikony."""
    rows = pixels(size)
    bgra = b''.join(bytes(row[i + j] for i in range(0, len(row), 4) for j in (2, 1, 0, 3)) for row in reversed(rows))
    mask = bytes(((size + 31) // 32 * 4) * size)
    header = struct.pack('<IiiHHIIiiII', 40, size, size * 2, 1, 32, 0, len(bgra) + len(mask), 0, 0, 0, 0)
    return header + bgra + mask


def png(size):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    raw = b''.join(b'\0' + row for row in pixels(size))
    header = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


images = [(size, png(size) if size == 256 else dib(size)) for size in SIZES]
offset = 6 + 16 * len(images)
directory = struct.pack('<HHH', 0, 1, len(images))
for size, data in images:
    side = 0 if size == 256 else size
    directory += struct.pack('<BBBBHHII', side, side, 0, 0, 1, 32, len(data), offset)
    offset += len(data)

target = Path(__file__).resolve().parent.parent / 'player.ico'
target.write_bytes(directory + b''.join(data for _, data in images))
print(target, target.stat().st_size)
