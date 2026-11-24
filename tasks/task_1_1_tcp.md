# 1.1. TCP и обмен сообщениями

[Этап 1](task_1.md) · Зависимостей нет.

## Результат

Два процесса обмениваются целыми сообщениями через `Connection`. TCP не влияет
на прикладной протокол и корректно обрабатывает частичное чтение и закрытие.

## Прогресс

- [x] Есть `Connection`, `Endpoint`, `TcpConnection`, `TcpListener` и `FrameCodec`.
- [x] Длина и содержимое кадра читаются полностью; проверяется предел размера.
- [x] Есть команды чата и отображение подключения, отключения и ошибок.
- [x] Написаны локальные тесты кадров, двустороннего обмена, конкурентной отправки и разблокировки при закрытии.
- [ ] На двух ПК в одной LAN проверены чат в обе стороны, недоступный адрес и занятый порт; сохранены результаты.

## Где смотреть и проверять

- `core/src/main/kotlin/org/enderlink/network/{connection,tcp,protocol}`.
- `cli/src/main/kotlin/org/enderlink/cli/console/ChatSession.kt`.
- Тесты: `FrameCodecTest`, `TcpConnectionTest`, `TcpIntegrationTest`.
- Ручной запуск: [TCP-чат в руководстве CLI](../cli/README.md#по-локальному-адресу-tcp).

```bash
./gradlew :core:test --tests '*FrameCodecTest' --tests '*TcpConnectionTest' --tests '*TcpIntegrationTest'
```

Закрыть после локальных тестов и записи результата проверки LAN. TCP на этом
этапе используется только в доверенной сети.
