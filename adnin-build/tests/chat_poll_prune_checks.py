"""Offline execution of unchanged native consumers with full/pruned collectors.

Copies only the reviewed consumer function into an owned mock allocation. Every
external callee, JNI operation and clock is replaced by an owned fixture. The
original DLL is never loaded, no game process is accessed, and no network runs.
"""
import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import random
import re
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import pefile
import native_chat_poll as policy

CODE_RVA = 0x1b0000
SIZE = 0x200000
NATIVE = {
    'lunar': dict(file='Adnin.dll', disabled=0x1a68c4, clockAt=0x1a69e0,
        clockIat=0x12f1f0, resetFlag=0x1a69dc, copy=0x3790, compare=0x123fae,
        allocate=0x122e70, move=0x123fb4, release=0x122ec0, event=0x16fd0,
        reset=0x1cdd0, destroy=0x6ba0, log=0x12820),
    'vanilla': dict(file='AdninVanilla.dll', disabled=0x17e946, clockAt=0x17ea68,
        clockIat=0x132170, resetFlag=0x17ea70, copy=0x3670, compare=0x126cae,
        allocate=0x125b70, move=0x126cb4, release=0x125bc0, event=0x170b0,
        reset=0x1d180, destroy=0x6950),
}


def assembled(nasm, profile):
    prelude = 'bits 64\ndefault rel\n%define IMAGE_BASE 0x180000000\n%define CODE_RVA 0x1b0000\norg IMAGE_BASE+CODE_RVA\n'
    if profile == 'vanilla':
        prelude += '%define ADNIN_COMPAT_PROFILE 1\n'
    snapshot = '\n'.join('    mov [rax+%d], %s' % (i*8, name)
        for i, name in enumerate(('rcx','rdx','rbx','rsp','rbp','rsi','rdi',
                                  'r8','r9','r10','r11','r12','r13','r14','r15')))
    snapshot += '\n    mov edx, [rsp+0x40]\n    mov [rax+120], edx\n'
    # RDX itself must remain untouched by the observer.
    snapshot += '    mov rdx, [rax+8]\n'
    snapshot += '\n'.join('    movdqu [rax+%d], xmm%d' % (128+i*16, i) for i in range(16))
    source = prelude + '%include "adnin-chat-poll.asm"\n' + '''
db 'CHPTEST1'
dd fixture_entry-$$, fixture_continue-$$, fixture_stop-$$
fixture_entry:
    push rbp
    push rbx
    push rsi
    push rdi
    push r12
    push r13
    push r14
    push r15
    sub rsp, 0x2b8
    mov r13, rcx
    mov [rsp+0x1a8], rdx
    mov [rsp+0x40], r8d
    mov [rsp+0x1b0], r9
''' + '\n'.join('    movdqu [rsp+%d], xmm%d' % (0x1c0+(i-6)*16, i) for i in range(6,16)) + '\n' + '\n'.join(
        '    mov %s, %d' % (name,0x123400+index) for index,name in enumerate(
            ('rcx','rdx','rbx','rbp','rsi','rdi','r8','r9','r10','r11','r12','r14','r15'))) + '''
    pcmpeqd xmm0, xmm0
''' + '\n'.join('    movdqa xmm%d, xmm0' % i for i in range(1,16)) + '''
    mov rax, [rsp+0x1b0]
''' + snapshot + '''
    jmp chat_poll_tail
fixture_continue:
    mov [rsp+0x1b8], eax
    mov dword [rsp+0x1bc], 0
    jmp fixture_done
fixture_stop:
    mov [rsp+0x1b8], eax
    mov dword [rsp+0x1bc], 1
fixture_done:
    mov rax, [rsp+0x1b0]
    add rax, 512
''' + snapshot + '''
    mov edx, [rsp+0x1b8]
    mov [rax+124], edx
''' + '\n'.join('    movdqu xmm%d, [rsp+%d]' % (i,0x1c0+(i-6)*16) for i in range(6,16)) + '''
    mov eax, [rsp+0x1bc]
    add rsp, 0x2b8
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbx
    pop rbp
    ret
'''
    with tempfile.TemporaryDirectory() as directory:
        src = Path(directory) / 'fixture.asm'
        out = Path(directory) / 'fixture.bin'
        src.write_text(source, encoding='utf-8')
        subprocess.run([str(nasm), '-f', 'bin', '-I', str(ROOT/'src/native')+'/',
                        str(src), '-o', str(out)], check=True, capture_output=True)
        return out.read_bytes()


