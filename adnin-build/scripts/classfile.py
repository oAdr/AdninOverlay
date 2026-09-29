"""Small bounded classfile reader for ABI checks; does not execute bytecode."""
import struct

class Reader:
    def __init__(self, data): self.data, self.p = data, 0
    def take(self, n):
        if n < 0 or self.p+n > len(self.data): raise ValueError('Truncated class file')
        value=self.data[self.p:self.p+n]; self.p+=n; return value
    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack('>H',self.take(2))[0]
    def u4(self): return struct.unpack('>I',self.take(4))[0]

def read_class(data):
    r=Reader(data)
    if r.u4()!=0xcafebabe: raise ValueError('Invalid class magic')
    minor,major,count=r.u2(),r.u2(),r.u2()
    pool=[None]*count; i=1
    while i<count:
        tag=r.u1()
        if tag==1: pool[i]=(tag,r.take(r.u2()).decode('utf8',errors='surrogateescape'))
        elif tag in (7,8,16,19,20): pool[i]=(tag,r.u2())
        elif tag in (9,10,11,12,17,18): pool[i]=(tag,r.u2(),r.u2())
        elif tag in (3,4): pool[i]=(tag,r.take(4))
        elif tag in (5,6): pool[i]=(tag,r.take(8));i+=1
        elif tag==15: pool[i]=(tag,r.u1(),r.u2())
        else: raise ValueError(f'Unsupported constant pool tag {tag}')
        i+=1
    def utf(index):
        if not 0<index<count or not pool[index] or pool[index][0]!=1: raise ValueError('Invalid UTF constant')
        return pool[index][1]
    def cls(index):
        if index==0:return None
        if not 0<index<count or not pool[index] or pool[index][0]!=7:raise ValueError('Invalid class constant')
        return utf(pool[index][1])
    access,name,parent=r.u2(),cls(r.u2()),cls(r.u2())
    interfaces=[cls(r.u2()) for _ in range(r.u2())]
    def attributes():
        result=[]
        for _ in range(r.u2()):
            key=utf(r.u2()); value=r.take(r.u4()); result.append((key,value))
        return result
    def members():
        result=[]
        for _ in range(r.u2()):
            a,n,d=r.u2(),utf(r.u2()),utf(r.u2())
            attrs=attributes();result.append({'access':a,'name':n,'descriptor':d})
        return result
    fields,methods=members(),members()
    attributes()
    if r.p!=len(data):raise ValueError('Trailing bytes in class file')
    refs=[]
    for item in pool:
        if item and item[0] in (9,10,11):
            nt=pool[item[2]]
            refs.append({'tag':item[0],'owner':cls(item[1]),'name':utf(nt[1]),'descriptor':utf(nt[2])})
    return {'name':name,'parent':parent,'access':access,'major':major,'minor':minor,'interfaces':interfaces,'fields':fields,'methods':methods,'references':refs,
            'classReferences':sorted({cls(i) for i,p in enumerate(pool) if p and p[0]==7})}

def abi(info):
    def keys(members):return sorted((m['name'],m['descriptor'],m['access'] & 0x5fff) for m in members)
    return info['name'],info['parent'],info['access'],info['interfaces'],keys(info['fields']),keys(info['methods'])


def rewrite_utf8(data, replacements):
    """Rewrite constant-pool UTF8 bytes without changing pool indexes or bytecode.

    Class files use modified UTF-8. Replacing ASCII branding at the byte level
    deliberately avoids decoding/re-encoding unrelated modified UTF-8 strings.
    `replacements` is an ordered sequence of (old_bytes, new_bytes) pairs.
    """
    r = Reader(data)
    if r.u4() != 0xcafebabe:
        raise ValueError('Invalid class magic')
    r.take(4)
    count = r.u2()
    result = bytearray(data[:r.p])
    index = 1
    while index < count:
        tag = r.u1()
        result.append(tag)
        if tag == 1:
            value = r.take(r.u2())
            for old, new in replacements:
                if not old:
                    raise ValueError('Empty constant-pool replacement')
                value = value.replace(old, new)
            if len(value) > 0xffff:
                raise ValueError('Rewritten UTF8 constant exceeds JVM limit')
            result.extend(struct.pack('>H', len(value)))
            result.extend(value)
        elif tag in (7, 8, 16, 19, 20):
            result.extend(r.take(2))
        elif tag in (9, 10, 11, 12, 17, 18, 3, 4):
            result.extend(r.take(4))
        elif tag in (5, 6):
            result.extend(r.take(8))
            index += 1
        elif tag == 15:
            result.extend(r.take(3))
        else:
            raise ValueError(f'Unsupported constant pool tag {tag}')
        index += 1
    result.extend(data[r.p:])
    # Parse the entire result as well: a correct constant pool alone does not
    # establish that the member/attribute tail is complete.
    read_class(bytes(result))
    return bytes(result)
