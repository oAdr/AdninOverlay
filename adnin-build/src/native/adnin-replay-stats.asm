; The original Replay branch formats placeholders and skips native API stats.
; Resolve only a current Replay actor via Java's bounded asynchronous cache.
; Keep the row's raw name, actor UUID, team and entity attribution unchanged.
; Submit the verified account UUID to the existing UUID job queue under its
; mutex. Do not register historical chat names or call any chat/Output producer.
db 'ADNRST02'
dd replay_stats-$$, replay_stats_end-$$, replay_stats_unwind-$$
dd replay_stats_queue-$$, replay_stats_queue_end-$$, replay_stats_queue_unwind-$$
dd replay_stats_cleanup-$$, replay_stats_cleanup_end-$$, replay_stats_cleanup_unwind-$$
dd replay_stats_frame_cleanup-$$, replay_stats_frame_cleanup_end-$$, replay_stats_frame_cleanup_unwind-$$

replay_stats:
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
    push r14
.p6:
    push r15
.p7:
    sub rsp, 0x2d0
.prolog:
    mov rdi, r13                 ; verified caller's JNIEnv
    mov rbx, rcx                 ; mutable row, raw name at +0x40
    mov dword [rsp+0x40], 0      ; live, fully constructed Stats scratch
    mov [rsp+0x48], rdi          ; cleanup handler's stable JNIEnv
    mov qword [rsp+0x50], 0      ; owned name local ref
    mov qword [rsp+0x58], 0      ; owned result local ref
    call IMAGE_BASE+REPLAY_PLACEHOLDER
    mov [rsp+0x30], rax
    xor r13d, r13d               ; optional result jstring
    xor r14d, r14d               ; raw Tab name jstring
    test rbx, rbx
    jz .done
    test rdi, rdi
    jz .done
    cmp byte [rel $$-CODE_RVA+MODE_FLAG_RVA], 0
    je .done
    cmp qword [gui_class], 0
    je .done
    mov r12, [rbx+0x50]
    test r12, r12
    jz .done
    cmp r12, 16
    ja .done
    lea rdx, [rbx+0x40]
    cmp qword [rbx+0x58], 16
    jb .name_data
    mov rdx, [rbx+0x40]
.name_data:
    test rdx, rdx
    jz .done
    xor ecx, ecx
.name_character:
    movzx eax, byte [rdx+rcx]
    cmp al, '_'
    je .name_valid
    cmp al, '0'
    jb .done
    cmp al, '9'
    jbe .name_valid
    or al, 0x20
    cmp al, 'a'
    jb .done
    cmp al, 'z'
    ja .done
.name_valid:
    inc ecx
    cmp ecx, r12d
    jb .name_character
    cmp byte [rdx+r12], 0
    jne .done
    mov rcx, rdi
    call new_string
    mov r14, rax
    mov [rsp+0x50], rax
    test rax, rax
    jz .done
    mov rcx, rdi
    mov rdx, [gui_class]
    lea r8, [replay_profile_name]
    lea r9, [replay_profile_sig]
    mov rax, [rdi]
    call [rax+GET_STATIC_METHOD]
    mov r15, rax
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    test r15, r15
    jz .cleanup
    mov [rsp+0x28], r14
    mov rcx, rdi
    mov rdx, [gui_class]
    mov r8, r15
    lea r9, [rsp+0x28]
    mov rax, [rdi]
    call [rax+0x3a0]             ; CallStaticObjectMethodA
    mov r13, rax
    mov [rsp+0x58], rax
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    test r13, r13
    jz .cleanup
    mov rcx, rdi
    mov rdx, r13
    mov rax, [rdi]
    call [rax+0x520]             ; GetStringLength
    mov r12d, eax
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    cmp r12d, 4                  ; explicit confirmed-not-found result only
    je .profile_length_valid
    cmp r12d, 38
    jb .cleanup
    cmp r12d, 53
    ja .cleanup
.profile_length_valid:
    lea rax, [rsp+0x80]
    mov ecx, 24
    xor edx, edx
.zero_profile:
    mov [rax], rdx
    add rax, 8
    dec ecx
    jnz .zero_profile
    lea rax, [rsp+0x80]
    mov [rsp+0x20], rax
    mov rcx, rdi
    mov rdx, r13
    xor r8d, r8d
    mov r9d, r12d
    mov rax, [rdi]
    call [rax+0x6e8]             ; GetStringUTFRegion, <=159 bytes
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    cmp r12d, 4
    jne .account_profile
    cmp dword [rsp+0x80], 0x4b43494e ; exact ASCII "NICK"
    jne .cleanup
    cmp byte [rsp+0x84], 0
    jne .cleanup
    mov rcx, rbx
    lea rdx, [replay_nick_string]
    mov r8d, 0xff5555
    call IMAGE_BASE+REPLAY_PLACEHOLDER ; same mode-aware normal Nick formatter
    jmp .cleanup                ; no UUID queue/getter for the sentinel
.account_profile:
    xor ecx, ecx
