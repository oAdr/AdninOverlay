"""Append reviewed optional JNI bridges without loading or executing the DLL.

Existing section addresses, exports, imports and base relocations stay fixed.
Original CALL instructions gain optional callbacks that preserve their
original routines. Two Seraph-only prefix calls use a leaf SSO constructor;
the column catalog gains two native-owned descriptors before configuration
ordering. Layout and headers remain driven by that same native column model.
The merged x64 exception table includes the bridge's real unwind records.
Runtime behavior still needs a separate game test.
"""
import argparse
import hashlib
import json
import struct
import subprocess
import tempfile
from pathlib import Path

import pefile
import reembed
import native_anticheat
import native_replay
import native_replay_stats
import native_replay_denick
import native_number_poll
import native_api_policy
import native_tick_hook
import native_headers
import native_scheduler
import native_process_entry

ROOT = Path(__file__).resolve().parents[1]
MAGIC = b'ADNINB01'
MARKER = int.from_bytes(MAGIC, 'little')
META_FIELDS = (
    'version', 'headerSize', 'payloadSize', 'plain', 'json', 'prelayout', 'render',
    'outputBegin', 'outputEnd', 'outputUnwind',
    'invokeBegin', 'invokeEnd', 'invokeUnwind',
    'stringBegin', 'stringEnd', 'stringUnwind',
    'layoutBegin', 'layoutEnd', 'layoutUnwind',
    'renderBegin', 'renderEnd', 'renderUnwind',
    'seraphPrefix',
    'denicker', 'denickerBegin', 'denickerEnd', 'denickerUnwind',
    'metricsBegin', 'metricsEnd', 'metricsUnwind',
    'matchStart', 'matchBegin', 'matchEnd', 'matchUnwind',
    'columnCatalog', 'columnBegin', 'columnEnd', 'columnUnwind',
)
HOOKS = (
    (0x19083, 0x38260, 'plainLocal'), (0x1917c, 0x38260, 'plainLocal'),
    (0x6da3d, 0x38260, 'plain'), (0x6eb40, 0x38260, 'plain'),
    (0x6aeb7, 0x37f60, 'jsonTags'), (0x6b632, 0x38260, 'plainTags'),
    (0x88048, 0x37f60, 'json'), (0x8804f, 0x38260, 'plain'),
    (0x87f1d, 0x38260, 'plain'),  # Skin success bypasses the shared message queue
    (0x8e0e1, 0x8a360, 'prelayout'), (0x8e35f, 0x8c870, 'render'),
    (0x6aa7d, 0x68100, 'seraphPrefix'), (0x6b0dd, 0x68100, 'seraphPrefix'),
    (0x86cff, 0xba530, 'denicker'),
    (0x13f0d, 0x8a240, 'matchStart'),
    (0x5fa4d, 0x5e850, 'columnCatalog'),
    (0x87433, 0x76340, 'replayStats'),
    (0x871f5, 0x72c0, 'replayUuidCopy'),
    (0xbaaca, 0x122b77, 'numberGetLock'), (0xbb597, 0x122b77, 'numberRegisterLock'),
    (0xbbcf7, 0x122b77, 'numberPopLock'),
    (0xa03fc, 0x97b00, 'apiPingProxy'),
    (0x93dd8, 0x2ddb0, 'hypixelHttp'), (0x946c0, 0x2ddb0, 'hypixelHttp'),
    (0xaedf4, 0x2ddb0, 'hypixelHttp'),
    (0x9f1d5, 0xa9e90, 'apiRefreshFailures'),
    (0xd5d3a, 0xc270, 'lunarSchedule'),
)
# Hash of the fixed original .text with only reembed.py's verified operand
# patches normalized to zero. This pins all other original machine code.
TEXT_LINEAGE_SHA256 = 'c35696823c7004ce7567dbc704b4e8adf60e259f57d8f3694fe6ec230cd803cd'
FUNCTION_NAMES = ('output', 'invoke', 'string', 'layout', 'render', 'denicker', 'metrics', 'match', 'column', 'header',
                  'replayRdi', 'replayRbx', 'replayFrame',
                  'replayStats', 'replayStatsQueue', 'replayStatsCleanup', 'replayStatsFrameCleanup',
                  'replayDenickGate', 'replayNickName', 'replayUuidCopy', 'numberLock',
                  'apiKeyReady', 'apiUuidReady', 'apiPingProxy', 'hypixelHttp', 'apiDecodeComponent', 'apiRefreshFailures', 'processEntry')
