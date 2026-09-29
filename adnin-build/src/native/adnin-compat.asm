; Compatibility-only client-thread heartbeat. The pinned NewChat installer at
; 0x1187c registers two methods on the DefineClass result kept in R14. Copy those
; exact entries, add nativeClientTick()V, and return RegisterNatives' status.
%ifndef ADNIN_STATE_RVA
%error ADNIN_STATE_RVA is required for compatibility heartbeat storage
%endif

align 8, db 0
db 'ADNINC01'
dd compat_register-$$, compat_register_end-$$, compat_register_unwind-$$
dd compat_client_tick-$$, compat_client_tick_end-$$
dd compat_unload_key-$$, compat_unload_key_end-$$, compat_unload_key_unwind-$$

compat_register:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp, 0x70
.prolog:
    mov rbx, rcx
    mov rsi, rdx
    mov byte [rsp+0x68], 0
    test rcx, rcx
    jz .invalid_env
    cmp r9d, 2
    jne .original
    cmp rdx, r14                    ; original DefineClass result, pinned caller
    jne .original
    test rdx, rdx
    jz .original
    test r8, r8
    jz .original
    lea r10, [rel $$-CODE_RVA+0x140cd0]
    cmp [r8], r10                   ; nativeOnIncomingChat
    jne .original
    lea r10, [rel $$-CODE_RVA+0x140ce8]
    cmp [r8+8], r10                 ; (Ljava/lang/Object;)V
    jne .original
    lea r10, [rel $$-CODE_RVA+0x20490]
    cmp [r8+16], r10
    jne .original
    lea r10, [rel $$-CODE_RVA+0x140d00]
    cmp [r8+24], r10                ; nativeShouldSuppressIncomingChat
    jne .original
    lea r10, [rel $$-CODE_RVA+0x140d28]
    cmp [r8+32], r10                ; (Ljava/lang/Object;)Z
    jne .original
    lea r10, [rel $$-CODE_RVA+0x204a0]
    cmp [r8+40], r10
    jne .original
    movdqu xmm0, [r8]
    movdqu [rsp+0x20], xmm0
    movdqu xmm0, [r8+16]
    movdqu [rsp+0x30], xmm0
    movdqu xmm0, [r8+32]
    movdqu [rsp+0x40], xmm0
    lea r10, [compat_tick_name]
    mov [rsp+0x50], r10
    lea r10, [compat_tick_sig]
    mov [rsp+0x58], r10
    lea r10, [compat_client_tick]
    mov [rsp+0x60], r10
    lea r8, [rsp+0x20]
    mov r9d, 3
    mov byte [rsp+0x68], 1
.original:
    mov rax, [rbx]
    call [rax+0x6b8]                ; RegisterNatives, never clears exceptions
    test eax, eax
    jnz .done
    cmp byte [rsp+0x68], 1
    jne .done
    mov rcx, rbx
    mov rax, [rcx]
    call [rax+0x720]                ; preserve any pending JNI exception
    test al, al
    jnz .lifecycle_failed
    mov rcx, rbx
    mov rdx, rsi
    lea r8, [compat_stop_name]
    lea r9, [compat_tick_sig]
    mov rax, [rcx]
    call [rax+0x388]                ; GetStaticMethodID before constructor starts
    mov rdi, rax
    mov rcx, rbx
    mov rax, [rcx]
    call [rax+0x720]
    test al, al
    jnz .lifecycle_failed
    test rdi, rdi
    jz .lifecycle_failed
    mov rcx, rbx
    mov rdx, rsi
    mov rax, [rcx]
    call [rax+0xa8]                 ; keep NewChat class alive until pump stops
    mov rsi, rax
    mov rcx, rbx
    mov rax, [rcx]
    call [rax+0x720]
    test al, al
    jnz .global_ref_failed
    test rsi, rsi
    jz .lifecycle_failed
    mov [rel $$-CODE_RVA+ADNIN_STATE_RVA+24], rsi
    mov [rel $$-CODE_RVA+ADNIN_STATE_RVA+32], rdi
    mov dword [rel $$-CODE_RVA+ADNIN_STATE_RVA+8], 1
    xor eax, eax
    jmp .done
