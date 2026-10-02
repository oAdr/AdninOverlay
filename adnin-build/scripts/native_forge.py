"""Add an owned SRG lookup facade to the hash-pinned compatibility runtime."""
import hashlib
import json
from pathlib import Path
import struct
import subprocess

import pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_64
from capstone.x86 import X86_OP_MEM, X86_OP_IMM, X86_REG_RIP

ROOT = Path(__file__).resolve().parents[1]
LOOKUPS = {0x30: 'find', 0x108: 'method', 0x388: 'static_method',
           0x2f0: 'field', 0x480: 'static_field'}
# JavaVM::GetEnv shares offset 0x30 with JNIEnv::FindClass. These calls receive
# the pinned VM global, a stack output pointer and JNI_VERSION_1_6.
JAVA_VM_GET_ENV = {
    0x1d3d2: (0x1d3af, '488b0d5a101600488d54243048c74424300000000041b80600010048895c2420488b01ff5030'),
    0x1d4f5: (0x1d4d0, '488b0d390f1600488d55284c897c245841b8060001004533ff4c897424504c897d28488b01ff5030'),
    0x1d7d4: (0x1d7b1, '488b0d580c1600488d54243048c74424300000000041b80600010048897c2420488b01ff5030'),
}

def require(value, message):
    if not value: raise ValueError(message)

def align(value, size): return (value+size-1)//size*size

def compile_facade(out, toolchain, jdk, state_link_rva):
    compiler = Path(toolchain)/'bin/clang++.exe'
    target = out/'forge-facade.dll'
    command = [compiler, '-shared', '-O2', '-ffreestanding', '-fno-exceptions', '-fno-rtti', '-funwind-tables',
        '-fno-stack-protector', '-fno-builtin', '-nostdlib', '-Wl,--entry,adnin_forge_entry',
        '-Wl,--image-base,0x180000000', '-Wl,--no-insert-timestamp', '-Wl,--build-id=none',
        '-DFORGE_STATE_LINK_RVA='+hex(state_link_rva), '-I'+str(out),
        '-I'+str(Path(jdk)/'include'), '-I'+str(Path(jdk)/'include/win32'),
        str(ROOT/'src/native/forge-jni.cpp'), '-o', str(target)]
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True)
    require(result.returncode == 0, 'Forge JNI facade compilation failed:\n'+result.stdout+result.stderr)
    image = pefile.PE(data=target.read_bytes())
    require(not getattr(image, 'DIRECTORY_ENTRY_IMPORT', []), 'Forge facade must not import another library')
    require(all(item.type in (0,10) for block in getattr(image,'DIRECTORY_ENTRY_BASERELOC',[])
                for item in block.entries), 'Forge facade has unsupported relocations')
    text = [s for s in image.sections if s.Name.rstrip(b'\0') == b'.text']
    require(len(text) == 1 and text[0].VirtualAddress == 0x1000, 'Forge facade code layout differs')
    require(all(s.Misc_VirtualSize == 0 or s.Name.rstrip(b'\0') in (b'.text', b'.rdata', b'.pdata', b'.edata', b'.reloc', b'.buildid')
                for s in image.sections), 'Forge facade has unexpected mutable data')
    exports = {item.name.decode():item.address for item in image.DIRECTORY_ENTRY_EXPORT.symbols}
    require(set(exports) == {'adnin_forge_'+name for name in LOOKUPS.values()}, 'Forge facade exports differ')
    end = max(s.VirtualAddress+s.Misc_VirtualSize for s in image.sections)
    return image, image.get_memory_mapped_image()[0x1000:end], exports

