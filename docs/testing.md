# Testing

Qortium's default Maven test run should stay deterministic and should not depend on a desktop display, public network services, funded wallets, or local live-chain data. Tests that need those resources are still useful, but they are opt-in integration checks.

Run the deterministic tests with:

```bash
mvn test -DskipJUnitTests=false
```

## Opt-In Checks

- `-Dqortium.runLongMempowTests=true`
  Runs the long MemoryPoW compute benchmarks. The default suite keeps fast compute and known-nonce verification coverage instead.
- `-Dtest.awt.headless=false`
  Allows GUI display tests to open the splash frame and system tray when a desktop display is available. Headless Maven runs still exercise the splash no-op path.
- `-Dqortium.runLiveBootstrapChecks=true`
  Checks configured bootstrap hosts with live HTTP requests. Use `-Dqortium.liveBootstrapHosts=https://host-one,https://host-two` to override settings.
- `-Dqortium.runLiveElectrumXTests=true`
  Runs public ElectrumX server checks. The default suite uses mock ElectrumX responses for deterministic protocol coverage.
- `-Dqortium.runLiveCrosschainTests=true`
  Runs live crosschain checks that depend on public networks and fixture data. Default crosschain tests should prefer mock providers.
- `-Dqortium.runLiveRepositoryIntegrityChecks=true`
  Scans the repository configured by `settings.json` for reduced-name integrity issues. This intentionally targets live local data and is skipped by default.
