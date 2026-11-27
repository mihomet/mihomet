# Xray client corpus

Derived from the official XTLS/Xray-examples client configurations at commit
`167cded1a33ac7b0ce76fcf00ea2d8fb0604e927`. `sources.json` records each source
and the SHA-256 of the sanitized fixture. Server addresses, user identifiers,
passwords and Reality public keys are synthetic. Comments are deliberately kept
to exercise JSONC parsing. Tests never connect to these endpoints.

The existing `panels/remnawave` and `templates-remnawave` corpora complement
these fixtures with actual panel response shapes and routing templates.

`routing-logic.jsonc` and `mapping-edges.jsonc` are project-authored synthetic
regression fixtures, not XTLS examples. They cover scoped balancers, nested
AND/OR conditions, IPv6, regular expressions and strict XHTTP/XMUX option
decoding, and are also validated by the bundled core on-device.
