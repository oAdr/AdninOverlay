"""Execute production match-team helper with owned entity/Tab fixtures, no game or IO."""
import argparse
import importlib.util
import os
from pathlib import Path
import tempfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('teams_adapter_fixtures',Path(__file__).with_name('test_anticheat_adapter.py'))
adapter=importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)
common=adapter.common


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--runtime-jdk',type=Path,help='Optional JDK used to execute Java 8-compatible compiled fixtures')
    parser.add_argument('--classes',type=Path,required=True)
    parser.add_argument('--source',action='store_true',help='Compile the current production helper against the owned fixtures before testing')
    args=parser.parse_args()
    classes=args.classes.resolve()
    if not (classes/'AdninMatchTeams.class').is_file():raise ValueError('Missing production AdninMatchTeams bytecode')
    with tempfile.TemporaryDirectory(prefix='adnin-match-teams-') as folder:
        work=Path(folder);sources=[ROOT/'tests/java/AdninMatchTeamsTest.java']
        if args.source:sources.append(ROOT/'src/java/AdninMatchTeams.java')
        for name,content in adapter.FIXTURES.items():
            if name=='AdninAnticheatAdapterTest.java':continue
            file=work/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(content,encoding='utf-8');sources.append(file)
        output=work/'classes'
        common.compile_sources(common.find_java(args.jdk,'javac'),sources,str(classes),output,work/'args.txt')
        print(common.run([common.find_java(args.runtime_jdk or args.jdk,'java'),'-Xverify:all','-cp',str(output)+os.pathsep+str(classes),
                          'AdninMatchTeamsTest'],'Match teams production helper test'))


if __name__=='__main__':main()
