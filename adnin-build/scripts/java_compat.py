"""Bounded Java 8 class remapping and static runtime linkage validation.

Only structural names/descriptors are remapped. Ordinary string constants,
bytecode instructions, exception tables and stack maps retain their indexes.
Member NameAndType entries are cloned per owner, because the JVM constant pool
may share one entry between unrelated classes with different obfuscation names.
"""
import json
import re
import struct
import zipfile
from pathlib import Path
from classfile import Reader, read_class

def u2(value): return struct.pack('>H', value)

class ClassPath:
    def __init__(self, paths=(), classes=None):
        self.paths = [Path(path) for path in paths]
        self.classes = dict(classes or {})
        self.cache = {}
        self.archives = [zipfile.ZipFile(p) for p in self.paths if p.is_file() and p.suffix.lower() == '.jar']

    def close(self):
        for archive in self.archives: archive.close()

    def get(self, name):
        if name in self.cache: return self.cache[name]
        data = self.classes.get(name)
        if data is None:
            for p in self.paths:
                if p.is_dir() and (p / (name + '.class')).is_file():
                    data = (p / (name + '.class')).read_bytes(); break
        if data is None:
            for archive in self.archives:
                try: data = archive.read(name + '.class'); break
                except KeyError: pass
        self.cache[name] = read_class(data) if data is not None else None
        return self.cache[name]

    def ancestors(self, owner):
        pending, seen = [owner], set()
        while pending:
            current = pending.pop(0)
            if current in seen: continue
            seen.add(current)
            yield current
            info = self.get(current)
            if info:
                if info['parent']: pending.append(info['parent'])
                pending.extend(info['interfaces'])

class Mapping:
    def __init__(self, data, classpath):
        self.data, self.classpath = data, classpath
        self.classes = data['classes']
        self.fields = {tuple(row[:3]): row[3] for row in data['fields']}
        self.methods = {tuple(row[:3]): row[3] for row in data['methods']}
        self.used_classes, self.used_fields, self.used_methods = {}, {}, {}

    def class_name(self, name):
        if name.startswith('['): return self.descriptor(name)
        mapped = self.classes.get(name)
        if mapped is not None:
            self.used_classes[name] = mapped
            return mapped
        if name.startswith('net/minecraft/'):
            raise ValueError('Missing compatibility class mapping: ' + name)
        return name

    def descriptor(self, descriptor):
        return re.sub(r'L([^;<]+)', lambda m: 'L' + self.class_name(m[1]), descriptor)

    def member(self, owner, name, descriptor, field=False):
        if name.startswith('<'): return name
        table, used = (self.fields, self.used_fields) if field else (self.methods, self.used_methods)
        for current in self.classpath.ancestors(owner):
            key = (current, name, descriptor)
            if key in table:
                used[key] = table[key]
                return table[key]
        return name

    def subset(self):
        return {'format': 1, 'provenance': self.data.get('provenance', {}),
                'classes': dict(sorted(self.used_classes.items())),
                'fields': [list(k) + [v] for k,v in sorted(self.used_fields.items())],
                'methods': [list(k) + [v] for k,v in sorted(self.used_methods.items())]}

