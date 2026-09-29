; Read already-built native row cells. This helper performs no lookup request,
; cache mutation, formatting, rounding or numeric conversion. FUN_18005a510
; stores a full decimal counter under "finalKills" at row+0xc8. The "stars"
; value is formatted by FUN_180025fe0: Minecraft colors, brackets, the displayed
; integer Bedwars level and a star glyph. Higher prestiges color each digit.
; Forward those bytes intact; Java parses the verified presentation grammar.
;
; RCX JNIEnv*, RDX row*, R8 MSVC SSO key*, R9 matching FNV-1a 64-bit hash.
; Return a new local jstring (caller owns it), or NULL for an absent/invalid
; value. The shared new_string helper preserves pre-existing JNI exceptions.
METRICS_FINAL_HASH equ 0x40d2ddcc4d06d23c
METRICS_STARS_HASH equ 0xee5d94ad45ad2006

metrics_value:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp, 0x30
.prolog:
    mov rbx, rcx
    mov rsi, rdx
    test rbx, rbx
    jz .missing
    test rsi, rsi
    jz .missing
    cmp qword [rsi+0xd0], 0
    je .missing
    cmp qword [rsi+0xe0], 0
    je .missing
    mov qword [rsp+0x20], 0
    mov qword [rsp+0x28], 0
    lea rcx, [rsi+0xc8]
    lea rdx, [rsp+0x20]
    call IMAGE_BASE+NATIVE_METRICS_FIND
    mov rdi, [rsp+0x28]
    test rdi, rdi
    jz .missing
    cmp rdi, [rsi+0xd0]
    je .missing
    mov rax, [rdi+0x40]
    test rax, rax
    jz .missing
    cmp rax, 64
    ja .missing
    cmp rax, [rdi+0x48]
    ja .missing
    lea rdx, [rdi+0x30]
    cmp qword [rdi+0x48], 16
    jb .data_ready
    mov rdx, [rdi+0x30]
.data_ready:
    test rdx, rdx
    jz .missing
    cmp byte [rdx+rax], 0
    jne .missing
    mov rcx, rbx
    call new_string
    jmp .done
.missing:
    xor eax, eax
.done:
    add rsp, 0x30
    pop rdi
    pop rsi
    pop rbx
    ret
metrics_end:

align 8, db 0
metrics_final_key:
    db 'finalKills',0
    times 16-($-metrics_final_key) db 0
    dq 10,15
metrics_stars_key:
    db 'stars',0
    times 16-($-metrics_stars_key) db 0
    dq 5,15

align 4, db 0
metrics_unwind:
    db 1, metrics_value.prolog-metrics_value, 4, 0
    db metrics_value.prolog-metrics_value, 0x52
    db metrics_value.p3-metrics_value, 0x70
    db metrics_value.p2-metrics_value, 0x60
    db metrics_value.p1-metrics_value, 0x30
