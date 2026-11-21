# UKEY2 responder — implementation spec

Everything needed to implement `LinkEncryption` (the iPhone side of the watch's
encrypted channel) as the **UKEY2 responder**, with every constant taken from the pinned
upstream source so the implementation can be byte-exact.

- Upstream: https://github.com/google/ukey2 (Apache-2.0)
- Pinned commit: `10fc737aa901e873a3367e7e26b88eb01cd55d69`
- Vendored (gitignored) at `research/ukey2-src/` for reading and as the test oracle.
- Watch role = **initiator**, iPhone = **responder**. Cipher `P256_SHA512`, next protocol
  `AES_256_CBC-HMAC_SHA256`. Carried in RootMessage field 17 (`dako`) sub-field 1.

## Handshake (ClientInit → ServerInit → ClientFinished)

Protos (`src/main/proto/ukey.proto`):
- `Ukey2Message { Type message_type=1; bytes message_data=2 }`, Type: ALERT=1, CLIENT_INIT=2,
  SERVER_INIT=3, CLIENT_FINISH=4.
- `Ukey2ClientInit { int32 version=1; bytes random=2; repeated CipherCommitment cipher_commitments=3;
  string next_protocol=4 }`, `CipherCommitment { Ukey2HandshakeCipher handshake_cipher=1; bytes commitment=2 }`.
- `Ukey2ServerInit { int32 version=1; bytes random=2; Ukey2HandshakeCipher handshake_cipher=3; bytes public_key=4 }`.
- `Ukey2ClientFinished { bytes public_key=1 }`.
- `Ukey2HandshakeCipher.P256_SHA512 = 100`. VERSION = 1. random = 32 bytes.
- `public_key` = serialized `securemessage.GenericPublicKey` (see below).
- commitment = `SHA512(ClientFinished Ukey2Message bytes)` (`Ukey2Handshake.java` ~L901).

Responder flow (`Ukey2Handshake.java`):
1. Receive ClientInit (a `Ukey2Message` type 2 wrapping `Ukey2ClientInit`). Keep its **raw bytes** = `M1`.
2. Reply ServerInit (`Ukey2Message` type 3 wrapping `Ukey2ServerInit`) with our P256 public key. Keep raw = `M2`.
3. Receive ClientFinished (type 4 wrapping `Ukey2ClientFinished`); verify `SHA512(that Ukey2Message)`
   equals the commitment from ClientInit. Parse the initiator's public key.

## Key derivation (`Ukey2Handshake.getVerificationString` / `toConnectionContext`)

- `dhs = ECDH_P256(ourPriv, theirPub)` raw shared secret (32-byte X). **`derivedSecretKey = SHA256(dhs)`**
  (`EnrollmentCryptoOps.doKeyAgreement` L104).
- `info = M1 || M2` (raw ClientInit bytes ++ raw ServerInit bytes).
- `authString = HKDF_SHA256(ikm=derivedSecretKey, salt="UKEY2 v1 auth", info)` → verification code =
  `authString[0..<N]`. The watch shows the same; user compares. (L382-390)
- `nextSecret = HKDF_SHA256(ikm=derivedSecretKey, salt="UKEY2 v1 next", info)` (L517-525).
- `clientKey = deriveNewKeyForPurpose(nextSecret, "client")`, `serverKey = deriveNewKeyForPurpose(nextSecret, "server")`.
- `deriveNewKeyForPurpose(k, p) = HKDF_SHA256(ikm=k, salt=SHA256("SecureMessage"), info=p, len=32)`
  (`CryptoOps.java` L397; SALT L134).
- HKDF = RFC-5869 extract+expand with SHA-256 (`CryptoOps.hkdf` L430-438).

Responder `D2DConnectionContextV1`: **encodeKey = serverKey, decodeKey = clientKey**, both sequence
numbers start at 0 (`Ukey2Handshake.java` L540-545). PROTOCOL_VERSION = 1.

## GenericPublicKey (`src/main/proto/securemessage.proto`)

- `GenericPublicKey { PublicKeyType type=1; EcP256PublicKey ec_p256_public_key=2 }`, `PublicKeyType.EC_P256=1`.
- `EcP256PublicKey { bytes x=1; bytes y=2 }`. x/y are Java `BigInteger` two's-complement big-endian:
  minimal bytes, with a leading `0x00` when the top bit is set (so a 32-byte coord with MSB set → 33 bytes).
  Parsing: strip a leading `0x00`, left-pad to 32. **Our ServerInit key bytes feed `M2`, so this encoding
  must match upstream for the auth string to agree.**

## Transport: D2DConnectionContextV1 encode/decode

Each direction, per message:
- `body = DeviceToDeviceMessage { bytes message=1 (plaintext); int32 sequence_number=2 (++seq) }`
  (`device_to_device_messages.proto`).
- Wrap as a `securemessage.SecureMessage` via **signcrypt**, sign key == enc key == the direction key
  (so **no plaintext tag**: `taggedPlaintextRequired` is false when sig is HMAC and signKey==encKey).
  (`SecureMessageBuilder.buildSignCryptedMessage`, `D2DCryptoOps.signcryptPayload` L100-125.)
- `SecureMessage { bytes header_and_body=1; bytes signature=2 }`.
- `HeaderAndBody { Header header=1; bytes body=2 }`.
- `Header { SigScheme signature_scheme=1 (HMAC_SHA256=1); EncScheme encryption_scheme=2 (AES_256_CBC=2);
  bytes decryption_key_id=4 (omitted for ongoing D2D msgs); bytes iv=5 (16 random bytes);
  bytes public_metadata=6; uint32 associated_data_length=7 (0) }`.
- `public_metadata = GcmMetadata { Type type=1 (= PayloadType DEVICE_TO_DEVICE_MESSAGE = 13); int32 version=2 (= 1) }`
  (`securegcm.proto`; `SecureGcmConstants.SECURE_GCM_VERSION = 1`).
- `encryptedBody = AES_256_CBC(encSubKey, iv, PKCS7(body))`.
- `signature = HMAC_SHA256(macSubKey, HeaderAndBody bytes)` — HMAC input is **not** salted for symmetric
  schemes (`CryptoOps.sign` L170-174; SALT prefix is public-key only).
- **Sub-keys** (`CryptoOps.deriveAes256KeyFor` via `getPurpose`):
  `encSubKey = HKDF_SHA256(dirKey, salt=SHA256("SecureMessage"), info="ENC:2", 32)`,
  `macSubKey = HKDF_SHA256(dirKey, salt=SHA256("SecureMessage"), info="SIG:1", 32)`.
- Decode reverses: parse SecureMessage, recompute HMAC over header_and_body and compare (constant-time),
  AES-CBC decrypt, check `GcmMetadata.type==13`, check `sequence_number == ++decodeSeq`. Any failure throws.

Saved session (73 bytes, for resume): `1 (version) || int32 encodeSeq || int32 decodeSeq || encodeKey(32) || decodeKey(32)`.

## Security bar (unchanged from HANDOFF.md)
Official library unmodified as the test oracle; commit pinned. Never log key material, the verification
code, or decrypted payloads. Any auth/sequence failure drops the connection — no plaintext fallback.
Resume session only in the iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
