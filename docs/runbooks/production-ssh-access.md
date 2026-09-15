---
doc_schema: 1
doc_type: runbook
status: draft
owner: security
audience:
  - developer
  - operator
last_verified: 2026-09-04
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
  - production-drill
operation_type: recovery
environments:
  - local
  - staging
  - production
risk_level: critical
source_of_truth:
  - docs/history/handoffs/2026/08/PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md
verification_evidence:
  - level: static
    scope: local key identities, connection procedure, lockout diagnosis and owner-key preservation controls
    verified_at: 2026-09-04
    evidence: local-runtime:ssh-key-fingerprint-check-2026-09-04
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - production-host-change
  - ssh-key-change
  - access-incident
  - sudo-policy-change
  - host-rebuild
supersedes: []
superseded_by: null
---

# SSH-доступ к production и восстановление owner key

## Цель и статус

Документ определяет один устойчивый способ входа на production-хост из WSL, разделяет ключи
production и GitHub, описывает безопасную передачу уже открытого соединения агенту и защищает
постоянный owner key при выдаче и отзыве временного доступа.

Обычный вход и read-only диагностика не меняют сервер. Восстановление `authorized_keys` и выдача
временного ключа являются security-операциями и требуют явного подтверждения владельца. Runbook
остаётся `draft`, пока процедура не прошла обязательные staging, production read-only и
production-drill gates. Этот текст не разрешает самостоятельную замену ключей или расширение
`sudo`.

## Канонические идентификаторы

| Назначение | Значение |
|---|---|
| Production-хост | `92.53.127.24` (`store-analytics-prod-app-01`) |
| SSH-пользователь | `pavel` |
| Рекомендуемый локальный alias | `store-analytics-prod` |
| Production private key в WSL | `~/.ssh/store-analytics-prod` |
| Production public key в WSL | `~/.ssh/store-analytics-prod.pub` |
| Production agent socket | `/tmp/codex-store-analytics-agent` |
| GitHub agent socket | `/tmp/codex-github-agent` |
| Постоянный owner-key fingerprint | `SHA256:mrMWLVw6PA65R5A3zlTyJ+o9R0QyVh2zvfxcPQ1NcGQ` |
| GitHub private key в WSL | `~/.ssh/id_ed25519` |
| GitHub-key fingerprint | `SHA256:tBAv9YeGnlD/fSH7mb7LQMPOpAjN9QNCZhrpGqUbZX8` |

Production key и GitHub key — разные ключи и не взаимозаменяемы. Комментарий в конце public-key
строки — только подпись; идентичность определяется fingerprint. Несколько строк с одинаковым
fingerprint являются дубликатами одного ключа, а не независимыми каналами доступа.

Локально наблюдавшийся в WSL `known_hosts` ED25519 fingerprint production-хоста на дату проверки:
`SHA256:n3+ZfZO0M2cheNHXlwjwH91jGQyIjQ5ofHOx1TED2H0`. Сохранённая запись не является независимым
подтверждением identity сервера. При несовпадении не удалять её автоматически: остановиться и
сверить host key через provider console с `/etc/ssh/ssh_host_ed25519_key.pub`. После пересоздания
VM значение обязано измениться и этот документ нужно пересмотреть.

## Инварианты доступа

- В стабильном состоянии `authorized_keys` содержит постоянный owner key ровно один раз.
- Передача существующего разблокированного agent socket не создаёт новый серверный ключ и
  ограничивается временем жизни socket и согласованной задачей.
- Если выдаётся отдельный временный SSH-ключ, он использует другую пару и другой fingerprint;
  пока существующие sockets и key files доступны, создавать новую пару запрещено.
- Временные `sudo`-правила живут в `/etc/sudoers.d/` и отзываются отдельно от SSH-ключей.
- Очистка временного доступа никогда не заменяет и не обнуляет весь `authorized_keys`.
- Ключ нельзя считать временным по словам `codex`, `release` или `ops` в комментарии.
- Перед очисткой и после неё проверяется наличие постоянного fingerprint.
- Текущая сессия или provider console не закрывается, пока второй независимый SSH-вход не прошёл.
- Private key, passphrase, пароль пользователя, токены и содержимое secret-файлов не передаются в
  чат, issue, commit, screenshot или документацию.

