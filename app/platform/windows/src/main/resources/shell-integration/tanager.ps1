# Tanager 셸 통합 (Windows PowerShell 5.1). `-Command ". '<이 파일>'"` 로 프로필($PROFILE)을 읽은 뒤 dot-source 된다(ADR-0003 · 0014).
# 프롬프트마다 OSC 9;9 로 현재 경로를 알린다. 기존 prompt(oh-my-posh 등)를 감싸서 지우지 않는다.
# 5.1 문법만 쓴다(`e · ?? · 삼항 없음). 파일은 UTF-8 BOM · CRLF — BOM 이 없으면 5.1 이 한글을 깨뜨린다.

# 두 번 dot-source 해도 다시 감싸지 않는다(감싼 prompt 를 또 감싸면 OSC 가 두 번 나간다)
if (-not (Test-Path -LiteralPath 'Variable:global:__TanagerOriginalPrompt')) {
    $global:__TanagerOriginalPrompt = $function:prompt

    function global:prompt {
        # 가장 먼저 잡는다 — 다음 문장부터 $? 가 바뀐다
        $tanagerSucceeded = $global:?
        $tanagerExitCode = $global:LASTEXITCODE

        $tanagerOsc = ''
        $tanagerLocation = $executionContext.SessionState.Path.CurrentLocation
        if ($tanagerLocation.Provider.Name -eq 'FileSystem') {
            $e = [char]27
            $tanagerOsc = "$e]9;9;`"$($tanagerLocation.ProviderPath)`"$e\"
        }

        # 원래 prompt 가 직전 명령의 $? · $LASTEXITCODE 를 그대로 보게 한다($? 는 직접 대입할 수 없어 실패를 한 번 일으킨다)
        $global:LASTEXITCODE = $tanagerExitCode
        if ($null -eq $global:__TanagerOriginalPrompt) {
            $tanagerOut = "PS $($tanagerLocation)$('>' * ($nestedPromptLevel + 1)) "
        } elseif ($tanagerSucceeded) {
            $tanagerOut = & $global:__TanagerOriginalPrompt
        } else {
            Write-Error -Message 'tanager' -ErrorAction Ignore
            $tanagerOut = & $global:__TanagerOriginalPrompt
        }
        # 원래 prompt 안의 외부 명령(git 등)이 바꾼 값을 사용자에게 되돌린다
        $global:LASTEXITCODE = $tanagerExitCode

        return $tanagerOsc + ($tanagerOut -join '')
    }
}