class ConsumerFixture:
    def __init__(self, binary, profile, nasm, report=None):
        self.profile = profile
        self.spec = spec = policy.PROFILES[profile]
        self.native = native = NATIVE[profile]
        self.pe = pefile.PE(data=binary) if isinstance(binary, bytes) else pefile.PE(str(binary))
        if report is None:
            policy.reviewed_patch(self.pe, profile, CODE_RVA, {'chatPollTail': 20})
        self.keep = []
        self.arena = []
        self.kernel = C.WinDLL('kernel32', use_last_error=True)
        self.kernel.VirtualAlloc.argtypes = [C.c_void_p, C.c_size_t, C.c_uint32, C.c_uint32]
        self.kernel.VirtualAlloc.restype = C.c_void_p
        self.kernel.VirtualFree.argtypes = [C.c_void_p, C.c_size_t, C.c_uint32]
        self.size = max(SIZE,self.pe.OPTIONAL_HEADER.SizeOfImage)
        self.base = self.kernel.VirtualAlloc(None, self.size, 0x3000, 0x40)
        if not self.base:
            raise OSError(C.get_last_error())
        self.next_pointer = 0x1f0000
        # Collector bytes are data for Windows unwind inspection only. Its
        # entry is replaced by the owned fixture before the consumer runs.
        self.write(spec['collector'], self.pe.get_data(spec['collector'], spec['collectorEnd']-spec['collector']))
        self.write(spec['unwind'], self.pe.get_data(spec['unwind'], 64))
        self.write(spec['consumer'], self.pe.get_data(spec['consumer'], spec['consumerEnd']-spec['consumer']))
        payload = assembled(nasm, profile)
        self.write(CODE_RVA, payload)
        begin, end, unwind = struct.unpack_from('<III', payload, payload.index(b'ADNCHP01')+8)
        self.tail_runtime = (CODE_RVA+begin, CODE_RVA+end, CODE_RVA+unwind)
        if report is not None:
            item = next(x for x in report['bridgeRuntimeFunctions'] if x['name']=='chatPollTail')
            production = self.pe.get_data(item['begin'], item['end']-item['begin'])
            # The helper uses only RIP-relative original-image addresses. Move
            # its exact production bytes to their production RVA in our owned
            # allocation, and redirect the fixture's helper entry to it.
            self.write(item['begin'], production)
            self.write(item['unwind'], self.pe.get_data(item['unwind'],16))
            self.jump(CODE_RVA+begin, self.base+item['begin'])
            self.tail_runtime = tuple(item[key] for key in ('begin','end','unwind'))
        test = payload.index(b'CHPTEST1') + 8
        entry, cont, stop = struct.unpack_from('<III', payload, test)
        self.jump(spec['site']+6, self.base+CODE_RVA+cont)
        self.jump(spec['exit'], self.base+CODE_RVA+stop)
        self.tail = C.WINFUNCTYPE(C.c_int, C.c_void_p, C.c_void_p, C.c_int, C.c_void_p)(self.base+CODE_RVA+entry)
        self.snapshot = C.create_string_buffer(1024)
        self.abi_checks = 0
        self.install()
        self.consume = C.WINFUNCTYPE(None, C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p)(self.base+spec['consumer'])

    def should_stop(self, vector, caller, index):
        stop = self.tail(vector, caller, index, C.addressof(self.snapshot))
        before, after = self.snapshot.raw[:384], self.snapshot.raw[512:896]
        if before[:124] != after[:124] or before[128:] != after[128:]:
            raise AssertionError('chat tail modified GPR, XMM, stack, or loop index')
        if not stop and struct.unpack_from('<I',after,124)[0] != ((index+1)&0xffffffff):
            raise AssertionError('chat tail did not replay the displaced index increment')
        self.abi_checks += 1
        return stop

    def write(self, rva, data):
        if not 0 <= rva <= self.size-len(data):
            raise AssertionError(('mock write outside allocation',rva,len(data),self.size))
        C.memmove(self.base+rva, data, len(data))

    def jump(self, rva, target):
        # Original CRT thunks can be only six bytes apart; keep pointer data
        # out of their adjacent slots instead of using a 14-byte inline jump.
        pointer = self.next_pointer
        self.next_pointer += 8
        self.write(pointer, struct.pack('<Q', target))
        self.write(rva, b'\xff\x25'+struct.pack('<i', pointer-rva-6))

    def callback(self, rva, result, args, fn):
        callback = C.WINFUNCTYPE(result, *args)(fn)
        self.keep.append(callback)
        self.jump(rva, C.cast(callback, C.c_void_p).value)

    def alloc(self, size):
        buffer = C.create_string_buffer(max(int(size), 1))
        self.arena.append(buffer)
        return C.addressof(buffer)

    def string(self, target, text):
        data = text.encode('utf-8') if isinstance(text, str) else bytes(text)
        C.memset(target, 0, 32)
        if len(data) <= 15:
            C.memmove(target, data, len(data))
            capacity = 15
        else:
            address = self.alloc(len(data)+1)
            C.memmove(address, data, len(data))
            C.c_uint64.from_address(target).value = address
            capacity = len(data)
        C.c_uint64.from_address(target+16).value = len(data)
        C.c_uint64.from_address(target+24).value = capacity

    @staticmethod
    def read_string(address):
        size = C.c_uint64.from_address(address+16).value
        capacity = C.c_uint64.from_address(address+24).value
        source = C.c_uint64.from_address(address).value if capacity > 15 else address
        return C.string_at(source, size).decode('utf-8')

    def install(self):
        n = self.native
        ptr = C.c_void_p
        self.callback(n['copy'], ptr, [ptr, ptr, C.c_size_t],
                      lambda dest, source, size: (self.string(dest, C.string_at(source, size)), dest)[1])
        self.callback(n['compare'], C.c_int, [ptr, ptr, C.c_size_t],
                      lambda a, b, size: (C.string_at(a, size)>C.string_at(b, size))-(C.string_at(a, size)<C.string_at(b, size)))
        self.callback(n['allocate'], ptr, [C.c_size_t], self.alloc)
        self.callback(n['move'], ptr, [ptr, ptr, C.c_size_t],
                      lambda dest, source, size: C.memmove(dest, source, size))
        self.callback(n['release'], None, [ptr, C.c_size_t], lambda *_: None)
        self.callback(n['destroy'], None, [ptr], lambda *_: None)
        if 'log' in n:
            self.callback(n['log'], None, [ptr], lambda *_: None)
        clock = C.WINFUNCTYPE(C.c_uint64)(lambda: 1000000)
        self.keep.append(clock)
        self.write(n['clockIat'], struct.pack('<Q', C.cast(clock, ptr).value))
        delete = C.WINFUNCTYPE(None, ptr, ptr)(lambda env, ref: self.deleted.append(ref))
        self.keep.append(delete)
        self.vtable = C.create_string_buffer(0x728)
        C.c_uint64.from_address(C.addressof(self.vtable)+0xb8).value = C.cast(delete, ptr).value
        self.env = C.c_uint64(C.addressof(self.vtable))
        self.callback(self.spec['collector'], C.c_ubyte, [ptr, ptr, ptr, ptr, C.c_int], self.collect)
        self.callback(n['event'], None, [ptr]*7, self.event)
        self.callback(n['reset'], None, [ptr]*3, self.reset)

    def collect(self, env, instance, cls, vector, maximum):
        try:
            capacity = min(maximum, len(self.rows))
            data = self.alloc(capacity*0x50)
            C.c_uint64.from_address(vector).value = data
            C.c_uint64.from_address(vector+8).value = data
            C.c_uint64.from_address(vector+16).value = data+capacity*0x50
            count = 0
            for index, row in enumerate(self.rows[:maximum]):
                if row is None:  # null line/component or failed lookup
                    continue
                self.conversions += 1
                counter, text, plain, identifier = row
                if not text and not plain:
                    continue
                at = data+count*0x50
                C.c_int32.from_address(at).value = counter
                self.string(at+8, text)
                self.string(at+40, plain)
                C.c_uint64.from_address(at+72).value = identifier
                count += 1
                C.c_uint64.from_address(vector+8).value = data+count*0x50
                self.retained.append(identifier)
                if self.prune and self.should_stop(vector, self.base+self.spec['periodicCall']+5, index):
                    break
            return bool(count)
        except BaseException as error:
            self.errors.append(repr(error))
            return False

    def event(self, env, instance, cls, gui, component, text, plain):
        self.events.append((component, self.read_string(text), self.read_string(plain)))
        if component in self.reset_on:
            C.c_ubyte.from_address(self.base+self.native['resetFlag']).value = 1

    def reset(self, *_):
        self.resets += 1
        # The real reset collector has max=1, independent of periodic pruning.
        first = self.rows[0] if self.rows else None
        ready = first is not None and bool(first[1] or first[2])
        C.c_ubyte.from_address(self.base+self.spec['initialized']).value = ready
        C.c_int32.from_address(self.base+self.spec['counter']).value = first[0] if ready else -1
        self.string(self.base+self.spec['counter']+8, first[1] if ready else '')

    def run(self, rows, ready, counter, text, prune, reset_on=()):
        self.arena = []
        self.rows, self.prune, self.reset_on = rows, prune, set(reset_on)
        self.events, self.deleted, self.retained, self.errors = [], [], [], []
        self.conversions = self.resets = 0
        for key, width in [('disabled', 1), ('clockAt', 8), ('resetFlag', 1)]:
            C.memset(self.base+self.native[key], 0, width)
        C.c_ubyte.from_address(self.base+self.spec['initialized']).value = ready
        C.c_int32.from_address(self.base+self.spec['counter']).value = counter
        self.string(self.base+self.spec['counter']+8, text)
        self.consume(C.byref(self.env), 1, 2, 3)
        if self.errors:
            raise AssertionError(self.errors)
        if sorted(self.retained) != sorted(self.deleted):
            raise AssertionError(('retained local-reference ownership', self.retained, self.deleted))
        return dict(events=self.events, ready=bool(C.c_ubyte.from_address(self.base+self.spec['initialized']).value),
            counter=C.c_int32.from_address(self.base+self.spec['counter']).value,
            text=self.read_string(self.base+self.spec['counter']+8), resets=self.resets), self.conversions

    def close(self):
        self.kernel.VirtualFree(self.base, 0, 0x8000)


