; Optional Java callbacks for the recovered x64 DLL.  No absolute pointers are
; embedded: RIP-relative data and rel32 transfers keep the bridge ASLR-safe.
; The original chat routines remain the fallback when Java does not render a
; localized allowlisted message. Base Tab drawing remains native-owned.
bits 64
default rel
%ifndef IMAGE_BASE
%define IMAGE_BASE 0x180000000
%endif
%ifndef CODE_RVA
%error CODE_RVA must name the appended executable section
%endif
org IMAGE_BASE + CODE_RVA
%include "adnin-runtime.inc"

gui_class equ $$ - CODE_RVA + GUI_CLASS_RVA
%define EXCEPTION_CHECK 0x720
%define EXCEPTION_CLEAR 0x88
%define DELETE_LOCAL_REF 0xb8
%define GET_STATIC_METHOD 0x388
%define NEW_STRING_UTF 0x538
%define CALL_STATIC_VOID_A 0x478
%define CALL_STATIC_INT_A 0x418

db 'ADNINB01'
dd 2, 160, payload_end-$$
dd output_plain-$$, output_json-$$, prelayout-$$, render_rows-$$
dd output_common-$$, output_end-$$, output_unwind-$$
dd invoke_common-$$, invoke_end-$$, invoke_unwind-$$
dd new_string-$$, new_string_end-$$, new_string_unwind-$$
dd prelayout-$$, prelayout_end-$$, prelayout_unwind-$$
dd render_rows-$$, render_end-$$, render_unwind-$$
dd seraph_prefix-$$
dd denicker_result-$$
dd denicker_result-$$, denicker_end-$$, denicker_unwind-$$
dd metrics_value-$$, metrics_end-$$, metrics_unwind-$$
dd match_started-$$, match_started-$$, match_end-$$, match_unwind-$$
dd column_catalog-$$, column_catalog-$$, column_catalog_end-$$, column_catalog_unwind-$$
times 160-($-$$) db 0

; Only the two prefix-construction calls inside the verified Seraph producer
; use this leaf constructor. The 15-byte UTF8 label fits MSVC's 16-byte SSO
; storage together with its NUL, requiring neither allocation nor JNI calls.
seraph_prefix:
    mov rax, rcx
    movdqu xmm0, [seraph_label]
    movdqu [rcx], xmm0
    mov qword [rcx+16], 15
    mov qword [rcx+24], 15
    ret

output_plain:
    xor r10d, r10d
    jmp output_common
output_json:
    mov r10d, 1
    jmp output_common
output_plain_tags:
    mov r10d, 2
    jmp output_common
output_json_tags:
    mov r10d, 3
    jmp output_common
output_plain_denick:
    mov r10d, 6
    jmp output_common
output_json_denick:
    mov r10d, 7
    jmp output_common
output_plain_local:
    mov r10d, 8
    jmp output_common

; RCX env, RDX Minecraft, R8 Minecraft class, R9 MSVC std::string*.
output_common:
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
    mov rbx, rcx
    mov rsi, rdx
    mov rdi, r8
    mov r12, r9
    mov r13d, r10d
    test rbx, rbx
    jz .restore
    cmp qword [gui_class], 0
    je .restore
    test r12, r12
    jz .restore
    mov rax, [r12+16]
    test rax, rax
    jz .restore
    cmp rax, 16384
    ja .restore
    mov rdx, r12
    cmp qword [r12+24], 16
    jb .data_ready
    mov rdx, [r12]
.data_ready:
    mov rcx, rbx
    call new_string
    test rax, rax
    jz .restore
    mov [rsp+0x20], rax
    mov qword [rsp+0x28], 0
    mov eax, r13d
    and eax, 1
    mov [rsp+0x28], eax
    mov eax, r13d
    shr eax, 1
    mov [rsp+0x30], rax
    mov rcx, rbx
    lea rdx, [output_name]
    lea r8, [output_sig]
    lea r9, [rsp+0x20]
    call invoke_int
    mov [rsp+0x38], eax
    mov rcx, rbx
    mov rdx, [rsp+0x20]
    mov rax, [rbx]
    call [rax+DELETE_LOCAL_REF]
    cmp dword [rsp+0x38], 1
    je .consumed
