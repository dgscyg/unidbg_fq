#!/usr/bin/env bash
set -euo pipefail

setup_locale() {
  local target=""

  if [ -n "${UNIDBG_LANG:-}" ]; then
    target="${UNIDBG_LANG}"
  elif [ -n "${LANG:-}" ]; then
    case "${LANG}" in
      *UTF-8*|*utf-8*|*utf8*) return 0 ;;
    esac
  fi

  if [ -z "$target" ] && command -v locale >/dev/null 2>&1; then
    if locale -a 2>/dev/null | grep -qi '^zh_CN\.UTF-8$'; then
      target="zh_CN.UTF-8"
    elif locale -a 2>/dev/null | grep -qi '^C\.UTF-8$'; then
      target="C.UTF-8"
    elif locale -a 2>/dev/null | grep -qi '^en_US\.UTF-8$'; then
      target="en_US.UTF-8"
    fi
  fi

  if [ -n "$target" ]; then
    export LANG="${target}"
    export LC_ALL="${target}"
  fi
}

setup_locale

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
BASE_DIR="$(cd "$(dirname "${SCRIPT_PATH}")" && pwd)"
LOG_FILE="${BASE_DIR}/unidbg_onekey.log"
DISCLAIMER_FILE="${BASE_DIR}/.unidbg_disclaimer_accepted"

REPO_URL="https://github.com/dgscyg/unidbg_fq.git"
DEFAULT_APP_DIR="unidbg"
APP_DIR_INPUT="${DEFAULT_APP_DIR}"
ACTION=""
SHOW_HELP=0
SHOW_MENU=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ui|--tui|--no-ui) ;;
    -h|--help) SHOW_HELP=1 ;;
    --menu) SHOW_MENU=1 ;;
    --action) shift; [ "$#" -gt 0 ] && ACTION="$1" ;;
    --dir) shift; [ "$#" -gt 0 ] && APP_DIR_INPUT="$1" ;;
    --dir=*) APP_DIR_INPUT="${1#--dir=}" ;;
    --) shift; break ;;
    -*)
      echo "未知参数：$1"
      SHOW_HELP=1
      ;;
    *) APP_DIR_INPUT="$1" ;;
  esac
  shift
done

resolve_app_dir() {
  local dir="$1"
  if [[ "$dir" = /* ]]; then
    printf "%s" "$dir"
  else
    printf "%s/%s" "$BASE_DIR" "$dir"
  fi
}

APP_DIR="$(resolve_app_dir "$APP_DIR_INPUT")"

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

get_java_opts() {
  if [ -n "${UNIDBG_JAVA_OPTS:-}" ]; then
    echo "${UNIDBG_JAVA_OPTS}"
    return 0
  fi
  if [ -n "${JAVA_OPTS:-}" ]; then
    echo "${JAVA_OPTS}"
    return 0
  fi
  echo "-Xms1536m -Xmx1536m"
}

get_current_commit() {
  if [ -d "${APP_DIR}/.git" ] && need_cmd git; then
    git -C "${APP_DIR}" rev-parse HEAD 2>/dev/null || true
  fi
}

get_remote_commit() {
  if need_cmd git; then
    git ls-remote "${REPO_URL}" HEAD 2>/dev/null | awk '{print $1}' | head -n 1
  fi
}

get_last_build_commit() {
  if [ -f "${APP_DIR}/.unidbg_last_build" ]; then
    head -n 1 "${APP_DIR}/.unidbg_last_build" | tr -d '\r\n'
  fi
}

save_last_build_commit() {
  local commit="$1"
  if [ -z "$commit" ]; then
    date +%s > "${APP_DIR}/.unidbg_last_build" 2>/dev/null || true
  else
    printf "%s\n" "$commit" > "${APP_DIR}/.unidbg_last_build" 2>/dev/null || true
  fi
}

remove_old_artifacts() {
  if [ -d "${APP_DIR}/target" ]; then
    rm -f "${APP_DIR}/target/"*.jar 2>/dev/null || true
  fi
}

check_update_notice() {
  UPDATE_AVAILABLE=0
  if [ -d "${APP_DIR}/.git" ] && need_cmd git; then
    local current_commit=""
    local remote_commit=""
    current_commit=$(get_current_commit || true)
    remote_commit=$(get_remote_commit || true)
    if [ -n "$current_commit" ] && [ -n "$remote_commit" ] && [ "$current_commit" != "$remote_commit" ]; then
      UPDATE_AVAILABLE=1
    fi
  fi
}

SUDO=""
if [ "$(id -u)" -ne 0 ] && need_cmd sudo; then
  SUDO="sudo"
fi

ui_info() {
  echo "$1"
}

ui_msg() {
  echo "$1"
}

ui_yesno() {
  read -r -p "$1 [y/N]: " ans
  case "${ans}" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

ui_input() {
  local prompt="$1"
  local default_value="$2"
  read -r -p "$prompt [$default_value]: " input
  if [ -z "$input" ]; then
    echo "$default_value"
  else
    echo "$input"
  fi
}

prompt_build_choice() {
  local choice=""
  while true; do
    read -r -p "无法判断是否有更新，请先到浏览器查看是否有更新。选择操作：1) 使用旧产物(默认) 2) 删除旧产物并重新编译 [1/2]: " choice
    case "${choice}" in
      ""|1) return 0 ;;
      2) return 1 ;;
      *) echo "请输入 1 或 2。" ;;
    esac
  done
}

prompt_update_choice() {
  local choice=""
  while true; do
    read -r -p "检测到代码有更新。选择操作：1) 使用旧产物(默认) 2) 删除旧产物并重新编译 [1/2]: " choice
    case "${choice}" in
      ""|1) return 0 ;;
      2) return 1 ;;
      *) echo "请输入 1 或 2。" ;;
    esac
  done
}

prompt_remote_update_choice() {
  local choice=""
  while true; do
    read -r -p "检测到 GitHub 仓库有更新。选择操作：1) 先更新代码(默认) 2) 使用本地旧代码继续编译 [1/2]: " choice
    case "${choice}" in
      ""|1) return 0 ;;
      2) return 1 ;;
      *) echo "请输入 1 或 2。" ;;
    esac
  done
}

prompt_remote_skip_choice() {
  local choice=""
  while true; do
    read -r -p "检测到 GitHub 仓库有更新。选择操作：1) 使用旧产物并跳过编译(默认) 2) 更新代码并重新编译 [1/2]: " choice
    case "${choice}" in
      ""|1) return 0 ;;
      2) return 1 ;;
      *) echo "请输入 1 或 2。" ;;
    esac
  done
}

print_main_menu() {
  cat << 'EOF_MENU'
1) 安装/初始化
2) 更新代码
3) 编译项目
4) 启动程序
5) 查看日志
6) 清理构建
7) 重新安装
8) 环境信息
w) 启动 Web 控制台
9) 退出
0) 不常用选项
EOF_MENU
}

print_uncommon_menu() {
  cat << 'EOF_UNCOMMON'
1) 卸载全部（含脚本）
2) 仅安装依赖
3) 仅下载/克隆源码
4) 切换项目目录
5) 清理 Maven 缓存
6) 重启 API 服务（杀旧进程）
7) 重启 Web 控制台（杀旧进程）
8) 更新日志
9) 返回
EOF_UNCOMMON
}

show_help() {
  cat << 'EOF_HELP'
用法：
  bash unidbg_onekey.sh [--dir 路径] [--action 动作] [--menu]

默认使用命令行菜单（已移除图形界面）。

菜单选项：
EOF_HELP
  print_main_menu
  cat << 'EOF_HELP_2'

不常用选项：
EOF_HELP_2
  print_uncommon_menu
  cat << 'EOF_HELP_3'

可用参数：
  --menu            只显示菜单并退出
  --action <动作>   install | update | build | start | logs | clean | reinstall | env
  --dir <路径>      项目目录（可相对脚本目录）
  --help | -h       显示帮助

环境变量：
  UNIDBG_LANG       强制设置 LANG/LC_ALL（例如 zh_CN.UTF-8）
  UNIDBG_JAVA_OPTS  覆盖 JVM 参数（例如 -Xms512m -Xmx1024m）
  JAVA_OPTS         作为兜底的 JVM 参数（默认 -Xms1536m -Xmx1536m）
  UNIDBG_API_TARGET Web 控制台代理的 API 目标地址（默认 http://127.0.0.1:9999）
EOF_HELP_3
}

if [ "$SHOW_HELP" -eq 1 ]; then
  show_help
  exit 0
fi

if [ "$SHOW_MENU" -eq 1 ]; then
  print_main_menu
  exit 0
fi

show_disclaimer() {
  cat << 'EOF_DISCLAIMER'
免责声明
本项目仅供学习交流使用，使用时请遵守相关法律法规。
用户需自行承担由此引发的任何法律责任和风险。
程序的作者及项目贡献者不对因使用本程序所造成的任何损失、损害或法律后果负责！
EOF_DISCLAIMER
}

confirm_disclaimer() {
  while true; do
    read -r -p "同意免责声明并继续？[Y/n]: " ans
    case "${ans}" in
      y|Y|"") return 0 ;;
      n|N) return 1 ;;
      *) echo "请输入 Y 或 N。" ;;
    esac
  done
}

show_changelog() {
  cat << 'EOF_CHANGELOG'
更新日志（脚本功能改动）
- 移除图形界面，全部改为命令行交互
- Maven 构建/清理切换到项目目录执行，避免 MissingProjectException
- Web 控制台改为同源 API 代理，解决跨域
- 搜索/封面/章节字段兼容真实接口字段（coverUrl、item_data_list 等）
- 多章下载改用批量接口 /api/fqnovel/chapters/batch，单章仍用原接口，支持分批 30 章
- JVM 默认内存固定 1.5G（-Xms1536m -Xmx1536m），可用 UNIDBG_JAVA_OPTS 覆盖
- 不常用选项新增重启 API / 重启 Web 控制台，自动杀旧进程
- 免责声明首次运行确认，默认 Y，同意后记录 .unidbg_disclaimer_accepted（卸载会清理）
- 进入脚本自动检测 GitHub 仓库是否有更新，提示先更新代码再编译
- 检测到更新时编译默认选择 1（使用旧产物），需要时再手动选择重新编译
- 编译时再次检测 GitHub 更新，提示先更新代码或继续使用本地旧代码编译
EOF_CHANGELOG
}

menu_main() {
  if [ -t 1 ]; then
    echo ""
    print_main_menu
  else
    echo "" >&2
    print_main_menu >&2
  fi
  read -r -p "请选择: " choice
  echo "${choice}"
}

menu_uncommon() {
  if [ -t 1 ]; then
    echo ""
    print_uncommon_menu
  else
    echo "" >&2
    print_uncommon_menu >&2
  fi
  read -r -p "请选择: " choice
  echo "${choice}"
}

log_exec() {
  if [ -n "${LOG_FILE:-}" ] && need_cmd tee; then
    "$@" 2>&1 | tee -a "$LOG_FILE"
  elif [ -n "${LOG_FILE:-}" ]; then
    "$@" >> "$LOG_FILE" 2>&1
  else
    "$@"
  fi
}

run_cmd() {
  local desc="$1"
  shift
  ui_info "$desc"
  if ! log_exec "$@"; then
    ui_msg "执行失败：${desc}\n\n请查看日志：${LOG_FILE}"
    return 1
  fi
}

run_cmd_in_dir() {
  local desc="$1"
  local dir="$2"
  shift 2
  ui_info "$desc"
  if ! (cd "$dir" && log_exec "$@"); then
    ui_msg "执行失败：${desc}\n\n请查看日志：${LOG_FILE}"
    return 1
  fi
}

# 检测端口是否被占用（只检测不杀进程）
port_in_use() {
  local port="$1"
  if need_cmd ss; then
    ss -ltn 2>/dev/null | grep -q ":${port} " && return 0
  elif need_cmd lsof; then
    lsof -ti "tcp:${port}" 2>/dev/null | grep -q . && return 0
  elif need_cmd netstat; then
    netstat -tln 2>/dev/null | grep -q ":${port} " && return 0
  fi
  return 1
}

kill_port() {
  local port="$1"
  local pids=""

  if need_cmd lsof; then
    pids=$(lsof -ti "tcp:${port}" 2>/dev/null || true)
  elif need_cmd fuser; then
    pids=$(fuser -n tcp "$port" 2>/dev/null || true)
  elif need_cmd ss; then
    pids=$(ss -ltnp 2>/dev/null | awk -v port=":$port" '$4 ~ port {print $6}' | sed -n 's/.*pid=\\([0-9]*\\).*/\\1/p' | sort -u)
  elif need_cmd netstat; then
    pids=$(netstat -tlnp 2>/dev/null | awk -v port=":$port" '$4 ~ port {print $7}' | cut -d/ -f1 | grep -E '^[0-9]+$' | sort -u)
  fi

  if [ -z "$pids" ]; then
    return 1
  fi

  for pid in $pids; do
    if [ -n "$pid" ]; then
      kill "$pid" 2>/dev/null || true
    fi
  done

  sleep 1
  for pid in $pids; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
}

PKG_MGR=""
UPDATED=0

detect_pkg_manager() {
  if need_cmd apt-get; then
    PKG_MGR="apt"
  elif need_cmd dnf; then
    PKG_MGR="dnf"
  elif need_cmd yum; then
    PKG_MGR="yum"
  elif need_cmd pacman; then
    PKG_MGR="pacman"
  elif need_cmd zypper; then
    PKG_MGR="zypper"
  elif need_cmd apk; then
    PKG_MGR="apk"
  else
    PKG_MGR=""
  fi
}

pkg_update() {
  if [ "$UPDATED" -eq 1 ]; then
    return 0
  fi
  case "$PKG_MGR" in
    apt) run_cmd "更新软件源..." $SUDO apt-get update ;;
    dnf) run_cmd "更新软件源..." $SUDO dnf makecache -y ;;
    yum) run_cmd "更新软件源..." $SUDO yum makecache -y ;;
    pacman) run_cmd "更新软件源..." $SUDO pacman -Sy --noconfirm ;;
    zypper) run_cmd "更新软件源..." $SUDO zypper -n refresh ;;
    apk) run_cmd "更新软件源..." $SUDO apk update ;;
    *) return 1 ;;
  esac
  UPDATED=1
}

