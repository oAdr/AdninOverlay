; Retire the recovered synchronous per-client JSON writer. The Java callback
; only captures changed settings; its existing daemon owns disk persistence.
config_save:
    sub rsp, 0x28
.prolog:
    lea rdx, [config_save_name]
    lea r8, [config_save_sig]
    xor r9d, r9d
    call invoke_int
    add rsp, 0x28
    ret
config_save_end:
config_save_name: db 'nativeRequestConfigSave',0
config_save_sig: db '()I',0
align 4, db 0
config_save_unwind:
    db 1, config_save.prolog-config_save, 1, 0
    db config_save.prolog-config_save, 0x42
    dw 0
align 4, db 0
db 'ADNCFG01'
dd config_save-$$, config_save_end-$$, config_save_unwind-$$
