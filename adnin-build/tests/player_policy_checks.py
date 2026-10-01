"""Gray query/notification guards in a private mock image, never a game DLL.

Only newly authored bridge bytes execute. Original getters/register functions
are replaced by owned leaf markers which record arguments and consume an
owned one-shot latch, proving gray callbacks do not consume pending results.
"""
import ctypes
import os
import struct
import subprocess
import tempfile
from pathlib import Path

import pefile
import bridge
import native_player_policy
import replay_checks


def verify(test,before,final,report,profile):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    policy=report['grayPlayerPolicy']
    test.assertEqual(policy,native_player_policy.reviewed_policy(old,profile))
    test.assertEqual(policy['nameColorOffset'],0x94)
    test.assertEqual(policy['lightGrayRgb'],0xaaaaaa)
    test.assertTrue(policy['cachedIdentityAndStatisticsPreserved'] and policy['originalRowRenderingPreserved'])
    test.assertTrue(policy['noJniOrAllocation'])
    test.assertTrue(policy['cachedNotificationProducersGuarded'])
    test.assertEqual(policy['producerRowRegister'],'r9')
    gates = native_player_policy.PROFILES[profile] + native_player_policy.PRODUCERS[profile]
    for site,target,name,context in gates:
        producer = name in native_player_policy.PRODUCER_FUNCTIONS
        prefix = 48 if producer else 7
        with test.subTest(profile=profile,callback=name,callsite=hex(site)):
            hook=next(item for item in report['hooks'] if item['callRva']==site)
            test.assertEqual((hook['originalTargetRva'],hook['callback']),(target,name))
            test.assertEqual(old.get_data(site,5),bridge.call_bytes(site,target))
            test.assertEqual(new.get_data(site,5),bridge.call_bytes(site,hook['bridgeTargetRva']))
            # Changing only the call cannot change either parameter setup or
            # success/failure branching. The original callee body is retained.
            test.assertEqual(old.get_data(site-prefix,prefix),new.get_data(site-prefix,prefix))
            suffix=old.get_data(site+5,len(bytes.fromhex(context))-prefix-5)
            if name=='grayNumberRegister':
                # This immediately adjacent legacy Skin-enabled query was
                # independently retired before the gray policy was added.
                test.assertEqual(suffix[:1],b'\xe8')
                test.assertEqual(new.get_data(site+5,len(suffix)),bytes.fromhex('31c0909090'))
            else:
                test.assertEqual(suffix,new.get_data(site+5,len(suffix)))
            test.assertEqual(old.get_data(target,32),new.get_data(target,32))
            for at in (site-prefix,site,site+5):
                changed=bytearray(before);changed[old.get_offset_from_rva(at)]^=1
                with test.assertRaisesRegex(ValueError,'Gray player (query|producer) ABI'):
                    native_player_policy.reviewed_policy(pefile.PE(data=bytes(changed)),profile)
            function=next(f for f in report['bridgeRuntimeFunctions'] if f['name']==name)
            test.assertEqual(function['begin'],hook['bridgeTargetRva'])
            test.assertEqual(new.get_data(function['unwind'],4),bytes((1,0,0,0)))
            test.assertEqual(new.get_data(function['end']-1,1),b'\xc3')