.restore:
    mov rcx, rbx
    mov rdx, rsi
    mov r8, rdi
    mov r9, r12
    test r13b, 1
    jnz .json
    add rsp, 0x40
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    jmp IMAGE_BASE+NATIVE_PLAIN
.json:
    add rsp, 0x40
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    jmp IMAGE_BASE+NATIVE_JSON
.consumed:
    mov eax, 1                   ; original local renderer returns bool success
    add rsp, 0x40
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
output_end:

invoke_void:
    xor r10d, r10d
    jmp invoke_common
invoke_int:
    mov r10d, 1
    jmp invoke_common

; Optional static callback. RCX env, RDX name, R8 signature, R9 jvalue[].
; Existing exceptions are left pending. Only exceptions raised by this bridge
; are cleared. Return zero on an unavailable callback, otherwise the Java int.
invoke_common:
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
    sub rsp, 0x20
.prolog:
    mov rbx, rcx
    mov rsi, rdx
    mov rdi, r8
    mov r12, r9
    mov r13d, r10d
    test rbx, rbx
    jz .zero
    cmp qword [gui_class], 0
    je .zero
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .zero
    mov rcx, rbx
    mov rdx, [gui_class]
    mov r8, rsi
    mov r9, rdi
    mov rax, [rbx]
    call [rax+GET_STATIC_METHOD]
    mov rdi, rax
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    test rdi, rdi
    jz .zero
    mov rcx, rbx
    mov rdx, [gui_class]
    mov r8, rdi
    mov r9, r12
    mov rax, [rbx]
    test r13d, r13d
    jnz .integer
    call [rax+CALL_STATIC_VOID_A]
    xor esi, esi
    jmp .check_result
.integer:
    call [rax+CALL_STATIC_INT_A]
    mov esi, eax
.check_result:
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .clear
    mov eax, esi
    jmp .done
.clear:
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CLEAR]
.zero:
    xor eax, eax
.done:
    add rsp, 0x20
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
invoke_end:

; Create a local jstring without disturbing a pre-existing Java exception.
new_string:
    push rbx
.p1:
    push rsi
.p2:
    push rdi
.p3:
    sub rsp, 0x20
.prolog:
    mov rbx, rcx
    mov rsi, rdx
    xor edi, edi
    test rbx, rbx
    jz .done
    test rsi, rsi
    jz .done
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .done
    mov rcx, rbx
    mov rdx, rsi
    mov rax, [rbx]
    call [rax+NEW_STRING_UTF]
    mov rdi, rax
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jz .done
    mov rcx, rbx
    mov rax, [rbx]
    call [rax+EXCEPTION_CLEAR]
    test rdi, rdi
    jz .done
    mov rcx, rbx
    mov rdx, rdi
    mov rax, [rbx]
    call [rax+DELETE_LOCAL_REF]
    xor edi, edi
.done:
    mov rax, rdi
    add rsp, 0x20
    pop rdi
    pop rsi
    pop rbx
    ret
new_string_end:

; Keep the existing bridge entry while allowing the original layout to size
; and position every column from the ordered native descriptor vector.
prelayout:
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
    call IMAGE_BASE+NATIVE_PRELAYOUT
    add rsp, 0x40
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
prelayout_end:

; RCX context, RDX env, R8 FontRenderer, R9 model, fifth argument layout.
; Draw original rows, then extension cells at their native column positions.
; The native header and layout already consume the same ordered descriptors.
; Each callback owns three local strings: name, final kills and styled stars.
render_rows:
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
    sub rsp, 0x80
.prolog:
    mov rbx, rcx
    mov rsi, rdx
    mov rdi, r8
    mov r12, r9
    mov r13, [rsp+0xe0]
    mov [rsp+0x20], r13
    call IMAGE_BASE+NATIVE_RENDER
    mov [rsp+0x70], rax
    test rbx, rbx
    jz .done
    test rsi, rsi
    jz .done
    test r12, r12
    jz .done
    test r13, r13
    jz .done
    cmp qword [gui_class], 0
    je .done
    mov qword [rsp+0x60], 0
    mov qword [rsp+0x68], 0
    mov r8, [r12+0xd0]
    mov r9, [r12+0xd8]
    test r8, r8
    jz .done
    cmp r9, r8
    jb .done
    mov rax, r9
    sub rax, r8
    xor edx, edx
    mov ecx, 0x58
    div rcx
    test rdx, rdx
    jnz .done
    cmp rax, 128
    ja .done