pkg_install_try() {
  case "$PKG_MGR" in
    apt) log_exec $SUDO apt-get install -y "$@" ;;
    dnf) log_exec $SUDO dnf install -y "$@" ;;
    yum) log_exec $SUDO yum install -y "$@" ;;
    pacman) log_exec $SUDO pacman -S --noconfirm "$@" ;;
    zypper) log_exec $SUDO zypper -n install "$@" ;;
    apk) log_exec $SUDO apk add "$@" ;;
    *) return 1 ;;
  esac
}

ensure_pkg_manager() {
  detect_pkg_manager
  if [ -z "$PKG_MGR" ]; then
    ui_msg "未检测到包管理器，请手动安装依赖。"
    return 1
  fi
  if [ -z "$SUDO" ] && [ "$(id -u)" -ne 0 ]; then
    ui_msg "需要管理员权限安装依赖，请使用 sudo 运行脚本或先手动安装。"
    return 1
  fi
}

install_java() {
  if need_cmd java; then
    return 0
  fi
  ensure_pkg_manager || return 1
  pkg_update || return 1
  ui_info "安装 Java..."
  case "$PKG_MGR" in
    apt)
      if pkg_install_try openjdk-8-jdk; then return 0; fi
      if pkg_install_try openjdk-11-jdk; then return 0; fi
      if pkg_install_try default-jdk; then return 0; fi
      ;;
    dnf|yum)
      if pkg_install_try java-1.8.0-openjdk-devel; then return 0; fi
      if pkg_install_try java-11-openjdk-devel; then return 0; fi
      if pkg_install_try java-17-openjdk-devel; then return 0; fi
      ;;
    pacman)
      if pkg_install_try jdk8-openjdk; then return 0; fi
      if pkg_install_try jdk-openjdk; then return 0; fi
      ;;
    zypper)
      if pkg_install_try java-1_8_0-openjdk-devel; then return 0; fi
      if pkg_install_try java-11-openjdk-devel; then return 0; fi
      if pkg_install_try java-17-openjdk-devel; then return 0; fi
      ;;
    apk)
      if pkg_install_try openjdk8; then return 0; fi
      if pkg_install_try openjdk11; then return 0; fi
      if pkg_install_try openjdk17; then return 0; fi
      ;;
  esac
  ui_msg "Java 安装失败，请手动安装 JDK 8 或更高版本。"
  return 1
}

