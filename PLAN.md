# План: собственный Elasticsearch-плагин для IntelliJ IDEA

## 0. Что уже проверено (19.10.2026)

Сборка плагина под IntelliJ IDEA 2026.2.3 (build 262) реально работает в этой среде:

| Параметр | Значение |
|---|---|
| Gradle | 8.14.4 (из локального кэша) |
| JDK для сборки | JBR 21 из IDEA 2025.2.6.2 |
| IntelliJ Platform Gradle Plugin | 2.10.5 |
| Целевая IDE | `C:\Users\rutkovskiy_ayu\AppData\Local\Programs\IntelliJ IDEA` (IU-2026.2.3, 262.10968.63) |
| Kotlin | 2.4.21, `jvmTarget = 21` |
| Результат | `build/distributions/esplugin-spike-0.1.0.zip` (1.7 МБ) |

Особенности среды, которые уже учтены в конфиге:

- **Gradle 9.x не запускается**: в песочнице `Files.isWritable()` возвращает `false` для каталога проекта
  (ACL с deny на `DeleteSubdirectoriesAndFiles` для `Everyone`), и Gradle 9 отказывается конфигурировать
  проект. Gradle 8.14.4 такой проверки не делает.
- `GRADLE_USER_HOME` должен лежать внутри workspace, иначе не грузится `native-platform.dll`.
- `buildSearchableOptions` отключаем: headless-IDE не может создать `.lock` в sandbox-каталоге.
- Kotlin-демон не может писать в `%LOCALAPPDATA%\kotlin\daemon` → компиляция идёт in-process.

## 1. Границы: что можно взять из оригинала

Оригинал — платный плагин `org.elasticsearch4idea` (Anton Shuvaev), jar обфусцирован.

**Можно и полезно:**
- `META-INF/plugin.xml` — карта функций, точки расширения, версии, список зависимостей.
- Набор ресурсов — как ориентир по UX (иконки, цветовые схемы, web-UI).
- Поведение UI и REST-запросы, которые плагин шлёт в кластер (black-box).
- Публичная документация Elasticsearch/OpenSearch и IntelliJ Platform SDK.

**Нельзя:**
- Декомпилировать обфусцированные классы и переносить код.
- Копировать их иконки, строки, HTML-документацию, React-бандл.
- Патчить лицензионную проверку.

Поэтому пишем clean-room реализацию: свой код, свои ресурсы, Apache-2.0.

## 2. Архитектура

```
elasticsearch-client/
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── src/main/kotlin/dev/esplugin/
│   ├── settings/      ConnectionState, ConnectionManager (PersistentStateComponent), PasswordSafe
│   ├── client/        EsClient (java.net.http), AuthStrategy, Request/Response, ошибки
│   ├── ui/tree/       tool window, дерево кластеров/индексов, actions
│   ├── ui/console/    редактор запроса + панель ответа
│   ├── ui/table/      data browser (позже)
│   └── util/          JSON, нотификации, корутины, форматирование
├── src/main/resources/META-INF/plugin.xml, icons/, messages/
└── src/test/kotlin/   unit-тесты клиента
```

Ключевые решения:

- **UI — Swing/JB UI**, без JCEF и React. Проще, нативнее, меньше зависимостей.
- **HTTP — `java.net.http.HttpClient`** (JDK 21): basic/API-key/bearer, TLS, прокси, таймауты.
- **JSON — Gson** (лёгкий, уже есть в платформе; при необходимости — Jackson).
- **Асинхронность — kotlinx-coroutines** + `AppExecutorUtil`/`ProgressManager`, UI только на EDT.
- **Секреты — `PasswordSafe`**, настройки — `PersistentStateComponent` (XML в config).
- **Версии ES/OpenSearch** определяем по `GET /` (`version.number`, `tagline`, заголовок
  `X-Elastic-Product`), а не по жёсткой проверке.
- **since-build 262**, `until-build 263.*`.

## 3. Этапы

### M0. Каркас (сделано в spike, переносится в проект)
- Gradle-проект, plugin.xml, tool window-заглушка, сборка zip, запуск `runIde`.
- DoD: `buildPlugin` зелёный, плагин виден в IDEA.

