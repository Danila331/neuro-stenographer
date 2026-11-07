# Презентация защиты

`defense.html` — 9 слайдов в стиле приложения (нео-брутализм, акцент кислотно-зелёный), один автономный файл: скриншоты и шрифты (Unbounded, JetBrains Mono, SIL OFL) вшиты, сеть не нужна.

- Открыть в браузере: `←` / `→` или клик по левой/правой половине экрана переключают слайды, `N` показывает источники цифр. Печать (PDF) — один слайд на страницу.
- Пересборка из корня: `python3 artifacts/presentation/build.py` (только стандартная библиотека; цифры и подписи — в `build.py`, скриншоты — из `artifacts/demo/screens/`).
- Источники: `docs/TEST_RESULTS.md`, `docs/MODELS.md`, `docs/ARCHITECTURE.md`, `docs/LIMITATIONS.md`, `docs/DEFENSE.md`, `artifacts/test-results/prerelease2/README.md`.
- Остаётся `TODO(финал)` (заметки слайдов 5, 7, 9): финальный прогон на Pixel, аппаратное ускорение (B38), распределение блоков между людьми.