install_common_packages() {
  local pkgs=()
  if ! need_cmd git; then pkgs+=("git"); fi
  if ! need_cmd curl; then pkgs+=("curl"); fi
  if ! need_cmd wget; then pkgs+=("wget"); fi
  if ! need_cmd unzip; then pkgs+=("unzip"); fi
  if ! need_cmd mvn; then pkgs+=("maven"); fi

  if [ "${#pkgs[@]}" -eq 0 ]; then
    return 0
  fi
  ensure_pkg_manager || return 1
  pkg_update || return 1
  for pkg in "${pkgs[@]}"; do
    ui_info "安装 ${pkg}..."
    if ! pkg_install_try "$pkg"; then
      ui_msg "安装 ${pkg} 失败，请手动安装后再试。"
      return 1
    fi
  done
}

ensure_repo() {
  if [ -d "${APP_DIR}/.git" ] || [ -f "${APP_DIR}/pom.xml" ]; then
    return 0
  fi

  if need_cmd git; then
    run_cmd "克隆仓库..." git clone "${REPO_URL}" "${APP_DIR}" || return 1
    return 0
  fi

  if ! need_cmd curl && ! need_cmd wget; then
    ui_msg "未检测到 git 且 curl/wget 不可用，请先安装其中之一。"
    return 1
  fi
  if ! need_cmd unzip; then
    ui_msg "未检测到 unzip，请先安装 unzip。"
    return 1
  fi

  local tmp_zip
  local extract_dir
  tmp_zip="$(mktemp -t unidbg.XXXXXX.zip)"
  extract_dir="$(mktemp -d -t unidbg.XXXXXX)"

  ui_info "下载仓库压缩包..."
  if need_cmd curl; then
    if ! log_exec curl -fL -o "${tmp_zip}" "https://github.com/dgscyg/unidbg_fq/archive/refs/heads/main.zip"; then
      log_exec curl -fL -o "${tmp_zip}" "https://github.com/dgscyg/unidbg_fq/archive/refs/heads/master.zip" || true
    fi
  else
    if ! log_exec wget -O "${tmp_zip}" "https://github.com/dgscyg/unidbg_fq/archive/refs/heads/main.zip"; then
      log_exec wget -O "${tmp_zip}" "https://github.com/dgscyg/unidbg_fq/archive/refs/heads/master.zip" || true
    fi
  fi

  if [ ! -s "${tmp_zip}" ]; then
    ui_msg "下载失败，请检查网络或稍后重试。"
    rm -rf "${tmp_zip}" "${extract_dir}"
    return 1
  fi

  if ! log_exec unzip -q "${tmp_zip}" -d "${extract_dir}"; then
    ui_msg "解压失败，请查看日志：${LOG_FILE}"
    rm -rf "${tmp_zip}" "${extract_dir}"
    return 1
  fi

  rm -rf "${APP_DIR}"
  mkdir -p "$(dirname "${APP_DIR}")"
  if [ -d "${extract_dir}/unidbg-main" ]; then
    mv "${extract_dir}/unidbg-main" "${APP_DIR}"
  elif [ -d "${extract_dir}/unidbg-master" ]; then
    mv "${extract_dir}/unidbg-master" "${APP_DIR}"
  else
    ui_msg "未找到解压后的目录，请查看日志：${LOG_FILE}"
    rm -rf "${tmp_zip}" "${extract_dir}"
    return 1
  fi

  rm -rf "${tmp_zip}" "${extract_dir}"
}

get_mvn_cmd() {
  if [ -x "${APP_DIR}/mvnw" ]; then
    chmod +x "${APP_DIR}/mvnw" || true
    echo "${APP_DIR}/mvnw"
    return 0
  fi
  if need_cmd mvn; then
    echo "mvn"
    return 0
  fi
  return 1
}

find_jar() {
  local jar
  if [ -f "${APP_DIR}/target/fqnovel.jar" ]; then
    echo "${APP_DIR}/target/fqnovel.jar"
    return 0
  fi
  if [ -d "${APP_DIR}/target" ]; then
    jar=$(find "${APP_DIR}/target" -maxdepth 1 -type f -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -n 1)
    if [ -n "$jar" ]; then
      echo "$jar"
      return 0
    fi
  fi
  return 1
}

action_install() {
  ui_info "开始安装..."
  install_common_packages || return 1
  install_java || return 1
  ensure_repo || return 1
  ui_msg "安装完成。"
}

action_update() {
  if [ -d "${APP_DIR}/.git" ] && need_cmd git; then
    run_cmd "更新代码..." git -C "${APP_DIR}" pull || return 1
  else
    if ! ui_yesno "当前目录不是 git 仓库或未安装 git，是否重新下载源码？"; then
      return 0
    fi
    ensure_repo || return 1
  fi
  ui_msg "更新完成。"
}

action_build() {
  ensure_repo || return 1
  install_java || return 1
  if ! mvn_cmd=$(get_mvn_cmd); then
    install_common_packages || true
    if ! mvn_cmd=$(get_mvn_cmd); then
      ui_msg "未检测到 Maven 或 mvnw，请先安装 Maven。"
      return 1
    fi
  fi
  local jar_path=""
  jar_path=$(find_jar || true)
  local current_commit=""
  local remote_commit=""
  if [ -d "${APP_DIR}/.git" ] && need_cmd git; then
    current_commit=$(get_current_commit || true)
    remote_commit=$(get_remote_commit || true)
    if [ -n "$current_commit" ] && [ -n "$remote_commit" ] && [ "$current_commit" != "$remote_commit" ]; then
      if [ -n "$jar_path" ]; then
        if prompt_remote_skip_choice; then
          ui_msg "已跳过编译，使用旧产物：${jar_path}"
          return 0
        fi
        action_update || return 1
        remove_old_artifacts
        jar_path=""
        current_commit=$(get_current_commit || true)
      else
        if prompt_remote_update_choice; then
          action_update || return 1
          current_commit=$(get_current_commit || true)
        else
          ui_msg "继续使用本地旧代码编译。"
        fi
      fi
    fi
  fi
  if [ -n "$jar_path" ]; then
    local last_commit=""
    if [ -z "$current_commit" ]; then
      current_commit=$(get_current_commit || true)
    fi
    last_commit=$(get_last_build_commit || true)

    if [ -n "$current_commit" ] && [ -n "$last_commit" ] && [ "$current_commit" != "$last_commit" ]; then
      if prompt_update_choice; then
        ui_msg "已跳过编译，使用旧产物：${jar_path}"
        return 0
      fi
      ui_info "将删除旧产物并重新编译..."
      remove_old_artifacts
    elif [ -z "$current_commit" ] || [ -z "$last_commit" ]; then
      if prompt_build_choice; then
        ui_msg "已跳过编译，使用旧产物：${jar_path}"
        return 0
      fi
      ui_info "将删除旧产物并重新编译..."
      remove_old_artifacts
    fi
  fi

  run_cmd_in_dir "开始编译..." "$APP_DIR" "$mvn_cmd" -DskipTests package || return 1
  if jar_path=$(find_jar); then
    save_last_build_commit "$(get_current_commit || true)"
    ui_msg "编译完成。\n产物：${jar_path}"
  else
    ui_msg "未找到编译产物，请检查构建日志。"
    return 1
  fi
}

action_start() {
  ensure_repo || return 1
  install_java || return 1

  # 检测 API 端口是否已被占用
  if port_in_use 9999; then
    ui_msg "API 服务已在运行中（端口 9999 已被占用）。"
    return 0
  fi

  if ! jar_path=$(find_jar); then
    if ui_yesno "未找到编译产物，是否先编译？"; then
      action_build || return 1
      jar_path=$(find_jar) || return 1
    else
      return 0
    fi
  fi
  local java_opts=""
  if java_opts=$(get_java_opts); then
    if [ -n "$java_opts" ]; then
      ui_info "使用 JVM 参数: ${java_opts}"
    fi
  fi
  ui_info "启动程序..."
  if [ -n "$java_opts" ]; then
    java $java_opts -jar "${jar_path}"
  else
    java -jar "${jar_path}"
  fi
}

action_view_logs() {
  if [ ! -f "$LOG_FILE" ]; then
    ui_msg "暂无日志文件：${LOG_FILE}"
    return 0
  fi
  echo "日志路径：${LOG_FILE}"
  tail -n 200 "$LOG_FILE"
}

