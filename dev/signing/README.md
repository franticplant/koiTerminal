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
only in the GitHub `release` environment, and take their pin from CI
(`KOI_CERT_SHA256`), so each devd trusts exactly the key it was built with.