LUNAR_FUNCTION_NAMES = FUNCTION_NAMES + ('lunarSchedule', 'lunarStop')
LUNAR_UNLOAD_SITE = 0x150da
LUNAR_UNLOAD_BEFORE = bytes.fromhex('ff1548a51100')


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def align(value, boundary):
    require(boundary > 0 and boundary & (boundary - 1) == 0, 'Invalid PE alignment')
    return (value + boundary - 1) & ~(boundary - 1)


def call_bytes(site, target):
    return b'\xe8' + struct.pack('<i', target - site - 5)


def output_routing(hooks):
    return dict(owner='AdninGui4', callback='nativeRenderGeneratedEvent', descriptor='(Ljava/lang/String;ZI)I',
                categories={'players': 0, 'tags': 1, 'anticheatJavaOnly': 2, 'localOnly': 3},
                sources=[dict(callRva=site, json=name.startswith('json'), category=3 if name.endswith('Local') else 1 if name.endswith('Tags') else 0)
                         for site, _, name in hooks if name in ('plain','json','plainTags','jsonTags','plainLocal')],
                classification='verified native producer CALL site, independent of message text')


def normalized_text(data, pe):
    sections = [s for s in pe.sections if s.Name.rstrip(b'\0') == b'.text']
    require(len(sections) == 1, 'Expected one original .text section')
    section = sections[0]
    require(section.VirtualAddress == 0x1000, 'Unexpected original code RVA')
    text = bytearray(section.get_data())
    ranges = []
    for lea, length in reembed.SITES.values():
        ranges.extend(((lea + 3, 4), (length + 4, 4)))
    ranges.extend((rva, len(bytes.fromhex(before))) for rva, (before, _) in reembed.LENGTH_PATCHES.items())
    ranges.extend((rva, len(bytes.fromhex(before))) for rva, (before, _) in reembed.SPLIT_BRAND_PATCHES.items())
    for rva, size in ranges:
        offset = rva - section.VirtualAddress
        require(0 <= offset <= len(text) - size, 'Normalized patch outside original code')
        text[offset:offset + size] = bytes(size)
    return bytes(text)