.global_ref_failed:
    test rsi, rsi
    jz .lifecycle_failed
    mov rcx, rbx
    mov rdx, rsi
    mov rax, [rcx]
    call [rax+0xb0]                 ; DeleteGlobalRef is valid with an exception
.lifecycle_failed:
.invalid_env:
    mov eax, -1
.done:
    add rsp, 0x70
    pop rdi
    pop rsi
    pop rbx
    ret
compat_register_end:

; Called only by the bounded scheduled Java client callback after Features.tick.
; This leaf function has no stack frame, JNI calls, network or game operations.
compat_client_tick:
    lock inc qword [rel $$-CODE_RVA+ADNIN_STATE_RVA+16]
    ret
compat_client_tick_end:

; The pinned initializer calls GetAsyncKeyState(End) at 0x15205 with its
; attached-thread JNIEnv in [caller_rsp+0x30]. Return a pressed key only after
; Java's lifecycle barrier has stopped every scheduled callback. A JNI error
; returns zero, keeping the original polling loop before any cleanup/unload.
compat_unload_key:
    push rbx
.p1:
    sub rsp, 0x30
.prolog:
    mov rbx, [rsp+0x70]             ; entry_rsp+0x38 == caller_rsp+0x30
    call [rel $$-CODE_RVA+0x132640] ; original GetAsyncKeyState IAT
    mov [rsp+0x20], eax
    test ax, ax
    jz .done
    cmp dword [rel $$-CODE_RVA+ADNIN_STATE_RVA+8], 1
    jne .done                     ; no constructor/pump was allowed to start
    test rbx, rbx
    jz .blocked
    mov rcx, rbx
    mov rax, [rcx]
    call [rax+0x720]
    test al, al
    jnz .blocked
    mov rcx, rbx
    mov rdx, [rel $$-CODE_RVA+ADNIN_STATE_RVA+24]
    test rdx, rdx
    jz .blocked
    mov r8, [rel $$-CODE_RVA+ADNIN_STATE_RVA+32]
    test r8, r8
    jz .blocked
    xor r9d, r9d
    mov rax, [rcx]
    call [rax+0x478]                ; CallStaticVoidMethodA(stop, no arguments)
    mov rcx, rbx
    mov rax, [rcx]
    call [rax+0x720]
    test al, al
    jnz .blocked
    mov rcx, rbx
    mov rdx, [rel $$-CODE_RVA+ADNIN_STATE_RVA+24]
    mov rax, [rcx]
    call [rax+0xb0]
    mov qword [rel $$-CODE_RVA+ADNIN_STATE_RVA+24], 0
    mov qword [rel $$-CODE_RVA+ADNIN_STATE_RVA+32], 0
    mov dword [rel $$-CODE_RVA+ADNIN_STATE_RVA+8], 0
    mov dword [rel $$-CODE_RVA+ADNIN_STATE_RVA+12], 1
.done:
    mov eax, [rsp+0x20]
    add rsp, 0x30
    pop rbx
    ret
.blocked:
    mov dword [rsp+0x20], 0
    jmp .done
compat_unload_key_end:

compat_tick_name: db 'nativeClientTick',0
compat_tick_sig: db '()V',0
compat_stop_name: db 'adninStopClientPump',0
align 4, db 0
compat_register_unwind:
    db 1, compat_register.prolog-compat_register, 4, 0
    db compat_register.prolog-compat_register, 0xd2 ; 112-byte stack allocation
    db compat_register.p3-compat_register, 0x70   ; saved RDI
    db compat_register.p2-compat_register, 0x60   ; saved RSI
    db compat_register.p1-compat_register, 0x30   ; saved RBX
compat_unload_key_unwind:
    db 1, compat_unload_key.prolog-compat_unload_key, 2, 0
    db compat_unload_key.prolog-compat_unload_key, 0x52 ; 48-byte allocation
    db compat_unload_key.p1-compat_unload_key, 0x30
