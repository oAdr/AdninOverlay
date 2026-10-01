"""Hash-pinned original 1.8.9 native profile; static build, never executes input."""
from pathlib import Path
import json
import struct
import subprocess
import tempfile
import pefile
import bridge
import reembed
import native_anticheat
import native_replay
import native_replay_stats
import native_replay_denick
import native_number_poll
import native_api_policy
import native_tick_hook

FIXED_SHA256 = '4b66c2f91c91d7c5798488b487c5b1110d4ae74364a1ad2cd3cbae72d1e49d1d'
SITES = {
    'FrenchifyTabOverlayNative': (0x11d8b, 0x11d95),
    'FrenchifyGuiNewChat': (0x11699, 0x116a3),
    'FrenchifyGui4': (0x14fa8, 0x14fa0),
    'FrenchifyRenderGlobal': (0x4a16a, 0x4a183),
    'FrenchifyHitboxInstaller': (0x4a264, 0x4a26e),
    'FrenchifyIngameGui': (0xc5942, 0xc594c),
    'FrenchifySessionHud': (0xc5b29, 0xc5b33),
    'FrenchifySessionHudInstaller': (0xc5a34, 0xc5a3e),
    'FrenchifyPacketLog': (0xd1e51, 0xd1e5b),
}
LENGTH_PATCHES = {
    0x11179: ('4883f809', '4883f805'), 0x11183: ('41b909000000', '41b905000000'),
    0x111c3: ('4883f909', '4883f905'), 0x111cd: ('41b909000000', '41b905000000'),
    0x1261d: ('4883f809', '4883f805'), 0x12627: ('41b909000000', '41b905000000'),
    0x12660: ('4883f809', '4883f805'), 0x1266a: ('41b909000000', '41b905000000'),
}
SPLIT_BRAND_PATCHES = {
    0x41167: ('c7040831467265', 'c704083141646e'),
    0x41231: ('488d4104', '488d4103'),
    0x41246: ('c70408666e6368', 'c7040866696e00'),
    0x41258: ('48c744242004000000', '48c744242003000000'),
    0x4126b: ('ba04000000', 'ba03000000'),
    0x41310: ('488d4104', '488d4101'),
    0x41325: ('c7040863696679', 'c7040872000000'),
    0x41337: ('48c744242004000000', '48c744242001000000'),
    0x4134a: ('ba04000000', 'ba01000000'),
    0x68d39: ('c7040831467265', 'c704083141646e'),
    0x68dff: ('488d4104', '488d4103'),
    0x68e14: ('c70408666e6368', 'c7040866696e00'),
    0x68e26: ('48c744242004000000', '48c744242003000000'),
    0x68e39: ('ba04000000', 'ba03000000'),
    0x68ed4: ('488d4104', '488d4101'),
    0x68ee9: ('c7040863696679', 'c7040872000000'),
    0x68efb: ('48c744242004000000', '48c744242001000000'),
    0x68f0e: ('ba04000000', 'ba01000000'),
}
SPLIT_BRAND_STRINGS = {
    0x145390: (b'1Fre\0', b'1Adn\0'),
    0x145388: (b'fnch\0', b'fin\0\0'),
    0x145380: (b'cify\0', b'r\0\0\0\0'),
}


def profile():
    return dict(fixed_sha256=FIXED_SHA256, sites=dict(SITES), dormant=None,
                preserved_rva=0x14acc8, preserved_bytes=reembed.PRESERVED_BYTES,
                native_error_strings={0x140dd0: reembed.NATIVE_ERROR_STRINGS[0x13e2d0],
                                      0x140e30: reembed.NATIVE_ERROR_STRINGS[0x13e330]},
                colored_prefix_count=9, fragment_rva=0x140144,
                length_patches=dict(LENGTH_PATCHES), split_brand_patches=dict(SPLIT_BRAND_PATCHES),
                split_brand_strings=dict(SPLIT_BRAND_STRINGS), builder_rvas=[0x40f10, 0x68b50],
                allowed_gui_widenings=(('a', '(III)V'), ('a', '(CI)V')),
                additional_interfaces={'FrenchifyGuiNewChat': ('java/lang/Runnable',)},
                initial_patches={})


