; Lunar's native Prequeue consumer is on the client thread and outside its
; detector mutex. Read a bounded, validated Java mode response only while the
; native mode is unknown, then use the original parser/locking/scheduling.
; No packet is suppressed and no game/render/input state is changed.
party_mode:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp, 0x50
.prolog:
    mov rbx, rcx
    mov rsi, rdx
    mov rdi, r8
    mov [rsp+0x40], r9
    test rcx, rcx
    jz .original
    test rdx, rdx
    jz .original
    test r8, r8
    jz .original
    cmp byte [rel $$-CODE_RVA+0x1a7e74], 0 ; original mode-known flag
    jne .original
    lea rdx, [party_mode_name]
    lea r8, [party_mode_sig]
    xor r9d, r9d
    call invoke_int
    dec eax
    cmp eax, 4
    ja .original
    shl eax, 5
    lea rdx, [party_mode_strings]
    add rdx, rax
    movdqu xmm0, [rdx]
    movdqu [rsp+0x20], xmm0
    movdqu xmm0, [rdx+16]
    movdqu [rsp+0x30], xmm0
    lea rcx, [rsp+0x20]
    call IMAGE_BASE+0xcdb80
.original:
    mov rcx, rbx
    mov rdx, rsi
    mov r8, rdi
    mov r9, [rsp+0x40]
    add rsp, 0x50
    pop rdi
    pop rsi
    pop rbx
    jmp IMAGE_BASE+0xce030
party_mode_end:

party_mode_name: db 'pollPartyMode',0
party_mode_sig: db '()I',0
align 16, db 0
party_mode_strings:
    db 'eight_one',0,0,0,0,0,0,0
    dq 9,15
    db 'eight_two',0,0,0,0,0,0,0
    dq 9,15
    db 'four_three',0,0,0,0,0,0
    dq 10,15
    db 'four_four',0,0,0,0,0,0,0
    dq 9,15
    db 'two_four',0,0,0,0,0,0,0,0
    dq 8,15

align 4, db 0
party_mode_unwind:
    db 1, party_mode.prolog-party_mode, 4, 0
    db party_mode.prolog-party_mode, 0x92
    db party_mode.p3-party_mode, 0x70
    db party_mode.p2-party_mode, 0x60
    db party_mode.p1-party_mode, 0x30

align 4, db 0
db 'ADNPTY01'
dd party_mode-$$, party_mode_end-$$, party_mode_unwind-$$
