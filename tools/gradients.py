import numpy as np
from PIL import Image
W, H = 1920, 1080
P = {
 "01-nocturne": ["#070a1c", "#1d2470", "#6b2fd6", "#0f5f8c", "#c04fd0"],
 "02-ember":    ["#120607", "#5e1414", "#d4502c", "#f0a050", "#3a0d22"],
 "03-lagoon":   ["#02141c", "#0b4f5e", "#17a59a", "#7ad8c0", "#0a2a4a"],
 "04-dune":     ["#1c0f06", "#6e3a15", "#cf8838", "#f1c58a", "#3d1a0c"],
 "05-aurora":   ["#040814", "#0b3540", "#19b088", "#6a4fe0", "#08202e"],
 "06-rose":     ["#14070f", "#5a173a", "#cf3f72", "#f3a2bd", "#2a0c2c"],
 "07-graphite": ["#0a0b0d", "#24282e", "#4b525c", "#88909c", "#15171b"],
 "08-moss":     ["#0b1109", "#2c3b24", "#6f8247", "#c4bb84", "#1a2416"],
}
def rgb(h): return np.array([int(h[i:i+2], 16) for i in (1, 3, 5)], float) / 255
def lin(c): return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def srgb(c): return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.clip(c, 0, None) ** (1 / 2.4) - 0.055)
yy, xx = np.mgrid[0:H, 0:W].astype(float)
u, v = xx / W, yy / H * (H / W)
for n, (name, cols) in enumerate(P.items()):
    rng = np.random.default_rng(7 + n * 13)
    # low-frequency warp so the blobs flow instead of looking like circles
    wu = u + 0.06 * np.sin(2 * np.pi * (v * 1.3 + rng.random())) + 0.03 * np.sin(2 * np.pi * (u * 2.1 + rng.random()))
    wv = v + 0.05 * np.sin(2 * np.pi * (u * 1.1 + rng.random())) + 0.03 * np.sin(2 * np.pi * (v * 2.4 + rng.random()))
    img = np.broadcast_to(lin(rgb(cols[0])), (H, W, 3)).copy()
    # coloured light added on a deep base: two broad washes and two tighter glows
    spots = [(0.82, 0.06, 0.26, 0.50), (0.16, 0.50, 0.24, 0.40), (0.98, 0.44, 0.14, 0.95), (0.40, 0.02, 0.11, 0.55)]
    for (cx, cy, r, k), c in zip(spots, cols[1:]):
        cx += rng.uniform(-0.1, 0.1); cy += rng.uniform(-0.05, 0.05); r *= rng.uniform(0.85, 1.15)
        g = np.exp(-((wu - cx) ** 2 + (wv - cy) ** 2) / (2 * r * r))
        img = img + lin(rgb(c))[None, None, :] * (g * k)[..., None]
    img = (img / (1 + img * 0.35)) ** 1.12
    # gentle bottom darkening for the dock, then grain + dither against banding
    img *= (1 - 0.35 * np.clip((v / (H / W) - 0.55) / 0.45, 0, 1) ** 1.5)[..., None]
    out = srgb(img) * 255 + rng.normal(0, 2.2, (H, W, 1)) + rng.uniform(-0.5, 0.5, (H, W, 3))
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(f"{name}.jpg", quality=92, subsampling=0)
    print(name)
