# ARRR partial transaction history

The wallet history operation uses the existing coordinated native wallet session.
For the unified backend it obtains the active wallet id and requests
`qortal_list_transactions_partial` through `invokeJson`. Only an explicit
unknown-method response allows fallback to legacy `list`. Other errors do not
cause a second native history request. The legacy backend continues to use
`list` directly.

The HTTP wallet-transactions response remains an array of `SimpleTransaction`
rows, with additional optional fields for ARRR:

- `totalAmount` is the established incoming-minus-outgoing amount, excluding the
  fee. It is absent/null when the total is unknown; zero is a known value.
- `feeAmount` is the wallet's transaction fee, counted once, or absent/null when
  unknown. Incoming-only rows do not charge the sender's fee to this wallet.
- `totalAmountEstimate` and `feeAmountEstimate` are separate estimates, never
  substitutes for the established fields. Clients must label them estimated.
- `metadataComplete` says whether the total amount is established. It does not
  promise every recipient address or memo is known. A known-accounting remainder
  may have an unknown recipient address.
- `pending` preserves the native unconfirmed state. Pending rows with unknown
  totals stay visible; they are not represented as zero-value sends.

Recipient arrays can contain only part of the available recovery evidence.
Clients must not reconstruct a total by summing those arrays when accounting is
incomplete. Unknown direction should use neutral labels. Existing numeric
amount encoding is retained; Home continues to require safe integer values.

## Compatibility and rollout

The partial native method requires a compatible native library. The existing
Core native artifact pin is unchanged by this source change. Older native
libraries use the compatibility fallback and do not gain partial availability.

Home's custody adapter and Wallet's presentation must also understand nullable
amounts and the additional fields. Update those consumers before enabling this
Core/native combination in a user-facing deployment. No new custody authority
or wallet switching behavior is introduced. Source tests cover the native JSON
request path, API serialization, partial values, fee counting, and narrow
fallback behavior; deployed native acceptance remains a separate check.