def verify(test, before, after, report, profile):
    original, final = pefile.PE(data=before), pefile.PE(data=after)
    spec = policy.PROFILES[profile]
    patch = report['nativeChatPolling']
    item = next(x for x in report['bridgeRuntimeFunctions'] if x['name']=='chatPollTail')
    test.assertEqual(patch['siteRva'],spec['site'])
    test.assertEqual(patch['width'],6)
    test.assertEqual(final.get_data(spec['site'],6),
                     b'\xe9'+struct.pack('<i',item['begin']-spec['site']-5)+b'\x90')
    test.assertEqual(final.get_data(spec['collector'],spec['site']-spec['collector']),
                     original.get_data(spec['collector'],spec['site']-spec['collector']))
    test.assertEqual(final.get_data(spec['site']+6,spec['collectorEnd']-spec['site']-6),
                     original.get_data(spec['site']+6,spec['collectorEnd']-spec['site']-6))
    test.assertEqual(final.get_data(spec['consumer'],spec['consumerEnd']-spec['consumer']),
                     original.get_data(spec['consumer'],spec['consumerEnd']-spec['consumer']))
    test.assertEqual(final.get_data(spec['resetCall'],5),original.get_data(spec['resetCall'],5))
    test.assertEqual(final.get_data(spec['unwind'],64),original.get_data(spec['unwind'],64))
    test.assertEqual(final.get_data(item['unwind'],16),bytes.fromhex('21000000')+
                     struct.pack('<III',spec['collector'],spec['collectorEnd'],spec['unwind']))
    entries = [(x.struct.BeginAddress,x.struct.EndAddress,x.struct.UnwindData)
               for x in final.DIRECTORY_ENTRY_EXCEPTION]
    test.assertIn(tuple(item[key] for key in ('begin','end','unwind')),entries)
    test.assertIn((spec['collector'],spec['collectorEnd'],spec['unwind']),entries)
    test.assertTrue(patch['fullBoundaryRecordPreserved'])
    test.assertFalse(patch['crossPollComponentCaching'])


