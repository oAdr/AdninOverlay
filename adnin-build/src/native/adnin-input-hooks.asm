; Preserve the native input handler and its subclass predecessor as one binding.
; Ordinary messages add only an active counter and direct forwarding. Expensive
; ownership checks run on installation, destruction and explicit End unload.
; The private detach message executes on the actual window thread; the native
; worker requests a 50 ms timeout. Foreign outer subclasses retain our mapped DLL.
%ifndef ADNIN_STATE_RVA
%error ADNIN_STATE_RVA is required for input lifecycle storage
%endif
%define INPUT_STATE (ADNIN_STATE_RVA+64)
%define INPUT_ACTIVE (INPUT_STATE+0)
%define INPUT_PHASE (INPUT_STATE+4)
%define INPUT_DESTROYED (INPUT_STATE+8)
%define INPUT_THREAD (INPUT_STATE+12)
%define INPUT_IS_WINDOW (INPUT_STATE+16)
%define INPUT_WINDOW_THREAD (INPUT_STATE+24)
%define INPUT_GET_PROP (INPUT_STATE+32)
%define INPUT_SET_PROP (INPUT_STATE+40)
%define INPUT_REMOVE_PROP (INPUT_STATE+48)
%define INPUT_SEND_TIMEOUT (INPUT_STATE+56)
%define INPUT_REGISTER_MESSAGE (INPUT_STATE+64)
%define INPUT_MESSAGE (INPUT_STATE+72)
%define INPUT_READY (INPUT_STATE+76)
%define INPUT_DRAIN_HWND (INPUT_STATE+80)
%define INPUT_EVER_BOUND (INPUT_STATE+88)
%ifdef ADNIN_COMPAT_PROFILE
%define INPUT_HWND 0x17e9f8
%define INPUT_ORIGINAL 0x17e450
%define INPUT_LAST_CHECK 0x17ea60
%define INPUT_FINDER 0x10d40
%define INPUT_NATIVE_PROC 0x1f020
%define INPUT_TICK_IAT 0x132170
%define INPUT_PID_IAT 0x132180
%define INPUT_TID_IAT 0x132230
%define INPUT_GET_LONG_IAT 0x132660
%define INPUT_SET_LONG_IAT 0x132668
%else
%define INPUT_HWND 0x1a6968
%define INPUT_ORIGINAL 0x1a63d0
%define INPUT_LAST_CHECK 0x1a69d0
%define INPUT_FINDER 0x10f90
%define INPUT_NATIVE_PROC 0x1ec10
%define INPUT_TICK_IAT 0x12f1f0
%define INPUT_PID_IAT 0x12f200
%define INPUT_TID_IAT 0x12f220
%define INPUT_GET_LONG_IAT 0x12f658
%define INPUT_SET_LONG_IAT 0x12f660
%endif

align 8,db 0
db 'ADNINP01'
dd input_resolve-$$,input_resolve_end-$$,input_resolve_unwind-$$
dd input_maintain-$$,input_maintain_end-$$,input_maintain_unwind-$$
dd input_detach-$$,input_detach_end-$$,input_detach_unwind-$$
dd input_proc-$$,input_proc_end-$$,input_proc_unwind-$$
dd input_initialize-$$,input_initialize_end-$$,input_initialize_unwind-$$

input_initialize:
    sub rsp,0x28
.prolog:
    call input_maintain
%ifdef ADNIN_COMPAT_PROFILE
    cmp qword [rel $$-CODE_RVA+INPUT_HWND],0
    je .done
    ; Preserve compatibility's original post-binding renderer/import setup.
    add rsp,0x28
    jmp IMAGE_BASE+0x12240
.done:
%endif
    add rsp,0x28
    ret
input_initialize_end:

input_resolve:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp,0x20
.prolog:
    cmp dword [rel $$-CODE_RVA+INPUT_READY],1
    je .ready
    lea rcx,[input_user32]
    call [rel $$-CODE_RVA+GET_MODULE_HANDLE_A_IAT]
    test rax,rax
    jz .failed
    mov rbx,rax
    lea rsi,[input_api_names]
    xor edi,edi
