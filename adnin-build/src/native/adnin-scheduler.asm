; Lunar's reviewed scheduler ignores addScheduledTask's returned local Future.
; Its calling thread remains attached across ticks, so release that one local
; handle now. The scheduled task and Future remain owned by Java's queue.
db 'ADNSCH01'
dd lunar_schedule-$$, lunar_schedule_end-$$, lunar_schedule_unwind-$$
lunar_schedule:
    push rbx
.p1:
    sub rsp,0x30
.prolog:
    mov rbx,rcx
    call IMAGE_BASE+0xc270           ; original JNI CallObjectMethodV thunk
    mov [rsp+0x20],rax
    test rax,rax
    jz .done
    mov rdx,rax
    mov rcx,rbx
    mov rax,[rbx]
    call [rax+DELETE_LOCAL_REF]      ; valid with a pending Java exception
.done:
    mov rax,[rsp+0x20]              ; pinned caller discards this return value
    add rsp,0x30
    pop rbx
    ret
lunar_schedule_end:
align 4,db 0
lunar_schedule_unwind:
    db 1,lunar_schedule.prolog-lunar_schedule,2,0
    db lunar_schedule.prolog-lunar_schedule,0x52
    db lunar_schedule.p1-lunar_schedule,0x30
