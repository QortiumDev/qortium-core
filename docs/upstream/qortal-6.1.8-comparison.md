# Qortal 6.1.8 Comparison

Qortal 6.1.8 is a small validation-hardening follow-up to Qortal 6.1.7.
Qortium does not adopt the upstream version bump or peer-version floor change,
because Qortium versions and network compatibility are managed independently.

The useful upstream changes are adapted into Qortium's current code shape:

- grouped peer-message parsers now validate every group count, not just the
  first count, before reading timestamped online-account and trade-presence
  entries;
- `NamesMessage` uses a minimum required entry size for its initial count
  precheck, so multiple short valid names are not rejected just because each
  possible name entry could be much larger;
- AT message deserialization now accepts message payloads up to the validator's
  existing 256-byte `AtTransaction.MAX_DATA_SIZE`, rejects negative or
  over-declared lengths explicitly, and preserves zero-length MESSAGE-type AT
  transactions as messages.

These are parser hardening fixes. They do not add a feature trigger, change the
chain config, or import Qortal's version number.
