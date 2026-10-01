"""Reviewed native provider gates and Number's independent credential copy.

No credentials are embedded, logged, or read from personal configuration.
Existing configuration setters, worker locks and cleanup paths are preserved.
"""
import hashlib
import struct

AURORA_PING_URL = b'https://bordic.xyz/api/v2/resources/ping?uuid='
OLD_PING_URL = b'https://api.bordic.xyz/v3/player/ping?uuid='

PROFILES = {
    'lunar': dict(proxy=0x1a73d4, keyLength=0x1a4bd0,
        http=0x2ddb0, resolver=0x9ded0, assign=0x3790,
        httpSites=((0x93dd8,'604c8d442430488d55a0488d4de0e8d39ff9ff0fb6d8488b54'),
                   (0x946c0,'404c8d442438488d5580488d4da0e8eb96f9ff0fb6d8488b54'),
                   (0xaedf4,'44243c488d5570488d8db0000000e8b7eff7ff84c07423817c')),
        refreshCall=0x9f1d5,skinSetter=0xa9e90,statsHead=0x1a7478,statsFlags=(0x38,0x90,0xd0),
        refreshContext='488bd3488bcfe8b6ac0000e851050000',
        nameGuard=0x93b82, nameSite=0x93b8f, nameFail=0x93b7b,
        nameProxy=0x94176, nameDirect=0x93b99,
        uuidSite=0x942d0, uuidFail=0x94bde,
        uuidBefore='803dfd30110000750c48837b100074054532f6eb0341b601',
        pingCall=0xa03fc, pingTarget=0x97b00,
        pingUrlLea=0x97ba8, pingUrlOriginal=0x170188,
        pingContext='488d55af488d4de7e8ff76ffff84c0750e',
        shadow=0x1a4758, numberKey=0x1a4d60,
        numberSites=((0x1936c,3,8,24,'48833dfcb318000f'),
                     (0x19374,3,7,0,'488d05ddb31800'),
                     (0x1937b,4,8,0,'480f4705d5b31800'),
                     (0x193a5,3,7,16,'4c3b05bcb31800')),
        numberUpdate=0x193bf, numberUnchanged=0x19415, numberSetter=0xbbae0,
        numberCall=0x193f5, numberCompareCall=0x193b6,
        numberContext='488d9424d0000000488d8c2430010000e8e6260a004584ff740f',
        guards=((0x9ef70,0x9f22d,'1885eab9ed0d0756aec66c52bdae3dcd111b379071f9b01ee6121296031774bc'),
                (0xa9e90,0xa9f6a,'7c1f46f6a64e2a53262585c0833462216937b4b18608ccf230c8d4eda512ff5e'),
                (0xbbae0,0xbbbb5,'ccf3b47b2fb1267f92902c358efbfdd23b9053b5ce94faa77f5f6fe379da6da6'))),
    'vanilla': dict(proxy=0x17f354, keyLength=0x17cbb0,
        http=0x2f340, resolver=0xa03a0, assign=0x3670,
        httpSites=((0x95f58,'604c8d442430488d55a0488d4de0e8e393f9ff0fb6d8488b54'),
                   (0x96840,'404c8d442438488d5580488d4da0e8fb8af9ff0fb6d8488b54'),
                   (0xb16b4,'44243c488d5570488d8db0000000e887dcf7ff84c07423817c')),
        refreshCall=0xa16a5,skinSetter=0xac470,statsHead=0x17f3f8,statsFlags=(0x38,0xf0,0x130),
        refreshContext='488bd3488bcfe8c6ad0000e851050000',
        nameGuard=0x95d02, nameSite=0x95d0f, nameFail=0x95cfb,
        nameProxy=0x962f6, nameDirect=0x95d19,
        uuidSite=0x96450, uuidFail=0x96d5e,
        uuidBefore='803dfd8e0e0000750c48837b100074054532f6eb0341b601',
        pingCall=0xa28cc, pingTarget=0x99c70,
        pingUrlLea=0x99d18, pingUrlOriginal=0x148b48,
        pingContext='488d55af488d4de7e89f73ffff84c0750e',
        shadow=0x17c7c8, numberKey=0x17cd40,
        numberSites=((0x19571,3,8,24,'48833d673216000f'),
                     (0x19579,3,7,0,'488d0548321600'),
                     (0x19580,4,8,0,'480f470540321600'),
                     (0x195aa,3,7,16,'4c3b0527321600')),
        numberUpdate=0x195c4, numberUnchanged=0x1961a, numberSetter=0xbe680,
        numberCall=0x195fa, numberCompareCall=0x195bb,
        numberContext='488d9424d0000000488d8c2430010000e881500a004584ff740f',
        guards=((0xa1440,0xa16fd,'3c6570b57263f3ff940dd737f1226be051780b149711f94419589c6514521dc5'),
                (0xac470,0xac54a,'a145ca4664705d3f5d0a99b3dbd19ebd5f0d4843d0f1e8ca4bec6e0d2d3ae891'),
                (0xbe680,0xbe755,'d43d0f025ab33ae58464882c40890aa30a59e550910ee0e62da35d33e4db6732'),)),
}


