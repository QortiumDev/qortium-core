# Testing

Qortium's default Maven test run should stay deterministic and should not depend on a desktop display, public network services, funded wallets, or local live-chain data. Tests that need those resources are still useful, but they are opt-in integration checks.

Run the deterministic tests with:

```bash
mvn test -DskipJUnitTests=false
```

Default coverage should prefer local stand-ins for external systems. Bootstrap tests
exercise HTTP HEAD handling with a loopback server, and ElectrumX tests exercise
protocol parsing with mock RPC responses. Public hosts remain integration checks.

## Coverage Reports

Generate a local JaCoCo coverage report with:

```bash
mvn clean test jacoco:report jacoco:check -Pcoverage -DskipJUnitTests=false
```

The HTML report is written to `target/site/jacoco/index.html`, with machine-readable
XML and CSV output in the same directory. The coverage profile enforces intentionally
low bundle-level baselines so coverage cannot regress silently: 30% instruction
coverage, 20% branch coverage, and 30% line coverage. These thresholds are starting
points and should be ratcheted upward after broader default coverage is added. The
coverage profile excludes the performance-sensitive MemoryPoW implementation from
instrumentation because JaCoCo bytecode instrumentation makes nonce computation too
slow for full-suite coverage runs.

The default suite also contains a hygiene test that fails if new `@Ignore`
annotations are added under `src/test/java`. Prefer deterministic coverage for
normal tests, or use `Assume.assumeTrue(...)` behind an explicit opt-in property
for live, display-backed, funded-wallet, or long-running checks.

## Opt-In Checks

- `-Dqortium.runLongMempowTests=true`
  Runs the long MemoryPoW compute benchmarks. The default suite keeps fast compute and known-nonce verification coverage instead.
- `-Dqortium.runLongTrustNetworkTests=true`
  Runs larger synthetic account trust network derivation and rating-churn benchmarks. The default suite keeps smaller deterministic scale and churn sanity tests.
- `-Dqortium.runGuiDisplayTests=true -Dtest.awt.headless=false`
  Allows GUI display tests to open the splash frame and system tray when a desktop display is available. Headless Maven runs still exercise the splash no-op path.
  A manual `GUI display tests` GitHub Actions workflow runs the same checks under `xvfb`.
- `-Dqortium.runLiveBootstrapChecks=true`
  Checks configured bootstrap hosts with live HTTP requests. Use `-Dqortium.liveBootstrapHosts=https://host-one,https://host-two` to override settings. Explicit live runs fail if no bootstrap hosts are configured.
- `-Dqortium.runLiveElectrumXTests=true`
  Runs public ElectrumX server checks. The default suite uses mock ElectrumX responses for deterministic protocol coverage. Explicit live infrastructure checks prefer Bitcoin TEST4 servers, while legacy funded-address fixture checks still use Bitcoin TEST3 fixtures.
- `-Dqortium.runLiveCrosschainTests=true`
  Runs live crosschain checks that depend on public networks and fixture data. Default crosschain tests prefer mock providers, including deterministic HTLC fixtures for BTC-like chains. Live fixture checks fail when the explicitly requested fixture data is unavailable.
- `-Dqortium.runLiveRepositoryIntegrityChecks=true`
  Scans the repository configured by `settings.json` for reduced-name integrity issues. This intentionally targets live local data and is skipped by default.

## Example Commands

```bash
# Fast deterministic test run
mvn test -DskipJUnitTests=false

# Long MemoryPoW benchmarks
mvn test -DskipJUnitTests=false -Dqortium.runLongMempowTests=true -Dtest=MemoryPoWTests

# Long trust network derivation and churn benchmarks
mvn test -DskipJUnitTests=false -Dqortium.runLongTrustNetworkTests=true -Dtest=AccountTrustScaleTests

# Display-backed GUI checks
mvn test -DskipJUnitTests=false -Dqortium.runGuiDisplayTests=true -Dtest.awt.headless=false -Dtest=GuiTests

# Display-backed GUI checks under xvfb, matching the manual GitHub Actions workflow
xvfb-run -a mvn test -DskipJUnitTests=false -Dqortium.runGuiDisplayTests=true -Dtest.awt.headless=false -Dtest=GuiTests

# Live bootstrap host checks
mvn test -DskipJUnitTests=false -Dqortium.runLiveBootstrapChecks=true -Dtest=BootstrapTests

# Live ElectrumX server checks
mvn test -DskipJUnitTests=false -Dqortium.runLiveElectrumXTests=true -Dtest=ElectrumXTests

# Live crosschain checks
mvn test -DskipJUnitTests=false -Dqortium.runLiveCrosschainTests=true -Dtest=BitcoinyTests,HtlcTests,PirateChainTests

# Live local repository integrity scan
mvn test -DskipJUnitTests=false -Dqortium.runLiveRepositoryIntegrityChecks=true -Dtest=IntegrityTests
```
