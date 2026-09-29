"""Defer three UI polls while the Number worker owns its cache mutex."""
import struct

PROFILES = {
    'lunar': dict(mutex=0x1a4cf0, lock=0x122b77, module=0x12f1c8, proc=0x12f1c0,
        sites=((0xbaaca,0xbaab4,'numberGetLock','488bc8e8a880060085c00f8505030000','32dbe9a1020000'),
               (0xbb597,0xbb752,'numberRegisterLock','498bcde8db75060085c00f851f020000','488b55074883fa0f762d48ffc2488b4def4881fa'),
               (0xbbcf7,0xbbdd2,'numberPopLock','488bcee87b6e060085c00f85f6000000','0fb6c3488b5c2430488b7424404883c4205fc3')),
        workerLock=0xb8ef2, workerNetwork=0xb95b0, workerUnlock=0xb9810),
    'vanilla': dict(mutex=0x17ccd0, lock=0x125877, module=0x132148, proc=0x132140,
        sites=((0xbd66a,0xbd654,'numberGetLock','488bc8e80882060085c00f8505030000','32dbe9a1020000'),
               (0xbe137,0xbe2f2,'numberRegisterLock','498bcde83b77060085c00f851f020000','488b55074883fa0f762d48ffc2488b4def4881fa'),
               (0xbe897,0xbe972,'numberPopLock','488bcee8db6f060085c00f85f6000000','0fb6c3488b5c2430488b7424404883c4205fc3'))),
}


def reviewed_patches(pe, profile, code_rva, metadata):
    spec = PROFILES[profile]
    def require(ok, label):
        if not ok: raise ValueError('Number polling '+label+' changed: '+profile)
    require(pe.get_data(spec['mutex'],4)==struct.pack('<I',2), 'mutex nonblocking type')
    imports = {i.address-pe.OPTIONAL_HEADER.ImageBase:(d.dll.lower(),i.name)
               for d in pe.DIRECTORY_ENTRY_IMPORT for i in d.imports}
    require(imports.get(spec['module'])==(b'kernel32.dll',b'GetModuleHandleA'), 'module resolver import')
    require(imports.get(spec['proc'])==(b'kernel32.dll',b'GetProcAddress'), 'export resolver import')
    items=[]
    for call,busy,name,context,cleanup in spec['sites']:
        require(pe.get_data(call-3,len(bytes.fromhex(context)))==bytes.fromhex(context), name+' lock context')
        require(pe.get_data(busy,len(bytes.fromhex(cleanup)))==bytes.fromhex(cleanup), name+' no-lock cleanup')
        branch=call+7
        before=pe.get_data(branch,6)
        require(before[:2]==b'\x0f\x85',name+' failure branch')
        after=b'\x0f\x85'+struct.pack('<i',busy-branch-6)
        items.append(dict(callRva=call,originalTargetRva=spec['lock'],callback=name,
             bridgeTargetRva=code_rva+metadata[name],branchRva=branch,before=before.hex(),after=after.hex(),
             busyRva=busy,mutexRva=spec['mutex'],mutexType=2,successContinuationRva=call+5,
             noLockCleanupPreserved=True,workerSynchronizationPreserved=True,
             queuedItemsPreserved=True,registrationDeferredUntilLaterPoll=True,
             unexpectedErrorAction='original std::_Throw_Cpp_error(5)',
             missingExportFallback='original _Mtx_lock; original waiting retained',
             reason='Defer Number UI polling through original no-lock cleanup while cache worker is busy'))
    return items
