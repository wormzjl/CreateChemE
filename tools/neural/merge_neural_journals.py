"""Merge independently generated case groups, rejecting duplicate IDs or mismatched experiment designs."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("parts", type=Path, nargs="+")
    args = parser.parse_args()
    if args.output.resolve() in {p.resolve() for p in args.parts}:
        raise ValueError("Output must differ from the source journals")
    if (args.output / "cases.jsonl").exists():
        raise ValueError("Output journal already exists; choose a new directory")
    metadata = {name: (args.parts[0] / name).read_bytes() for name in ["design.json", "source-input.json"]}
    rows = {}
    for part in args.parts:
        for name, content in metadata.items():
            if json.loads((part / name).read_bytes()) != json.loads(content):
                raise ValueError("Mismatched source design: " + name)
        for line in (part / "cases.jsonl").read_text().splitlines():
            row = json.loads(line)
            if row["id"] in rows:
                raise ValueError("Duplicate case ID: " + str(row["id"]))
            rows[row["id"]] = line
    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in metadata.items():
        (args.output / name).write_bytes(content)
    (args.output / "cases.jsonl").write_text("\n".join(rows[i] for i in sorted(rows)) + "\n")
    print("Merged", len(rows), "cases")


if __name__ == "__main__":
    main()