HOOKS = (
    (0x19288, 0x39f40, 'plainLocal'), (0x19381, 0x39f40, 'plainLocal'),
    (0x6e4a2, 0x39f40, 'plain'), (0x6f5b0, 0x39f40, 'plainDenick'),
    (0x6b907, 0x39c40, 'jsonTags'), (0x6c082, 0x39f40, 'plainTags'),
    (0x89938, 0x39c40, 'jsonDenick'), (0x8993f, 0x39f40, 'plainDenick'),
    (0x89819, 0x39f40, 'plainDenick'),  # Skin success bypasses the shared message queue
    (0x8f921, 0x8ba70, 'prelayout'), (0x8fb9f, 0x8e0b0, 'render'),
    (0x6b4cd, 0x68b50, 'seraphPrefix'), (0x6bb2d, 0x68b50, 'seraphPrefix'),
    (0x885a4, 0xbcf40, 'denicker'), (0x1407d, 0x8b950, 'matchStart'),
    (0x186df, 0x12b90, 'gameActive'),
    (0x6045d, 0x5f220, 'columnCatalog'),
    (0x88d08, 0x77040, 'replayStats'),
    (0x88aca, 0x70c0, 'replayUuidCopy'),
    (0xbd66a, 0x125877, 'numberGetLock'), (0xbe137, 0x125877, 'numberRegisterLock'),
    (0xbe897, 0x125877, 'numberPopLock'),
    (0xa28cc, 0x99c70, 'apiPingProxy'),
    (0x95f58, 0x2f340, 'hypixelHttp'), (0x96840, 0x2f340, 'hypixelHttp'),
    (0xb16b4, 0x2f340, 'hypixelHttp'),
    (0xa16a5, 0xac470, 'apiRefreshFailures'),
    (*bridge.native_shared_config.PROFILES['vanilla'], 'configSave'),
)
HOOKS += tuple((site,target,name) for site,target,name,_ in bridge.native_player_policy.PROFILES['vanilla'])
HOOKS += tuple((site,target,name) for site,target,name,_ in bridge.native_player_policy.PRODUCERS['vanilla'])
TEXT_LINEAGE_SHA256 = '2be3c91504dff27c652e0ac6cf4d024794ba8fa7522e5faa496a17ad8490ad4b'
REGISTER_SITE = 0x1187c
REGISTER_BEFORE = bytes.fromhex('ff90b8060000')
UNLOAD_KEY_SITE = 0x15205
UNLOAD_KEY_BEFORE = bytes.fromhex('ff1535d41100')
STATE_MAGIC = b'ADNST001'
ROOT = Path(__file__).resolve().parents[1]


def normalized_text(pe):
    sections = [s for s in pe.sections if s.Name.rstrip(b'\0') == b'.text']
    bridge.require(len(sections) == 1 and sections[0].VirtualAddress == 0x1000,
                   'Expected original compatibility code section')
    data = bytearray(sections[0].get_data())
    ranges = []
    for lea, length in SITES.values():
        ranges.extend(((lea + 3, 4), (length + 4, 4)))
    for patches in (LENGTH_PATCHES, SPLIT_BRAND_PATCHES):
        ranges.extend((rva, len(bytes.fromhex(before))) for rva, (before, _) in patches.items())
    for rva, size in ranges:
        start = rva - 0x1000
        bridge.require(0 <= start <= len(data) - size, 'Operand normalization outside code')
        data[start:start + size] = bytes(size)
    return bytes(data)


def _compile(nasm, code_rva, state_rva, image_base):
    with tempfile.TemporaryDirectory(prefix='adnin-compat-bridge-') as directory:
        output = Path(directory) / 'bridge.bin'
        command = [str(nasm), '-f', 'bin', '-Ox', '-I' + str(ROOT / 'src/native') + '/',
                   '-DIMAGE_BASE=' + hex(image_base), '-DCODE_RVA=' + hex(code_rva),
                   '-DADNIN_COMPAT_PROFILE=1', '-DADNIN_STATE_RVA=' + hex(state_rva),
                   str(ROOT / 'src/native/adnin-bridge.asm'), '-o', str(output)]
        result = subprocess.run(command, capture_output=True, text=True)
        bridge.require(result.returncode == 0, 'Compatibility NASM failed:\n' + result.stdout + result.stderr)
        payload = output.read_bytes()
    meta = bridge.read_metadata(payload, code_rva)
    at = payload.find(b'ADNINC01')
    bridge.require(at >= 160 and payload.find(b'ADNINC01', at + 1) < 0, 'Missing or ambiguous compatibility metadata')
    begin, end, unwind, tick, tick_end, unload, unload_end, unload_unwind = struct.unpack_from('<8I', payload, at + 8)
    bridge.require(at + 40 <= begin < end <= tick < tick_end <= unload < unload_end <= unwind < unload_unwind < len(payload),
                   'Invalid compatibility callback ranges')
    bridge.require(unwind % 4 == 0 and payload[unwind:unwind + 4] == bytes((1, 7, 4, 0)),
                   'Unexpected registration wrapper unwind prologue')
    bridge.require(unload_unwind % 4 == 0 and payload[unload_unwind:unload_unwind + 4] == bytes((1, 5, 2, 0)),
                   'Unexpected unload-key wrapper unwind prologue')
    meta.update(register=begin, registerBegin=begin, registerEnd=end, registerUnwind=unwind,
                clientTick=tick, clientTickEnd=tick_end,
                unloadKey=unload, unloadKeyBegin=unload, unloadKeyEnd=unload_end, unloadKeyUnwind=unload_unwind)
    return payload, meta


