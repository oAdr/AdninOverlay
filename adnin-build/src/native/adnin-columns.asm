; Extend the catalog before the original configuration filter orders columns.
; All vector storage and both string copies remain owned by the original CRT.
; The descriptors below contain only short inline strings and are never moved
; into native storage directly: native vector append deep-copies each one.
column_catalog:
    push rbx
.p1:
    sub rsp, 0x20
.prolog:
    mov rbx, rcx
    call IMAGE_BASE+NATIVE_COLUMN_CATALOG
    mov rcx, rbx
    mov rdx, [rbx+8]
    lea r8, [fklv_column]
    call IMAGE_BASE+NATIVE_COLUMN_APPEND
    mov rcx, rbx
    mov rdx, [rbx+8]
    lea r8, [urchin_column]
    call IMAGE_BASE+NATIVE_COLUMN_APPEND
    mov rax, rbx
    add rsp, 0x20
    pop rbx
    ret
column_catalog_end:

align 4, db 0
column_catalog_unwind:
    db 1, column_catalog.prolog-column_catalog, 2, 0
    db column_catalog.prolog-column_catalog, 0x32
    db column_catalog.p1-column_catalog, 0x30

align 8, db 0
fklv_column:
    db 'fklv',0
    times 16-($-fklv_column) db 0
    dq 4, 15
    db 'FK/LV',0
    times 48-($-fklv_column) db 0
    dq 5, 15
    dd 0, 48, 1
    db 1, 0, 0, 0
    dd FKLV_COLUMN_ID, 0
urchin_column:
    db 'urchin',0
    times 16-($-urchin_column) db 0
    dq 6, 15
    db 'Urchin',0
    times 48-($-urchin_column) db 0
    dq 6, 15
    dd 0, 80, 1
    db 1, 0, 0, 0
    dd URCHIN_COLUMN_ID, 0
