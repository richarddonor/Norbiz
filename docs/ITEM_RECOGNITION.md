# Item Photo Recognition

Users can identify an item from a photo with the camera button (or Alt+P) next to the item picker in
forms (`ItemSelect` / `ItemRecognitionDialog` in Norbiz-Web, see its `docs/DROPDOWNS.md`). The Items
list's "Photo Search" menu starts the reindex job.

## How it works

1. **Reference images.** Each item's image (`items.image_path`, uploaded via `POST /items/{id}/image`) is
   sent to the **embedder** sidecar (`embedder/`, a CLIP ViT-B-32 model on CPU), which returns a
   512-dimensional, L2-normalised embedding. It's stored in `item_image_embeddings` (pgvector `vector(512)`)
   together with the item, company, image path and model id.
2. **Query.** `POST /lookups/items/recognize` embeds the user's photo the same way and returns the
   company's items whose reference embedding is closest by cosine similarity. Only embeddings from the
   same model are compared, and each item is returned once (its closest image).

The embedder is stateless and only ever receives image bytes. It never sees item, company or user data,
and it needs no network access at runtime because the weights are baked into its image.

## API

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /lookups/items/recognize` (multipart `file`) | `@lookupAccess.can(ITEM)`, the same as the item dropdown | `companyId` param or `X-Company-Id` header, `tag`, `activeOnly` (default true), `limit` 1–20 (default 5). Returns `AppResponse<List<ItemRecognitionMatchResponse>>`: `{item: <ItemLookupResponse>, similarity: 0..1, imagePath}`, best first. This is a ranked suggestion list, so it's capped by `limit` rather than paginated. Not cached. |
| `POST /items/{id}/image` | `UPDATE_ITEM` + company access | Indexes the new image right after saving it. The response's `recognitionIndexed: false` means indexing failed (e.g. the embedder is down), but the upload itself succeeded. |
| `POST /items/image-recognition/reindex` | `REINDEX_ITEM_IMAGES` + company access (seeded on SUPER_ADMIN and SYSTEM_ADMIN only; it re-embeds a whole catalog, so it is an admin action, not part of item editing) | Background job (202, `{queued}`) that indexes the company's images. By default it only does images that aren't indexed yet; `force=true` redoes all of them. Use it once after first deploying this feature, and after changing the model. One job runs per company at a time. Progress goes to the log and the `norbiz.item_recognition.reindex` span. |

Errors: a missing or undecodable image returns 400. When recognition is disabled or the embedder is unreachable, the endpoint returns **503 with `code: "ITEM_RECOGNITION_UNAVAILABLE"`**, and the frontend should fall back to text search.

## Configuration

| Property / env var | Default | Effect |
|---|---|---|
| `app.item-recognition.enabled` / `ITEM_RECOGNITION_ENABLED` | `true` | Master switch. When off, the endpoint returns 503 and uploads skip indexing. |
| `app.item-recognition.embedder-url` / `ITEM_EMBEDDER_URL` | `http://localhost:8000` | The compose files set `http://embedder:8000`. |
| `app.item-recognition.min-similarity` / `ITEM_RECOGNITION_MIN_SIMILARITY` | `0.5` | Candidates below this are dropped. As a rough guide, CLIP scores unrelated products at about 0.4–0.6 and photos of the same product typically above 0.8. Tune it on real photos. |
| `app.item-recognition.connect-timeout` / `read-timeout` | `2s` / `15s` | Timeouts for embedder calls. |
| `EMBEDDER_THREADS` (prod `.env`) | `2` | Torch CPU threads in the sidecar. |

The sidecar needs about 1 GB of RAM once loaded, and its image is about 1.5 GB (CPU-only torch).

## Rules and gotchas

- **`item_image_embeddings` is a derived index, not a JPA entity.** It is written through
  `ItemImageEmbeddingRepository` (JdbcTemplate), so Hibernate never creates or validates it. That means
  it needs `init.sql` plus a migration even in dev, where `ddl-auto=update` won't create it. It isn't
  `Auditable` and isn't cached. Rows cascade-delete with their item.
- **Company scoping happens in SQL** (`WHERE e.company_id = ?`), after `assertCompanyAccess`. Never
  search across companies.
- **The model and the column dimension are coupled.** `vector(512)` matches ViT-B-32. Switching to a
  model of another size needs a migration that changes the column, followed by `reindex?force=true`.
  Switching to another 512-d model only needs the reindex, because stale-model rows are ignored.
- **The embedder call never runs inside a DB transaction.** `ItemRecognitionService` isn't
  `@Transactional`, so a slow embedder doesn't hold a connection.
- **Search is an exact scan** over the company's rows, using the `(company_id, model)` index. That's
  fine up to tens of thousands of images per company. Beyond that, add
  `CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)` and `SET hnsw.iterative_scan = relaxed_order`
  so the company filter doesn't starve results.
- **Accuracy depends on the reference photos.** A clean product shot is matched well by a similar phone
  photo. Visually near-identical variants (sizes, colours of the same packaging) won't separate reliably,
  so show the top few candidates and let the user pick.
- **The pgvector DB image** is built from source on the same `postgres:17-alpine` base (`Dockerfile.db`).
  Don't switch to the Debian-based `pgvector/pgvector` image under an existing volume: changing the libc
  changes collation and corrupts text indexes.

## Local setup

```bash
docker-compose build db embedder && docker-compose up -d db embedder
# Existing dev database: the db image now has pgvector, but the table must be created once:
docker-compose exec -T db psql -U norbiz -d norbiz < db/migrations/005_item_image_embeddings.sql
```

Then call `POST /items/image-recognition/reindex` once per company to index the existing images.