def build_bridge(data, nasm):
    """Append reviewed feature bridges and a client-callback heartbeat, statically."""
    pe = pefile.PE(data=data)
    require, align, sha = bridge.require, bridge.align, bridge.sha
    require(pe.FILE_HEADER.Machine == 0x8664 and pe.OPTIONAL_HEADER.Magic == 0x20b
            and pe.OPTIONAL_HEADER.ImageBase == 0x180000000 and pe.FILE_HEADER.Characteristics & 0x2000,
            'Expected compatibility x64 PE32+ DLL')
    require(pe.sections[-1].Name.rstrip(b'\0') == b'.adnin', 'Run compatibility Java reembedding first')
    require(sha(normalized_text(pe)) == TEXT_LINEAGE_SHA256, 'Compatibility original code lineage mismatch')
    legacy_anticheat = native_anticheat.reviewed_patch(pe, 'vanilla')
    game_tick_hook = native_tick_hook.reviewed_patch(pe, 'vanilla')
    require(pe.OPTIONAL_HEADER.DATA_DIRECTORY[4].VirtualAddress == 0, 'Unexpected signature directory')
    require(max(s.PointerToRawData + s.SizeOfRawData for s in pe.sections) == len(data), 'Unexpected PE overlay')
    for site, target, _ in HOOKS:
        require(pe.get_data(site, 5) == bridge.call_bytes(site, target), 'Changed compatibility CALL at ' + hex(site))
    require(pe.get_data(0x894f8, 13) == bytes.fromhex('e80337020084c00f8430030000')
            and pe.get_data(0x89806, 19) == bytes.fromhex('4c8d8df02500004d8b4608488b542458498bcd'),
            'Compatibility Skin success-only guard or plain-chat ABI changed')
    require(pe.get_data(REGISTER_SITE, 6) == REGISTER_BEFORE, 'NewChat RegisterNatives call changed')
    require(pe.get_data(UNLOAD_KEY_SITE, 6) == UNLOAD_KEY_BEFORE
            and pe.get_data(0x15200, 5) == bytes.fromhex('b923000000')
            and pe.get_data(0x1520b, 5) == bytes.fromhex('6685c0752f')
            and pe.get_data(0x1527a, 5) == bytes.fromhex('488b542430'),
            'Original End-key polling or initializer JNIEnv stack slot changed')
    # Pinned caller passes its DefineClass result in R14, the actual table in
    # R8, and count 2. Runtime wrapper also verifies both names/signatures/functions.
    require(pe.get_data(0x11827, 6) == bytes.fromhex('41b902000000')
            and pe.get_data(0x11832, 5) == bytes.fromhex('4c8d442430')
            and pe.get_data(0x1183e, 3) == bytes.fromhex('498bd6')
            and pe.get_data(0x11846, 3) == bytes.fromhex('488bcb'), 'NewChat registration ABI changed')
    for rva, expected in ((0x140cd0, b'nativeOnIncomingChat\0'),
                          (0x140ce8, b'(Ljava/lang/Object;)V\0'),
                          (0x140d00, b'nativeShouldSuppressIncomingChat\0'),
                          (0x140d28, b'(Ljava/lang/Object;)Z\0')):
        require(pe.get_data(rva, len(expected)) == expected, 'Original native method descriptor changed')
    exception = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
    require(exception.VirtualAddress and exception.Size and exception.Size % 12 == 0, 'Invalid original unwind table')
    original_table = pe.get_data(exception.VirtualAddress, exception.Size)
    original_functions = list(struct.iter_unpack('<III', original_table))
    require(len(original_functions) == 3795 and original_functions == sorted(original_functions), 'Unexpected original functions')
    file_align, section_align = pe.OPTIONAL_HEADER.FileAlignment, pe.OPTIONAL_HEADER.SectionAlignment
    code_rva = align(max(s.VirtualAddress + max(s.Misc_VirtualSize, s.SizeOfRawData) for s in pe.sections), section_align)
    code_raw = align(len(data), file_align)
    payload, meta = _compile(nasm, code_rva, code_rva + 0x20000, pe.OPTIONAL_HEADER.ImageBase)
    function_names = bridge.FUNCTION_NAMES + ('register', 'unloadKey')
    table_offset = align(len(payload), 4)
    code_size = table_offset + (len(original_functions) + len(function_names)) * 12
    state_rva = align(code_rva + code_size, section_align)
    final_payload, final_meta = _compile(nasm, code_rva, state_rva, pe.OPTIONAL_HEADER.ImageBase)
    require(len(final_payload) == len(payload) and final_meta == meta, 'Bridge address resolution changed layout')
    payload = final_payload
    replay_overlay = native_replay.reviewed_patches(pe, 'vanilla', code_rva, meta)
    replay_stats = native_replay_stats.reviewed_hook(pe, 'vanilla', code_rva, meta)
    replay_denick = native_replay_denick.reviewed_patch(pe, 'vanilla', code_rva, meta)
    number_polling = native_number_poll.reviewed_patches(pe, 'vanilla', code_rva, meta)
    api_policy = native_api_policy.reviewed_patches(pe, 'vanilla', code_rva, meta)
    header_localization = bridge.native_headers.reviewed_patches(pe, 'vanilla', code_rva, meta)
    process_entry = bridge.native_process_entry.reviewed_patch(pe,'vanilla',code_rva,meta)
    game_state = bridge.native_game_state.reviewed_hook(pe,'vanilla',code_rva,meta)
    skin_policy = bridge.native_skin_policy.reviewed_patches(pe, 'vanilla')
    chat_poll = bridge.native_chat_poll.reviewed_patch(pe, 'vanilla', code_rva, meta)
    player_policy = bridge.native_player_policy.reviewed_policy(pe, 'vanilla')
    shared_config = bridge.native_shared_config.reviewed_hook(pe, 'vanilla', code_rva, meta)
    input_hooks = bridge.native_input_hooks.reviewed_patches(pe,'vanilla',code_rva,meta,state_rva,require)
    added = []
    for name in function_names:
        entry = tuple(code_rva + meta[name + suffix] for suffix in ('Begin', 'End', 'Unwind'))
        require(entry[0] < entry[1], 'Empty bridge function')
        added.append(dict(name=name, begin=entry[0], end=entry[1], unwind=entry[2]))
    functions = sorted(original_functions + [(v['begin'], v['end'], v['unwind']) for v in added])
    require(all(functions[i][1] <= functions[i + 1][0] for i in range(len(functions) - 1)), 'Overlapping unwind records')
    table = b''.join(struct.pack('<III', *entry) for entry in functions)
    code = payload + bytes(table_offset - len(payload)) + table
    code_raw_size = align(len(code), file_align)
    state_raw = code_raw + code_raw_size
    # +8 registered u32; +12 stopped u32; +16 heartbeat u64;
    # +24 NewChat global jclass; +32 static stop jmethodID.
    state = STATE_MAGIC + bytes(bridge.native_input_hooks.STATE_SIZE-8)
    state_raw_size = align(len(state), file_align)
    image_size = align(state_rva + len(state), section_align)
    header_at = pe.sections[-1].get_file_offset() + 40
    require(header_at + 80 <= pe.OPTIONAL_HEADER.SizeOfHeaders and not any(data[header_at:header_at + 80]),
            'No room for two compatibility section headers')
    result, patches = bytearray(data), []
    def patch(offset, value, reason):
        before = bytes(result[offset:offset + len(value)])
        require(len(before) == len(value), 'Patch outside existing image')
        result[offset:offset + len(value)] = value
        patches.append(dict(offset=offset, before=before.hex(), after=value.hex(), reason=reason))
    hooks = []
    patch(process_entry['offset'],bytes.fromhex(process_entry['after']),process_entry['reason'])
    for site, target, name in HOOKS:
        replacement = code_rva + meta[name]
        patch(pe.get_offset_from_rva(site), bridge.call_bytes(site, replacement), 'Compatibility JNI ' + name)
        hooks.append(dict(callRva=site, originalTargetRva=target, bridgeTargetRva=replacement, callback=name))
    register_target = code_rva + meta['register']
    patch(pe.get_offset_from_rva(REGISTER_SITE), bridge.call_bytes(REGISTER_SITE, register_target) + b'\x90',
          'Extend active NewChat native registration with client heartbeat')
    hooks.append(dict(callRva=REGISTER_SITE, originalTargetRva=None, bridgeTargetRva=register_target, callback='registerClientTick'))
    unload_target = code_rva + meta['unloadKey']
    patch(pe.get_offset_from_rva(UNLOAD_KEY_SITE), bridge.call_bytes(UNLOAD_KEY_SITE, unload_target) + b'\x90',
          'Require client-authorized unload request before stopping callbacks; never read system-wide End state')
    hooks.append(dict(callRva=UNLOAD_KEY_SITE, originalTargetRva=None, bridgeTargetRva=unload_target, callback='stopClientPumpBeforeUnload'))
    patch(pe.get_offset_from_rva(legacy_anticheat['callRva']), native_anticheat.REPLACEMENT,
          legacy_anticheat['reason'])
    patch(pe.get_offset_from_rva(game_tick_hook['branchRva']), bytes.fromhex(game_tick_hook['after']),
          game_tick_hook['reason'])
    for item in replay_overlay:
        patch(pe.get_offset_from_rva(item['predicateRva']), bytes.fromhex(item['after']), item['reason'])
    patch(pe.get_offset_from_rva(replay_denick['gateRva']), bytes.fromhex(replay_denick['after']), replay_denick['reason'])
    for item in number_polling:
        patch(pe.get_offset_from_rva(item['branchRva']),bytes.fromhex(item['after']),item['reason'])
    for item in skin_policy['patches']:
        patch(pe.get_offset_from_rva(item['siteRva']),bytes.fromhex(item['after']),item['reason'])
    for item in api_policy['patches']:
        patch(pe.get_offset_from_rva(item['siteRva']),bytes.fromhex(item['after']),item['reason'])
    for item in header_localization['patches']:
        patch(pe.get_offset_from_rva(item['siteRva']), bytes.fromhex(item['after']), item['reason'])
    for item in input_hooks['patches']:
        patch(pe.get_offset_from_rva(item['siteRva']),bytes.fromhex(item['after']),item['reason'])
    chat_site = chat_poll['siteRva']
    patch(pe.get_offset_from_rva(chat_site), b'\xe9'+struct.pack('<i',chat_poll['bridgeTargetRva']-chat_site-5)+b'\x90',
          chat_poll['reason'])
    patch(pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'), struct.pack('<H', len(pe.sections) + 2), 'Append code and state sections')
    patch(pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'), struct.pack('<I', image_size), 'Compatibility image size')
    patch(pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfCode'), struct.pack('<I', pe.OPTIONAL_HEADER.SizeOfCode + code_raw_size), 'Compatibility code size')
    patch(pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfInitializedData'),
          struct.pack('<I', pe.OPTIONAL_HEADER.SizeOfInitializedData + state_raw_size), 'Compatibility state size')
    patch(exception.get_file_offset(), struct.pack('<II', code_rva + table_offset, len(table)), 'Merged compatibility unwind table')
    patch(header_at, struct.pack('<8sIIIIIIHHI', b'.adncode', len(code), code_rva, code_raw_size, code_raw, 0, 0, 0, 0, 0x60000020),
          'Read-execute compatibility bridge header')
    patch(header_at + 40, struct.pack('<8sIIIIIIHHI', b'.adnstat', len(state), state_rva, state_raw_size, state_raw, 0, 0, 0, 0, 0xc0000040),
          'Read-write non-executable heartbeat header')
    result.extend(bytes(code_raw - len(result)) + code + bytes(code_raw_size - len(code)))
    result.extend(state + bytes(state_raw_size - len(state)))
    checksum = pefile.PE(data=bytes(result)).generate_checksum()
    patch(pe.OPTIONAL_HEADER.get_field_absolute_offset('CheckSum'), struct.pack('<I', checksum), 'PE checksum')
    final = bytes(result); checked = pefile.PE(data=final)
    require(checked.OPTIONAL_HEADER.CheckSum == checked.generate_checksum(), 'Compatibility checksum self-check failed')
    require(checked.get_data(code_rva, 8) == bridge.MAGIC and checked.get_data(state_rva, len(state)) == state, 'Bridge/state readback failed')
    require(not checked.sections[-1].Characteristics & 0x20000000 and not checked.sections[-2].Characteristics & 0x80000000,
            'Compatibility section permissions are not W^X')
    allowed = bytearray(len(data))
    for change in patches:
        start, size = change['offset'], len(bytes.fromhex(change['after']))
        allowed[start:start + size] = b'\x01' * size
    require(all(a == b or allowed[i] for i, (a, b) in enumerate(zip(data, final))), 'Unledgered original byte change')
    runtime = dict(peHeaderOffset=pe.DOS_HEADER.e_lfanew, imageSize=image_size,
                   markerRva=code_rva, marker=bridge.MARKER,
                   signatures=[dict(rva=REGISTER_SITE, bytes=checked.get_data(REGISTER_SITE, 6).hex()),
                               dict(rva=state_rva, bytes=STATE_MAGIC.hex())],
                   classes=[dict(rva=rva, width=8) for rva in (0x17e410, 0x17e418, 0x17e420, 0x17e428, 0x17e430)],
                   pump=[], hooks=[dict(rva=0x17e945, width=1), dict(rva=0x17e946, width=1), dict(rva=state_rva + 8, width=4)],
                   heartbeatRva=state_rva + 16, heartbeatWidth=8)
    report = dict(inputSha256=sha(data), outputSha256=sha(final), fixedNativeSha256=FIXED_SHA256,
                  normalizedNativeTextSha256=TEXT_LINEAGE_SHA256, imageSize=image_size,
                  section=dict(name='.adncode', rva=code_rva, offset=code_raw, size=len(code), rawSize=code_raw_size,
                               executable=True, writable=False),
                  stateSection=dict(name='.adnstat', rva=state_rva, offset=state_raw, size=len(state), rawSize=state_raw_size,
                                    executable=False, writable=True), marker=hex(bridge.MARKER), metadata=meta,
                  hooks=hooks, originalRuntimeFunctions=len(original_functions), bridgeRuntimeFunctions=added,
                  runtimeFunctionTable=dict(rva=code_rva + table_offset, size=len(table), count=len(functions)),
                  patches=patches, runtime_metadata=runtime, legacyAnticheat=legacy_anticheat,
                  gameTickHook=game_tick_hook, replayOverlay=replay_overlay, replayStats=replay_stats, replayDenicker=replay_denick, numberPolling=number_polling,
                  nativeApiPolicy=api_policy, skinDenickerPolicy=skin_policy, grayPlayerPolicy=player_policy, headerLocalization=header_localization,
                  processTerminationGuard=process_entry, nativeGameState=game_state,
                  nativeInputHooks=input_hooks, nativeChatPolling=chat_poll,
                  sharedConfiguration=shared_config,
                  outputRouting=bridge.output_routing(HOOKS),
                  gameRuntimeTested=False, originalDllExecutedByBuild=False,
                  denickerLayout=dict(rowSize=0x178, uuidOffset=0x148, statsSize=0x180, skinScratchSize=0x280,
                                      skinLastFieldEnd=0x274, numberStatsReady=0x1c0, modeReady=[0x48, 0x100, 0x140]),
                  columnCatalog=dict(originalCount=30, originalMaxId=29, fklvId=30, urchinId=31),
                  skinSuccessOutput=dict(callRva=0x89819, successGetterCallRva=0x894f8,
                                         successGetterRva=0xacc00, noSuccessSkipRva=0x89835,
                                         format='colored plain std::string', callback='nativeRenderGeneratedEvent'),
                  clientTick=dict(owner='AdninGuiNewChat', method='nativeClientTick', descriptor='()V',
                                  source='bounded scheduled client callback after Features.tick; independent of sessionStats and world rendering',
                                  originalNativeMethodsPreserved=2),
                  unloadGuard=dict(callRva=UNLOAD_KEY_SITE, stopOwner='AdninGuiNewChat',
                                   stopMethod='adninTryStopClientPump', descriptor='()I', acknowledgement=1,
                                   systemKeyPolling=False, requiresClientAuthorizedRequest=True, originalPollIntervalMs=20,
                                   requestSurvivesKeyRelease=True,
                                   busyAction='return zero; retain class/method and retry without waiting for Netty',
                                   legacyStopAbiPreserved='AdninGuiNewChat.adninStopClientPump()V',
                                   jniErrorAction='return zero; keep cleanup and unload deferred',
                                   classGlobalRefRva=state_rva + 24, stopMethodRva=state_rva + 32,
                                   stoppedFlagRva=state_rva + 12))
    return final, report
