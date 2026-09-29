; Adapt only the legacy official Hypixel URL shape at three reviewed HTTP
; calls. Other providers retain the original HTTP helper unchanged.
; All temporary strings are non-owning views over this frame. The existing
; caller owns its response string, including temporary Mojang UUID output.
db 'ADNHYP01'
dd hypixel_http-$$, hypixel_http_end-$$, hypixel_http_unwind-$$
dd api_decode_component-$$, api_decode_component_end-$$, api_decode_component_unwind-$$
dd api_refresh_failures-$$, api_refresh_failures_end-$$, api_refresh_failures_unwind-$$

%define HYP_URL_VIEW 0x20
%define HYP_HEADER_VIEW 0x40
%define HYP_NAME_VIEW 0x60
%define HYP_NOT_FOUND 0x80
%define HYP_KIND 0x88
%define HYP_URL_BUFFER 0x100
%define HYP_TARGET_BUFFER 0x180
%define HYP_HEADER_BUFFER 0x200
%define HYP_FRAME 0x348

hypixel_http:
    push rbx
.p1:
    push rbp
.p2:
    push rsi
.p3:
    push rdi
.p4:
    push r12
.p5:
    push r13
.p6:
    push r14
.p7:
    push r15
.p8:
    sub rsp,HYP_FRAME
.prolog:
    mov rbx,rcx                 ; original URL string
    mov r12,rdx                ; caller-owned response string
    mov r13,r8                 ; HTTP status output
    mov r14,r9                 ; optional original header string
    mov rbp,[rbx+16]
    cmp rbp,hypixel_legacy_prefix_end-hypixel_legacy_prefix
    jb .passthrough
    mov r15,rbx
    cmp qword [rbx+24],15
    jbe .url_pointer
    mov r15,[rbx]
.url_pointer:
    mov rsi,r15
    lea rdi,[hypixel_legacy_prefix]
    mov ecx,hypixel_legacy_prefix_end-hypixel_legacy_prefix
    repe cmpsb
    jne .passthrough
    ; A matched official URL is transformed, never forwarded with a key in
    ; its query string. Existing reviewed callers supply an empty header.
    cmp qword [r14+16],0
    jne .failed
    cmp qword [rel $$-CODE_RVA+API_KEY_LENGTH_RVA],0
    je .failed
    add rbp,r15                ; one past original URL
    mov r15,rsi                ; first encoded key byte
.find_key_end:
    cmp rsi,rbp
    jae .failed
    cmp byte [rsi],'&'
    je .key_end
    inc rsi
    jmp .find_key_end
.key_end:
    mov rdi,rsi
    lea rax,[rdi+6]
    cmp rax,rbp
    jae .failed
    cmp dword [rdi],0x69757526  ; &uui
    jne .check_name
    cmp word [rdi+4],0x3d64     ; d=
    jne .failed
    mov dword [rsp+HYP_KIND],0
    jmp .decode_key
.check_name:
    cmp dword [rdi],0x6d616e26  ; &nam
    jne .failed
    cmp word [rdi+4],0x3d65     ; e=
    jne .failed
    mov dword [rsp+HYP_KIND],1
