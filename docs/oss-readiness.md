# Open-source readiness

This ledger records what this fork must satisfy before it publishes binaries,
and where each item stands. It is engineering record-keeping, not legal advice.

## Posture

- Alpha, developer preview. No releases of this fork have been published yet.
- Planned release artifacts:
  - the `prod` app
  - the `dev` app
  - `devd`
  - `koi-ctl`

## Licences and attribution

- **Upstream:** koiTerminal by OutlawSanZhang, Apache-2.0 (`LICENCE`). It
  derives from the AOSP/GrapheneOS Linux Terminal and Virtualization sources,
  also Apache-2.0. `NOTICE` is kept as upstream ships it.
- **This fork's additions** (`devapi`, `devd`, `devd-native`, `dev/ctl`, the
  dev flavour, build and CI scripts) are under the same licence.
- **Third-party notices:**
  - Rust dependencies: `cargo-about` (`about.toml`, `about.hbs`), with an
    accepted-licence list in `about.toml`. Its comment refers to a
    `deny.toml` that is not in the repository yet; add one, or drop the
    reference.
  - Android dependencies: `AndroidStudioProject/notices.gradle`.
  - The generated notices must be reviewed for each release.
- **GPL components:** none are distributed by this fork today.
  - If a release ever ships U-Boot, a kernel or other GPL code, its release
    notes must name the exact source and commit, and that source must stay
    available.
  - Upstream's NixOS build script (GPL-3.0) stays in its separate repository,
    as upstream does.

## Identity

- **Package names:** the app is `io.github.franticplant.koiterminal` and the
  agent is `io.github.franticplant.koiterminal.devd`.
- **Name and relationship:** the product keeps the koiTerminal name. The
  repository is a GitHub fork of upstream, and the README states the
  relationship at the top.

## Release gates

| Gate | State |
|---|---|
| Secret scan of every commit this fork adds | Done before the first push. No secrets, keystores or key files committed |
| Releases only from CI, from protected `v*` tags, with the owner's approval on the `release` environment | Workflow in place. Repository rules to be configured |
| SBOM and SLSA build provenance (Sigstore) | In the release workflow |
| Release check: `prod` contains no dev pieces; devd checked separately | In CI and the release workflow |
| Separate release keys: one for `prod`, one for `dev` and `devd` releases. Neither exists outside the `release` environment. Each artifact's signer checked against its expected certificate | To do |
| `dev` flavour under its own application ID, so it installs beside `prod` | To do |
| Repeatable builds: two clean builds compare byte for byte | Done on the build host (2026-11-02): the unsigned dev, prod and devd APKs and koi-ctl were identical across two clean builds with empty caches. Matching across different machines is the reproduce job's task |
| Reproducible releases: a second CI runner rebuilds and compares against the signed release; a public verification script | To do |
| Release notes that name their inputs (upstream base; U-Boot and kernel sources if shipped) | To do. The `v0.1.0` draft must be rewritten before tagging |
| devd's safe defaults documented (listener off until started, loopback only, auto-off, scoped and expiring pairings) | Done (`README.md`, `docs/devd.md`) |