def remap_class(data, mapping):
    original = read_class(data)
    r = Reader(data)
    header = r.take(8)
    count = r.u2()
    pool = [None]
    while len(pool) < count:
        tag = r.u1()
        if tag == 1: value = (tag, r.take(r.u2()))
        elif tag in (7, 8, 16, 19, 20): value = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18): value = (tag, r.u2(), r.u2())
        elif tag in (3, 4): value = (tag, r.take(4))
        elif tag in (5, 6):
            value = (tag, r.take(8)); pool.extend([value, None]); continue
        elif tag == 15: value = (tag, r.u1(), r.u2())
        else: raise ValueError('Unsupported class constant ' + str(tag))
        pool.append(value)
    old_pool = list(pool)
    tail = bytearray(data[r.p:])
    def utf(index): return old_pool[index][1].decode('utf8', errors='surrogateescape')
    def cls(index): return utf(old_pool[index][1])
    # UTF8 entries remain immutable. Other old entries may change during this
    # pass, so they must not be reused through stale pre-remap indexes.
    indices = {entry:i for i,entry in enumerate(pool) if entry is not None and entry[0] == 1}
    def intern(entry):
        if entry in indices: return indices[entry]
        if len(pool) >= 65535: raise ValueError('Remapped constant pool exceeds JVM limit')
        index = len(pool); pool.append(entry); indices[entry] = index
        return index
    def new_utf(text):
        value = text.encode('utf8', errors='surrogateescape')
        if len(value) > 65535: raise ValueError('Remapped UTF8 exceeds JVM limit')
        return intern((1, value))
    def mapped_desc(index): return new_utf(mapping.descriptor(utf(index)))
    for index, entry in enumerate(old_pool):
        if not entry: continue
        tag = entry[0]
        if tag == 7: pool[index] = (tag, new_utf(mapping.class_name(utf(entry[1]))))
        elif tag == 16: pool[index] = (tag, mapped_desc(entry[1]))
        elif tag == 12: pool[index] = (tag, entry[1], mapped_desc(entry[2]))
        elif tag in (9, 10, 11):
            owner = cls(entry[1]); nt = old_pool[entry[2]]
            name, desc = utf(nt[1]), utf(nt[2])
            mapped_name = mapping.member(owner, name, desc, tag == 9)
            pool[index] = (tag, entry[1], intern((12, new_utf(mapped_name), new_utf(mapping.descriptor(desc)))))
    # Inspect the tail structurally to rewrite member definitions and Signature
    # attributes without treating literal strings as names. Code references CP
    # entries, including StackMapTable/Exceptions, and needs no bytecode changes.
    tr = Reader(tail)
    def replace_index(at, index): tail[at:at+2] = u2(index)
    def attributes(reader):
        for _ in range(reader.u2()):
            name = utf(reader.u2()); size = reader.u4(); start = reader.p
            if name == 'Signature':
                if size != 2: raise ValueError('Invalid Signature attribute')
                replace_index(start, mapped_desc(reader.u2()))
            elif name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations',
                          'RuntimeVisibleTypeAnnotations', 'RuntimeInvisibleTypeAnnotations',
                          'RuntimeVisibleParameterAnnotations', 'RuntimeInvisibleParameterAnnotations',
                          'AnnotationDefault'):
                # Current Adnin sources have no runtime annotations. Fail closed
                # if a future source adds descriptors this mapper does not parse.
                raise ValueError('Unsupported annotation attribute: ' + name)
            elif name == 'Code':
                reader.take(4); reader.take(reader.u4()); reader.take(reader.u2() * 8)
                attributes(reader)
            elif name in ('LocalVariableTable', 'LocalVariableTypeTable'):
                for _ in range(reader.u2()):
                    reader.take(6); at = reader.p
                    replace_index(at, mapped_desc(reader.u2())); reader.take(2)
            elif name == 'InnerClasses':
                for _ in range(reader.u2()):
                    inner = reader.u2(); reader.u2(); at = reader.p
                    simple = reader.u2(); reader.u2()
                    if inner and simple:
                        before = cls(inner); after = mapping.class_name(before)
                        if after != before:
                            replace_index(at, new_utf(after.rsplit('$',1)[-1].rsplit('/',1)[-1]))
            elif name == 'EnclosingMethod':
                if size != 4: raise ValueError('Invalid EnclosingMethod attribute')
                owner = cls(reader.u2()); at = reader.p; method = reader.u2()
                if method:
                    nt = old_pool[method]
                    method_name, method_desc = utf(nt[1]), utf(nt[2])
                    replace_index(at,intern((12,new_utf(mapping.member(owner,method_name,method_desc)),
                                             new_utf(mapping.descriptor(method_desc)))))
            else: reader.take(size)
            if reader.p != start + size: raise ValueError('Invalid attribute size: ' + name)
    tr.take(6); tr.take(tr.u2() * 2)
    for is_field in (True, False):
        for _ in range(tr.u2()):
            tr.u2(); at = tr.p; name, desc = utf(tr.u2()), utf(tr.u2())
            # Declared fields are never inherited; method declarations may be
            # overrides of mapped methods on a game superclass/interface.
            new_name = (mapping.member(original['name'], name, desc, True)
                        if is_field and mapping.data.get('remapDeclaredFields') else
                        name if is_field else mapping.member(original['name'], name, desc))
            replace_index(at, new_utf(new_name)); replace_index(at+2, new_utf(mapping.descriptor(desc)))
            attributes(tr)
    attributes(tr)
    if tr.p != len(tail): raise ValueError('Trailing class tail')
    output = bytearray(header + u2(len(pool)))
    for entry in pool[1:]:
        if entry is None: continue
        tag = entry[0]; output.append(tag)
        if tag == 1: output.extend(u2(len(entry[1])) + entry[1])
        elif tag in (7,8,16,19,20): output.extend(u2(entry[1]))
        elif tag in (9,10,11,12,17,18): output.extend(u2(entry[1]) + u2(entry[2]))
        elif tag in (3,4,5,6): output.extend(entry[1])
        elif tag == 15: output.append(entry[1]); output.extend(u2(entry[2]))
    output.extend(tail)
    read_class(bytes(output))
    return bytes(output)