.decode_key:
    ; Build an HTTP header privately; no credentials enter diagnostics.
    lea r8,[rsp+HYP_HEADER_BUFFER]
    mov rax,0x3a79654b2d495041 ; API-Key:
    mov [r8],rax
    mov byte [r8+8],' '
    add r8,9
    mov rcx,r15
    mov rdx,rdi
    sub rdx,r15
    mov r9d,257
    call api_decode_component
    test eax,eax
    jz .failed
    add eax,9
    lea rdx,[rsp+HYP_HEADER_BUFFER]
    mov [rsp+HYP_HEADER_VIEW],rdx
    mov qword [rsp+HYP_HEADER_VIEW+8],0
    mov [rsp+HYP_HEADER_VIEW+16],rax
    mov qword [rsp+HYP_HEADER_VIEW+24],0x11f
    lea rcx,[rdi+6]
    mov rdx,rbp
    sub rdx,rcx
    lea r8,[rsp+HYP_TARGET_BUFFER]
    mov r9d,128
    call api_decode_component
    test eax,eax
    jz .failed
    mov ecx,eax
    lea rsi,[rsp+HYP_TARGET_BUFFER]
    cmp dword [rsp+HYP_KIND],0
    je .uuid
    ; Resolve a username using the existing native Mojang resolver. Its output
    ; goes into the response object already protected by the caller's EH.
    cmp ecx,16
    ja .failed
    mov [rsp+HYP_NAME_VIEW],rsi
    mov qword [rsp+HYP_NAME_VIEW+8],0
    mov [rsp+HYP_NAME_VIEW+16],rcx
    mov qword [rsp+HYP_NAME_VIEW+24],127
    mov byte [rsp+HYP_NOT_FOUND],0
    lea rcx,[rsp+HYP_NAME_VIEW]
    mov rdx,r12
    lea r8,[rsp+HYP_NOT_FOUND]
    call IMAGE_BASE+NATIVE_MOJANG_RESOLVE
    test al,al
    jz .failed
    cmp byte [rsp+HYP_NOT_FOUND],0
    jne .missing_player
    mov rcx,[r12+16]
    mov rsi,r12
    cmp qword [r12+24],15
    jbe .uuid
    mov rsi,[r12]
.uuid:
    cmp rcx,32
    je .uuid_size_ok
    cmp rcx,36
    jne .failed
.uuid_size_ok:
    mov r15,rsi
    mov rbp,rcx
    lea rsi,[hypixel_uuid_prefix]
    lea rdi,[rsp+HYP_URL_BUFFER]
    mov ecx,hypixel_uuid_prefix_end-hypixel_uuid_prefix
    rep movsb
    mov rsi,r15
    mov rcx,rbp
    xor r8d,r8d
.copy_uuid:
    movzx eax,byte [rsi]
    inc rsi
    cmp al,'-'
    je .uuid_next
    cmp al,'0'
    jb .failed
    cmp al,'9'
    jbe .uuid_hex
    or al,0x20
    cmp al,'a'
    jb .failed
    cmp al,'f'
    ja .failed
.uuid_hex:
    cmp r8d,32
    jae .failed
    mov [rdi],al
    inc rdi
    inc r8d
.uuid_next:
    dec rcx
    jnz .copy_uuid
    cmp r8d,32
    jne .failed
    mov byte [rdi],0
    lea rax,[rsp+HYP_URL_BUFFER]
    mov [rsp+HYP_URL_VIEW],rax
    mov qword [rsp+HYP_URL_VIEW+8],0
    mov qword [rsp+HYP_URL_VIEW+16],hypixel_uuid_prefix_end-hypixel_uuid_prefix+32
    mov qword [rsp+HYP_URL_VIEW+24],127
    lea rcx,[rsp+HYP_URL_VIEW]
    mov rdx,r12
    mov r8,r13
    lea r9,[rsp+HYP_HEADER_VIEW]
    call IMAGE_BASE+NATIVE_HTTP_GET
    jmp .done
.missing_player:
    ; Preserve the original by-name parser's Nick/no-player distinction.
    ; The native resolver's reported no-player result becomes player:null.
    mov rcx,r12
    lea rdx,[hypixel_missing_player]
    mov r8d,hypixel_missing_player_end-hypixel_missing_player
    call IMAGE_BASE+NATIVE_STRING_ASSIGN_BYTES
    mov dword [r13],200
    mov eax,1
    jmp .done
.passthrough:
    mov rcx,rbx
    mov rdx,r12
    mov r8,r13
    mov r9,r14
    call IMAGE_BASE+NATIVE_HTTP_GET
    jmp .done
.failed:
    mov dword [r13],0
    mov qword [r12+16],0
    mov rax,r12
    cmp qword [r12+24],15
    jbe .clear_response
    mov rax,[r12]
.clear_response:
    mov byte [rax],0
    xor eax,eax
.done:
    ; Erase this temporary copy of the header before releasing the frame.
    mov r11,rax
    lea rdi,[rsp+HYP_HEADER_BUFFER]
    xor eax,eax
    mov ecx,0x120/8
    rep stosq
    mov rax,r11
    add rsp,HYP_FRAME
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbp
    pop rbx
    ret
