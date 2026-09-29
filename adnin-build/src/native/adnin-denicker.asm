; Included in adnin-bridge.asm. Only the verified Number Denicker result-read
; call inside the original filtered UUID-v1 player path is redirected here.
; Bot supplies a cached identity; all statistics come from existing native
; asynchronous API queues and their native Stats cache/copy routines.
; No GameProfile, player UUID, original identity cache or skin-learning input
; is changed. Original Number and enabled Skin Denicker identities win.
denicker_result:
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
    sub rsp, DENICKER_FRAME
.prolog:
    mov rdi, r13                    ; JNIEnv from this verified caller
    mov rbx, rcx                    ; row.name std::string (row + 0x40)
    mov rsi, rdx                    ; original Number Denicker result
    xor r13d, r13d                  ; local returned profile jstring
    xor r14d, r14d                  ; local nick jstring
    call IMAGE_BASE+NATIVE_DENICKER
    mov [rsp+0x30], rax
    test rbx, rbx
    jz .done
    test rsi, rsi
    jz .done
    cmp byte [rsi], 0
    je .eligibility
    cmp qword [rsi+0x30], 0         ; preserve known identity before stats arrive
    jne .done
.eligibility:
    ; Preserve UUID-v1 ordinary eligibility. Replay must independently pass
    ; the same current-roster cached-NICK predicate as the upstream gate.
    cmp byte [rel $$-CODE_RVA+MODE_FLAG_RVA], 0
    je .ordinary_uuid
    mov rcx, rdi
    mov rdx, rbx
    call replay_nick_name
    test eax, eax
    jz .done
    jmp .eligible
.ordinary_uuid:
    lea rcx, [rbx+0x108]            ; read-only row UUID (row + 0x148)
    call IMAGE_BASE+NATIVE_UUID_VERSION
    cmp eax, 1
    jne .done
.eligible:
    test rdi, rdi
    jz .done
    cmp qword [gui_class], 0
    je .done
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .done                      ; never clear an existing exception
    call IMAGE_BASE+NATIVE_SKIN_ENABLED
    test al, al
    jz .nick
    ; Getter uses native string move/copy helpers; a zeroed destination is safe.
    ; Its matching native destructor owns every copied member.
    lea rax, [rsp+0x140]
    mov ecx, SKIN_ZERO_QWORDS
    xor edx, edx
.zero_skin:
    mov [rax], rdx
    add rax, 8
    dec ecx
    jnz .zero_skin
    mov dword [rsp+SKIN_FOUND_STACK], 0
    mov rcx, rbx
    lea rdx, [rsp+0x140]
    call IMAGE_BASE+NATIVE_SKIN_GET
    test al, al
    jz .destroy_skin
    cmp dword [rsp+0x140], 3
    jne .destroy_skin
    cmp qword [rsp+0x158], 0
    je .destroy_skin
    mov dword [rsp+SKIN_FOUND_STACK], 1
.destroy_skin:
    lea rcx, [rsp+0x140]
    call IMAGE_BASE+NATIVE_SKIN_DESTROY
    cmp dword [rsp+SKIN_FOUND_STACK], 0
    jne .done
.nick:
    mov r12, [rbx+16]
    test r12, r12
    jz .done
    cmp r12, 16
    ja .done
    mov rdx, rbx
    cmp qword [rbx+24], 16
    jb .nick_data
    mov rdx, [rbx]
.nick_data:
    test rdx, rdx
    jz .done
    xor ecx, ecx
.nick_character:
    movzx eax, byte [rdx+rcx]
    cmp al, '_'
    je .nick_valid
    cmp al, '0'
    jb .done
    cmp al, '9'
    jbe .nick_valid
    or al, 0x20
    cmp al, 'a'
    jb .done
    cmp al, 'z'
    ja .done