def constant_utf8(data):
    """Return raw modified-UTF8 constants for generated bootstrap integrity checks."""
    r = Reader(data)
    if r.u4() != 0xcafebabe: raise ValueError('Invalid class magic')
    r.take(4); count = r.u2(); result = set(); index = 1
    while index < count:
        tag = r.u1()
        if tag == 1: result.add(r.take(r.u2()))
        elif tag in (7,8,16,19,20): r.take(2)
        elif tag in (3,4,9,10,11,12,17,18): r.take(4)
        elif tag in (5,6): r.take(8); index += 1
        elif tag == 15: r.take(3)
        else: raise ValueError('Unsupported class constant ' + str(tag))
        index += 1
    return result

def validate_runtime(classes, runtime_jar, mapping, allow_named=False):
    """Resolve actual emitted game member references without executing the game."""
    target = ClassPath([runtime_jar], classes)
    game_names = set(mapping.classes.values())
    checked_classes, checked_members = set(), set()
    errors = []
    try:
        for name, data in classes.items():
            info = read_class(data)
            types = set(info['classReferences'])
            for member in info['fields'] + info['methods'] + info['references']:
                types.update(re.findall(r'L([^;<]+)', member['descriptor']))
            for cls in types:
                if cls.startswith('net/minecraft/') and not allow_named:
                    errors.append(name + ': unremapped class ' + cls)
                elif cls in game_names:
                    if target.get(cls) is None: errors.append(name + ': runtime class absent ' + cls)
                    checked_classes.add(cls)
            for ref in info['references']:
                owner = ref['owner']
                if owner not in game_names and owner not in classes: continue
                found = None
                for ancestor in target.ancestors(owner):
                    candidate = target.get(ancestor)
                    if not candidate: continue
                    for member in candidate['fields' if ref['tag'] == 9 else 'methods']:
                        if (member['name'],member['descriptor']) == (ref['name'],ref['descriptor']):
                            found = (ancestor, member); break
                    if found: break
                    if ref['name'] == '<init>': break
                if found is None:
                    # Java/Object members are checked by the JVM verifier, not
                    # resolved from a game jar which does not include java.base.
                    if ref['name'] in ('toString','hashCode','equals','getClass','clone','finalize','notify','notifyAll','wait'):
                        continue
                    errors.append(name + ': unresolved ' + owner + '.' + ref['name'] + ref['descriptor'])
                elif found[1]['access'] & 2 and name != found[0]:
                    errors.append(name + ': inaccessible private ' + owner + '.' + ref['name'])
                checked_members.add((owner,ref['name'],ref['descriptor']))
    finally: target.close()
    if errors: raise ValueError('Compatibility runtime linkage failed:\n' + '\n'.join(sorted(set(errors))))
    return {'gameClassesResolved':len(checked_classes), 'memberReferencesResolved':len(checked_members),
            'runtimeGameExecuted':False}