hypixel_http_end:
align 4,db 0
hypixel_http_unwind:
    db 1,hypixel_http.prolog-hypixel_http,10,0
    db hypixel_http.prolog-hypixel_http,1
    dw HYP_FRAME/8
    db hypixel_http.p8-hypixel_http,0xf0
    db hypixel_http.p7-hypixel_http,0xe0
    db hypixel_http.p6-hypixel_http,0xd0
    db hypixel_http.p5-hypixel_http,0xc0
    db hypixel_http.p4-hypixel_http,0x70
    db hypixel_http.p3-hypixel_http,0x60
    db hypixel_http.p2-hypixel_http,0x50
    db hypixel_http.p1-hypixel_http,0x30
hypixel_legacy_prefix: db 'https://api.hypixel.net/v2/player?key='
hypixel_legacy_prefix_end:
    ; Separate length-bounded public fragments in binary string diagnostics.
    ; Neither terminator nor separator is part of the request/prefix length.
    db 0,10
hypixel_uuid_prefix: db 'https://api.hypixel.net/v2/player?uuid='
hypixel_uuid_prefix_end:
    db 0,10
hypixel_missing_player: db '{"success":true,"player":null}'
hypixel_missing_player_end:
    db 0

; RCX=input, RDX=length, R8=output, R9=capacity including NUL.
; EAX=nonzero decoded length or zero for invalid/empty. Only volatile GPRs
; are used. Reject control/space/non-ASCII bytes and malformed %-escapes so
; a key cannot create another header or alter the generated UUID query.
api_decode_component:
    lea r10,[rcx+rdx]
    xor edx,edx
.loop:
    cmp rcx,r10
    jae .end
    movzx eax,byte [rcx]
    inc rcx
    cmp al,'%'
    jne .character
    lea r11,[rcx+2]
    cmp r11,r10
    ja .invalid
    movzx eax,byte [rcx]
    cmp al,'0'
    jb .invalid
    cmp al,'9'
    jbe .first_digit
    or al,0x20
    sub al,'a'
    cmp al,5
    ja .invalid
    add al,10
    jmp .first_hex
.first_digit:
    sub al,'0'
.first_hex:
    mov r11d,eax
    shl r11d,4
    movzx eax,byte [rcx+1]
    cmp al,'0'
    jb .invalid
    cmp al,'9'
    jbe .second_digit
    or al,0x20
    sub al,'a'
    cmp al,5
    ja .invalid
    add al,10
    jmp .second_hex
.second_digit:
    sub al,'0'
.second_hex:
    or eax,r11d
    add rcx,2
.character:
    cmp al,0x20
    jbe .invalid
    cmp al,0x7f
    jae .invalid
    inc rdx
    cmp rdx,r9
    jae .invalid
    mov [r8+rdx-1],al
    jmp .loop
.end:
    mov byte [r8+rdx],0
    mov eax,edx
    ret
.invalid:
    xor eax,eax
    mov byte [r8],0
    ret
api_decode_component_end:
align 4,db 0
api_decode_component_unwind: db 1,0,0,0

; Called only from the configuration setter's changed branch while the
; original Stats mutex is still held. No list node, key or successful result
; is removed. Expiring failures lets existing queue logic retry immediately
; after a key/provider change instead of honoring an old 45-second failure.
api_refresh_failures:
    mov r10,[rel $$-CODE_RVA+API_STATS_CACHE_HEAD]
    test r10,r10
    jz .forward
    mov r11,[r10]
    mov r8,[rel $$-CODE_RVA+API_STATS_CACHE_HEAD+8]
.next:
    test r8,r8
    jz .forward
    cmp r11,r10
    je .forward
    test r11,r11
    jz .forward
    cmp byte [r11+0x38],0
    jne .advance
    cmp byte [r11+API_STATS_CACHE_SKYWARS_FLAG],0
    jne .advance
    cmp byte [r11+API_STATS_CACHE_DUELS_FLAG],0
    jne .advance
    mov qword [r11+0x30],-45001
.advance:
    mov r11,[r11]
    dec r8
    jmp .next
.forward:
    jmp IMAGE_BASE+NATIVE_SKIN_CONFIG_SET
api_refresh_failures_end:
align 4,db 0
api_refresh_failures_unwind: db 1,0,0,0
