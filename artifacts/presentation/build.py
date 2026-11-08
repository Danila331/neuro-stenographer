#!/usr/bin/env python3
"""Собирает автономную HTML-презентацию защиты (без сети): скриншоты и шрифты вшиваются в файл.

Запуск из корня репозитория: python3 artifacts/presentation/build.py
Источники цифр — в заметках к каждому слайду (клавиша N). Шрифты — из приложения (SIL OFL 1.1, `docs/LICENSES.md`).
"""
from pathlib import Path
import base64
from html import escape

root = Path(__file__).resolve().parents[2]
out = Path(__file__).with_name('defense.html')
SCREENS = root / 'artifacts/demo/screens'
FONTS = root / 'src/android/app/src/main/res/font'


def b64(path, mime):
    return f'data:{mime};base64,' + base64.b64encode(path.read_bytes()).decode()


def img(name):
    return b64(SCREENS / name, 'image/png')


def shot(name, caption):
    return f'<figure><img src="{img(name)}" alt="{escape(caption)}"><figcaption>{escape(caption)}</figcaption></figure>'


def metric(value, label):
    return f'<div class="m"><b>{value}</b><span>{label}</span></div>'


slides = []

slides.append(('Нейро-стенографист', f'''
<div class="split">
 <div><p class="hero">Разговор в расшифровку — <mark>на устройстве</mark></p>
 <p>Русская и английская речь, говорящие, метки времени, локальная LLM и JSON строго по ТЗ. Без интернета.</p>
 <div class="chips"><span>офлайн</span><span>ARM64 · 4 ГБ</span><span>4 варианта APK</span></div><p class="sm">Жюри: WER 40, диаризация 25, метки 10, скорость 10, UX 10, защита 5. Проверка автоматическая, модели внутри APK.</p></div>
 <div class="shots">{shot('purple/01_home.png', 'Импорт и запись')}{shot('purple/03_transcript.png', 'Расшифровка и плеер')}</div>
</div>''', 'README.md; docs/INSTALL.md; docs/team/BACKLOG.md: Q2, Q6, Q7, Q9–Q10 (условия проверки). Скриншоты: artifacts/demo/screens/purple/.'))

slides.append(('Пайплайн', '''
<div class="flow"><div>Импорт<br><small>любой формат → 16 кГц</small></div><div>VAD + язык<br><small>Silero · Whisper-tiny по чанкам</small></div><div class="hi">ASR ∥ диаризация<br><small>GigaAM v3 / Parakeet · pyannote</small></div><div class="hi">Говорящие по фразам<br><small>эмбеддинг на фразу + ward</small></div><div>Реплики и JSON<br><small>Aligner · ТЗ</small></div></div>
<div class="flow low"><div>Протокол<br><small>Qwen3, по кнопке</small></div><div>Выжимка<br><small>темы, решения, ссылки</small></div><div>AI-поиск<br><small>BM25 + эмбеддинги + ответ</small></div><div>PDF, SRT, VTT, TXT</div></div>
<p>LLM работает после сохранения JSON и не влияет на RTF. Один код, один конфиг на телефоне и в Python-эталоне.</p>''',
 'docs/ARCHITECTURE.md: Компоненты и схема обработки.'))

slides.append(('Выбор моделей', '''
<div class="split">
 <div><div class="m big"><b>30,8 % <small>против</small> 45,9 %</b><span>WER со знаками, synth test: GigaAM v3 с пунктуацией и без</span></div>
 <div class="m big"><b>4,19 % <small>против</small> 9,77 %</b><span>GigaAM и Whisper-turbo на 166 русских фразах; Whisper — 2,7 ГБ RSS</span></div></div>
 <div><h2>Что где</h2><p><b>GigaAM v3 RNNT</b> — русский, знаки и регистр<br><b>Parakeet-110m</b> — английский (WER 6,6 %)<br><b>Whisper-tiny</b> — язык фрагмента<br><b>pyannote + ERes2Net/CAM++</b> — говорящие<br><b>Qwen3 0,6B / 1,7B / 4B</b> — протокол, выжимка<br><b>USER2 / rubert-tiny</b> — поиск</p><p class="sm">Отвергнуто: Zipformer, Whisper вторым проходом, голосование моделей, hotwords, LLM-пунктуация.</p></div>
</div>''', 'docs/MODELS.md: «Почему GigaAM», «Что отвергнуто»; src/eval/bench_asr/results/final/SUMMARY_ru.md (synth test, pc_sep_num); second_pass_rudev.md; final/mem.jsonl.'))

