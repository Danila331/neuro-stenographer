"""stenodata: сборка eval-датасетов для Нейро-стенографиста (формат docs/team/ARCHITECTURE_TEAM.md §2)."""
import os

__version__ = "0.1.0"

# Ограничение нагрузки (решение пользователя): библиотеки не плодят потоки; параллелизм задаёт только вызывающий.
for _k in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "VECLIB_MAXIMUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS"):
    os.environ.setdefault(_k, "1")
