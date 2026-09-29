; Replace only four overlay predicate reads. The real game-state flag remains
; untouched: enabled = native eligibility || Java's observed Replay sidebar.
; The call sites originally contained CMP, so ALL registers must be preserved,
; including volatile integer and XMM registers. Only comparison flags change.
; RBP provides a legal Windows unwind/epilogue frame while retaining final ZF.
replay_native_flag equ $$ - CODE_RVA + OVERLAY_ELIGIBLE_RVA

db 'ADNRPL02'
dd replay_rdi-$$, replay_rdi_end-$$, replay_rdi_unwind-$$
dd replay_rbx-$$, replay_rbx_end-$$, replay_rbx_unwind-$$
dd replay_frame-$$, replay_frame_end-$$, replay_frame_unwind-$$

%macro REPLAY_GATE 2
%1:
    push rbp
.p1:
    sub rsp, 0xf0
.allocation:
    lea rbp, [rsp+0xf0]
.prolog:
    mov [rsp+0x28], rax
    mov [rsp+0x30], rcx
    mov [rsp+0x38], rdx
    mov [rsp+0x40], r8
    mov [rsp+0x48], r9
    mov [rsp+0x50], r10
    mov [rsp+0x58], r11
    movdqu [rsp+0x60], xmm0
    movdqu [rsp+0x70], xmm1
    movdqu [rsp+0x80], xmm2
    movdqu [rsp+0x90], xmm3
    movdqu [rsp+0xa0], xmm4
    movdqu [rsp+0xb0], xmm5
    movzx eax, byte [replay_native_flag]
    test eax, eax
    jnz .result
%ifidni %2, frame
    mov rcx, [rbp]                ; saved caller frame pointer
    mov rcx, [rcx+0x28]           ; its attached-thread JNIEnv local
%else
    mov rcx, %2
%endif
    lea rdx, [replay_name]
    lea r8, [replay_sig]
    xor r9d, r9d
    call invoke_int
    cmp eax, 1
    sete al
    movzx eax, al
.result:
    mov [rsp+0x20], eax
    movdqu xmm0, [rsp+0x60]
    movdqu xmm1, [rsp+0x70]
    movdqu xmm2, [rsp+0x80]
    movdqu xmm3, [rsp+0x90]
    movdqu xmm4, [rsp+0xa0]
    movdqu xmm5, [rsp+0xb0]
    mov rax, [rsp+0x28]
    mov rcx, [rsp+0x30]
    mov rdx, [rsp+0x38]
    mov r8, [rsp+0x40]
    mov r9, [rsp+0x48]
    mov r10, [rsp+0x50]
    mov r11, [rsp+0x58]
    cmp dword [rsp+0x20], 0
    lea rsp, [rbp]
    pop rbp
    ret
%1 %+ _end:
align 4, db 0
%1 %+ _unwind:
    db 1, %1.prolog-%1, 4, 0xf5
    db %1.prolog-%1, 3
    db %1.allocation-%1, 1
    dw 0xf0/8
    db %1.p1-%1, 0x50
%endmacro

REPLAY_GATE replay_rdi, rdi
REPLAY_GATE replay_rbx, rbx
REPLAY_GATE replay_frame, frame

replay_name: db 'nativeReplayMode',0
replay_sig: db '()I',0
