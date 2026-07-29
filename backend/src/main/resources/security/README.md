# Offline compromised-password blocklist

`common-passwords.sha256` is generated from the first 1,000,000 entries of the public SecLists
`xato-net-10-million-passwords` dataset. Runtime password validation is completely offline and never
sends a password or password prefix to an external service.

Source:
`https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt`

- Retrieved: 2026-07-26
- Source SHA-256: `424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51`
- License: SecLists MIT license, reproduced in `SECLISTS_LICENSE.txt`
- Transformation: Unicode NFC, locale-independent lowercase, policy-eligible length/byte/control
  filtering, SHA-256, unique lexical sort
- Generated entries: 46,146
- Generated SHA-256: `863346929ba7822857ee0407f456108ccc9473e380ec927f5ab12d37ee92b762`

Regenerate from a separately verified source file:

```shell
node backend/scripts/generate-password-blocklist.mjs /verified/source.txt \
  /tmp/common-passwords.sha256
sha256sum /tmp/common-passwords.sha256
diff backend/src/main/resources/security/common-passwords.sha256 \
  /tmp/common-passwords.sha256
```