def read_metadata(payload, code_rva):
    require(len(payload) >= 160 and payload[:8] == MAGIC, 'Invalid bridge metadata marker')
    values = struct.unpack_from('<38I', payload, 8)
    result = dict(zip(META_FIELDS, values))
    require(result['version'] == 2 and result['headerSize'] == 160, 'Unsupported bridge metadata')
    require(result['payloadSize'] == len(payload), 'Bridge payload length mismatch')
    require(not any(values[len(META_FIELDS):]), 'Nonzero bridge reserved metadata')
    for name in ('plain', 'json', 'prelayout', 'render', 'seraphPrefix', 'denicker', 'matchStart', 'columnCatalog'):
        require(result['headerSize'] <= result[name] < len(payload), 'Invalid bridge entry: ' + name)
    output_at = payload.find(b'ADNOUT01')
    require(output_at >= 160 and payload.find(b'ADNOUT01', output_at + 1) < 0
            and output_at + 20 <= len(payload), 'Missing or ambiguous Output category metadata')
    result['plainTags'], result['jsonTags'], result['plainLocal'] = struct.unpack_from('<3I', payload, output_at + 8)
    for name in ('plainTags', 'jsonTags', 'plainLocal'):
        require(160 <= result[name] < len(payload), 'Invalid Output category entry: ' + name)
    header_at = payload.find(b'ADNHDR01')
    require(header_at >= 160 and payload.find(b'ADNHDR01', header_at + 1) < 0
            and header_at + 20 <= len(payload), 'Missing or ambiguous Overlay header metadata')
    begin, end, unwind = struct.unpack_from('<3I', payload, header_at + 8)
    result.update(header=begin, headerBegin=begin, headerEnd=end, headerUnwind=unwind)
    entry_at=payload.find(b'ADNENT01')
    require(entry_at>=160 and payload.find(b'ADNENT01',entry_at+1)<0 and entry_at+20<=len(payload),
            'Missing or ambiguous process-entry metadata')
    begin,end,unwind=struct.unpack_from('<3I',payload,entry_at+8)
    result.update(processEntry=begin,processEntryBegin=begin,processEntryEnd=end,processEntryUnwind=unwind)
    number_at = payload.find(b'ADNNPL01')
    require(number_at >= 160 and payload.find(b'ADNNPL01',number_at+1)<0 and number_at+32<=len(payload),
            'Missing or ambiguous Number polling metadata')
    number_values = struct.unpack_from('<6I',payload,number_at+8)
    for key,value in zip(('numberGetLock','numberRegisterLock','numberPopLock','numberLockBegin','numberLockEnd','numberLockUnwind'),number_values):
        result[key]=value
    for name in ('numberGetLock','numberRegisterLock','numberPopLock'):
        require(160<=result[name]<len(payload),'Invalid Number polling entry: '+name)
    api_at = payload.find(b'ADNAPI01')
    require(api_at >= 160 and payload.find(b'ADNAPI01',api_at+1)<0 and api_at+44<=len(payload),
            'Missing or ambiguous native API policy metadata')
    api_values = struct.unpack_from('<9I',payload,api_at+8)
    for index,name in enumerate(('apiKeyReady','apiUuidReady','apiPingProxy')):
        begin,end,unwind = api_values[index*3:index*3+3]
        result.update({name:begin,name+'Begin':begin,name+'End':end,name+'Unwind':unwind})
    http_at = payload.find(b'ADNHYP01')
    require(http_at >= 160 and payload.find(b'ADNHYP01',http_at+1)<0 and http_at+44<=len(payload),
            'Missing or ambiguous Hypixel request metadata')
    http_values = struct.unpack_from('<9I',payload,http_at+8)
    for index,name in enumerate(('hypixelHttp','apiDecodeComponent','apiRefreshFailures')):
        begin,end,unwind = http_values[index*3:index*3+3]
        result.update({name:begin,name+'Begin':begin,name+'End':end,name+'Unwind':unwind})
    replay_at = payload.find(b'ADNRPL02')
    require(replay_at >= 160 and payload.find(b'ADNRPL02', replay_at + 1) < 0,
            'Missing or ambiguous Replay metadata')
    require(replay_at + 44 <= len(payload), 'Truncated Replay metadata')
    replay_values = struct.unpack_from('<9I', payload, replay_at + 8)
    for index, name in enumerate(('replayRdi', 'replayRbx', 'replayFrame')):
        begin, end, unwind = replay_values[index * 3:index * 3 + 3]
        result.update({name: begin, name + 'Begin': begin, name + 'End': end, name + 'Unwind': unwind})
    stats_at = payload.find(b'ADNRST02')
    require(stats_at >= 160 and payload.find(b'ADNRST02', stats_at + 1) < 0
            and stats_at + 56 <= len(payload), 'Missing or ambiguous Replay stats metadata')
    stats_values = struct.unpack_from('<12I', payload, stats_at + 8)
    for index, name in enumerate(('replayStats', 'replayStatsQueue', 'replayStatsCleanup', 'replayStatsFrameCleanup')):
        begin, end, unwind = stats_values[index * 3:index * 3 + 3]
        result.update({name: begin, name + 'Begin': begin, name + 'End': end, name + 'Unwind': unwind})
    denick_at = payload.find(b'ADNRDN01')
    require(denick_at >= 160 and payload.find(b'ADNRDN01', denick_at + 1) < 0
            and denick_at + 44 <= len(payload), 'Missing or ambiguous Replay Denicker metadata')
    denick_values = struct.unpack_from('<9I', payload, denick_at + 8)
    for index, name in enumerate(('replayDenickGate', 'replayNickName', 'replayUuidCopy')):
        begin, end, unwind = denick_values[index * 3:index * 3 + 3]
        result.update({name: begin, name + 'Begin': begin, name + 'End': end, name + 'Unwind': unwind})
    stop_at = payload.find(b'ADNLST01')
    if stop_at >= 0:
        schedule_at = payload.find(b'ADNSCH01')
        require(schedule_at >= 160 and payload.find(b'ADNSCH01', schedule_at+1) < 0
                and schedule_at+20 <= len(payload), 'Missing or ambiguous Lunar scheduler metadata')
        begin,end,unwind = struct.unpack_from('<3I',payload,schedule_at+8)
        result.update(lunarSchedule=begin,lunarScheduleBegin=begin,lunarScheduleEnd=end,lunarScheduleUnwind=unwind)
        require(stop_at >= 160 and payload.find(b'ADNLST01', stop_at + 1) < 0 and stop_at + 20 <= len(payload),
                'Invalid Lunar stop metadata')
        begin, end, unwind = struct.unpack_from('<3I', payload, stop_at + 8)
        result.update(lunarStop=begin, lunarStopBegin=begin, lunarStopEnd=end, lunarStopUnwind=unwind)
    for name in (LUNAR_FUNCTION_NAMES if stop_at >= 0 else FUNCTION_NAMES):
        begin, end, unwind = (result[name + suffix] for suffix in ('Begin', 'End', 'Unwind'))
        require(result['headerSize'] <= begin < end <= len(payload), 'Invalid bridge function: ' + name)
        require(unwind % 4 == 0 and end <= unwind < len(payload), 'Invalid bridge unwind: ' + name)
        handlers = {'replayStats': 'replayStatsFrameCleanup', 'replayStatsQueue': 'replayStatsCleanup'}
        expected_flags = 0x11 if name in handlers else 1
        require(payload[unwind] == expected_flags, 'Unexpected bridge unwind flags: ' + name)
        code_count = payload[unwind + 2]
        tail = unwind + 4 + align(code_count, 2) * 2
        require(tail <= len(payload), 'Truncated bridge unwind')
        if name in handlers:
            require(tail + 4 <= len(payload)
                    and struct.unpack_from('<I', payload, tail)[0] == code_rva + result[handlers[name]],
                    'Replay stats cleanup handler changed: ' + name)
    return result


