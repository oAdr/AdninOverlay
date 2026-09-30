; Publish the strict native state after its existing sidebar parser returns.
; Repeating the notification also covers injection into an existing match and
; retries an unavailable optional Java callback. Java owns transition handling.
game_active:
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
    mov rdi, r8
    call IMAGE_BASE+NATIVE_STATE_PARSER
    mov [rsp+0x28], rax
    mov qword [rsp+0x20], 0
    test rsi, rsi
    jz .publish
    test rdi, rdi
    jz .publish
    lea rdx, [$$-CODE_RVA+GAME_STATE_RVA]
    cmp qword [rdx+16], 6
    jne .publish
    cmp qword [rdx+24], 6
    jb .publish
    cmp qword [rdx+24], 16
    jb .text_ready
    mov rdx, [rdx]
    test rdx, rdx
    jz .publish
.text_ready:
    cmp dword [rdx], 0x61676e49 ; Inga
    jne .publish
    cmp word [rdx+4], 0x656d   ; me
    jne .publish
    mov qword [rsp+0x20], 1
.publish:
    mov rcx, rbx
    lea rdx, [game_active_name]
    lea r8, [game_active_sig]
    lea r9, [rsp+0x20]
    call invoke_void
    mov rax, [rsp+0x28]
    add rsp, 0x30
    pop rdi
    pop rsi
    pop rbx
    ret
game_active_end:

game_active_name: db 'setGameActive',0
game_active_sig: db '(Z)V',0

align 4, db 0
game_active_unwind:
    db 1, game_active.prolog-game_active, 4, 0
    db game_active.prolog-game_active, 0x52
    db game_active.p3-game_active, 0x70
    db game_active.p2-game_active, 0x60
    db game_active.p1-game_active, 0x30

align 4, db 0
db 'ADNGAM01'
dd game_active-$$, game_active_end-$$, game_active_unwind-$$
