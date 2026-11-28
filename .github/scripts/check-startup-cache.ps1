# 시작 시간 · AOT 캐시 확인(#221): MSI 를 관리자 설치(msiexec /a — 등록 없이 폴더에 풀기)로 풀고 두 번 띄운다.
#   1번째: MSI 가 2초 단위로 밀어 놓은 jar 수정 시각을 앱이 되돌린다(이때는 캐시를 못 쓴다)
#   2번째: 캐시를 쓴다(-Xlog:aot 에 "Using AOT-linked classes: true")
# 이미지에 캐시가 없거나(훈련 실패) 창을 띄우지 못하면(데스크톱 없음) 경고만 한다. 캐시가 있고 앱도 떴는데 2번째에 캐시를 못 쓰면 실패.
# 저장소 루트에서 실행한다. PowerShell 5.1 · 7 둘 다 동작한다. 사용자의 %APPDATA%\Tanager 는 건드리지 않는다.
param(
    [string]$Image = 'app/bootstrap/build/jpackage/Tanager',
    [string]$InstallerDir = 'app/bootstrap/build/jpackage-installer',
    [int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'

function Warn([string]$message) {
    if ($env:GITHUB_ACTIONS -eq 'true') { Write-Host "::warning title=AOT 캐시::$message" } else { Write-Warning $message }
}

if (-not (Test-Path -LiteralPath (Join-Path $Image 'app/tanager.aot'))) {
    Warn "app-image 에 AOT 캐시가 없다(훈련 실행 실패 — jpackageImage 로그 참고). 캐시 없이도 산출물은 정상이다"
    exit 0
}
$msi = @(Get-ChildItem -Path $InstallerDir -Filter '*.msi' -ErrorAction SilentlyContinue)
if ($msi.Count -ne 1) { Write-Error "MSI 가 1개가 아님: $($msi.Name -join ', ')"; exit 1 }

$work = Join-Path ([IO.Path]::GetTempPath()) ("tanager-startup-check-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $work | Out-Null
try {
    $target = Join-Path $work 'installed'
    $extract = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/a', "`"$($msi[0].FullName)`"", '/qn', "TARGETDIR=`"$target`"") -Wait -PassThru
    if ($extract.ExitCode -ne 0) { Write-Error "msiexec /a 실패: $($extract.ExitCode)"; exit 1 }
    $exe = Get-ChildItem -Path $target -Recurse -Filter 'Tanager.exe' | Select-Object -First 1
    if (-not $exe) { Write-Error "풀어 놓은 MSI 에 Tanager.exe 가 없다"; exit 1 }
    $installed = $exe.DirectoryName

    $usedCache = @()
    foreach ($run in 1, 2) {
        $appData = Join-Path $work "appdata-$run"
        New-Item -ItemType Directory -Force (Join-Path $appData 'Tanager') | Out-Null
        # 기억한 셸이 있어야 셸 선택 화면 없이 첫 셸을 띄운다(cmd 는 모든 Windows 에 있다)
        Set-Content -Encoding ASCII (Join-Path $appData 'Tanager/config.json') '{ "schemaVersion": 1, "defaultProfile": "cmd" }'
        $env:APPDATA = $appData
        # 실행 옵션(cfg)보다 뒤에 읽혀 로그만 켠다
        $env:_JAVA_OPTIONS = '-Xlog:aot'
        $out = Join-Path $work "out-$run.txt"
        $err = Join-Path $work "err-$run.txt"
        $process = Start-Process -FilePath $exe.FullName -ArgumentList '--startup-training' -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
        $finished = $process.WaitForExit($TimeoutSeconds * 1000)
        # 끝나지 않았으면 이 폴더의 Tanager.exe 와 그 셸까지 끝낸다
        Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($installed, [StringComparison]::OrdinalIgnoreCase) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        $log = @(Get-Content -LiteralPath $out, $err -ErrorAction SilentlyContinue)
        $startup = $log | Where-Object { $_ -match 'startup:' } | Select-Object -First 1
        if (-not $finished -or -not $startup) {
            Warn "${run}번째 실행이 첫 창 · 첫 셸까지 가지 못했다(데스크톱 없음?) — 확인을 건너뛴다"
            exit 0
        }
        $cache = [bool]($log | Where-Object { $_ -match 'Using AOT-linked classes: true' })
        $usedCache += $cache
        Write-Host "${run}번째: AOT 캐시 $(if ($cache) { '씀' } else { '안 씀' }) · $($startup.Substring($startup.IndexOf('startup:')))"
    }
    if (-not $usedCache[1]) {
        Write-Error "설치한 이미지가 2번째 실행에도 AOT 캐시를 쓰지 않는다(jar 수정 시각 되돌리기 확인)"
        exit 1
    }
} finally {
    Remove-Item Env:_JAVA_OPTIONS -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
