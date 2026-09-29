"""Pin Lunar's ignored addScheduledTask local-reference return and JNI ABI."""
SITE = 0xd5d3a
TARGET = 0xc270
PREFIX = bytes.fromhex('4c8b0590240d004c8bce488b4c2430488bd5c6056e240d0001')
CALL = bytes.fromhex('e83165f3ff')
SUFFIX = bytes.fromhex('488b4c2430488b01ff902007000084c0')
THUNK = bytes.fromhex('4c894424184c894c24204883ec28488b014c8d4c2448ff90180100004883c428c3')


def reviewed_hook(pe, code_rva, metadata):
    checks = ((SITE-len(PREFIX), PREFIX), (SITE, CALL), (SITE+5, SUFFIX), (TARGET, THUNK),
        (0x16d950, b'(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;\0'),
        (0x16d9a0, b'addScheduledTask\0'))
    for at, expected in checks:
        if pe.get_data(at,len(expected)) != expected:
            raise ValueError('Lunar scheduler ignored-result/JNI ABI changed at '+hex(at))
    return dict(callRva=SITE, originalTargetRva=TARGET, bridgeTargetRva=code_rva+metadata['lunarSchedule'],
        callback='lunarSchedule', returnedLocalFutureReleased=True, pendingExceptionPreserved=True,
        javaQueueOwnershipUnchanged=True, scope='Lunar long-lived attached initializer thread')
