"""Make the Adnin transparent logo and ICO from the supplied original artwork.

This is deterministic matting, not logo generation. The reference contour follows
the supplied artwork; original RGB pixels, dark metal faces, and colored edges
are retained. A black color key would incorrectly erase those dark metal faces.
Only Pillow is required. Source artwork is supplied explicitly and never read
from a user's configuration or bundled into the release.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
REFERENCE_SIZE = (1254, 1254)
REFERENCE_SHA256 = 'a0ce604189f87571e00c164bf382fca50cce5244222786e9e5342efe9dd7a455'
ICON_SIZES = (16, 24, 32, 48, 64, 128, 256)
SUPERSAMPLE = 4

# The source silhouette is traced at its native resolution. Cubic sections are
# connected in outline order; the right rim uses measured visible edge points.
# No foreground shape or color is invented or painted into the original image.
BEFORE_RIM = (
    ((213, 636), (318, 750), (525, 761), (643, 676)),
    ((643, 676), (516, 560), (374, 402), (284, 294)),
    ((284, 294), (382, 389), (490, 463), (585, 517)),
    ((585, 517), (521, 443), (444, 345), (378, 257)),
    ((378, 257), (494, 398), (606, 463), (750, 557)),
    ((750, 557), (768, 521), (779, 477), (772, 431)),
    ((772, 431), (769, 332), (728, 246), (681, 191)),
)
RIGHT_RIM = ((681, 191), (736, 250), (791, 300), (834, 350),
             (869, 400), (893, 450), (909, 500), (918, 550),
             (919, 600), (915, 640), (910, 660), (908, 666))
AFTER_RIM = (
    ((908, 666), (946, 720), (986, 819), (969, 873)),
    ((969, 873), (941, 825), (906, 801), (856, 809)),
    ((856, 809), (792, 813), (731, 864), (640, 868)),
    ((640, 868), (471, 883), (308, 774), (213, 636)),
)


def bezier(segment):
    for step in range(201):
        t = step / 200.0
        weights = ((1-t)**3, 3*(1-t)**2*t, 3*(1-t)*t*t, t**3)
        yield tuple(sum(weights[i]*segment[i][axis] for i in range(4))
                    for axis in (0, 1))


def rim():
    """Smooth x(y) interpolation along the original blue/gold right-hand rim."""
    points = RIGHT_RIM
    slopes = []
    for i in range(len(points)):
        a, b = points[max(0, i-1)], points[min(len(points)-1, i+1)]
        slopes.append((b[0]-a[0])/(b[1]-a[1]))
    for i in range(len(points)-1):
        a, b = points[i], points[i+1]
        height = b[1]-a[1]
        for step in range(101):
            t = step/100.0
            x = ((2*t**3-3*t*t+1)*a[0] + (t**3-2*t*t+t)*height*slopes[i]
                 + (-2*t**3+3*t*t)*b[0] + (t**3-t*t)*height*slopes[i+1])
            yield x, a[1]+t*height


def resize_rgba(image, size):
    # Premultiplied-alpha filtering prevents invisible black RGB pixels from
    # contaminating antialiased edge pixels during icon downsampling.
    return image.convert('RGBa').resize(size, Image.Resampling.LANCZOS).convert('RGBA')


def matte(source):
    outline = []
    for section in BEFORE_RIM:
        outline.extend(bezier(section))
    outline.extend(rim())
    for section in AFTER_RIM:
        outline.extend(bezier(section))
    mask = Image.new('L', tuple(side*SUPERSAMPLE for side in source.size))
    ImageDraw.Draw(mask).polygon([(x*SUPERSAMPLE, y*SUPERSAMPLE) for x, y in outline], fill=255)
    mask = mask.resize(source.size, Image.Resampling.LANCZOS)
    original = source.convert('RGBA')
    original.putalpha(mask)
    bounds = mask.getbbox()
    cropped = original.crop(bounds)
    # Remove only empty source padding; preserve the original logo proportions.
    side = max(cropped.size)+80
    centered = Image.new('RGBA', (side, side))
    centered.alpha_composite(cropped, ((side-cropped.width)//2, (side-cropped.height)//2))
    return resize_rgba(centered, (1024, 1024)), bounds


def background(size, kind):
    if kind == 'white':
        return Image.new('RGBA', size, (255, 255, 255, 255))
    if kind == 'dark':
        return Image.new('RGBA', size, (22, 26, 33, 255))
    image = Image.new('RGBA', size, (235, 237, 241, 255))
    draw = ImageDraw.Draw(image)
    for y in range(0, size[1], 24):
        for x in range(0, size[0], 24):
            if (x//24+y//24)%2:
                draw.rectangle((x, y, x+23, y+23), fill=(197, 202, 210, 255))
    return image


def write_qa(logo, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for kind in ('white', 'dark', 'checker'):
        full = background(logo.size, kind)
        full.alpha_composite(logo)
        full.convert('RGB').save(directory/('logo-'+kind+'.png'))
    panel = Image.new('RGB', (1536, 512))
    for index, kind in enumerate(('white', 'dark', 'checker')):
        bg = background((512, 512), kind)
        bg.alpha_composite(resize_rgba(logo, (512, 512)))
        panel.paste(bg.convert('RGB'), (512*index, 0))
    panel.save(directory/'logo-backgrounds.png')
    sizes = Image.new('RGB', (900, 440), (242, 244, 247))
    draw = ImageDraw.Draw(sizes)
    x = 18
    for size in ICON_SIZES:
        tile = max(size+18, 54)
        draw.text((x, 10), str(size)+' px'+(' (shown at 160)' if size == 256 else ''), fill=(22, 26, 33))
        icon = resize_rgba(logo, (size, size))
        for top, color in ((38, (255, 255, 255, 255)), (230, (22, 26, 33, 255))):
            bg = Image.new('RGBA', (tile, 176), color)
            # Large 256px member is shown separately in the full-size QA panels.
            shown = icon if size <= 128 else resize_rgba(logo, (160, 160))
            bg.alpha_composite(shown, ((tile-shown.width)//2, (176-shown.height)//2))
            sizes.paste(bg.convert('RGB'), (x, top))
        x += tile+10
    sizes.save(directory/'icon-sizes.png')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=ROOT/'src/injector/assets')
    parser.add_argument('--qa', type=Path)
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    if hashlib.sha256(source_bytes).hexdigest() != REFERENCE_SHA256:
        raise SystemExit('Source does not match the supplied artwork; this matte belongs to that exact image.')
    source = Image.open(io.BytesIO(source_bytes)).convert('RGB')
    if source.size != REFERENCE_SIZE:
        raise SystemExit('Expected the supplied 1254 x 1254 artwork; do not substitute another design.')
    logo, bounds = matte(source)
    args.output.mkdir(parents=True, exist_ok=True)
    png = args.output/'adnin.png'
    ico = args.output/'adnin.ico'
    logo.save(png, optimize=True)
    members = [resize_rgba(logo, (size, size)) for size in ICON_SIZES]
    # Explicit PNG-encoded ICO members preserve alpha at every requested size.
    encoded = []
    for member in members:
        stream = io.BytesIO()
        member.save(stream, format='PNG', optimize=True)
        encoded.append(stream.getvalue())
    cursor = 6+16*len(members)
    header = bytearray(struct.pack('<HHH', 0, 1, len(members)))
    for size, data in zip(ICON_SIZES, encoded):
        header.extend(struct.pack('<BBBBHHII', size if size < 256 else 0,
                                  size if size < 256 else 0, 0, 0, 1, 32, len(data), cursor))
        cursor += len(data)
    ico.write_bytes(header+b''.join(encoded))
    with Image.open(ico) as saved:
        if saved.ico.sizes() != {(size, size) for size in ICON_SIZES}:
            raise RuntimeError('ICO is missing a required member')
        for size in ICON_SIZES:
            member = saved.ico.getimage((size, size)).convert('RGBA')
            if member.getchannel('A').getextrema() != (0, 255):
                raise RuntimeError('ICO member lost transparent or opaque pixels')
    alpha = logo.getchannel('A')
    if alpha.getextrema() != (0, 255) or any(alpha.getpixel(point) for point in ((0, 0), (1023, 0), (0, 1023), (1023, 1023))):
        raise RuntimeError('Transparent logo bounds are invalid')
    if args.qa:
        write_qa(logo, args.qa)
        (args.qa/'asset-report.json').write_text(json.dumps({
            'sourceSha256': hashlib.sha256(source_bytes).hexdigest(),
            'sourceSize': list(source.size), 'sourceMatteBounds': list(bounds),
            'outputPngSize': list(logo.size), 'icoSizes': list(ICON_SIZES),
            'method': 'Supersampled source-contour matte; original RGB retained; premultiplied-alpha resizing',
            'pngSha256': hashlib.sha256(png.read_bytes()).hexdigest(),
            'icoSha256': hashlib.sha256(ico.read_bytes()).hexdigest(),
        }, indent=2)+'\n', encoding='utf8')
    print('Built transparent 1024px PNG and ICO sizes '+', '.join(map(str, ICON_SIZES)))


if __name__ == '__main__':
    main()