slides.append(('Качество', '''
<div class="grid4">'''+metric('24,0 → 11,7 %', 'DER ±.25, диаризация по фразам (refpipe, test)')+metric('42 → 77 %', 'файлов с верным числом говорящих (test)')+metric('21,4 %', 'WER norm, итоговая конфигурация, test')+metric('0,00 п.п.', 'расхождение Android и эталона по WER и DER')+'''</div>
<table><tr><th>Итог refpipe, medium, test (31 файл)</th><th>WER norm</th><th>cpWER</th><th>DER ±.25</th><th>начало реплики</th></tr>
<tr><td>все условия</td><td>21,4</td><td>30,5</td><td>11,3</td><td>256 мс, 77 % &lt; 300 мс</td></tr>
<tr><td>шум · дальнее поле</td><td>14,4 · 14,9</td><td>24,8 · 22,0</td><td>8,7 · 5,2</td><td></td></tr>
<tr><td>перекрытия · телефон</td><td>26,0 · 23,4</td><td>32,0 · 54,3</td><td>16,1 · 21,0</td><td></td></tr></table>
<p class="sm">Наборы синтетические или из чтения; реальных размеченных диалогов нет.</p>''',
 'docs/TEST_RESULTS.md: сквозной пайплайн (run_id refpipe-bp-robust-test-bp1.0-k1thr030, refpipe-medium-relabel-test, refpipe-medium-norelabel-test); паритет — docs/team/BACKLOG.md B28, B42, B46, B55; подтверждено архитектором 2026-11-07.'))

slides.append(('Скорость и память', '''
<div class="grid4">'''+metric('0,075–0,133', 'RTF, medium release, 6 записей, jury-4g')+metric('0,088', 'RTF, запись 30 минут, пик 938 МБ, без OOM')+metric('0,5–0,66 ГБ', 'пик PSS обработки (LLM не участвует)')+metric('1258 → 53 МБ', 'пик памяти при первом запуске')+'''</div>
<table><tr><th>Проверка</th><th>Результат</th></tr>
<tr><td>lite на jury-2g (2 ГБ RAM)</td><td>RTF 0,105–0,119, пик PSS 452–498 МБ</td></tr>
<tr><td>протокол Qwen3-1.7B · выжимка · ответ поиска</td><td>PSS 1,71 · 1,75 · 1,36 ГБ</td></tr>
<tr><td>финальная регрессия release-APK</td><td>16 из 16 PASS</td></tr></table>
<p class="sm">Эмулятор ARM без GPU, хост нагружен — RTF относительный. Pixel 10 (старая сборка): RTF 0,16–0,23; финальный прогон на телефоне — TODO(финал).</p>''',
 'artifacts/test-results/prerelease2/README.md (пп. 3, 4, 8–12, 16); B44; docs/TEST_RESULTS.md: Устройства. TODO(финал): финальный прогон на Pixel с итоговым APK.'))

slides.append(('Функции', f'''
<div class="shots four">{shot('purple/04_record.png', 'Живой текст при записи')}{shot('purple/02_processing.png', 'Этапы обработки')}{shot('purple/03_transcript.png', 'Расшифровка и плеер')}{shot('purple/01_home.png', 'Главный экран')}</div>
<p class="sm">Ещё: повторная обработка фрагмента, PDF, протокол, менеджер моделей, тёмная тема, шрифт ×1,6.</p>''',
 'artifacts/demo/screens/purple; README.md: таблицы возможностей.'))

