# CLIP image-embedding sidecar — see docs/ITEM_RECOGNITION.md.
FROM python:3.12-slim

ENV PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/opt/models \
    CLIP_ARCH=ViT-B-32 \
    CLIP_PRETRAINED=laion2b_s34b_b79k

WORKDIR /app

# CPU-only torch wheels (the default index pulls ~2 GB of CUDA libraries).
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.5.1 torchvision==0.20.1
COPY requirements.txt .
RUN pip install -r requirements.txt

# Bake the model weights into the image so the container never downloads at runtime.
RUN python -c "import open_clip; open_clip.create_model_and_transforms('${CLIP_ARCH}', pretrained='${CLIP_PRETRAINED}')" \
    && chmod -R a+rX /opt/models
ENV HF_HUB_OFFLINE=1

COPY app.py .

RUN useradd --system --no-create-home embedder
USER embedder

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
