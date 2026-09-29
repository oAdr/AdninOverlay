"""Review the fixed CRT entry; bypass it only for OS process termination."""
import struct

ENTRIES = {'lunar':0x123d10,'vanilla':0x126a10}
ENTRY_BYTES = bytes.fromhex('48895c24084889742410574883ec20498bf88bda488bf183fa017505e8af000000'
    '4c8bc78bd3488bce488b5c2430488b7424384883c4205fe983feffff')


def reviewed_patch(pe,profile,code_rva,metadata):
    original=ENTRIES[profile]
    if pe.OPTIONAL_HEADER.AddressOfEntryPoint!=original or pe.get_data(original,len(ENTRY_BYTES))!=ENTRY_BYTES:
        raise ValueError('Original DLL CRT entry changed: '+profile)
    replacement=code_rva+metadata['processEntry']
    return dict(offset=pe.OPTIONAL_HEADER.get_field_absolute_offset('AddressOfEntryPoint'),
        before=struct.pack('<I',original).hex(),after=struct.pack('<I',replacement).hex(),
        originalEntryRva=original,bridgeEntryRva=replacement,
        condition='DLL_PROCESS_DETACH and lpReserved != NULL',explicitFreeLibraryPreserved=True,
        noJniLocksOrJoinsDuringProcessExit=True,
        reason='Skip CRT worker destructors only while the entire process is terminating')