action_clean() {
  if [ -d "${APP_DIR}" ]; then
    if mvn_cmd=$(get_mvn_cmd); then
      run_cmd_in_dir "清理构建..." "$APP_DIR" "$mvn_cmd" -q clean || true
    else
      rm -rf "${APP_DIR}/target"
    fi
    ui_msg "清理完成。"
  else
    ui_msg "项目目录不存在：${APP_DIR}"
  fi
}

action_reinstall() {
  if ! ui_yesno "将删除现有项目目录并重新安装，是否继续？"; then
    return 0
  fi
  rm -rf "${APP_DIR}"
  action_install || return 1
}

action_env_info() {
  local info
  info="项目目录：${APP_DIR}\n"
  if need_cmd java; then
    info+="Java：$(java -version 2>&1 | head -n 1)\n"
  else
    info+="Java：未安装\n"
  fi
  if need_cmd mvn; then
    info+="Maven：$(mvn -v 2>&1 | head -n 1)\n"
  else
    info+="Maven：未安装或使用 mvnw\n"
  fi
  if need_cmd git; then
    info+="Git：$(git --version 2>&1)\n"
  else
    info+="Git：未安装\n"
  fi
  ui_msg "$info"
}

action_uninstall_all() {
  if ! ui_yesno "将删除项目目录、日志和脚本本身，是否继续？"; then
    return 0
  fi
  rm -rf "${APP_DIR}"
  rm -f "$LOG_FILE"
  rm -f "$DISCLAIMER_FILE"
  rm -f "$SCRIPT_PATH"
  ui_msg "卸载完成。"
  goodbye
  exit 0
}

action_install_only_deps() {
  install_common_packages || return 1
  install_java || return 1
  ui_msg "依赖安装完成。"
}

action_download_only() {
  ensure_repo || return 1
  ui_msg "源码已准备完成。"
}

action_change_dir() {
  local new_dir
  new_dir=$(ui_input "请输入新的项目目录（可相对脚本目录）" "${APP_DIR_INPUT}") || return 0
  if [ -n "$new_dir" ]; then
    APP_DIR_INPUT="$new_dir"
    APP_DIR="$(resolve_app_dir "$APP_DIR_INPUT")"
    ui_msg "已切换到：${APP_DIR}"
  fi
}

action_clean_maven_cache() {
  local cache_dir="$HOME/.m2/repository"
  if [ ! -d "$cache_dir" ]; then
    ui_msg "未找到 Maven 缓存目录：${cache_dir}"
    return 0
  fi
  if ! ui_yesno "将删除 Maven 缓存目录：${cache_dir}，是否继续？"; then
    return 0
  fi
  rm -rf "$cache_dir"
  ui_msg "Maven 缓存已清理。"
}

action_restart_api() {
  ui_info "尝试结束旧的 API 进程(9999)..."
  if ! kill_port 9999; then
    ui_msg "未发现占用 9999 端口的进程。"
  fi
  action_start
}

action_restart_web_ui() {
  ui_info "尝试结束旧的 Web 控制台进程(8080)..."
  if ! kill_port 8080; then
    ui_msg "未发现占用 8080 端口的进程。"
  fi
  action_web_ui
}

write_web_files() {
cat << 'EOF_SERVER_PY' > "${BASE_DIR}/server.py"
import http.server
import socketserver
import subprocess
import json
import os
import urllib.parse
import urllib.request
import urllib.error
import threading
import time

PORT = 8080
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SCRIPT_PATH = os.environ.get("UNIDBG_SCRIPT_PATH", os.path.join(BASE_DIR, "unidbg_onekey.sh"))
LOG_FILE = os.environ.get("UNIDBG_LOG_FILE", os.path.join(BASE_DIR, "unidbg_onekey.log"))
APP_DIR = os.environ.get("UNIDBG_APP_DIR", os.path.join(BASE_DIR, "unidbg"))
API_TARGET = os.environ.get("UNIDBG_API_TARGET", "http://127.0.0.1:9999").rstrip("/")

SCRIPT_PATH = os.path.abspath(SCRIPT_PATH)
LOG_FILE = os.path.abspath(LOG_FILE)
APP_DIR = os.path.abspath(APP_DIR)

def json_response(handler, payload, status=200):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header('Content-type', 'application/json; charset=utf-8')
    handler.send_header('Content-Length', str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)

def proxy_request(handler, method):
    target_url = f"{API_TARGET}{handler.path}"
    data = None
    if method in ("POST", "PUT", "PATCH"):
        length = int(handler.headers.get('Content-Length', 0))
        if length > 0:
            data = handler.rfile.read(length)
    req = urllib.request.Request(target_url, data=data, method=method)
    for key, value in handler.headers.items():
        lower_key = key.lower()
        if lower_key in ("host", "connection", "content-length", "accept-encoding"):
            continue
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read()
            handler.send_response(resp.status)
            for key, value in resp.headers.items():
                lower_key = key.lower()
                if lower_key in ("transfer-encoding", "content-encoding", "connection"):
                    continue
                handler.send_header(key, value)
            handler.end_headers()
            handler.wfile.write(body)
    except Exception as e:
        json_response(handler, {"error": f"proxy_error: {e}"}, status=502)

class RequestHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path
        
        if path == "/":
            self.path = "/index.html"
            return http.server.SimpleHTTPRequestHandler.do_GET(self)
            
        if path == "/api/logs":
            content = ""
            if os.path.exists(LOG_FILE):
                try:
                    with open(LOG_FILE, 'r', encoding='utf-8', errors='replace') as f:
                        content = f.read()
                except Exception as e:
                    content = f"Error reading log: {str(e)}"
            json_response(self, {"logs": content})
            return

        if path == "/api/files":
            files = []
            target_dirs = [BASE_DIR, APP_DIR, os.path.join(APP_DIR, "target")]
            
            for d in target_dirs:
                if os.path.exists(d):
                    for f in os.listdir(d):
                        if f.endswith(('.txt', '.epub', '.jar', '.log')):
                            full_path = os.path.join(d, f)
                            if os.path.isfile(full_path):
                                try:
                                    common = os.path.commonpath([BASE_DIR, os.path.abspath(full_path)])
                                except ValueError:
                                    continue
                                if common != BASE_DIR:
                                    continue
                                rel_path = os.path.relpath(full_path, BASE_DIR).replace(os.sep, "/")
                                files.append({
                                    "name": f,
                                    "path": rel_path,
                                    "size": os.path.getsize(full_path)
                                })
            json_response(self, {"files": files})
            return

        if path.startswith("/api/"):
            proxy_request(self, "GET")
            return

        return http.server.SimpleHTTPRequestHandler.do_GET(self)

    def do_POST(self):
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path
        
        if path == "/api/run":
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data.decode('utf-8'))
            except Exception:
                json_response(self, {"error": "Invalid JSON"}, status=400)
                return
            action = data.get('action')
            
            if not action:
                json_response(self, {"error": "Missing action"}, status=400)
                return

            def run_script():
                cmd = ["bash", SCRIPT_PATH, "--action", action, "--dir", APP_DIR]
                try:
                    with open(LOG_FILE, "a", encoding="utf-8") as log:
                        log.write(f"\n\n--- Executing: {action} at {time.ctime()} ---\n")
                        subprocess.run(cmd, stdout=log, stderr=log, cwd=BASE_DIR)
                        log.write(f"\n--- Finished: {action} ---\n")
                except Exception as e:
                    with open(LOG_FILE, "a", encoding="utf-8") as log:
                        log.write(f"\nError executing {action}: {e}\n")

            threading.Thread(target=run_script, daemon=True).start()
            json_response(self, {"status": "started", "action": action})
            return

        if path.startswith("/api/"):
            proxy_request(self, "POST")
            return

        json_response(self, {"error": "Not found"}, status=404)

class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

os.chdir(BASE_DIR)

print(f"Server starting on port {PORT}...")
print(f"Open http://localhost:{PORT} in your browser")

with ReusableTCPServer(("0.0.0.0", PORT), RequestHandler) as httpd:
    httpd.serve_forever()
EOF_SERVER_PY

