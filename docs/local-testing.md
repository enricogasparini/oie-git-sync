# Local testing against a self-built OIE

**Audience:** contributors who are hacking on the gitsync plugin source and
want to test the server-side hooks and the Swing settings panel against a
locally running OIE instance through OpenWebStart.

**Not for:** end users installing gitsync into an already-running production
OIE. That path is plain: download the release ZIP, unzip into
`$OIE_HOME/extensions/`, restart. None of the workarounds on this page apply.

This document explains *why* the [`scripts/dev-setup.sh`](../scripts/dev-setup.sh)
and [`scripts/dev-deploy.sh`](../scripts/dev-deploy.sh) scripts do what they
do, so the ugly parts are honest rather than mysterious.

## The desired loop

```bash
# One-time setup (per machine)
cp scripts/dev-signing.properties.example scripts/dev-signing.properties
$EDITOR scripts/dev-signing.properties
./scripts/dev-setup.sh

# Every time you change code
./scripts/dev-deploy.sh

# Every time you want to verify the change in the admin console UI
javaws https://localhost:8443/webstart.jnlp
# -> log in as admin/admin, go to Settings -> Git Sync
```

## Why the setup is more than `ant build && docker cp`

Four independent obstacles sit between a freshly built gitsync JAR and a
working Swing settings panel. The dev-setup script handles each of them so
you don't have to debug them individually.

### Obstacle 1: OIE's admin console is delivered via OpenWebStart (JNLP)

When you open the admin console, it runs as a desktop Swing app delivered
over JNLP. OpenWebStart fetches `https://your-oie/webstart.jnlp`, then
downloads every JAR the JNLP references into a local cache, then launches a
JVM with those JARs on the classpath and `com.mirth.connect.client.ui.Mirth`
as the entry point.

OIE's JNLP declares `<all-permissions/>`. That means the app is asking for
unrestricted JVM access — file system, network, reflection, everything. The
Java Web Start security model says that any application asking for
`all-permissions` **must** have every one of its JARs signed by a certificate
that the user trusts.

### Obstacle 2: self-built OIE has unsigned JARs

If you built your OIE from source — which is what you're almost certainly
doing if you're developing a plugin against it — the OIE build defaults to
`-DdisableSigning=true` to skip the signing step. That means every JAR in
`/opt/oie/client-lib/` and `/opt/oie/extensions/*/` is unsigned.

Trying to open the admin console against a self-built OIE through
OpenWebStart therefore fails with:

```
Fatal: Application Error: Cannot grant permissions to unsigned jars.
Application requested security permissions, but jars are not signed.
```

**Fix:** `dev-setup.sh` generates a throwaway self-signed dev keystore and
runs `jarsigner` over every JAR in the container (223-ish of them in a
typical OIE install). It also imports the dev cert into OpenWebStart's
`trusted.certs` so OpenWebStart recognises our signature. Only needed once
per container image — `docker restart` preserves the signatures,
`docker compose up` from scratch does not.

You can skip this step entirely (`SKIP_BASE_SIGNING=1`) if you're running
against an official signed OIE release.

The keystore is gitignored and easy to lose — see
[If you lose the keystore](#if-you-lose-the-keystore) for the (automatic)
recovery path.

### Obstacle 3: OIE 4.5.2's bundled Reflections 0.9.10 is broken on JDK 17+

Once the signing obstacle is out of the way, OpenWebStart proceeds to start
the admin console. You see the login dialog. You type `admin / admin`. The
dialog says: *"There was an error connecting to the server at the specified
address. Please verify the server is up and running."*

That's a lie. Look at the javaws log (`~/.cache/icedtea-web/log/` or
`$JAVA_WS_LOG_DIR`) and you see:

```
java.lang.IllegalStateException: zip file closed
    at java.base/java.util.zip.ZipFile.ensureOpen(ZipFile.java:846)
    at java.base/java.util.zip.ZipFile.jarEntries(ZipFile.java:547)
    at org.reflections.vfs.ZipDir$1$1.<init>(ZipDir.java:27)
    at org.reflections.Reflections.scan(Reflections.java:240)
    ...
    at com.mirth.connect.client.core.Client.<init>(Client.java:193)
    at com.mirth.connect.client.ui.LoginPanel$8.doInBackground(LoginPanel.java:426)
```

