# Notification WebSocket

Core exposes session-scoped push subscriptions at `/websockets/notifications`.
The endpoint is covered by the node-wide `PublicApiAccessHandler`; notification
events do not add a second authentication scheme.

Clients send a `subscribe` action with one or more rules. Generic filter arrays
mean OR within one key, while different keys are ANDed. `RESOURCE_PUBLISHED`
uses its typed `resourceFilter` instead.

## Events and filters

| Event | Filters |
| --- | --- |
| `RESOURCE_PUBLISHED` | Typed `resourceFilter`; legacy generic `service`, `name` |
| `PAYMENT_RECEIVED` | `sender`, `recipient`, `amount`, `created`, `signature` |
| `CHAT_MESSAGE` | `recipient`, `sender`, `txGroupId`, `involving` (at least one required) |
| `TRANSACTION_CONFIRMED` | `signature`, `address`, `groupId`, `txType`; at least one of `signature`, `address`, or `groupId` is required |
| `FOREIGN_PAYMENT_RECEIVED` | Scalar `coin` and `xpub`, both required |

`TRANSACTION_CONFIRMED.groupId` is the direct group ID carried by
`JOIN_GROUP`, `GROUP_INVITE`, `LEAVE_GROUP`, `GROUP_KICK`, `GROUP_BAN`,
`CANCEL_GROUP_INVITE`, `CANCEL_GROUP_BAN`, `ADD_GROUP_ADMIN`,
`REMOVE_GROUP_ADMIN`, `UPDATE_GROUP`, and assigned `CREATE_GROUP`
transactions. `SET_GROUP.defaultGroupId` is deliberately excluded because it
has different semantics.

## Foreign payments

One rule watches one coin and one hierarchical deterministic wallet:

```json
{
  "event": "FOREIGN_PAYMENT_RECEIVED",
  "filters": {
    "coin": "BTC",
    "xpub": "<extended public key>"
  }
}
```

`coin` accepts a coin name or currency code from Core's registered Bitcoiny
chains and is normalized to the currency code in pushes. PirateChain/ARRR is
not a Bitcoiny ElectrumX chain and is rejected. `xpub` must parse as a public
extended key for the coin's active network; private extended keys and filter
arrays are rejected. An xpub is limited to 128 characters and is rejected
before Base58 decoding when it exceeds that limit. Each websocket session may
hold at most 20 active foreign-payment rules, with at most 64 active rules
across all sessions on one Core node.

Core holds the xpub and its derived keys only in memory for the websocket
session. It derives receive and change addresses with the same initial and
incremental batches and `Settings.getGapLimit()` stopping rule as
`/crosschain/{coin}/wallettransactions`, but never derives more than 1,024
receive/change addresses combined for one rule. Reaching that ceiling is
logged once and the already-derived set remains active. The first ElectrumX
history read is a baseline, so subscribing does not replay old deposits. Later
scripthash status changes fetch a history delta and emit one event per newly
seen incoming transaction. A transaction is only marked seen after Core has
successfully fetched and classified it, so a transient fetch failure is retried
after failover. A transaction spending any watched wallet output is treated as
outgoing/change and does not emit an incoming event.

```json
{
  "type": "notification",
  "event": "FOREIGN_PAYMENT_RECEIVED",
  "data": {
    "coin": "BTC",
    "txHash": "<foreign transaction hash>",
    "address": "<first watched receiving address in transaction output order>",
    "amount": "1.23456789",
    "direction": "incoming",
    "confirmations": 1,
    "checkpoint": "<ElectrumX scripthash status>",
    "timestamp": 1784150000000
  },
  "notificationId": "wallet-btc",
  "appName": "Wallet",
  "appService": "APP"
}
```

`amount` is the sum of watched outputs in the transaction, formatted in coin
units with eight decimal places. There is one event per new transaction per
subscription; when a transaction pays more than one watched address, `address`
is the first matching output address and `amount` remains the combined incoming
amount. `confirmations` is calculated from the subscribed ElectrumX header
height at push time. `checkpoint` is always present and lets a client suppress
replays across reconnects. `timestamp` is added by Core's common notification
emitter. The generic emitter also echoes `notificationId`, `appName`, and
`appService` when supplied by the subscription.

The push transport uses a separate persistent ElectrumX connection per active
coin. It multiplexes all websocket sessions for that coin, reconnects with
server failover and backoff, and closes when the last matching rule is removed.
The connection sends `server.ping` every 30 seconds once ready and fails over
when the response misses the 15-second RPC deadline. ElectrumX input is capped
at 9,437,184 characters per newline-delimited message, 1,000 history entries
per scripthash, and 4 MiB per decoded raw transaction; exceeding a cap is a
protocol error and disconnects that server. Per-scripthash baseline/seen
history retains at most 1,000 hashes, and cross-address per-rule deduplication
retains at most 4,096.
Foreign pushes use their own one-thread, 128-entry dispatch queue, separate
from chain-derived notification dispatch. The notification-only Bitcoiny
instance is also separate from the normal shared wallet instance, so a watch
does not bypass an operator-disabled wallet integration. Core never persists
an xpub. Ownership proof for watch-only keys remains outside the v1 protocol;
access follows the existing public-API rules.
