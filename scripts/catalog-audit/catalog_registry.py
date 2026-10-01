"""Read the backend-owned category contract; never infer an assignment or touch a DB."""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
from types import MappingProxyType


VERSION = 'catalog-category-registry-v2'
REGISTRY_PATH = (Path(__file__).resolve().parents[2]
                 / 'backend/src/main/resources/catalog/category-registry-v2.tsv')
HEADER = ('code', 'name', 'category_kind', 'device_family', 'counts_as_phone',
          'counts_as_device', 'counts_as_additional_revenue', 'scope', 'device_type',
          'device_brand_match', 'proposal_keys', 'confirmed_functions', 'confirmed_conditions')
KINDS = {'DEVICE', 'ACCESSORY', 'SERVICE', 'WARRANTY', 'PROTECTION', 'OTHER', 'EXCLUDED'}
FAMILIES = {'IPHONE', 'SAMSUNG', 'PODS_WATCH', 'IPAD_MAC', 'OTHER', 'NONE'}
SCOPES = {'STANDARD', 'LEGACY', 'DEFERRED', 'TECHNICAL'}
DEVICE_TYPES = {'TABLET', 'LAPTOP', 'WATCH'}
DOMAINS = {'DEVICE', 'SERVICE', 'ITEM'}
CONFIRMED_CONDITIONS = frozenset({'NEW', 'USED', 'ASIS', 'NOT_APPLICABLE'})


def _token(value):
    return bool(re.fullmatch(r'[A-Z][A-Z0-9_]+', value))


def _tokens(value):
    items = value.split('|') if value else []
    if len(items) != len(set(items)) or any(not _token(item) for item in items):
        raise ValueError('Invalid or duplicate registry token')
    return frozenset(items)


def _flag(value):
    if value not in {'true', 'false'}:
        raise ValueError('Invalid registry boolean')
    return value == 'true'


@dataclass(frozen=True)
class CategoryDefinition:
    code: str
    name: str
    category_kind: str
    device_family: str
    counts_as_phone: bool
    counts_as_device: bool
    counts_as_additional_revenue: bool
    scope: str
    device_type: str
    device_brand_match: str
    proposal_keys: frozenset
    confirmed_functions: frozenset
    confirmed_conditions: frozenset


class CatalogRegistry:
    """Target metadata and proposal maps, not a production-readiness flag."""
    def __init__(self, content):
        lines = content.splitlines()
        if (len(lines) < 3 or lines[0] != '# ' + VERSION
                or lines[1].split('\t') != list(HEADER)):
            raise ValueError('Unsupported catalog registry version or header')
        definitions, devices, proposals = {}, {}, {}
        for number, line in enumerate(lines[2:], 3):
            cells = line.split('\t')
            if len(cells) != len(HEADER) or any(cell != cell.strip() for cell in cells):
                raise ValueError(f'Invalid registry columns/whitespace at line {number}')
            cells = ["" if index >= 8 and value == '-' else value
                     for index, value in enumerate(cells)]
            (code, name, kind, family, phone, device, additional, scope, device_type,
             brand, raw_proposals, raw_functions, raw_conditions) = cells
            if not _token(code) or not name or code in definitions:
                raise ValueError('Invalid or duplicate category: ' + code)
            if kind not in KINDS or family not in FAMILIES or scope not in SCOPES:
                raise ValueError('Unknown registry enum: ' + code)
            phone, device, additional = map(_flag, (phone, device, additional))
            if (device != (kind == 'DEVICE') or (phone and (not device or family not in {'IPHONE', 'SAMSUNG'} and not (code == 'PHONE_OTHER' and family == 'OTHER')))
                    or additional != (kind in {'ACCESSORY', 'SERVICE', 'WARRANTY', 'PROTECTION'})
                    or (scope == 'DEFERRED' and kind != 'SERVICE')):
                raise ValueError('Contradictory financial flags: ' + code)
            if (bool(device_type) != bool(brand) or (device_type and (
                    not device or device_type not in DEVICE_TYPES or brand not in {'APPLE', 'SAMSUNG', 'OTHER'}))):
                raise ValueError('Invalid device mapping: ' + code)
            if device_type:
                key = (device_type, brand)
                if key in devices:
                    raise ValueError('Duplicate device mapping')
                devices[key] = code
            functions = _tokens(raw_functions)
            conditions = _tokens(raw_conditions)
            if not conditions <= CONFIRMED_CONDITIONS:
                raise ValueError('Invalid confirmed conditions')
            keys = raw_proposals.split('|') if raw_proposals else []
            for key in keys:
                parts = key.split(':')
                if (len(parts) != 2 or parts[0] not in DOMAINS or not _token(parts[1])
                        or parts[1] not in functions or key in proposals):
                    raise ValueError('Invalid or duplicate proposal key: ' + key)
                proposals[key] = code
            definitions[code] = CategoryDefinition(code, name, kind, family, phone, device,
                additional, scope, device_type, brand, frozenset(keys), functions, conditions)
        required = {(kind, brand) for kind in DEVICE_TYPES for brand in ('APPLE', 'OTHER')}
        required.add(('WATCH', 'SAMSUNG'))
        if not required <= devices.keys():
            raise ValueError('Missing device mapping')
        self.definitions = MappingProxyType(definitions)
        self.device_mappings = MappingProxyType(devices)
        self.proposal_mappings = MappingProxyType(proposals)
        self.sha256 = hashlib.sha256(content.encode('utf-8')).hexdigest()

    def require(self, code):
        if not isinstance(code, str) or code not in self.definitions:
            raise ValueError('Unknown catalog category: ' + str(code))
        return self.definitions[code]

    def proposal_categories(self, domain):
        if domain not in DOMAINS:
            raise ValueError('Unknown proposal domain: ' + str(domain))
        return {key.split(':', 1)[1]: code for key, code in self.proposal_mappings.items()
                if key.startswith(domain + ':')}

    def function_categories(self):
        result = {}
        for entry in self.definitions.values():
            for function in entry.confirmed_functions:
                result.setdefault(function, set()).add(entry.code)
        return result

    def device_category(self, device_type, brand):
        if device_type not in DEVICE_TYPES:
            raise ValueError('Unknown device type: ' + str(device_type))
        brand = (brand or '').strip().upper()
        if brand in {'', 'UNKNOWN', 'OTHER'}:
            return None
        return self.device_mappings.get((device_type, brand), self.device_mappings[(device_type, 'OTHER')])


def load_registry():
    return CatalogRegistry(REGISTRY_PATH.read_bytes().decode('utf-8'))


REGISTRY = load_registry()