### M1. Подключения и дерево кластера
- Диалог соединения: имя, URL(ы), basic auth / API key / bearer / без auth, доп. заголовки,
  таймауты, SSL (verify on/off, свой CA позже), «Test connection».
- Хранение: несколько соединений, пароли в PasswordSafe, экспорт/импорт (без секретов).
- Tool window «Elasticsearch»: дерево `Cluster → Indices / Aliases / Data streams / Nodes`,
  ленивая загрузка, refresh, фильтр, иконки/статусы (green/yellow/red), контекстное меню.
- Действия: add/edit/duplicate/remove connection, refresh, copy name, open in console.
- DoD: подключение к локальному/удалённому кластеру, дерево строится, ошибки показываются
  нотификацией, настройки переживают рестарт IDE.

### M2. REST-консоль
- Вкладка с двумя панелями: запрос (метод + путь + тело) и ответ.
- Выполнение `Ctrl+Enter`, отмена, таймаут, статус/время/размер ответа.
- Подсветка JSON (bundled JSON language), форматирование, свёртка, поиск.
- История запросов (persistent), избранное, сохранение в project/scratch.
- Подстановка `{{host}}`/переменных соединения.
- DoD: можно выполнить любой REST-запрос и увидеть ответ, история сохраняется.

### M3. Data browser
- Таблица документов индекса: колонки из маппинга, сортировка, пагинация (from/size + search_after),
  фильтр, просмотр JSON документа.
- Create/update/delete документа, bulk-изменения, подтверждение.
- Экспорт CSV/JSON, импорт JSON/NDJSON (bulk).
- DoD: CRUD документов без выхода из IDE.

### M4. Продвинутая авторизация и сеть
- SSL: custom CA, client certificate (PKCS#12/PEM), отключение проверки с предупреждением.
- SSH-туннель (JSch): хост, порт, ключ/пароль, local port forwarding.
- AWS SigV4 (ES/OpenSearch managed, serverless) — руками или AWS SDK v2 signer.
- Прокси, keep-alive, retry, gzip.
- DoD: подключение к AWS OpenSearch и кластеру за SSH.

### M5. Языки и автодополнение
- File type `*.es` / `*.kql` / `*.painless`, лексер, парсер, подсветка, folding, formatter.
- Completion для Query DSL (поля индекса, ключи агрегаций), KQL, Painless.
- Инспекции (duplicate keys, неизвестные поля), quick-fix, documentation provider.
- DoD: автодополнение работает на реальном маппинге.

### M6. Полировка и публикация
- Настройки/цвета, i18n-бандл, иконки, README, скриншоты.
- `verifyPlugin` против 2026.2, тесты, CI (GitHub Actions), подпись, публикация на Marketplace.
- DoD: плагин устанавливается из zip/Marketplace, проходит verifier.

## 4. Тестирование

- Unit: `EsClient` против встроенного `com.sun.net.httpserver.HttpServer` (без внешних зависимостей):
  auth-заголовки, ошибки, таймауты, парсинг ответов.
- Integration (опционально): docker-контейнер Elasticsearch 8/9 + OpenSearch; если Docker недоступен —
  пропускаем.
- UI: ручной прогон через `runIde`; позже — `BasePlatformTestCase`.
- Совместимость: `verifyPlugin` для 2026.2 (262) и, при желании, 2026.3.

## 5. Риски

| Риск | mitigation |
|---|---|
| Песочница ломает Gradle 9 / headless IDE | зафиксированы Gradle 8.14.4, JDK 21, `-x buildSearchableOptions` |
| Изменения API в 2026.2 (JCEF, structureView) | не используем JCEF; минимум platform API |
| Конфликт библиотек с платформой | Gson/Jackson только в plugin classloader, без `provided` |
| Торговая марка Elasticsearch | имя «Elasticsearch Client», дисклеймер, Apache-2.0, без логотипов Elastic |
| Огромный объём фич оригинала | строгий scope по этапам, M1–M2 дают 80% пользы |

## 6. Ближайший шаг

1. Создать `elasticsearch-client/` с проверенным `build.gradle.kts` и plugin.xml.
2. Перенести каркас, сделать M1: настройки соединения + тест + дерево индексов.
3. Прогнать `buildPlugin` и `runIde`, проверить на реальном кластере (если есть доступ).
