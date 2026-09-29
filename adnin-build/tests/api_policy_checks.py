"""Offline PE checks and authored native provider/credential fixtures only.

Original DLL functions, personal configuration and HTTP are never executed.
"""
import ctypes
import os
import struct
import subprocess
import tempfile
from pathlib import Path
import pefile
import bridge
import native_api_policy
import replay_denick_checks


def verify_pe(test,before,final,report,profile):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    spec=native_api_policy.PROFILES[profile]
    policy=report['nativeApiPolicy']
    test.assertEqual(policy,native_api_policy.reviewed_patches(old,profile,report['section']['rva'],report['metadata']))
    test.assertEqual(len(policy['patches']),6)
    for item in policy['patches']:
        site=item['siteRva']; original=bytes.fromhex(item['before']); replacement=bytes.fromhex(item['after'])
        test.assertEqual(old.get_data(site,len(original)),original)
        test.assertEqual(new.get_data(site,len(replacement)),replacement)
        changed=bytearray(before); changed[old.get_offset_from_rva(site)]^=1
        with test.assertRaisesRegex(ValueError,'Native API policy'):
            native_api_policy.reviewed_patches(pefile.PE(data=bytes(changed)),profile,report['section']['rva'],report['metadata'])
    for site in (spec['nameGuard'],spec['nameFail'],spec['uuidFail'],spec['uuidSite']-12,
                 spec['pingCall']-8,spec['numberCall']-16,spec['keyLength'],spec['numberKey']+16):
        changed=bytearray(before); changed[old.get_offset_from_rva(site)]^=1
        with test.assertRaisesRegex(ValueError,'Native API policy'):
            native_api_policy.reviewed_patches(pefile.PE(data=bytes(changed)),profile,report['section']['rva'],report['metadata'])
    ping=policy['ping']
    test.assertIn(ping,report['hooks'])
    test.assertEqual(new.get_data(ping['callRva'],5),bridge.call_bytes(ping['callRva'],ping['bridgeTargetRva']))
    for start,end,_ in spec['guards']:
        expected=bytearray(old.get_data(start,end-start))
        if start<=spec['refreshCall']<end:
            at=spec['refreshCall']-start
            expected[at:at+5]=bridge.call_bytes(spec['refreshCall'],policy['failedCacheRefresh']['bridgeTargetRva'])
        test.assertEqual(bytes(expected),new.get_data(start,end-start))
        changed=bytearray(before);changed[old.get_offset_from_rva(start)]^=1
        with test.assertRaisesRegex(ValueError,'clear-capable setter'):
            native_api_policy.reviewed_patches(pefile.PE(data=bytes(changed)),profile,report['section']['rva'],report['metadata'])
    test.assertTrue(policy['originalSettersAndLocksPreserved'])
    test.assertTrue(policy['currentEmptyConfigRejectsOldWorkerKey'])
    test.assertIn('no cancellation barrier',policy['limitation'])
    # The original synchronization order and Number setter invocation remain.
    for site,size in ((spec['numberCall']-16,26),(spec['nameFail'],7),(spec['uuidFail'],64)):
        test.assertEqual(old.get_data(site,size),new.get_data(site,size))
    for site,offset,size,field,_ in spec['numberSites']:
        data=new.get_data(site,size)
        test.assertEqual(site+size+struct.unpack_from('<i',data,offset)[0],spec['numberKey']+field)
    for name in ('apiKeyReady','apiUuidReady','apiPingProxy'):
        item=next(i for i in report['bridgeRuntimeFunctions'] if i['name']==name)
        test.assertEqual(new.get_data(item['unwind'],4),bytes((1,0,0,0)))
    for item in policy['http']['sources']:
        test.assertIn(item,report['hooks'])
        test.assertEqual(new.get_data(item['callRva'],5),bridge.call_bytes(item['callRva'],item['bridgeTargetRva']))
    for site,context in spec['httpSites']:
        changed=bytearray(before);changed[old.get_offset_from_rva(site-14)]^=1
        with test.assertRaisesRegex(ValueError,'Hypixel HTTP'):
            native_api_policy.reviewed_patches(pefile.PE(data=bytes(changed)),profile,report['section']['rva'],report['metadata'])
    refresh=policy['failedCacheRefresh']
    test.assertIn({k:refresh[k] for k in ('callRva','originalTargetRva','bridgeTargetRva','callback')},report['hooks'])
    test.assertTrue(refresh['underOriginalStatsMutex'] and refresh['onlyFailedEntriesExpired'])


