param([string]$Toolchain,[string]$CMake,[string]$Ninja,[string]$Nasm,[string]$Jdk,[string]$Python,[string]$RuntimeClasspath,[string]$VanillaJar,[string]$BuildDirectory='build')
$ErrorActionPreference='Stop'
$workspace=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$sibling=Join-Path (Split-Path $workspace -Parent) 'https-github-com-freecodexyz-free-code'
if(!$Toolchain){$Toolchain=Join-Path $workspace 'work/toolchain/llvm-mingw-20260922-ucrt-x86_64'}
if(!$CMake){$CMake=Join-Path $workspace 'work/python-tools/cmake/data/bin/cmake.exe'}
if(!$Ninja){$Ninja=Join-Path $workspace 'work/python-tools/bin/ninja.exe'}
if(!$Nasm){$Nasm=Join-Path $sibling 'work/nasm/nasm-3.02/nasm.exe'}
if(!$Jdk){$Jdk='C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot'}
if(!$Python){
  $bundledPython=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
  $Python=if(Test-Path -LiteralPath $bundledPython -PathType Leaf){$bundledPython}else{Join-Path $Toolchain 'python/bin/python.exe'}
}
$env:PYTHONPATH=(Join-Path $workspace 'work/python-tools')
foreach($path in @($Python,$CMake,$Ninja,$Nasm,(Join-Path $Jdk 'bin/javac.exe'),(Join-Path $Toolchain 'bin/clang++.exe'))){
  if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing build tool: $path. Pass its path as a Build.ps1 argument."}
}
& $Python -c 'import zlib, ctypes, pefile'
if($LASTEXITCODE -ne 0){throw 'Use -Python with a complete Python runtime containing zlib, ctypes and pefile.'}
$out=if([IO.Path]::IsPathRooted($BuildDirectory)){$BuildDirectory}else{Join-Path $PSScriptRoot $BuildDirectory}
$buildArgs=@((Join-Path $PSScriptRoot 'scripts/build.py'),'--nasm',$Nasm,'--jdk',$Jdk,'--toolchain',$Toolchain,'--cmake',$CMake,'--ninja',$Ninja,'--build',$out)
if($RuntimeClasspath){$buildArgs+=@('--classpath',$RuntimeClasspath)}
if($VanillaJar){$buildArgs+=@('--vanilla-jar',$VanillaJar)}
& $Python @buildArgs
if($LASTEXITCODE -ne 0){throw 'Adnin build failed'}
