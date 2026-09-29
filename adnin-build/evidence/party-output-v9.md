# Party output changes in v9

The supplied numeric screenshot contains one party-message header followed by
five naturally wrapped rows of 51, 51, 51, 51 and 48 digits: 252 body characters.
With `/pc `, the outgoing command has 256 UTF-16 code units. Screen wrapping
does not imply multiple messages. The former fixed 96-character body chunks
unnecessarily split that example into three commands.

## Capacity is selected at delivery

The shared `AdninFeatures.tick()` now constructs a 256-unit ASCII party-command
probe on the client thread and reads `C01PacketChatMessage.getMessage()`. The
probe is never queued or transmitted. The accepted string must be an unchanged
prefix of the probe, between 6 and 256 units. Invalid results and constructor,
getter or linkage failures return zero and defer delivery. Retries use the
existing send cadence; pending messages retain their normal expiry.

Static examination of the installed transformed Lunar packet class shows that
its constructor applies the current module/server/Apollo length policy. Its
normal player send method has no additional fixed trimming and retains a
cancellable command event. The original vanilla 1.8.9 packet constructor trims
at 100 units and its decoder reads at most 100. The generic packet writer's much
larger byte bound is not evidence of a larger chat protocol limit.

The outgoing path still calls the player's ordinary `sendChatMessage`. It does
not edit packet fields, patch the game packet class or force long commands onto
an unsupported connection. A runtime accepting 256 units gets up to 252 body
units per command; a runtime accepting 100 gets up to 96. Capacity is measured
again before each fragment so a changed client policy is respected.

Events are queued as whole cleaned bodies and split only at delivery. Pure
formatting helpers also accept an explicit command limit. Fragmentation keeps
surrogate pairs intact and no longer drops everything after the eighth
fragment. Existing limits still apply: at most 1500 units during initial text
cleanup, 32 queued events, 15-second expiry, 30-second duplicate suppression and
a minimum 1.5-second send interval. A very long or busy queue can still expire.

## Party-only branding removal

`partyText` removes a leading `[Adnin]` after local colors and whitespace are
cleaned. `[Seraph]` and `[Urchin]` remain. An `[Adnin]` occurrence inside actual
message content is not removed, and an unrelated label such as `[AdninExtra]`
is preserved. Local/native display is unchanged. Branding-only content is
ignored. The deduplication key is the resulting party body, so differently
colored, branded and unbranded copies of one result do not create duplicates.

The original Skin success message therefore sends `/pc Nick -> RealName`.
Bot Denicker continues to forward only successfully matched and verified
identities. All final commands still begin with `/pc `, even if untrusted
content itself starts with a slash.

## Verification scope

`AdninOutputTest` exercises the exact screenshot length, both transport bounds,
complete reassembly, Unicode boundaries, capacity changes after enqueue,
brand normalization, JSON/plain callbacks, expiry, deduplication and gates.
`AdninFeaturePresentationTest` retains Bot eligibility and local-color checks.

`test_party_packet.py` checks the production capacity method with owned packet
fixtures and an initialization-failing Minecraft fixture. Separate tests use
the actual signed vanilla JAR's packet and buffer classes with in-memory Netty
buffers. No game is initialized, no API is requested and no chat is sent.
Final machine-readable results are in `party-packet-tests-v9.json`; shared
feature results are in `java-tests.txt`.

The vanilla compatibility mapping adds `C01PacketChatMessage -> ie` and
`getMessage()Ljava/lang/String; -> a`. Both payloads are rebuilt from the shared
source. Live Lunar policy activation and actual multiplayer delivery remain
unverified by these offline tests.