CALLER=replay_checks.SNAPSHOT_ASM.split('    push rbx\n')[0]+r'''
    push rbx
    push rbp
    push rsi
    push rdi
    push r12
    push r13
    push r14
    push r15
    sub rsp,0x38
    mov r12,rdx
%ifdef PRODUCER
    mov r9,rcx
    mov rcx,0x1111222233334444
    mov rbp,0x5551223344556600
%else
    lea rbp,[rcx+0x40-NAMEFRAME]
    add rcx,0x40
%endif
    mov rbx,0x3333444455556666
    mov rsi,0x6666777788889999
    mov rdi,0x777788889999aaaa
    mov r13,0xdddd111122223333
    mov r14,0xeeee111122223333
    mov r15,0xffff222233334444
    movdqu xmm0,[pattern]
    movdqu xmm1,[pattern+16]
    movdqu xmm2,[pattern+32]
    movdqu xmm3,[pattern+48]
    movdqu xmm4,[pattern+64]
    movdqu xmm5,[pattern+80]
    mov rax,0x0001223344556600
    mov rdx,0x2221223344556600
    mov r8,0x8881223344556600
%ifndef PRODUCER
    mov r9,0x9991223344556600
%endif
    mov r10,0xaaa1223344556600
    mov r11,0xbbb1223344556600
    mov qword [rsp+0x20],0x12345678
    mov qword [rsp+0x28],0x76543210
    SNAPSHOT 0
    call [target]
    SNAPSHOT 256
    add rsp,0x38
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbp
    pop rbx
    ret
target: dq TARGET
pattern:
%assign x 0
%rep 96
    db (x*17+7)&255
%assign x x+1
%endrep
'''

ORIGINAL=r'''
bits 64
default rel
org ORIGIN
marker:
state equ marker+STATE-ORIGIN
    mov [rel state],rcx
    mov [rel state+8],rdx
    mov [rel state+16],r8
    mov [rel state+24],r9
    mov rax,[rsp+0x28]
    mov [rel state+32],rax
    mov rax,[rsp+0x30]
    mov [rel state+64],rax
    inc qword [rel state+40]
    mov rax,[rel state+48]
    mov qword [rel state+48],0
    ret
'''


def execute(test,final,report,profile,nasm):
    if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8:test.skipTest('Windows x64 private mock execution')
    P=ctypes.c_void_p
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];kernel.VirtualAlloc.restype=P
    kernel.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32]
    kernel.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
    base=kernel.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(base)
    section=report['section'];meta=report['metadata']
    ctypes.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    name_frame=0x3d0 if profile=='lunar' else 0x3f0
    def assemble(source,defines,path):
        asm=path.with_suffix('.asm');binary=path.with_suffix('.bin');asm.write_text(source,encoding='ascii')
        result=subprocess.run([str(nasm),'-f','bin','-Ox']+['-D'+key+'='+hex(value) for key,value in defines.items()]
                              +[str(asm),'-o',str(binary)],capture_output=True,text=True)
        test.assertEqual(result.returncode,0,result.stdout+result.stderr)
        test.assertFalse(result.stderr,result.stderr)
        return binary.read_bytes()
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-gray-query-') as tmp:
            # Several source call sites share each producer, but there is one
            # runtime function record per unique leaf. Verify every call site
            # above and execute each authored leaf with its own ABI below.
            functions = {}
            for _,target,name,_ in native_player_policy.PROFILES[profile] + native_player_policy.PRODUCERS[profile]:
                functions.setdefault(name,target)
                test.assertEqual(functions[name],target)
            for index,(name,target) in enumerate(functions.items()):
                producer=name in native_player_policy.PRODUCER_FUNCTIONS
                state=base+0x20000+index*0x100
                stub=assemble(ORIGINAL,dict(ORIGIN=base+target,STATE=state),Path(tmp)/(name+'-original'))
                ctypes.memmove(base+target,stub,len(stub))
                defines=dict(TARGET=base+section['rva']+meta[name],NAMEFRAME=name_frame)
                if producer:defines['PRODUCER']=1
                code=assemble(CALLER,defines,Path(tmp)/name)
                address=base+0x1000+index*0x1000;ctypes.memmove(address,code,len(code))
                kernel.FlushInstructionCache(P(-1),base,report['imageSize'])
                invoke=ctypes.WINFUNCTYPE(None,P,P)(address)
                for color in (0xaaaaaa,0x555555,0xffffff,0xff5555,0,0xaaaaa9,0xaaaaab,0xffaaaaaa):
                    for result in (1,0,0x123456789abcdef0):
                        with test.subTest(profile=profile,callback=name,color=hex(color),result=result):
                            row=ctypes.create_string_buffer(bytes((i*29+3)&255 for i in range(0x178)),0x178)
                            struct.pack_into('<I',row,0x94,color);before=bytes(row)
                            snapshot=ctypes.create_string_buffer(512)
                            ctypes.memset(state,0,80);ctypes.c_uint64.from_address(state+48).value=result
                            invoke(ctypes.addressof(row),ctypes.addressof(snapshot))
                            after=struct.unpack_from('<16Q',snapshot,256);prior=struct.unpack_from('<16Q',snapshot,0)
                            gray=color==0xaaaaaa
                            test.assertEqual(after[0],0 if gray else result)
                            test.assertEqual(after[1:],prior[1:],'leaf guard preserves all GPR input/nonvolatile/stack registers except return RAX')
                            test.assertEqual(snapshot.raw[128:224],snapshot.raw[384:480],'leaf guard preserves XMM0-5')
                            test.assertEqual(bytes(row),before,'query pause never rewrites row identity/statistics/rendering')
                            recorded=struct.unpack('<8Q',ctypes.string_at(state,64))
                            if gray:
                                test.assertEqual(recorded[:6],(0,)*6,'gray does not invoke a new lookup or consume a one-shot status')
                                test.assertEqual(recorded[6],result,'gray retains the pending original success/failure result')
                            else:
                                rcx=0x1111222233334444 if producer else ctypes.addressof(row)+0x40
                                test.assertEqual(recorded[:5],(rcx,prior[2],prior[8],prior[9],0x12345678))
                                test.assertEqual(ctypes.c_uint64.from_address(state+64).value,0x76543210,
                                                 'tailcall preserves the sixth argument as well as the fifth')
                                test.assertEqual(recorded[5:7],(1,0),'non-gray invokes the original operation once with its original arguments')
                            if gray:
                                # A later real team color resumes the original
                                # pending result, instead of losing it while gray.
                                struct.pack_into('<I',row,0x94,0xffffff)
                                invoke(ctypes.addressof(row),ctypes.addressof(snapshot))
                                test.assertEqual(struct.unpack_from('<Q',snapshot,256)[0],result)
                                test.assertEqual(ctypes.c_uint64.from_address(state+40).value,1)
                                test.assertEqual(ctypes.c_uint64.from_address(state+48).value,0)
        check_unwind(test,base,report)
    finally:kernel.VirtualFree(base,0,0x8000)


