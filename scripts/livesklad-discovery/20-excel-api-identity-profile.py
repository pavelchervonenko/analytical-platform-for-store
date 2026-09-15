#!/usr/bin/env python3

"""Profile catalog/classification overlap without printing product-level business data."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import unicodedata
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from xml.etree import ElementTree


MAIN_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
CELL_REFERENCE = re.compile(r"([A-Z]+)")


def normalize(value: str | None) -> str:
    if value is None:
        return ""
    normalized = unicodedata.normalize("NFKC", str(value)).casefold()
    return " ".join(normalized.split())


def column_number(reference: str) -> int:
    match = CELL_REFERENCE.match(reference)
    if not match:
        raise ValueError(f"Invalid cell reference: {reference!r}")
    result = 0
    for character in match.group(1):
        result = result * 26 + ord(character) - ord("A") + 1
    return result


def shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        source = archive.open("xl/sharedStrings.xml")
    except KeyError:
        return []

    values: list[str] = []
    with source:
        for event, element in ElementTree.iterparse(source, events=("end",)):
            if element.tag == f"{MAIN_NS}si":
                values.append("".join(node.text or "" for node in element.iter(f"{MAIN_NS}t")))
                element.clear()
    return values


def cell_value(cell: ElementTree.Element, strings: list[str]) -> str:
    cell_type = cell.attrib.get("t")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(f"{MAIN_NS}t"))
    value_node = cell.find(f"{MAIN_NS}v")
    if value_node is None or value_node.text is None:
        return ""
    if cell_type == "s":
        return strings[int(value_node.text)]
    return value_node.text


def read_first_sheet(path: Path) -> list[dict[str, str]]:
    with zipfile.ZipFile(path) as archive:
        strings = shared_strings(archive)
        headers: dict[int, str] = {}
        records: list[dict[str, str]] = []
        with archive.open("xl/worksheets/sheet1.xml") as source:
            for event, element in ElementTree.iterparse(source, events=("end",)):
                if element.tag != f"{MAIN_NS}row":
                    continue
                values = {
                    column_number(cell.attrib["r"]): cell_value(cell, strings)
                    for cell in element.findall(f"{MAIN_NS}c")
                }
                if not headers:
                    headers = {number: value.strip() for number, value in values.items()}
                else:
                    records.append({headers[number]: value for number, value in values.items() if number in headers})
                element.clear()
    return records


def catalog_profile(records: list[dict[str, str]], file_index: int) -> dict[str, object]:
    codes = [normalize(record.get("Код")) for record in records]
    names = [normalize(record.get("Название") or record.get("Наименование")) for record in records]
    barcodes = [normalize(record.get("Штрихкоды")) for record in records]
    modifications = [normalize(record.get("Модификация")) for record in records]
    return {
        "fileIndex": file_index,
        "rowCount": len(records),
        "code": {
            "presentCount": sum(bool(value) for value in codes),
            "uniquePresentCount": len({value for value in codes if value}),
            "duplicateValueCount": sum(count > 1 for count in Counter(value for value in codes if value).values()),
        },
        "name": {
            "presentCount": sum(bool(value) for value in names),
            "uniquePresentCount": len({value for value in names if value}),
            "duplicateValueCount": sum(count > 1 for count in Counter(value for value in names if value).values()),
        },
        "barcode": {
            "presentCount": sum(bool(value) for value in barcodes),
            "uniquePresentCount": len({value for value in barcodes if value}),
            "duplicateValueCount": sum(count > 1 for count in Counter(value for value in barcodes if value).values()),
        },
        "modificationPresentCount": sum(bool(value) for value in modifications),
    }


def catalog_map(records: list[dict[str, str]]) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for record in records:
        code = normalize(record.get("Код"))
        if not code:
            continue
        result[code] = {
            "name": normalize(record.get("Название") or record.get("Наименование")),
            "barcode": normalize(record.get("Штрихкоды")),
            "modification": normalize(record.get("Модификация")),
        }
    return result


def cross_catalog(left: list[dict[str, str]], right: list[dict[str, str]]) -> dict[str, object]:
    left_map = catalog_map(left)
    right_map = catalog_map(right)
    shared = set(left_map) & set(right_map)
    return {
        "leftCodeCount": len(left_map),
        "rightCodeCount": len(right_map),
        "sharedCodeCount": len(shared),
        "leftOnlyCodeCount": len(set(left_map) - set(right_map)),
        "rightOnlyCodeCount": len(set(right_map) - set(left_map)),
        "sharedCodeSameNameCount": sum(left_map[code]["name"] == right_map[code]["name"] for code in shared),
        "sharedCodeDifferentNameCount": sum(left_map[code]["name"] != right_map[code]["name"] for code in shared),
        "sharedCodeDifferentBarcodeCount": sum(left_map[code]["barcode"] != right_map[code]["barcode"] for code in shared),
        "sharedCodeDifferentModificationCount": sum(
            left_map[code]["modification"] != right_map[code]["modification"] for code in shared
        ),
    }


def classification_assignments(path: Path) -> list[dict[str, str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    assignments = payload.get("assignments")
    if not isinstance(assignments, list):
        raise ValueError("Classification file must contain an assignments array")
    return [
        {
            "id": normalize(item.get("externalProductId")),
            "name": normalize(item.get("productName")),
            "category": normalize(item.get("categoryCode")),
        }
        for item in assignments
        if isinstance(item, dict)
    ]


def assignment_catalog_overlap(
    assignments: list[dict[str, str]], records: list[dict[str, str]]
) -> dict[str, object]:
    catalog_names: dict[str, list[str]] = defaultdict(list)
    catalog_codes: dict[str, str] = {}
    for record in records:
        name = normalize(record.get("Название") or record.get("Наименование"))
        code = normalize(record.get("Код"))
        if name and code:
            catalog_names[name].append(code)
        if code:
            catalog_codes[code] = name

    assignment_name_counts = Counter(
        assignment["name"] for assignment in assignments if assignment["name"]
    )
    exact = [assignment for assignment in assignments if assignment["name"] in catalog_names]
    id_matches = [assignment for assignment in assignments if assignment["id"] in catalog_codes]
    id_not_found = [assignment for assignment in assignments if assignment["id"] not in catalog_codes]
    unambiguous = [
        assignment
        for assignment in exact
        if len(set(catalog_names[assignment["name"]])) == 1
        and assignment_name_counts[assignment["name"]] == 1
    ]
    return {
        "assignmentCount": len(assignments),
        "assignmentPresentExternalIdCount": sum(bool(item["id"]) for item in assignments),
        "assignmentUniqueExternalIdCount": len({item["id"] for item in assignments if item["id"]}),
        "assignmentUniquePresentNameCount": len({item["name"] for item in assignments if item["name"]}),
        "exactAssignmentExternalIdToCatalogCodeMatchCount": len(id_matches),
        "idMatchedSameNameCount": sum(
            catalog_codes[item["id"]] == item["name"] for item in id_matches
        ),
        "idMatchedDifferentNameCount": sum(
            catalog_codes[item["id"]] != item["name"] for item in id_matches
        ),
        "assignmentExternalIdNotFoundAsCatalogCodeCount": len(assignments) - len(id_matches),
        "assignmentExternalIdNotFoundCategoryCounts": dict(
            sorted(Counter(item["category"] for item in id_not_found).items())
        ),
        "exactNormalizedNameMatchCount": len(exact),
        "unambiguousNameToCodeMatchCount": len(unambiguous),
        "unmatchedByExactNameCount": len(assignments) - len(exact),
        "identityConclusion": "name_overlap_is_diagnostic_only_not_a_stable_key",
    }


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--classification-assignments", required=True, type=Path)
    parser.add_argument("--catalog", action="append", required=True, type=Path)
    parser.add_argument("--private-index-output", type=Path)
    return parser.parse_args()


def write_private_identity_index(path: Path, records: list[dict[str, str]]) -> None:
    resolved = path.resolve()
    if resolved.parent != Path("/tmp"):
        raise ValueError("Private identity index must be created directly under /tmp")
    payload = {
        "codes": sorted({normalize(record.get("Код")) for record in records if normalize(record.get("Код"))}),
        "barcodes": sorted(
            {normalize(record.get("Штрихкоды")) for record in records if normalize(record.get("Штрихкоды"))}
        ),
    }
    descriptor = os.open(resolved, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as destination:
        json.dump(payload, destination, ensure_ascii=False)


def main() -> int:
    arguments = parse_arguments()
    if len(arguments.catalog) != 2:
        raise ValueError("Exactly two --catalog files are required")
    catalogs = [read_first_sheet(path) for path in arguments.catalog]
    assignments = classification_assignments(arguments.classification_assignments)
    result = {
        "containsProductLevelBusinessData": False,
        "catalogs": [catalog_profile(records, index + 1) for index, records in enumerate(catalogs)],
        "crossCatalog": cross_catalog(catalogs[0], catalogs[1]),
        "classificationAssignmentsAgainstCatalog1": assignment_catalog_overlap(assignments, catalogs[0]),
        "classificationAssignmentsAgainstCatalog2": assignment_catalog_overlap(assignments, catalogs[1]),
    }
    if arguments.private_index_output is not None:
        write_private_identity_index(arguments.private_index_output, catalogs[0])
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
