#!/usr/bin/env python3
"""Facade-contract compatibility gate (MACH P1, API management).

Compares two generated openapi.json contracts (base vs head) and FAILS on breaking
changes unless x-darpan-contract-version was bumped:
  - removed method
  - removed required request param, or a param made newly required
  - removed response field (out-fields are consumer-visible)
  - type change on any request/response field

Additive changes (new methods, new optional params, new out-fields) always pass —
that is the additive-only deprecation policy.

Usage:
  python3 scripts/check_contract_compat.py <base-openapi.json> <head-openapi.json>

CI usage (PRs): git show "origin/${BASE_REF}:docs/api-contract/openapi.json" > /tmp/base.json
then compare against the checked-out head file. Exits 1 on unversioned breakage.
"""
import json
import sys


def flatten(schema: dict, schemas: dict, prefix: str = "", depth: int = 0) -> dict:
    """field path -> coarse type string. Follows $ref one level deep at a time."""
    out: dict[str, str] = {}
    if depth > 12 or not isinstance(schema, dict):
        return out
    if "$ref" in schema:
        ref = schema["$ref"].rsplit("/", 1)[-1]
        return flatten(schemas.get(ref, {}), schemas, prefix, depth + 1)
    stype = schema.get("type")
    if isinstance(stype, list):
        stype = "/".join(sorted(str(t) for t in stype))
    if "oneOf" in schema and not stype:
        stype = "oneOf"
    out[prefix or "."] = str(stype)
    for name, sub in (schema.get("properties") or {}).items():
        out.update(flatten(sub, schemas, f"{prefix}.{name}" if prefix else name, depth + 1))
    if schema.get("items"):
        out.update(flatten(schema["items"], schemas, f"{prefix}[]", depth + 1))
    return out


def contract_surface(spec: dict) -> dict:
    schemas = spec["components"]["schemas"]
    surface = {}
    for method, pascal in spec["x-darpan-methods"].items():
        params = schemas.get(f"{pascal}Params", {})
        result = schemas.get(f"{pascal}Result", {})
        surface[method] = {
            "required": sorted(params.get("required") or []),
            "params": flatten(params, schemas),
            "result": flatten(result, schemas),
        }
    return surface


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base_spec = json.load(open(sys.argv[1]))
    head_spec = json.load(open(sys.argv[2]))
    base_ver = base_spec["info"].get("x-darpan-contract-version", 0)
    head_ver = head_spec["info"].get("x-darpan-contract-version", 0)

    base = contract_surface(base_spec)
    head = contract_surface(head_spec)

    breaks: list[str] = []
    for method, b in base.items():
        h = head.get(method)
        if h is None:
            breaks.append(f"method removed: {method}")
            continue
        for field, btype in b["params"].items():
            htype = h["params"].get(field)
            if htype is None:
                # removed request param is breaking only if it was required; optional-param
                # removal still logged as breaking (callers may send it and get -32602).
                breaks.append(f"{method}: request param removed: {field}")
            elif htype != btype and "None" not in (htype, btype):
                breaks.append(f"{method}: request param type changed: {field} {btype} -> {htype}")
        newly_required = set(h["required"]) - set(b["required"])
        if newly_required:
            breaks.append(f"{method}: params made newly required: {sorted(newly_required)}")
        for field, btype in b["result"].items():
            htype = h["result"].get(field)
            if htype is None:
                breaks.append(f"{method}: response field removed: {field}")
            elif htype != btype and "None" not in (htype, btype):
                breaks.append(f"{method}: response field type changed: {field} {btype} -> {htype}")

    added = sorted(set(head) - set(base))
    if added:
        print(f"additive: {len(added)} new methods (OK): {added}")

    if breaks:
        if head_ver > base_ver:
            print(f"BREAKING changes present but contract version bumped {base_ver} -> {head_ver} (accepted):")
            for b in breaks:
                print(f"  - {b}")
            return 0
        print(f"BREAKING contract changes WITHOUT an x-darpan-contract-version bump (still {head_ver}):")
        for b in breaks:
            print(f"  - {b}")
        print("Either restore compatibility (additive-only policy) or consciously bump "
              "ApiContractGenerator.CONTRACT_VERSION and regenerate.")
        return 1

    print(f"contract compatible: {len(base)} base methods preserved, version {head_ver}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
