; The Number worker owns its cache mutex across synchronous HTTP. Only the
; three periodic UI-side lock calls use this optional nonblocking acquisition.
; Their existing JNE instructions go to reviewed no-lock cleanup/defer paths.
; No return address, native stack frame, worker lock or queued item is changed.
db 'ADNNPL01'
dd number_get_lock-$$, number_register_lock-$$, number_pop_lock-$$
dd number_lock-$$, number_lock_end-$$, number_lock_unwind-$$

number_get_lock:
number_register_lock:
    xor r10d, r10d
    jmp number_lock
number_pop_lock:
    mov r10d, 1
    jmp number_lock

number_lock:
    push rbx
.p1:
    push rsi
.p2:
    sub rsp, 0x28
.prolog:
    mov rbx, rcx
    mov esi, r10d
    ; Use the imported, already-loaded CRT. Lookup has no network or loader IO.
    lea rcx, [number_crt_name]
    call [rel $$-CODE_RVA+GET_MODULE_HANDLE_A_IAT]
    test rax, rax
    jz .fallback
    mov rcx, rax
    lea rdx, [number_trylock_name]
    call [rel $$-CODE_RVA+GET_PROC_ADDRESS_IAT]
    test rax, rax
    jz .fallback
    mov rcx, rbx
    call rax
    jmp .result
.fallback:
    ; Preserve availability on an unusual CRT; this path retains old waiting.
    mov rcx, rbx
    call [rel $$-CODE_RVA+REPLAY_MTX_LOCK_IAT]
    test eax, eax
    jz .done
    jmp .error
.result:
    test eax, eax
    jz .done
    cmp eax, 3
    je .defer
.error:
    ; Preserve std::mutex error semantics for every result except timed busy.
    mov ecx, 5
    call IMAGE_BASE+NATIVE_THROW_CPP_ERROR
.defer:
    test esi, esi
    jz .busy
    ; The pop routine uses BL as its local bool, then restores caller RBX in
    ; its existing epilogue. Getter's own busy path already clears BL.
    mov byte [rsp+0x30], 0
.busy:
    mov eax, 3                  ; busy acquisition safely defers this poll
.done:
    add rsp, 0x28
    pop rsi
    pop rbx
    ret
number_lock_end:
align 4, db 0
number_lock_unwind:
    db 1, number_lock.prolog-number_lock, 3, 0
    db number_lock.prolog-number_lock, 0x42
    db number_lock.p2-number_lock, 0x60
    db number_lock.p1-number_lock, 0x30
    dw 0
number_crt_name: db 'MSVCP140.dll',0
number_trylock_name: db '_Mtx_trylock',0
