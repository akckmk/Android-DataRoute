# DataRoute security review

Review of the whole app (engine, Android service, web viewer, CI) with attack tests added to CI.

## Threat model

DataRoute is a local VPN: every app's IPv4 traffic passes through it, it holds the VPN permission, and it opens a
web viewer onto home devices. The parties that matter:

- **Any app on the phone** can send arbitrary traffic into the tunnel (fuzzed packets, floods, odd DNS names).
- **Other devices on a network the phone joins** (home LAN, or a café/hotel Wi-Fi) can answer DNS or pose as
  "192.168.1.10".
- **Anyone who reads this public repository** sees its code, history and CI configuration.

DataRoute has no server, account or telemetry; nothing is sent anywhere except the apps' own traffic, the
"Run tests" connectivity check, and connections to the home services the user opens.

## Findings and fixes

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | High | The APK signing key and its password were committed to this **public** repo. Anyone could build a lookalike "update" that Android would accept, inheriting the VPN permission. | Key removed and retired. CI signs with a private key from GitHub Secrets; the download link only publishes builds signed with it. |
| 2 | High | The web viewer accepted **any** self-signed certificate for local-looking addresses. On a hostile Wi-Fi, a device could pose as the NVR and capture its login. | Trust on first use: each device's certificate is remembered; a different one is blocked with an explanation. "Forget trusted certificates" in Settings. |
| 3 | Medium | A burst of DNS lookups created one blocking thread each (a test reached 3,000), so any app could exhaust memory. | At most 64 lookups in flight; extra queries are dropped and retried by the app's resolver. |
| 4 | Medium | An unexpected exception while handling a packet would end the TUN reader thread, silently cutting every app off. | The engine never throws from packet handling, and the reader loop guards each packet as well. |
| 5 | Low | Home-network DNS replies were accepted from any sender with the right 16-bit ID (blind LAN spoofing). | Unicast queries use connected sockets; every reply must echo the question. |
| 6 | Low | DNS names were written to the in-app Log verbatim (newlines could forge log lines). | Names are reduced to printable ASCII before logging. |
| 7 | Low | Web viewer allowed mixed content, content:// access and non-web links (`intent:`). | Mixed content in compatibility mode, file/content access off, only http(s) navigation. |
| 8 | Low | 64 KB receive buffer per UDP flow (up to 64 MB) though larger datagrams are dropped anyway. | 1.5 KB buffers. |
| 9 | Low | CI jobs that run third-party actions had write access; actions referenced by movable tags. | Read-only by default; write only in two small first-party jobs; third-party actions pinned to commit SHAs. |

Also changed: the NAT64 retry only fires on "no route" errors (it doubled timeouts before); "Internet via Wi-Fi"
during an outage shows as a note instead of a failure.

## Tests that guard these

`core/src/test/.../AdversarialTest.kt`, run on every push:

- 150,000 random and mutated IPv4/TCP/UDP/DNS packets through the engine: none may throw.
- 200,000 malformed DNS messages through the parsers and the local resolver.
- A 3,000-query DNS flood: concurrent work must stay within the cap.
- A DNS name carrying a newline and a fake log line must be sanitized.
- 400 simultaneous TCP connections, each must get its own data back.

Plus `LocalDnsTest.spoofedRepliesAreIgnored` (forged source / forged question rejected) and
`TrustOnFirstUseTest` (first certificate trusted, a changed one refused).

## Known limits (by design)

- One VPN at a time (Android). DataRoute isn't a privacy VPN: traffic leaves on your own networks unencrypted
  by DataRoute (apps' own TLS still applies).
- `allowBypass()` is on, so an app that explicitly binds to a network can skip the tunnel. That's intended.
- The retired signing key remains in git history. It no longer signs anything; uninstall any build signed with it.

## Reporting

Open an issue on this repository (don't include private keys or passwords).
