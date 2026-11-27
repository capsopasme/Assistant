#!/usr/bin/env bash
# Fetches the PP-OCRv6 small models (official ONNX exports from PaddlePaddle on Hugging Face) into
# app/src/main/assets/ocr, writes the recognizer's dictionary as keys.txt, and smoke-tests the
# pair with the same pre/post-processing as the app (BGR order, normalization, CTC layout), so a
# model update that changes any of that fails the build instead of shipping garbage.
#
#   .github/scripts/fetch-ocr-models.sh            (needs curl, python3; pip packages installed on demand)
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=app/src/main/assets/ocr
HF=https://huggingface.co/PaddlePaddle
mkdir -p "$OUT"

fetch() { # url dest
  [ -s "$2" ] && return 0
  curl -fSL --retry 4 --retry-delay 5 -o "$2.part" "$1"
  mv "$2.part" "$2"
}
fetch "$HF/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx" "$OUT/det.onnx"
fetch "$HF/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx" "$OUT/rec.onnx"
fetch "$HF/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml" "$OUT/rec.yml.tmp"

python3 -m pip install --quiet --disable-pip-version-check pyyaml onnxruntime numpy pillow >/dev/null

python3 - "$OUT" <<'PY'
import sys, os, yaml, numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
out = sys.argv[1]
y = yaml.safe_load(open(os.path.join(out, "rec.yml.tmp"), encoding="utf-8"))
chars = y["PostProcess"]["character_dict"]
assert all("\n" not in c for c in chars), "dictionary entry with a newline"
with open(os.path.join(out, "keys.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(chars) + "\n")
os.remove(os.path.join(out, "rec.yml.tmp"))

det = ort.InferenceSession(os.path.join(out, "det.onnx"), providers=["CPUExecutionProvider"])
rec = ort.InferenceSession(os.path.join(out, "rec.onnx"), providers=["CPUExecutionProvider"])
classes = rec.get_outputs()[0].shape[-1]
print(f"dict {len(chars)} entries, rec classes {classes}")
assert classes in (len(chars) + 1, len(chars) + 2), "dictionary does not match the recognizer"

# a test image: dark text on white, like a screen
img = Image.new("RGB", (720, 200), "white")
font = None
for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf"]:
    if os.path.exists(p): font = ImageFont.truetype(p, 44)
if font is None: font = ImageFont.load_default(size=44)
ImageDraw.Draw(img).text((40, 70), "Hello OCR 2026", fill=(20, 20, 20), font=font)
rgb = np.asarray(img).astype(np.float32)
bgr = rgb[:, :, ::-1]

# detector: BGR, ImageNet mean/std (as OcrEngine.detect)
h, w = bgr.shape[:2]
dh, dw = (h + 31) // 32 * 32, (w + 31) // 32 * 32
pad = np.full((dh, dw, 3), 255, np.float32); pad[:h, :w] = bgr
x = (pad / 255.0 - np.array([0.485, 0.456, 0.406], np.float32)) / np.array([0.229, 0.224, 0.225], np.float32)
prob = det.run(None, {det.get_inputs()[0].name: x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0, 0]
ys, xs = np.where(prob > 0.2)
assert len(xs) > 0, "detector found nothing"
l, t, r, b = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
d = (r - l) * (b - t) * 1.4 / (2 * ((r - l) + (b - t)))
l, t, r, b = int(max(0, l - d)), int(max(0, t - d)), int(min(w, r + d)), int(min(h, b + d))
print(f"det box {l},{t},{r},{b}  mean prob {prob[prob > 0.2].mean():.2f}")

# recognizer: height 48, (v/255-0.5)/0.5, BGR, zero padding to >= 320 (as OcrEngine.recBatch)
crop = img.crop((l, t, r, b))
rw = int(np.ceil(48 * crop.width / crop.height))
W = max(320, rw)
line = np.asarray(crop.resize((rw, 48), Image.BILINEAR)).astype(np.float32)[:, :, ::-1]
inp = np.zeros((1, 3, 48, W), np.float32)
inp[0, :, :, :rw] = (line / 255.0 - 0.5).transpose(2, 0, 1) / 0.5
o = rec.run(None, {rec.get_inputs()[0].name: inp})[0][0]
idx = o.argmax(-1)
text, prev = "", -1
for i in idx:
    if i != 0 and i != prev:
        text += chars[i - 1] if i - 1 < len(chars) else " "
    prev = i
print(f"recognized: {text!r}  (row sum {o[0].sum():.3f}: {'probabilities' if abs(o[0].sum()-1) < 0.02 else 'logits'})")
assert "OCR" in text and "2026" in text, "smoke test failed: preprocessing or dictionary mismatch"
PY
ls -l "$OUT"
sha256sum "$OUT"/*