cat << 'EOF_INDEX_HTML' > "${BASE_DIR}/index.html"
<!DOCTYPE html>
<html lang="zh-CN" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>番茄小说下载器</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    <link href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;600;700&display=swap" rel="stylesheet">
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Noto Sans SC', 'sans-serif'] },
                    colors: { 
                        primary: '#F97316', 
                        dark: { 950: '#0a0a0a', 900: '#0F172A', 800: '#1E293B', 700: '#334155' }
                    }
                }
            }
        }
    </script>
    <style>
        ::-webkit-scrollbar { width: 6px; height: 6px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: #475569; border-radius: 3px; }
        .glass { background: rgba(30, 41, 59, 0.8); backdrop-filter: blur(12px); border: 1px solid rgba(255,255,255,0.08); }
        .fade-in { animation: fadeIn 0.3s ease-out; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
        .loading { display: inline-block; width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.3); border-radius: 50%; border-top-color: #F97316; animation: spin 0.8s linear infinite; }
        @keyframes spin { to { transform: rotate(360deg); } }
        input:focus { outline: none; }
        .tab-active { border-color: #F97316; color: #F97316; }
        .chapter-item:hover { background: rgba(249, 115, 22, 0.1); }
        .chapter-selected { background: rgba(249, 115, 22, 0.2) !important; border-left: 3px solid #F97316; }</style>
</head>
<body class="bg-dark-950 text-slate-200 font-sans min-h-screen">
    <!-- Navigation -->
    <nav class="fixed top-0 left-0 right-0 z-50 glass border-b border-white/5">
        <div class="max-w-4xl mx-auto px-4 py-3 flex items-center justify-between">
            <div class="flex items-center space-x-2">
                <div class="w-8 h-8 bg-gradient-to-br from-primary to-red-600 rounded-lg flex items-center justify-center">
                    <i class="fas fa-book text-white text-sm"></i>
                </div>
                <span class="font-bold text-white">番茄小说</span>
            </div>
            <div class="flex items-center space-x-3">
                <button onclick="showTab('search')" class="text-slate-400 hover:text-white p-2"><i class="fas fa-search"></i></button>
                <button onclick="showTab('manage')" class="text-slate-400 hover:text-white p-2"><i class="fas fa-cog"></i></button>
            </div>
        </div>
    </nav>
    <main class="pt-16 pb-20 max-w-4xl mx-auto">
        <!-- Search Tab -->
        <div id="tab-search" class="p-4 space-y-4">
            <!-- Search Box -->
            <div class="glass rounded-2xl p-4">
                <div class="flex items-center space-x-3">
                    <div class="flex-1 relative">
                        <input type="text" id="search-input" placeholder="搜索小说名称或作者..." class="w-full bg-dark-900 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-slate-500 focus:border-primary/50 transition-colors">
                        <button onclick="clearSearch()" class="absolute right-3 top-1/2 -translate-y-1/2 text-slate-500 hover:text-white hidden" id="clear-btn">
                            <i class="fas fa-times"></i>
                        </button>
                    </div>
                    <button onclick="searchBooks()" id="search-btn" class="bg-primary hover:bg-primary/80 text-white px-5 py-3 rounded-xl font-medium transition-colors flex items-center space-x-2">
                        <i class="fas fa-search"></i>
                        <span class="hidden sm:inline">搜索</span>
                    </button>
                </div>
            </div>

            <!-- Search Results -->
            <div id="search-results" class="space-y-3"></div>

            <!-- Book Detail Modal -->
            <div id="book-detail" class="hidden">
                <div class="glass rounded-2xl overflow-hidden fade-in">
                    <!-- Book Header -->
                    <div id="book-header" class="p-4 border-b border-white/5"></div>
                    <!-- Chapters -->
                    <div class="p-4">
                        <div class="flex items-center justify-between mb-3">
                            <h3 class="font-semibold text-white">章节列表</h3>
                            <div class="flex items-center space-x-2">
                                <button onclick="selectAllChapters()" class="text-xs bg-dark-700 px-3 py-1.5 rounded-lg hover:bg-dark-600 transition-colors">全选</button>
                                <button onclick="deselectAllChapters()" class="text-xs bg-dark-700 px-3 py-1.5 rounded-lg hover:bg-dark-600 transition-colors">取消</button>
                            </div>
                        </div>
                        <div id="chapter-list" class="max-h-[50vh] overflow-y-auto space-y-1 rounded-xl bg-dark-900/50 p-2"></div>
                        <div class="mt-4 flex items-center justify-between">
                            <span id="selected-count" class="text-sm text-slate-400">已选择 0 章</span>
                            <button onclick="downloadSelected()" id="download-btn" class="bg-primary hover:bg-primary/80 disabled:opacity-50 disabled:cursor-not-allowed text-white px-6 py-2.5 rounded-xl font-medium transition-colors flex items-center space-x-2">
                                <i class="fas fa-download"></i>
                                <span>下载选中</span>
                            </button>
                        </div>
                    </div>
                </div>
                <button onclick="hideBookDetail()" class="mt-4 w-full glass rounded-xl py-3 text-slate-400 hover:text-white transition-colors">
                    <i class="fas fa-arrow-left mr-2"></i>返回搜索
                </button>
            </div>
        </div>

        <!-- Manage Tab -->
        <div id="tab-manage" class="p-4 space-y-4 hidden">
            <div class="glass rounded-2xl p-4 space-y-4">
                <h2 class="font-semibold text-white text-lg">系统管理</h2>
                <div class="grid grid-cols-2 gap-3">
                    <button onclick="runAction('install')" class="bg-dark-800 hover:bg-dark-700 p-4 rounded-xl text-left transition-colors group">
                        <div class="w-10 h-10 rounded-full bg-blue-500/20 flex items-center justify-center text-blue-400 mb-2 group-hover:scale-110 transition-transform">
                            <i class="fas fa-download"></i>
                        </div>
                        <div class="font-medium text-white">安装环境</div>
                        <div class="text-xs text-slate-500 mt-1">安装依赖和Java</div>
                    </button>
                    <button onclick="runAction('update')" class="bg-dark-800 hover:bg-dark-700 p-4 rounded-xl text-left transition-colors group">
                        <div class="w-10 h-10 rounded-full bg-emerald-500/20 flex items-center justify-center text-emerald-400 mb-2 group-hover:scale-110 transition-transform">
                            <i class="fas fa-sync"></i>
                        </div>
                        <div class="font-medium text-white">更新代码</div>
                        <div class="text-xs text-slate-500 mt-1">Git Pull最新版</div>
                    </button>
                    <button onclick="runAction('build')" class="bg-dark-800 hover:bg-dark-700 p-4 rounded-xl text-left transition-colors group">
                        <div class="w-10 h-10 rounded-full bg-amber-500/20 flex items-center justify-center text-amber-400 mb-2 group-hover:scale-110 transition-transform">
                            <i class="fas fa-hammer"></i>
                        </div>
                        <div class="font-medium text-white">编译项目</div>
                        <div class="text-xs text-slate-500 mt-1">Maven构建</div>
                    </button>
                    <button onclick="runAction('start')" class="bg-dark-800 hover:bg-dark-700 p-4 rounded-xl text-left transition-colors group border border-primary/30">
                        <div class="w-10 h-10 rounded-full bg-primary/20 flex items-center justify-center text-primary mb-2 group-hover:scale-110 transition-transform">
                            <i class="fas fa-play"></i>
                        </div>
                        <div class="font-medium text-white">启动服务</div>
                        <div class="text-xs text-slate-500 mt-1">运行 API服务</div>
                    </button>
                </div>
            </div><!-- Logs -->
            <div class="glass rounded-2xl overflow-hidden">
                <div class="p-4 border-b border-white/5 flex justify-between items-center">
                    <h3 class="font-semibold text-white"><i class="fas fa-terminal mr-2 text-slate-500"></i>运行日志</h3>
                    <button onclick="refreshLogs()" class="text-slate-400 hover:text-white text-sm"><i class="fas fa-redo-alt"></i></button>
                </div>
                <div id="logs-container" class="h-64 overflow-auto p-4 font-mono text-xs text-slate-400 bg-dark-900/50 whitespace-pre-wrap">等待日志...</div>
            </div><!-- Files -->
            <div class="glass rounded-2xl overflow-hidden">
                <div class="p-4 border-b border-white/5 flex justify-between items-center">
                    <h3 class="font-semibold text-white"><i class="fas fa-folder mr-2 text-slate-500"></i>已下载文件</h3>
                    <button onclick="refreshFiles()" class="text-slate-400 hover:text-white text-sm"><i class="fas fa-sync-alt"></i></button>
                </div>
                <div id="files-list" class="max-h-64 overflow-auto p-2"></div>
            </div>
        </div>
    </main>

    <!-- Toast -->
    <div id="toast" class="fixed bottom-20 left-1/2 -translate-x-1/2 transform translate-y-20 opacity-0 transition-all duration-300 z-50 pointer-events-none">
        <div class="glass text-white px-5 py-3 rounded-full shadow-2xl flex items-center space-x-2 text-sm">
            <i id="toast-icon" class="fas fa-info-circle text-primary"></i>
            <span id="toast-msg">通知</span>
        </div>
    </div>

    <!-- Download Progress Modal -->
    <div id="download-modal" class="fixed inset-0 bg-black/70 z-50 hidden items-center justify-center p-4">
        <div class="glass rounded-2xl p-6 w-full max-w-sm text-center">
            <div class="loading mx-auto mb-4" style="width:40px;height:40px;border-width:3px"></div>
            <h3 class="text-white font-semibold mb-2">正在下载</h3>
            <p id="download-progress" class="text-slate-400 text-sm">准备中...</p>
            <div class="mt-4 h-2 bg-dark-700 rounded-full overflow-hidden">
                <div id="progress-bar" class="h-full bg-primary transition-all duration-300" style="width:0%"></div>
            </div>
        </div>
    </div>

    <script>
        const API_BASE = '';
        let currentBook = null;
        let chapters = [];
        let selectedChapters = new Set();

        // Tab Navigation
        function showTab(tab) {
            document.getElementById('tab-search').classList.toggle('hidden', tab !== 'search');
            document.getElementById('tab-manage').classList.toggle('hidden', tab !== 'manage');
            if (tab === 'manage') { refreshLogs(); refreshFiles(); }
        }

        // Search Functions
        document.getElementById('search-input').addEventListener('input', function() {
            document.getElementById('clear-btn').classList.toggle('hidden', !this.value);
        });
        document.getElementById('search-input').addEventListener('keypress', function(e) {
            if (e.key === 'Enter') searchBooks();
        });

        function clearSearch() {
            document.getElementById('search-input').value = '';
            document.getElementById('clear-btn').classList.add('hidden');document.getElementById('search-results').innerHTML = '';
        }

        function normalizeUrl(value) {
            if (!value) return '';
            if (Array.isArray(value)) {
                for (const v of value) {
                    const normalized = normalizeUrl(v);
                    if (normalized) return normalized;
                }
                return '';
            }
            if (typeof value === 'object') {
                return normalizeUrl(
                    value.url || value.uri || value.cover || value.coverUrl || value.cover_url ||
                    value.cover_uri || value.coverUri || value.thumb_uri || value.thumbUri ||
                    value.thumb_url || value.thumbUrl || value.thumb || value.image || value.img ||
                    value.path || value.link || value.href || value.url_list || value.urls || value.urlList
                );
            }
            let url = String(value).trim();
            if (!url) return '';
            if (url.startsWith('data:')) return url;
            if (url.startsWith('//')) return `https:${url}`;
            if (url.startsWith('http://') || url.startsWith('https://')) return url;
            if (!url.includes('.') && !url.includes('/')) return '';
            if (!url.includes('://') && !url.startsWith('/')) {
                return `https://p3-reading-sign.fqnovelpic.com/${url}`;
            }
            if (url.startsWith('/')) return `${location.origin}${url}`;
            return url;
        }

        function findFirstArray(payload, keyHints = []) {
            const queue = [payload];
            const seen = new Set();
            while (queue.length) {
                const item = queue.shift();
                if (Array.isArray(item)) return item;
                if (!item || typeof item !== 'object') continue;
                if (seen.has(item)) continue;
                seen.add(item);
                for (const [key, value] of Object.entries(item)) {
                    if (Array.isArray(value) && (keyHints.length === 0 || keyHints.includes(key))) {
                        return value;
                    }
                    if (value && typeof value === 'object') queue.push(value);
                }
            }
            return [];
        }

        function findFirstObject(payload, keyHints = []) {
            const queue = [payload];
            const seen = new Set();
            while (queue.length) {
                const item = queue.shift();
                if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
                if (seen.has(item)) continue;
                seen.add(item);
                const keys = Object.keys(item);
                if (keyHints.some(key => keys.includes(key))) return item;
                for (const value of Object.values(item)) {
                    if (value && typeof value === 'object') queue.push(value);
                }
            }
            return null;
        }

        function getBookId(book) {
            const id = book?.book_id || book?.bookId || book?.bookIdStr || book?.id || book?.bookID || book?.book_id_str;
            return id ? String(id) : '';
        }

        function getBookCover(book) {
            return normalizeUrl(
                book?.coverUrl || book?.detailPageThumbUrl || book?.expandThumbUrl || book?.horizThumbUrl ||
                book?.thumb_url || book?.thumbUrl || book?.thumb_uri || book?.thumbUri ||
                book?.cover || book?.cover_url || book?.cover_uri || book?.coverUri ||
                book?.pic || book?.image || book?.img || book?.coverImage || book?.cover_image
            );
        }

        function getBookTitle(book) {
            return book?.book_name || book?.title || book?.bookName || '未知书名';
        }

        function getBookAuthor(book) {
            return book?.author || book?.authorName || '未知作者';
        }

        function getBookAbstract(book) {
            return book?.abstract || book?.description || book?.desc || '';
        }

        function getBookStatus(book) {
            if (book?.status === 1 || book?.serial_status === 1 || book?.serialStatus === 1 || book?.isEnd === 0) return '连载中';
            if (book?.status === 0 || book?.serial_status === 0 || book?.serialStatus === 0 || book?.isEnd === 1) return '已完结';
            if (book?.creationStatus) return book.creationStatus;
            if (book?.updateStatus) return book.updateStatus;
            return '未知';
        }

        function getBookWordCount(book) {
            return book?.word_count || book?.wordCount || book?.wordNumber || book?.word_number || book?.words || 0;
        }

        function extractBookList(payload) {
            const candidates = [
                payload?.data?.data,
                payload?.data?.books,
                payload?.data?.book_list,
                payload?.data?.list,
                payload?.data?.items,
                payload?.data?.result,
                payload?.data?.results,
                payload?.data,
                payload?.books,
                payload?.list
            ];
            for (const c of candidates) {
                if (Array.isArray(c)) return c;
                if (c && Array.isArray(c.list)) return c.list;
                if (c && Array.isArray(c.items)) return c.items;
            }
            return findFirstArray(payload, ['data', 'books', 'book_list', 'list', 'items', 'result', 'results']);
        }

        function extractBookDetail(payload) {
            const candidates = [
                payload?.data?.book,
                payload?.data?.bookInfo,
                payload?.data?.info,
                payload?.data?.data,
                payload?.data,
                payload?.book
            ];
            for (const c of candidates) {
                if (c && typeof c === 'object') return c;
            }
            return findFirstObject(payload, ['book_id', 'bookId', 'bookName', 'book_name', 'title']);
        }

        function extractChapterList(payload) {
            const candidates = [
                payload?.data?.data,
                payload?.data?.chapters,
                payload?.data?.chapter_list,
                payload?.data?.chapterList,
                payload?.data?.item_data_list,
                payload?.data?.itemDataList,
                payload?.data?.list,
                payload?.data?.items,
                payload?.data?.result,
                payload?.data?.results,
                payload?.data,
                payload?.chapters,
                payload?.list
            ];
            for (const c of candidates) {
                if (Array.isArray(c)) return c;
                if (c && Array.isArray(c.list)) return c.list;
                if (c && Array.isArray(c.items)) return c.items;
            }
            return findFirstArray(payload, ['item_data_list', 'itemDataList', 'chapters', 'chapter_list', 'chapterList', 'list', 'items', 'data']);
        }

        function getChapterId(chap) {
            const id = chap?.item_id || chap?.itemId || chap?.chapterId || chap?.chapter_id || chap?.id || chap?.cid;
            return id ? String(id) : '';
        }

        function getChapterTitle(chap, idx) {
            return chap?.title || chap?.chapterTitle || chap?.chapter_title || chap?.name || `第${idx + 1}章`;
        }

        async function searchBooks() {
            const query = document.getElementById('search-input').value.trim();
            if (!query) { showToast('请输入搜索关键词', 'warning'); return; }
            
            const btn = document.getElementById('search-btn');
            btn.innerHTML = '<span class="loading"></span>';
            btn.disabled = true;

            try {
                const res = await fetch(`${API_BASE}/api/fqsearch/books?query=${encodeURIComponent(query)}&tabType=3&offset=0&count=20`);
                const data = await res.json();

                if (data.code === 0 || data.success === true) {
                    const books = extractBookList(data);
                    if (books.length > 0) {
                        renderSearchResults(books);
                    } else {
                        renderSearchResults([]);
                        console.warn('Search response payload:', data);
                    }
                } else {
                    showToast(data.message || data.msg || '搜索失败', 'error');
                }
            } catch (e) {
                showToast('连接 API 服务失败，请确保服务已启动', 'error');console.error(e);
            } finally {
                btn.innerHTML = '<i class="fas fa-search"></i><span class="hidden sm:inline">搜索</span>';
                btn.disabled = false;
            }
        }

        function renderSearchResults(books) {
            const container = document.getElementById('search-results');
            if (!books || books.length === 0) {
                container.innerHTML = '<div class="text-center text-slate-500 py-8">未找到相关书籍</div>';
                return;
            }

            container.innerHTML = books.map(book => `
                <div class="glass rounded-xl p-4 flex items-start space-x-4 cursor-pointer hover:border-primary/30 transition-colors fade-in" onclick="showBookDetail('${getBookId(book)}')">
                    <img src="${getBookCover(book)}" alt="" class="w-16 h-22 rounded-lg object-cover bg-dark-700 flex-shrink-0" onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 140%22><rect fill=%22%231E293B%22 width=%22100%22 height=%22140%22/><text x=%2250%22 y=%2270%22 text-anchor=%22middle%22 fill=%22%23475569%22 font-size=%2224%22>📖</text></svg>'">
                    <div class="flex-1 min-w-0">
                        <h3 class="font-semibold text-white truncate">${getBookTitle(book)}</h3>
                        <p class="text-sm text-slate-400 mt-1">${getBookAuthor(book)}</p>
                        <p class="text-xs text-slate-500 mt-2 line-clamp-2">${getBookAbstract(book)}</p>
                        <div class="flex items-center space-x-3 mt-2 text-xs text-slate-500">
                            <span><i class="fas fa-book-open mr-1"></i>${getBookStatus(book)}</span>
                            <span><i class="fas fa-file-alt mr-1"></i>${getBookWordCount(book) ? Math.floor(getBookWordCount(book)/10000) + '万字' : ''}</span>
                        </div>
                    </div>
                    <i class="fas fa-chevron-right text-slate-600 mt-6"></i>
                </div>
            `).join('');
        }

        async function showBookDetail(bookId) {
            if (!bookId) {
                showToast('无法获取书籍ID', 'error');
                return;
            }
            document.getElementById('search-results').classList.add('hidden');
            document.getElementById('book-detail').classList.remove('hidden');
            document.getElementById('book-header').innerHTML = '<div class="flex justify-center py-8"><span class="loading"></span></div>';
            document.getElementById('chapter-list').innerHTML = '<div class="text-center text-slate-500 py-4">加载中...</div>';
            
            selectedChapters.clear();
            updateSelectedCount();

            try {
                // Get book info
                const bookRes = await fetch(`${API_BASE}/api/fqnovel/book/${encodeURIComponent(bookId)}`);
                const bookData = await bookRes.json();
                const bookInfo = extractBookDetail(bookData);
                
                if ((bookData.code === 0 || bookData.success === true) && bookInfo) {
                    currentBook = bookInfo;
                    renderBookHeader(currentBook);
                } else if (bookInfo) {
                    currentBook = bookInfo;
                    renderBookHeader(currentBook);
                } else {
                    showToast(bookData.message || bookData.msg || '获取书籍信息失败', 'error');
                }

                // Get chapters
                const chapRes = await fetch(`${API_BASE}/api/fqsearch/directory/${encodeURIComponent(bookId)}`);
                const chapData = await chapRes.json();
                const chapterList = extractChapterList(chapData);
                
                if ((chapData.code === 0 || chapData.success === true) && chapterList.length > 0) {
                    chapters = chapterList;
                    renderChapters(chapters);
                } else if (chapterList.length > 0) {
                    chapters = chapterList;
                    renderChapters(chapters);
                } else {
                    document.getElementById('chapter-list').innerHTML = '<div class="text-center text-red-400 py-4">获取章节失败</div>';
                }
            } catch (e) {
                showToast('加载失败', 'error');
                console.error(e);
            }
        }

        function renderBookHeader(book) {
            document.getElementById('book-header').innerHTML = `
                <div class="flex items-start space-x-4">
                    <img src="${getBookCover(book)}" alt="" class="w-20 h-28 rounded-lg object-cover bg-dark-700" onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 140%22><rect fill=%22%231E293B%22 width=%22100%22 height=%22140%22/></svg>'">
                    <div class="flex-1 min-w-0">
                        <h2 class="text-xl font-bold text-white">${getBookTitle(book)}</h2>
                        <p class="text-slate-400 mt-1">${getBookAuthor(book)}</p>
                        <div class="flex flex-wrap gap-2 mt-3">
                            <span class="text-xs bg-primary/20 text-primary px-2 py-1 rounded">${book.category || book.categoryName || '小说'}</span>
                            <span class="text-xs bg-dark-700 text-slate-300 px-2 py-1 rounded">${getBookWordCount(book) ? Math.floor(getBookWordCount(book)/10000) + '万字' : ''}</span>
                        </div>
                    </div>
                </div>
            `;
        }

        function renderChapters(chaps) {
            if (!chaps || chaps.length === 0) {
                document.getElementById('chapter-list').innerHTML = '<div class="text-center text-slate-500 py-4">暂无章节</div>';
                return;
            }
            document.getElementById('chapter-list').innerHTML = chaps.map((chap, idx) => `
                <div class="chapter-item flex items-center p-3 rounded-lg cursor-pointer transition-colors ${selectedChapters.has(getChapterId(chap)) ? 'chapter-selected' : ''}" 
                     onclick="toggleChapter('${getChapterId(chap)}', ${idx})" data-id="${getChapterId(chap)}">
                    <span class="text-slate-500 text-xs w-12">${idx + 1}</span>
                    <span class="flex-1 text-sm text-slate-300 truncate">${getChapterTitle(chap, idx)}</span>
                    <i class="fas fa-check text-primary text-xs ${selectedChapters.has(getChapterId(chap)) ? '' : 'hidden'}"></i>
                </div>
            `).join('');
        }

        function toggleChapter(chapterId, idx) {
            if (!chapterId) {
                showToast('无法获取章节ID', 'error');
                return;
            }
            if (selectedChapters.has(chapterId)) {
                selectedChapters.delete(chapterId);} else {
                selectedChapters.add(chapterId);
            }
            const el = document.querySelector(`[data-id="${chapterId}"]`);
            if (el) {
                el.classList.toggle('chapter-selected');el.querySelector('.fa-check').classList.toggle('hidden');
            }
            updateSelectedCount();
        }

        function selectAllChapters() {
            chapters.forEach(c => {
                const chapId = getChapterId(c);
                if (chapId) selectedChapters.add(chapId);
            });
            renderChapters(chapters);
            updateSelectedCount();
        }

        function deselectAllChapters() {
            selectedChapters.clear();
            renderChapters(chapters);
            updateSelectedCount();
        }

        function updateSelectedCount() {
            document.getElementById('selected-count').textContent = `已选择 ${selectedChapters.size} 章`;
            document.getElementById('download-btn').disabled = selectedChapters.size === 0;
        }

        function hideBookDetail() {
            document.getElementById('book-detail').classList.add('hidden');
            document.getElementById('search-results').classList.remove('hidden');
            currentBook = null;
            chapters = [];
            selectedChapters.clear();
        }

        async function downloadSelected() {
            if (!currentBook || selectedChapters.size === 0) return;
            
            const modal = document.getElementById('download-modal');
            modal.classList.remove('hidden');
            modal.classList.add('flex');
            
            const progressText = document.getElementById('download-progress');
            const progressBar = document.getElementById('progress-bar');
            const currentBookId = getBookId(currentBook);
            if (!currentBookId) {
                showToast('无法获取书籍ID', 'error');
                modal.classList.add('hidden');
                modal.classList.remove('flex');
                return;
            }
            
            let content = `《${getBookTitle(currentBook)}》\n作者：${getBookAuthor(currentBook) || '未知'}\n\n`;
            const selectedIds = Array.from(selectedChapters);
            let completed = 0;

            try {
                let chaptersMap = null;
                if (selectedIds.length > 1) {
                    const chunkSize = 30;
                    chaptersMap = {};
                    try {
                        for (let i = 0; i < selectedIds.length; i += chunkSize) {
                            const chunk = selectedIds.slice(i, i + chunkSize);
                            progressText.textContent = `批量获取中 ${Math.min(i + chunk.length, selectedIds.length)}/${selectedIds.length}`;
                            progressBar.style.width = `${Math.min(10 + ((i + chunk.length) / selectedIds.length) * 40, 50)}%`;

                            let res = await fetch(`${API_BASE}/api/fqnovel/chapters/batch`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ bookId: currentBookId, chapterIds: chunk })
                            });
                            if (!res.ok) {
                                res = await fetch(`${API_BASE}/api/fqnovel/chapter/batch`, {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({ bookId: currentBookId, chapterIds: chunk })
                                });
                            }
                            const data = await res.json();
                            const chunkMap = data?.data?.chapters || data?.chapters;
                            if (!chunkMap || typeof chunkMap !== 'object') {
                                throw new Error(data?.message || data?.msg || '批量接口返回为空');
                            }
                            Object.assign(chaptersMap, chunkMap);
                        }
                    } catch (e) {
                        console.warn('Batch download failed, fallback to single', e);
                        showToast('批量获取失败，已切换为单章下载', 'warning');
                        chaptersMap = null;
                    }
                }

                if (chaptersMap) {
                    for (const chapterId of selectedIds) {
                        const chap = chapters.find(c => getChapterId(c) === chapterId);
                        const info = chaptersMap[chapterId];
                        const title = info?.chapterName || (chap ? getChapterTitle(chap, completed) : `第${completed+1}章`);
                        const text = info?.txtContent || info?.rawContent || info?.content || info?.originalContent || '';
                        if (text) {
                            content += `\n${title}\n\n${text}\n\n`;
                        }
                        completed++;
                        progressText.textContent = `整理中 ${completed}/${selectedIds.length}`;
                        progressBar.style.width = `${50 + (completed / selectedIds.length) * 50}%`;
                    }
                } else {
                    for (const chapterId of selectedIds) {
                        const chap = chapters.find(c => getChapterId(c) === chapterId);
                        progressText.textContent = `下载中 ${completed + 1}/${selectedIds.length}`;
                        progressBar.style.width = `${(completed / selectedIds.length) * 100}%`;

                        const res = await fetch(`${API_BASE}/api/fqnovel/chapter/${encodeURIComponent(currentBookId)}/${encodeURIComponent(chapterId)}`);
                        const data = await res.json();
                        
                        if (data.code === 0 && data.data) {
                            const title = chap ? getChapterTitle(chap, completed) : (data.data.chapterTitle || `第${completed+1}章`);
                            const text = data.data.originalContent || data.data.content || data.data.txtContent || '';
                            content += `\n${title}\n\n${text}\n\n`;
                        }
                        completed++;
                        
                        // Small delay to avoid rate limiting
                        await new Promise(r => setTimeout(r, 100));
                    }
                }

                progressBar.style.width = '100%';
                progressText.textContent = '下载完成，准备保存...';

                // Create and download file
                const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `${currentBook.bookName || currentBook.title || 'novel'}.txt`;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);

                showToast('下载完成！', 'success');
            } catch (e) {
                showToast('下载失败: ' + e.message, 'error');console.error(e);
            } finally {
                modal.classList.add('hidden');
                modal.classList.remove('flex');
                progressBar.style.width = '0%';
            }
        }

        // Management Functions
        async function runAction(action) {
            showToast(`执行: ${action}...`);
            try {
                const res = await fetch('/api/run', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ action })
                });
                if (res.ok) {
                    showToast(`${action} 已启动`, 'success');
                setTimeout(refreshLogs, 1000);
                }
            } catch (e) {
                showToast('执行失败', 'error');
            }
        }

        async function refreshLogs() {
            try {
                const res = await fetch('/api/logs');
                const data = await res.json();
                const container = document.getElementById('logs-container');
                container.textContent = data.logs || '暂无日志';container.scrollTop = container.scrollHeight;
            } catch (e) {}
        }

        async function refreshFiles() {
            try {
                const res = await fetch('/api/files');
                const data = await res.json();
                const list = document.getElementById('files-list');
                if (!data.files || data.files.length === 0) {
                    list.innerHTML = '<div class="text-center text-slate-500 py-4 text-sm">暂无文件</div>';
                    return;
                }
                list.innerHTML = data.files.map(f => `
                    <div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg group">
                        <div class="flex items-center min-w-0">
                            <i class="fas ${f.name.endsWith('.txt') ? 'fa-file-lines text-blue-400' : 'fa-file text-slate-500'} mr-3"></i>
                            <span class="text-sm text-slate-300 truncate">${f.name}</span>
                        </div><a href="${f.path}" download class="text-primary hover:text-primary/80 p-2"><i class="fas fa-download"></i></a>
                    </div>
                `).join('');
            } catch (e) {}
        }

        function showToast(msg, type = 'info') {
            const toast = document.getElementById('toast');
            const icon = document.getElementById('toast-icon');
            document.getElementById('toast-msg').textContent = msg;
            
            icon.className = 'fas ' + ({
                'success': 'fa-check-circle text-emerald-400',
                'error': 'fa-exclamation-circle text-red-400',
                'warning': 'fa-exclamation-triangle text-amber-400',
                'info': 'fa-info-circle text-primary'
            }[type] || 'fa-info-circle text-primary');
            
            toast.classList.remove('translate-y-20', 'opacity-0');
            setTimeout(() => toast.classList.add('translate-y-20', 'opacity-0'), 3000);
        }

        // Init
        setInterval(refreshLogs, 5000);
    </script>