def check_unwind(test,base,report):
    unwind=ctypes.WinDLL('ntdll').RtlVirtualUnwind
    unwind.argtypes=[ctypes.c_uint32,ctypes.c_uint64,ctypes.c_uint64]+[ctypes.c_void_p]*5
    unwind.restype=ctypes.c_void_p
    for item in report['bridgeRuntimeFunctions']:
        if item['name'] not in native_player_policy.FUNCTIONS:continue
        for offset in (0,item['end']-item['begin']-1):
            stack=ctypes.create_string_buffer(128);context=ctypes.create_string_buffer(1232)
            sp=ctypes.addressof(stack)+32;ret=0x7ffefedc1234;struct.pack_into('<Q',stack,32,ret)
            struct.pack_into('<I',context,0x30,0x00100003);struct.pack_into('<Q',context,0x98,sp)
            pc=base+item['begin']+offset;struct.pack_into('<Q',context,0xf8,pc)
            for register in (3,5,6,7,12,13,14,15):struct.pack_into('<Q',context,0x78+register*8,0xabc000+register)
            runtime=(ctypes.c_uint32*3)(item['begin'],item['end'],item['unwind'])
            handler,frame=ctypes.c_void_p(),ctypes.c_uint64()
            unwind(0,base,pc,ctypes.byref(runtime),context,ctypes.byref(handler),ctypes.byref(frame),None)
            test.assertEqual(struct.unpack_from('<Q',context,0xf8)[0],ret)
            test.assertEqual(struct.unpack_from('<Q',context,0x98)[0],sp+8)
            for register in (3,5,6,7,12,13,14,15):test.assertEqual(struct.unpack_from('<Q',context,0x78+register*8)[0],0xabc000+register)
