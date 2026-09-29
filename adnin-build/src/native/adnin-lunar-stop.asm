; Called at the initializer's GetAsyncKeyState(End) poll. Its attached-thread
; JNIEnv remains at caller RSP+0x30. A pressed key reaches original cleanup
; only after Java's lifecycle lock has completed stop() and acknowledged 1.
; Failed/missing JNI callbacks return no key so native unloading is deferred.
db 'ADNLST01'
dd lunar_stop-$$, lunar_stop_end-$$, lunar_stop_unwind-$$
lunar_stop:
    push rbx
.p1:
    sub rsp, 0x30
.prolog:
    mov rbx, [rsp+0x70]
    call [rel $$-CODE_RVA+0x12f628]
    mov [rsp+0x20], eax
    test ax, ax
    jz .done
    mov rcx, rbx
    lea rdx, [lunar_stop_name]
    lea r8, [lunar_stop_sig]
    xor r9d, r9d
    call invoke_int
    cmp eax, 1
    je .done
    mov dword [rsp+0x20], 0
.done:
    mov eax, [rsp+0x20]
    add rsp, 0x30
    pop rbx
    ret
lunar_stop_end:
lunar_stop_name: db 'nativeStopGameModules',0
lunar_stop_sig: db '()I',0
align 4, db 0
lunar_stop_unwind:
    db 1, lunar_stop.prolog-lunar_stop, 2, 0
    db lunar_stop.prolog-lunar_stop, 0x52
    db lunar_stop.p1-lunar_stop, 0x30
