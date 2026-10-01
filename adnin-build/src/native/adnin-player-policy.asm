; The Tab model already resolves the current nametag RGB into row+0x94.
; Pause only new work for light-gray rows; keep cache reads, classification,
; row construction and rendering unchanged, including across respawns.
; All reviewed callers retain the model frame in RBP and pass the row
; name in RCX. These leaf guards add no allocations, locks or JNI calls.
db 'ADNGRY01'
dd gray_number_register-$$, gray_number_register_end-$$, gray_number_register_unwind-$$
dd gray_stats_allowed-$$, gray_stats_allowed_end-$$, gray_stats_allowed_unwind-$$
dd gray_legacy_lookup-$$, gray_legacy_lookup_end-$$, gray_legacy_lookup_unwind-$$
dd gray_legacy_success-$$, gray_legacy_success_end-$$, gray_legacy_success_unwind-$$
dd gray_skin_register-$$, gray_skin_register_end-$$, gray_skin_register_unwind-$$
db 'ADNGRY02'
dd gray_stats_produce-$$, gray_stats_produce_end-$$, gray_stats_produce_unwind-$$
dd gray_tags_produce-$$, gray_tags_produce_end-$$, gray_tags_produce_unwind-$$

%macro GRAY_PAUSE 2
%1:
    cmp dword [rbp+REPLAY_ROW_NAME_FRAME+0x54], 0xaaaaaa
    je .paused
    jmp IMAGE_BASE+%2
.paused:
    xor eax,eax
    ret
%{1}_end:
align 4, db 0
%{1}_unwind: db 1,0,0,0
%endmacro

; Cache-hit producers bypass query admission. R9 is the row itself; tailcalls
; retain RCX's JNI environment and every original register/stack argument.
%macro GRAY_PRODUCER_PAUSE 2
%1:
    cmp dword [r9+0x94], 0xaaaaaa
    je .paused
    jmp IMAGE_BASE+%2
.paused:
    xor eax,eax
    ret
%{1}_end:
align 4, db 0
%{1}_unwind: db 1,0,0,0
%endmacro
%ifdef ADNIN_COMPAT_PROFILE
GRAY_PAUSE gray_number_register, 0xbe090
GRAY_PAUSE gray_stats_allowed, 0x9b770
GRAY_PAUSE gray_legacy_lookup, 0xace90
GRAY_PAUSE gray_legacy_success, 0xacc00
GRAY_PAUSE gray_skin_register, 0xa9dc0
GRAY_PRODUCER_PAUSE gray_stats_produce, 0x6c2a0
GRAY_PRODUCER_PAUSE gray_tags_produce, 0x6a620
%else
GRAY_PAUSE gray_number_register, 0xbb4f0
GRAY_PAUSE gray_stats_allowed, 0x99600
GRAY_PAUSE gray_legacy_lookup, 0xaa8b0
GRAY_PAUSE gray_legacy_success, 0xaa620
GRAY_PAUSE gray_skin_register, 0xa7750
GRAY_PRODUCER_PAUSE gray_stats_produce, 0x6b850
GRAY_PRODUCER_PAUSE gray_tags_produce, 0x69bd0
%endif