def check_chained_unwind(fixture,nasm):
    unwind = C.WinDLL('ntdll').RtlVirtualUnwind
    unwind.argtypes = [C.c_uint32,C.c_uint64,C.c_uint64]+[C.c_void_p]*5
    unwind.restype = C.c_void_p
    spec,base = fixture.spec,fixture.base
    stack = C.create_string_buffer(2048)
    sp = C.addressof(stack)+128
    registers = (15,14,13,12,7,6,3,5)
    for i,reg in enumerate(registers):
        struct.pack_into('<Q',stack,128+0x168+i*8,0xabc000+reg)
    ret = 0x7ffefedc1234
    struct.pack_into('<Q',stack,128+0x1a8,ret)

    def run(runtime,offset):
        context = C.create_string_buffer(1232)
        for reg in range(16):
            struct.pack_into('<Q',context,0x78+reg*8,0xdef000+reg)
        struct.pack_into('<I',context,0x30,0x00100003)
        struct.pack_into('<Q',context,0x98,sp)
        pc=base+runtime[0]+offset
        struct.pack_into('<Q',context,0xf8,pc)
        entry=(C.c_uint32*3)(*runtime)
        handler,frame=C.c_void_p(),C.c_uint64()
        unwind(0,base,pc,C.byref(entry),context,C.byref(handler),C.byref(frame),None)
        values=[struct.unpack_from('<Q',context,0x78+reg*8)[0] for reg in registers]
        state=(struct.unpack_from('<Q',context,0xf8)[0],
               struct.unpack_from('<Q',context,0x98)[0],values,frame.value)
        if state[:3] != (ret,sp+0x1b0,[0xabc000+reg for reg in registers]):
            raise AssertionError(('unwind frame',fixture.profile,hex(pc-base),state))
        return state

    original=(spec['collector'],spec['collectorEnd'],spec['unwind'])
    reference=run(original,0x40)
    begin,end,_=fixture.tail_runtime
    # Test every real instruction boundary, including both fixed conditional
    # exit transfers. A naked JMP can be mistaken for a leaf epilogue even
    # when CHAININFO is present, so branch PCs require actual Windows checks.
    with tempfile.TemporaryDirectory() as directory:
        path=Path(directory)/'chat-tail.bin'
        path.write_bytes(C.string_at(base+begin,end-begin))
        disasm=subprocess.run([str(Path(nasm).with_name('ndisasm.exe')),'-b','64',str(path)],
                              check=True,capture_output=True,text=True).stdout
    positions=[int(line.split()[0],16) for line in disasm.splitlines()
               if re.match(r'^[0-9A-F]{8,16}\s+[0-9A-F]+\s+\S',line)]
    if not positions or positions[0]!=0 or positions[-1]>=end-begin:
        raise AssertionError(('missing helper instruction boundaries',disasm))
    for offset in positions:
        if run(fixture.tail_runtime,offset) != reference:
            raise AssertionError(('chained unwind differs',fixture.profile,offset))
    return len(positions)


