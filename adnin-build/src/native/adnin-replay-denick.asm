; Preserve the ordinary UUID-v1 gate; enter that same native Nick path for
; Replay only when the current Java roster has an authoritative cached NICK.
; The caller's Replay flag, actor UUID and GameProfile are never overwritten.
db 'ADNRDN01'
dd replay_denick_gate-$$, replay_denick_gate_end-$$, replay_denick_gate_unwind-$$
dd replay_nick_name-$$, replay_nick_name_end-$$, replay_nick_name_unwind-$$
dd replay_uuid_copy-$$, replay_uuid_copy_end-$$, replay_uuid_copy_unwind-$$

replay_denick_gate:
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
    test r12b, r12b
    jnz .replay
    xor eax, eax
    cmp ebx, 1
    sete al
    jmp .result
.replay:
    mov rdx, [rbp]               ; preserved model builder's RBP
    add rdx, REPLAY_ROW_NAME_FRAME
    mov rcx, r13                 ; verified builder JNIEnv
    call replay_nick_name
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
replay_denick_gate_end:
align 4, db 0
replay_denick_gate_unwind:
    db 1, replay_denick_gate.prolog-replay_denick_gate, 4, 0xf5
    db replay_denick_gate.prolog-replay_denick_gate, 3
    db replay_denick_gate.allocation-replay_denick_gate, 1
    dw 0xf0/8
    db replay_denick_gate.p1-replay_denick_gate, 0x50

; RCX JNIEnv, RDX already-normalized native row name. Returns exactly 0/1.
; Java only reads current-roster/cache state. No requests or IO here.
replay_nick_name:
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
    xor edi, edi
    mov dword [rsp+0x28], 0
    test rbx, rbx
    jz .done
    test rsi, rsi
    jz .done
    cmp qword [gui_class], 0
    je .done
    mov r8, [rsi+16]
    test r8, r8
    jz .done
    cmp r8, 16
    ja .done
    cmp r8, [rsi+24]
    ja .done
    mov rdx, rsi
    cmp qword [rsi+24], 16
    jb .data
    mov rdx, [rsi]
.data:
    test rdx, rdx
    jz .done
    xor ecx, ecx
.character:
    movzx eax, byte [rdx+rcx]
    cmp al, '_'
    je .valid
    cmp al, '0'
    jb .done
    cmp al, '9'
    jbe .valid
    or al, 0x20
    cmp al, 'a'
    jb .done
    cmp al, 'z'
    ja .done
.valid:
    inc ecx
    cmp ecx, r8d
    jb .character
    cmp byte [rdx+r8], 0
    jne .done
    mov rcx, rbx
    call new_string
    mov rdi, rax
    test rax, rax
    jz .done
    mov [rsp+0x20], rax
    mov rcx, rbx
    lea rdx, [replay_is_nick_name]
    lea r8, [replay_is_nick_sig]
    lea r9, [rsp+0x20]
    call invoke_int
    cmp eax, 1
    sete al
    movzx eax, al
    mov [rsp+0x28], eax
    mov rcx, rbx
    mov rdx, rdi
    mov rax, [rbx]
    call [rax+DELETE_LOCAL_REF]
.done:
    mov eax, [rsp+0x28]
    add rsp, 0x30
    pop rdi
    pop rsi
    pop rbx
    ret
replay_nick_name_end:
align 4, db 0
replay_nick_name_unwind:
    db 1, replay_nick_name.prolog-replay_nick_name, 4, 0
    db replay_nick_name.prolog-replay_nick_name, 0x52
    db replay_nick_name.p3-replay_nick_name, 0x70
    db replay_nick_name.p2-replay_nick_name, 0x60
    db replay_nick_name.p1-replay_nick_name, 0x30

; Skin's ready result already contains the account UUID and copied Stats.
; Preserve Replay's actor UUID in the published row; ordinary copy is intact.
replay_uuid_copy:
    cmp byte [rel $$-CODE_RVA+MODE_FLAG_RVA], 0
    jne .replay
    jmp IMAGE_BASE+NATIVE_UUID_COPY
.replay:
    mov rax, rcx
    ret
replay_uuid_copy_end:
align 4, db 0
replay_uuid_copy_unwind:
    db 1, 0, 0, 0

replay_is_nick_name: db 'nativeReplayIsNick',0
replay_is_nick_sig: db '(Ljava/lang/String;)I',0
