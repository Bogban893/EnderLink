# Подключение: source /путь/к/EnderLink/cli/completion/cli.bash
# Регистрирует cli из PATH, пути от корня проекта и абсолютный путь сборки.
_enderlink_cli_complete() {
    local current="${COMP_WORDS[COMP_CWORD]}"
    local previous="${COMP_WORDS[COMP_CWORD-1]}"
    local command="${COMP_WORDS[1]}"
    local candidate option used i
    local -a options=()
    COMPREPLY=()

    if (( COMP_CWORD == 1 )); then
        while IFS= read -r candidate; do
            COMPREPLY+=("$candidate")
        done < <(compgen -W 'listen connect send receive --help -h' -- "$current")
        return 0
    fi

    # Значения параметров: имена с пробелами сохраняются одним вариантом.
    case "$previous" in
        --file|--directory|--output)
            compopt -o filenames 2>/dev/null || :
            if [[ "$previous" == --output || "$previous" == --directory ]]; then
                while IFS= read -r candidate; do
                    COMPREPLY+=("$candidate")
                done < <(compgen -d -- "$current")
            else
                while IFS= read -r candidate; do
                    COMPREPLY+=("$candidate")
                done < <(compgen -f -- "$current")
            fi
            return 0
            ;;
        --host|--port)
            # Адрес и порт вводятся пользователем, без выдуманных значений.
            return 0
            ;;
    esac

    case "$command" in
        listen|connect) options=(--host --port --help) ;;
        send) options=(--host --port --file --directory --help) ;;
        receive) options=(--host --port --output --help) ;;
        *) return 0 ;;
    esac

    for option in "${options[@]}"; do
        # Парсер принимает --help только сразу после команды.
        [[ "$option" == --help && "$COMP_CWORD" != 2 ]] && continue
        used=false
        for ((i = 2; i < COMP_CWORD; i += 2)); do
            if [[ "${COMP_WORDS[i]}" == "$option" ||
                ( "$option" == --directory && "${COMP_WORDS[i]}" == --file ) ||
                ( "$option" == --file && "${COMP_WORDS[i]}" == --directory ) ]]; then
                used=true
                break
            fi
        done
        if [[ "$used" == false && "$option" == "$current"* ]]; then
            COMPREPLY+=("$option")
        fi
    done
    return 0
}

_enderlink_cli_register() {
    local cli_directory
    cli_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)" || return
    complete -F _enderlink_cli_complete \
        cli \
        ./cli/build/install/cli/bin/cli \
        cli/build/install/cli/bin/cli \
        "$cli_directory/build/install/cli/bin/cli"
}

_enderlink_cli_register
unset -f _enderlink_cli_register