slides.append(('ТЗ: Ultra Pro Max', '''
<table class="tz"><tr><th>Пункт</th><th>Статус</th><th>Цифра</th></tr>
<tr><td>Шум и плохие записи</td><td>да</td><td>шум WER 14,4 %, дальнее поле 14,9 %</td></tr>
<tr><td>Короткие реплики, перебивания</td><td class="p">частично</td><td>VAD теряет 3–12 % коротких</td></tr>
<tr><td>Одновременная речь</td><td class="p">частично</td><td>слова смеси — одному говорящему; DER 16,1 %</td></tr>
<tr><td>Тембр и манера речи</td><td>да</td><td>число говорящих верно у 81 % файлов</td></tr>
<tr><td>Оперативная память</td><td>да</td><td>0,5–0,66 ГБ; 30 мин — 938 МБ</td></tr>
<tr><td>Аппаратное ускорение</td><td class="p">частично</td><td>только CPU-оптимизации; GPU/NPU нет</td></tr>
<tr><td>Правка и повторная обработка</td><td>да</td><td>фрагмент за ≈1 с, с отменой</td></tr></table>
<p class="sm">Сверх ТЗ: живая расшифровка, выжимка, AI-поиск, PDF, менеджер моделей, редизайн.</p>''',
 'README.md: «Требования ТЗ: как закрыто»; docs/TEST_RESULTS.md; docs/team/BACKLOG.md B31, B37, B38, B55. TODO(финал): NNAPI/GPU (B38) — нужен Pixel.'))

slides.append(('Ограничения', '''
<div class="split"><div><h2>Честно</h2><p>Перекрытия и дальнее поле ухудшают распознавание (остаток — предел модели).</p><p>Число говорящих иногда «флипает» (B57): DER cats_c01 21,7 %, cats_c04 41,2 %.</p><p>Данные синтетические; методика жюри не подтверждена (знаки, числа).</p></div>
<div><h2>Дальше</h2><p>Реальные размеченные диалоги и подбор порогов на них.</p><p>Устойчивый выбор числа говорящих.</p><p>GPU/NPU с откатом на CPU.</p><p>Английская пунктуация.</p></div></div>''',
 'docs/LIMITATIONS.md; artifacts/test-results/prerelease2/README.md п. 2а; docs/team/BACKLOG.md B57, B61.'))

slides.append(('Команда и ИИ', '''
<div class="split"><div><h2>Как работали</h2><p>ИИ — инструмент: код и тексты писали агенты Claude под руководством архитектора; каждое решение подтверждено замером и записано в бэклоге.</p><p>Заготовленные расшифровки не используются; параметры подбирались по dev, test не трогали.</p></div>
<div><h2>Что можно проверить</h2><p>Все цифры воспроизводятся: <b>stenoeval report</b>, таблицы bench, <b>parity.sh</b>.</p><p>Исходники, APK и sha256, лицензии — в репозитории.</p><p class="sm">Кто за какой блок отвечает — TODO(финал): назвать людей.</p></div></div>''',
 'docs/DEFENSE.md, вопросы 36–38; docs/team/TEAM.md; docs/LICENSES.md. TODO(финал): распределение блоков между людьми.'))