## Обычный вход из WSL

### 1. Локальный preflight

Выполнять в WSL под Linux-пользователем `pavel`, не в консоли production-сервера:

```bash
test -f ~/.ssh/store-analytics-prod
test -f ~/.ssh/store-analytics-prod.pub
chmod 700 ~/.ssh
chmod 600 ~/.ssh/store-analytics-prod
chmod 644 ~/.ssh/store-analytics-prod.pub
ssh-keygen -lf ~/.ssh/store-analytics-prod.pub
```

Последняя команда должна вывести постоянный owner-key fingerprint из таблицы выше. Другой
fingerprint означает неверный key file и является stop-условием.

### 2. Рекомендуемый SSH alias

Добавить в локальный `~/.ssh/config`, не в репозиторий:

```sshconfig
Host store-analytics-prod
    HostName 92.53.127.24
    User pavel
    IdentityFile ~/.ssh/store-analytics-prod
    IdentitiesOnly yes
    PreferredAuthentications publickey
```

Права файла:

```bash
chmod 600 ~/.ssh/config
```

Вход после настройки:

```bash
ssh store-analytics-prod
```

Эквивалент без alias:

```bash
ssh -o IdentitiesOnly=yes -i ~/.ssh/store-analytics-prod pavel@92.53.127.24
```

Запрос `Enter passphrase for .../store-analytics-prod` означает, что найден правильный
зашифрованный private key. Это не пароль production-пользователя и не `sudo`-пароль.

### 3. SSH-agent для текущей WSL-сессии

```bash
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/store-analytics-prod
ssh-add -l
```

В списке должен быть production fingerprint. `SSH_AUTH_SOCK` принадлежит конкретной shell-сессии:
новый терминал или sandbox агента может его не видеть. Ошибка `Error connecting to agent` означает
проблему с agent socket, а не потерю ключа. Сначала повторяют локальный preflight и запускают agent;
новую пару ключей не создают.

## Безопасный доступ для Codex или другого агента

Канонический путь — два заранее разблокированных SSH-agent socket. Агенту не передаются private
key, passphrase, пароль, PAT или secret-файлы. Перед production-доступом проверяется socket и
ожидаемый fingerprint:

```bash
SSH_AUTH_SOCK=/tmp/codex-store-analytics-agent ssh-add -l
SSH_AUTH_SOCK=/tmp/codex-store-analytics-agent \
ssh -i ~/.ssh/store-analytics-prod \
  -o IdentitiesOnly=yes \
  pavel@92.53.127.24
```

Для GitHub используется отдельный socket и отдельный key через SSH на порту 443:

```bash
SSH_AUTH_SOCK=/tmp/codex-github-agent ssh-add -l
SSH_AUTH_SOCK=/tmp/codex-github-agent \
GIT_SSH_COMMAND='ssh -p 443 -o HostName=ssh.github.com -o IdentitiesOnly=yes -i /home/pavel/.ssh/id_ed25519' \
git fetch origin --prune --tags
```

Пути под `/tmp` не являются долговечными: socket может исчезнуть после перезапуска WSL или
ssh-agent. Если socket отсутствует, не является Unix socket, недоступен sandbox или не содержит
ожидаемый fingerprint, агент останавливается. Владелец восстанавливает socket, разблокируя уже
существующий key file; агент не создаёт новую пару ключей и не удаляет socket вслепую.

Если sandbox отвечает `Operation not permitted`, но socket существует, это ещё не отказ сервера:
нужно повторить ту же read-only проверку с явно разрешённым доступом к socket. Если fingerprint
после этого не совпал, подключение запрещено.