.profile_name:
    movzx eax, byte [rsp+0x80+rcx]
    cmp al, '|'
    je .separator
    cmp ecx, 16
    jae .cleanup
    cmp al, '_'
    je .profile_name_valid
    cmp al, '0'
    jb .cleanup
    cmp al, '9'
    jbe .profile_name_valid
    or al, 0x20
    cmp al, 'a'
    jb .cleanup
    cmp al, 'z'
    ja .cleanup
.profile_name_valid:
    inc ecx
    jmp .profile_name
.separator:
    test ecx, ecx
    jz .cleanup
    lea eax, [ecx+37]
    cmp eax, r12d
    jne .cleanup
    lea r15, [rsp+0x81+rcx]
    cmp byte [r15+36], 0
    jne .cleanup
    xor ecx, ecx
.uuid_character:
    movzx eax, byte [r15+rcx]
    cmp ecx, 8
    je .uuid_dash
    cmp ecx, 13
    je .uuid_dash
    cmp ecx, 18
    je .uuid_dash
    cmp ecx, 23
    je .uuid_dash
    cmp al, '0'
    jb .cleanup
    cmp al, '9'
    jbe .uuid_valid
    or al, 0x20
    cmp al, 'a'
    jb .cleanup
    cmp al, 'f'
    ja .cleanup
    jmp .uuid_valid
.uuid_dash:
    cmp al, '-'
    jne .cleanup
.uuid_valid:
    inc ecx
    cmp ecx, 36
    jb .uuid_character
    cmp byte [r15+14], '4'
    jne .cleanup
    mov al, [r15+19]
    or al, 0x20
    cmp al, '8'
    je .profile_valid
    cmp al, '9'
    je .profile_valid
    cmp al, 'a'
    je .profile_valid
    cmp al, 'b'
    jne .cleanup
.profile_valid:
    ; Read-only string view. The native queue/getter normalizes and copies it.
    mov [rsp+0x60], r15
    mov qword [rsp+0x68], 0
    mov qword [rsp+0x70], 36
    mov qword [rsp+0x78], 63
    lea rcx, [rsp+0x60]
    call replay_stats_queue
    ; Construct every empty native string with SSO capacity 15. A zero
    ; capacity can make the original short-string assignment allocate a
    ; heap pointer while still tagging its destination as inline storage.
    lea rax, [rsp+0x140]
    mov ecx, 0x180/8
    xor edx, edx
.zero_stats:
    mov [rax], rdx
    add rax, 8
    dec ecx
    jnz .zero_stats
%ifdef ADNIN_COMPAT_PROFILE
%assign string_offset 0x10
%rep 3
    mov qword [rsp+0x140+string_offset+0x18], 15
%assign string_offset string_offset+0x20
%endrep
    mov qword [rsp+0x140+0xd0+0x18], 15
%assign string_offset 0x120
%rep 3
    mov qword [rsp+0x140+string_offset+0x18], 15
%assign string_offset string_offset+0x20
%endrep
%else
    mov qword [rsp+0x140+0x70+0x18], 15
%assign string_offset 0xc0
%rep 3
    mov qword [rsp+0x140+string_offset+0x18], 15
%assign string_offset string_offset+0x20
%endrep
%endif
    mov dword [rsp+0x40], 1
    lea rcx, [rsp+0x60]
    lea rdx, [rsp+0x140]
    call IMAGE_BASE+NATIVE_STATS_GET
    test al, al
    jz .destroy_stats
    mov r12d, [rel $$-CODE_RVA+REPLAY_GAME_MODE]
    cmp r12d, 2
    je .skywars_ready
    cmp r12d, 3
    je .duels_ready
    cmp r12d, 4
    je .duels_ready
    cmp byte [rsp+0x148], 0
    je .destroy_stats
    jmp .format
.skywars_ready:
    cmp byte [rsp+0x140+STATS_SKYWARS_READY-0x40], 0
    je .destroy_stats
    jmp .format
.duels_ready:
    cmp byte [rsp+0x140+STATS_DUELS_READY-0x40], 0
    je .destroy_stats
.format:
    mov rcx, rbx
    lea rdx, [rsp+0x140]
    mov r8d, r12d
    xor r9d, r9d
    call IMAGE_BASE+REPLAY_STATS_FORMAT
.destroy_stats:
    mov dword [rsp+0x40], 0
    lea rcx, [rsp+0x140]
    call IMAGE_BASE+REPLAY_STATS_DESTROY
    jmp .cleanup
.clear:
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CLEAR]
.cleanup:
    test r13, r13
    jz .delete_name
    mov rcx, rdi
    mov rdx, r13
    mov qword [rsp+0x58], 0
    mov rax, [rdi]
    call [rax+DELETE_LOCAL_REF]
.delete_name:
    test r14, r14
    jz .done
    mov rcx, rdi
    mov rdx, r14
    mov qword [rsp+0x50], 0
    mov rax, [rdi]
    call [rax+DELETE_LOCAL_REF]
