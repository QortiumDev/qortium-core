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
  and authentication-tag validation; and
- one QPGC member key wrap with exact nonce and wrapped bytes.

The fixture will grow additively during roadmap milestone C0. Key-announcement
signing, key/rotation requests, relayed controls, CHAT transaction bytes, and
QENC attachment vectors are not part of this first slice.

Home must consume these committed values rather than copying expected output
from Java source. Core remains the protocol authority; Home must keep private
and group keys outside QDN app requests, logs, and renderer error payloads.