</body>
</html>
EOF_INDEX_HTML
}

action_web_ui() {
  if ! need_cmd python3; then
    ui_msg "未检测到 python3，请先安装 python3。"
    return 1
  fi
  write_web_files
  ui_msg "Web 控制台已启动。\n\n请在浏览器访问：http://localhost:8080\n\nAPI 代理目标：${UNIDBG_API_TARGET:-http://127.0.0.1:9999}\n\n按 Ctrl+C 停止服务。"
  UNIDBG_APP_DIR="${APP_DIR}" \
  UNIDBG_LOG_FILE="${LOG_FILE}" \
  UNIDBG_SCRIPT_PATH="${SCRIPT_PATH}" \
  python3 "${BASE_DIR}/server.py"
}

goodbye() {
  ui_msg "谢谢你的使用，再见"
}

main_loop() {
  while true; do
    choice=$(menu_main)
    case "$choice" in
      1) action_install ;;
      2) action_update ;;
      3) action_build ;;
      4) action_start ;;
      5) action_view_logs ;;
      6) action_clean ;;
      7) action_reinstall ;;
      8) action_env_info ;;
      w|W) action_web_ui ;;
      9) goodbye; exit 0 ;;
      0) uncommon_loop ;;
      *) ui_msg "无效选项，请重新选择。" ;;
    esac
  done
}