def lookup_sites(image):
    """Limit decoding to recovered game routines and their direct leaf wrappers."""
    for start, expected in JAVA_VM_GET_ENV.values():
        require(image.get_data(start,len(bytes.fromhex(expected))) == bytes.fromhex(expected),
                'Pinned JavaVM GetEnv context changed: '+hex(start))
    decoder = Cs(CS_ARCH_X86, CS_MODE_64); decoder.detail = True
    decoded, leaf_targets, branches = {}, set(), set()
    functions = []
    for item in image.DIRECTORY_ENTRY_EXCEPTION:
        lo, hi, unwind = item.struct.BeginAddress, item.struct.EndAddress, item.struct.UnwindData
        if lo >= 0xd8000: continue
        ins = list(decoder.disasm(image.get_data(lo, hi-lo), lo))
        functions.append((lo, hi, unwind, ins))
        for index, instruction in enumerate(ins):
            decoded[instruction.address] = (lo, hi, unwind, ins, index)
            if instruction.mnemonic.startswith('j') or instruction.mnemonic == 'call':
                if instruction.operands and instruction.operands[0].type == X86_OP_IMM:
                    destination = instruction.operands[0].imm
                    branches.add(destination)
                    if instruction.mnemonic == 'call': leaf_targets.add(destination)
    # Tiny JNI forwarding wrappers have no RUNTIME_FUNCTION record.
    for target in sorted(leaf_targets):
        if target in decoded or not 0x1000 <= target < 0xd8000: continue
        ins = list(decoder.disasm(image.get_data(target, 16), target))[:2]
        if len(ins) != 2 or ins[0].mnemonic != 'mov' or ins[0].op_str != 'rax, qword ptr [rcx]': continue
        instruction = ins[1]
        if instruction.mnemonic != 'jmp' or instruction.operands[0].type != X86_OP_MEM: continue
        if instruction.operands[0].mem.disp not in LOOKUPS: continue
        for index, instruction in enumerate(ins): decoded[instruction.address] = (target, target+sum(i.size for i in ins), 0, ins, index)
    sites, occupied = [], set()
    for address, (lo, hi, unwind, ins, index) in sorted(decoded.items()):
        if address in JAVA_VM_GET_ENV: continue
        instruction = ins[index]
        if instruction.mnemonic not in ('call', 'jmp') or instruction.operands[0].type != X86_OP_MEM: continue
        memory = instruction.operands[0].mem
        if memory.index or memory.disp not in LOOKUPS: continue
        start = index
        size = instruction.size
        while size < 5 and start:
            start -= 1; size += ins[start].size
        require(size >= 5, 'Short JNI lookup cannot be patched: '+hex(address))
        before = ins[start:index]
        require(not any(i.mnemonic.startswith('j') or i.mnemonic == 'call' for i in before), 'JNI lookup crosses control flow')
        begin = ins[start].address
        require(not any(begin < target <= address for target in branches), 'JNI lookup span has an internal branch target')
        require(not occupied.intersection(range(begin, address+instruction.size)), 'Overlapping JNI lookup span')
        occupied.update(range(begin, address+instruction.size))
        sites.append(dict(begin=begin, end=address+instruction.size, lookup=address,
                          kind=LOOKUPS[memory.disp], instruction=instruction, before=before, unwind=unwind,
                          ownerBegin=lo, ownerEnd=hi))
    counts = {name:sum(s['kind']==name for s in sites) for name in LOOKUPS.values()}
    require(all(counts[name] >= minimum for name, minimum in
                {'find':90,'method':280,'static_method':40,'field':160,'static_field':100}.items()),
            'Incomplete recovered JNI lookup coverage: '+str(counts))
    return sites, counts

def relocate(instruction, destination):
    raw = bytearray(instruction.bytes)
    for operand in instruction.operands:
        if operand.type == X86_OP_MEM and operand.mem.base == X86_REG_RIP:
            require(instruction.disp_size == 4, 'Unexpected RIP-relative operand width')
            target = instruction.address+instruction.size+operand.mem.disp
            struct.pack_into('<i', raw, instruction.disp_offset, target-destination-instruction.size)
    return raw

def unwind_operations(image, unwind):
    header = image.get_data(unwind, 4)
    require(header[0]&7 == 1, 'Unsupported Forge owner unwind version')
    codes = image.get_data(unwind+4, header[2]*2)
    operations, index = [], 0
    while index < header[2]:
        code, info = codes[index*2:index*2+2]
        opcode, argument = info&15, info>>4
        if opcode == 1:
            require(argument in (0,1), 'Invalid large stack allocation unwind')
            slots = 2 if argument == 0 else 3
        elif opcode in (4,8): slots = 2
        elif opcode in (5,9): slots = 3
        else:
            require(opcode in (0,2,3,10), 'Unsupported Forge owner unwind opcode')
            slots = 1
        require(index+slots <= header[2], 'Truncated Forge owner unwind operation')
        operations.append((code,codes[index*2:(index+slots)*2]))
        index += slots
    return header, operations

