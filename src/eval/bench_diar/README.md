# bench_diar — diarization benchmark (WIP)

Status: paused mid-way (machine overloaded). Done: metrics, sherpa runner, staged pipeline replica with
caching, tune/stage CLIs, clustering variants (not yet evaluated). Results so far: `results/prelim_*.csv`.

## Setup
```
cd src/eval/bench_diar
uv venv --python 3.11 .venv && uv pip install --python .venv/bin/python -r pyproject.toml
./fetch_models.sh            # segmentation + embedding models -> $ROOT/.cache/models (diar-emb-<name>/model.onnx)
.venv/bin/python -m pytest -q tests      # DER implementation cross-checked vs pyannote.metrics
```
## Run
```
# stock sherpa-onnx (what ships in the APK):
.venv/bin/python -m bench_diar.sweep --dataset $ROOT/.cache/data/ru-dialog-synth --split dev \
   --embs <name> --thresholds 0.5,0.7 --threads 2 --procs 4 --out results/x.csv
# staged replica (expensive stages A+B cached in results/stage_cache; clustering sweeps are then cheap):
.venv/bin/python -m bench_diar.stage --dataset ... --embs a,b --shift 0.3 --procs 4
.venv/bin/python -m bench_diar.tune  --dataset ... --split dev --embs a,b --shift 0.3 --out results/x.csv
```
Modules: `metrics` (DER/JER, collar 0/0.25, overlap-aware), `runner` (stock sherpa), `pipeline`
(staged replica of sherpa's pyannote pipeline), `variants`/`improve` (clustering & post-processing
prototypes), `synth` (own synthetic dialog generator, unused so far).

## Known pitfalls found
* Batched onnxruntime inference of the exported pyannote int8 model gives different labels than batch=1
  (8.6 % frames differ); the replica runs batch=1.
* Replica vs stock sherpa is not yet bit-parity (same segmentation with k=1: DER 0.0; with clustering a
  few % to tens of % on a 34 s clip, brittle with few embeddings) -> headline numbers must come from `sweep`
  (stock), the replica is a sandbox for improvements.
