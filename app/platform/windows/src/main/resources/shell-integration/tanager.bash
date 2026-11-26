# Tanager 셸 통합 (WSL bash). `bash --rcfile <이 파일> -i` 로 읽힌다(ADR-0003 · 0014).
# 프롬프트마다 OSC 7 로 현재 경로를 알린다. 사용자 설정과 프롬프트 훅은 덮어쓰지 않는다.
# 줄바꿈은 LF 만 — CR 이 섞이면 bash 가 $'\r' 오류로 죽는다.

# --rcfile 은 -l 과 함께 쓸 수 없어 로그인 셸 설정이 읽히지 않는다(#55).
# 그래서 bash 로그인 셸 순서를 흉내 낸다: /etc/profile → ~/.bash_profile | ~/.bash_login | ~/.profile
# 중 처음 있는 것. 셋 다 없으면 ~/.bashrc (보통 ~/.profile 이 ~/.bashrc 를 읽으므로 두 번 읽지 않게).
if [ -z "${__tanager_profile_loaded-}" ]; then
  __tanager_profile_loaded=1
  if [ -r /etc/profile ]; then . /etc/profile; fi
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
__tanager_install_hook() {
  local entry
  if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
    for entry in "${PROMPT_COMMAND[@]}"; do
      [[ $entry == *__tanager_prompt* ]] && return 0
    done
    PROMPT_COMMAND+=(__tanager_prompt)
  elif [[ ${PROMPT_COMMAND-} != *__tanager_prompt* ]]; then
    PROMPT_COMMAND="__tanager_prompt${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
  fi
}
__tanager_install_hook
unset -f __tanager_install_hook
