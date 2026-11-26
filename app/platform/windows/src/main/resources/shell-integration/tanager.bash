# Tanager 셸 통합 (WSL bash). `bash --rcfile <이 파일> -i` 로 읽힌다(ADR-0003 · 0014).
# 프롬프트마다 OSC 7 로 현재 경로를 알린다. 사용자 설정과 프롬프트 훅은 덮어쓰지 않는다.
# 줄바꿈은 LF 만 — CR 이 섞이면 bash 가 $'\r' 오류로 죽는다.

# --rcfile 은 -l 과 함께 쓸 수 없어 로그인 셸 설정이 읽히지 않는다(#55).
# 그래서 bash 로그인 셸 순서를 흉내 낸다: /etc/profile → ~/.bash_profile | ~/.bash_login | ~/.profile
# 중 처음 있는 것. 셋 다 없으면 ~/.bashrc (보통 ~/.profile 이 ~/.bashrc 를 읽으므로 두 번 읽지 않게).
# Debian 계열 bash 는 --rcfile 을 줘도 /etc/bash.bashrc 를 먼저 읽고, /etc/profile 은 PS1 이 있으면 그것을
# 또 읽는다(sudo 안내가 두 번 나온다). /etc/profile 을 읽는 동안만 PS1 을 비우고 원래대로(unset 이면 unset) 돌린다.
if [ -z "${__tanager_profile_loaded-}" ]; then
  __tanager_profile_loaded=1
  if [ -r /etc/profile ]; then
    if [ "${PS1+set}" ]; then __tanager_ps1=$PS1; else unset __tanager_ps1; fi
    PS1=
    . /etc/profile
    if [ "${__tanager_ps1+set}" ]; then PS1=$__tanager_ps1; else unset PS1; fi
    unset __tanager_ps1
    # 비운 PS1 때문에 /etc/profile.d/bash_completion.sh 도 건너뛰었다 — 로그인 셸처럼 completion 을 불러온다
    if [ -z "${BASH_COMPLETION_VERSINFO-}" ] && [ -r /etc/profile.d/bash_completion.sh ]; then
      . /etc/profile.d/bash_completion.sh
    fi
  fi
  if [ -r ~/.bash_profile ]; then
    . ~/.bash_profile
  elif [ -r ~/.bash_login ]; then
    . ~/.bash_login
  elif [ -r ~/.profile ]; then
    . ~/.profile
  elif [ -r ~/.bashrc ]; then
    . ~/.bashrc
  fi
fi

__tanager_osc7() {
  local LC_ALL=C
  # LC_ALL=C 덕분에 ${p:i:1} 이 바이트 단위다 — 빼면 한글이 %D55C 처럼 코드 포인트로 나간다(M0 에서 재현)
  local p=$PWD out= c i
  for (( i = 0; i < ${#p}; i++ )); do
    c=${p:i:1}
    case $c in
      [a-zA-Z0-9/._~-]) out+=$c ;;
      *) printf -v c '%%%02X' "'$c"; out+=$c ;;
    esac
  done
  printf '\e]7;file://%s%s\e\\' "$HOSTNAME" "$out"
}

# 다른 훅(starship 등)과 PS1 이 직전 명령의 $? 를 그대로 보도록 받은 값을 돌려준다
__tanager_prompt() {
  local status=$?
  __tanager_osc7
  return "$status"
}

# bash 5.1+ 의 배열 PROMPT_COMMAND 는 원소를 더하고, 문자열이면 앞에 붙인다. 이미 있으면 두 번 넣지 않는다.
# 사용자가 PROMPT_COMMAND 를 export 해 두면 중첩 bash 가 훅 이름만 물려받는다 — 함수도 함께 export 해
# 자식의 프롬프트마다 "__tanager_prompt: command not found" 가 뜨지 않게 한다(자식도 OSC 7 을 낸다).
__tanager_install_hook() {
  local entry decl flags
  decl=$(declare -p PROMPT_COMMAND 2>/dev/null)
  flags=${decl#declare -}
  flags=${flags%% *}
  if [[ $flags == *a* ]]; then
    for entry in "${PROMPT_COMMAND[@]}"; do
      [[ $entry == *__tanager_prompt* ]] && return 0
    done
    PROMPT_COMMAND+=(__tanager_prompt)
  elif [[ ${PROMPT_COMMAND-} != *__tanager_prompt* ]]; then
    PROMPT_COMMAND="__tanager_prompt${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
  fi
  if [[ $flags == *x* ]]; then
    export -f __tanager_prompt __tanager_osc7
  fi
}
__tanager_install_hook
unset -f __tanager_install_hook
