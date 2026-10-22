# vendor-pages-check

The seller's phone page (`/replies/`) and the delegate's desk (`/delegate/`) are plain files vendor-server serves
(`vendor-server/src/main/resources/static`); no unit test drives them. This check opens each page in headless
Chromium against a mocked vendor server and asserts what the seller or the delegate is shown and may press —
holds after approval, their reasons, and the pre-save stop (`docs/vendor_server_contract_v1.md` §4.2).

```bash
node tools/vendor-pages-check/check.mjs
```

Exit 0 iff every case passes. Uses the Playwright already installed under `collector/node_modules`. Nothing
leaves the machine: any request other than the page's own files and the mocked `/v1` calls fails the run.
Run it after changing either page.