.column_loop:
    cmp r8, r9
    jae .columns_ready
    cmp byte [r8+0x4c], 0
    je .column_next
    mov eax, [r8+0x44]
    test eax, eax
    jle .column_next
    cmp eax, 4096
    ja .column_next
    cmp dword [r8+0x40], 65536
    ja .column_next
    mov rcx, [r8+0x10]
    cmp rcx, 4
    je .column_key
    cmp rcx, 6
    jne .column_next
.column_key:
    lea rdx, [r8]
    cmp qword [r8+0x18], 16
    jb .column_data
    mov rdx, [r8]
    test rdx, rdx
    jz .column_next
.column_data:
    cmp rcx, 4
    jne .column_urchin
    cmp dword [rdx], 0x766c6b66
    jne .column_next
    cmp byte [rdx+4], 0
    jne .column_next
    cmp dword [rsp+0x64], 0
    jne .column_next
    cvtsi2ss xmm0, eax
    movss [rsp+0x64], xmm0
    mov eax, [r8+0x40]
    add eax, [r13]
    cvtsi2ss xmm0, eax
    movss [rsp+0x60], xmm0
    jmp .column_next
.column_urchin:
    cmp dword [rdx], 0x68637275
    jne .column_next
    cmp word [rdx+4], 0x6e69
    jne .column_next
    cmp byte [rdx+6], 0
    jne .column_next
    cmp dword [rsp+0x6c], 0
    jne .column_next
    cvtsi2ss xmm0, eax
    movss [rsp+0x6c], xmm0
    mov eax, [r8+0x40]
    add eax, [r13]
    cvtsi2ss xmm0, eax
    movss [rsp+0x68], xmm0
.column_next:
    add r8, 0x58
    jmp .column_loop
.columns_ready:
    mov eax, [rsp+0x64]
    or eax, [rsp+0x6c]
    jz .done
    mov rcx, rsi
    mov rax, [rsi]
    call [rax+EXCEPTION_CHECK]
    test al, al
    jnz .done
    mov r14, [r12]
    mov r15, [r12+8]
    test r14, r14
    jz .done
    cmp r15, r14
    jb .done
    mov rax, r15
    sub rax, r14
    xor edx, edx
    mov ecx, 0x178
    div rcx
    test rdx, rdx
    jnz .done
    cmp rax, 1000
    ja .done
    mov r12d, [rbx+0x240]
    test r12d, r12d
    jle .done
    cmp r12d, 256
    ja .done
    xor edi, edi
.loop:
    cmp r14, r15
    jae .done
    mov rax, [r14+0x50]
    test rax, rax
    jz .next
    cmp rax, 16384
    ja .next
    lea rdx, [r14+0x40]
    cmp qword [r14+0x58], 16
    jb .data_ready
    mov rdx, [r14+0x40]
.data_ready:
    mov rcx, rsi
    call new_string
    test rax, rax
    jz .next
    mov [rsp+0x20], rax
    mov qword [rsp+0x28], 0
    mov qword [rsp+0x30], 0
    mov qword [rsp+0x38], 0
    mov qword [rsp+0x40], 0
    mov qword [rsp+0x48], 0
    mov eax, edi
    imul eax, r12d
    add eax, [r13+20]
    cvtsi2ss xmm0, eax
    movss [rsp+0x28], xmm0
    mov eax, [rsp+0x60]
    mov [rsp+0x30], eax
    mov eax, [rsp+0x64]
    mov [rsp+0x38], eax
    mov eax, [rsp+0x68]
    mov [rsp+0x40], eax
    mov eax, [rsp+0x6c]
    mov [rsp+0x48], eax
    mov rcx, rsi
    mov rdx, r14
    lea r8, [metrics_final_key]
    mov r9, METRICS_FINAL_HASH
    call metrics_value
    mov [rsp+0x50], rax
    mov rcx, rsi
    mov rdx, r14
    lea r8, [metrics_stars_key]
    mov r9, METRICS_STARS_HASH
    call metrics_value
    mov [rsp+0x58], rax
    mov rcx, rsi
    lea rdx, [row_name]
    lea r8, [row_sig]
    lea r9, [rsp+0x20]
    call invoke_void
    mov rcx, rsi
    mov rdx, [rsp+0x20]
    mov rax, [rsi]
    call [rax+DELETE_LOCAL_REF]
    mov rdx, [rsp+0x50]
    test rdx, rdx
    jz .delete_stars
    mov rcx, rsi
    mov rax, [rsi]
    call [rax+DELETE_LOCAL_REF]