`Client.<init>` uses
[Reflections 0.9.10](https://repo1.maven.org/maven2/org/reflections/reflections/0.9.10/)
(released 2015) to scan for annotation-based JAX-RS providers and servlet
interfaces at startup. The version of Reflections that OIE bundles has a
bug in its `ZipDir` iterator: it closes the `ZipFile` handle between
`iterator()` and `next()`, which blows up on JDK 17+'s stricter JarFile
validation. The error is thrown on a `SwingWorker` background thread, the
`SwingWorker` maps any exception out of `Client.<init>` to the generic
*"error connecting to the server"* dialog, and the login fails every time.

This is an **OIE upstream problem**, not a gitsync problem. It affects any
plugin, any developer, any admin console on JDK 17+ against OIE 4.5.2.

**Fix:** `dev-setup.sh` replaces `/opt/oie/client-lib/reflections-0.9.10.jar`
with the content of Reflections 0.9.12 (same JAR filename so the JNLP
reference still resolves), re-signs it with the dev cert, and pushes it back
into the container. 0.9.12 is API-compatible with 0.9.10 and has the
`ZipDir` bug fixed.

**Upstream tracking:** this should be reported to the OIE project as a
JDK 17+ compatibility bug and the Reflections dependency bumped. Once that
ships in an OIE release, the `patch-reflections` step becomes a no-op and
can be deleted from `dev-setup.sh`.

### Obstacle 4: OpenWebStart picks JDK 21 by default

OpenWebStart ships with a JVM manager that downloads and caches JREs on
demand. The first time you run it, it installs Adoptium 21 into
`~/.cache/icedtea-web/jvm-cache/`. When it sees OIE's JNLP declare
`<j2se version="1.9+"/>`, it matches that against "newest available JRE ≥ 9"
and picks the Adoptium 21 it just installed.

Even after the Reflections 0.9.10 → 0.9.12 patch, JDK 21 still trips related
issues elsewhere in OIE's class-scanning code paths — some parts of the
older client-lib assume JDK 17 semantics for `ClassLoader.getResource()`,
module access, and reflective invocations on `java.awt.Component`.

**Fix:** `dev-setup.sh` edits `~/.cache/icedtea-web/jvm-cache/cache.json`
to mark a locally installed Zulu 17 JDK as the only active runtime.
OpenWebStart then happily uses that for all subsequent launches. Get a
Zulu 17 JDK via SDKMAN (`sdk install java 17.0.18.fx-zulu`) or your
distro's packages.

## After setup: the daily loop

Once `dev-setup.sh` has run, every further iteration is just:

```bash
./scripts/dev-deploy.sh
javaws https://localhost:8443/webstart.jnlp
```

which takes ~15–30 seconds: ant build, sign plugin JARs, push into
container, restart container, purge OpenWebStart cache, print the relaunch
command. None of the obstacle-3 / obstacle-4 workarounds are re-run since
they're already in place.

If you're iterating on **server-side** code only and the container is still
running signed base JARs from a previous `dev-setup.sh` run, you don't need
the cache purge step — set `NO_CACHE_PURGE=1`. If you're iterating on
**client-side** code only, the container doesn't need to restart — set
`NO_RESTART=1`.

## Debugging a broken deploy

The most common failure modes after a `dev-deploy.sh` run:

- **`NoClassDefFoundError` for something in the gitsync package.** You've
  added a new class that's referenced by both the server JAR and the client
  JAR. Edit `build.xml` so the class lands in `gitsync-shared.jar`, not just
  the server JAR. OpenWebStart only ships the SHARED and CLIENT libraries to
  the admin console — the SERVER library is never on its classpath.
- **Admin console opens but the Settings → Git Sync tab shows a stacktrace.**
  Same root cause most of the time — look at the `Caused by:` chain for the
  missing class and check which JAR you packaged it into.
- **`Cannot grant permissions to unsigned jars`.** You probably made an
  unsigned release ZIP by running `ant clean build` directly instead of
  `dev-deploy.sh`. Run `dev-deploy.sh`, which re-signs.
- **`error connecting to the server` on login.** Look at the javaws log —
  if it's `zip file closed` again, the Reflections patch didn't stick. Most
  likely you recreated the container and lost the in-container changes. Run
  `dev-setup.sh` again (it's idempotent).
- **`Cannot grant permissions` / signature error after regenerating the
  keystore.** The base JARs and the plugin JARs are signed by different certs
  (mixed-signature classpath). Run `FORCE_RESIGN=1 ./scripts/dev-setup.sh` to
  re-sign every base JAR and refresh the trusted cert with the current key,
  then `./scripts/dev-deploy.sh`. See
  [If you lose the keystore](#if-you-lose-the-keystore).

## If you lose the keystore

`scripts/dev-keystore.jks` is **gitignored** (so your signing key and password
never reach Git), which means a fresh clone, a clean checkout, or a tidy-up
deletes it with no warning. Back it up if you want to keep the same signing
identity. Losing it is recoverable, but there is a subtlety worth understanding.

Re-running `dev-setup.sh` regenerates a self-signed keystore. It reuses the
same alias (`gitsync-dev`) but the new keystore holds a **different
certificate**. OpenWebStart grants all-permissions only when every JAR on the
admin console classpath is signed by a **trusted** cert, so after a regenerate
you must end up with the base OIE JARs, the gitsync plugin JARs, and the
OpenWebStart trust store all referring to the *new* cert. If the base JARs stay
signed by the old (now-lost) cert while the plugin JARs get the new one, the
admin console launch fails with a signature/permission error.

`dev-setup.sh` handles this for you: the base-signing and trust-import steps
compare by certificate **SHA-256 fingerprint**, not by alias name. A
regenerated keystore therefore fails the fingerprint match and the script
re-signs every base JAR and replaces the stale trust-store entry automatically.
You just re-run it:

```bash
./scripts/dev-setup.sh        # regenerates keystore, re-signs base JARs,
                              # refreshes the trusted cert on every container
./scripts/dev-deploy.sh       # rebuild + sign + deploy the plugin with the new key
```

`FORCE_RESIGN=1 ./scripts/dev-setup.sh` forces the re-sign and trust refresh
even when the fingerprints already match — useful if you suspect a partially
signed container or want to rotate the key deliberately. Re-signing every base
JAR across the containers takes a couple of minutes.

> Why this matters: an earlier version skipped a container whenever its JARs
> already carried the `gitsync-dev` **alias**, regardless of which cert was
> behind it. After a keystore loss that left the base JARs on the old cert and
> the plugin JARs on the new one — a mixed-signature classpath OpenWebStart
> quietly refuses. Keying the skip on the fingerprint fixes it.

## Reverting all of this

Everything the scripts touch is scoped to your user account and to the dev
containers. Nothing is system-wide, nothing needs sudo.

```bash
# Keystore + config
rm -f scripts/dev-keystore.jks scripts/dev-keystore.cer scripts/dev-signing.properties

# OpenWebStart config overrides
rm -rf ~/.cache/icedtea-web/cache ~/.cache/icedtea-web/jvm-cache/cache.json
# (the trust store entries can be cleaned via `itw-settings` GUI)

# Container-side changes disappear on container recreation:
docker compose down && docker compose up -d
```