def thunk_unwind(image, site):
    header, operations = unwind_operations(image,site['unwind'])
    call_offset = site['lookup']-site['ownerBegin']
    if call_offset >= header[1]:
        return bytes([0x21,0,0,0])+struct.pack('<III',site['ownerBegin'],site['ownerEnd'],site['unwind'])
    # A moved call can precede later saves in a split prologue. Snapshot only
    # completed saves; their offsets follow the displaced instructions here.
    require(header[0]>>3 == 4 and header[3] == 0,
            'Unsupported partial Forge owner unwind')
    begin_offset = site['begin']-site['ownerBegin']
    codes = bytearray()
    for completed, operation in operations:
        if completed > call_offset: continue
        translated = max(0,completed-begin_offset)
        require(translated <= site['lookup']-site['begin'], 'Forge thunk unwind offset exceeds its prologue')
        codes.extend(bytes([translated])+operation[1:])
    parent = image.get_data(site['unwind']+align(4+header[2]*2,4),12)
    require(len(parent) == 12, 'Truncated Forge owner unwind chain')
    result = bytearray([0x21,site['lookup']-site['begin'],len(codes)//2,0])
    result.extend(codes)
    result.extend(bytes(align(len(result),4)-len(result)))
    result.extend(parent)
    return result

def build(data, output, toolchain, jdk, mapper_class):
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    bytecode = Path(mapper_class).read_bytes()
    (output/'forge-mapper-bytes.h').write_text('static const unsigned char ADNIN_FORGE_MAPPER[] = {'
        +','.join(str(b) for b in bytecode)+'};\n', encoding='ascii')
    original = pefile.PE(data=data)
    sites, counts = lookup_sites(original)
    code_rva = align(original.OPTIONAL_HEADER.SizeOfImage, 0x1000)
    first, first_code, _ = compile_facade(output, toolchain, jdk, 0x100000)
    # Worst-case space includes displaced instructions, transfers and chain records.
    state_rva = align(code_rva+len(first_code)+len(sites)*64
            +len(original.DIRECTORY_ENTRY_EXCEPTION)*12
            +original.OPTIONAL_HEADER.DATA_DIRECTORY[5].Size+0x2000, 0x1000)
    facade, code, exports = compile_facade(output, toolchain, jdk, state_rva-code_rva+0x1000)
    require(len(code) == len(first_code), 'Forge state address changed facade layout')
    require(facade.OPTIONAL_HEADER.ImageBase == original.OPTIONAL_HEADER.ImageBase,
            'Forge facade and runtime image bases differ')
    delta = code_rva-0x1000
    records = [(x.struct.BeginAddress+delta,x.struct.EndAddress+delta,x.struct.UnwindData+delta)
               for x in facade.DIRECTORY_ENTRY_EXCEPTION]
    payload = bytearray(code)
    relocation_entries = []
    for block in getattr(facade,'DIRECTORY_ENTRY_BASERELOC',[]):
        for item in block.entries:
            if item.type == 0: continue
            require(0x1000<=item.rva<=0x1000+len(payload)-8, 'Forge facade relocation is outside copied sections')
            at=item.rva-0x1000
            value=struct.unpack_from('<Q',payload,at)[0]
            struct.pack_into('<Q',payload,at,value+delta)
            relocation_entries.append((item.rva+delta,item.type))
    edits = []
    for site in sites:
        target = exports['adnin_forge_'+site['kind']]+delta
        size = site['end']-site['begin']
        if not site['before']:
            replacement = bytes([0xe8 if site['instruction'].mnemonic=='call' else 0xe9])+struct.pack('<i',target-site['begin']-5)
        elif not site['unwind'] and site['instruction'].mnemonic == 'jmp':
            replacement = b'\xe9'+struct.pack('<i',target-site['begin']-5)
        else:
            require(site['instruction'].mnemonic == 'call', 'Unexpected displaced JNI tail call')
            while len(payload)%16: payload.append(0x90)
            thunk = code_rva+len(payload)
            for instruction in site['before']: payload.extend(relocate(instruction,code_rva+len(payload)))
            at = code_rva+len(payload)
            payload.extend(b'\xe8'+struct.pack('<i',target-at-5))
            # A return PC directly on the jump is treated as a leaf epilogue
            # by Windows, bypassing the original owner's chained unwind.
            payload.append(0x90)
            at = code_rva+len(payload)
            payload.extend(b'\xe9'+struct.pack('<i',site['end']-at-5))
            thunk_end = code_rva+len(payload)
            while len(payload)%4: payload.append(0)
            chain = code_rva+len(payload)
            payload.extend(thunk_unwind(original,site))
            records.append((thunk,thunk_end,chain))
            replacement = b'\xe9'+struct.pack('<i',thunk-site['begin']-5)
        replacement += b'\x90'*(size-len(replacement))
        edits.append(dict(rva=site['begin'],lookupRva=site['lookup'],kind=site['kind'],
                          before=original.get_data(site['begin'],size).hex(),after=replacement.hex()))
    # Existing and new unwind records share one sorted read-only table.
    records += [(x.struct.BeginAddress,x.struct.EndAddress,x.struct.UnwindData)
                for x in original.DIRECTORY_ENTRY_EXCEPTION]
    records.sort()
    require(all(a[1]<=b[0] for a,b in zip(records,records[1:])), 'Forge unwind ranges overlap')
    while len(payload)%4: payload.append(0)
    table_rva = code_rva+len(payload)
    payload.extend(b''.join(struct.pack('<III',*r) for r in records))
    while len(payload)%4: payload.append(0)
    relocation_rva=code_rva+len(payload)
    for block in original.DIRECTORY_ENTRY_BASERELOC:
        relocation_entries.extend((item.rva,item.type) for item in block.entries if item.type)
    groups={}
    for rva,kind in sorted(set(relocation_entries)):
        groups.setdefault(rva&~0xfff,[]).append((kind<<12)|(rva&0xfff))
    for page,entries in sorted(groups.items()):
        if len(entries)%2: entries.append(0)
        payload.extend(struct.pack('<II',page,8+len(entries)*2))
        payload.extend(struct.pack('<'+'H'*len(entries),*entries))
    relocation_size=code_rva+len(payload)-relocation_rva
    require(code_rva+len(payload)<=state_rva, 'Forge facade exceeded its reserved code extent')
    result = bytearray(data)
    for item in edits:
        at = original.get_offset_from_rva(item['rva'])
        result[at:at+len(bytes.fromhex(item['after']))] = bytes.fromhex(item['after'])
    file_align = original.OPTIONAL_HEADER.FileAlignment
    raw = align(len(result),file_align); result.extend(bytes(raw-len(result)))
    result.extend(payload); result.extend(bytes(align(len(result),file_align)-len(result)))
    state_raw = len(result); result.extend(bytes(file_align))
    header = original.sections[-1].get_file_offset()+40
    require(header+80<=original.OPTIONAL_HEADER.SizeOfHeaders and not any(result[header:header+80]), 'No Forge section header capacity')
    # Cover the reserved virtual extent; Windows rejects a gap between sections.
    result[header:header+40] = struct.pack('<8sIIIIIIHHI',b'.adnf',state_rva-code_rva,code_rva,
            state_raw-raw,raw,0,0,0,0,0x60000020)
    result[header+40:header+80] = struct.pack('<8sIIIIIIHHI',b'.adnfs',32,state_rva,
            file_align,state_raw,0,0,0,0,0xc0000040)
    struct.pack_into('<H',result,original.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),len(original.sections)+2)
    image_size = align(state_rva+32,0x1000)
    struct.pack_into('<I',result,original.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),image_size)
    struct.pack_into('<I',result,original.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfCode'),
                    original.OPTIONAL_HEADER.SizeOfCode+state_raw-raw)
    struct.pack_into('<I',result,original.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfInitializedData'),
                    original.OPTIONAL_HEADER.SizeOfInitializedData+file_align)
    exception = original.OPTIONAL_HEADER.DATA_DIRECTORY[3].get_file_offset()
    struct.pack_into('<II',result,exception,table_rva,len(records)*12)
    relocation_directory=original.OPTIONAL_HEADER.DATA_DIRECTORY[5].get_file_offset()
    struct.pack_into('<II',result,relocation_directory,relocation_rva,relocation_size)
    struct.pack_into('<I',result,original.OPTIONAL_HEADER.get_field_absolute_offset('CheckSum'),0)
    final = pefile.PE(data=bytes(result))
    require(not any(s.Characteristics&0x20000000 and s.Characteristics&0x80000000 for s in final.sections), 'Forge image has writable executable code')
    report = dict(profile='forge-srg-1.8.9',inputSha256=hashlib.sha256(data).hexdigest(),
        lookupCounts=counts,patches=edits,facadeCodeRva=code_rva,stateRva=state_rva,
        mapperSha256=hashlib.sha256(bytecode).hexdigest(),imageSize=image_size,
        jvmTableUnchanged=True,gameClassesUnchanged=True,otherNativeModulesUnchanged=True,
        preservedJavaVmGetEnvCalls=sorted(JAVA_VM_GET_ENV),
        unwindRecords=len(records),runtimeGameTested=False)
    return bytes(result), report
