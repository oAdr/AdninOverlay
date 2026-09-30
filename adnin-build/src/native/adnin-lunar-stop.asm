; Replaces the initializer's former system-wide End-key poll. Its attached
; JNIEnv remains at caller RSP+0x30. Only a client-authorized fresh End gesture
; may produce Java acknowledgement 1; background keys are never inspected here.
; Polling continues after key release so pending lifecycle work can drain.
; Missing requests, busy callbacks and JNI failures defer native unloading.
db 'ADNLST01'
dd lunar_stop-$$, lunar_stop_end-$$, lunar_stop_unwind-$$
lunar_stop:
    push rbx
.p1:
    sub rsp, 0x30
.prolog:
    mov rbx, [rsp+0x70]
    mov rcx, rbx
    lea rdx, [lunar_stop_name]
    lea r8, [lunar_stop_sig]
    xor r9d, r9d
    call invoke_int
    cmp eax, 1
    jne .blocked
    call input_detach            ; explicit request only; retain mapped input chain on failure
    jmp .done
.blocked:
    xor eax,eax
.done:
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
