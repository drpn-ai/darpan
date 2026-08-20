#!/usr/bin/env python3
"""Change-based test selection for the darpan component.

Maps a set of changed files onto the smallest set of test classes that could
plausibly break, so a developer can run those instead of the whole suite.

The mapping is deliberately OVER-inclusive. A selector that misses a real
breakage is worse than no selector at all, so every rule below errs toward
selecting more, and anything it does not recognise escalates to ALL.

Rules, by changed path:

  src/test/groovy/**/FooTests.groovy
      The test class itself.

  src/test/groovy/**/FooTestSupport.groovy
      Every test class that references the support class (these are the
      shared Moqui fixtures — changing one reaches all of its users).

  src/main/groovy/**/Foo.groovy
      Every test that references `Foo`, plus every test that references any
      main class which transitively references `Foo`. A change to a leaf
      helper like ValueSupport therefore fans out widely, which is correct.

      Many main classes are never named by a test because Moqui reaches them
      through service XML instead — either `location=".../Foo.groovy"` on a
      script-backed service, or an inline `from="pkg.Foo.method(...)"`. Those
      edges are followed too, so changing such a class selects the tests that
      invoke the owning service by name.

  service/**/Xyz Services.xml
      Tests naming any `verb#Noun` declared in that file, or naming the file's
      base name (services are addressed as `pkg.FileBaseName.verb#Noun`).
      Service XML is only reachable through a booted Moqui, so this also
      escalates to the whole smoke pool when a service is added or removed.

  entity/**/*.xml
      Tests referencing any entity-name declared in the file. Entity XML also
      shapes the H2 schema every smoke test loads, so a structural change
      (entity/field/relationship added or removed) escalates to the smoke pool.

  data/**/*.xml, screen/**/*.xml
      The smoke pool (seed data and screens are only exercised through a
      booted Moqui).

  component.xml, MoquiConf.xml, build.gradle, scripts/**, anything unrecognised
      ALL.

Usage:
  python3 scripts/select_tests.py --base origin/main
  python3 scripts/select_tests.py --changed service/facade/AuthFacadeServices.xml
  python3 scripts/select_tests.py --base HEAD~1 --explain
  python3 scripts/select_tests.py --self-check

Output formats:
  --format list    one FQCN per line (default); the single token ALL means
                   "no useful narrowing, run everything"
  --format gradle  ready-to-paste `--tests a --tests b` argument string
  --format json    {"all": bool, "unit": [...], "smoke": [...], "why": {...}}
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# Only ever parses this component's own Gradle test-result XML out of build/, so the input is
# build output rather than anything untrusted. Still prefer defusedxml when it happens to be
# installed; the script must stay runnable on a bare python3, so the fallback is not optional.
try:
    from defusedxml import ElementTree as ET  # type: ignore
except ImportError:
    import xml.etree.ElementTree as ET

COMPONENT_ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = COMPONENT_ROOT / "src" / "test" / "groovy"
MAIN_ROOT = COMPONENT_ROOT / "src" / "main" / "groovy"
SERVICE_ROOT = COMPONENT_ROOT / "service"

# A test class that touches any of these boots a real ExecutionContextFactory and
# must run in its own JVM. build.gradle derives the smoke pool from the same
# markers, so the two never drift.
MOQUI_BOOT_MARKERS = ("initMoqui(", "Moqui.dynamicInit", "ExecutionContextFactoryImpl")

# Paths whose blast radius is the entire suite.
GLOBAL_IMPACT = ("component.xml", "MoquiConf.xml", "build.gradle", "build.xml")
GLOBAL_IMPACT_DIRS = ("lib/", "template/", "theme-library/")

# Never affects a test outcome. scripts/ is developer tooling only — nothing in build.gradle
# invokes it — so editing this selector does not itself force a full run.
# NB: docs/ is deliberately absent — docs/api-contract/openapi.json is a committed snapshot
# that a test asserts against, so it is handled as a real input further down.
IGNORED_DIRS = ("scripts/", "moqui_logs/", "build/")
IGNORED_SUFFIXES = (".pyc", ".md", ".log", ".swp", ".orig", ".rej")
IGNORED_PARTS = ("__pycache__", ".DS_Store")

# How many hops of reverse-reference to follow from a changed main class.
# See Index.expand_main_symbols for why this is capped rather than exhaustive.
DEFAULT_DEPTH = 2

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
MYSQL_TAG = re.compile(r'@Tag\(\s*"mysql"\s*\)')
SERVICE_DECL = re.compile(r'<service\s+verb="([^"]+)"\s+noun="([^"]+)"')
ENTITY_DECL = re.compile(r'entity-name="([^"]+)"')


def measured_times() -> dict:
    """fqcn -> seconds, from the last run's JUnit XML. Empty if the suite has never run.

    This is in-JVM execution time only. It does not include the JVM start each class pays
    under forkEvery = 1, so treat it as a lower bound on what a selection actually saves.
    """
    results = COMPONENT_ROOT / "build" / "test-results"
    out: dict[str, float] = {}
    # Any of the three task result dirs will do, and a class may appear in more than one; keep the
    # fastest reading, since the slower one is usually a run that was competing with other forks.
    for task_dir in ("unitTest", "smokeTest", "test"):
        for xml in (results / task_dir).glob("TEST-*.xml"):
            try:
                root = ET.parse(xml).getroot()
            except ET.ParseError:
                continue
            name, time = root.get("name"), root.get("time")
            if name and time:
                out[name] = min(float(time), out.get(name, float("inf")))
    return out


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def fqcn_for(path: Path, root: Path) -> str:
    return str(path.relative_to(root).with_suffix("")).replace(os.sep, ".")


class Index:
    """Static reference index over the component's Groovy sources."""

    def __init__(self) -> None:
        self.tests: dict[str, dict] = {}        # fqcn -> {path, idents, raw, smoke}
        self.supports: dict[str, Path] = {}     # simple name -> path (test-tree helpers)
        self.main: dict[str, Path] = {}         # simple name -> path
        self.main_idents: dict[str, set] = {}   # simple name -> identifiers it mentions
        self.services: dict[str, dict] = {}     # service rel path -> {names, stem, symbols}

        for path in sorted(MAIN_ROOT.rglob("*.groovy")):
            name = path.stem
            self.main[name] = path
            self.main_idents[name] = set(IDENT.findall(read(path)))

        for path in sorted(SERVICE_ROOT.rglob("*.xml")) if SERVICE_ROOT.is_dir() else []:
            text = read(path)
            idents = set(IDENT.findall(text))
            self.services[str(path.relative_to(COMPONENT_ROOT))] = {
                "names": {f"{v}#{n}" for v, n in SERVICE_DECL.findall(text)},
                "stem": path.stem,
                # main classes this service file reaches, via `location=".../Foo.groovy"`
                # or an inline `from="pkg.Foo.method(...)"` call
                "symbols": idents & set(self.main),
            }

        for path in sorted(TEST_ROOT.rglob("*.groovy")):
            raw = read(path)
            if path.stem.endswith(("Test", "Tests")):
                # @Tag("mysql") classes are excluded from the `test` task and only run under the
                # opt-in mysqlTest task. Naming one in `--tests` would match the class, then have
                # every method filtered out by the tag — which fails the run rather than skipping.
                if MYSQL_TAG.search(raw):
                    continue
                self.tests[fqcn_for(path, TEST_ROOT)] = {
                    "path": path,
                    "idents": set(IDENT.findall(raw)),
                    "raw": raw,
                    "smoke": any(m in raw for m in MOQUI_BOOT_MARKERS),
                }
            else:
                self.supports[path.stem] = path

    @property
    def smoke_tests(self) -> set:
        return {f for f, t in self.tests.items() if t["smoke"]}

    @property
    def contract_tests(self) -> set:
        """Tests that assert against the committed openapi.json rather than against code."""
        return self.tests_containing({"openapi.json"})

    def expand_main_symbols(self, seeds: set, depth: int = DEFAULT_DEPTH) -> set:
        """Add main classes that reference any seed class, up to `depth` hops (0 = unlimited).

        Depth matters more than it looks. Reverse-reference closure over this
        codebase saturates fast, because hub classes like ReconciliationServices
        mention nearly everything: from one leaf class the reachable test count
        runs 19 -> 28 -> 57 -> 63 -> 72 over hops 1..5. Past hop 2 the selection
        stops being a selection. DEFAULT_DEPTH is the deliberate cutoff, and the
        full suite before pushing is what covers the tail it gives up.
        """
        seen = set(seeds)
        frontier = set(seeds)
        hop = 0
        while frontier and (depth == 0 or hop < depth):
            hop += 1
            nxt = {n for n, idents in self.main_idents.items()
                   if n not in seen and idents & frontier}
            seen |= nxt
            frontier = nxt
        return seen

    def tests_mentioning(self, symbols: set) -> set:
        if not symbols:
            return set()
        return {f for f, t in self.tests.items() if t["idents"] & symbols}

    def tests_containing(self, needles: set) -> set:
        """Raw substring match, for tokens like `create#RuleSetRun` that are not identifiers."""
        if not needles:
            return set()
        return {f for f, t in self.tests.items() if any(n in t["raw"] for n in needles)}

    def tests_for_service_file(self, rel: str) -> set:
        """Tests that invoke any service declared in this file, by name or by file base name."""
        svc = self.services.get(rel)
        if not svc:
            return set()
        return self.tests_containing(svc["names"]) | self.tests_mentioning({svc["stem"]})

    def service_files_using(self, symbols: set) -> set:
        """Service XML files that reach any of these main classes."""
        return {rel for rel, svc in self.services.items() if svc["symbols"] & symbols}

    def tests_reaching(self, seeds: set, depth: int = DEFAULT_DEPTH) -> set:
        """Every test a set of main classes can affect, direct or through service XML."""
        reached = self.expand_main_symbols(seeds, depth)
        tests = self.tests_mentioning(reached)
        for rel in self.service_files_using(reached):
            tests |= self.tests_for_service_file(rel)
        return tests


class Selection:
    def __init__(self) -> None:
        self.all = False
        self.tests: set = set()
        self.why: dict[str, list] = {}
        self.all_reasons: list = []

    def add(self, tests: set, reason: str) -> None:
        # `tests` is always drawn from the index except on the direct-edit path, where a
        # renamed or deleted test file yields an FQCN with no class behind it. Passing that
        # to `--tests` fails the build with "no tests found", so drop it here instead.
        for fqcn in tests:
            self.why.setdefault(fqcn, [])
            if reason not in self.why[fqcn]:
                self.why[fqcn].append(reason)
        self.tests |= tests

    def escalate(self, reason: str) -> None:
        self.all = True
        if reason not in self.all_reasons:
            self.all_reasons.append(reason)


def structural_xml_change(rel: str, base: str | None) -> bool:
    """True when a diff adds/removes a declaration line, vs only editing attributes inside one.

    Without a base ref we cannot tell, so we assume structural (the safe answer).
    """
    if not base:
        return True
    try:
        diff = subprocess.run(
            ["git", "diff", "--unified=0", base, "--", rel],
            cwd=COMPONENT_ROOT, capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError:
        return True
    for line in diff.splitlines():
        if line.startswith(("+++", "---")):
            continue
        if line.startswith(("+", "-")) and re.search(r"<(service|entity|view-entity|relationship|field)\b", line):
            return True
    return False


def select(changed: list, index: Index, base: str | None, depth: int = DEFAULT_DEPTH) -> Selection:
    sel = Selection()
    main_seeds: set = set()

    for rel in changed:
        path = COMPONENT_ROOT / rel
        name = os.path.basename(rel)

        if (rel.startswith(IGNORED_DIRS) or rel.endswith(IGNORED_SUFFIXES)
                or any(p in rel for p in IGNORED_PARTS)):
            continue

        if name in GLOBAL_IMPACT or rel.startswith(GLOBAL_IMPACT_DIRS):
            sel.escalate(f"{rel} (global build/runtime config)")

        elif rel.startswith("src/test/groovy/"):
            stem = Path(rel).stem
            if stem.endswith(("Test", "Tests")):
                fqcn = fqcn_for(path, TEST_ROOT)
                if fqcn in index.tests:
                    sel.add({fqcn}, f"{rel} (test edited directly)")
                # else: deleted or renamed away — nothing to run for it
            else:
                users = index.tests_mentioning({stem})
                sel.add(users, f"{rel} (shared test fixture)")

        elif rel.startswith("src/main/groovy/"):
            main_seeds.add(Path(rel).stem)

        elif rel.startswith("service/"):
            sel.add(index.tests_for_service_file(rel), f"{rel} (service names / file)")
            # The committed openapi.json is generated from these definitions and guarded by a
            # drift gate, so any service edit can fail that test on its own.
            sel.add(index.contract_tests, f"{rel} (regenerates the API contract)")
            if structural_xml_change(rel, base):
                sel.add(index.smoke_tests, f"{rel} (service added/removed -> smoke pool)")

        elif rel.startswith("entity/"):
            text = read(path)
            sel.add(index.tests_mentioning(set(ENTITY_DECL.findall(text))), f"{rel} (entity names)")
            if structural_xml_change(rel, base):
                sel.add(index.smoke_tests, f"{rel} (schema change -> smoke pool)")

        elif rel.startswith("screen/"):
            sel.add(index.smoke_tests, f"{rel} (screen -> smoke pool)")

        elif rel.startswith("data/") or rel.startswith("src/test/resources/"):
            # Fixtures are loaded by name, so a test that reads one names the file.
            sel.add(index.tests_containing({name}), f"{rel} (fixture referenced by name)")
            if rel.startswith("data/"):
                # Everything under data/ is loaded through a booted Moqui, including the
                # darpan-fixture set, so the smoke pool is in scope regardless.
                sel.add(index.smoke_tests, f"{rel} (loaded at ECFI init -> smoke pool)")

        elif rel.startswith("docs/api-contract/"):
            sel.add(index.contract_tests, f"{rel} (committed contract snapshot)")

        elif rel.startswith(("docs/", "README", "AGENTS.md", "SKILLS.md")):
            continue  # documentation cannot break a test

        else:
            sel.escalate(f"{rel} (unrecognised path)")

    if main_seeds:
        reached = index.expand_main_symbols(main_seeds, depth)
        via_svc = index.service_files_using(reached)
        sel.add(index.tests_reaching(main_seeds, depth),
                f"main classes {sorted(main_seeds)} "
                f"(+{len(reached - main_seeds)} within {depth or 'unlimited'} hops"
                + (f", via {len(via_svc)} service file(s)" if via_svc else "") + ")")

    return sel


def changed_from_git(base: str) -> list:
    def run(args):
        return subprocess.run(["git", *args], cwd=COMPONENT_ROOT,
                              capture_output=True, text=True, check=True).stdout.split("\n")

    prefix = subprocess.run(["git", "rev-parse", "--show-prefix"], cwd=COMPONENT_ROOT,
                            capture_output=True, text=True, check=True).stdout.strip()
    paths = set(run(["diff", "--name-only", base])) | set(run(["ls-files", "--others", "--exclude-standard"]))
    out = []
    for p in paths:
        if not p:
            continue
        if prefix and not p.startswith(prefix):
            continue  # outside this component
        out.append(p[len(prefix):] if prefix else p)
    return sorted(out)


def self_check(index: Index) -> int:
    """Report main classes no test mentions — selection cannot protect what nothing covers."""
    # depth 0 here on purpose: this asks "is this class covered at all?", which is a
    # different question from "how much should one edit run?"
    orphans = sorted(n for n in index.main if not index.tests_reaching({n}, depth=0))
    indirect = sorted(n for n in index.main
                      if n not in orphans and not index.tests_mentioning({n}))
    print(f"main classes: {len(index.main)}   test classes: {len(index.tests)} "
          f"({len(index.smoke_tests)} smoke / {len(index.tests) - len(index.smoke_tests)} unit)"
          f"   service files: {len(index.services)}")
    if indirect:
        print(f"\n{len(indirect)} main class(es) reached only through service XML "
              f"(covered, but only by smoke tests):")
        for n in indirect:
            print(f"  {n}")
    if orphans:
        print(f"\n{len(orphans)} main class(es) reach NO test — change-based selection "
              f"will pick nothing for these:")
        for n in orphans:
            print(f"  {n}")
        return 1
    print("\nevery main class reaches at least one test")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", help="git ref to diff against (e.g. origin/main, HEAD~1)")
    ap.add_argument("--changed", nargs="*", default=None,
                    help="explicit component-relative paths instead of a git diff")
    ap.add_argument("--depth", type=int, default=DEFAULT_DEPTH,
                    help=f"hops of reverse-reference to follow from a changed main class "
                         f"(default {DEFAULT_DEPTH}, 0 = unlimited)")
    ap.add_argument("--format", choices=["list", "gradle", "json"], default="list")
    ap.add_argument("--explain", action="store_true", help="print why each test was selected")
    ap.add_argument("--self-check", action="store_true", help="report main classes no test covers")
    args = ap.parse_args()

    index = Index()
    if args.self_check:
        return self_check(index)

    if args.changed is not None:
        changed = sorted(args.changed)
    elif args.base:
        changed = changed_from_git(args.base)
    else:
        ap.error("one of --base, --changed or --self-check is required")

    if not changed:
        if args.format == "json":
            print(json.dumps({"all": False, "unit": [], "smoke": [], "why": {}}))
        elif args.format == "list":
            print("", end="")
        return 0

    sel = select(changed, index, args.base, args.depth)
    unit = sorted(f for f in sel.tests if not index.tests[f]["smoke"])
    smoke = sorted(f for f in sel.tests if index.tests[f]["smoke"])

    if args.explain:
        print(f"changed files ({len(changed)}):", file=sys.stderr)
        for c in changed:
            print(f"  {c}", file=sys.stderr)
        if sel.all:
            print("\nESCALATED TO FULL SUITE:", file=sys.stderr)
            for r in sel.all_reasons:
                print(f"  {r}", file=sys.stderr)
        else:
            times = measured_times()
            if times:
                picked = sum(times.get(f, 0.0) for f in sel.tests)
                total = sum(times.values())
                pct = (picked / total * 100) if total else 0
                print(f"\nmeasured in-JVM time: {picked:.0f}s of {total:.0f}s ({pct:.0f}%) "
                      f"— excludes per-class JVM start, so the real saving is larger",
                      file=sys.stderr)
            print(f"\nselected {len(unit)} unit + {len(smoke)} smoke "
                  f"of {len(index.tests)} classes:", file=sys.stderr)
            for f in unit + smoke:
                kind = "smoke" if index.tests[f]["smoke"] else "unit "
                print(f"  [{kind}] {f}", file=sys.stderr)
                for r in sel.why[f]:
                    print(f"            <- {r}", file=sys.stderr)

    if args.format == "json":
        print(json.dumps({"all": sel.all, "unit": unit, "smoke": smoke,
                          "why": sel.why, "allReasons": sel.all_reasons}))
    elif args.format == "gradle":
        if sel.all:
            print("ALL")
        else:
            print(" ".join(f"--tests {f}" for f in unit + smoke))
    else:
        print("ALL" if sel.all else "\n".join(unit + smoke))
    return 0


if __name__ == "__main__":
    sys.exit(main())
