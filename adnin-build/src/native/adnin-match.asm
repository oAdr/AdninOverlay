; The verified new-Ingame transition at RVA 0x13f0d keeps JNIEnv* in RBX.
; Its original target (0x8a240 -> 0x91e10) takes no arguments and clears the
; native per-match state. Run it first, then notify Java without doing any IO.
; Entering an already-running match also counts as a transition; Java owns
; caching and deduplication, not this notification-only hook.
match_started:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp, 0x20
.prolog:
    mov rsi, rbx
    call IMAGE_BASE+NATIVE_MATCH_CLEAR
    mov rdi, rax
    mov rcx, rsi
    lea rdx, [match_started_name]
    lea r8, [match_started_sig]
    xor r9d, r9d
    call invoke_void
    mov rax, rdi
    add rsp, 0x20
    pop rdi
    pop rsi
    pop rbx
    ret
match_end:

match_started_name: db 'nativeMatchStarted',0
match_started_sig: db '()V',0

align 4, db 0
match_unwind:
    db 1, match_started.prolog-match_started, 4, 0
    db match_started.prolog-match_started, 0x32
    db match_started.p3-match_started, 0x70
    db match_started.p2-match_started, 0x60
    db match_started.p1-match_started, 0x30