def rel32(opcode, site, target):
    return opcode + struct.pack('<i', target-site-len(opcode)-4)


def reviewed_patches(pe, profile, code_rva, metadata):
    spec = PROFILES[profile]
    def require(ok, label):
        if not ok:
            raise ValueError('Native API policy '+label+' changed: '+profile)
    def checked(rva, expected, label):
        require(pe.get_data(rva,len(expected)) == expected,label)
    checked(spec['nameGuard'], b'\x40\x38\x35'+struct.pack('<i',spec['proxy']-spec['nameGuard']-7)
            +rel32(b'\x0f\x85',spec['nameGuard']+7,spec['nameProxy']), 'name explicit-proxy gate')
    checked(spec['nameSite'],bytes.fromhex('483971100f84dd050000'),'name key gate')
    checked(spec['nameFail'],bytes.fromhex('32c0e9bf050000'),'name no-result cleanup')
    checked(spec['uuidSite'],bytes.fromhex(spec['uuidBefore']),'UUID provider gate')
    checked(spec['uuidSite']-12,bytes.fromhex('48837c2470000f840e090000'),'UUID initialized-string guard')
    checked(spec['uuidFail'],bytes.fromhex('32db488b5424784883fa0f'),'UUID no-result cleanup')
    checked(spec['pingCall']-8,bytes.fromhex(spec['pingContext']),'ping argument/result ABI')
    checked(spec['pingUrlLea']-3,b'\x4c\x8b\xc0'+rel32(b'\x48\x8d\x15',spec['pingUrlLea'],spec['pingUrlOriginal'])
            +bytes.fromhex('488d8dc0010000'),'ping URL argument ABI')
    checked(spec['pingUrlOriginal'],OLD_PING_URL+b'\0','original Ping URL')
    checked(spec['numberCall']-16,bytes.fromhex(spec['numberContext']),'Number setter ABI')
    checked(spec['refreshCall']-6,bytes.fromhex(spec['refreshContext']),'Stats locked configuration refresh ABI')
    for site,context in spec['httpSites']:
        checked(site-14,bytes.fromhex(context),'Hypixel HTTP arguments and result ABI')
    require(spec['keyLength'] % 8 == 0,'aligned current-key length')
    checked(spec['keyLength'],bytes(8),'empty initial main key')
    checked(spec['numberKey']+16,bytes(8),'empty initial Number key')
    for start,end,digest in spec['guards']:
        require(hashlib.sha256(pe.get_data(start,end-start)).hexdigest()==digest,
                'original clear-capable setter '+hex(start))
    patches=[]
    def add(site,before,after,reason,**details):
        require(len(before)==len(after),'equal patch size')
        patches.append(dict(siteRva=site,before=before.hex(),after=after.hex(),reason=reason,**details))
    url_site=spec['pingUrlLea']
    add(url_site,pe.get_data(url_site,7),rel32(b'\x48\x8d\x15',url_site,code_rva+metadata['auroraPingUrl']),
        'Use the public Aurora Ping endpoint; retain UUID encoding, background worker, parser and cache',
        oldTargetRva=spec['pingUrlOriginal'],newTargetRva=code_rva+metadata['auroraPingUrl'],displacementOffset=3)
    site=spec['nameSite']
    # The original function has already reset its outputs before this guard.
    replacement=rel32(b'\xe8',site,code_rva+metadata['apiKeyReady'])+b'\x84\xc0\x74'
    replacement+=struct.pack('<b',spec['nameFail']-(site+9))+b'\x90'
    add(site,pe.get_data(site,10),replacement,
        'Require current nonempty direct key and reject blank copied key; never implicitly choose proxy',
        failureRva=spec['nameFail'],continuationRva=spec['nameDirect'])
    site=spec['uuidSite']
    replacement=rel32(b'\xe8',site,code_rva+metadata['apiUuidReady'])+b'\x84\xc0'
    replacement+=rel32(b'\x0f\x84',site+7,spec['uuidFail'])+b'\x90'*11
    add(site,pe.get_data(site,24),replacement,
        'Require explicit proxy or current nonblank direct key before UUID stats retrieval',
        failureRva=spec['uuidFail'],continuationRva=site+24)
    for site,disp_at,size,field,before in spec['numberSites']:
        old=bytes.fromhex(before)
        checked(site,old,'Number key comparison operand')
        require(site+size+struct.unpack_from('<i',old,disp_at)[0]==spec['shadow']+field,
                'Number old comparison target')
        new=bytearray(old)
        struct.pack_into('<i',new,disp_at,spec['numberKey']+field-site-size)
        add(site,old,bytes(new),'Compare Number-owned key so replacement and clearing reach its existing setter',
            oldTargetRva=spec['shadow']+field,newTargetRva=spec['numberKey']+field,displacementOffset=disp_at)
    return dict(patches=patches,explicitProxyRequired=True,blankDirectKeyRejected=True,
        keyPresenceSourceRva=spec['keyLength'],numberKeyRva=spec['numberKey'],
        numberIndependentCopyCompared=True,currentEmptyConfigRejectsOldWorkerKey=True,
        ping=dict(callRva=spec['pingCall'],originalTargetRva=spec['pingTarget'],
                  bridgeTargetRva=code_rva+metadata['apiPingProxy'],callback='apiPingProxy',
                  provider='aurora',endpoint=AURORA_PING_URL.decode('ascii'),requiresApiKey=False,
                  urlOperandRva=spec['pingUrlLea'],requiresProxy=False,hypixelProviderIndependent=True,
                  backgroundWorkerAndCachePreserved=True),
        http=dict(authentication='API-Key request header',playerParameter='uuid',
                  nameResolution='original native Mojang resolver',
                  legacyKeyQueryRemovedBeforeNetwork=True,temporaryHeaderClearedOnNormalReturn=True,
                  sources=[dict(callRva=site,originalTargetRva=spec['http'],
                      bridgeTargetRva=code_rva+metadata['hypixelHttp'],callback='hypixelHttp')
                      for site,_ in spec['httpSites']]),
        failedCacheRefresh=dict(callRva=spec['refreshCall'],originalTargetRva=spec['skinSetter'],
                      bridgeTargetRva=code_rva+metadata['apiRefreshFailures'],callback='apiRefreshFailures',
                      underOriginalStatsMutex=True,onlyFailedEntriesExpired=True,successfulResultsPreserved=True,
                      failureTimestamp=(-45001)&((1<<64)-1)),
        originalSettersAndLocksPreserved=True,originalNoResultCleanupPreserved=True,
        limitation='Existing cached results and requests already accepted before configuration synchronization may complete; no cancellation barrier is installed')
