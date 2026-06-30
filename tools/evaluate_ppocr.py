"""Prueba local del reconocedor PP-OCRv6 contra un recorte de diagnóstico."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
import yaml


def find_text_rows(image: Image.Image) -> list[tuple[int, int]]:
    rgb = np.asarray(image.convert("RGB"))
    gray = rgb.mean(axis=2)
    ink = gray < 215
    projection = ink.sum(axis=1).astype(np.float32)

    # Las líneas horizontales de la tabla no son texto.
    projection[projection > image.width * 0.72] = 0
    smooth = np.convolve(projection, np.ones(9, dtype=np.float32) / 9, mode="same")
    active = smooth > max(3, image.width * 0.012)

    runs: list[list[int]] = []
    start = None
    for y, enabled in enumerate(active):
        if enabled and start is None:
            start = y
        elif not enabled and start is not None:
            if y - start >= 8:
                runs.append([start, y])
            start = None
    if start is not None:
        runs.append([start, image.height])

    merged: list[list[int]] = []
    for run in runs:
        if merged and run[0] - merged[-1][1] < 5:
            merged[-1][1] = run[1]
        else:
            merged.append(run)

    heights = sorted(bottom - top for top, bottom in merged)
    typical_height = heights[len(heights) // 2] if heights else 1
    split_runs: list[tuple[int, int]] = []
    for top, bottom in merged:
        part_count = max(1, round((bottom - top) / typical_height))
        if part_count == 1 or bottom - top < typical_height * 1.55:
            split_runs.append((top, bottom))
            continue
        part_height = (bottom - top) / part_count
        for part in range(part_count):
            split_runs.append((
                round(top + part * part_height),
                round(top + (part + 1) * part_height),
            ))

    result = []
    for top, bottom in split_runs:
        if bottom - top < 28:
            continue
        result.append((max(0, top - 10), min(image.height, bottom + 10)))
    return result


def crop_ink_horizontally(row: Image.Image) -> Image.Image:
    rgb = np.asarray(row.convert("RGB"))
    ink = rgb.mean(axis=2) < 225
    columns = np.where(ink.sum(axis=0) > 2)[0]
    if columns.size == 0:
        return row
    left = max(0, int(columns[0]) - 12)
    right = min(row.width, int(columns[-1]) + 13)
    return row.crop((left, 0, right, row.height))


def prepare(row: Image.Image, bgr: bool) -> np.ndarray:
    row = crop_ink_horizontally(row).convert("RGB")
    width = max(32, min(640, round(row.width * 48 / row.height)))
    resized = row.resize((width, 48), Image.Resampling.BICUBIC)
    array = np.asarray(resized).astype(np.float32)
    if bgr:
        array = array[:, :, ::-1]
    array = array.transpose(2, 0, 1) / 255.0
    array = (array - 0.5) / 0.5
    return array[None, :, :, :].copy()


def decode(output: np.ndarray, characters: list[str]) -> tuple[str, float]:
    values = output[0]
    indices = values.argmax(axis=1)
    probabilities = values.max(axis=1)
    result = []
    confidence = []
    previous = -1
    for index, probability in zip(indices, probabilities):
        index = int(index)
        if index != 0 and index != previous and index - 1 < len(characters):
            result.append(characters[index - 1])
            confidence.append(float(probability))
        previous = index
    return "".join(result), float(np.mean(confidence)) if confidence else 0.0


def logsumexp(values: list[float]) -> float:
    maximum = max(values)
    if not math.isfinite(maximum):
        return maximum
    return maximum + math.log(sum(math.exp(value - maximum) for value in values))


def ctc_log_probability(
    output: np.ndarray,
    candidate: str,
    character_indices: dict[str, int],
) -> float:
    labels = [character_indices[character] for character in candidate]
    extended = [0]
    for label in labels:
        extended.extend((label, 0))
    probabilities = np.maximum(output[0], 1e-12)
    log_probs = np.log(probabilities)
    previous = np.full(len(extended), -np.inf, dtype=np.float64)
    previous[0] = log_probs[0, 0]
    if len(extended) > 1:
        previous[1] = log_probs[0, extended[1]]
    for time in range(1, log_probs.shape[0]):
        current = np.full(len(extended), -np.inf, dtype=np.float64)
        for state, label in enumerate(extended):
            sources = [float(previous[state])]
            if state > 0:
                sources.append(float(previous[state - 1]))
            if state > 1 and label != 0 and label != extended[state - 2]:
                sources.append(float(previous[state - 2]))
            current[state] = logsumexp(sources) + float(log_probs[time, label])
        previous = current
    return logsumexp([float(previous[-1]), float(previous[-2])])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("model", type=Path)
    parser.add_argument("config", type=Path)
    parser.add_argument("--rows-dir", type=Path)
    parser.add_argument("--catalog", type=Path)
    args = parser.parse_args()

    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    characters = config["PostProcess"]["character_dict"]
    if config["PostProcess"].get("use_space_char"):
        characters.append(" ")
    character_indices = {character: index + 1 for index, character in enumerate(characters)}
    catalog = []
    if args.catalog:
        catalog = [
            item["matricula"].replace("-", "").upper()
            for item in json.loads(args.catalog.read_text(encoding="utf-8"))
        ]

    image = Image.open(args.image).convert("RGB")
    rows = find_text_rows(image)
    print(f"rows={rows}")
    if args.rows_dir:
        args.rows_dir.mkdir(parents=True, exist_ok=True)

    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    for index, (top, bottom) in enumerate(rows, start=1):
        row = image.crop((0, top, image.width, bottom))
        if args.rows_dir:
            row.save(args.rows_dir / f"row-{index}.png")
        alternatives = []
        for bgr in (True, False):
            output = session.run([output_name], {input_name: prepare(row, bgr)})[0]
            alternatives.append(decode(output, characters))
        print(f"{index}: {alternatives}")
        if catalog:
            output = session.run([output_name], {input_name: prepare(row, True)})[0]
            ranked = sorted(
                (
                    ctc_log_probability(output, candidate, character_indices),
                    candidate,
                )
                for candidate in catalog
            )
            print("   catalog:", list(reversed(ranked[-5:])))


if __name__ == "__main__":
    main()
