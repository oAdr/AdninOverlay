"""Execute authored request/refresh code against private fake HTTP and lists.

No DLL entry point, original routine, personal key or network is used. The
synthetic API key exists only in this test process and is never sent anywhere.
"""
import ctypes
import os
import struct
import subprocess
import tempfile
from pathlib import Path
import native_api_policy
import replay_denick_checks

UUID=b'123456781234423482341234567890ab'
KEY=b'12345678-1234-4234-8234-123456789abc'
PREFIX=b'https://api.hypixel.net/v2/player'

CALLER=r'''
bits 64
default rel
    push rbx
    push rbp
    push rsi
    push rdi
    push r12
    push r13
    push r14
    push r15
    sub rsp,0x28
    mov r15,[rsp+0x90]
    mov rbx,0x3333333333333333
    mov rbp,0x5555555555555555
    mov rsi,0x6666666666666666
    mov rdi,0x7777777777777777
    mov r12,0xcccccccccccccccc
    mov r13,0xdddddddddddddddd
    mov r14,0xeeeeeeeeeeeeeeee
    call [target]
    mov [r15],rax
    mov [r15+8],rbx
    mov [r15+16],rbp
    mov [r15+24],rsi
    mov [r15+32],rdi
    mov [r15+40],r12
    mov [r15+48],r13
    mov [r15+56],r14
    ; Snapshot the released private header before another call can reuse it.
    lea rsi,[rsp-0x190]
    lea rdi,[r15+64]
    mov ecx,0x120/8
    rep movsq
    mov rax,[r15]
    add rsp,0x28
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
'''


