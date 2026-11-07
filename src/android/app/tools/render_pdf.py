"""Хост-рендер страниц PDF в PNG и проверка текста (PyMuPDF): render_pdf.py <file.pdf> <каталог>. Запускать с -I из чужого cwd."""
import sys
import pymupdf

src, outdir = sys.argv[1], sys.argv[2]
d = pymupdf.open(src)
print("страниц:", d.page_count)
for p in d:
    print(f"  стр. {p.number + 1}: {len(p.get_text())} символов; начало: {p.get_text()[:90]!r}")
    p.get_pixmap(dpi=110).save(f"{outdir}/page{p.number + 1}.png")
