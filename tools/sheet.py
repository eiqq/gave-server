"""사진 여러 장을 한 장으로: python -X utf8 tools/sheet.py <폴더> <출력.jpg> [가로칸=3] [한 칸 폭=640]"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

d = Path(sys.argv[1]); out = Path(sys.argv[2])
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 3
w = int(sys.argv[4]) if len(sys.argv) > 4 else 640
files = sorted(f for f in d.glob("*.png"))
if len(sys.argv) > 5:
    keep = sys.argv[5].split(",")
    files = [f for f in files if any(f.stem.startswith(k) for k in keep)]
ims = []
for f in files:
    im = Image.open(f).convert("RGB")
    h = int(im.height * w / im.width)
    ims.append((f.stem, im.resize((w, h))))
h = max(i.height for _, i in ims)
rows = (len(ims) + cols - 1) // cols
sheet = Image.new("RGB", (cols * w, rows * h), (20, 20, 20))
dr = ImageDraw.Draw(sheet)
for k, (name, im) in enumerate(ims):
    x, y = (k % cols) * w, (k // cols) * h
    sheet.paste(im, (x, y))
    dr.rectangle([x, y, x + 8 * len(name) + 6, y + 14], fill=(0, 0, 0))
    dr.text((x + 3, y + 1), name, fill=(255, 255, 0))
sheet.save(out, quality=85)
print(out, sheet.size)