.resolve:
    mov eax,[rsi+rdi*4]
    lea rdx,[$$]
    add rdx,rax
    mov rcx,rbx
    call [rel $$-CODE_RVA+GET_PROC_ADDRESS_IAT]
    test rax,rax
    jz .failed
    lea rdx,[rel $$-CODE_RVA+INPUT_IS_WINDOW]
    mov [rdx+rdi*8],rax
    inc edi
    cmp edi,7
    jb .resolve
    lea rcx,[input_property_name]
    call [rel $$-CODE_RVA+INPUT_REGISTER_MESSAGE]
    test eax,eax
    jz .failed
    mov [rel $$-CODE_RVA+INPUT_MESSAGE],eax
    mov dword [rel $$-CODE_RVA+INPUT_READY],1
.ready:
    mov eax,1
    jmp .done
.failed:
    xor eax,eax
.done:
    add rsp,0x20
    pop rdi
    pop rsi
    pop rbx
    ret
input_resolve_end:

input_maintain:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp,0x30
.prolog:
    cmp dword [rel $$-CODE_RVA+INPUT_PHASE],2
    jae .done                      ; detachment is permanent; no reacquisition
    call [rel $$-CODE_RVA+INPUT_TICK_IAT]
    mov rcx,rax
    sub rcx,[rel $$-CODE_RVA+INPUT_LAST_CHECK]
    cmp rcx,1000
    jb .done
    mov [rel $$-CODE_RVA+INPUT_LAST_CHECK],rax
    call input_resolve
    test eax,eax
    jz .done
    cmp dword [rel $$-CODE_RVA+INPUT_ACTIVE],0
    jne .done
    mov rbx,[rel $$-CODE_RVA+INPUT_HWND]
    test rbx,rbx
    jz .find
    mov rcx,rbx
    call [rel $$-CODE_RVA+INPUT_IS_WINDOW]
    test eax,eax
    jz .retire
    cmp dword [rel $$-CODE_RVA+INPUT_DESTROYED],1
    jne .done                      ; never rehook or move off a live binding
    mov rcx,rbx
    lea rdx,[input_property_name]
    call [rel $$-CODE_RVA+INPUT_GET_PROP]
    lea rdx,[rel $$-CODE_RVA+INPUT_STATE]
    cmp rax,rdx
    je .done                       ; WM_NCDESTROY has not completed yet
.retire:
    cmp dword [rel $$-CODE_RVA+INPUT_ACTIVE],0
    jne .done
    mov qword [rel $$-CODE_RVA+INPUT_HWND],0
    mov qword [rel $$-CODE_RVA+INPUT_ORIGINAL],0
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],0
    mov dword [rel $$-CODE_RVA+INPUT_DESTROYED],0
.find:
    call IMAGE_BASE+INPUT_FINDER
    test rax,rax
    jz .done
    mov rbx,rax
    mov rcx,rax
    lea rdx,[rsp+0x20]
    mov dword [rsp+0x20],0
    call [rel $$-CODE_RVA+INPUT_WINDOW_THREAD]
    test eax,eax
    jz .done
    mov esi,eax
    cmp dword [rel $$-CODE_RVA+INPUT_EVER_BOUND],0
    je .process_identity
    cmp esi,[rel $$-CODE_RVA+INPUT_THREAD]
    jne .done                      ; no unproven transfer to another GUI thread
