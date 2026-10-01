; Explicit provider selection. These leaf helpers never read user settings,
; mutate configuration, acquire a mutex, or start network work.
; Both reviewed stats functions hold their private key string in RBX.
db 'ADNAPI01'
dd api_key_ready-$$, api_key_ready_end-$$, api_key_ready_unwind-$$
dd api_uuid_ready-$$, api_uuid_ready_end-$$, api_uuid_ready_unwind-$$
dd api_ping_proxy-$$, api_ping_proxy_end-$$, api_ping_proxy_unwind-$$

db 'ADNPURL1'
dd aurora_ping_url-$$
aurora_ping_url: db 'https://bordic.xyz/api/v2/resources/ping?uuid=',0

api_key_ready:
    ; AL = whether the string contains a byte above ASCII space. This rejects
    ; empty/whitespace-only input even before the GUI has normalized the field.
    ; Preserve RCX/RDX/R8/R9 and every nonvolatile register. RAX/R10/R11 are
    ; dead scratch registers at the two hash-pinned native insertion sites.
    xor eax, eax
    ; The current main configuration length is an aligned scalar. Checking
    ; it also rejects a previously copied Number worker key after a clear.
    ; Never dereference the concurrently replaceable global string buffer.
    cmp qword [rel $$-CODE_RVA+API_KEY_LENGTH_RVA], 0
    je .done
    mov r10, [rbx+16]
    test r10, r10
    jz .done
    mov r11, rbx
    cmp qword [rbx+24], 15
    jbe .scan
    mov r11, [rbx]
.scan:
    cmp byte [r11], 0x20
    ja .ready
    inc r11
    dec r10
    jnz .scan
.done:
    ret
.ready:
    mov al, 1
    ret
api_key_ready_end:
align 4, db 0
api_key_ready_unwind: db 1, 0, 0, 0

api_uuid_ready:
    ; Internal ABI additionally returns the original R14B proxy selector.
    ; The enclosing original function already saves/restores caller R14.
    cmp byte [rel $$-CODE_RVA+API_PROXY_RVA], 0
    setne r14b
    jne .proxy
    jmp api_key_ready
.proxy:
    mov eax, 1
    ret
api_uuid_ready_end:
align 4, db 0
api_uuid_ready_unwind: db 1, 0, 0, 0

api_ping_proxy:
    ; Keep the bridge's established symbol/ABI, but Aurora Ping is an independent
    ; public provider. Hypixel's proxy selector and its key must not disable it
    ; or synthesize a failed-cache entry before any request was attempted.
    ; Column demand, gray admission and the original worker/cache still own
    ; scheduling; this tail call adds no work to the game/render thread.
    jmp IMAGE_BASE+NATIVE_PROXY_PING
api_ping_proxy_end:
align 4, db 0
api_ping_proxy_unwind: db 1, 0, 0, 0

%include "adnin-hypixel-http.asm"
