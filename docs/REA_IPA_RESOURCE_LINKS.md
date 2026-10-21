# REA cross-archive brush resources: verification protocol

The pinned reference IPA contains 394 brush containers and 534 primary/secondary brush settings components. The prior [REA evidence](REA_BRUSH_COMPATIBILITY.md) identifies **297 distinct bundled shape paths (534 component references)** and **234 distinct bundled grain paths (504 component references)**. Most of these assets are not embedded in the `.brush` containers. Actual availability inside the full IPA's resource bundles **is still unknown**.

The new [`rea_ipa_resource_links.py`](../.github/scripts/rea_ipa_resource_links.py) joins these reference paths to the full ZIP's central-directory filenames without extracting texture bytes, running the reference app or attempting decryption. It requires both the hash-pinned `compound-structures.json` and the **original full IPA**, refusing changed source hashes.

Three distinct results have explicit meanings: `unique-path-candidate` (one literal path-suffix match), `ambiguous-path-candidates` (more than one), and `no-loose-file-candidate` (none). A missing loose filename **does not** establish an absent resource: Apple's `Assets.car` and other opaque resource packages may contain it. None of the outcomes establishes pixel fidelity or observed native loading.

To run on an authorized local copy:

```bash
python .github/scripts/rea_ipa_resource_links.py compound-structures.json reference.ipa --out resource-links.json
python -m unittest discover -s .github/scripts -p 'test_rea_ipa_resource_links.py'
```

The resolver bounds archive member counts and declared expansion sizes, detects traversal, ZIP encryption and case-insensitive duplicate names, counts compound-brush components precisely, and stores **paths and aggregate counts only**, with at most 25 candidate paths per reference. It never checks proprietary image bytes into the repository.

**Verification boundary:** unit tests are synthetic. Full-IPA results require a completed artifact inspection or access to the 777 MB file and cannot be asserted from the existing brush-only REA corpus.