fonts = (
    "@font-face{font-family:'Inter';src:url(" + b64(FONTS / 'inter.ttf', 'font/ttf') + ") format('truetype');font-weight:100 900}"
    "@font-face{font-family:'JetBrains Mono';src:url(" + b64(FONTS / 'jetbrains_mono.ttf', 'font/ttf') + ") format('truetype');font-weight:100 800}"
)
css = fonts + '''
*{box-sizing:border-box}body{margin:0;background:#0D0D10;color:#19191D;font-family:'Inter',Arial,Helvetica,sans-serif}
.slide{background:#F5F5F5;width:100vw;height:100vh;padding:4vh 5vw;display:none;position:relative;border:.8vh solid #19191D;overflow:hidden}
.slide.active{display:block}
header{display:flex;align-items:center;gap:1.5vw;border-bottom:.7vh solid #19191D;padding-bottom:1.6vh;margin-bottom:2.2vh}
header strong{background:#540DFF;color:#fff;border:.45vh solid #19191D;box-shadow:.5vh .5vh 0 #19191D;padding:.1em .4em;font:700 1.8vw 'JetBrains Mono',monospace}
h1{font:700 3.1vw 'Inter',Arial,sans-serif;margin:0;line-height:1.1}
h2{font:700 1.9vw 'Inter',Arial,sans-serif;margin:0 0 1.6vh;border-bottom:.6vh solid #540DFF;display:inline-block}
p{font-size:1.75vw;line-height:1.3;margin:1.4vh 0}.sm{font-size:1.3vw;color:#71717C}
.hero{font:700 3.1vw 'Inter',Arial,sans-serif;line-height:1.2;margin:2vh 0 3vh}
mark{background:#540DFF;color:#fff;padding:0 .12em}
.split{display:grid;grid-template-columns:1fr 1fr;gap:4vw;align-items:start}
.chips span{display:inline-block;border:.4vh solid #19191D;box-shadow:.4vh .4vh 0 #19191D;padding:.4vh 1vw;margin:0 1vw 1vh 0;font:600 1.4vw 'JetBrains Mono',monospace;background:#fff}
.shots{display:flex;gap:2.5vw;justify-content:center;height:76vh}.shots.four{gap:1.6vw;height:68vh;margin-top:.5vh}
figure{margin:0;height:100%;text-align:center}figure img{height:calc(100% - 3.4vh);max-width:100%;object-fit:contain;border:.45vh solid #19191D;box-shadow:.7vh .7vh 0 #19191D}
figcaption{font:600 1.2vw 'Inter',Arial,sans-serif;margin-top:1vh}
.flow{display:flex;margin:2.4vh 0}.flow div{flex:1;border:.45vh solid #19191D;padding:2.2vh .8vw;font:600 1.5vw 'Inter',Arial,sans-serif;background:#fff}
.flow div+div{border-left:0}.flow .hi{background:#540DFF;color:#fff}.flow small{display:block;font:400 1vw 'JetBrains Mono',monospace;margin-top:.6vh}.flow.low div{background:#F2F2F5;color:#19191D}
.grid4{display:grid;grid-template-columns:repeat(4,1fr);gap:1.5vw;margin:1vh 0 2.4vh}
.m{border:.45vh solid #19191D;box-shadow:.6vh .6vh 0 #19191D;padding:1.8vh 1vw;background:#fff}
.m b{display:block;font:800 2.1vw 'JetBrains Mono',monospace;line-height:1.1}.m span{display:block;font-size:1.2vw;margin-top:1vh;line-height:1.25}
.m.big{margin-bottom:3vh;background:#540DFF;color:#fff}.m.big b{font-size:2.7vw}.m.big small{font-size:1.2vw;font-weight:400}
table{border-collapse:collapse;width:100%;margin:1vh 0;font-size:1.45vw}th,td{border:.35vh solid #19191D;padding:1.1vh 1vw;text-align:left}th{background:#19191D;color:#fff}
td.p{background:#EEE8FF;font-weight:700}.tz td:nth-child(2){width:11vw}
.bars div{position:relative;height:6.2vh;margin:1.2vh 0;border:.4vh solid #19191D;background:#fff}
.bars div:before{content:"";position:absolute;left:0;top:0;bottom:0;width:var(--w);background:#540DFF}
.bars b,.bars i{position:relative;font:700 1.5vw 'JetBrains Mono',monospace;line-height:5.4vh;padding:0 1vw}.bars i{float:right;font-style:normal}
.notes{display:none;position:absolute;bottom:0;left:0;right:0;background:#540DFF;color:#fff;border-top:.5vh solid #19191D;padding:1vh 1.5vw;font-size:1.05vw}
.show-notes .notes{display:block}.hint{position:fixed;bottom:1vh;right:1.5vw;color:#A6A6B0;font:12px 'JetBrains Mono',monospace}
@media print{body{background:#fff}.slide{display:block;page-break-after:always;width:100vw;height:100vh}.hint,.notes{display:none!important}}
'''
html = '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Нейро-стенографист — защита</title><style>' + css + '</style><body>'
for i, (title, body, note) in enumerate(slides, 1):
    html += (f'<section class="slide{" active" if i == 1 else ""}"><header><strong>{i:02}</strong><h1>{escape(title)}</h1></header>'
             f'{body}<aside class="notes">Источник: {escape(note)}</aside></section>')
html += ('<div class="hint">← / → слайды · N источники</div><script>let n=0,s=[...document.querySelectorAll(".slide")];'
         'function show(){s.forEach((x,i)=>x.classList.toggle("active",i===n))}'
         'onkeydown=e=>{if(e.key==="ArrowRight"||e.key===" "){n=Math.min(n+1,s.length-1);show()}'
         'if(e.key==="ArrowLeft"){n=Math.max(n-1,0);show()}if(e.key.toLowerCase()==="n")document.body.classList.toggle("show-notes")};'
         'onclick=e=>{if(e.clientX>innerWidth/2){n=Math.min(n+1,s.length-1)}else{n=Math.max(n-1,0)}show()}</script></body></html>')
out.write_text(html, encoding='utf-8')
print('Wrote', out, f'{out.stat().st_size // 1024} KiB, {len(slides)} slides')