.delete_stars:
    mov rdx, [rsp+0x58]
    test rdx, rdx
    jz .next
    mov rcx, rsi
    mov rax, [rsi]
    call [rax+DELETE_LOCAL_REF]
.next:
    add r14, 0x178
    inc edi
    jmp .loop
.done:
    mov rax, [rsp+0x70]
    add rsp, 0x80
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    ret
render_end:

output_name: db 'nativeRenderGeneratedEvent',0
output_sig: db '(Ljava/lang/String;ZI)I',0
db 'ADNOUT02'
dd output_plain_tags-$$, output_json_tags-$$
dd output_plain_local-$$
dd output_plain_denick-$$, output_json_denick-$$
row_name: db 'nativeOrderedOverlayRow',0
row_sig: db '(Ljava/lang/String;FFFFFLjava/lang/String;Ljava/lang/String;)V',0
seraph_label: db 0xc2,0xa7,'d[Seraph]',0xc2,0xa7,'r ',0

; UNWIND_INFO: version 1, no handler; codes are reverse prologue order.
%macro UNWIND5 3
align 4, db 0
%1:
    db 1, %2.prolog-%2, 6, 0
    db %2.prolog-%2, (((%3-8)/8)<<4)|2
    db %2.p5-%2, 0xd0
    db %2.p4-%2, 0xc0
    db %2.p3-%2, 0x70
    db %2.p2-%2, 0x60
    db %2.p1-%2, 0x30
%endmacro
UNWIND5 output_unwind, output_common, 0x40
UNWIND5 invoke_unwind, invoke_common, 0x20
align 4, db 0
new_string_unwind:
    db 1, new_string.prolog-new_string, 4, 0
    db new_string.prolog-new_string, 0x32
    db new_string.p3-new_string, 0x70
    db new_string.p2-new_string, 0x60
    db new_string.p1-new_string, 0x30
UNWIND5 prelayout_unwind, prelayout, 0x40
align 4, db 0
render_unwind:
    db 1, render_rows.prolog-render_rows, 8, 0
    db render_rows.prolog-render_rows, 0xf2
    db render_rows.p7-render_rows, 0xf0
    db render_rows.p6-render_rows, 0xe0
    db render_rows.p5-render_rows, 0xd0
    db render_rows.p4-render_rows, 0xc0
    db render_rows.p3-render_rows, 0x70
    db render_rows.p2-render_rows, 0x60
    db render_rows.p1-render_rows, 0x30
%include "adnin-denicker.asm"
%include "adnin-metrics.asm"
%include "adnin-match.asm"
%include "adnin-game-state.asm"
%include "adnin-columns.asm"
%include "adnin-headers.asm"
%include "adnin-replay.asm"
%include "adnin-replay-stats.asm"
%include "adnin-replay-denick.asm"
%include "adnin-player-policy.asm"
%include "adnin-number-poll.asm"
%include "adnin-api-policy.asm"
%include "adnin-process-entry.asm"
%include "adnin-input-hooks.asm"
%include "adnin-chat-poll.asm"
%ifdef ADNIN_COMPAT_PROFILE
%include "adnin-compat.asm"
%else
%include "adnin-scheduler.asm"
%include "adnin-lunar-stop.asm"
%endif
align 16, db 0
payload_end:
