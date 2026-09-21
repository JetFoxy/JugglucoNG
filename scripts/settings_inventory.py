#!/usr/bin/env python3
"""Settings inventory generator (plan task T2.1).

Scans the shared, mobile and wear source sets for SharedPreferences files and
Room databases, and writes docs/architecture/settings-inventory.md. The point is
the gate, not the prose: `--check` fails if the committed inventory no longer
matches the code, so a new prefs file cannot be added without being recorded.

What is deliberately not derived here (see the note in the generated doc):
- per-key defaults and writer/reader sets — that is dataflow, not a scan;
- the native `Natives.get*/set*` surface — hundreds of methods whose
  setting/data/command split needs a human pass.

Usage:
    scripts/settings_inventory.py --write          # update the committed doc
    scripts/settings_inventory.py --check          # fail on drift
    scripts/settings_inventory.py --stdout         # print, do not write
    ... [--root DIR] [--doc FILE]
"""
from __future__ import annotations

import argparse
import os
import re
import sys

SOURCES = ("Common/src/main/java", "Common/src/mobile/java", "Common/src/wear/java")
DOC_RELATIVE = "docs/architecture/settings-inventory.md"

# getSharedPreferences(<first argument>, ...) — capture the argument up to the comma.
CALL_RE = re.compile(r"getSharedPreferences\(\s*([^,]+?)\s*,")
# A constant that names a prefs file. Kept deliberately tight: `PREF` alone is
# not enough (it would match PREFIX), and generic names like `PREFS` are resolved
# per file rather than globally.
CONST_RE = re.compile(
    r"""(?:String|val|const\s+val)\s+(\w*(?:PREFS|PREF_FILE|PREF_NAME|PrefsName|prefsName)\w*)\s*=\s*"([^"]+)\""""
)
# getSharedPreferences("literal",
LITERAL_RE = re.compile(r'^"([^"]+)"$')
DATABASE_RE = re.compile(r"@(?:[\w.]+\.)?Database\s*\(")
ENTITIES_RE = re.compile(r"entities\s*=\s*\[([^\]]*)\]")
# A migrated area names its prefs file on the SettingKey instead of calling
# getSharedPreferences. Without this, moving an area onto SettingsStore would
# quietly drop it from the inventory.
SETTINGKEY_RE = re.compile(r"SettingKey\(\s*([^,]+?)\s*,")
# Any `NAME = "value"`; only used to resolve a SettingKey's file argument, where
# a generic name like FILE is expected (the prefs-named CONST_RE above is too narrow).
ANY_CONST_RE = re.compile(r'(?:String|val|const\s+val)\s+(\w+)\s*=\s*"([^"]+)"')


def java_files(root: str):
    """Every source file, in a stable order. The order matters: `defined_in`
    picks the first file that declares a name, so an unsorted walk makes the
    generated doc differ between machines (it did, on CI)."""
    paths = []
    for source in SOURCES:
        base = os.path.join(root, source)
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if name.endswith((".java", ".kt")):
                    paths.append(os.path.join(dirpath, name))
    yield from sorted(paths)


def strip_literals_argument(argument: str) -> str:
    """`activity.packageName + "_preferences"` and locals stay unresolved."""
    return argument.replace(" ", "")


def collect_global_constants(root: str):
    """name -> value, but only where every definition agrees. A name defined with
    two different values (PREFS is one per feature) has no global meaning."""
    values: dict[str, set[str]] = {}
    for path in java_files(root):
        text = open(path, encoding="utf-8", errors="replace").read()
        for name, value in CONST_RE.findall(text):
            values.setdefault(name, set()).add(value)
    return {name: next(iter(found)) for name, found in values.items() if len(found) == 1}


def resolve(argument: str, file_consts: dict[str, str], global_consts: dict[str, str]) -> str:
    """Return the prefs file name, or '' when it cannot be resolved confidently."""
    literal = LITERAL_RE.match(argument.strip())
    if literal:
        return literal.group(1)
    arg = strip_literals_argument(argument)
    leaf = arg.rsplit(".", 1)[-1]
    if leaf in file_consts:          # a local/companion constant: authoritative
        return file_consts[leaf]
    if arg in file_consts:
        return file_consts[arg]
    if leaf in global_consts:        # shared constant defined elsewhere, unambiguous
        return global_consts[leaf]
    return ""


def collect_any_constants(root: str):
    """name -> value for every string constant, but only where all definitions
    agree. Used to resolve a SettingKey's file argument, which is often a generic
    name like FILE that the prefs-named CONST_RE would not match."""
    values: dict[str, set[str]] = {}
    for path in java_files(root):
        text = open(path, encoding="utf-8", errors="replace").read()
        for name, value in ANY_CONST_RE.findall(text):
            values.setdefault(name, set()).add(value)
    return {name: next(iter(found)) for name, found in values.items() if len(found) == 1}


def resolve_any(argument: str, file_consts: dict[str, str], global_consts: dict[str, str]) -> str:
    literal = LITERAL_RE.match(argument.strip())
    if literal:
        return literal.group(1)
    arg = strip_literals_argument(argument)
    leaf = arg.rsplit(".", 1)[-1]
    for candidate in (leaf, arg):
        if candidate in file_consts:
            return file_consts[candidate]
    if leaf in global_consts:
        return global_consts[leaf]
    return ""


def scan_prefs(root: str):
    global_consts = collect_global_constants(root)
    any_global_consts = collect_any_constants(root)
    files: dict[str, dict] = {}
    unresolved: dict[str, int] = {}

    def entry_for(name: str) -> dict:
        return files.setdefault(name, {"calls": 0, "flavors": set(), "defined_in": ""})

    for path in java_files(root):
        rel = os.path.relpath(path, root)
        flavor = rel.split(os.sep)[2]
        text = open(path, encoding="utf-8", errors="replace").read()
        file_consts = dict(CONST_RE.findall(text))
        file_any_consts = dict(ANY_CONST_RE.findall(text))
        for argument in CALL_RE.findall(text):
            name = resolve(argument, file_consts, global_consts)
            if not name:
                key = strip_literals_argument(argument)
                unresolved[key] = unresolved.get(key, 0) + 1
                continue
            entry = entry_for(name)
            entry["calls"] += 1
            entry["flavors"].add(flavor)
        # Files named on a SettingKey: an area already moved onto SettingsStore.
        for argument in SETTINGKEY_RE.findall(text):
            name = resolve_any(argument, file_any_consts, any_global_consts)
            if name:
                entry = entry_for(name)
                entry["calls"] += 1
                entry["flavors"].add(flavor)
                if not entry["defined_in"]:
                    entry["defined_in"] = rel
    # Where each name is defined: the file whose constant resolved to it.
    for path in java_files(root):
        rel = os.path.relpath(path, root)
        text = open(path, encoding="utf-8", errors="replace").read()
        for value in CONST_RE.findall(text):
            if value[1] in files and not files[value[1]]["defined_in"]:
                files[value[1]]["defined_in"] = rel
    return files, unresolved


def scan_room(root: str):
    databases = []
    for path in java_files(root):
        text = open(path, encoding="utf-8", errors="replace").read()
        if not DATABASE_RE.search(text):
            continue
        rel = os.path.relpath(path, root)
        entities = ENTITIES_RE.search(text)
        names = []
        if entities:
            names = re.findall(r"([A-Za-z_][A-Za-z0-9_]*)::class", entities.group(1))
        databases.append((os.path.basename(path).rsplit(".", 1)[0], rel, names))
    return databases


def render(root: str) -> str:
    files, unresolved = scan_prefs(root)
    databases = scan_room(root)

    out = []
    out.append("# Settings inventory")
    out.append("")
    out.append("Generated by `scripts/settings_inventory.py` (plan task T2.1).")
    out.append("Do not edit by hand: run `scripts/settings_inventory.py --write`.")
    out.append("`SettingsInventoryCompletenessTest` runs `--check`, so adding a")
    out.append("prefs file or a Room database without regenerating turns CI red.")
    out.append("")
    out.append("Not derived here, on purpose: per-key defaults and writer/reader sets")
    out.append("(that is dataflow, not a scan), and the `Natives.get*/set*` surface")
    out.append("(358 methods whose setting/data/command split needs a human pass).")
    out.append("")
    out.append("`Defined in` names one file that declares a constant resolving to that")
    out.append("prefs file (the first alphabetically, so the doc is reproducible); a name")
    out.append("may be declared in several places.")
    out.append("")
    out.append(f"## SharedPreferences files ({len(files)})")
    out.append("")
    out.append("| File | Defined in | References | Flavours |")
    out.append("|---|---|---:|---|")
    for name in sorted(files):
        entry = files[name]
        flavors = ", ".join(sorted(entry["flavors"]))
        defined = entry["defined_in"] or "inline literal"
        out.append(f"| `{name}` | `{defined}` | {entry['calls']} | {flavors} |")
    out.append("")

    if unresolved:
        out.append("### Unresolved `getSharedPreferences` arguments")
        out.append("")
        out.append("Arguments that are locals, parameters or concatenations; they need")
        out.append("a manual look before T2.2 routes them through `SettingsStore`.")
        out.append("")
        out.append("| Argument | Call sites |")
        out.append("|---|---:|")
        for argument in sorted(unresolved):
            out.append(f"| `{argument}` | {unresolved[argument]} |")
        out.append("")

    out.append(f"## Room databases ({len(databases)})")
    out.append("")
    if databases:
        out.append("| Database | Defined in | Entities |")
        out.append("|---|---|---|")
        for name, path, entities in databases:
            joined = ", ".join(f"`{e}`" for e in entities) or "(none declared)"
            out.append(f"| `{name}` | `{path}` | {joined} |")
    else:
        out.append("_none found._")
    out.append("")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--stdout", action="store_true")
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--doc", default=None)
    args = parser.parse_args()

    if not (args.write or args.check or args.stdout):
        parser.error("choose one of --write, --check, --stdout")

    doc_path = args.doc or os.path.join(args.root, DOC_RELATIVE)
    generated = render(args.root)

    if args.stdout:
        print(generated)
        return 0

    if args.write:
        os.makedirs(os.path.dirname(doc_path), exist_ok=True)
        with open(doc_path, "w", encoding="utf-8") as handle:
            handle.write(generated)
        print(f"wrote {os.path.relpath(doc_path, args.root)}")
        return 0

    try:
        committed = open(doc_path, encoding="utf-8").read()
    except FileNotFoundError:
        sys.exit(f"settings inventory not found: {doc_path} (run with --write)")

    if committed.strip() != generated.strip():
        print("settings inventory is out of date — run scripts/settings_inventory.py --write", file=sys.stderr)
        return 1
    print("settings inventory is up to date")
    return 0


if __name__ == "__main__":
    sys.exit(main())
