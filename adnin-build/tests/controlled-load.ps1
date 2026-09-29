param(
  [Parameter(Mandatory=$true)][string]$Injector,
  [Parameter(Mandatory=$true)][string]$Dll,
  [Parameter(Mandatory=$true)][string]$DummyHost,
  [string]$Report='controlled-load.json'
)
$ErrorActionPreference='Stop'
$reportPath=if([IO.Path]::IsPathRooted($Report)){$Report}else{Join-Path (Get-Location) $Report}
$hostOutput=Join-Path $env:TEMP "lunar-full-build-$([guid]::NewGuid().ToString('N')).out"
$hostProcess=$null
$checks=@()
function Check([string]$name,[bool]$passed,[string]$detail='') {
  $script:checks+=@{name=$name;passed=$passed;detail=$detail}
  if(!$passed){throw "${name}: $detail"}
}
function Invoke-Injector([string[]]$CommandArguments) {
  # PowerShell does not always wait for a GUI-subsystem executable invoked with
  # &, so neither $LASTEXITCODE nor pipeline capture is reliable for this test.
  $startInfo=New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName=$Injector
  $startInfo.UseShellExecute=$false
  $startInfo.CreateNoWindow=$true
  $startInfo.RedirectStandardOutput=$true
  $startInfo.RedirectStandardError=$true
  # Quote using Windows argv rules; also works under Windows PowerShell 5.1,
  # whose ProcessStartInfo does not expose ArgumentList.
  $startInfo.Arguments=($CommandArguments | ForEach-Object {
    '"'+[regex]::Replace([regex]::Replace($_,'(\\*)"','$1$1\"'),'(\\+)$','$1$1')+'"'
  }) -join ' '
  $process=New-Object Diagnostics.Process
  $process.StartInfo=$startInfo
  try {
    if(!$process.Start()){throw 'Could not start the test injector'}
    $stdout=$process.StandardOutput.ReadToEndAsync()
    $stderr=$process.StandardError.ReadToEndAsync()
    if(!$process.WaitForExit(15000)) {
      $process.Kill()
      $process.WaitForExit()
      throw 'The test injector did not exit within 15 seconds'
    }
    $process.WaitForExit()
    return [pscustomobject]@{
      ExitCode=$process.ExitCode
      Stdout=$stdout.GetAwaiter().GetResult()
      Stderr=$stderr.GetAwaiter().GetResult()
    }
  } finally {$process.Dispose()}
}
try {
  $hostProcess=Start-Process -FilePath $DummyHost -PassThru -RedirectStandardOutput $hostOutput -WindowStyle Hidden
  $targetProcessId=$null
  for($i=0;$i -lt 100 -and !$targetProcessId;$i++) {
    Start-Sleep -Milliseconds 100
    if(Test-Path $hostOutput) {
      $line=Get-Content $hostOutput | Select-Object -First 1
      if($line){$targetProcessId=[int]$line}
    }
  }
  Check 'dummy host started' ([bool]$targetProcessId)
  $dryRun=Invoke-Injector @('--pid',"$targetProcessId",'--dll',$Dll,'--dry-run')
  $dryExit=$dryRun.ExitCode
  Write-Output $dryRun.Stdout $dryRun.Stderr
  Check 'dry-run succeeds' ($dryExit -eq 0) "exit=$dryExit"
  $injection=Invoke-Injector @('--pid',"$targetProcessId",'--dll',$Dll,'--inject','--timeout-ms','500')
  $injectExit=$injection.ExitCode
  Write-Output $injection.Stdout $injection.Stderr
  Check 'non-JVM host does not falsely report ready' ($injectExit -eq 8) "exit=$injectExit"
  Start-Sleep -Milliseconds 300
  $loaded=Get-Process -Id $targetProcessId -Module -ErrorAction SilentlyContinue | Where-Object { $_.FileName -eq (Resolve-Path $Dll).Path }
  Check 'real DLL appears in target module list' ([bool]$loaded)
  $result=@{passed=$true;targetProcessId=$targetProcessId;dryRunExit=$dryExit;injectExit=$injectExit;gameInjectionPerformed=$false;checks=$checks}
} finally {
  if($hostProcess -and !$hostProcess.HasExited){$hostProcess.Kill();$hostProcess.WaitForExit()}
  Remove-Item $hostOutput -Force -ErrorAction SilentlyContinue
  if(!$result){$result=@{passed=$false;gameInjectionPerformed=$false;checks=$checks}}
  $result | ConvertTo-Json -Depth 5 | Set-Content $reportPath -Encoding UTF8
}
Write-Output "Controlled DLL load passed; report: $reportPath"
