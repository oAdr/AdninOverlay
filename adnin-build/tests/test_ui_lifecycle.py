"""Production renderer bytecode with owned GL allocation/fault counters; no game or native GL."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from classfile import read_class
spec = importlib.util.spec_from_file_location('ui_lifecycle_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

STATE = '''package org.lwjgl.opengl;
import java.nio.*;
import java.util.*;
public final class GpuFixture {
    public static final Map<Integer,Texture> live=new HashMap<Integer,Texture>();
    public static final Set<ByteBuffer> buffers=Collections.newSetFromMap(new IdentityHashMap<ByteBuffer,Boolean>());
    public static final List<String> violations=new ArrayList<String>();
    public static int next,bound,created,deleted,clientDepth,failStripe=-1;
    public static boolean failStorage,failDelete,zeroName;
    public static long resident,peak;
    public static final class Texture { public int width,height,uploaded; public long bytes,alpha; }
    public static void require(boolean ok,String why) {
        if(!ok) { violations.add(why); throw new IllegalStateException(why); }
    }
    public static int create() {
        if(zeroName)return 0;
        int id=++next;created++;live.put(id,new Texture());return id;
    }
    public static void delete(int id) {
        if(failDelete) { failDelete=false;throw new IllegalStateException("owned delete failure"); }
        Texture item=live.remove(id);require(item!=null,"Texture deleted exactly once");
        resident-=item.bytes;deleted++;
    }
    public static void bind(int id) { require(live.containsKey(id),"Bind only owned live texture");bound=id; }
    public static void allocate(int width,int height,ByteBuffer data) {
        require(data==null,"Storage allocation has no full-size transient buffer");
        if(failStorage)throw new IllegalStateException("owned storage failure");
        Texture item=live.get(bound);require(item!=null,"Storage has an owner");
        item.width=width;item.height=height;item.bytes=(long)width*height*4;
        resident+=item.bytes;peak=Math.max(peak,resident);
        require(resident<=44L*1024*1024,"Resident textures never exceed 32 MiB plus three 4 MiB atlases");
    }
    public static void stripe(int x,int y,int width,int height,ByteBuffer data) {
        if(failStripe==0)throw new IllegalStateException("owned stripe failure");
        if(failStripe>0)failStripe--;
        Texture item=live.get(bound);require(item!=null,"Stripe has an owner");
        require(x==0 && y==item.uploaded && width==item.width && y+height<=item.height,"Stripes cover each row once");
        require(data.isDirect() && data.remaining()==width*height*4 && data.capacity()==256*1024,"Upload scratch is bounded and exact");
        buffers.add(data);
        for(int at=data.position();at<data.limit();at+=4) {
            require(data.get(at)==(byte)255 && data.get(at+1)==(byte)255 && data.get(at+2)==(byte)255,"RGB raster retained");
            item.alpha+=data.get(at+3)&255;
        }
        item.uploaded+=height;
    }
    public static void query(IntBuffer values) {
        values.put(0,0);values.put(1,0);values.put(2,800);values.put(3,600);
    }
    public static int query(int name) { return name==34018?2:name==2976?5888:0; }
}'''

BODIES = {
    ('glGenTextures', '()I'): 'return GpuFixture.create();',
    ('glDeleteTextures', '(I)V'): 'GpuFixture.delete(a0);',
    ('glBindTexture', '(II)V'): 'GpuFixture.bind(a1);',
    ('glTexImage2D', '(IIIIIIIILjava/nio/ByteBuffer;)V'): 'GpuFixture.allocate(a3,a4,a8);',
    ('glTexSubImage2D', '(IIIIIIIILjava/nio/ByteBuffer;)V'): 'GpuFixture.stripe(a2,a3,a4,a5,a8);',
    ('glGetInteger', '(I)I'): 'return GpuFixture.query(a0);',
    ('glGetInteger', '(ILjava/nio/IntBuffer;)V'): 'GpuFixture.query(a1);',
    ('glIsEnabled', '(I)Z'): 'return true;',
    ('glPushClientAttrib', '(I)V'): 'GpuFixture.clientDepth++;',
    ('glPopClientAttrib', '()V'): 'GpuFixture.clientDepth--;GpuFixture.require(GpuFixture.clientDepth>=0,"Client attribute balance");',
}
NOOPS = {'glEnable', 'glBegin', 'glTexCoord2f', 'glVertex2f', 'glEnd', 'glPushAttrib',
         'glUseProgram', 'glMatrixMode', 'glPushMatrix', 'glScalef', 'glTranslatef',
         'glDisable', 'glShadeModel', 'glPolygonMode', 'glColorMask', 'glBlendEquation',
         'glBlendFunc', 'glActiveTexture', 'glTexEnvi', 'glLoadIdentity', 'glPopMatrix',
         'glPopAttrib', 'glScissor', 'glColor4f', 'glLineWidth', 'glTexParameteri', 'glPixelStorei'}


def java_type(value):
    if value.startswith('L'):
        return value[1:-1].replace('/', '.')
    return {'I': 'int', 'F': 'float', 'Z': 'boolean', 'V': 'void'}[value]


def fixtures(bytecode):
    # Mirror only exact linked GL signatures from the actual compiled renderer.
    # A new resource operation must have an explicit behavioral fixture above.
    methods = {}
    for ref in read_class(bytecode)['references']:
        if not ref['owner'].startswith('org/lwjgl/opengl/'):
            continue
        name, desc = ref['name'], ref['descriptor']
        body = BODIES.get((name, desc))
        if body is None:
            if name not in NOOPS or not desc.endswith('V'):
                raise ValueError('Unreviewed GL fixture operation: ' + repr(ref))
            body = ''
        inputs, output = desc[1:].split(')')
        types = re.findall(r'L[^;]+;|[IFZ]', inputs)
        params = ','.join(java_type(t) + ' a' + str(i) for i, t in enumerate(types))
        method = 'public static ' + java_type(output) + ' ' + name + '(' + params + ') {' + body + '}'
        methods.setdefault(ref['owner'], []).append(method)
    result = {'org/lwjgl/opengl/GpuFixture.java': STATE}
    for owner, declarations in methods.items():
        result[owner + '.java'] = 'package org.lwjgl.opengl; public final class ' + owner.rsplit('/', 1)[1] + ' {' + '\n'.join(declarations) + '}'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--java8', type=Path, help='Also test the same production bytecode on this Java 8 executable')
    args = parser.parse_args()
    classes = args.classes.resolve()
    runtime = str(classes) + os.pathsep + common.default_classpath(ROOT)
    with tempfile.TemporaryDirectory(prefix='adnin-render-lifecycle-') as directory:
        work = Path(directory)
        sources = [ROOT / 'tests/java/AdninUiLifecycleTest.java']
        for name, content in fixtures((classes / 'AdninUi.class').read_bytes()).items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, runtime, output, work / 'args.txt')
        runtimes = [common.find_java(args.jdk, 'java')]
        if args.java8:
            runtimes.append(args.java8)
        for java in runtimes:
            print(common.run([java, '-Xverify:all', '-Djava.awt.headless=true',
                              '-XX:MaxDirectMemorySize=2m', '-Xmx192m', '-cp', str(output) + os.pathsep + runtime,
                              'AdninUiLifecycleTest'], 'UI lifecycle pressure test', timeout=120))


if __name__ == '__main__':
    main()
