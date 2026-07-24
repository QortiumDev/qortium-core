# Peer maintenance policy

Core evaluates chain and data peer maintenance from one controller schedule:
the first pass is 120 seconds after startup and later passes are 90 seconds
apart. The network layers do not add private disconnection timers. Health and
capacity pruning remain separate from voluntary rotation.

## Chain peers

Each chain peer receives a randomized lifetime between
`minPeerConnectionTime` and `maxPeerConnectionTime`. A voluntary chain
rotation can remove at most one peer per maintenance pass. The peer must be
outbound, past its generated lifetime, not fixed, and not synchronizing.
Core preserves all voluntary candidates when either the total handshaked count
is at or below `minBlockchainPeers` or the outbound count is below
`minOutboundPeers`.

When several peers qualify, Core rotates the most overdue peer. Inbound peers
can still be removed by health, protocol, duplicate, or capacity policies, but
not by voluntary age rotation.

## Data peers

`maxDataPeerIdleTime` is a seconds value and defaults to 1800 (30 minutes). It
measures time since meaningful QDN payload or relay activity, not total
connection age. Routine file-list gossip does not refresh this deadline, so a
broadcast that touches every peer cannot prevent useful LRU rotation.

An outbound data peer is eligible only when it is past the idle threshold and
has no in-flight chunk request, pending relay, queued send, output in progress,
or active prefetch. Core preserves all voluntary candidates at or below
`minDataPeers`, or below `minOutboundPeers`, selects the least recently useful
eligible peer, and removes at most one per pass.

Elapsed connection age and meaningful-data idle time use the monotonic clock.
Wall/NTP timestamps remain available for API display. If NTP is temporarily
unavailable, monotonic voluntary-policy evaluation and stale outbound-failure
cleanup continue, while repository age pruning keeps its NTP requirement.

## Reconnect opportunity

A voluntarily rotated endpoint receives a ten-minute reconnect cooldown. Core
prefers another connectable endpoint during that window. This is a soft
preference: when no alternative exists, the rotated endpoint remains an
allowed fallback so a small or I2P-only node is not stranded.

## Setting migration

`maxDataPeerConnectionTime` is accepted for one compatibility release as a
deprecated alias for `maxDataPeerIdleTime`; its value now means idle time.
When both keys are present with different values, startup fails rather than
guessing. Writing `maxDataPeerIdleTime` through the settings API removes the
legacy key. Settings diagnostics report the effective idle time and whether it
came from the new key, the deprecated alias, or the default.

Startup also rejects non-positive chain minimums, chain maximums that are not
strictly greater than their minimums, and non-positive data idle values.
Previewnet's existing `maxPeerConnectionTime: 999999999` override is unchanged.

For local acceptance, a node must have at least `minOutboundPeers` outbound
connections before voluntary rotation can run. A participant with two
outbound data peers and the Previewnet target of eight therefore needs a
temporary test-only `minOutboundPeers` reduction; restore the normal setting
after the test.