.process_identity:
    call [rel $$-CODE_RVA+INPUT_PID_IAT]
    cmp eax,[rsp+0x20]
    jne .done                      ; FindWindow can see a different client
    mov rcx,rbx
    mov edx,-4
    call [rel $$-CODE_RVA+INPUT_GET_LONG_IAT]
    test rax,rax
    jz .done
    lea rdx,[input_proc]
    cmp rax,rdx
    je .done
    lea rdx,[rel $$-CODE_RVA+INPUT_NATIVE_PROC]
    cmp rax,rdx
    je .done
    mov rdi,rax
    mov rcx,rbx
    lea rdx,[input_property_name]
    lea r8,[rel $$-CODE_RVA+INPUT_STATE]
    call [rel $$-CODE_RVA+INPUT_SET_PROP]
    test eax,eax
    jz .done
    mov [rel $$-CODE_RVA+INPUT_HWND],rbx
    mov [rel $$-CODE_RVA+INPUT_ORIGINAL],rdi
    mov [rel $$-CODE_RVA+INPUT_THREAD],esi
    mov dword [rel $$-CODE_RVA+INPUT_DESTROYED],0
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],1
    mov rcx,rbx
    mov edx,-4
    lea r8,[input_proc]
    call [rel $$-CODE_RVA+INPUT_SET_LONG_IAT]
    test rax,rax
    jz .install_failed
    lea rdx,[input_proc]
    cmp rax,rdx
    je .done
    mov [rel $$-CODE_RVA+INPUT_ORIGINAL],rax
    mov dword [rel $$-CODE_RVA+INPUT_EVER_BOUND],1
    jmp .done
.install_failed:
    mov rcx,rbx
    lea rdx,[input_property_name]
    call [rel $$-CODE_RVA+INPUT_REMOVE_PROP]
    mov qword [rel $$-CODE_RVA+INPUT_HWND],0
    mov qword [rel $$-CODE_RVA+INPUT_ORIGINAL],0
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],0
.done:
    add rsp,0x30
    pop rdi
    pop rsi
    pop rbx
    ret
input_maintain_end:

; Called only after Java acknowledges an explicitly accepted End request.
; A failed/blocked detach retains JNI references and never authorizes cleanup.
input_detach:
    push rbx
.p1:
    sub rsp,0x50
.prolog:
    cmp dword [rel $$-CODE_RVA+INPUT_ACTIVE],0
    jne .blocked
    cmp dword [rel $$-CODE_RVA+INPUT_PHASE],3
    je .ready
    cmp dword [rel $$-CODE_RVA+INPUT_PHASE],2
    je .drain
    mov rbx,[rel $$-CODE_RVA+INPUT_HWND]
    test rbx,rbx
    jz .never_bound
    cmp dword [rel $$-CODE_RVA+INPUT_READY],1
    jne .blocked
    mov rcx,rbx
    call [rel $$-CODE_RVA+INPUT_IS_WINDOW]
    test eax,eax
    jz .blocked                    ; no GUI-thread completion barrier remains
    mov rcx,rbx
    lea rdx,[input_property_name]
    call [rel $$-CODE_RVA+INPUT_GET_PROP]
    lea rdx,[rel $$-CODE_RVA+INPUT_STATE]
    cmp rax,rdx
    je .owned_identity
    jmp .blocked
.owned_identity:
    mov rcx,rbx
    mov edx,-4
    call [rel $$-CODE_RVA+INPUT_GET_LONG_IAT]
    lea rdx,[input_proc]
    cmp rax,rdx
    jne .blocked                    ; an outer subclass still owns our pointer
    call [rel $$-CODE_RVA+INPUT_TID_IAT]
    cmp eax,[rel $$-CODE_RVA+INPUT_THREAD]
    je .blocked                     ; the native worker must not be GUI thread
    mov qword [rsp+0x38],0
    lea rax,[rsp+0x38]
    mov [rsp+0x30],rax
    mov dword [rsp+0x28],50
    mov dword [rsp+0x20],3           ; SMTO_BLOCK | SMTO_ABORTIFHUNG
    mov rcx,rbx
    mov edx,[rel $$-CODE_RVA+INPUT_MESSAGE]
    lea r8,[rel $$-CODE_RVA+INPUT_STATE]
    mov r9d,0x41444e49
    call [rel $$-CODE_RVA+INPUT_SEND_TIMEOUT]
    test rax,rax
    jz .blocked
    cmp qword [rsp+0x38],1
    jne .blocked
    cmp dword [rel $$-CODE_RVA+INPUT_PHASE],2
    jne .blocked
    cmp dword [rel $$-CODE_RVA+INPUT_ACTIVE],0
    jne .blocked
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],3
    jmp .ready
