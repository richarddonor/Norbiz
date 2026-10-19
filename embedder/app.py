"""Image embedding sidecar for Norbiz item photo recognition (docs/ITEM_RECOGNITION.md).

POST /embed   raw image bytes (any Content-Type) -> {"model", "dim", "embedding": [L2-normalised floats]}
GET  /health  -> {"status": "UP", "model"}

Stateless: it never sees item or company data, only pixels. The model weights are baked into the
image at build time, so it needs no network access at runtime.
"""
import io
import os

import open_clip
import torch
from fastapi import FastAPI, HTTPException, Request
from PIL import Image, ImageOps, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool

ARCH = os.environ.get("CLIP_ARCH", "ViT-B-32")
PRETRAINED = os.environ.get("CLIP_PRETRAINED", "laion2b_s34b_b79k")
MODEL_ID = f"{ARCH}/{PRETRAINED}"
MAX_BYTES = int(os.environ.get("EMBED_MAX_BYTES", str(10 * 1024 * 1024)))

# Decoding a tiny file into a gigapixel bitmap would exhaust memory; Pillow raises
# DecompressionBombError past twice this limit.
Image.MAX_IMAGE_PIXELS = 50_000_000
torch.set_num_threads(int(os.environ.get("TORCH_THREADS", "2")))

model, _, preprocess = open_clip.create_model_and_transforms(ARCH, pretrained=PRETRAINED)
model.eval()

app = FastAPI(title="norbiz-embedder")


def _embed(data: bytes) -> list[float]:
    try:
        image = Image.open(io.BytesIO(data))
        # Phone photos are often stored sideways with an EXIF rotation flag.
        image = ImageOps.exif_transpose(image).convert("RGB")
    except (UnidentifiedImageError, Image.DecompressionBombError, OSError) as ex:
        raise HTTPException(status_code=422, detail=f"Not a decodable image: {ex}") from ex
    with torch.inference_mode():
        vector = model.encode_image(preprocess(image).unsqueeze(0))
        vector = vector / vector.norm(dim=-1, keepdim=True)
    return vector[0].tolist()


@app.get("/health")
def health():
    return {"status": "UP", "model": MODEL_ID}


@app.post("/embed")
async def embed(request: Request):
    data = await request.body()
    if not data:
        raise HTTPException(status_code=400, detail="Empty request body")
    if len(data) > MAX_BYTES:
        raise HTTPException(status_code=413, detail=f"Image larger than {MAX_BYTES} bytes")
    # Inference is CPU-bound — keep it off the event loop.
    embedding = await run_in_threadpool(_embed, data)
    return {"model": MODEL_ID, "dim": len(embedding), "embedding": embedding}
