# NetworkData capacity contract

`maxPeers` limits the chain `Network` only. `maxDataPeers` is the effective
startup capacity of the separate QDN/data overlay.

The data layer keeps at most `maxDataPeers` completed handshakes and one
additional provisional admission. The provisional slot is reserved before a
direct TCP accept, I2P forwarded setup, ordinary outbound dial, or forced QDN
dial performs meaningful socket or handshake work. It is released on every
failed path or consumed when the peer joins the connected list.

The extra slot is intentionally not a general overflow allowance. A candidate
must first complete the handshake so the node can identify a duplicate and
apply the deterministic connection-direction rule. A valid replacement can
then replace its incumbent. A non-duplicate candidate that completes while the
layer is full is closed; it does not evict an established peer based only on
being newly connected.

This avoids the former periodic soft cap, which allowed an inbound flood to
grow sockets, peer state, and queued I2P setup work until pruning ran. The
normal prune remains a recovery backstop for state left over from older builds.
Because `maxDataPeers` is restart-required, NetworkData captures it at startup
and does not mix a later settings-object replacement into its live contract.
