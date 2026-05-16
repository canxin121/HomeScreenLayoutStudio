#!/usr/bin/env python3
"""Reusable ordered keyword rule matching.

Rules are data, not launcher-layout logic. The engine intentionally stays small:
it loads a JSON rule set, checks records in rule order, and returns the first
matching rule name. Other tools can reuse it for any record shaped like a dict.
"""

from __future__ import annotations

import json
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


SUPPORTED_MATCH_TYPES = ("equals", "prefix", "contains")


@dataclass(frozen=True)
class MatchHit:
    category: str
    field: str | None = None
    match_type: str | None = None
    keyword: str | None = None


@dataclass(frozen=True)
class Rule:
    name: str
    equals: Mapping[str, tuple[str, ...]]
    prefix: Mapping[str, tuple[str, ...]]
    contains: Mapping[str, tuple[str, ...]]
    metadata: Mapping[str, Any]


class RuleSet:
    def __init__(
        self,
        *,
        name: str,
        fallback: str,
        field_aliases: Mapping[str, tuple[str, ...]],
        rules: tuple[Rule, ...],
        path: Path | None = None,
    ) -> None:
        self.name = name
        self.fallback = fallback
        self.field_aliases = dict(field_aliases)
        self.rules = rules
        self._rules_by_name = {rule.name: rule for rule in rules}
        self.path = path

    @classmethod
    def from_file(cls, path: Path) -> "RuleSet":
        with path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle, object_pairs_hook=OrderedDict)
        return cls.from_mapping(payload, path=path)

    @classmethod
    def from_mapping(cls, payload: Mapping[str, Any], path: Path | None = None) -> "RuleSet":
        if not isinstance(payload, Mapping):
            raise ValueError("Rule file root must be a JSON object.")

        name = str(payload.get("name") or (path.stem if path else "rules"))
        fallback = str(payload.get("fallback") or "待确认")
        field_aliases = _load_field_aliases(payload.get("field_aliases", {}))
        rules_payload = payload.get("rules")
        if not isinstance(rules_payload, list):
            raise ValueError("Rule file must contain a 'rules' array.")

        rules = tuple(_load_rule(rule_payload, index) for index, rule_payload in enumerate(rules_payload))
        return cls(name=name, fallback=fallback, field_aliases=field_aliases, rules=rules, path=path)

    def category_names(self) -> tuple[str, ...]:
        return tuple(rule.name for rule in self.rules)

    def rule_by_name(self, name: str) -> Rule | None:
        return self._rules_by_name.get(name)

    def classify(self, record: Mapping[str, Any]) -> str:
        return self.match(record).category

    def match(self, record: Mapping[str, Any]) -> MatchHit:
        for rule in self.rules:
            hit = self._match_rule(record, rule)
            if hit is not None:
                return hit
        return MatchHit(self.fallback)

    def _match_rule(self, record: Mapping[str, Any], rule: Rule) -> MatchHit | None:
        for match_type in SUPPORTED_MATCH_TYPES:
            field_keywords = getattr(rule, match_type)
            for field, keywords in field_keywords.items():
                value = _normalize(self._record_value(record, field))
                for keyword in keywords:
                    needle = _normalize(keyword)
                    if not needle:
                        continue
                    if _matches(value, needle, match_type):
                        return MatchHit(rule.name, field, match_type, keyword)
        return None

    def _record_value(self, record: Mapping[str, Any], field: str) -> Any:
        for key in (field, *self.field_aliases.get(field, ())):
            if key in record and record[key] not in (None, ""):
                return record[key]
        return ""


def _matches(value: str, keyword: str, match_type: str) -> bool:
    if match_type == "equals":
        return value == keyword
    if match_type == "prefix":
        return value.startswith(keyword)
    if match_type == "contains":
        return keyword in value
    raise ValueError(f"Unsupported match type: {match_type}")


def _normalize(value: Any) -> str:
    return str(value or "").replace("\u00a0", "").strip().lower()


def _load_field_aliases(value: Any) -> dict[str, tuple[str, ...]]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise ValueError("'field_aliases' must be a JSON object.")
    return {str(field): _load_strings(aliases, f"field_aliases.{field}") for field, aliases in value.items()}


def _load_rule(value: Any, index: int) -> Rule:
    if not isinstance(value, Mapping):
        raise ValueError(f"rules[{index}] must be a JSON object.")
    name = str(value.get("name") or "").strip()
    if not name:
        raise ValueError(f"rules[{index}] must have a non-empty 'name'.")

    known_keys = {"name", "equals", "prefix", "contains"}
    return Rule(
        name=name,
        equals=_load_match_map(value.get("equals", {}), f"rules[{index}].equals"),
        prefix=_load_match_map(value.get("prefix", {}), f"rules[{index}].prefix"),
        contains=_load_match_map(value.get("contains", {}), f"rules[{index}].contains"),
        metadata=OrderedDict((key, item) for key, item in value.items() if key not in known_keys),
    )


def _load_match_map(value: Any, label: str) -> dict[str, tuple[str, ...]]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise ValueError(f"'{label}' must be a JSON object.")
    return {str(field): _load_strings(keywords, f"{label}.{field}") for field, keywords in value.items()}


def _load_strings(value: Any, label: str) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        return (value,)
    if not isinstance(value, list):
        raise ValueError(f"'{label}' must be a string or string array.")
    result: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str):
            raise ValueError(f"'{label}[{index}]' must be a string.")
        if item:
            result.append(item)
    return tuple(result)