Доступ по SSH не даёт агенту неограниченный `sudo`. Для production-write создаётся отдельный,
минимальный и ограниченный по времени exact-command allowlist. Его проверяют через
`visudo -cf /etc/sudoers` и удаляют после задачи, не затрагивая `authorized_keys`.

### Ограниченный read-only allowlist для помесячных сверок

Для повторяемых LiveSklad-сверок 14 сентября 2026 года по отдельному согласованию владельца
установлен долговечный allowlist. Это не общий `sudo` и не production-write доступ:

- `/etc/sudoers.d/codex-store-month-audits` принадлежит `root:root`, mode `0440`;
- разрешены ровно 16 команд без аргументов: два магазина × месяцы `2026-01..2026-08`;
- имена команд имеют вид `/usr/local/sbin/codex-store-audit-YYYY-MM-{magazin|mobisphere}`;
- каждая root-owned команда принимает ноль аргументов, содержит фиксированные store и даты,
  открывает `BEGIN READ ONLY`, включает PostgreSQL read-only/timeout guards и завершает `ROLLBACK`;
- произвольный SQL, другой период, другой магазин, shell и обычный `sudo` allowlist не разрешает;
- private key, passphrase, database password и содержимое secret-файлов не выводятся.

После установки проверено: найдено ровно 16 root-owned executable, sudoers owner/mode равен
`root:root:440`, exact July command выполнилась, а `sudo -n true` осталась запрещена. Загруженный
installer, manifest и пользовательские копии audit scripts после проверки удалены из production
`/tmp`; root-owned установленные команды сохранены.

Расширение списка месяцев или магазинов требует нового отдельного согласования, проверки
отсутствия DML/DDL, shell syntax check, SHA-256 manifest и `visudo -cf` до установки. Нельзя
заменять exact commands wildcard-командой с произвольными аргументами.

Для отзыва сначала получить согласование operations, проверить точный список файлов и сохранить
sanitized evidence. Затем удалить exact sudoers-файл, повторить `visudo -cf /etc/sudoers`,
убедиться, что audit command больше не разрешена, и только после этого удалить перечисленные
root-owned audit scripts. Другие sudoers entries и `authorized_keys` не затрагивать.

## Диагностика `Permission denied (publickey)`

Сначала выполнить локально:

```bash
ssh-keygen -lf ~/.ssh/store-analytics-prod.pub
SSH_AUTH_SOCK=/tmp/codex-store-analytics-agent ssh-add -l
SSH_AUTH_SOCK=/tmp/codex-store-analytics-agent \
ssh -vvv -o IdentitiesOnly=yes \
  -i ~/.ssh/store-analytics-prod pavel@92.53.127.24
```

Интерпретация:

| Наблюдение | Вероятная область проблемы | Действие |
|---|---|---|
| `No such file or directory` | Неверный Linux-пользователь или путь | Вернуться в WSL под `pavel`, проверить два key files |
| `Error connecting to agent` / socket отсутствует | Socket не существует или недоступен текущему процессу | Владелец восстанавливает socket существующего ключа; новый ключ не создавать |
| `Operation not permitted` при существующем socket | Sandbox запретил Unix socket | Повторить read-only проверку с разрешённым socket-доступом; серверные ключи не менять |
| Timeout до предложения ключа | Сеть, port 22, firewall или provider | Проверить сеть и provider console; ключи не менять |
| Правильный ключ предложен, затем `Permission denied` | Сервер не авторизует ключ либо неверны owner/mode | Проверить `authorized_keys` через уже открытую сессию или provider console |
| Host-key mismatch | Возможен rebuild или подмена target | Остановиться и сверить fingerprint вне SSH; не выполнять слепой `ssh-keygen -R` |

Если есть серверная или provider-console сессия, read-only проверка:

```bash
sudo stat -c '%U:%G %a %s %n' \
  /home/pavel /home/pavel/.ssh /home/pavel/.ssh/authorized_keys
sudo namei -l /home/pavel/.ssh/authorized_keys
sudo ssh-keygen -lf /home/pavel/.ssh/authorized_keys
sudo sshd -T | grep -E \
  '^(pubkeyauthentication|authorizedkeysfile|passwordauthentication|permitrootlogin) '
sudo journalctl -u ssh.service --since '-30 minutes' --no-pager
```

Ожидаемые owner/mode: `/home/pavel` — `pavel:pavel 750`, `.ssh` — `pavel:pavel 700`,
`authorized_keys` — `pavel:pavel 600`. Действующий сервер использует public-key authentication;
включать password login или root SSH как обход запрещено.

## Аварийное восстановление через provider console

### Требуемая авторизация и stop-условия

Восстановление выполняет владелец production из web/VNC console провайдера. На login prompt
вводится `pavel`, затем пароль Linux-пользователя; ввод пароля не отображается. Это не passphrase
SSH-ключа.

Остановиться, если не подтверждены exact VM, пользователь, локальный production fingerprint или
источник public key. Нельзя копировать ключ из старого чата, неизвестного файла или комментария.

### Процедура

1. В доверенном WSL проверить fingerprint и скопировать одну полную строку из
   `~/.ssh/store-analytics-prod.pub`. Public key не сохранять в документации.
2. В provider console сначала выполнить `stat`, `namei` и `ssh-keygen -lf` из предыдущего раздела.
3. Убедиться, что active path не является symlink, и восстановить безопасные owner/mode:

   ```bash
   sudo test ! -L /home/pavel/.ssh
   sudo test ! -L /home/pavel/.ssh/authorized_keys
   sudo install -d -m 700 -o pavel -g pavel /home/pavel/.ssh
   sudo touch /home/pavel/.ssh/authorized_keys
   sudo chown pavel:pavel /home/pavel/.ssh/authorized_keys
   sudo chmod 600 /home/pavel/.ssh/authorized_keys
   ```

4. Создать backup и candidate на том же filesystem. Команды выводят точные пути — сохранить их до
   завершения проверки:

   ```bash
   authorized_keys_backup="$(mktemp /home/pavel/.ssh/authorized_keys.backup.XXXXXX)"
   authorized_keys_candidate="$(mktemp /home/pavel/.ssh/authorized_keys.candidate.XXXXXX)"
   cp --preserve=mode,ownership \
     /home/pavel/.ssh/authorized_keys "$authorized_keys_backup"
   chmod 600 "$authorized_keys_backup" "$authorized_keys_candidate"
   printf 'backup=%s\ncandidate=%s\n' \
     "$authorized_keys_backup" "$authorized_keys_candidate"
   editor "$authorized_keys_candidate"
   ```

5. В candidate вставить только одну доверенную owner public-key строку. Если active-файл содержал
   иные подтверждённые ключи, остановиться: их перенос или отзыв требует отдельного exact scope.
6. До установки проверить candidate. Все команды обязаны завершиться успешно:

   ```bash
   test -s "$authorized_keys_candidate"
   test "$(wc -l < "$authorized_keys_candidate")" -eq 1
   ssh-keygen -lf "$authorized_keys_candidate"
   test "$(ssh-keygen -lf "$authorized_keys_candidate" | \
     grep -Fc 'SHA256:mrMWLVw6PA65R5A3zlTyJ+o9R0QyVh2zvfxcPQ1NcGQ')" -eq 1
   ```

7. Установить уже проверенный candidate атомарным rename на том же filesystem и повторить
   fingerprint/owner/mode проверки:

   ```bash
   mv -T "$authorized_keys_candidate" /home/pavel/.ssh/authorized_keys
   chmod 600 /home/pavel/.ssh/authorized_keys
   ssh-keygen -lf /home/pavel/.ssh/authorized_keys
   stat -c '%U:%G %a %s %n' /home/pavel/.ssh/authorized_keys
   ```

