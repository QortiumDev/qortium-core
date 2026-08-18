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
  decryption, shared secret, AAD, derived key, and authentication failures;
- QPGC v1 sorted-membership epoch and group-key identifiers;
- QPGC v1 message nonce, ciphertext, full envelope, wrong-context rejection,
  exact AAD, and authentication-tag validation;
- QPGC member key wrapping, a full signed key announcement, current/specific
  key requests, and a rotation request, including exact signing and envelope
  bytes;
- Qortium CHAT unsigned/signing/signed bytes for an initial group message, a
  `chatReference` revision, and a member relaying another member's signed QPGC
  announcement; and
- Machine-readable positive and negative cases identify the source fixture,
  deterministic mutation/operation, and expected validation layer. Wrapper
  reordering is explicitly accepted after canonical sorting; duplicate or
  missing wrappers are rejected.

Fixed CHAT nonces exercise serialization and signing; they do not claim to
satisfy the current network MemoryPoW policy.

## QENC v2 attachment coverage

`qenc-attachment-v2.json` independently freezes:

- the encrypted QATT v1 filename, media type, data length, SHA-256, and bytes;
- direct sender/recipient Ed25519 keys, X25519 ephemeral/shared secrets,
  canonical recipient key ids, nonces, HKDF info/salts, derived wrap keys,
  wrapped content keys, content AAD, ciphertext, and complete QENC envelope;
- decryption by both participants so the sender-reopen requirement is explicit;
- the QPGC group/epoch/key context, domain-separated attachment content key,
  nonce, AAD, ciphertext, and complete group envelope; and
- structural, context, AEAD, payload-digest, and complete 1 MiB boundary cases.

QENC v2 fixture integers are unsigned big-endian as stated in the QENC spec.
Its deterministic ephemeral/content keys and nonces are test inputs only.

Home must consume these committed values rather than copying expected output
from Java source. Core remains the protocol authority; Home must keep private
and group keys outside QDN app requests, logs, and renderer error payloads.