uncommon_loop() {
  while true; do
    choice=$(menu_uncommon)
    case "$choice" in
      1) action_uninstall_all ;;
      2) action_install_only_deps ;;
      3) action_download_only ;;
      4) action_change_dir ;;
      5) action_clean_maven_cache ;;
      6) action_restart_api ;;
      7) action_restart_web_ui ;;
      8) show_changelog ;;
      9) return 0 ;;
      *) ui_msg "无效选项，请重新选择。" ;;
    esac
  done
}

if [ -n "$ACTION" ]; then
  # 确保日志文件存在
  touch "$LOG_FILE"
  
  case "$ACTION" in
    install) action_install ;;
    update) action_update ;;
    build) action_build ;;
    start) action_start ;;
    logs) action_view_logs ;;
    clean) action_clean ;;
    reinstall) action_reinstall ;;
    env) action_env_info ;;
    *) echo "Unknown action: $ACTION"; exit 1 ;;
  esac
  exit 0
fi

if [ -z "$ACTION" ]; then
  if [ ! -f "$DISCLAIMER_FILE" ]; then
    show_disclaimer
    if ! confirm_disclaimer; then
      ui_msg "已取消。"
      exit 1
    fi
    printf "accepted\n" > "$DISCLAIMER_FILE"
  fi

  check_update_notice
  if [ "${UPDATE_AVAILABLE:-0}" -eq 1 ]; then
    ui_msg "检测到 GitHub 仓库有更新，请先执行“更新代码”。编译时可选择是否重新编译。"
  fi
fi

main_loop
