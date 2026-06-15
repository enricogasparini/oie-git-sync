# Contributing

`oie-git-sync` is published under the [Mozilla Public License 2.0](./LICENSE)
for use and transparency. It is written and maintained by a sole developer for
a specific healthcare integration context, and is **not open to external
contributions**.

There is no public issue tracker, and unsolicited pull requests are not
reviewed or accepted. The intended consumption model is **fork-first**: if you
need a change, fork the repository and maintain your own copy under the licence.
That is what the licence is for, and you are free to do so.

The rest of this document covers building the plugin from source, which is
useful if you are running or adapting your own fork.

## Building from source

### Prerequisites

- JDK 17 or later
- Apache Ant 1.10+
- A local clone of the OIE engine source at `../engine` (configurable in
  `build.properties`). The engine must be built first:

  ```bash
  cd ../engine/server
  ant -f mirth-build.xml -DdisableSigning=true -DdisableTests=true
  ```

### Build

```bash
ant fetch-libs       # Download JGit and dependencies (first time only)
ant fetch-tools      # Download Checkstyle + SpotBugs (first time only)
ant fetch-test-libs  # Download JUnit, Mockito, JaCoCo, google-java-format (first time only)
ant build            # Compile and produce dist/gitsync-<version>.zip
ant clean build      # Full rebuild
```

The full local gate is:

```bash
ant clean build test coverage-enforce checkstyle format-check spotbugs
```

It takes well under a minute.

### Install into a local OIE server

Extract `dist/gitsync-<version>.zip` into the OIE server's `extensions/`
directory and restart OIE. A new **Git Sync** tab will appear in Settings.

### Local test loop against a running OIE container

To test changes end-to-end through the OIE admin console (launched via
OpenWebStart) against one or more local OIE containers, use the scripts in
[`scripts/`](./scripts/):

```bash
cp scripts/dev-signing.properties.example scripts/dev-signing.properties
$EDITOR scripts/dev-signing.properties     # set container names and URLs
./scripts/dev-setup.sh                     # one-time per machine
./scripts/dev-deploy.sh                    # every rebuild
```

See [`docs/local-testing.md`](./docs/local-testing.md) for the full story,
including why self-built OIE installs need JAR signing, the Reflections 0.9.10
patch, and why OpenWebStart has to be pinned to JDK 17.

## Code style and tests

These are documented for anyone maintaining a fork:

- **Java 17**, `source="17" target="17" release="17"`.
- **google-java-format** for `src/` formatting (2-space indent, 100 cols).
  Run `ant format` to reformat and `ant format-check` to verify.
- **Checkstyle** (Google Java style, relaxed to 120 cols): `ant checkstyle`.
- **SpotBugs** for static bug analysis: `ant spotbugs`.
- **No emojis** in source files or log messages; **UK English** in user-facing
  messages and documentation.
- Prefer clarity over cleverness; follow the existing patterns in the module.
- Unit and integration tests live under `test/` (250+ tests). Run `ant test`,
  or `ant coverage-enforce` for the JaCoCo 80% line / 70% branch gate. Tests
  use **JUnit 5** with **Mockito** (inline mock maker), run against real
  temporary JGit repositories where needed, and never touch a live OIE
  instance or the network.

## Reporting security issues

Please do **not** disclose security vulnerabilities publicly. See
[`SECURITY.md`](./SECURITY.md) for the private reporting process.
