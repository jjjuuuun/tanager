# 배포 파일 모으기: app-image → zip, MSI → 이름 맞추기. release 와 CI package-smoke 가 같이 쓴다(#22).
#   -Version  태그 버전(예 0.2.0, 0.2.0-rc.1). 파일 이름에 그대로 쓴다
#   -SkipMsi  프리릴리스는 MSI 를 붙이지 않는다 — MSI 버전은 숫자만이라 rc 와 정식의 ProductCode 가 같아져
#             정식판을 rc 위에 설치하면 업그레이드가 안 된다(jpackage 는 vendor · name · version 으로 ProductCode 를 정한다)
# 저장소 루트에서 실행한다. PowerShell 5.1 · 7 둘 다 동작한다.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$SkipMsi
)
$ErrorActionPreference = 'Stop'

$image = 'app/bootstrap/build/jpackage/Tanager'
if (-not (Test-Path -LiteralPath $image)) { Write-Error "app-image 가 없음: $image"; exit 1 }
Compress-Archive -Path $image -DestinationPath "Tanager-$Version-windows-x64.zip" -Force

if ($SkipMsi) {
    Write-Host "프리릴리스($Version): MSI 를 붙이지 않는다"
    exit 0
}
$msi = @(Get-ChildItem -Path 'app/bootstrap/build/jpackage-installer' -Filter '*.msi' -ErrorAction SilentlyContinue)
if ($msi.Count -ne 1) { Write-Error "MSI 가 1개가 아님: $($msi.Name -join ', ')"; exit 1 }
Copy-Item -LiteralPath $msi[0].FullName -Destination "Tanager-$Version-windows-x64.msi" -Force
Write-Host "Tanager-$Version-windows-x64.zip, Tanager-$Version-windows-x64.msi"
