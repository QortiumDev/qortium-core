# Local ARRR native acceptance pin

This branch is for local integrated acceptance and is not a general release.
The native bundle is built from reviewed Stashi source commit
`6b19fb18cd949ada5c8aeeebcd412cfb2448eed0` using all five JNI artifacts from
CI run `35755835448`. Its exact tag, URL, size and checksum are recorded in
`tools/pirate-unified-artifact.properties` and the loader constants.

The operator must explicitly set `pirateChainWalletQdnSignature` to the
immutable transaction signature of the matching test bundle. Existing official
bundle data must not be overwritten. Official settings defaults, preview
profiles and migration rules are unchanged. The native cache is isolated by
transaction signature.

After testing, use a coordinated supported Core/native release or restore the
previous Core jar and signature override. Stop Core before changing either.
Preserve the wallet storage; do not delete or reset a wallet to test this build.

The production partial-history changes are reviewed separately from this
experimental pin. Upstream Stashi PR 60 remains draft pending owner acceptance.
