; Stop the periodic history collector after the first fully converted record
; that the unchanged consumer treats as its old-record boundary. No component
; or text is cached across polls. The boundary record is kept in full so the
; same-counter/changed-text special case remains observable.
%ifdef ADNIN_COMPAT_PROFILE
%define CHAT_POLL_RETURN 0x16a25
%define CHAT_POLL_INITIALIZED 0x17e9f7
%define CHAT_POLL_COUNTER 0x17c690
%define CHAT_COLLECT_CONTINUE 0xcf24
%define CHAT_COLLECT_EXIT 0xcf32
%define CHAT_COLLECT_BEGIN 0xc480
%define CHAT_COLLECT_END 0xd2de
%define CHAT_COLLECT_UNWIND 0x1611fc
%else
%define CHAT_POLL_RETURN 0x16925
%define CHAT_POLL_INITIALIZED 0x1a69db
%define CHAT_POLL_COUNTER 0x1a4640
%define CHAT_COLLECT_CONTINUE 0xd184
%define CHAT_COLLECT_EXIT 0xd192
%define CHAT_COLLECT_BEGIN 0xc6e0
%define CHAT_COLLECT_END 0xd53e
%define CHAT_COLLECT_UNWIND 0x188e6c
%endif

db 'ADNCHP01'
dd chat_poll_tail-$$, chat_poll_tail_end-$$, chat_poll_tail_unwind-$$
chat_poll_tail:
    ; The collector also has a one-line reset caller. Change only the reviewed
    ; 100-line periodic poll, identified by its original native return address.
    lea rax, [rel $$-CODE_RVA+CHAT_POLL_RETURN]
    cmp [rsp+0x1a8], rax
    jne .continue
    ; R13 is restored to the output vector on all paths reaching this tail.
    ; Do not stop after a failed/empty conversion until a valid row exists.
    mov rax, [r13+8]
    cmp rax, [r13]
    je .continue
    cmp byte [rel $$-CODE_RVA+CHAT_POLL_INITIALIZED], 0
    je .stop
    cmp dword [rel $$-CODE_RVA+CHAT_POLL_COUNTER], 0
    jl .continue
    mov eax, [rax-0x50]
    cmp eax, [rel $$-CODE_RVA+CHAT_POLL_COUNTER]
    jg .continue
.stop:
    ; Each completed row has already released its temporary local refs and
    ; strings. Original collection and consumer cleanup own all retained rows.
    ; A direct JMP here looks like a leaf epilogue to RtlVirtualUnwind even
    ; with CHAININFO. A fixed conditional transfer retains the chained frame.
    cmp eax, eax
    je IMAGE_BASE+CHAT_COLLECT_EXIT
.continue:
    ; Replay the two displaced instructions exactly. No stack, nonvolatile
    ; register, JNI state, scheduling, thread priority or rendering is changed.
    mov eax, [rsp+0x40]
    inc eax
    cmp eax, eax
    je IMAGE_BASE+CHAT_COLLECT_CONTINUE
chat_poll_tail_end:
align 4, db 0
chat_poll_tail_unwind:
    ; This is an out-of-line body fragment, not a new call frame. Chaining
    ; retains the collector's established stack/register unwind information.
    db 0x21, 0, 0, 0
    dd CHAT_COLLECT_BEGIN, CHAT_COLLECT_END, CHAT_COLLECT_UNWIND