.drain:
    ; A timed-out detach might have finished just after timeout. A second
    ; ordinary synchronous message proves the former wrapper returned, closing
    ; the active-counter decrement/epilogue gap without polling the GUI thread.
    mov rbx,[rel $$-CODE_RVA+INPUT_DRAIN_HWND]
    test rbx,rbx
    jz .blocked
    mov rcx,rbx
    call [rel $$-CODE_RVA+INPUT_IS_WINDOW]
    test eax,eax
    jz .blocked
    mov rcx,rbx
    lea rdx,[rsp+0x40]
    mov dword [rsp+0x40],0
    call [rel $$-CODE_RVA+INPUT_WINDOW_THREAD]
    cmp eax,[rel $$-CODE_RVA+INPUT_THREAD]
    jne .blocked
    call [rel $$-CODE_RVA+INPUT_PID_IAT]
    cmp eax,[rsp+0x40]
    jne .blocked
    mov qword [rsp+0x38],0
    lea rax,[rsp+0x38]
    mov [rsp+0x30],rax
    mov dword [rsp+0x28],50
    mov dword [rsp+0x20],3
    mov rcx,rbx
    xor edx,edx                     ; WM_NULL to the restored original chain
    xor r8d,r8d
    xor r9d,r9d
    call [rel $$-CODE_RVA+INPUT_SEND_TIMEOUT]
    test rax,rax
    jz .blocked
    cmp dword [rel $$-CODE_RVA+INPUT_ACTIVE],0
    jne .blocked
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],3
    jmp .ready
.never_bound:
    cmp dword [rel $$-CODE_RVA+INPUT_EVER_BOUND],0
    jne .blocked                    ; old destroyed HWND needs a live same-thread replacement
    mov qword [rel $$-CODE_RVA+INPUT_HWND],0
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],3
.ready:
    mov eax,1
    jmp .done
.blocked:
    xor eax,eax
.done:
    add rsp,0x50
    pop rbx
    ret
input_detach_end:

input_proc:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    push rbp
.p4:
    push r12
.p5:
    sub rsp,0x40
.prolog:
    lock inc dword [rel $$-CODE_RVA+INPUT_ACTIVE]
    mov rbx,rcx
    mov esi,edx
    mov rdi,r8
    mov rbp,r9
    cmp edx,[rel $$-CODE_RVA+INPUT_MESSAGE]
    je .maybe_detach
.forward:
    mov rcx,rbx
    mov edx,esi
    mov r8,rdi
    mov r9,rbp
    call IMAGE_BASE+INPUT_NATIVE_PROC
    mov r12,rax
    cmp esi,0x82                    ; WM_NCDESTROY: no check on ordinary input
    jne .return
    cmp rbx,[rel $$-CODE_RVA+INPUT_HWND]
    jne .return
    call [rel $$-CODE_RVA+INPUT_TID_IAT]
    cmp eax,[rel $$-CODE_RVA+INPUT_THREAD]
    jne .return
    mov dword [rel $$-CODE_RVA+INPUT_DESTROYED],1
    jmp .return