def compile_bridge(nasm, rva, image_base):
    with tempfile.TemporaryDirectory(prefix='adnin-bridge-') as directory:
        output = Path(directory) / 'bridge.bin'
        command = [str(nasm), '-f', 'bin', '-Ox', '-I' + str(ROOT / 'src/native') + '/', '-DIMAGE_BASE=' + hex(image_base),
                   '-DCODE_RVA=' + hex(rva), str(ROOT / 'src/native/adnin-bridge.asm'), '-o', str(output)]
        result = subprocess.run(command, capture_output=True, text=True)
        require(result.returncode == 0, 'NASM bridge build failed:\n' + result.stdout + result.stderr)
        payload = output.read_bytes()
    return payload, read_metadata(payload, rva)


def rebuild(data, nasm):
    pe = pefile.PE(data=data)
    require(pe.FILE_HEADER.Machine == 0x8664 and pe.OPTIONAL_HEADER.Magic == 0x20b,
            'Expected x64 PE32+ DLL')
    require(pe.OPTIONAL_HEADER.ImageBase == 0x180000000, 'Unexpected preferred image base')
    require(pe.FILE_HEADER.Characteristics & 0x2000, 'Expected a DLL')
    require(pe.sections[-1].Name.rstrip(b'\0') == b'.adnin', 'Run the Java reembedding stage first')
    require(sha(normalized_text(data, pe)) == TEXT_LINEAGE_SHA256,
            'Original native code lineage mismatch; refusing unreviewed machine code')
    legacy_anticheat = native_anticheat.reviewed_patch(pe, 'lunar')
    game_tick_hook = native_tick_hook.reviewed_patch(pe, 'lunar')
    require(pe.get_data(LUNAR_UNLOAD_SITE, 6) == LUNAR_UNLOAD_BEFORE
            and pe.get_data(0x150d5, 5) == bytes.fromhex('b923000000')
            and pe.get_data(0x150e0, 5) == bytes.fromhex('6685c0753b')
            and pe.get_data(0x14d2e, 5) == bytes.fromhex('488d542430')
            and pe.get_data(0x1515b, 5) == bytes.fromhex('488b4c2430'),
            'Lunar End-key polling or initializer JNIEnv stack slot changed')
    require(pe.OPTIONAL_HEADER.DATA_DIRECTORY[4].VirtualAddress == 0,
            'Signed PE requires a separate reviewed signing workflow')
    for site, target, _ in HOOKS:
        require(pe.get_data(site, 5) == call_bytes(site, target),
                'Original CALL bytes changed at RVA ' + hex(site))
    require(pe.get_data(0x87bfc, 13) == bytes.fromhex('e81f2a020084c00f8430030000')
            and pe.get_data(0x87f0a, 19) == bytes.fromhex('4c8d8d302500004d8b4608488b542458498bcd'),
            'Skin success-only guard or plain-chat ABI changed')
    file_alignment, section_alignment = pe.OPTIONAL_HEADER.FileAlignment, pe.OPTIONAL_HEADER.SectionAlignment
    raw_end = max(s.PointerToRawData + s.SizeOfRawData for s in pe.sections)
    require(raw_end == len(data), 'Unexpected PE overlay after existing sections')
    rva = align(max(s.VirtualAddress + max(s.Misc_VirtualSize, s.SizeOfRawData) for s in pe.sections), section_alignment)
    raw = align(len(data), file_alignment)
    payload, meta = compile_bridge(nasm, rva, pe.OPTIONAL_HEADER.ImageBase)
    replay_overlay = native_replay.reviewed_patches(pe, 'lunar', rva, meta)
    replay_stats = native_replay_stats.reviewed_hook(pe, 'lunar', rva, meta)
    replay_denick = native_replay_denick.reviewed_patch(pe, 'lunar', rva, meta)
    number_polling = native_number_poll.reviewed_patches(pe, 'lunar', rva, meta)
    api_policy = native_api_policy.reviewed_patches(pe, 'lunar', rva, meta)
    header_localization = native_headers.reviewed_patches(pe, 'lunar', rva, meta)
    scheduler = native_scheduler.reviewed_hook(pe,rva,meta)
    process_entry = native_process_entry.reviewed_patch(pe,'lunar',rva,meta)

    exception = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
    require(exception.VirtualAddress and exception.Size and exception.Size % 12 == 0,
            'Missing or malformed original x64 exception table')
    old_table = pe.get_data(exception.VirtualAddress, exception.Size)
    require(len(old_table) == exception.Size, 'Truncated original exception table')
    old_functions = list(struct.iter_unpack('<III', old_table))
    require(all(a < b for a, b, _ in old_functions), 'Invalid original runtime-function range')
    require(old_functions == sorted(old_functions), 'Original runtime-function table is not sorted')
    functions = list(old_functions)
    bridge_functions = []
    for name in LUNAR_FUNCTION_NAMES:
        entry = tuple(rva + meta[name + suffix] for suffix in ('Begin', 'End', 'Unwind'))
        functions.append(entry)
        bridge_functions.append(dict(name=name, begin=entry[0], end=entry[1], unwind=entry[2]))
    functions.sort()
    require(all(functions[i][1] <= functions[i + 1][0] for i in range(len(functions) - 1)),
            'Overlapping runtime-function records')
    table_offset = align(len(payload), 4)
    table = b''.join(struct.pack('<III', *entry) for entry in functions)
    section_data = payload + bytes(table_offset - len(payload)) + table
    raw_size = align(len(section_data), file_alignment)
    image_size = align(rva + len(section_data), section_alignment)
    section_header = pe.sections[-1].get_file_offset() + 40
    require(section_header + 40 <= pe.OPTIONAL_HEADER.SizeOfHeaders,
            'No room for bridge section header')
    require(not any(data[section_header:section_header + 40]), 'Section header padding is occupied')
    result = bytearray(data)
    patches = []

    def patch(offset, replacement, reason):
        before = bytes(result[offset:offset + len(replacement)])
        require(len(before) == len(replacement), 'Original-byte patch outside file')
        result[offset:offset + len(replacement)] = replacement
        patches.append(dict(offset=offset, before=before.hex(), after=replacement.hex(), reason=reason))

    hooks = []
    patch(process_entry['offset'],bytes.fromhex(process_entry['after']),process_entry['reason'])
    for site, target, name in HOOKS:
        replacement = rva + meta[name]
        patch(pe.get_offset_from_rva(site), call_bytes(site, replacement), 'Optional JNI ' + name + ' bridge')
        hooks.append(dict(callRva=site, originalTargetRva=target, bridgeTargetRva=replacement, callback=name))
    patch(pe.get_offset_from_rva(legacy_anticheat['callRva']), native_anticheat.REPLACEMENT,
          legacy_anticheat['reason'])
    patch(pe.get_offset_from_rva(game_tick_hook['branchRva']), bytes.fromhex(game_tick_hook['after']),
          game_tick_hook['reason'])
    for item in replay_overlay:
        patch(pe.get_offset_from_rva(item['predicateRva']), bytes.fromhex(item['after']), item['reason'])
    patch(pe.get_offset_from_rva(replay_denick['gateRva']), bytes.fromhex(replay_denick['after']), replay_denick['reason'])
    for item in number_polling:
        patch(pe.get_offset_from_rva(item['branchRva']),bytes.fromhex(item['after']),item['reason'])
    for item in api_policy['patches']:
        patch(pe.get_offset_from_rva(item['siteRva']),bytes.fromhex(item['after']),item['reason'])
    for item in header_localization['patches']:
        patch(pe.get_offset_from_rva(item['siteRva']), bytes.fromhex(item['after']), item['reason'])
    patch(pe.get_offset_from_rva(LUNAR_UNLOAD_SITE), call_bytes(LUNAR_UNLOAD_SITE, rva + meta['lunarStop']) + b'\x90',
          'Require Java game-module stop acknowledgement before Lunar End-key cleanup/unload')
    patch(pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),
          struct.pack('<H', pe.FILE_HEADER.NumberOfSections + 1), 'Append executable bridge section')
    patch(pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'), struct.pack('<I', image_size), 'Bridge image size')
    patch(pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfCode'),
          struct.pack('<I', pe.OPTIONAL_HEADER.SizeOfCode + raw_size), 'Bridge code size')
    patch(exception.get_file_offset(), struct.pack('<II', rva + table_offset, len(table)), 'Merged x64 exception table')
    header = struct.pack('<8sIIIIIIHHI', b'.adncode', len(section_data), rva, raw_size, raw, 0, 0, 0, 0, 0x60000020)
    patch(section_header, header, 'Read-execute bridge section header')
    result.extend(bytes(raw - len(result)))
    result.extend(section_data)
    result.extend(bytes(raw_size - len(section_data)))
    checksum_offset = pe.OPTIONAL_HEADER.get_field_absolute_offset('CheckSum')
    checksum = pefile.PE(data=bytes(result)).generate_checksum()
    patch(checksum_offset, struct.pack('<I', checksum), 'PE checksum')
    final = bytes(result)
    checked = pefile.PE(data=final)
    require(checked.OPTIONAL_HEADER.CheckSum == checked.generate_checksum(), 'Checksum self-check failed')
    require(checked.get_data(rva, 8) == MAGIC, 'Bridge marker self-check failed')
    require(len(checked.sections) == len(pe.sections) + 1, 'Bridge section self-check failed')
    report = dict(inputSha256=sha(data), outputSha256=sha(final), fixedNativeSha256=reembed.FIXED_SHA256,
                  normalizedNativeTextSha256=TEXT_LINEAGE_SHA256, imageSize=image_size,
                  section=dict(name='.adncode', rva=rva, offset=raw, size=len(section_data), rawSize=raw_size,
                               executable=True, writable=False), marker=hex(MARKER), metadata=meta, hooks=hooks,
                  originalRuntimeFunctions=len(old_functions), bridgeRuntimeFunctions=bridge_functions,
                  runtimeFunctionTable=dict(rva=rva + table_offset, size=len(table), count=len(functions)),
                  patches=patches, legacyAnticheat=legacy_anticheat, gameTickHook=game_tick_hook,
                  replayOverlay=replay_overlay, replayStats=replay_stats, replayDenicker=replay_denick, numberPolling=number_polling,
                  nativeApiPolicy=api_policy,
                  headerLocalization=header_localization, schedulerLocalReference=scheduler,
                  processTerminationGuard=process_entry,
                  outputRouting=output_routing(HOOKS),
                  lunarUnloadGuard=dict(callRva=LUNAR_UNLOAD_SITE, bridgeTargetRva=rva + meta['lunarStop'],
                    originalIatRva=0x12f628, stopOwner='AdninGui4', stopMethod='nativeStopGameModules',
                    descriptor='()I', requiredAcknowledgement=1, initializerEnvStackOffset=0x30,
                    failureAction='return no End key; defer native cleanup and unload'),
                  gameRuntimeTested=False, originalDllExecutedByBuild=False,
                  skinSuccessOutput=dict(callRva=0x87f1d, successGetterCallRva=0x87bfc,
                                         successGetterRva=0xaa620, noSuccessSkipRva=0x87f39,
                                         format='colored plain std::string', callback='nativeRenderGeneratedEvent'))
    return final, report


def generated_header(report):
    return ('// Generated by scripts/bridge.py; do not edit.\n#pragma once\n#include <cstdint>\n'
            f'inline constexpr std::uint32_t ADNIN_IMAGE_SIZE = 0x{report["imageSize"]:x}u;\n'
            f'inline constexpr std::uint32_t ADNIN_BRIDGE_RVA = 0x{report["section"]["rva"]:x}u;\n'
            f'inline constexpr std::uint64_t ADNIN_BRIDGE_MARKER = 0x{MARKER:x}ull;\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('input', 'output', 'report', 'header', 'nasm'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    require(args.input.resolve() != args.output.resolve(), 'Do not overwrite the intermediate DLL')
    require(len({args.output.resolve(), args.report.resolve(), args.header.resolve()}) == 3,
            'Output DLL, report and header need different paths')
    output, report = rebuild(args.input.read_bytes(), args.nasm)
    for path in (args.output, args.report, args.header):
        path.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(output)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
    args.header.write_text(generated_header(report), encoding='utf8')
    print('Added ' + str(len(report['hooks'])) + ' native call hooks, four Replay predicates and '
          + str(len(LUNAR_FUNCTION_NAMES)) + ' x64 unwind records: ' + str(args.output))


if __name__ == '__main__':
    main()
