# devd: the development control plane

koiTerminal's **devd** is a separate Android package that drives the app during
development. It is not part of a production setup and is never installed by
the app. The model is copied from Veil Terminal's devd: a typed API over
mutually authenticated TLS, a phone-side approval step, and an updater that
installs builds of the app and of devd itself.

```
laptop                            phone
koi-ctl  ── TLS 1.3, client cert ──▶  devd (io.github.franticplant.koiterminal.devd)
                                            │  Binder, signature permission
                                            ▼
                                      koiTerminal dev flavor
                                      (io.github.franticplant.koiterminal)
```

## Pieces

| Piece | Where | What |
|---|---|---|
| devapi | `AndroidStudioProject/devapi` | Method specs, parameter checking, scopes, `IDevHooks.aidl` |
| devd | `AndroidStudioProject/devd` | Listener, pairing, journal, metrics, updater, phone UI |
| devd-native | `AndroidStudioProject/devd-native` | TLS 1.3 + client certificates (rustls), listeners, framing |
| DevHooks | `AndroidStudioProject/app/src/dev` | Binder service in the app's **dev flavor only** |
| koi-ctl | `dev/ctl` | Laptop CLI: pair, call, logs, update, top, journal |

The app has `dev` and `prod` flavors. They share the package identity and the
signing key, so one installs over the other. `prod` does not contain the
DevHooks service, the dev API classes, or the `DEV_HOOKS` permission;
`scripts/check-release-apk.sh` verifies that.

`devd` and the app must be signed with the same key: the `DEV_HOOKS`
permission is `signature`-level, and `DevHooksService` additionally checks
that the caller is devd's package.

## Build and install

```sh
scripts/build all          # app dev + prod + devd, unsigned
scripts/sign dev           # dist/koi-<version>-dev.apk
scripts/sign devd          # dist/koi-devd-<version>.apk
scripts/ctl --install      # dist/koi-ctl and ~/.local/bin/koi-ctl
```

The local dev key is `AndroidStudioProject/keystore.jks`; its public digest is
`dev/signing/koi-dev-cert.sha256`. The private key and `dev/keystore.env` are
gitignored. Releases use the key from the `release` environment and must match
`KOI_CERT_SHA256` (or the committed pin, if it was updated to the release
certificate).

First install, once per phone:

```sh
adb install -r dist/koi-devd-<version>.apk
adb install -r dist/koi-<version>-dev.apk   # optional; devd can update it later
```

Open **koi devd** on the phone, allow notifications and local-network access,
and press **Allow installs from devd**. Press **Start listener**. It listens on
loopback (`127.0.0.1:7391`) and on any interface you tick, never on a wildcard.
It switches itself off after the chosen period.

To reach it from the laptop, forward a tailnet port to `127.0.0.1:7391` (for
example with Tailmesh's inbound listener) and keep the tailnet ACL limited to
the workstation.

## Pair and use

```sh
koi-ctl -a <phone>:7391 pair
```

`koi-ctl` prints a six-digit code; devd shows one on the phone. Approve only
if they match. The phone chooses the scopes and duration; they are a subset of
what the client requested.

```sh
koi-ctl -a <phone>:7391 status
koi-ctl -a <phone>:7391 methods
koi-ctl -a <phone>:7391 call vm.start
koi-ctl -a <phone>:7391 call vm.status
koi-ctl -a <phone>:7391 call installer.status
koi-ctl -a <phone>:7391 call image.info
koi-ctl -a <phone>:7391 call config.get
koi-ctl -a <phone>:7391 call ports.setForwarding port=8080 enabled=true
koi-ctl -a <phone>:7391 call app.restart
koi-ctl -a <phone>:7391 wait vm.status vm.name running --timeout 60
koi-ctl -a <phone>:7391 logs devd -f
koi-ctl -a <phone>:7391 logs app
koi-ctl -a <phone>:7391 update dist/koi-<version>-dev.apk
koi-ctl -a <phone>:7391 update dist/koi-devd-<version>.apk
```

`update` uploads the APK in chunks, verifies its SHA-256, package allowlist,
signer against `BuildConfig.PINNED_CERT_SHA256`, and refuses downgrades. The
first install of the app (or a first devd install) needs one tap on the phone.
If devd performed that first app install, it becomes the installer of record
and later updates are unattended. An app installed by adb first does not
retroactively grant devd ownership; Android may still ask for one tap. A devd
self-update ends the devd process and `RestoreReceiver` resumes the listener if
it was on.

## Phone controls

The main screen reports listener binds (including per-interface errors), port,
active connections and the server key fingerprint, and shows app/devd versions,
signers, installers and update owners. It can start the app, start/stop the VM,
ask for a graceful shutdown and refresh the interface list. Pairing requests
appear as a banner with a countdown, scope descriptions and read-only/update/
all presets. Logs and metrics have their own screens.

## Security model

- TLS 1.3 only, mutual certificates. devd's key is in Android Keystore; it
  never leaves the phone. The native listener requires a client certificate
  and checks the handshake signature; whether that certificate is paired is
  decided in Kotlin by its SHA-256.
- Pairings are created only by a person on the phone. There is no API method to
  list, add, revoke or reconfigure pairings, and none to change the listener.
- Every method has a scope (`read`, `read.content`, `operate`, `secrets`,
  `lifecycle`, `scenario`, `update`). Secret parameters additionally need
  `secrets`; values are never logged.
- The journal is hash-chained. `koi-ctl journal` compares the phone's chain
  against the anchor this laptop kept last time, so a truncated or rewritten
  journal is detected.
- `koi-ctl` pins devd's certificate at pairing. A changed certificate is
  refused unless you explicitly repin.

## Differences from Veil's devd

- The app being driven is koiTerminal, so the methods are VM-oriented
  (`vm.start`, `vm.stop`, `vm.shutdown`, `vm.resizeTty`, `installer.*`,
  `ports.list`) rather than SSH sessions.
- devd's default port is **7391** (Veil uses 7390), so both can run on one
  phone during the transition.
- The phone UI runs in devd's main process instead of a separate `:ui`
  process. The service/UI split in Veil keeps the listener's memory apart from
  the screen; koiTerminal can adopt it if the UI grows.
- Metrics currently sample devd and the koiTerminal app. Veil also samples its
  engine, VM host and per-session connections; those have no koi equivalent
  yet.
