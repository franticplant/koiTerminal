# Signing pins

`koi-dev-cert.pem` and `koi-dev-cert.sha256` are the public certificate and
digest of the local development key. The private key is
`AndroidStudioProject/keystore.jks` (gitignored); `dev/keystore.env` holds its
passwords (also gitignored).

devd compiles the digest into `BuildConfig.PINNED_CERT_SHA256` and refuses to
install an APK signed by anything else.

This pin stays the **local development** certificate: local builds sign with
the local key, and their devd must accept them. Do not replace it with a
release certificate. Release builds are signed only in CI, with keys that live
only in the GitHub `release` environment.

`release-prod-cert.sha256` and `release-dev-cert.sha256` are the public digests
of the two release keys: prod signs the `prod` app, dev signs the `dev` app and
devd. A release build of devd pins `release-dev-cert.sha256` (`scripts/build`
does this when `KOI_RELEASE_TAG` is set), so each devd trusts exactly the key it
was built for. The release workflow refuses to sign if these files and the
environment's `KOI_PROD_CERT_SHA256` / `KOI_DEV_CERT_SHA256` disagree.