.done:
    mov rax, [rsp+0x30]
    add rsp, 0x2d0
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
replay_stats_end:
align 4, db 0
replay_stats_unwind:
    db 0x11, replay_stats.prolog-replay_stats, 9, 0 ; UHANDLER
    db replay_stats.prolog-replay_stats, 1
    dw 0x2d0/8
    db replay_stats.p7-replay_stats, 0xf0
    db replay_stats.p6-replay_stats, 0xe0
    db replay_stats.p5-replay_stats, 0xd0
    db replay_stats.p4-replay_stats, 0xc0
    db replay_stats.p3-replay_stats, 0x70
    db replay_stats.p2-replay_stats, 0x60
    db replay_stats.p1-replay_stats, 0x30
    dw 0
    dd CODE_RVA+replay_stats_frame_cleanup-$$

; Same mutex and UUID queue as the original public name+UUID entry, without
; registering prequeue/history names. Native enqueue retains TTL, in-flight
; deduplication, worker throttling and configured API access.
replay_stats_queue:
    push rbx
.p1:
    sub rsp, 0x30
.prolog:
    mov rbx, rcx
    mov dword [rsp+0x20], 0
    lea rcx, [rel $$-CODE_RVA+REPLAY_STATS_MUTEX]
    call [rel $$-CODE_RVA+REPLAY_MTX_LOCK_IAT]
    test eax, eax
    jnz .done
    mov dword [rsp+0x20], 1
    mov rcx, rbx
    call IMAGE_BASE+REPLAY_STATS_ENQUEUE
    mov dword [rsp+0x20], 0
    lea rcx, [rel $$-CODE_RVA+REPLAY_STATS_MUTEX]
    call [rel $$-CODE_RVA+REPLAY_MTX_UNLOCK_IAT]
.done:
    add rsp, 0x30
    pop rbx
    ret
replay_stats_queue_end:
align 4, db 0
replay_stats_queue_unwind:
    db 0x11, replay_stats_queue.prolog-replay_stats_queue, 2, 0 ; UHANDLER
    db replay_stats_queue.prolog-replay_stats_queue, 0x52
    db replay_stats_queue.p1-replay_stats_queue, 0x30
    dd CODE_RVA+replay_stats_cleanup-$$

; Release our acquired lock during native exception unwinding. RDX is the
; establisher RSP after the queue wrapper's fixed allocation, never row data.
replay_stats_cleanup:
    sub rsp, 0x28
.prolog:
    test dword [rcx+4], 6
    jz .done
    cmp dword [rdx+0x20], 1
    jne .done
    mov dword [rdx+0x20], 0
    lea rcx, [rel $$-CODE_RVA+REPLAY_STATS_MUTEX]
    call [rel $$-CODE_RVA+REPLAY_MTX_UNLOCK_IAT]
.done:
    mov eax, 1                 ; ExceptionContinueSearch disposition
    add rsp, 0x28
    ret
replay_stats_cleanup_end:
align 4, db 0
replay_stats_cleanup_unwind:
    db 1, replay_stats_cleanup.prolog-replay_stats_cleanup, 1, 0
    db replay_stats_cleanup.prolog-replay_stats_cleanup, 0x42
    dw 0

; Release main-frame temporaries if native stats allocation/formatting
; unwinds. Do not swallow exceptions, free the UUID view or alter the row.
replay_stats_frame_cleanup:
    push rbx
.p1:
    sub rsp, 0x20
.prolog:
    test dword [rcx+4], 6
    jz .done
    mov rbx, rdx
    cmp dword [rbx+0x40], 1
    jne .refs
    mov dword [rbx+0x40], 0
    lea rcx, [rbx+0x140]
    call IMAGE_BASE+REPLAY_STATS_DESTROY
.refs:
    mov rcx, [rbx+0x48]
    test rcx, rcx
    jz .done
    mov rdx, [rbx+0x58]
    test rdx, rdx
    jz .name
    mov qword [rbx+0x58], 0
    mov rax, [rcx]
    call [rax+DELETE_LOCAL_REF]
.name:
    mov rcx, [rbx+0x48]
    mov rdx, [rbx+0x50]
    test rdx, rdx
    jz .done
    mov qword [rbx+0x50], 0
    mov rax, [rcx]
    call [rax+DELETE_LOCAL_REF]
.done:
    mov eax, 1
    add rsp, 0x20
    pop rbx
    ret
replay_stats_frame_cleanup_end:
align 4, db 0
replay_stats_frame_cleanup_unwind:
    db 1, replay_stats_frame_cleanup.prolog-replay_stats_frame_cleanup, 2, 0
    db replay_stats_frame_cleanup.prolog-replay_stats_frame_cleanup, 0x32
    db replay_stats_frame_cleanup.p1-replay_stats_frame_cleanup, 0x30

replay_profile_name: db 'nativeReplayProfile',0
replay_profile_sig: db '(Ljava/lang/String;)Ljava/lang/String;',0

align 8, db 0
replay_nick_string:
    db 0xc2,0xa7,'c[NICK]',0    ; original normal-match presentation: section-c [NICK]
    times 16-($-replay_nick_string) db 0
    dq 9,15                    ; read-only valid MSVC SSO string
