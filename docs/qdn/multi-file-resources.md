# Multi-file QDN resources, entry points, and private resources

A client implementation guide (Home / Hub / Q-Apps) for working with multi-file QDN
resources, the optional **entry point**, default-file resolution, and how **private
(encrypted)** resources are published and consumed. Core-side mechanics are summarised;
the parts that are the **client's responsibility** are called out explicitly. For the
encryption format itself, see [encrypted-data-envelope.md](encrypted-data-envelope.md).

## Multi-file vs single-file services

Many services accept a **directory** of files (Core zips it on publish); others take a
**single file**. Multi-file services as of v1:

```
AUTO_UPDATE, ARBITRARY_DATA, FILES, WEBSITE, GIT_REPOSITORY, IMAGE_GALLERY,
VIDEO, AUDIO, PODCAST, BLOG, DOCUMENT, APP, GIF_REPOSITORY, GAME, DATABASE, SNAPSHOT
```

Everything else (IMAGE, THUMBNAIL, JSON, the various media/chat/record types) is
single-file.

**Don't hardcode this.** Query `GET /arbitrary/services` for the authoritative per-service
flags — `singleFile`, `supportsDirectories`, `requiresValidation`, `maxSize`, `private`,
`requiresEncryption`, `value`, `id` — and drive your UI from that.

**Publishing is deterministic:** Core sorts the file list and writes the zip with a fixed
entry order and timestamp, so re-publishing identical content yields byte-identical data
(stable hashes, reproducible resources).

## Entry point (primary file)

A multi-file resource may declare an optional **`entryPoint`** — the primary file to
serve when the resource is fetched **without naming a specific file**.

- **Set it at publish time.** The `entryPoint` must be one of the resource's files, or
  the publish is rejected.
- **It is stored in the resource metadata** and returned by
  `GET /arbitrary/metadata/{service}/{name}/{identifier}` as `entryPoint`, so a client
  can discover it without downloading the resource.
- **It must be re-supplied on every update** (like `title`/`description`/etc.) — if you
  omit it when updating, it is cleared.

## Default-file resolution (fetch without a path)

When a resource is fetched **without a `filepath`**, Core resolves which file to serve:

1. exactly one file → serve it;
2. else if the metadata declares an `entryPoint` that exists → serve it;
3. else → reject with *"filepath is required for resources containing more than one file"*.

**Client responsibility (consumer handling):** a consumer fetching a multi-file resource
must either pass `?filepath=...`, or rely on the `entryPoint`, and otherwise handle the
"filepath required" error gracefully. Multi-file media (e.g. a video published with sidecar
files) is not usable end-to-end until consuming apps/players handle this case.

## Rendering and SPA routing (rendered services)

For rendered resources — `APP`, or **any** service that declares an `entryPoint` — Core
forwards unhandled **route-like** requests (a path with no file extension, or a browser
navigation sending `Accept: text/html`) to the `entryPoint` (else the conventional index
file), so a client-side router works. **Missing assets** (`.css`, `.js`, images) still
return 404, so static sites stay correct. A plain `WEBSITE` with no `entryPoint` is
unaffected and serves files as-is.

## Private (encrypted) resources

Private resources are encrypted **entirely client-side** — Core never holds keys and never
sees plaintext. The audience modes (**publisher / accounts / group**) and the envelope
format are specified in [encrypted-data-envelope.md](encrypted-data-envelope.md).

**The encrypted-archive model.** A private resource is published as **one client-encrypted
blob**:

1. the client **zips** the directory,
2. **encrypts** the whole archive into an envelope,
3. publishes it to the matching `*_PRIVATE` service (e.g. `APP_PRIVATE`, `IMAGE_GALLERY_PRIVATE`,
   `FILES_PRIVATE`, `WEBSITE_PRIVATE`, `BLOG_PRIVATE`, `GIT_REPOSITORY_PRIVATE`,
   `DATABASE_PRIVATE`, `SNAPSHOT_PRIVATE`).

At the Core level a private resource is therefore a **single, opaque encrypted file**. This
hides the file list, names, and sizes (more private), and Core enforces that private content
actually carries the envelope (plaintext is rejected at publish).

**Paths for private resources are a client concern, after decryption.** Core cannot index
into the encrypted blob, so there is **no `?filepath=` support** for private resources. To
use one:

1. fetch the **whole** encrypted blob,
2. **decrypt** it (client holds the key),
3. **unzip** it,
4. resolve paths yourself — your own routing, **or** re-POST the decrypted plaintext zip to
   the render/preview endpoint to reuse Core's `entryPoint` / SPA-fallback / path resolution
   on the now-plaintext content.

> Per-file private resources (individually-encrypted files with Core-level `filepath`
> support) are **not** implemented. They would leak the file list and are only worth it for
> streaming large private media — a possible future feature, not part of v1.

## Client / app responsibilities (not Core)

These are deliberately left to the apps; Core provides only the storage/validation/metadata
primitives above:

1. **Upload UX.** Offering a "main file + extra files" picker, patch-to-add-files onto an
   existing resource, or letting the user choose the `entryPoint` when uploading a zip — all
   client UX. Core only stores the `entryPoint` value you supply.
2. **Consumer handling of multi-file fetch.** As above: pass a `filepath`, or use the
   `entryPoint`, and handle the "filepath required" error. Until consuming apps/players do
   this, multi-file media isn't usable end-to-end.
3. **Sidecar association.** Pairing a subtitle to its video (`movie.mp4` ↔ `movie.srt`),
   cover art to audio, etc. is a **player concern**, resolved client-side by basename
   matching. Core does not pair sidecars — it only stores the files (and the `entryPoint`
   identifying the primary one).