CALLER=r'''
bits 64
default rel
    push rbx
    push rsi
    push r14
    push r15
    sub rsp,0x28
    mov rbx,rcx
    mov r15,rdx
    xor esi,esi
    mov r14,0x9876543212345600
    mov r8,0x1020304050607080
    mov r9,0x1122334455667788
    call [target]
    mov [r15],r14
    mov [r15+8],rbx
    mov [r15+16],rcx
    mov [r15+24],rdx
    mov [r15+32],r8
    mov [r15+40],r9
    add rsp,0x28
    pop r15
    pop r14
    pop rsi
    pop rbx
    ret
target: dq TARGET
'''


def execute(test,final,report,profile,nasm):
    if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8:test.skipTest('Windows x64 private mock execution')
    P=ctypes.c_void_p
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];kernel.VirtualAlloc.restype=P
    kernel.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32]
    kernel.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
    base=kernel.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(base)
    section=report['section'];spec=native_api_policy.PROFILES[profile];policy=report['nativeApiPolicy']
    pe=pefile.PE(data=final)
    # Only the newly authored bridge section is copied. All old functions are
    # absent; below, authored markers stand in for successful/failed retrieval.
    ctypes.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    def put(rva,data):ctypes.memmove(base+rva,data,len(data))
    keep=[]
    def string(value,address=None):
        obj=ctypes.create_string_buffer(32)
        if len(value)>15:
            buf=ctypes.create_string_buffer(value);keep.append(buf)
            struct.pack_into('<Q',obj,0,ctypes.addressof(buf))
        else:
            ctypes.memmove(obj,value,len(value))
        struct.pack_into('<QQ',obj,16,len(value),max(15,len(value)))
        keep.append(obj)
        if address is not None:ctypes.memmove(address,obj,32);return address
        return ctypes.addressof(obj)
    def assemble(source,path):
        asm,binary=path.with_suffix('.asm'),path.with_suffix('.bin')
        asm.write_text(source,encoding='ascii')
        result=subprocess.run([str(nasm),'-f','bin','-Ox',str(asm),'-o',str(binary)],capture_output=True,text=True)
        test.assertEqual(result.returncode,0,result.stdout+result.stderr)
        return binary.read_bytes()
    try:
        by_site={i['siteRva']:bytes.fromhex(i['after']) for i in policy['patches']}
        # Author the unchanged proxy branch explicitly; only replacement
        # instructions are taken from the built DLL.
        site=spec['nameGuard']
        put(site,b'\x40\x38\x35'+struct.pack('<i',spec['proxy']-site-7)
            +native_api_policy.rel32(b'\x0f\x85',site+7,spec['nameProxy'])+by_site[spec['nameSite']])
        put(spec['nameFail'],b'\x31\xc0\xc3')
        put(spec['nameProxy'],b'\xb8\x02\0\0\0\xc3')
        put(spec['nameDirect'],b'\xb8\x01\0\0\0\xc3')
        put(spec['uuidSite'],by_site[spec['uuidSite']])
        put(spec['uuidSite']+24,bytes.fromhex('410fb6c6ffc0c3'))
        put(spec['uuidFail'],b'\x31\xc0\xc3')
        put(spec['pingTarget'],b'\xb8\x01\0\0\0\xc3')
        callers={}
        with tempfile.TemporaryDirectory(prefix='adnin-api-policy-') as tmp:
            temp=Path(tmp)
            for index,(kind,target) in enumerate((('name',spec['nameGuard']),('uuid',spec['uuidSite']),
                    ('key',section['rva']+report['metadata']['apiKeyReady']),
                    ('ping',section['rva']+report['metadata']['apiPingProxy']))):
                machine=assemble('%define TARGET '+hex(base+target)+'\n'+CALLER,temp/kind)
                address=base+0x1000*(index+1);ctypes.memmove(address,machine,len(machine))
                callers[kind]=ctypes.WINFUNCTYPE(ctypes.c_int,P,P)(address)
            # Exercise all four changed Number operands with an independently
            # authored equality loop; no original native comparison is executed.
            rows=spec['numberSites'];start=rows[0][0];last=rows[-1][0]
            prefix=b''.join(by_site[row[0]] for row in rows[:3])
            source='bits 64\ndefault rel\norg '+hex(start)+'\n'
            source+='db '+','.join(hex(x) for x in prefix)+'\n'
            source+='mov r8,[rcx+16]\ncmp qword [rcx+24],15\ncmova rcx,[rcx]\n'
            source+='times '+str(last-start)+'-($-$$) db 0x90\n'
            source+='db '+','.join(hex(x) for x in by_site[last])+'\n'
            source+='jne changed\ntest r8,r8\njz same\nagain:\nmov dl,[rcx]\ncmp dl,[rax]\njne changed\ninc rcx\ninc rax\ndec r8\njnz again\nsame:\nxor eax,eax\nret\nchanged:\nmov eax,1\nret\n'
            machine=assemble(source,temp/'number-compare');put(start,machine)
            number=ctypes.WINFUNCTYPE(ctypes.c_int,P)(base+start)
        kernel.FlushInstructionCache(P(-1),base,report['imageSize'])
        values=(b'',b' ',b'\t\r\n',bytes(range(33)),b'fixture-a',b' \tfixture-b\r\n',b'F'*48,b' '*48,b'\xc2\xa0',
                b'12345678-1234-4234-8234-123456789abc')
        for current in (0,32):
            ctypes.c_uint64.from_address(base+spec['keyLength']).value=current
            for proxy in (0,1,2):
                ctypes.c_ubyte.from_address(base+spec['proxy']).value=proxy
                for value in values:
                    key=string(value);snap=ctypes.create_string_buffer(48)
                    ready=current!=0 and any(byte>0x20 for byte in value)
                    for kind,call in callers.items():
                        with test.subTest(profile=profile,currentPresent=bool(current),proxy=proxy,kind=kind,size=len(value),ready=ready):
                            result=call(key,ctypes.addressof(snap))
                            expected=(int(bool(proxy)) if kind=='ping' else int(ready) if kind=='key' else 2 if proxy else int(ready))
                            test.assertEqual(result,expected)
                            r14,rbx,rcx,rdx,r8,r9=struct.unpack('<6Q',snap)
                            test.assertEqual(r14,0x9876543212345600+(int(bool(proxy)) if kind=='uuid' else 0))
                            test.assertEqual((rbx,rcx,rdx,r8,r9),(key,key,ctypes.addressof(snap),0x1020304050607080,0x1122334455667788))
        for cached in (b'',b'old-key',b'C'*45):
            for visible in (b'',b'old-key',b'new-key',b'C'*45,b'N'*45):
                with test.subTest(profile=profile,numberCachedSize=len(cached),visibleSize=len(visible)):
                    # Reproduce the old bug: main shadow already matches UI.
                    string(visible,base+spec['shadow'])
                    string(cached,base+spec['numberKey'])
                    ui=string(visible)
                    test.assertEqual(number(ui),int(cached!=visible))
                    # Following a setter update, the next tick does not repeat.
                    string(visible,base+spec['numberKey'])
                    test.assertEqual(number(ui),0)
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:
        kernel.VirtualFree(base,0,0x8000)
