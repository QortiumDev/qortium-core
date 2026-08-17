# Chat interoperability fixtures

These fixtures are language-neutral protocol contracts shared by Qortium Core
and the trusted Home implementations that reproduce Core chat cryptography.

## Format rules

- Binary fields are lowercase hexadecimal without a prefix.
- Text fields ending in `Utf8` are serialized as exact UTF-8 bytes.
- Serialized integers are signed Java `int` values in big-endian order unless
  a protocol field explicitly says otherwise.
- Account private keys are deterministic test inputs only. They must never be
  imported, funded, or used outside automated interoperability tests.
- Fixture nonces are deterministic test inputs. Production entry points
  continue generating nonces with `SecureRandom`.

## Version 1 coverage

`chat-crypto-v1.json` currently freezes:

- QDM1 sender/recipient keys, nonce, ciphertext, full envelope, both-party
  decryption, and authentication-tag tamper rejection;
- QPGC v1 sorted-membership epoch and group-key identifiers;
- QPGC v1 message nonce, ciphertext, full envelope, wrong-context rejection,
  and authentication-tag validation;
- QPGC member key wrapping, a full signed key announcement, current/specific
  key requests, and a rotation request, including exact signing and envelope
  bytes; and
- Qortium CHAT unsigned/signing/signed bytes for an initial group message, a
  `chatReference` revision, and a member relaying another member's signed QPGC
  announcement.

The fixture will grow additively during roadmap milestone C0. Explicit negative
transaction/control cases and QENC attachment vectors remain open. Fixed CHAT
nonces exercise serialization and signing; they do not claim to satisfy the
current network MemoryPoW policy.

Home must consume these committed values rather than copying expected output
from Java source. Core remains the protocol authority; Home must keep private
and group keys outside QDN app requests, logs, and renderer error payloads.