class Image:
    def __init__(self,test,final,report,profile):
        if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8:test.skipTest('Windows x64 private mocks')
        self.test,self.report,self.spec=test,report,native_api_policy.PROFILES[profile]
        P=ctypes.c_void_p
        self.kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        self.kernel.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];self.kernel.VirtualAlloc.restype=P
        self.kernel.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32]
        self.kernel.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
        self.base=self.kernel.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(self.base)
        section=report['section']
        ctypes.memmove(self.base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
        self.keep=[]
        ctypes.c_uint64.from_address(self.base+self.spec['keyLength']).value=len(KEY)
    def close(self):self.kernel.VirtualFree(self.base,0,0x8000)
    def put(self,rva,data):ctypes.memmove(self.base+rva,data,len(data))
    def bind(self,rva,result,args,fn):
        cb=ctypes.WINFUNCTYPE(result,*args)(fn);self.keep.append(cb)
        self.put(rva,b'\x48\xb8'+struct.pack('<Q',ctypes.cast(cb,ctypes.c_void_p).value)+b'\xff\xe0')
    def string(self,value,address=None):
        if address is None:
            obj=ctypes.create_string_buffer(32);self.keep.append(obj);address=ctypes.addressof(obj)
        ctypes.memset(address,0,32)
        if len(value)>15:
            buf=ctypes.create_string_buffer(value);self.keep.append(buf)
            ctypes.c_uint64.from_address(address).value=ctypes.addressof(buf)
        else:ctypes.memmove(address,value,len(value))
        ctypes.c_uint64.from_address(address+16).value=len(value)
        ctypes.c_uint64.from_address(address+24).value=max(15,len(value))
        return address
    def read(self,address):
        size=ctypes.c_uint64.from_address(address+16).value
        capacity=ctypes.c_uint64.from_address(address+24).value
        pointer=ctypes.c_uint64.from_address(address).value if capacity>15 else address
        return ctypes.string_at(pointer,size)
    def entry(self,name):return self.base+self.report['section']['rva']+self.report['metadata'][name]
    def flush(self):self.kernel.FlushInstructionCache(ctypes.c_void_p(-1),self.base,self.report['imageSize'])


def execute_http(test,final,report,profile,nasm):
    image=Image(test,final,report,profile);P=ctypes.c_void_p
    events=[];state=dict(status=200,transport=1,resolve=True,nick=False,resolved=UUID)
    body=b'{"success":true,"player":{"displayname":"Fixture","stats":{"Bedwars":{"Experience":12345,"final_kills_bedwars":12,"final_deaths_bedwars":3}}}}'
    def http(url,response,status,header):
        events.append(('http',image.read(url),image.read(header)))
        ctypes.c_uint32.from_address(status).value=state['status'] if state['transport'] else 0
        image.string(body if state['transport'] else b'',response)
        return state['transport']
    def resolve(name,response,nick):
        events.append(('resolve',image.read(name)))
        ctypes.c_ubyte.from_address(nick).value=int(state['nick'])
        image.string(state['resolved'] if state['resolve'] else b'',response)
        return int(state['resolve'])
    image.bind(image.spec['http'],ctypes.c_int,[P,P,P,P],http)
    image.bind(image.spec['resolver'],ctypes.c_int,[P,P,P],resolve)
    image.bind(image.spec['assign'],P,[P,P,ctypes.c_size_t],
               lambda obj,data,size:image.string(ctypes.string_at(data,size),obj))
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-hypixel-fixture-') as directory:
            source,binary=Path(directory)/'caller.asm',Path(directory)/'caller.bin'
            source.write_text('%define TARGET '+hex(image.entry('hypixelHttp'))+'\n'+CALLER,encoding='ascii')
            built=subprocess.run([str(nasm),'-f','bin','-Ox',str(source),'-o',str(binary)],capture_output=True,text=True)
            test.assertEqual(built.returncode,0,built.stdout+built.stderr)
            image.put(0x1000,binary.read_bytes())
        call=ctypes.WINFUNCTYPE(ctypes.c_int,P,P,P,P,P)(image.base+0x1000)
        image.flush()
        def run(url,header=b'',current=True):
            events.clear()
            ctypes.c_uint64.from_address(image.base+image.spec['keyLength']).value=len(KEY) if current else 0
            response=image.string(b'stale response must be replaced')
            status=ctypes.c_uint32(999);snapshot=ctypes.create_string_buffer(64+0x120)
            result=call(image.string(url),response,ctypes.byref(status),image.string(header),snapshot)
            test.assertEqual(struct.unpack_from('<7Q',snapshot,8),(0x3333333333333333,0x5555555555555555,
                0x6666666666666666,0x7777777777777777,0xcccccccccccccccc,0xdddddddddddddddd,0xeeeeeeeeeeeeeeee))
            test.assertFalse(any(snapshot.raw[64:]),'temporary header is cleared on normal return')
            return result,status.value,image.read(response)
        legacy=PREFIX+b'?key='+KEY
        # Baseline fixture for the original request contract: it has no API-Key
        # header and retains key/name in the URL, contrary to the public schema.
        def documented_request(url,header):
            return url==PREFIX+b'?uuid='+UUID and header==b'API-Key: '+KEY
        test.assertFalse(documented_request(legacy+b'&name=Fixture',b''))
        for suffix,resolve_calls in ((b'&uuid='+UUID,0),(b'&name=Fixture',1),
                (b'&uuid=12345678-1234-4234-8234-1234567890AB',0),(b'&name=Fixt%75re',1)):
            with test.subTest(profile=profile,path='name' if resolve_calls else 'uuid'):
                result,status,response=run(legacy+suffix)
                test.assertEqual((result,status,response),(1,200,body))
                test.assertEqual(len([e for e in events if e[0]=='resolve']),resolve_calls)
                requests=[e for e in events if e[0]=='http'];test.assertEqual(len(requests),1)
                test.assertTrue(documented_request(requests[0][1],requests[0][2]))
                test.assertNotIn(KEY,requests[0][1]);test.assertNotIn(b'name=',requests[0][1])
        # Percent-encoded key characters become header bytes exactly once.
        encoded=KEY.replace(b'-',b'%2D')
        test.assertEqual(run(PREFIX+b'?key='+encoded+b'&uuid='+UUID)[0],1)
        test.assertTrue(documented_request(events[-1][1],events[-1][2]))
        for status in (200,400,403,429,500):
            state['status']=status
            test.assertEqual(run(legacy+b'&uuid='+UUID)[:2],(1,status))
        state.update(status=200,transport=0)
        test.assertEqual(run(legacy+b'&uuid='+UUID),(0,0,b''))
        state['transport']=1
        test.assertEqual(run(legacy+b'&uuid='+UUID),(1,200,body))
        for options in (dict(resolve=False),dict(resolved=b'invalid')):
            state.update(resolve=True,nick=False,resolved=UUID);state.update(options)
            test.assertEqual(run(legacy+b'&name=Fixture'),(0,0,b''))
            test.assertEqual([e[0] for e in events],['resolve'])
        state.update(resolve=True,nick=True,resolved=b'')
        test.assertEqual(run(legacy+b'&name=Fixture'),(1,200,b'{"success":true,"player":null}'))
        test.assertEqual([e[0] for e in events],['resolve'])
        state.update(resolve=True,nick=False,resolved=UUID)
        invalid=[PREFIX+b'?key=&uuid='+UUID,PREFIX+b'?key=%0D%0AInjected&uuid='+UUID,
            PREFIX+b'?key=abc%2&uuid='+UUID,PREFIX+b'?key=abc%xx&uuid='+UUID,
            PREFIX+b'?key='+b'x'*257+b'&uuid='+UUID,legacy+b'&uuid=',legacy+b'&uuid='+b'x'*32,
            legacy+b'&uuid='+UUID+b'&extra=x',legacy+b'&name='+b'X'*17,
            legacy+b'&name=%0AFixture',legacy+b'&other='+UUID]
        for index,url in enumerate(invalid):
            with test.subTest(profile=profile,invalidCase=index):
                test.assertEqual(run(url),(0,0,b''));test.assertEqual(events,[])
        test.assertEqual(run(legacy+b'&uuid='+UUID,header=b'X-Test: fixture'),(0,0,b''));test.assertEqual(events,[])
        test.assertEqual(run(legacy+b'&uuid='+UUID,current=False),(0,0,b''));test.assertEqual(events,[])
        maximum_key=b'x'*256
        test.assertEqual(run(PREFIX+b'?key='+maximum_key+b'&uuid='+UUID),(1,200,body))
        test.assertEqual(events,[('http',PREFIX+b'?uuid='+UUID,b'API-Key: '+maximum_key)])
        # Explicit proxy route contains no key and remains byte-for-byte intact.
        proxy=b'https://api.bordic.xyz/v3/cache/hypixel?uuid='+UUID
        test.assertEqual(run(proxy,current=False),(1,200,body))
        test.assertEqual(events,[('http',proxy,b'')])
        test.assertEqual(run(proxy,header=b'X-Test: original',current=False),(1,200,body))
        test.assertEqual(events,[('http',proxy,b'X-Test: original')])
        replay_denick_checks.check_simple_unwind(test,image.base,report)
    finally:image.close()


def execute_refresh(test,final,report,profile,nasm):
    image=Image(test,final,report,profile);P=ctypes.c_void_p
    events=[]
    image.bind(image.spec['skinSetter'],ctypes.c_int,[P,P],lambda a,b:events.append((a,b)) or 0x41)
    refresh=ctypes.WINFUNCTYPE(ctypes.c_int,P,P)(image.entry('apiRefreshFailures'))
    head=image.spec['statsHead'];flags=image.spec['statsFlags']
    sentinel=ctypes.create_string_buffer(16);image.keep.append(sentinel);sentinel_at=ctypes.addressof(sentinel)
    ctypes.c_uint64.from_address(image.base+head).value=sentinel_at
    image.flush()
    try:
        # Failed entries retry immediately after changing config, successful
        # Bedwars/Skywars/Duels records and their timestamps remain intact.
        nodes=[ctypes.create_string_buffer(0x200) for _ in range(5)];image.keep.extend(nodes)
        addrs=[ctypes.addressof(n) for n in nodes]
        ctypes.c_uint64.from_address(sentinel_at).value=addrs[0]
        ctypes.c_uint64.from_address(sentinel_at+8).value=addrs[-1]
        ctypes.c_uint64.from_address(image.base+head+8).value=len(nodes)
        before=[]
        for i,addr in enumerate(addrs):
            ctypes.memset(addr,0,0x200)
            ctypes.c_uint64.from_address(addr).value=addrs[i+1] if i+1<len(nodes) else sentinel_at
            ctypes.c_uint64.from_address(addr+8).value=addrs[i-1] if i else sentinel_at
            ctypes.c_uint64.from_address(addr+0x30).value=1000
            if 1<=i<=3:ctypes.c_ubyte.from_address(addr+flags[i-1]).value=1
            before.append(ctypes.string_at(addr,0x200))
        test.assertEqual(refresh(0x1234,0x5678),0x41)
        test.assertEqual(events,[(0x1234,0x5678)])
        for i,addr in enumerate(addrs):
            expected=bytearray(before[i])
            if i in (0,4):struct.pack_into('<Q',expected,0x30,(-45001)&((1<<64)-1))
            test.assertEqual(ctypes.string_at(addr,0x200),bytes(expected))
            age=(1001-ctypes.c_uint64.from_address(addr+0x30).value)&((1<<64)-1)
            test.assertEqual(age>45000,i in (0,4))
        # Empty cache and count bounds must still forward the original setter.
        ctypes.c_uint64.from_address(image.base+head+8).value=0
        test.assertEqual(refresh(3,4),0x41)
        ctypes.c_uint64.from_address(image.base+head).value=0
        test.assertEqual(refresh(5,6),0x41)
        test.assertEqual(events[-2:],[(3,4),(5,6)])
        ctypes.c_uint64.from_address(image.base+head).value=sentinel_at
        ctypes.c_uint64.from_address(image.base+head+8).value=1
        for addr in addrs:ctypes.c_uint64.from_address(addr+0x30).value=1234
        test.assertEqual(refresh(7,8),0x41)
        test.assertEqual(ctypes.c_uint64.from_address(addrs[0]+0x30).value,(-45001)&((1<<64)-1))
        test.assertEqual(ctypes.c_uint64.from_address(addrs[4]+0x30).value,1234)
        ctypes.c_uint64.from_address(sentinel_at).value=sentinel_at
        test.assertEqual(refresh(9,10),0x41)
    finally:image.close()
