# Проверка минималистичной версии — 07.11.2026

| Проверка | Результат |
|---|---|
| Kotlin/JVM: core | 818 тестов, 0 ошибок |
| Kotlin/JVM: app | 176 тестов, 0 ошибок |
| Android UI | 39 тестов, 0 ошибок |
| Снимки выжимки и поиска | 4 сценария, 0 ошибок |
| Реальный импорт аудиофайла | 58,58 с аудио → 14 реплик, без предупреждений |
| Экспорт результата | JSON валиден по схеме ТЗ; `transcript.json` |
| Подписанные APK | medium и online; проверены `apksigner` и `zipalign -P 16` |
| Сохранение ядра | нет изменений в core/audio/engine-*/store-objectbox/config/src/eval |
| Сохранение моделей | манифест 22 файлов и конфиг внутри APK совпадают с исходной версией |
| Сохранение исходного APK | SHA256 исходного medium совпадает с прежним SHA256SUMS.txt |
| Адаптивность | просмотрены обе темы, шрифт ×1,6, альбомная ориентация |

Среда: выделенный эмулятор `minimal-review`, ARM64, API 35, 4 ГБ RAM, диск 20 ГБ.
Исходный `jury-4g` и данные его приложений сохранены. Установка APK и проверки приложения выполнялись только на эмуляторах.

Скриншоты — настоящий Compose-интерфейс. Готовые ответы поиска и выжимка в `insight-shots/`
показаны на демонстрационных данных существующих UI-тестов; это проверка представления,
а не новый замер качества LLM. Остальные экраны сняты при обычной навигации и обработке файла.

## Исправления при проверке

- Убрана вторая кнопка повтора в состоянии ошибки выжимки с сохранённым документом.
- Проверка лицензии привязана к заголовку, поскольку название встречается и в самом тексте.
- Соседние кнопки главного экрана и вкладки протокола выровнены по высоте при переносе текста.

## Артефакты

- `index.html` — галерея с фильтрами и сравнением до/после.
- `checks/jvm-tests.json` — количество и результат JVM-тестов.
- `checks/minimal-ui-tests.log.txt` — полный результат 39 UI-тестов.
- `checks/minimal-insight-shots.log.txt` — четыре сценария скриншотов.
- `checks/minimal-build.log.txt`, `checks/minimal-release.log.txt` — логи сборки.
- `checks/apks.json` — имена файлов, размеры, пакеты и SHA256 двух новых APK.
- `processing.done.json`, `transcript.json` — маркер и результат обработки тестового файла.

## Повторение проверок

Из рабочей копии `codex/minimal-design`:

```bash
source scripts/env.sh
cd src/android
./gradlew --offline --max-workers=2 :core:test :app:testMediumDebugUnitTest
```

UI-проверка выполнялась установленным `app-medium-debug-androidTest.apk`:

```bash
adb -s emulator-5556 shell am instrument -w \
  -e class ru.neurosteno.app.UiKeyPathTest,ru.neurosteno.app.InsightUiTest,ru.neurosteno.app.LiveRecordUiTest,ru.neurosteno.app.TimelinePdfTest,ru.neurosteno.app.LicensesScreenTest \
  ru.neurosteno.minimal.medium.test/androidx.test.runner.AndroidJUnitRunner
```