.maybe_detach:
    lea rax,[rel $$-CODE_RVA+INPUT_STATE]
    cmp rdi,rax
    jne .forward
    cmp rbp,0x41444e49
    jne .forward
    xor r12d,r12d
    cmp rbx,[rel $$-CODE_RVA+INPUT_HWND]
    jne .return
    cmp dword [rel $$-CODE_RVA+INPUT_PHASE],1
    jne .return
    cmp dword [rel $$-CODE_RVA+INPUT_ACTIVE],1
    jne .return                     ; no nested/in-flight original callback
    call [rel $$-CODE_RVA+INPUT_TID_IAT]
    cmp eax,[rel $$-CODE_RVA+INPUT_THREAD]
    jne .return
    mov rcx,rbx
    lea rdx,[input_property_name]
    call [rel $$-CODE_RVA+INPUT_GET_PROP]
    lea rdx,[rel $$-CODE_RVA+INPUT_STATE]
    cmp rax,rdx
    jne .return
    mov rcx,rbx
    mov edx,-4
    call [rel $$-CODE_RVA+INPUT_GET_LONG_IAT]
    lea rdx,[input_proc]
    cmp rax,rdx
    jne .return
    mov r8,[rel $$-CODE_RVA+INPUT_ORIGINAL]
    test r8,r8
    jz .return
    cmp r8,rdx
    je .return
    mov rcx,rbx
    mov edx,-4
    call [rel $$-CODE_RVA+INPUT_SET_LONG_IAT]
    lea rdx,[input_proc]
    cmp rax,rdx
    jne .restore_raced_owner
    mov rcx,rbx
    lea rdx,[input_property_name]
    call [rel $$-CODE_RVA+INPUT_REMOVE_PROP]
    mov [rel $$-CODE_RVA+INPUT_DRAIN_HWND],rbx
    mov dword [rel $$-CODE_RVA+INPUT_PHASE],2
    mov qword [rel $$-CODE_RVA+INPUT_HWND],0
    mov r12d,1
    jmp .return
.restore_raced_owner:
    ; A third-party worker may have raced the checked swap. Return its handler
    ; if our replacement is still current, and retain this module regardless.
    test rax,rax
    jz .return
    mov [rsp+0x20],rax
    mov rcx,rbx
    mov edx,-4
    call [rel $$-CODE_RVA+INPUT_GET_LONG_IAT]
    cmp rax,[rel $$-CODE_RVA+INPUT_ORIGINAL]
    jne .return
    mov rcx,rbx
    mov edx,-4
    mov r8,[rsp+0x20]
    call [rel $$-CODE_RVA+INPUT_SET_LONG_IAT]
.return:
    lock dec dword [rel $$-CODE_RVA+INPUT_ACTIVE]
    mov rax,r12
    add rsp,0x40
    pop r12
    pop rbp
    pop rdi
    pop rsi
    pop rbx
    ret
input_proc_end:

input_user32: db 'user32.dll',0
input_property_name: db 'Adnin.Input.Binding.v23.59dbcfed28a44a62',0
input_api_names:
dd input_api_is_window-$$,input_api_window_thread-$$,input_api_get_prop-$$
dd input_api_set_prop-$$,input_api_remove_prop-$$,input_api_send_timeout-$$
dd input_api_register_message-$$
input_api_is_window: db 'IsWindow',0
input_api_window_thread: db 'GetWindowThreadProcessId',0
input_api_get_prop: db 'GetPropA',0
input_api_set_prop: db 'SetPropA',0
input_api_remove_prop: db 'RemovePropA',0
input_api_send_timeout: db 'SendMessageTimeoutA',0
input_api_register_message: db 'RegisterWindowMessageA',0

align 4,db 0
input_resolve_unwind:
    db 1,input_resolve.prolog-input_resolve,4,0
    db input_resolve.prolog-input_resolve,0x32
    db input_resolve.p3-input_resolve,0x70
    db input_resolve.p2-input_resolve,0x60
    db input_resolve.p1-input_resolve,0x30
input_maintain_unwind:
    db 1,input_maintain.prolog-input_maintain,4,0
    db input_maintain.prolog-input_maintain,0x52
    db input_maintain.p3-input_maintain,0x70
    db input_maintain.p2-input_maintain,0x60
    db input_maintain.p1-input_maintain,0x30
input_detach_unwind:
    db 1,input_detach.prolog-input_detach,2,0
    db input_detach.prolog-input_detach,0x92
    db input_detach.p1-input_detach,0x30
input_proc_unwind:
    db 1,input_proc.prolog-input_proc,6,0
    db input_proc.prolog-input_proc,0x72
    db input_proc.p5-input_proc,0xc0
    db input_proc.p4-input_proc,0x50
    db input_proc.p3-input_proc,0x70
    db input_proc.p2-input_proc,0x60
    db input_proc.p1-input_proc,0x30
align 4,db 0
input_initialize_unwind:
    db 1,input_initialize.prolog-input_initialize,1,0
    db input_initialize.prolog-input_initialize,0x42
    dw 0
