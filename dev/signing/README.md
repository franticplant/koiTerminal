# Signing pins

`koi-dev-cert.pem` and `koi-dev-cert.sha256` are the public certificate and
digest of the local development key. The private key is
`AndroidStudioProject/keystore.jks` (gitignored); `dev/keystore.env` holds its
passwords (also gitignored).

devd compiles the digest into `BuildConfig.PINNED_CERT_SHA256` and refuses to
install an APK signed by anything else. The release workflow uses the
certificate in the `release` environment and passes its digest as
`KOI_CERT_SHA256`; update this file to the release certificate before the
first public release so the committed default and the release key agree.
