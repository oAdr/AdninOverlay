; During OS process termination, other threads have already been terminated.
; Do not invoke CRT onexit destructors that terminate on joinable worker IDs,
; acquire locks or access a dying JVM. Explicit FreeLibrary keeps original CRT
; cleanup, and every attach/thread notification follows the original entry.
%ifdef ADNIN_COMPAT_PROFILE
%define ORIGINAL_DLL_ENTRY 0x126a10
%else
%define ORIGINAL_DLL_ENTRY 0x123d10
%endif
db 'ADNENT01'
dd process_entry-$$,process_entry_end-$$,process_entry_unwind-$$
process_entry:
    test edx,edx                    ; DLL_PROCESS_DETACH == 0
    jnz .original
    test r8,r8                      ; lpReserved != NULL means process exit
    jz .original
    mov eax,1
    ret
.original:
    jmp IMAGE_BASE+ORIGINAL_DLL_ENTRY
process_entry_end:
align 4,db 0
process_entry_unwind: db 1,0,0,0