def execute(test, binary, report, profile, nasm):
    return run_profile(binary, profile, nasm, report)


def run_profile(binary, profile, nasm, production_report=None):
        fixture = ConsumerFixture(binary, profile, nasm, production_report)
        try:
            cases = [
                ([], False, -1, '', ()),
                ([(4, 'head', 'head', 1), (3, 'old', 'old', 2)], False, -1, '', ()),
                ([(4, 'same', 'same', 1), (3, 'old', 'old', 2)], True, 4, 'same', ()),
                ([(4, 'changed', 'changed', 1), (3, 'old', 'old', 2)], True, 4, 'same', ()),
                ([(4, '\u00a7cstyle', 'style', 1), (3, 'old', 'old', 2)], True, 4, '\u00a7astyle', ()),
                ([(4, 'same', 'metadata changed', 1), (3, 'old', 'old', 2)], True, 4, 'same', ()),
                ([None, (4, '', '', 2), (4, 'visible', 'visible', 3), (3, 'old', 'old', 4)], True, 4, 'before', ()),
                ([(7, 'newer', 'newer', 1), (6, 'new', 'new', 2), (4, 'old', 'old', 3), (99, 'outoforder', 'outoforder', 4)], True, 4, 'old', ()),
                ([(2, 'rollback', 'rollback', 1), (99, 'later', 'later', 2)], True, 4, 'old', ()),
                ([(2, 'rollback', 'rollback', 1), (-2, 'negative', 'negative', 2)], True, -1, 'old', ()),
                ([(7, 'reset', 'reset', 1), (6, 'new', 'new', 2), (4, 'old', 'old', 3)], True, 4, 'old', (1,)),
                ([(0x7fffffff, 'same', 'same', 1), (-0x80000000, 'wrapped', 'wrapped', 2)], True, 0x7fffffff, 'same', ()),
                ([(-0x80000000, 'wrapped', 'wrapped', 1), (0x7fffffff, 'before wrap', 'before wrap', 2)], True, 0x7fffffff, 'old', ()),
                ([(-0x80000000, 'wrapped', 'wrapped', 1), (0x7fffffff, 'before wrap', 'before wrap', 2)], True, -0x80000000, 'old', ()),
            ]
            rng = random.Random(20261001)
            for _ in range(300):
                rows = []
                for i in range(rng.randrange(0, 110)):
                    if rng.randrange(7) == 0:
                        rows.append(None)
                    else:
                        text = rng.choice(['', 'same', '\u00a7cchanged', 'long text '+'x'*30])
                        rows.append((rng.randrange(-2, 10), text, rng.choice(['', text, 'plain']), i+1))
                cases.append((rows, bool(rng.randrange(2)), rng.randrange(-2, 10), rng.choice(['', 'same', '\u00a7cchanged']), ()))
            for index, (rows, ready, counter, text, reset_on) in enumerate(cases):
                original, _ = fixture.run(rows, ready, counter, text, False, reset_on)
                optimized, _ = fixture.run(rows, ready, counter, text, True, reset_on)
                if optimized != original:
                    raise AssertionError((profile, index, original, optimized))
            history = [(100-i, 'unchanged '+str(i), 'unchanged '+str(i), i+1) for i in range(100)]
            original, before = fixture.run(history, True, 100, history[0][1], False)
            optimized, after = fixture.run(history, True, 100, history[0][1], True)
            if original != optimized or (before, after) != (100, 1):
                raise AssertionError((profile, 'conversion count', before, after))
            # Reset/any other collector caller must bypass the optimization.
            vector = fixture.alloc(24)
            rows = fixture.alloc(80)
            C.c_uint64.from_address(vector).value = rows
            C.c_uint64.from_address(vector+8).value = rows+80
            C.c_int32.from_address(rows).value = 0
            if fixture.should_stop(vector, fixture.base+fixture.spec['resetCall']+5, 0):
                raise AssertionError('reset caller was changed')
            unwind_cases = check_chained_unwind(fixture,nasm)
            return dict(equivalenceCases=len(cases), unchangedHistoryConversionsBefore=before,
                unchangedHistoryConversionsAfter=after, retainedRecordsBefore=100, retainedRecordsAfter=1,
                consumerOriginalBytesExecuted=True, helperNativeBytesExecuted=True,
                oldBoundaryFullyConverted=True, resetCallerPreserved=True,
                tailAbiChecks=fixture.abi_checks, chainedUnwindComparisons=unwind_cases,
                productionHelperBytesExecuted=production_report is not None,
                testedDllSha256=hashlib.sha256(binary if isinstance(binary,bytes) else Path(binary).read_bytes()).hexdigest(),
                helperBeginRva=fixture.tail_runtime[0],
                gameOrNetworkAccess=False)
        finally:
            fixture.close()


def run_checks(directory, nasm, production=False):
    result={}
    for profile,native in NATIVE.items():
        report_path=directory.parent/('bridge.json' if profile=='lunar' else 'vanilla-bridge.json')
        report=json.loads(report_path.read_text(encoding='utf-8')) if production else None
        result[profile]=run_profile(directory/native['file'],profile,nasm,report)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=ROOT/'build-v22-lobby-eagle-crash/bin')
    parser.add_argument('--nasm', type=Path, default=ROOT.parents[2]/'https-github-com-freecodexyz-free-code/work/nasm/nasm-3.02/nasm.exe')
    parser.add_argument('--report', type=Path)
    parser.add_argument('--production', action='store_true',
                        help='Execute actual helper bytes using adjacent bridge reports')
    args = parser.parse_args()
    result = run_checks(args.directory, args.nasm, args.production)
    output = json.dumps(result, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output+'\n', encoding='utf-8')
    print(output)
