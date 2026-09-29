; Replace only the two verified Overlay-header NewStringUTF callsites.
; Live caller ABI: RCX=JNIEnv, RDX=UTF8 title, RDI=column, R12=actual font.
; The returned local ref is consumed/deleted by the unchanged native caller.
db 'ADNHDR01'
dd header_string-$$, header_end-$$, header_unwind-$$

header_string:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    push r12
.p4:
    push r13
.p5:
    sub rsp, 0x40
.prolog:
    mov r13, r12
    mov r12, rdx
    mov rbx, rcx
    xor esi, esi
    test rdi, rdi
    jz .width_ready
    mov esi, [rdi+0x44]
.width_ready:
    xor edi, edi
    test rbx, rbx
    jz .done
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    mov [rsp+0x38], eax
    mov rcx, rbx
    mov rdx, r12
    mov rax, [rbx]
    call [rax+NEW_STRING_UTF]
    mov rdi, rax
    test rax, rax
    jz .done
    cmp byte [rsp+0x38], 0
    jne .done                       ; never clear an existing exception
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .done                       ; original NewStringUTF owns this failure
    cmp qword [gui_class], 0
    je .done
    mov [rsp+0x20], rdi
    mov [rsp+0x28], r13
    mov qword [rsp+0x30], 0
    mov [rsp+0x30], esi
    mov rcx, rbx
    mov rdx, [gui_class]
    lea r8, [header_name]
    lea r9, [header_sig]
    mov rax, [rbx]
    call [rax+GET_STATIC_METHOD]
    mov rsi, rax
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear_lookup
    test rsi, rsi
    jz .done
    mov rcx, rbx
    mov rdx, [gui_class]
    mov r8, rsi
    lea r9, [rsp+0x20]
    mov rax, [rbx]
    call [rax+0x3a0]                 ; CallStaticObjectMethodA
    mov r12, rax
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear_result
    test r12, r12
    jz .done
    cmp r12, rdi
    je .done                        ; identical handle: retain caller ownership
    mov rcx, rbx
    mov rdx, rdi
    mov rax, [rbx]
    call [rax+DELETE_LOCAL_REF]
    mov rdi, r12
    jmp .done
.clear_result:
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CLEAR]
    test r12, r12
    jz .done
    cmp r12, rdi
    je .done
    mov rcx, rbx
    mov rdx, r12
    mov rax, [rbx]
    call [rax+DELETE_LOCAL_REF]
    jmp .done
.clear_lookup:
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CLEAR]
.done:
    mov rax, rdi
    add rsp, 0x40
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
header_end:
header_name: db 'nativeOverlayHeader',0
header_sig: db '(Ljava/lang/String;Ljava/lang/Object;I)Ljava/lang/String;',0
align 4, db 0
header_unwind:
    db 1, header_string.prolog-header_string, 6, 0
    db header_string.prolog-header_string, 0x72
    db header_string.p5-header_string, 0xd0
    db header_string.p4-header_string, 0xc0
    db header_string.p3-header_string, 0x70
    db header_string.p2-header_string, 0x60
    db header_string.p1-header_string, 0x30
