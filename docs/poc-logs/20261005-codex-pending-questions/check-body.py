"""Check the exact blank-body symptom on a 904x572 rear screenshot."""
import sys
from PIL import Image

for path in sys.argv[1:]:
    image = Image.open(path).convert("RGB")
    # Camera band, title and edge glow excluded; white/grey glyph pixels only.
    crop = image.crop((296, 110, 820, 490))
    pixels = crop.get_flattened_data() if hasattr(crop, "get_flattened_data") else crop.getdata()
    glyphs = sum(r > 110 and abs(r - g) < 12 and abs(g - b) < 12 for r, g, b in pixels)
    print(f"{path}: body_glyph_pixels={glyphs}")
    if glyphs < 100:
        raise SystemExit("FAIL: pending question body disappeared")