8. Не перезапуская `sshd`, открыть из WSL вторую SSH-сессию и проверить `id -un` и `hostname`.
9. Только после успешного второго входа закрыть provider console и удалить exact backup-файл.
   Зафиксировать sanitized incident evidence: время, host, fingerprint, до/после line count и
   результат второго входа.

При любом сбое после шага 7 оставить provider console открытой и вернуть backup атомарным rename,
затем повторить fingerprint и второй вход. Не использовать backup от другой попытки.

Редактирование `authorized_keys` применяется к новым SSH-сессиям сразу; restart `sshd` обычно не
нужен и добавляет ненужный риск.

## Выдача и отзыв временного SSH-ключа

1. Владелец утверждает цель, срок и отдельный temporary-key fingerprint.
2. Active-файл не редактируется. На том же filesystem создаются backup и candidate; active-файл
   копируется в оба, а точная temporary public-key строка добавляется только в candidate.
3. До атомарной установки candidate проверяются: файл не пуст, синтаксис читается `ssh-keygen`,
   постоянный fingerprint встречается ровно один раз, temporary fingerprint встречается ровно один
   раз и отличается от постоянного.
4. После атомарной установки проверяются owner/mode, вход временным ключом и второй вход постоянным
   ключом. Только затем удаляется exact backup.
5. При отзыве снова создаются новые backup и candidate. Из candidate удаляется только точная
   temporary public-key строка; массовый фильтр по комментарию (`codex`, `release`, `ops`) запрещён.
6. До установки candidate проверяются: постоянный fingerprint ровно один раз, temporary fingerprint
   отсутствует, файл не пуст и читается `ssh-keygen`. После установки обязательно проверяется второй
   вход постоянным ключом.
7. Только после успешной SSH-проверки удаляется соответствующий временный `sudoers` allowlist.

Если temporary fingerprint совпал с постоянным, это не отдельный ключ: добавление или удаление
останавливается. Дубликаты постоянного ключа не создают резервного доступа.

### Безопасная production-drill репетиция

Drill никогда не удаляет owner key и не симулирует lockout на единственном production-канале.
После staging rehearsal на production атомарно добавляется отдельный короткоживущий test key при
сохранённом owner key, проверяются оба входа, затем новым candidate атомарно удаляется только test
key и снова проверяется owner-вход. Provider console остаётся открытой до конца. Evidence обязано
содержать before/after fingerprints и подтверждать, что owner key присутствовал на каждом шаге.

До успешного staging rehearsal и такой production-drill проверки статус runbook остаётся `draft`.

## Повторный запуск, rollback и evidence

Обычный SSH-вход повторяем и не меняет сервер. Изменение `authorized_keys` не считается безопасно
идемпотентным без проверки fingerprints до и после. Rollback ошибочного редактирования — вернуть
предыдущее проверенное множество public keys через ещё открытую сессию/provider console, затем
снова проверить второй вход. Закрывать единственный канал доступа до проверки запрещено.

Для изменения доступа или расследования сохраняются только timestamps, exact host, username, key
fingerprints, owner/mode, line count, результат входа и sanitized journal events. Public-key строки
обычно не нужны; private keys, passphrases, пароли и IP клиента в evidence не включаются.

## Связанные процедуры

- [Production deployment](production-deployment.md) — release preflight и deploy после установки
  проверенного SSH-канала.
- [Incident response](incident-response.md) — координация security incident.
- [Host rebuild](host-rebuild.md) — обязательная смена и повторная проверка host-key fingerprint.
- [Application access и break-glass](access-and-break-glass.md) — отдельный контур пользователей
  самого приложения, не SSH.

## Ограничения проверки

На 2026-09-04 локально подтверждены fingerprints обоих public keys и fingerprint ключа,
загруженного в production agent socket. GitHub socket во время этой сверки отсутствовал, а сетевой
вход на production и GitHub не повторялся. Независимый security/operations review, staging
rehearsal, production read-only evidence и безопасная production-drill репетиция не зафиксированы.
Поэтому recovery-runbook остаётся `draft` и сам по себе не разрешает изменение доступа.