.nick_valid:
    inc ecx
    cmp ecx, r12d
    jb .nick_character
    mov rcx, rdi
    call new_string
    mov r14, rax
    test rax, rax
    jz .done
    mov rcx, rdi
    mov rdx, [gui_class]
    lea r8, [denicker_profile_name]
    lea r9, [denicker_profile_sig]
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
    call [rax+0x3a0]                ; CallStaticObjectMethodA
    mov r13, rax
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
    call [rax+0x520]                ; GetStringLength (UTF-16 code units)
    mov r12d, eax
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    cmp r12d, 38                    ; one name char + '|' + dashed UUID
    jb .cleanup
    cmp r12d, 53                    ; max Minecraft name 16 + 1 + 36
    ja .cleanup
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
    call [rax+0x6e8]                ; GetStringUTFRegion, <= 159 bytes
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
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
    jne .cleanup                   ; reject non-ASCII/embedded separators
    mov byte [rsp+0x80+rcx], 0
    lea r15, [rsp+0x81+rcx]
    mov [rsp+0x50], rcx
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
    ; Const MSVC string views into our terminated stack buffer. Native input
    ; helpers only read/copy them; these views must never be destructed.
    lea rax, [rsp+0x80]
    mov [rsp+0x40], rax
    mov qword [rsp+0x48], 0
    mov qword [rsp+0x58], 63
    mov [rsp+0x60], r15
    mov qword [rsp+0x68], 0
    mov qword [rsp+0x70], 36
    mov qword [rsp+0x78], 63
    cmp byte [rel $$-CODE_RVA+MODE_FLAG_RVA], 0
    je .ordinary_queue
    lea rcx, [rsp+0x60]
    call replay_stats_queue         ; no historical-name registration in Replay
    jmp .read_stats
.ordinary_queue:
    lea rcx, [rsp+0x40]
    mov edx, -1
    call IMAGE_BASE+NATIVE_REGISTER_NAME ; locked, deduplicated native registration
    lea rcx, [rsp+0x40]
    lea rdx, [rsp+0x60]
    call IMAGE_BASE+NATIVE_QUEUE_UUID ; locked native UUID statistics queue
.read_stats:
    lea rcx, [rsp+0x60]
    lea rdx, [rsi+0x40]
    call IMAGE_BASE+NATIVE_STATS_GET ; deep-copy existing native Stats result
    test al, al
    jz .cleanup
    ; The original normal-player branch uses these same mode-ready flags.
    cmp byte [rsi+0x48], 0
    jne .publish
    cmp byte [rsi+STATS_SKYWARS_READY], 0
    jne .publish
    cmp byte [rsi+STATS_DUELS_READY], 0
    je .cleanup
.publish:
    lea rcx, [rsi+0x20]
    lea rdx, [rsp+0x80]
    call IMAGE_BASE+NATIVE_STRING_ASSIGN ; native-owned real-name std::string
    lea rcx, [rsi+8]
    lea rdx, [rsp+0x40]
    mov r8d, 1
    call IMAGE_BASE+NATIVE_CANDIDATES_COPY ; native-owned singleton candidate vector
    mov byte [rsi], 1
    mov byte [rsi+1], 0
    mov byte [rsi+2], 0
    mov dword [rsi+4], 1
    mov byte [rsi+NUMBER_STATS_READY], 1
    mov qword [rsp+0x30], 1
    jmp .cleanup
.clear:
    mov rcx, rdi
    mov rax, [rdi]
    call [rax+EXCEPTION_CLEAR]
.cleanup:
    test r13, r13
    jz .delete_nick
    mov rcx, rdi
    mov rdx, r13
    mov rax, [rdi]
    call [rax+DELETE_LOCAL_REF]
.delete_nick:
    test r14, r14
    jz .done
    mov rcx, rdi
    mov rdx, r14
    mov rax, [rdi]
    call [rax+DELETE_LOCAL_REF]
.done:
    mov rax, [rsp+0x30]
    add rsp, DENICKER_FRAME
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
denicker_end:

denicker_profile_name: db 'nativeBotProfile',0
denicker_profile_sig: db '(Ljava/lang/String;)Ljava/lang/String;',0
align 4, db 0
denicker_unwind:
    db 1, denicker_result.prolog-denicker_result, 9, 0
    db denicker_result.prolog-denicker_result, 1 ; UWOP_ALLOC_LARGE, OpInfo 0
    dw DENICKER_FRAME/8
    db denicker_result.p7-denicker_result, 0xf0
    db denicker_result.p6-denicker_result, 0xe0
    db denicker_result.p5-denicker_result, 0xd0
    db denicker_result.p4-denicker_result, 0xc0
    db denicker_result.p3-denicker_result, 0x70
    db denicker_result.p2-denicker_result, 0x60
    db denicker_result.p1-denicker_result, 0x30
    dw 0
